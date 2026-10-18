/** MSW handlers: savings and insights (§12.5). */
import { delay, http } from 'msw'
import type { BudgetDto, Category, FdRateDto, FixedDepositDto, ForecastDto, TransactionDto } from '@/api/types'
import { BASE, db, error, idempotent, isMoney, last4, ownAccounts } from '../handlers'
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
  pct,
  requireUser,
  stepUpGate,
  todayIst,
} from './common'
import { prng } from './state'

export const FD_RATES: FdRateDto[] = [
  { minDays: 7, maxDays: 45, annualRate: '4.50' },
  { minDays: 46, maxDays: 179, annualRate: '5.75' },
  { minDays: 180, maxDays: 364, annualRate: '6.50' },
  { minDays: 365, maxDays: 729, annualRate: '7.10' },
  { minDays: 730, maxDays: 1825, annualRate: '7.25' },
]
const rateFor = (days: number) => FD_RATES.find((r) => days >= r.minDays && days <= r.maxDays)

export const CATEGORY_LABEL: Record<Category, string> = {
  FOOD: 'Food & dining',
  GROCERIES: 'Groceries',
  SHOPPING: 'Shopping',
  TRANSPORT: 'Transport',
  BILLS: 'Bills & utilities',
  RENT: 'Rent',
  ENTERTAINMENT: 'Entertainment',
  HEALTH: 'Health',
  EDUCATION: 'Education',
  TRAVEL: 'Travel',
  TRANSFERS: 'Transfers',
  INVESTMENTS: 'Investments',
  LOANS: 'Loans',
  INCOME: 'Income',
  OTHER: 'Other',
}

/** Deterministic category for a transaction (F-INS-1), honouring user overrides. */
export function categorize(t: TransactionDto): Category {
  const o = db.v2.categoryOverrides.get(t.id)
  if (o) return o
  switch (t.type) {
    case 'TRADE':
      return 'INVESTMENTS'
    case 'LOAN_REPAYMENT':
    case 'LOAN_DISBURSAL':
      return 'LOANS'
    case 'BILL_PAYMENT':
      return 'BILLS'
    case 'DEPOSIT':
    case 'TOPUP':
    case 'INTEREST':
      return 'INCOME'
    default:
      break
  }
  const d = `${t.description} ${t.counterparty ?? ''}`.toLowerCase()
  if (/rent/.test(d)) return 'RENT'
  if (/swiggy|zomato|dinner|lunch|cafe|toit|restaurant/.test(d)) return 'FOOD'
  if (/uber|ola|cab|metro|fuel|scooter/.test(d)) return 'TRANSPORT'
  if (/amazon|flipkart|myntra|store/.test(d)) return 'SHOPPING'
  if (/movie|concert|netflix|spotify/.test(d)) return 'ENTERTAINMENT'
  if (t.direction === 'CREDIT') return 'INCOME'
  return 'TRANSFERS'
}

/** A synthetic 6-month spending history per category, so charts have shape (mock only). */
export function syntheticSpend(userId: string, monthsBack: number): Map<Category, Decimal> {
  const rnd = prng(userId.charCodeAt(0) * 97 + monthsBack * 13)
  const base: Array<[Category, number]> = [
    ['RENT', 12000],
    ['FOOD', 6200],
    ['GROCERIES', 4800],
    ['SHOPPING', 5200],
    ['TRANSPORT', 2400],
    ['BILLS', 4100],
    ['ENTERTAINMENT', 1900],
    ['HEALTH', 900],
    ['TRAVEL', monthsBack === 2 ? 14500 : 1200],
  ]
  return new Map(
    base.map(([c, v]) => [c, new Decimal(Math.round(v * (0.75 + rnd() * 0.5) * 100)).div(100)]),
  )
}

function fdView({ userId: _u, fromAccountId: _f, ...fd }: FixedDepositDto & { userId: string; fromAccountId: string }): FixedDepositDto {
  if (fd.status !== 'ACTIVE') return fd
  const held = Math.max(0, Math.round((Date.parse(todayIst()) - Date.parse(fd.startDate)) / 86_400_000))
  const accrued = new Decimal(fd.principal).times(fd.annualRate).div(100).times(Math.min(held, fd.tenureDays)).div(365).toDecimalPlaces(2)
  return { ...fd, accruedInterest: money(accrued) }
}

export const savingsHandlers = [
  http.get(`${BASE}/accounts/:id/round-up`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!ownAccount(r.user.id, params.id)) return notFound(request, 'Account')
    const ru = db.v2.roundUps.get(String(params.id))
    return json({ accountId: params.id, enabled: false, roundTo: 10, goalAccountId: '', ...ru })
  }),
  http.put(`${BASE}/accounts/:id/round-up`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const acc = ownAccount(r.user.id, params.id)
    if (!acc || (acc.type !== 'SAVINGS' && acc.type !== 'CHECKING')) return notFound(request, 'Account')
    const b = (await request.json()) as { enabled?: unknown; roundTo?: unknown; goalAccountId?: unknown }
    if (typeof b.enabled !== 'boolean') return invalid(request, 'enabled', 'must be true or false')
    if (![10, 50, 100].includes(Number(b.roundTo))) return invalid(request, 'roundTo', 'must be 10, 50 or 100')
    const goal = ownAccount(r.user.id, b.goalAccountId)
    if (b.enabled && (!goal || goal.type !== 'GOAL')) return invalid(request, 'goalAccountId', 'pick one of your goals')
    const v = { enabled: b.enabled, roundTo: Number(b.roundTo) as 10 | 50 | 100, goalAccountId: goal?.id ?? '' }
    db.v2.roundUps.set(acc.id, v)
    return json({ accountId: acc.id, ...v })
  }),

  http.get(`${BASE}/fixed-deposits/rates`, () => json(FD_RATES)),
  http.get(`${BASE}/fixed-deposits`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.fds.filter((x) => x.userId === r.user.id).map(fdView))
  }),
  http.post(`${BASE}/fixed-deposits`, async ({ request }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    // Backend V2-M3: no KYC gate on FDs; someone else's account → 404, a goal/FD → 400.
    const from = ownAccount(r.user.id, b.fromAccountId)
    if (!from) return notFound(request, 'Account')
    if (from.type !== 'SAVINGS' && from.type !== 'CHECKING') return invalid(request, 'fromAccountId', 'must be a savings or checking account')
    if (!isMoney(b.amount) || new Decimal(String(b.amount)).lt(1000) || new Decimal(String(b.amount)).gt(1000000))
      return invalid(request, 'amount', 'must be between 1000.00 and 1000000.00')
    const days = Number(b.tenureDays)
    const rate = Number.isInteger(days) ? rateFor(days) : undefined
    if (!rate) return invalid(request, 'tenureDays', 'must be 7–1825 days')
    if (b.onMaturity !== 'PAYOUT' && b.onMaturity !== 'RENEW') return invalid(request, 'onMaturity', 'must be PAYOUT or RENEW')
    if (from.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    const amount = new Decimal(String(b.amount))
    if (available(from).lt(amount)) return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
    const fdAcc = { id: crypto.randomUUID(), userId: r.user.id, accountNumber: `5021${String(Math.floor(Math.random() * 1e8)).padStart(8, '0')}`, type: 'FIXED_DEPOSIT' as const, status: 'ACTIVE' as const, balance: '0.0000', currency: 'INR' as const, createdAt: new Date().toISOString() }
    db.accounts.set(fdAcc.id, fdAcc)
    move(from, fdAcc, amount, 'TRANSFER', 'Fixed deposit booked', 'Fixed deposit opened', `FD ${last4(fdAcc.accountNumber)}`, `From ${last4(from.accountNumber)}`)
    const start = todayIst()
    const interest = amount.times(rate.annualRate).div(100).times(days).div(365).toDecimalPlaces(2)
    const fd = { id: crypto.randomUUID(), userId: r.user.id, fromAccountId: from.id, accountNumberMasked: last4(fdAcc.accountNumber), principal: money(amount), annualRate: rate.annualRate, tenureDays: days, startDate: start, maturityDate: addDays(start, days), accruedInterest: '0.0000', maturityAmount: money(amount.plus(interest)), onMaturity: b.onMaturity as 'PAYOUT' | 'RENEW', status: 'ACTIVE' as const }
    db.v2.fds.unshift(fd)
    const out = fdView(fd)
    idem.save(201, out)
    return json(out, 201)
  }),
  http.post(`${BASE}/fixed-deposits/:id/close`, async ({ request, params }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    const fd = db.v2.fds.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!fd) return notFound(request, 'Fixed deposit')
    const idem = idempotent(request, r.user.id, { id: fd.id })
    if (idem.early) return idem.early
    if (fd.status !== 'ACTIVE') return error(409, 'INVALID_STATE', 'This deposit is already closed', request)
    const gate = stepUpGate(request, r.user, 'FD_CLOSE')
    if (gate) return gate
    // Premature closure: rate for the period held, minus 1% (F-SAV-3).
    const held = Math.max(1, Math.round((Date.parse(todayIst()) - Date.parse(fd.startDate)) / 86_400_000))
    const heldRate = rateFor(held) ?? FD_RATES[0]!
    const minus1 = new Decimal(heldRate.annualRate).minus(1)
    const rate = minus1.isNegative() ? new Decimal(0) : minus1
    const interest = new Decimal(fd.principal).times(rate).div(100).times(held).div(365).toDecimalPlaces(2)
    const to = db.accounts.get(fd.fromAccountId)!
    to.balance = new Decimal(to.balance).plus(fd.principal).plus(interest).toFixed(4)
    fd.status = 'CLOSED'
    fd.accruedInterest = money(interest)
    fd.maturityAmount = money(new Decimal(fd.principal).plus(interest))
    const out = fdView(fd)
    idem.save(200, out)
    return json(out)
  }),

  http.get(`${BASE}/insights/spending`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const groupBy = url.searchParams.get('groupBy') === 'month' ? 'month' : 'category'
    if (groupBy === 'month') {
      const out = []
      for (let m = 5; m >= 0; m--) {
        const d = new Date()
        d.setUTCMonth(d.getUTCMonth() - m, 1)
        const total = [...syntheticSpend(r.user.id, m).values()].reduce((s, v) => s.plus(v), new Decimal(0))
        out.push({ key: d.toISOString().slice(0, 7), amount: money(total), count: 30 + m * 3 })
      }
      return json(out)
    }
    const spend = syntheticSpend(r.user.id, 0)
    // Real mock transactions in the range are added on top of the synthetic baseline.
    const ids = new Set(ownAccounts(r.user.id).map((a) => a.id))
    for (const t of db.transactions) {
      if (!ids.has(t.accountId) || t.direction !== 'DEBIT') continue
      const c = categorize(t)
      if (c === 'TRANSFERS' || c === 'INVESTMENTS') continue
      spend.set(c, (spend.get(c) ?? new Decimal(0)).plus(t.amount))
    }
    return json(
      [...spend.entries()]
        .map(([key, amount]) => ({ key, amount: money(amount), count: Math.max(1, Math.round(amount.div(700).toNumber())) }))
        .sort((a, b) => new Decimal(b.amount).cmp(a.amount)),
    )
  }),
  http.patch(`${BASE}/transactions/:id/category`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const t = db.transactions.find((x) => x.id === params.id)
    if (!t || !ownAccount(r.user.id, t.accountId)) return notFound(request, 'Transaction')
    const b = (await request.json()) as { category?: unknown; applyToCounterparty?: unknown }
    if (typeof b.category !== 'string' || !(b.category in CATEGORY_LABEL)) return invalid(request, 'category', 'unknown category')
    const cat = b.category as Category
    db.v2.categoryOverrides.set(t.id, cat)
    if (b.applyToCounterparty === true && t.counterparty)
      for (const x of db.transactions) if (x.counterparty === t.counterparty) db.v2.categoryOverrides.set(x.id, cat)
    return json({ ...t, category: cat })
  }),
  http.get(`${BASE}/budgets`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(budgetsFor(r.user.id))
  }),
  http.put(`${BASE}/budgets`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as unknown
    if (!Array.isArray(b) || b.length > 15) return invalid(request, 'budgets', 'send up to 15 budgets')
    const seen = new Set<string>()
    for (const x of b as Array<{ category?: unknown; monthlyLimit?: unknown }>) {
      if (typeof x.category !== 'string' || !(x.category in CATEGORY_LABEL) || seen.has(x.category)) return invalid(request, 'category', 'unknown or duplicate category')
      if (!isMoney(x.monthlyLimit) || new Decimal(x.monthlyLimit).lt(100)) return invalid(request, 'monthlyLimit', 'must be at least 100.00')
      seen.add(x.category)
    }
    db.v2.budgets.set(r.user.id, (b as Array<{ category: Category; monthlyLimit: string }>).map((x) => ({ category: x.category, monthlyLimit: money(x.monthlyLimit) })))
    return json(budgetsFor(r.user.id))
  }),
  http.get(`${BASE}/insights/forecast`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const days = Math.min(90, Math.max(7, Number(new URL(request.url).searchParams.get('days') ?? 30)))
    const cash = ownAccounts(r.user.id).filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING')
    let bal = cash.reduce((s, a) => s.plus(a.balance), new Decimal(0))
    const rnd = prng(42)
    const today = todayIst()
    const points = []
    const warnings: ForecastDto['warnings'] = []
    for (let i = 0; i < days; i++) {
      const date = addDays(today, i)
      const dom = Number(date.slice(8))
      if (i > 0) bal = bal.minus(new Decimal(Math.round(350 + rnd() * 900)))
      if (dom === 1) bal = bal.plus(82000) // salary pattern
      if (dom === 5) bal = bal.minus('9414.69') // EMI
      for (const s of db.v2.scheduled.filter((x) => x.userId === r.user.id && x.status === 'ACTIVE' && x.nextRunDate === date)) bal = bal.minus(s.amount)
      points.push({ date, balance: money(bal) })
      if (bal.lt(1000) && !warnings.some((w) => w.code === 'LOW_BALANCE')) warnings.push({ date, code: 'LOW_BALANCE' as const, message: `Your balance could drop to ${bal.toFixed(0)} rupees on ${date}.` })
    }
    return json({ points, warnings })
  }),
  http.get(`${BASE}/insights/net-worth`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const accts = ownAccounts(r.user.id)
    const sum = (types: string[]) => accts.filter((a) => types.includes(a.type)).reduce((s, a) => s.plus(a.balance), new Decimal(0))
    const cash = sum(['SAVINGS', 'CHECKING'])
    const goals = sum(['GOAL'])
    const fds = sum(['FIXED_DEPOSIT'])
    const investments = db.holdings
      .filter((h) => h.userId === r.user.id)
      .reduce((s, h) => s.plus(new Decimal(db.instruments.get(h.symbol)?.price ?? '0').times(h.quantity)), new Decimal(0))
    const loans = db.loans.filter((l) => l.userId === r.user.id && l.status === 'ACTIVE').reduce((s, l) => s.plus(l.outstandingPrincipal), new Decimal(0))
    const current = cash.plus(goals).plus(fds).plus(investments).minus(loans)
    const months = Math.min(24, Number(new URL(request.url).searchParams.get('months') ?? 12))
    const rnd = prng(7)
    const history = []
    let v = current
    for (let m = 0; m < months; m++) {
      const d = new Date()
      d.setUTCMonth(d.getUTCMonth() - m, 1)
      history.unshift({ month: d.toISOString().slice(0, 7), value: money(v) })
      v = v.minus(new Decimal(Math.round(14000 + rnd() * 16000)))
    }
    return json({ current: money(current), breakdown: { cash: money(cash), goals: money(goals), fixedDeposits: money(fds), investments: money(investments), loans: money(loans) }, history })
  }),
]

export function budgetsFor(userId: string): BudgetDto[] {
  const spend = syntheticSpend(userId, 0)
  return (db.v2.budgets.get(userId) ?? []).map((b) => {
    const spent = (spend.get(b.category) ?? new Decimal(0)).times(new Date().getUTCDate()).div(30).toDecimalPlaces(2)
    return { category: b.category, monthlyLimit: b.monthlyLimit, spent: money(spent), pct: pct(spent.div(b.monthlyLimit).times(100)) }
  })
}
