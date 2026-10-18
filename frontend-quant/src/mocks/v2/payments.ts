/** MSW handlers: Payments 2.0 (§12.4) and Razorpay top-ups (§7.9). */
import { delay, http } from 'msw'
import type { GroupDto, PaymentRequestDto, ScheduledTransferDto, TopupDto, TransferDto } from '@/api/types'
import { BASE, db, error, idempotent, inr, isMoney, IST_DATE, last4, notify, paginate, postLine } from '../handlers'
import {
  addDays,
  available,
  Decimal,
  invalid,
  json,
  money,
  move,
  notFound,
  ownAccount,
  requireUser,
  stepUpGate,
  todayIst,
  userById,
} from './common'

const HANDLE_RE = /^[a-z0-9.]{3,30}@qg$/
const handleOf = (userId: string) => db.v2.handles.get(userId)?.handle ?? null
const userByHandle = (handle: string) => {
  const h = [...db.v2.handles.values()].find((x) => x.handle === handle.toLowerCase())
  return h ? { userId: h.userId, accountId: h.accountId } : null
}
function maskName(full: string) {
  const [first = '', ...rest] = full.trim().split(/\s+/)
  const last = rest.at(-1)
  return last ? `${first} ${last[0]}.` : first
}

/** Balances per member: positive = the group owes them. */
function withBalances(g: GroupDto & { memberIds: string[] }): GroupDto {
  const handles = g.memberIds.map((id) => handleOf(id) ?? '')
  const bal = new Map(handles.map((h) => [h, new Decimal(0)]))
  const expenses = g.expenses.map((e) => {
    let shares = e.shares
    if (!shares.length) {
      // EQUAL split, leftover paisa to the payer (deterministic, PRD F-PAY-12).
      const each = new Decimal(e.amount).div(handles.length).toDecimalPlaces(2, Decimal.ROUND_DOWN)
      const rest = new Decimal(e.amount).minus(each.times(handles.length))
      shares = handles.map((h) => ({ handle: h, amount: money(h === e.paidByHandle ? each.plus(rest) : each) }))
    }
    for (const s of shares) bal.set(s.handle, (bal.get(s.handle) ?? new Decimal(0)).minus(s.amount))
    bal.set(e.paidByHandle, (bal.get(e.paidByHandle) ?? new Decimal(0)).plus(e.amount))
    return { ...e, shares }
  })
  return {
    id: g.id,
    name: g.name,
    createdAt: g.createdAt,
    expenses: [...expenses].reverse(),
    members: g.memberIds.map((id) => ({
      handle: handleOf(id) ?? '',
      displayName: userById(id)?.fullName ?? 'Member',
      balance: money(bal.get(handleOf(id) ?? '') ?? new Decimal(0)),
    })),
  }
}

function billAmount(billerId: string, consumer: string) {
  // Deterministic "fetched" bill: hash of the inputs → ₹200–₹4,800.
  let h = 0
  for (const c of `${billerId}:${consumer}`) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return new Decimal(200 + (h % 4600)).plus(new Decimal(h % 100).div(100))
}

function publicSchedule({ userId: _u, toAccountNumber: _n, ...s }: ScheduledTransferDto & { userId: string; toAccountNumber: string }): ScheduledTransferDto {
  return s
}

export const paymentHandlers = [
  // Handles + resolve
  http.put(`${BASE}/users/me/handle`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { handle?: unknown; accountId?: unknown }
    const handle = typeof b.handle === 'string' ? b.handle.trim().toLowerCase() : ''
    if (!HANDLE_RE.test(handle)) return invalid(request, 'handle', 'use 3–30 letters, digits or dots, ending in @qg')
    if (!ownAccount(r.user.id, b.accountId)) return notFound(request, 'Account')
    const existing = db.v2.handles.get(r.user.id)
    const taken = userByHandle(handle)
    if (taken && taken.userId !== r.user.id) return invalid(request, 'handle', 'is already taken')
    if (existing && existing.handle !== handle && Date.parse(existing.changeableAt) > Date.now())
      return error(422, 'LIMIT_EXCEEDED', 'A handle can change once every 30 days', request)
    const dto = {
      userId: r.user.id,
      handle,
      accountId: String(b.accountId),
      changeableAt: existing?.handle === handle ? existing.changeableAt : new Date(Date.now() + 30 * 86_400_000).toISOString(),
    }
    db.v2.handles.set(r.user.id, dto)
    const { userId: _u, ...out } = dto
    return json(out)
  }),
  http.get(`${BASE}/payees/resolve`, async ({ request }) => {
    await delay(250)
    const r = requireUser(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const handle = url.searchParams.get('handle')
    const number = url.searchParams.get('accountNumber')
    let acc
    if (handle) {
      const h = userByHandle(handle)
      acc = h ? db.accounts.get(h.accountId) : undefined
    } else if (number && /^\d{12}$/.test(number)) {
      acc = [...db.accounts.values()].find((a) => a.accountNumber === number && (a.type === 'SAVINGS' || a.type === 'CHECKING'))
    } else return invalid(request, handle !== null ? 'handle' : 'accountNumber', 'is required')
    if (!acc || acc.status === 'CLOSED') return notFound(request, 'Payee')
    const owner = userById(acc.userId)
    return json({ holderNameMasked: maskName(owner?.fullName ?? ''), handle: handleOf(acc.userId), accountNumberMasked: last4(acc.accountNumber) })
  }),

  // Payment requests
  http.post(`${BASE}/payment-requests`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { toHandle?: unknown; amount?: unknown; note?: unknown }
    const me = handleOf(r.user.id)
    if (!me) return error(422, 'INVALID_STATE', 'Set your handle first', request)
    const to = typeof b.toHandle === 'string' ? userByHandle(b.toHandle) : null
    if (!to || to.userId === r.user.id) return invalid(request, 'toHandle', 'handle not found')
    if (!isMoney(b.amount) || new Decimal(b.amount).lt(1) || new Decimal(b.amount).gt(200000))
      return invalid(request, 'amount', 'must be between 1.00 and 200000.00')
    if (db.v2.requests.filter((x) => x.fromHandle === me && x.status === 'OPEN').length >= 20)
      return error(422, 'LIMIT_EXCEEDED', 'At most 20 open requests', request)
    const toHandle = handleOf(to.userId)!
    if (db.v2.blocked.some((x) => x.userId === to.userId && x.blockedHandle === me))
      return invalid(request, 'toHandle', 'handle not found') // don't reveal blocks
    const dto: PaymentRequestDto = {
      id: crypto.randomUUID(),
      fromHandle: me,
      toHandle,
      amount: money(b.amount),
      note: typeof b.note === 'string' && b.note.trim() ? b.note.trim().slice(0, 80) : null,
      status: 'OPEN',
      transferId: null,
      expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      createdAt: new Date().toISOString(),
    }
    db.v2.requests.unshift(dto)
    notify(to.userId, 'PAYMENT_REQUEST', 'Payment request', `${r.user.fullName} asked you for ${inr(b.amount)}${dto.note ? ` · ${dto.note}` : ''}.`)
    return json(dto, 201)
  }),
  http.get(`${BASE}/payment-requests`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const me = handleOf(r.user.id)
    const dir = url.searchParams.get('direction') === 'OUTGOING' ? 'OUTGOING' : 'INCOMING'
    const status = url.searchParams.get('status')
    const items = db.v2.requests
      .map((x) => (x.status === 'OPEN' && Date.parse(x.expiresAt) < Date.now() ? { ...x, status: 'EXPIRED' as const } : x))
      .filter((x) => (dir === 'INCOMING' ? x.toHandle === me : x.fromHandle === me))
      .filter((x) => !status || x.status === status)
    return json(paginate(items, url))
  }),
  http.post(`${BASE}/payment-requests/:id/pay`, async ({ request, params }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    const req = db.v2.requests.find((x) => x.id === params.id && x.toHandle === handleOf(r.user.id))
    if (!req) return notFound(request, 'Request')
    const b = (await request.json()) as { fromAccountId?: unknown }
    const idem = idempotent(request, r.user.id, { id: req.id, ...b })
    if (idem.early) return idem.early
    if (req.status !== 'OPEN' || Date.parse(req.expiresAt) < Date.now())
      return error(409, 'INVALID_STATE', 'This request is no longer open', request)
    const from = ownAccount(r.user.id, b.fromAccountId)
    if (!from) return notFound(request, 'Account')
    if (from.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    const amount = new Decimal(req.amount)
    if (amount.gt(50000)) {
      const gate = stepUpGate(request, r.user, 'TRANSFER')
      if (gate) return gate
    }
    if (available(from).lt(amount)) return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
    const requester = userByHandle(req.fromHandle)!
    const to = db.accounts.get(requester.accountId)!
    const toUser = userById(requester.userId)!
    move(from, to, amount, 'TRANSFER', req.note ?? `Paid request from ${toUser.fullName}`, req.note ?? `Request paid by ${r.user.fullName}`, `To ${toUser.fullName} ${last4(to.accountNumber)}`, `From ${r.user.fullName} ${last4(from.accountNumber)}`)
    const t: TransferDto = { id: crypto.randomUUID(), fromAccountNumber: from.accountNumber, toAccountNumber: to.accountNumber, toName: toUser.fullName, amount: money(amount), note: req.note, status: 'COMPLETED', createdAt: new Date().toISOString(), source: 'REQUEST' }
    db.transfers.unshift({ ...t, userId: r.user.id })
    req.status = 'PAID'
    req.transferId = t.id
    notify(toUser.id, 'MONEY_RECEIVED', 'Request paid', `${r.user.fullName} paid your request for ${inr(amount)}.`)
    idem.save(201, t)
    return json(t, 201)
  }),
  http.post(`${BASE}/payment-requests/:id/decline`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const me = handleOf(r.user.id)
    const req = db.v2.requests.find((x) => x.id === params.id && (x.toHandle === me || x.fromHandle === me))
    if (!req) return notFound(request, 'Request')
    if (req.status !== 'OPEN') return error(409, 'INVALID_STATE', 'This request is no longer open', request)
    const b = (await request.json().catch(() => ({}))) as { block?: unknown }
    req.status = req.fromHandle === me ? 'CANCELLED' : 'DECLINED'
    if (b.block === true && req.toHandle === me) db.v2.blocked.push({ userId: r.user.id, blockedHandle: req.fromHandle })
    return json(req)
  }),

  // Scheduled transfers
  http.post(`${BASE}/scheduled-transfers`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    const from = ownAccount(r.user.id, b.fromAccountId)
    if (!from) return notFound(request, 'Account')
    let to
    if (typeof b.toHandle === 'string') {
      const h = userByHandle(b.toHandle)
      to = h ? db.accounts.get(h.accountId) : undefined
      if (!to) return invalid(request, 'toHandle', 'handle not found')
    } else {
      to = [...db.accounts.values()].find((a) => a.accountNumber === b.toAccountNumber)
      if (!to) return invalid(request, 'toAccountNumber', 'account not found')
    }
    if (to.id === from.id) return invalid(request, 'toAccountNumber', 'must differ from the source account')
    if (!isMoney(b.amount) || new Decimal(String(b.amount)).lt(1) || new Decimal(String(b.amount)).gt(200000))
      return invalid(request, 'amount', 'must be between 1.00 and 200000.00')
    if (!['ONCE', 'WEEKLY', 'MONTHLY'].includes(String(b.frequency))) return invalid(request, 'frequency', 'must be ONCE, WEEKLY or MONTHLY')
    const start = typeof b.startDate === 'string' ? b.startDate : ''
    if (!IST_DATE.test(start) || start <= todayIst()) return invalid(request, 'startDate', 'must be a future date')
    if (b.frequency === 'MONTHLY' && Number(start.slice(8)) > 28) return invalid(request, 'startDate', 'monthly transfers run on day 1–28')
    if (b.endDate !== undefined && (typeof b.endDate !== 'string' || b.endDate <= start)) return invalid(request, 'endDate', 'must be after the start date')
    if (b.count !== undefined && (typeof b.count !== 'number' || !Number.isInteger(b.count) || b.count < 1 || b.count > 120)) return invalid(request, 'count', 'must be 1–120')
    const owner = userById(to.userId)!
    const dto: ScheduledTransferDto & { userId: string; toAccountNumber: string } = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      fromAccountId: from.id,
      toAccountNumber: to.accountNumber,
      toAccountNumberMasked: last4(to.accountNumber),
      toName: owner.fullName,
      amount: money(String(b.amount)),
      note: typeof b.note === 'string' && b.note.trim() ? b.note.trim().slice(0, 80) : null,
      frequency: b.frequency as ScheduledTransferDto['frequency'],
      nextRunDate: start,
      endDate: typeof b.endDate === 'string' ? b.endDate : null,
      remainingCount: b.frequency === 'ONCE' ? 1 : typeof b.count === 'number' ? b.count : null,
      status: 'ACTIVE',
      lastRun: null,
    }
    db.v2.scheduled.unshift(dto)
    const out = publicSchedule(dto)
    idem.save(201, out)
    return json(out, 201)
  }),
  http.get(`${BASE}/scheduled-transfers`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.scheduled.filter((x) => x.userId === r.user.id).map(publicSchedule))
  }),
  http.post(`${BASE}/scheduled-transfers/:id/:action`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const s = db.v2.scheduled.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!s) return notFound(request, 'Scheduled transfer')
    if (params.action === 'pause') {
      if (s.status !== 'ACTIVE') return error(409, 'INVALID_STATE', 'Only active schedules can be paused', request)
      s.status = 'PAUSED'
    } else if (params.action === 'resume') {
      if (s.status !== 'PAUSED') return error(409, 'INVALID_STATE', 'Only paused schedules can resume', request)
      s.status = 'ACTIVE'
      if (s.nextRunDate && s.nextRunDate <= todayIst()) s.nextRunDate = addDays(todayIst(), 1)
    } else return notFound(request)
    return json(publicSchedule(s))
  }),
  http.delete(`${BASE}/scheduled-transfers/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const s = db.v2.scheduled.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!s) return notFound(request, 'Scheduled transfer')
    s.status = 'CANCELLED'
    s.nextRunDate = null
    return new Response(null, { status: 204 })
  }),

  // Bills
  http.get(`${BASE}/billers`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const c = new URL(request.url).searchParams.get('category')
    return json(db.v2.billers.filter((x) => !c || x.category === c))
  }),
  http.post(`${BASE}/billers/:id/fetch`, async ({ request, params }) => {
    await delay(700)
    const r = requireUser(request)
    if (!r.user) return r.res
    const biller = db.v2.billers.find((x) => x.id === params.id)
    if (!biller) return notFound(request, 'Biller')
    const b = (await request.json()) as { consumerNumber?: unknown }
    const n = typeof b.consumerNumber === 'string' ? b.consumerNumber.trim() : ''
    if (!new RegExp(biller.consumerNumberPattern).test(n)) return invalid(request, 'consumerNumber', `isn't a valid ${biller.consumerNumberLabel.toLowerCase()}`)
    const billId = crypto.randomUUID()
    const amount = money(billAmount(biller.id, n))
    const dueDate = addDays(todayIst(), 9)
    db.v2.bills.set(billId, { userId: r.user.id, billerId: biller.id, consumerNumber: n, amount, dueDate })
    return json({ billId, amount, dueDate, customerNameMasked: maskName(r.user.fullName) })
  }),
  http.post(`${BASE}/bill-payments`, async ({ request }) => {
    await delay(600)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { billId?: unknown; fromAccountId?: unknown }
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    const bill = typeof b.billId === 'string' ? db.v2.bills.get(b.billId) : undefined
    if (!bill || bill.userId !== r.user.id) return notFound(request, 'Bill')
    if (db.v2.billPayments.some((p) => p.transactionId === `bill:${b.billId}`)) return error(409, 'INVALID_STATE', 'This bill is already paid', request)
    const from = ownAccount(r.user.id, b.fromAccountId)
    if (!from) return notFound(request, 'Account')
    if (from.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    if (available(from).lt(bill.amount)) return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
    const biller = db.v2.billers.find((x) => x.id === bill.billerId)!
    const tx = move(from, null, new Decimal(bill.amount), 'BILL_PAYMENT', `${biller.name} bill`, '', biller.name)
    const dto = { id: crypto.randomUUID(), userId: r.user.id, billerId: biller.id, billerName: biller.name, category: biller.category, consumerNumberMasked: last4(bill.consumerNumber), amount: bill.amount, fromAccountId: from.id, transactionId: tx.id, createdAt: new Date().toISOString() }
    db.v2.billPayments.unshift(dto)
    db.v2.bills.delete(String(b.billId))
    const { userId: _u, ...out } = dto
    idem.save(201, out)
    return json(out, 201)
  }),
  http.get(`${BASE}/bill-payments`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(paginate(db.v2.billPayments.filter((x) => x.userId === r.user.id).map(({ userId: _u, ...x }) => x), new URL(request.url)))
  }),

  // Split groups
  http.get(`${BASE}/split-groups`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.groups.filter((g) => g.memberIds.includes(r.user.id)).map(withBalances))
  }),
  http.get(`${BASE}/split-groups/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const g = db.v2.groups.find((x) => x.id === params.id && x.memberIds.includes(r.user.id))
    return g ? json(withBalances(g)) : notFound(request, 'Group')
  }),
  http.post(`${BASE}/split-groups`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { name?: unknown; memberHandles?: unknown }
    const name = typeof b.name === 'string' ? b.name.trim() : ''
    if (!name || name.length > 40) return invalid(request, 'name', 'must be 1–40 characters')
    if (!Array.isArray(b.memberHandles) || b.memberHandles.length < 1 || b.memberHandles.length > 19)
      return invalid(request, 'memberHandles', 'add 1–19 people')
    const ids = [r.user.id]
    for (const h of b.memberHandles) {
      const u = typeof h === 'string' ? userByHandle(h) : null
      if (!u) return invalid(request, 'memberHandles', `${String(h)} not found`)
      if (!ids.includes(u.userId)) ids.push(u.userId)
    }
    const g = { id: crypto.randomUUID(), name, memberIds: ids, members: [], expenses: [], createdAt: new Date().toISOString() }
    db.v2.groups.unshift(g)
    return json(withBalances(g), 201)
  }),
  http.post(`${BASE}/split-groups/:id/expenses`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const g = db.v2.groups.find((x) => x.id === params.id && x.memberIds.includes(r.user.id))
    if (!g) return notFound(request, 'Group')
    const b = (await request.json()) as { paidByHandle?: unknown; amount?: unknown; description?: unknown; split?: { type?: unknown; shares?: unknown } }
    const handles = g.memberIds.map((id) => handleOf(id))
    if (typeof b.paidByHandle !== 'string' || !handles.includes(b.paidByHandle)) return invalid(request, 'paidByHandle', 'must be a group member')
    if (!isMoney(b.amount) || new Decimal(b.amount).lt(1)) return invalid(request, 'amount', 'must be at least 1.00')
    const description = typeof b.description === 'string' ? b.description.trim() : ''
    if (!description || description.length > 60) return invalid(request, 'description', 'must be 1–60 characters')
    const type = b.split?.type
    const total = new Decimal(b.amount)
    let shares: Array<{ handle: string; amount: string }> = []
    if (type === 'EXACT' || type === 'PERCENT') {
      const raw = Array.isArray(b.split?.shares) ? (b.split.shares as Array<{ handle: string; value: string }>) : []
      let sum = new Decimal(0)
      for (const s of raw) {
        if (!handles.includes(s.handle) || !/^\d{1,12}(\.\d{1,2})?$/.test(s.value)) return invalid(request, 'split.shares', 'invalid share')
        const amt = type === 'EXACT' ? new Decimal(s.value) : total.times(s.value).div(100).toDecimalPlaces(2, Decimal.ROUND_DOWN)
        sum = sum.plus(type === 'EXACT' ? amt : new Decimal(s.value))
        shares.push({ handle: s.handle, amount: money(amt) })
      }
      if (type === 'EXACT' && !sum.eq(total)) return invalid(request, 'split.shares', 'shares must add up to the amount')
      if (type === 'PERCENT') {
        if (!sum.eq(100)) return invalid(request, 'split.shares', 'percentages must add up to 100')
        const allocated = shares.reduce((s, x) => s.plus(x.amount), new Decimal(0))
        const payer = shares.find((x) => x.handle === b.paidByHandle)
        if (payer) payer.amount = money(new Decimal(payer.amount).plus(total.minus(allocated)))
      }
    } else if (type !== 'EQUAL') return invalid(request, 'split.type', 'must be EQUAL, EXACT or PERCENT')
    else shares = []
    const e = { id: crypto.randomUUID(), paidByHandle: b.paidByHandle, amount: money(total), description, splitType: type as 'EQUAL' | 'EXACT' | 'PERCENT', shares, createdAt: new Date().toISOString() }
    g.expenses.push(e)
    const view = withBalances(g).expenses.find((x) => x.id === e.id)
    return json(view, 201)
  }),
  http.post(`${BASE}/split-groups/:id/settle`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const g = db.v2.groups.find((x) => x.id === params.id && x.memberIds.includes(r.user.id))
    if (!g) return notFound(request, 'Group')
    const view = withBalances(g)
    const me = handleOf(r.user.id)!
    // The caller asks everyone who owes the group for what they owe *the caller*, capped by the caller's credit.
    const mine = view.members.find((m) => m.handle === me)
    let credit = new Decimal(mine?.balance ?? '0')
    const out: PaymentRequestDto[] = []
    for (const m of view.members) {
      if (m.handle === me || credit.lte(0)) continue
      const owes = new Decimal(m.balance).neg()
      if (owes.lte(0)) continue
      const amt = owes.lt(credit) ? owes : credit
      credit = credit.minus(amt)
      const req: PaymentRequestDto = { id: crypto.randomUUID(), fromHandle: me, toHandle: m.handle, amount: money(amt), note: `Settle up · ${g.name}`, status: 'OPEN', transferId: null, expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(), createdAt: new Date().toISOString() }
      db.v2.requests.unshift(req)
      out.push(req)
    }
    return json(out)
  }),

  // Razorpay top-ups (§7.9). The mock "Checkout" lives in the UI's dev-only simulator.
  http.post(`${BASE}/accounts/:id/topups`, async ({ request, params }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.razorpay.enabled) return error(503, 'GATEWAY_UNAVAILABLE', 'Gateway unavailable', request)
    const acc = ownAccount(r.user.id, params.id)
    if (!acc) return notFound(request, 'Account')
    const b = (await request.json()) as { amount?: unknown }
    const idem = idempotent(request, r.user.id, { id: params.id, ...b })
    if (idem.early) return idem.early
    if (acc.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    if (!isMoney(b.amount) || new Decimal(b.amount).lt(1) || new Decimal(b.amount).gt(100000))
      return invalid(request, 'amount', 'must be between 1.00 and 100000.00')
    const now = new Date().toISOString()
    const dto: TopupDto & { userId: string } = { id: crypto.randomUUID(), userId: r.user.id, accountId: acc.id, amount: money(b.amount), currency: 'INR', status: 'CREATED', gateway: 'RAZORPAY', gatewayOrderId: `order_${crypto.randomUUID().replace(/-/g, '').slice(0, 14)}`, gatewayKeyId: 'rzp_test_mock000000', transactionId: null, failureReason: null, createdAt: now, updatedAt: now }
    db.v2.topups.unshift(dto)
    const { userId: _u, ...out } = dto
    idem.save(201, out)
    return json(out, 201)
  }),
  http.post(`${BASE}/topups/:id/verify`, async ({ request, params }) => {
    await delay(700)
    const r = requireUser(request)
    if (!r.user) return r.res
    const t = db.v2.topups.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!t) return notFound(request, 'Top-up')
    const { userId: _u0, ...current } = t
    if (t.status === 'CREDITED') return json(current)
    const b = (await request.json()) as { gatewayPaymentId?: unknown; gatewaySignature?: unknown }
    // Mock signature rule: the simulator signs with "sig_" + paymentId. Anything else fails.
    if (typeof b.gatewayPaymentId !== 'string' || b.gatewaySignature !== `sig_${b.gatewayPaymentId}`)
      return error(400, 'PAYMENT_VERIFICATION_FAILED', 'Signature mismatch', request)
    const acc = db.accounts.get(t.accountId)!
    // Credit GATEWAY_CLEARING → customer (frozen accounts can still receive, F-ACC-3).
    const credit = postLine(acc, 'CREDIT', new Decimal(t.amount), 'TOPUP', 'Added via Razorpay', 'Razorpay', crypto.randomUUID())
    t.status = 'CREDITED'
    t.transactionId = credit.id
    t.updatedAt = credit.createdAt
    const { userId: _u, ...out } = t
    return json(out)
  }),
  http.get(`${BASE}/topups/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const t = db.v2.topups.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!t) return notFound(request, 'Top-up')
    const { userId: _u, ...out } = t
    return json(out)
  }),
  http.get(`${BASE}/accounts/:id/topups`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!ownAccount(r.user.id, params.id)) return notFound(request, 'Account')
    return json(paginate(db.v2.topups.filter((x) => x.accountId === params.id).map(({ userId: _u, ...x }) => x), new URL(request.url)))
  }),
]
