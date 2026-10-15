/**
 * MSW handlers for PRD §7. They return the contract's shapes and error bodies exactly, and
 * emulate the backend's rules that the UI must handle: ownership (404 for others' data),
 * idempotency replay/conflict, limits, insufficient funds and the login lockout.
 */
import { delay, http, HttpResponse } from 'msw'
import type {
  AccountDto,
  AuthResponse,
  ErrorBody,
  ErrorCode,
  FieldError,
  HoldingDto,
  Page,
  PortfolioDto,
  PricePointDto,
  TransactionDto,
  TransferDto,
  UserDto,
} from '@/api/types'
import { Decimal } from '@/lib/money'
import { createDb, tickPrices, type MockDb } from './db'

const BASE = '/api/v1'
const ACCESS_TTL_SECONDS = 900
const REFRESH_COOKIE = 'qg_refresh'

export let db: MockDb = createDb()
export function resetMockDb() {
  db = createDb()
}

// ── helpers ───────────────────────────────────────────────────────────────────────────
function error(
  status: number,
  code: ErrorCode,
  message: string,
  request: Request,
  fieldErrors?: FieldError[],
  headers?: Record<string, string>,
) {
  const requestId = crypto.randomUUID()
  // Matches the live backend: fieldErrors is always present, X-Request-Id === requestId.
  const body: ErrorBody = {
    timestamp: new Date().toISOString(),
    status,
    code,
    message,
    path: new URL(request.url).pathname,
    fieldErrors: fieldErrors ?? [],
    requestId,
  }
  return HttpResponse.json(body, { status, headers: { 'X-Request-Id': requestId, ...headers } })
}

/** Mock access tokens look like `mock.<userId>.<nonce>`. Real ones are JWTs. */
function userFrom(request: Request): UserDto | null {
  const header = request.headers.get('Authorization') ?? ''
  // Hybrid mode: a real JWT from the live auth API is mapped onto the mock demo user so
  // not-yet-live endpoints still have data. Never in full-mock mode or tests.
  if (
    import.meta.env.VITE_MOCKS_HYBRID === 'true' &&
    /^Bearer [\w-]+\.[\w-]+\.[\w-]+$/.test(header)
  ) {
    return db.users.get('demo@quantgen.dev')?.user ?? null
  }
  const m = /^Bearer mock\.([0-9a-f-]{36})\.[0-9a-f-]+$/.exec(header)
  if (!m) return null
  for (const { user } of db.users.values()) if (user.id === m[1]) return user
  return null
}

function issue(user: UserDto): HttpResponse<AuthResponse> {
  const body: AuthResponse = {
    accessToken: `mock.${user.id}.${crypto.randomUUID()}`,
    expiresIn: ACCESS_TTL_SECONDS,
    user,
  }
  // HttpOnly can't be emulated by MSW; the real backend sets HttpOnly; Secure; SameSite=Strict.
  return HttpResponse.json(body, {
    headers: {
      'Set-Cookie': `${REFRESH_COOKIE}=mock-refresh.${user.id}; Path=/; SameSite=Strict`,
    },
  })
}

/** Request amounts: at most 2 decimals (the backend returns 400 otherwise). */
const isMoney = (v: unknown): v is string =>
  typeof v === 'string' && /^\d{1,15}(\.\d{1,2})?$/.test(v)
const IST_DATE = /^\d{4}-\d{2}-\d{2}$/
/** yyyy-MM-dd of an instant in Asia/Kolkata. */
const istDate = (iso: string) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date(iso))
const last4 = (n: string) => `\u2022\u2022\u2022\u2022${n.slice(-4)}`
const DAILY_LIMIT = new Decimal(500000)
const ownAccounts = (userId: string) => [...db.accounts.values()].filter((a) => a.userId === userId)
const publicAccount = ({ userId: _u, ...a }: AccountDto & { userId: string }): AccountDto => a

function paginate<T>(items: T[], url: URL): Page<T> {
  const page = Math.max(0, Number.parseInt(url.searchParams.get('page') ?? '0', 10) || 0)
  const size = Math.min(
    100,
    Math.max(1, Number.parseInt(url.searchParams.get('size') ?? '20', 10) || 20),
  )
  return {
    content: items.slice(page * size, page * size + size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
  }
}

/** Replay / conflict semantics of F-PAY-2. Returns a response to short-circuit, or a saver. */
function idempotent(request: Request, userId: string, body: unknown) {
  const key = request.headers.get('Idempotency-Key')
  if (!key || !/^[0-9a-f-]{36}$/i.test(key)) {
    return {
      early: error(400, 'VALIDATION_FAILED', 'Idempotency-Key header is required', request, [
        { field: 'Idempotency-Key', message: 'must be a UUID' },
      ]),
      save: () => {},
    }
  }
  const id = `${userId}:${key}`
  const hash = JSON.stringify(body)
  const prior = db.idempotency.get(id)
  if (prior) {
    return {
      early:
        prior.hash === hash
          ? HttpResponse.json(prior.body as object, {
              status: prior.status,
              headers: { 'Idempotent-Replayed': 'true' },
            })
          : error(
              409,
              'IDEMPOTENCY_CONFLICT',
              'Idempotency key reused with a different body',
              request,
            ),
      save: () => {},
    }
  }
  return {
    early: null,
    save: (status: number, resBody: unknown) =>
      db.idempotency.set(id, { hash, status, body: resBody }),
  }
}

function postLine(
  account: AccountDto & { userId: string },
  direction: TransactionDto['direction'],
  amount: Decimal,
  type: TransactionDto['type'],
  description: string,
  counterparty: string | null,
  journalEntryId: string,
): TransactionDto {
  const next =
    direction === 'CREDIT'
      ? new Decimal(account.balance).plus(amount)
      : new Decimal(account.balance).minus(amount)
  account.balance = next.toFixed(4)
  const tx: TransactionDto = {
    id: crypto.randomUUID(),
    journalEntryId,
    accountId: account.id,
    direction,
    amount: amount.toFixed(4),
    balanceAfter: account.balance,
    type,
    description,
    counterparty,
    createdAt: new Date().toISOString(),
  }
  db.transactions.unshift(tx)
  return tx
}

let lastTick = 0
function maybeTick() {
  if (Date.now() - lastTick >= 3000) {
    tickPrices(db)
    lastTick = Date.now()
  }
}

const PASSWORD_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,72}$/

// ── handlers ──────────────────────────────────────────────────────────────────────────
export const handlers = [
  // §7.2 Auth
  http.post(`${BASE}/auth/register`, async ({ request }) => {
    await delay(400)
    const b = (await request.json()) as Record<string, unknown>
    const fe: FieldError[] = []
    if (typeof b.fullName !== 'string' || b.fullName.trim().length < 2)
      fe.push({ field: 'fullName', message: 'must not be blank' })
    if (typeof b.email !== 'string' || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(b.email))
      fe.push({ field: 'email', message: 'must be a well-formed email address' })
    if (typeof b.phone !== 'string' || !/^\+?\d{10,15}$/.test(b.phone))
      fe.push({ field: 'phone', message: 'must be a valid phone number' })
    if (typeof b.password !== 'string' || !PASSWORD_RE.test(b.password))
      fe.push({ field: 'password', message: 'does not meet the password policy' })
    if (fe.length) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)

    const email = (b.email as string).toLowerCase()
    if (db.users.has(email))
      return error(409, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'email', message: 'is already registered' },
      ])

    const user: UserDto = {
      id: crypto.randomUUID(),
      fullName: (b.fullName as string).trim(),
      email,
      phone: b.phone as string,
      role: 'CUSTOMER',
      createdAt: new Date().toISOString(),
    }
    db.users.set(email, { user, password: b.password as string })
    // F-AUTH-5: new users get one SAVINGS account.
    const id = crypto.randomUUID()
    db.accounts.set(id, {
      id,
      userId: user.id,
      accountNumber: `5021${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`,
      type: 'SAVINGS',
      status: 'ACTIVE',
      balance: '0.0000',
      currency: 'INR',
      createdAt: user.createdAt,
    })
    return HttpResponse.json(user, { status: 201 })
  }),

  http.post(`${BASE}/auth/login`, async ({ request }) => {
    await delay(450)
    const b = (await request.json()) as Record<string, unknown>
    const email = typeof b.email === 'string' ? b.email.toLowerCase() : ''
    const now = Date.now()
    const window = (db.failedLogins.get(email) ?? []).filter((t) => now - t < 15 * 60_000)
    if (window.length >= 5) {
      const retry = Math.ceil(((window[0] ?? now) + 15 * 60_000 - now) / 1000)
      return error(429, 'RATE_LIMITED', 'Too many failed logins', request, undefined, {
        'Retry-After': String(retry),
      })
    }
    const entry = db.users.get(email)
    if (!entry || entry.password !== b.password) {
      const failures = [...window, now]
      db.failedLogins.set(email, failures)
      // Like the backend: the attempt that triggers the lock already returns 429.
      if (failures.length >= 5) {
        return error(429, 'RATE_LIMITED', 'Too many failed logins', request, undefined, {
          'Retry-After': String(15 * 60),
        })
      }
      return error(401, 'UNAUTHORIZED', 'Invalid email or password', request)
    }
    db.failedLogins.delete(email)
    return issue(entry.user)
  }),

  http.post(`${BASE}/auth/refresh`, async ({ request, cookies }) => {
    await delay(150)
    const m = /^mock-refresh\.([0-9a-f-]{36})$/.exec(cookies[REFRESH_COOKIE] ?? '')
    const entry = m && [...db.users.values()].find((u) => u.user.id === m[1])
    if (!entry) {
      return error(401, 'UNAUTHORIZED', 'Refresh token missing or invalid', request, undefined, {
        'Set-Cookie': `${REFRESH_COOKIE}=; Path=/; Max-Age=0; SameSite=Strict`,
      })
    }
    return issue(entry.user)
  }),

  http.post(`${BASE}/auth/logout`, async () => {
    await delay(120)
    return new HttpResponse(null, {
      status: 204,
      headers: { 'Set-Cookie': `${REFRESH_COOKIE}=; Path=/; Max-Age=0; SameSite=Strict` },
    })
  }),

  http.get(`${BASE}/users/me`, ({ request }) => {
    const user = userFrom(request)
    return user
      ? HttpResponse.json(user)
      : error(401, 'UNAUTHORIZED', 'Authentication required', request)
  }),

  // §7.3 Accounts
  http.get(`${BASE}/accounts`, async ({ request }) => {
    await delay(300)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    return HttpResponse.json(ownAccounts(user.id).map(publicAccount))
  }),

  http.post(`${BASE}/accounts`, async ({ request }) => {
    await delay(400)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    if (b.type !== 'SAVINGS' && b.type !== 'CHECKING')
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'type', message: 'must be SAVINGS or CHECKING' },
      ])
    if (ownAccounts(user.id).length >= 3)
      return error(422, 'LIMIT_EXCEEDED', 'A customer can hold at most 3 accounts', request)
    const acc = {
      id: crypto.randomUUID(),
      userId: user.id,
      accountNumber: `5021${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`,
      type: b.type === 'CHECKING' ? ('CHECKING' as const) : ('SAVINGS' as const),
      status: 'ACTIVE' as const,
      balance: '0.00',
      currency: 'INR' as const,
      createdAt: new Date().toISOString(),
    }
    db.accounts.set(acc.id, acc)
    return HttpResponse.json(publicAccount(acc), { status: 201 })
  }),

  http.get(`${BASE}/accounts/:id`, ({ request, params }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const acc = db.accounts.get(String(params.id))
    if (!acc || acc.userId !== user.id) return error(404, 'NOT_FOUND', 'Account not found', request)
    return HttpResponse.json(publicAccount(acc))
  }),

  http.post(`${BASE}/accounts/:id/deposit`, async ({ request, params }) => {
    await delay(500)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, user.id, { id: params.id, ...b })
    if (idem.early) return idem.early
    const acc = db.accounts.get(String(params.id))
    if (!acc || acc.userId !== user.id) return error(404, 'NOT_FOUND', 'Account not found', request)
    if (!isMoney(b.amount) || new Decimal(b.amount).lt(1) || new Decimal(b.amount).gt(100000))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'amount', message: 'must be between 1.00 and 100000.00' },
      ])
    const tx = postLine(
      acc,
      'CREDIT',
      new Decimal(b.amount),
      'DEPOSIT',
      'Demo deposit',
      null,
      crypto.randomUUID(),
    )
    idem.save(201, tx)
    return HttpResponse.json(tx, { status: 201 })
  }),

  http.get(`${BASE}/accounts/:id/transactions`, async ({ request, params }) => {
    await delay(350)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const acc = db.accounts.get(String(params.id))
    if (!acc || acc.userId !== user.id) return error(404, 'NOT_FOUND', 'Account not found', request)
    const url = new URL(request.url)
    const type = url.searchParams.get('type')
    const from = url.searchParams.get('from')
    const to = url.searchParams.get('to')
    const badFrom = from !== null && !IST_DATE.test(from)
    if (badFrom || (to !== null && !IST_DATE.test(to)))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: badFrom ? 'from' : 'to', message: 'must be yyyy-MM-dd' },
      ])
    // Dates are IST calendar days, both inclusive; newest first.
    const items = db.transactions.filter((t) => {
      const d = istDate(t.createdAt)
      return (
        t.accountId === acc.id &&
        (!type || t.type === type) &&
        (!from || d >= from) &&
        (!to || d <= to)
      )
    })
    return HttpResponse.json(paginate(items, url))
  }),

  // §7.4 Payments
  http.post(`${BASE}/transfers`, async ({ request }) => {
    await delay(600)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, user.id, b)
    if (idem.early) return idem.early
    const from = db.accounts.get(String(b.fromAccountId))
    if (!from || from.userId !== user.id)
      return error(404, 'NOT_FOUND', 'Account not found', request)
    if (!isMoney(b.amount) || new Decimal(b.amount).lt(1))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'amount', message: 'must be between 1.00 and 200000.00' },
      ])
    const amount = new Decimal(b.amount)
    // Backend: over the per-transfer max is a 400 on "amount"; only the daily cap is 422.
    if (amount.gt(200000))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'amount', message: 'must be at most 200000.00' },
      ])
    if (from.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    const to = [...db.accounts.values()].find((a) => a.accountNumber === b.toAccountNumber)
    if (!to || to.id === from.id || to.status === 'CLOSED') {
      const message = !to
        ? 'account not found'
        : to.id === from.id
          ? 'must differ from the source account'
          : 'account is closed'
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'toAccountNumber', message },
      ])
    }
    // Daily limit: transfers to OTHER people per IST day; own-account moves don't count.
    if (to.userId !== user.id) {
      const today = istDate(new Date().toISOString())
      const ownNumbers = new Set(ownAccounts(user.id).map((a) => a.accountNumber))
      const sentToday = db.transfers
        .filter(
          (t) =>
            t.userId === user.id &&
            !ownNumbers.has(t.toAccountNumber) &&
            istDate(t.createdAt) === today,
        )
        .reduce((s, t) => s.plus(t.amount), new Decimal(0))
      if (sentToday.plus(amount).gt(DAILY_LIMIT)) {
        const left = DAILY_LIMIT.minus(sentToday)
        const remaining = (left.isNegative() ? new Decimal(0) : left).toFixed(2)
        return error(
          422,
          'LIMIT_EXCEEDED',
          `Daily transfer limit reached; remaining today: ${remaining}`,
          request,
        )
      }
    }
    if (new Decimal(from.balance).lt(amount))
      return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
    const toUser = [...db.users.values()].find((u) => u.user.id === to.userId)?.user
    if (!toUser) return error(500, 'INTERNAL_ERROR', 'Mock data inconsistent', request)
    const entry = crypto.randomUUID()
    const note = typeof b.note === 'string' ? b.note : null
    postLine(
      from,
      'DEBIT',
      amount,
      'TRANSFER',
      note ?? `Transfer to ${toUser.fullName}`,
      `To ${toUser.fullName} ${last4(to.accountNumber)}`,
      entry,
    )
    postLine(
      to,
      'CREDIT',
      amount,
      'TRANSFER',
      note ?? `Transfer from ${user.fullName}`,
      `From ${user.fullName} ${last4(from.accountNumber)}`,
      entry,
    )
    const dto: TransferDto = {
      id: crypto.randomUUID(),
      fromAccountNumber: from.accountNumber,
      toAccountNumber: to.accountNumber,
      toName: toUser.fullName,
      amount: amount.toFixed(4),
      note,
      status: 'COMPLETED',
      createdAt: new Date().toISOString(),
    }
    db.transfers.unshift({ ...dto, userId: user.id })
    idem.save(201, dto)
    return HttpResponse.json(dto, { status: 201 })
  }),

  http.get(`${BASE}/transfers`, ({ request }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const items = db.transfers.filter((t) => t.userId === user.id).map(({ userId: _u, ...t }) => t)
    return HttpResponse.json(paginate(items, new URL(request.url)))
  }),

  http.get(`${BASE}/beneficiaries`, ({ request }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    return HttpResponse.json(
      db.beneficiaries.filter((x) => x.userId === user.id).map(({ userId: _u, ...x }) => x),
    )
  }),

  http.post(`${BASE}/beneficiaries`, async ({ request }) => {
    await delay(350)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const nickname = typeof b.nickname === 'string' ? b.nickname.trim() : ''
    if (!nickname || nickname.length > 50)
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'nickname', message: 'must be 1-50 characters' },
      ])
    const target = [...db.accounts.values()].find((a) => a.accountNumber === b.accountNumber)
    const invalid = (status: number, message: string) =>
      error(status, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'accountNumber', message },
      ])
    if (!target || target.status === 'CLOSED') return invalid(400, 'account not found')
    if (target.userId === user.id) return invalid(400, 'is your own account')
    if (
      db.beneficiaries.some((x) => x.userId === user.id && x.accountNumber === target.accountNumber)
    )
      return invalid(409, 'is already saved')
    const holder = [...db.users.values()].find((u) => u.user.id === target.userId)?.user
    const dto = {
      id: crypto.randomUUID(),
      nickname,
      accountNumber: target.accountNumber,
      holderName: holder?.fullName ?? '',
      createdAt: new Date().toISOString(),
    }
    db.beneficiaries.push({ ...dto, userId: user.id })
    return HttpResponse.json(dto, { status: 201 })
  }),

  http.delete(`${BASE}/beneficiaries/:id`, ({ request, params }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const i = db.beneficiaries.findIndex((x) => x.id === params.id && x.userId === user.id)
    if (i === -1) return error(404, 'NOT_FOUND', 'Beneficiary not found', request)
    db.beneficiaries.splice(i, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // §7.5 Trading
  http.get(`${BASE}/market/instruments`, async ({ request }) => {
    await delay(250)
    if (!userFrom(request)) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    maybeTick()
    return HttpResponse.json([...db.instruments.values()].map(({ open: _o, ...i }) => i))
  }),

  http.get(`${BASE}/market/instruments/:symbol/history`, ({ request, params }) => {
    if (!userFrom(request)) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const inst = db.instruments.get(String(params.symbol))
    if (!inst) return error(404, 'NOT_FOUND', 'Instrument not found', request)
    const range = new URL(request.url).searchParams.get('range') ?? '1D'
    const span = range === '1H' ? 3_600_000 : range === '1W' ? 7 * 86_400_000 : 86_400_000
    const points = 60
    // Walk backwards from the current price so the series ends on it.
    let p = new Decimal(inst.price)
    const out: PricePointDto[] = []
    for (let i = 0; i < points; i++) {
      out.unshift({
        t: new Date(Date.now() - (i * span) / points).toISOString(),
        price: p.toFixed(2),
      })
      p = p.times(new Decimal(10_000 + Math.round((Math.random() - 0.5) * 120)).div(10_000))
    }
    return HttpResponse.json(out)
  }),

  http.get(`${BASE}/portfolio`, async ({ request }) => {
    await delay(320)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    maybeTick()
    const holdings: HoldingDto[] = []
    let mv = new Decimal(0)
    let cost = new Decimal(0)
    let realized = new Decimal(0)
    for (const h of db.holdings.filter((x) => x.userId === user.id)) {
      realized = realized.plus(h.realizedPnl)
      if (h.quantity === 0) continue
      const inst = db.instruments.get(h.symbol)
      if (!inst) continue
      const value = new Decimal(inst.price).times(h.quantity)
      const c = new Decimal(h.avgCost).times(h.quantity)
      const pnl = value.minus(c)
      mv = mv.plus(value)
      cost = cost.plus(c)
      holdings.push({
        symbol: h.symbol,
        name: inst.name,
        quantity: h.quantity,
        avgCost: h.avgCost,
        price: inst.price,
        marketValue: value.toFixed(4),
        unrealizedPnl: pnl.toFixed(4),
        unrealizedPnlPct: pnl.div(c).times(100).toDecimalPlaces(2).toFixed(2),
      })
    }
    const body: PortfolioDto = {
      holdings,
      totalMarketValue: mv.toFixed(4),
      totalCost: cost.toFixed(4),
      totalUnrealizedPnl: mv.minus(cost).toFixed(4),
      totalRealizedPnl: realized.toFixed(4),
    }
    return HttpResponse.json(body)
  }),

  // §7.6 Loans
  http.get(`${BASE}/loans`, async ({ request }) => {
    await delay(280)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    return HttpResponse.json(
      db.loans.filter((l) => l.userId === user.id).map(({ userId: _u, ...l }) => l),
    )
  }),

  // §7.8 Notifications
  http.get(`${BASE}/notifications/unread-count`, ({ request }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    return HttpResponse.json({ count: db.unread.get(user.id) ?? 0 })
  }),
]
