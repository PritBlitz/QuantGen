/**
 * MSW handlers for PRD §7. They return the contract's shapes and error bodies exactly, and
 * emulate the backend's rules that the UI must handle: ownership (404 for others' data),
 * idempotency replay/conflict, limits, insufficient funds and the login lockout.
 */
import { delay, http, HttpResponse } from 'msw'
import type {
  AccountDto,
  AdminLoanDto,
  AdminUserDto,
  AuthResponse,
  ErrorBody,
  ErrorCode,
  ErrorCodeV2,
  FieldError,
  HoldingDto,
  NotificationDto,
  OrderDto,
  Page,
  PortfolioDto,
  PricePointDto,
  StepUpAction,
  TransactionDto,
  TransferDto,
  UserDto,
} from '@/api/types'
import { Decimal } from '@/lib/money'
import {
  eligibility,
  emi,
  LOAN_MAX,
  LOAN_MIN,
  schedule,
  TENURE_MAX,
  TENURE_MIN,
  totals,
} from '@/lib/emi'
import { addMonths, buildSchedule, createDb, tickPrices, type MockDb, type MockLoan } from './db'

export const BASE = '/api/v1'
const ACCESS_TTL_SECONDS = 900
const REFRESH_COOKIE = 'qg_refresh'

export let db: MockDb = createDb()
export function resetMockDb() {
  db = createDb()
}

// ── helpers ───────────────────────────────────────────────────────────────────────────
export function error(
  status: number,
  code: ErrorCode | ErrorCodeV2,
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
export function userFrom(request: Request): UserDto | null {
  const header = request.headers.get('Authorization') ?? ''
  // Hybrid mode: a real JWT from the live auth API is mapped onto the mock demo user so
  // not-yet-live endpoints still have data. Never in full-mock mode or tests.
  if (
    import.meta.env.VITE_MOCKS_HYBRID === 'true' &&
    /^Bearer [\w-]+\.[\w-]+\.[\w-]+$/.test(header)
  ) {
    return (
      db.users.get(jwtIsAdmin(header) ? 'admin@quantgen.dev' : 'demo@quantgen.dev')?.user ?? null
    )
  }
  const m = /^Bearer mock\.([0-9a-f-]{36})\.[0-9a-f-]+$/.exec(header)
  if (!m) return null
  for (const { user } of db.users.values()) if (user.id === m[1]) return user
  return null
}

/** Hybrid mode only: peek at the (unverified) JWT payload to pick the matching mock user. */
function jwtIsAdmin(header: string): boolean {
  try {
    const payload = header.split('.')[1] ?? ''
    return /"ADMIN"|ROLE_ADMIN/.test(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return false
  }
}

/** 401 when signed out, 403 for non-admins (the real backend's deny-by-default). */
export function adminFrom(
  request: Request,
): { admin: UserDto; denied: null } | { admin: null; denied: Response } {
  const user = userFrom(request)
  if (!user)
    return { admin: null, denied: error(401, 'UNAUTHORIZED', 'Authentication required', request) }
  if (user.role !== 'ADMIN')
    return { admin: null, denied: error(403, 'FORBIDDEN', 'Access denied', request) }
  return { admin: user, denied: null }
}

export function notify(userId: string, type: NotificationDto['type'], title: string, body: string) {
  db.notifications.unshift({
    id: crypto.randomUUID(),
    userId,
    type,
    title,
    body,
    read: false,
    createdAt: new Date().toISOString(),
  })
}

export function audit(
  actor: UserDto,
  action: string,
  targetType: string | null,
  targetId: string | null,
  details: string | null,
) {
  db.auditLogs.unshift({
    id: crypto.randomUUID(),
    actorId: actor.id,
    actorEmail: actor.email,
    action,
    targetType,
    targetId,
    details,
    ip: '127.0.0.1',
    requestId: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
  })
}

/** Notification copy only (mock side); grouping is done on the string, no float maths. */
export function inr(v: Decimal | string): string {
  const [whole = '0', frac = '00'] = new Decimal(v).toFixed(2).split('.')
  const last3 = whole.slice(-3)
  const rest = whole.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')
  return `₹${rest ? `${rest},` : ''}${last3}.${frac}`
}

/** UserDto plus the v2 fields the backend now sends (handle, KYC, passkey/PIN flags). */
export function withV2(user: UserDto): UserDto {
  return {
    ...user,
    handle: db.v2.handles.get(user.id)?.handle ?? null,
    kycStatus: db.v2.kyc.get(user.id)?.status ?? 'NONE',
    hasPasskey: db.v2.passkeys.some((p) => p.userId === user.id),
    hasPin: db.v2.pins.has(user.id),
  }
}

export function issue(user: UserDto): HttpResponse<AuthResponse> {
  const body: AuthResponse = {
    accessToken: `mock.${user.id}.${crypto.randomUUID()}`,
    expiresIn: ACCESS_TTL_SECONDS,
    user: withV2(user),
  }
  // HttpOnly can't be emulated by MSW; the real backend sets HttpOnly; Secure; SameSite=Strict.
  return HttpResponse.json(body, {
    headers: {
      'Set-Cookie': `${REFRESH_COOKIE}=mock-refresh.${user.id}; Path=/; SameSite=Strict`,
    },
  })
}

/** Request amounts: at most 2 decimals (the backend returns 400 otherwise). */
export const isMoney = (v: unknown): v is string =>
  typeof v === 'string' && /^\d{1,15}(\.\d{1,2})?$/.test(v)
export const IST_DATE = /^\d{4}-\d{2}-\d{2}$/
/** yyyy-MM-dd of an instant in Asia/Kolkata. */
export const istDate = (iso: string) =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date(iso))
export const last4 = (n: string) => `\u2022\u2022\u2022\u2022${n.slice(-4)}`
export const DAILY_LIMIT = new Decimal(500000)

export const toLoanDto = ({
  userId: _u,
  monthlyIncome: _m,
  disburseAccountId: _d,
  purpose: _p,
  decisionReason: _r,
  schedule: _s,
  ...l
}: MockLoan) => l
export const toLoanDetail = ({
  userId: _u,
  monthlyIncome: _m,
  disburseAccountId: _d,
  ...l
}: MockLoan) => l

export const existingEmis = (userId: string) =>
  db.loans
    // Backend M4: pending applications count too, so a second one can be ineligible.
    .filter((l) => l.userId === userId && (l.status === 'ACTIVE' || l.status === 'PENDING'))
    .reduce((s, l) => s.plus(l.emi), new Decimal(0))

function loanFieldErrors(b: Record<string, unknown>): FieldError[] {
  const fe: FieldError[] = []
  if (
    !isMoney(b.amount) ||
    new Decimal(String(b.amount)).lt(LOAN_MIN) ||
    new Decimal(String(b.amount)).gt(LOAN_MAX)
  )
    fe.push({ field: 'amount', message: 'must be between 10000.00 and 1000000.00' })
  const t = b.tenureMonths
  if (typeof t !== 'number' || !Number.isInteger(t) || t < TENURE_MIN || t > TENURE_MAX)
    fe.push({ field: 'tenureMonths', message: 'must be a whole number from 6 to 60' })
  if (!isMoney(b.monthlyIncome) || new Decimal(String(b.monthlyIncome)).lte(0))
    fe.push({ field: 'monthlyIncome', message: 'must be a positive amount' })
  return fe
}
export function toAdminUser(user: UserDto): AdminUserDto {
  return { ...user, accountCount: ownAccounts(user.id).length }
}

export function toAdminLoan(loan: MockLoan): AdminLoanDto {
  const applicant = [...db.users.values()].find((u) => u.user.id === loan.userId)?.user
  return {
    ...toLoanDto(loan),
    applicant: {
      userId: loan.userId,
      fullName: applicant?.fullName ?? 'Unknown',
      email: applicant?.email ?? '',
    },
    monthlyIncome: loan.monthlyIncome,
    purpose: loan.purpose,
    decisionReason: loan.decisionReason,
  }
}
export const ownAccounts = (userId: string) =>
  [...db.accounts.values()].filter((a) => a.userId === userId)
/** Public shape, plus the v2 fields (PRD §12.2): available balance after active holds, goal info. */
export const publicAccount = ({
  userId: _u,
  ...a
}: AccountDto & { userId: string }): AccountDto => {
  const holds = db.v2.holds
    .filter((h) => h.accountId === a.id && h.status === 'ACTIVE')
    .reduce((sum, h) => sum.plus(h.amount), new Decimal(0))
  const goal = db.v2.goals.get(a.id) ?? null
  return {
    ...a,
    availableBalance: new Decimal(a.balance).minus(holds).toFixed(4),
    holdsTotal: holds.toFixed(4),
    ...(goal ? { goal } : {}),
  }
}

export function paginate<T>(items: T[], url: URL): Page<T> {
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
export function idempotent(request: Request, userId: string, body: unknown) {
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

export function postLine(
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
export function maybeTick() {
  if (Date.now() - lastTick >= 3000) {
    tickPrices(db)
    lastTick = Date.now()
  }
}

const PASSWORD_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,72}$/

/** Mock step-up (§12.1): risky actions need a valid X-Step-Up-Token for this user + action. */
export function stepUpGate(request: Request, user: UserDto, action: StepUpAction): Response | null {
  const token = request.headers.get('X-Step-Up-Token')
  const rec = token ? db.v2.stepUpTokens.get(token) : undefined
  // Backend f29bb64: reusable for 5 minutes for the same action (so a retry with the same
  // Idempotency-Key works).
  if (rec && rec.userId === user.id && rec.action === action && rec.exp > Date.now()) return null
  const methods: string[] = []
  if (db.v2.passkeys.some((p) => p.userId === user.id)) methods.push('PASSKEY')
  if (db.v2.pins.has(user.id)) methods.push('PIN')
  const requestId = crypto.randomUUID()
  return HttpResponse.json(
    {
      timestamp: new Date().toISOString(),
      status: 401,
      code: 'STEP_UP_REQUIRED',
      message: 'Step-up authentication required',
      path: new URL(request.url).pathname,
      fieldErrors: [],
      requestId,
      stepUp: { methods, action },
    },
    { status: 401, headers: { 'X-Request-Id': requestId } },
  )
}

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
      ? HttpResponse.json(withV2(user))
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
    // v2 (F-SAV-1): goals are accounts too, outside the 3-account cap.
    if (b.type === 'GOAL') {
      const fe: FieldError[] = []
      const name = typeof b.name === 'string' ? b.name.trim() : ''
      if (name.length < 1 || name.length > 40)
        fe.push({ field: 'name', message: 'must be 1–40 characters' })
      if (!isMoney(b.target) || new Decimal(String(b.target)).lt(100))
        fe.push({ field: 'target', message: 'must be at least 100.00' })
      if (
        b.targetDate !== undefined &&
        (typeof b.targetDate !== 'string' || !IST_DATE.test(b.targetDate))
      )
        fe.push({ field: 'targetDate', message: 'must be a date (yyyy-MM-dd)' })
      if (fe.length)
        return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
      if (ownAccounts(user.id).filter((a) => a.type === 'GOAL').length >= 10)
        return error(422, 'LIMIT_EXCEEDED', 'At most 10 goals', request)
      const goal = {
        id: crypto.randomUUID(),
        userId: user.id,
        accountNumber: `5021${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`,
        type: 'GOAL' as const,
        status: 'ACTIVE' as const,
        balance: '0.0000',
        currency: 'INR' as const,
        createdAt: new Date().toISOString(),
      }
      db.accounts.set(goal.id, goal)
      db.v2.goals.set(goal.id, {
        name,
        target: new Decimal(String(b.target)).toFixed(4),
        targetDate: typeof b.targetDate === 'string' ? b.targetDate : null,
        emoji: typeof b.emoji === 'string' ? b.emoji.slice(0, 8) : null,
      })
      return HttpResponse.json(publicAccount(goal), { status: 201 })
    }
    if (b.type !== 'SAVINGS' && b.type !== 'CHECKING')
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'type', message: 'must be SAVINGS or CHECKING' },
      ])
    if (
      ownAccounts(user.id).filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING').length >= 3
    )
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
    // v2 (F-PAY-5): pay by handle. Exactly one of toAccountNumber / toHandle.
    if (b.toHandle !== undefined) {
      if (b.toAccountNumber !== undefined)
        return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
          { field: 'toHandle', message: 'send either toHandle or toAccountNumber, not both' },
        ])
      const h = [...db.v2.handles.values()].find(
        (x) => x.handle === String(b.toHandle).toLowerCase(),
      )
      const acc = h ? db.accounts.get(h.accountId) : undefined
      if (!acc)
        return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
          { field: 'toHandle', message: 'handle not found' },
        ])
      b.toAccountNumber = acc.accountNumber
    }
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
    // v2 (F-SEC-5): new-payee cooling — at most ₹25,000 in total during the first 24 h.
    if (to.userId !== user.id) {
      const ben = db.beneficiaries.find(
        (x) => x.userId === user.id && x.accountNumber === to.accountNumber,
      )
      if (ben && Date.now() - Date.parse(ben.createdAt) < 86_400_000) {
        const sent = db.transfers
          .filter((t) => t.userId === user.id && t.toAccountNumber === to.accountNumber)
          .reduce((s2, t) => s2.plus(t.amount), new Decimal(0))
        if (sent.plus(amount).gt(25000))
          return error(
            422,
            'COOLING_PERIOD',
            'New payee: ₹25,000 limit in the first 24 hours',
            request,
          )
      }
    }
    // v2 (F-SEC-2): step-up for transfers above ₹50,000 to someone else, or the first transfer
    // to a payee saved less than 24 h ago.
    const newPayee = db.beneficiaries.find(
      (x) =>
        x.userId === user.id &&
        x.accountNumber === to.accountNumber &&
        Date.now() - Date.parse(x.createdAt) < 86_400_000,
    )
    const firstToNewPayee =
      !!newPayee &&
      !db.transfers.some((t) => t.userId === user.id && t.toAccountNumber === to.accountNumber)
    if (to.userId !== user.id && (amount.gt(50000) || firstToNewPayee)) {
      const gate = stepUpGate(request, user, 'TRANSFER')
      if (gate) return gate
    }
    // v2 (F-HOLD-1): debits check the available balance (balance − active holds).
    const held = db.v2.holds
      .filter((h) => h.accountId === from.id && h.status === 'ACTIVE')
      .reduce((s2, h) => s2.plus(h.amount), new Decimal(0))
    if (new Decimal(from.balance).minus(held).lt(amount))
      return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
    const toUser = [...db.users.values()].find((u) => u.user.id === to.userId)?.user
    if (!toUser) return error(500, 'INTERNAL_ERROR', 'Mock data inconsistent', request)
    const note = typeof b.note === 'string' ? b.note : null
    // v2 (F-RISK-1): very large transfers to someone else are held for fraud review (mock rule).
    if (
      to.userId !== user.id &&
      db.v2.riskReviewAbove !== null &&
      amount.gte(db.v2.riskReviewAbove)
    ) {
      const holdId = crypto.randomUUID()
      db.v2.holds.push({
        id: holdId,
        accountId: from.id,
        amount: amount.toFixed(4),
        reason: 'FRAUD_REVIEW',
        status: 'ACTIVE',
      })
      const pending: TransferDto = {
        id: crypto.randomUUID(),
        fromAccountNumber: from.accountNumber,
        toAccountNumber: to.accountNumber,
        toName: toUser.fullName,
        amount: amount.toFixed(4),
        note,
        status: 'PENDING_REVIEW',
        createdAt: new Date().toISOString(),
        source: 'MANUAL',
      }
      db.transfers.unshift({ ...pending, userId: user.id })
      db.v2.fraudCases.unshift({
        id: crypto.randomUUID(),
        status: 'OPEN',
        score: 78,
        transferId: pending.id,
        amount: pending.amount,
        fromAccountNumberMasked: last4(from.accountNumber),
        toAccountNumberMasked: last4(to.accountNumber),
        toName: toUser.fullName,
        customer: { userId: user.id, fullName: user.fullName },
        topSignal: 'Unusually large amount for this customer',
        createdAt: pending.createdAt,
        decidedAt: null,
        decisionReason: null,
        memo: null,
        signals: [
          { code: 'LARGE_AMOUNT', label: 'Unusually large amount for this customer', weight: 40 },
        ],
        customerBaseline: {
          avgTransfer: '6800.0000',
          transfers30d: 4,
          usualHoursIst: '09:00–21:00',
          accountAgeDays: 108,
        },
      })
      notify(
        user.id,
        'TRANSFER_UNDER_REVIEW',
        'Transfer under review',
        `${inr(amount)} to ${toUser.fullName} is being checked. The money is held, not sent.`,
      )
      idem.save(201, pending)
      return HttpResponse.json(pending, { status: 201 })
    }
    const entry = crypto.randomUUID()
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
    if (toUser.id !== user.id)
      notify(
        toUser.id,
        'MONEY_RECEIVED',
        'Money received',
        `${inr(amount)} from ${user.fullName} into ${last4(to.accountNumber)}.`,
      )
    if (amount.gte(100000))
      audit(
        user,
        'LARGE_TRANSFER',
        'TRANSFER',
        dto.id,
        `${inr(amount)} to ${last4(to.accountNumber)}`,
      )
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

  http.get(`${BASE}/market/instruments/:symbol`, async ({ request, params }) => {
    await delay(200)
    if (!userFrom(request)) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    maybeTick()
    const inst = db.instruments.get(String(params.symbol).toUpperCase())
    if (!inst) return error(404, 'NOT_FOUND', 'Instrument not found', request)
    const { open: _o, ...dto } = inst
    return HttpResponse.json(dto)
  }),

  http.get(`${BASE}/market/instruments/:symbol/history`, ({ request, params }) => {
    if (!userFrom(request)) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const inst = db.instruments.get(String(params.symbol).toUpperCase())
    if (!inst) return error(404, 'NOT_FOUND', 'Instrument not found', request)
    const range = new URL(request.url).searchParams.get('range') ?? '1D'
    if (!['1H', '1D', '1W'].includes(range))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'range', message: 'must be one of 1H, 1D, 1W' },
      ])
    const span = range === '1H' ? 3_600_000 : range === '1W' ? 7 * 86_400_000 : 86_400_000
    const points = 60
    // Drift from the open price to the current price with bounded noise, so the line agrees
    // with changePct24h. Oldest first, like the backend.
    const end = new Decimal(inst.price)
    const startPrice = range === '1H' ? end.times('0.998') : new Decimal(inst.open)
    const out: PricePointDto[] = []
    for (let i = 0; i < points; i++) {
      const f = new Decimal(i).div(points - 1)
      const trend = startPrice.plus(end.minus(startPrice).times(f))
      const noise = i === points - 1 ? 0 : Math.round((Math.random() - 0.5) * 60) // ±0.3%
      out.push({
        t: new Date(Date.now() - ((points - 1 - i) * span) / (points - 1)).toISOString(),
        price: trend.times(new Decimal(10_000 + noise).div(10_000)).toFixed(2),
      })
    }
    return HttpResponse.json(out)
  }),

  http.post(`${BASE}/orders`, async ({ request }) => {
    await delay(550)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, user.id, b)
    if (idem.early) return idem.early
    const acc = db.accounts.get(String(b.accountId))
    if (!acc || acc.userId !== user.id) return error(404, 'NOT_FOUND', 'Account not found', request)
    const symbol = typeof b.symbol === 'string' ? b.symbol.toUpperCase() : ''
    const inst = db.instruments.get(symbol)
    const fe: FieldError[] = []
    if (!inst) fe.push({ field: 'symbol', message: 'unknown instrument' })
    if (b.side !== 'BUY' && b.side !== 'SELL')
      fe.push({ field: 'side', message: 'must be BUY or SELL' })
    const qty = b.quantity
    if (typeof qty !== 'number' || !Number.isInteger(qty) || qty < 1 || qty > 10000)
      fe.push({ field: 'quantity', message: 'must be a whole number from 1 to 10000' })
    if (fe.length || !inst || typeof qty !== 'number')
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
    if (acc.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    maybeTick()
    const price = new Decimal(inst.price)
    const total = price.times(qty).toDecimalPlaces(2)
    let holding = db.holdings.find((h) => h.userId === user.id && h.symbol === symbol)
    if (b.side === 'BUY') {
      if (new Decimal(acc.balance).lt(total))
        return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
      postLine(acc, 'DEBIT', total, 'TRADE', `Bought ${qty} × ${symbol}`, null, crypto.randomUUID())
      if (!holding) {
        holding = { userId: user.id, symbol, quantity: 0, avgCost: '0.00', realizedPnl: '0' }
        db.holdings.push(holding)
      }
      const newQty = holding.quantity + qty
      holding.avgCost = new Decimal(holding.avgCost)
        .times(holding.quantity)
        .plus(total)
        .div(newQty)
        .toDecimalPlaces(2)
        .toFixed(2)
      holding.quantity = newQty
    } else {
      if (!holding || holding.quantity < qty)
        return error(422, 'INSUFFICIENT_HOLDINGS', 'Not enough shares to sell', request)
      postLine(acc, 'CREDIT', total, 'TRADE', `Sold ${qty} × ${symbol}`, null, crypto.randomUUID())
      holding.realizedPnl = new Decimal(holding.realizedPnl)
        .plus(price.minus(holding.avgCost).times(qty))
        .toFixed(4)
      holding.quantity -= qty
    }
    const dto: OrderDto = {
      id: crypto.randomUUID(),
      symbol,
      side: b.side as OrderDto['side'],
      quantity: qty,
      price: price.toFixed(2),
      total: total.toFixed(2),
      status: 'FILLED',
      createdAt: new Date().toISOString(),
    }
    db.orders.unshift({ ...dto, userId: user.id })
    idem.save(201, dto)
    return HttpResponse.json(dto, { status: 201 })
  }),

  http.get(`${BASE}/orders`, ({ request }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const items = db.orders.filter((o) => o.userId === user.id).map(({ userId: _u, ...o }) => o)
    return HttpResponse.json(paginate(items, new URL(request.url)))
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
    return HttpResponse.json(db.loans.filter((l) => l.userId === user.id).map(toLoanDto))
  }),

  http.post(`${BASE}/loans/quote`, async ({ request }) => {
    await delay(200)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const fe = loanFieldErrors(b)
    if (fe.length) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
    const rows = schedule(String(b.amount), Number(b.tenureMonths))
    const { totalInterest, totalPayable } = totals(rows)
    const e = emi(String(b.amount), Number(b.tenureMonths))
    const verdict = eligibility(String(b.amount), e, String(b.monthlyIncome), existingEmis(user.id))
    return HttpResponse.json({
      emi: e.toFixed(2),
      totalInterest: totalInterest.toFixed(2),
      totalPayable: totalPayable.toFixed(2),
      eligible: verdict.eligible,
      ...(verdict.reason ? { reason: verdict.reason } : {}),
    })
  }),

  http.post(`${BASE}/loans`, async ({ request }) => {
    await delay(600)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const fe = loanFieldErrors(b)
    const purpose = typeof b.purpose === 'string' ? b.purpose.trim() : ''
    if (purpose.length < 3 || purpose.length > 200)
      fe.push({ field: 'purpose', message: 'must be 3-200 characters' })
    const acc = db.accounts.get(String(b.disburseAccountId))
    if (!acc || acc.userId !== user.id)
      fe.push({ field: 'disburseAccountId', message: 'account not found' })
    if (fe.length) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
    const verdict = eligibility(
      String(b.amount),
      emi(String(b.amount), Number(b.tenureMonths)),
      String(b.monthlyIncome),
      existingEmis(user.id),
    )
    if (!verdict.eligible)
      return error(422, 'LOAN_NOT_ELIGIBLE', verdict.reason ?? 'Not eligible', request)
    const loan: MockLoan = {
      id: crypto.randomUUID(),
      userId: user.id,
      amount: new Decimal(String(b.amount)).toFixed(2),
      tenureMonths: Number(b.tenureMonths),
      annualRate: '12.00',
      emi: emi(String(b.amount), Number(b.tenureMonths)).toFixed(2),
      status: 'PENDING',
      outstandingPrincipal: '0.00',
      nextDueDate: null,
      createdAt: new Date().toISOString(),
      purpose,
      decisionReason: null,
      schedule: [],
      monthlyIncome: new Decimal(String(b.monthlyIncome)).toFixed(2),
      disburseAccountId: String(b.disburseAccountId),
    }
    db.loans.unshift(loan)
    audit(
      user,
      'LOAN_APPLIED',
      'LOAN',
      loan.id,
      `amount=${loan.amount} tenureMonths=${loan.tenureMonths}`,
    )
    return HttpResponse.json(toLoanDto(loan), { status: 201 })
  }),

  http.get(`${BASE}/loans/:id`, async ({ request, params }) => {
    await delay(250)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const loan = db.loans.find((l) => l.id === params.id && l.userId === user.id)
    if (!loan) return error(404, 'NOT_FOUND', 'Loan not found', request)
    return HttpResponse.json(toLoanDetail(loan))
  }),

  http.post(`${BASE}/loans/:id/repay`, async ({ request, params }) => {
    await delay(550)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, user.id, { id: params.id, ...b })
    if (idem.early) return idem.early
    const loan = db.loans.find((l) => l.id === params.id && l.userId === user.id)
    if (!loan) return error(404, 'NOT_FOUND', 'Loan not found', request)
    if (loan.status !== 'ACTIVE') return error(409, 'INVALID_STATE', 'Loan is not active', request)
    const acc = db.accounts.get(String(b.accountId))
    if (!acc || acc.userId !== user.id) return error(404, 'NOT_FOUND', 'Account not found', request)
    if (acc.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    const unpaid = loan.schedule.filter((r) => r.status !== 'PAID')
    const next = unpaid[0]
    if (!next) return error(409, 'INVALID_STATE', 'Nothing due', request)
    if (b.amount !== undefined && !isMoney(b.amount))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'amount', message: 'must be a positive amount with at most 2 decimals' },
      ])
    // Backend M4: no amount = exactly the oldest unpaid instalment's amountDue.
    const amount = new Decimal(typeof b.amount === 'string' ? b.amount : next.amountDue)
    const maxDue = unpaid.reduce((s, r) => s.plus(r.amountDue), new Decimal(0))
    if (amount.lt(1) || amount.gt(maxDue))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'amount', message: `must be between 1.00 and ${maxDue.toFixed(2)}` },
      ])
    if (new Decimal(acc.balance).lt(amount))
      return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)

    // Backend M4: oldest first, interest before principal within each instalment; the last
    // instalment touched may be paid partially (its amountDue drops).
    let left = amount
    let principalPaid = new Decimal(0)
    let interestPaid = new Decimal(0)
    const now = new Date().toISOString()
    for (const row of unpaid) {
      if (left.lte(0)) break
      const due = new Decimal(row.amountDue)
      const paidBefore = new Decimal(row.emi).minus(due)
      const interestOwed = new Decimal(row.interest).minus(paidBefore)
      const interestLeft = interestOwed.isNegative() ? new Decimal(0) : interestOwed
      const pay = left.lt(due) ? left : due
      const toInterest = pay.lt(interestLeft) ? pay : interestLeft
      interestPaid = interestPaid.plus(toInterest)
      principalPaid = principalPaid.plus(pay.minus(toInterest))
      row.amountDue = due.minus(pay).toFixed(2)
      left = left.minus(pay)
      if (new Decimal(row.amountDue).isZero()) {
        row.status = 'PAID'
        row.paidAt = now
      }
    }
    postLine(acc, 'DEBIT', amount, 'LOAN_REPAYMENT', 'Loan repayment', null, crypto.randomUUID())
    const outstanding = new Decimal(loan.outstandingPrincipal).minus(principalPaid)
    loan.outstandingPrincipal = (outstanding.isNegative() ? new Decimal(0) : outstanding).toFixed(2)
    const stillDue = loan.schedule.find((r) => r.status !== 'PAID')
    loan.nextDueDate = stillDue?.dueDate ?? null
    if (new Decimal(loan.outstandingPrincipal).isZero() || !stillDue) {
      loan.status = 'CLOSED'
      loan.nextDueDate = null
    }
    const dto = {
      id: crypto.randomUUID(),
      loanId: loan.id,
      amount: amount.toFixed(2),
      principalPaid: principalPaid.toFixed(2),
      interestPaid: interestPaid.toFixed(2),
      createdAt: now,
    }
    notify(
      user.id,
      'EMI_PAID',
      loan.status === 'CLOSED' ? 'Loan closed' : 'EMI paid',
      `${inr(amount)} was applied to your loan from ${last4(acc.accountNumber)}.`,
    )
    idem.save(201, dto)
    return HttpResponse.json(dto, { status: 201 })
  }),

  // §7.7 Admin
  http.get(`${BASE}/admin/stats`, async ({ request }) => {
    await delay(300)
    const { denied } = adminFrom(request)
    if (denied) return denied
    const customerIds = new Set(
      [...db.users.values()].filter((u) => u.user.role === 'CUSTOMER').map((u) => u.user.id),
    )
    const deposits = [...db.accounts.values()]
      .filter((a) => customerIds.has(a.userId))
      .reduce((s, a) => s.plus(a.balance), new Decimal(0))
    const active = db.loans.filter((l) => l.status === 'ACTIVE')
    const today = istDate(new Date().toISOString())
    return HttpResponse.json({
      // Backend M5: customers only.
      totalUsers: customerIds.size,
      totalDeposits: deposits.toFixed(4),
      activeLoans: active.length,
      outstandingPrincipal: active
        .reduce((s, l) => s.plus(l.outstandingPrincipal), new Decimal(0))
        .toFixed(4),
      tradesToday: db.orders.filter((o) => o.status === 'FILLED' && istDate(o.createdAt) === today)
        .length,
    })
  }),

  http.get(`${BASE}/admin/users`, async ({ request }) => {
    await delay(280)
    const { denied } = adminFrom(request)
    if (denied) return denied
    const url = new URL(request.url)
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    const rows = [...db.users.values()]
      .map((u) => toAdminUser(u.user))
      .filter((u) => !q || [u.fullName, u.email, u.phone].some((f) => f.toLowerCase().includes(q)))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    return HttpResponse.json(paginate(rows, url))
  }),

  http.get(`${BASE}/admin/users/:id`, async ({ request, params }) => {
    await delay(260)
    const { denied } = adminFrom(request)
    if (denied) return denied
    const entry = [...db.users.values()].find((u) => u.user.id === params.id)
    if (!entry) return error(404, 'NOT_FOUND', 'User not found', request)
    return HttpResponse.json({
      ...toAdminUser(entry.user),
      accounts: ownAccounts(entry.user.id).map(publicAccount),
      loans: db.loans.filter((l) => l.userId === entry.user.id).map(toLoanDto),
    })
  }),

  http.patch(`${BASE}/admin/accounts/:id/status`, async ({ request, params }) => {
    await delay(450)
    const { admin, denied } = adminFrom(request)
    if (denied) return denied
    const b = (await request.json()) as Record<string, unknown>
    const fe: FieldError[] = []
    if (b.status !== 'ACTIVE' && b.status !== 'FROZEN')
      fe.push({ field: 'status', message: 'must be ACTIVE or FROZEN' })
    const reason = typeof b.reason === 'string' ? b.reason.trim() : ''
    if (reason.length < 5 || reason.length > 500)
      fe.push({ field: 'reason', message: 'must be 5-500 characters' })
    if (fe.length) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
    const acc = db.accounts.get(String(params.id))
    if (!acc) return error(404, 'NOT_FOUND', 'Account not found', request)
    if (acc.status === 'CLOSED' || acc.status === b.status)
      return error(409, 'INVALID_STATE', `Account is already ${acc.status.toLowerCase()}`, request)
    acc.status = b.status as 'ACTIVE' | 'FROZEN'
    const frozen = acc.status === 'FROZEN'
    audit(
      admin,
      frozen ? 'ACCOUNT_FROZEN' : 'ACCOUNT_UNFROZEN',
      'ACCOUNT',
      acc.id,
      `Account ${last4(acc.accountNumber)}: ${reason}`,
    )
    notify(
      acc.userId,
      frozen ? 'ACCOUNT_FROZEN' : 'ACCOUNT_UNFROZEN',
      frozen ? 'Account frozen' : 'Account unfrozen',
      frozen
        ? `Account ${last4(acc.accountNumber)} was frozen. It can still receive money. Reason: ${reason}`
        : `Account ${last4(acc.accountNumber)} is active again.`,
    )
    return HttpResponse.json(publicAccount(acc))
  }),

  http.get(`${BASE}/admin/loans`, async ({ request }) => {
    await delay(280)
    const { denied } = adminFrom(request)
    if (denied) return denied
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const rows = db.loans
      .filter((l) => !status || l.status === status)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map(toAdminLoan)
    return HttpResponse.json(paginate(rows, url))
  }),

  http.post(`${BASE}/admin/loans/:id/approve`, async ({ request, params }) => {
    await delay(650)
    const { admin, denied } = adminFrom(request)
    if (denied) return denied
    const b = (await request.json().catch(() => ({}))) as Record<string, unknown>
    const reason = typeof b.reason === 'string' && b.reason.trim() ? b.reason.trim() : null
    if (reason && reason.length > 500)
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'reason', message: 'must be at most 500 characters' },
      ])
    const loan = db.loans.find((l) => l.id === params.id)
    if (!loan) return error(404, 'NOT_FOUND', 'Loan not found', request)
    if (loan.status !== 'PENDING')
      return error(409, 'INVALID_STATE', 'Loan is not pending', request)
    if (loan.userId === admin.id)
      return error(409, 'INVALID_STATE', 'You can’t decide your own application', request)
    const acc = db.accounts.get(loan.disburseAccountId)
    if (!acc) return error(409, 'INVALID_STATE', 'Disbursal account missing', request)
    // APPROVED → ACTIVE in one step: disburse (LOAN_RECEIVABLE → customer), build the schedule.
    postLine(
      acc,
      'CREDIT',
      new Decimal(loan.amount),
      'LOAN_DISBURSAL',
      'Loan disbursal',
      null,
      crypto.randomUUID(),
    )
    loan.schedule = buildSchedule(
      loan.amount,
      loan.tenureMonths,
      addMonths(istDate(new Date().toISOString()), 1),
    )
    loan.status = 'ACTIVE'
    loan.outstandingPrincipal = new Decimal(loan.amount).toFixed(2)
    loan.nextDueDate = loan.schedule[0]?.dueDate ?? null
    loan.decisionReason = reason ?? 'Approved'
    audit(admin, 'LOAN_APPROVED', 'LOAN', loan.id, reason)
    notify(
      loan.userId,
      'LOAN_APPROVED',
      'Loan approved',
      `Your ${inr(loan.amount)} loan was approved and disbursed into ${last4(acc.accountNumber)}.`,
    )
    return HttpResponse.json(toLoanDto(loan))
  }),

  http.post(`${BASE}/admin/loans/:id/reject`, async ({ request, params }) => {
    await delay(500)
    const { admin, denied } = adminFrom(request)
    if (denied) return denied
    const b = (await request.json().catch(() => ({}))) as Record<string, unknown>
    const reason = typeof b.reason === 'string' ? b.reason.trim() : ''
    if (reason.length < 5 || reason.length > 500)
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'reason', message: 'must be 5-500 characters' },
      ])
    const loan = db.loans.find((l) => l.id === params.id)
    if (!loan) return error(404, 'NOT_FOUND', 'Loan not found', request)
    if (loan.status !== 'PENDING')
      return error(409, 'INVALID_STATE', 'Loan is not pending', request)
    if (loan.userId === admin.id)
      return error(409, 'INVALID_STATE', 'You can’t decide your own application', request)
    loan.status = 'REJECTED'
    loan.decisionReason = reason
    audit(admin, 'LOAN_REJECTED', 'LOAN', loan.id, reason)
    notify(
      loan.userId,
      'LOAN_REJECTED',
      'Loan application declined',
      `Your ${inr(loan.amount)} application was declined. Reason: ${reason}`,
    )
    return HttpResponse.json(toLoanDto(loan))
  }),

  http.get(`${BASE}/admin/audit-logs`, async ({ request }) => {
    await delay(260)
    const { denied } = adminFrom(request)
    if (denied) return denied
    const url = new URL(request.url)
    const actor = (url.searchParams.get('actor') ?? '').trim().toLowerCase()
    const action = (url.searchParams.get('action') ?? '').trim()
    const rows = db.auditLogs.filter(
      (a) =>
        (!actor || (a.actorEmail ?? '').toLowerCase().includes(actor)) &&
        (!action || a.action === action),
    )
    return HttpResponse.json(paginate(rows, url))
  }),

  http.get(`${BASE}/admin/ledger/reconcile`, async ({ request }) => {
    await delay(900)
    const { denied } = adminFrom(request)
    if (denied) return denied
    return HttpResponse.json({ balanced: true, ledgerSum: '0.0000', mismatches: [] })
  }),

  // §7.8 Notifications
  http.get(`${BASE}/notifications`, async ({ request }) => {
    await delay(220)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const unreadOnly = new URL(request.url).searchParams.get('unreadOnly') === 'true'
    return HttpResponse.json(
      db.notifications
        .filter((n) => n.userId === user.id && (!unreadOnly || !n.read))
        .slice(0, 50)
        .map(({ userId: _u, ...n }) => n),
    )
  }),

  http.get(`${BASE}/notifications/unread-count`, ({ request }) => {
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    return HttpResponse.json({
      count: db.notifications.filter((n) => n.userId === user.id && !n.read).length,
    })
  }),

  http.patch(`${BASE}/notifications/:id/read`, async ({ request, params }) => {
    await delay(120)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    const n = db.notifications.find((x) => x.id === params.id && x.userId === user.id)
    if (!n) return error(404, 'NOT_FOUND', 'Notification not found', request)
    n.read = true
    return new HttpResponse(null, { status: 204 })
  }),

  http.post(`${BASE}/notifications/read-all`, async ({ request }) => {
    await delay(160)
    const user = userFrom(request)
    if (!user) return error(401, 'UNAUTHORIZED', 'Authentication required', request)
    for (const n of db.notifications) if (n.userId === user.id) n.read = true
    return new HttpResponse(null, { status: 204 })
  }),
]
