/**
 * MSW handlers: analytics (§12.13). Admin numbers come from a deterministic synthetic "bank"
 * (seeded per IST day, so a chart is identical on every load) with a gentle growth trend; personal
 * numbers are anchored to the demo user's real mock balances. Values are decimal strings.
 */
import { delay, http } from 'msw'
import type {
  AnalyticsMetric,
  BreakdownDimension,
  BreakdownRow,
  Granularity,
  KpiDto,
  KpiKey,
  MetricUnit,
  SeriesPoint,
} from '@/api/types'
import { audit, BASE, db, last4, ownAccounts } from '../handlers'
import {
  addDays,
  dateRange,
  daysBetween,
  Decimal,
  invalid,
  json,
  money,
  notFound,
  pct,
  requireAdmin,
  requireUser,
  todayIst,
} from './common'
import { CATEGORY_LABEL, syntheticSpend } from './savings'
import { prng } from './state'

// ── Synthetic business model ───────────────────────────────────────────────────────────
const EPOCH = '2026-01-01'
const seedOf = (date: string) => Number(date.replace(/-/g, '')) % 2_147_483_647

interface Day {
  newUsers: number
  activeUsers: number
  deposits: Decimal
  transferVolume: Decimal
  transferCount: number
  tradeVolume: Decimal
  tradeCount: number
  loanDisbursed: Decimal
  loanRepaid: Decimal
  interestIncome: Decimal
  overdue: Decimal
  failed: number
  copilotTurns: number
  aiCost: Decimal
  autopilotRuns: number
  fraudCases: number
}

const dayCache = new Map<string, Day>()
function day(date: string): Day {
  const hit = dayCache.get(date)
  if (hit) return hit
  const r = prng(seedOf(date))
  const t = Math.max(0, daysBetween(EPOCH, date)) // growth factor
  const g = 1 + t / 365
  const dow = new Date(`${date}T00:00:00Z`).getUTCDay()
  const weekend = dow === 0 || dow === 6 ? 0.72 : 1
  const v = (base: number, spread = 0.35) => base * g * weekend * (1 - spread / 2 + r() * spread)
  const d: Day = {
    newUsers: Math.round(v(9)),
    activeUsers: Math.round(v(260, 0.2)),
    deposits: new Decimal(Math.round(v(410000))),
    transferVolume: new Decimal(Math.round(v(1_850_000))),
    transferCount: Math.round(v(540)),
    tradeVolume: new Decimal(Math.round(v(2_300_000, 0.6))),
    tradeCount: Math.round(v(190, 0.6)),
    loanDisbursed: new Decimal(r() < 0.55 ? Math.round(v(320000, 0.9)) : 0),
    loanRepaid: new Decimal(Math.round(v(145000))),
    interestIncome: new Decimal(Math.round(v(21000))),
    overdue: new Decimal(Math.round(v(38000, 0.5))),
    failed: Math.round(v(14, 0.8)),
    copilotTurns: Math.round(v(310, 0.4)),
    aiCost: new Decimal(v(3.8, 0.4)).toDecimalPlaces(2),
    autopilotRuns: Math.round(v(120, 0.3)),
    fraudCases: Math.round(v(1.4, 1.2)),
  }
  dayCache.set(date, d)
  return d
}

function eachDay(from: string, to: string) {
  const out: string[] = []
  for (let d = from; d <= to; d = addDays(d, 1)) out.push(d)
  return out
}

const METRIC: Record<
  AnalyticsMetric,
  {
    label: string
    unit: MetricUnit
    good: 'UP' | 'DOWN'
    available: boolean
    of: (d: Day) => Decimal
    sum: boolean
  }
> = {
  TOTAL_USERS: {
    label: 'Total users',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.newUsers),
    sum: true,
  },
  NEW_USERS: {
    label: 'New users',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.newUsers),
    sum: true,
  },
  ACTIVE_USERS: {
    label: 'Active users',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.activeUsers),
    sum: false,
  },
  CUSTOMER_DEPOSITS: {
    label: 'Customer deposits',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.deposits,
    sum: true,
  },
  NET_INFLOW: {
    label: 'Net inflow',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.deposits.minus(d.loanRepaid.times(0.3)),
    sum: true,
  },
  TRANSFER_VOLUME: {
    label: 'Transfer volume',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.transferVolume,
    sum: true,
  },
  TRANSFER_COUNT: {
    label: 'Transfers',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.transferCount),
    sum: true,
  },
  TRADE_VOLUME: {
    label: 'Trade volume',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.tradeVolume,
    sum: true,
  },
  TRADE_COUNT: {
    label: 'Trades',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.tradeCount),
    sum: true,
  },
  LOAN_DISBURSED: {
    label: 'Loans disbursed',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.loanDisbursed,
    sum: true,
  },
  LOAN_REPAID: {
    label: 'Loans repaid',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.loanRepaid,
    sum: true,
  },
  INTEREST_INCOME: {
    label: 'Interest income',
    unit: 'MONEY',
    good: 'UP',
    available: true,
    of: (d) => d.interestIncome,
    sum: true,
  },
  OVERDUE_AMOUNT: {
    label: 'Overdue amount',
    unit: 'MONEY',
    good: 'DOWN',
    available: true,
    of: (d) => d.overdue,
    sum: false,
  },
  FAILED_MONEY_REQUESTS: {
    label: 'Failed money requests',
    unit: 'COUNT',
    good: 'DOWN',
    available: true,
    of: (d) => new Decimal(d.failed),
    sum: true,
  },
  COPILOT_TURNS: {
    label: 'Copilot turns',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.copilotTurns),
    sum: true,
  },
  AI_COST_USD: {
    label: 'AI cost (USD)',
    unit: 'MONEY',
    good: 'DOWN',
    available: true,
    of: (d) => d.aiCost,
    sum: true,
  },
  AUTOPILOT_RUNS: {
    label: 'Autopilot runs',
    unit: 'COUNT',
    good: 'UP',
    available: true,
    of: (d) => new Decimal(d.autopilotRuns),
    sum: true,
  },
  FRAUD_CASES: {
    label: 'Fraud cases',
    unit: 'COUNT',
    good: 'DOWN',
    available: true,
    of: (d) => new Decimal(d.fraudCases),
    sum: true,
  },
}

const BASE_USERS = 1140
const usersUpTo = (date: string) =>
  BASE_USERS + eachDay(EPOCH, date).reduce((s, d) => s + day(d).newUsers, 0)

function aggregate(metric: AnalyticsMetric, from: string, to: string): Decimal {
  if (metric === 'TOTAL_USERS') return new Decimal(usersUpTo(to))
  const m = METRIC[metric]
  const vals = eachDay(from, to).map((d) => m.of(day(d)))
  if (!vals.length) return new Decimal(0)
  const sum = vals.reduce((s, v) => s.plus(v), new Decimal(0))
  return m.sum ? sum : sum.div(vals.length).toDecimalPlaces(0)
}

function bucketStart(date: string, g: Granularity) {
  if (g === 'DAY') return date
  if (g === 'MONTH') return `${date.slice(0, 7)}-01`
  const dow = (new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7 // Monday = 0
  return addDays(date, -dow)
}

function series(metric: AnalyticsMetric, from: string, to: string, g: Granularity): SeriesPoint[] {
  const m = METRIC[metric]
  const buckets = new Map<string, Decimal[]>()
  for (const d of eachDay(from, to)) {
    const k = bucketStart(d, g)
    const v = metric === 'TOTAL_USERS' ? new Decimal(usersUpTo(d)) : m.of(day(d))
    buckets.set(k, [...(buckets.get(k) ?? []), v])
  }
  return [...buckets.entries()].map(([t, vs]) => {
    const total = vs.reduce((s, v) => s.plus(v), new Decimal(0))
    const value =
      metric === 'TOTAL_USERS'
        ? vs.at(-1)!
        : m.sum
          ? total
          : total.div(vs.length).toDecimalPlaces(0)
    return { t, value: m.unit === 'MONEY' ? money(value) : value.toFixed(0) }
  })
}

function kpi(
  key: KpiKey,
  label: string,
  unit: MetricUnit,
  value: Decimal,
  previous: Decimal,
  good: 'UP' | 'DOWN',
): KpiDto {
  const fmt = (d: Decimal) =>
    unit === 'MONEY' ? money(d) : unit === 'PERCENT' ? pct(d) : d.toFixed(0)
  return {
    key,
    label,
    unit,
    value: fmt(value),
    previous: fmt(previous),
    changePct: previous.isZero() ? null : pct(value.minus(previous).div(previous.abs()).times(100)),
    goodDirection: good,
  }
}

function breakdown(
  dim: BreakdownDimension,
  from: string,
  to: string,
): { unit: MetricUnit; rows: Array<Omit<BreakdownRow, 'sharePct'>> } {
  const r = prng(seedOf(from) + dim.length * 7919)
  const scale = daysBetween(from, to) / 30
  const row = (key: string, label: string, base: number, count?: number, money_ = true) => {
    const value = new Decimal(Math.round(base * scale * (0.85 + r() * 0.3)))
    const c =
      count === undefined ? null : Math.max(0, Math.round(count * scale * (0.85 + r() * 0.3)))
    return {
      key,
      label,
      value: money_ ? money(value) : value.toFixed(0),
      count: c,
      suppressed: false,
    }
  }
  switch (dim) {
    case 'TRANSACTION_TYPE':
      return {
        unit: 'MONEY',
        rows: [
          row('TRANSFER', 'Transfers', 55e6, 16000),
          row('TRADE', 'Trades', 69e6, 5700),
          row('DEPOSIT', 'Deposits', 12e6, 2400),
          row('LOAN_REPAYMENT', 'Loan repayments', 4.3e6, 610),
          row('LOAN_DISBURSAL', 'Loan disbursals', 5.2e6, 21),
          row('BILL_PAYMENT', 'Bill payments', 2.1e6, 1800),
          row('TOPUP', 'Razorpay top-ups', 1.4e6, 390),
        ],
      }
    case 'ACCOUNT_TYPE':
      return {
        unit: 'COUNT',
        rows: [
          row('SAVINGS', 'Savings', 1180, undefined, false),
          row('CHECKING', 'Checking', 420, undefined, false),
          row('GOAL', 'Goals', 640, undefined, false),
          row('FIXED_DEPOSIT', 'Fixed deposits', 210, undefined, false),
        ].map((x) => ({ ...x, count: Number(x.value) })),
      }
    case 'ACCOUNT_STATUS':
      return {
        unit: 'COUNT',
        rows: [
          row('ACTIVE', 'Active', 2380, undefined, false),
          row('FROZEN', 'Frozen', 9, undefined, false),
          { key: 'CLOSED', label: 'Closed', value: null, count: null, suppressed: true },
        ],
      }
    case 'INSTRUMENT':
      return {
        unit: 'MONEY',
        rows: [...db.instruments.values()]
          .slice(0, 10)
          .map((i, k) => row(i.symbol, i.name, (11 - k) * 1.1e6, (11 - k) * 70)),
      }
    case 'SECTOR': {
      const sectors = [...new Set([...db.instruments.values()].map((i) => i.sector))]
      return {
        unit: 'MONEY',
        rows: sectors.map((s, k) =>
          row(s, s, (sectors.length - k) * 2.4e6, (sectors.length - k) * 160),
        ),
      }
    }
    case 'ORDER_SIDE':
      return {
        unit: 'MONEY',
        rows: [row('BUY', 'Buy', 38e6, 3100), row('SELL', 'Sell', 31e6, 2600)],
      }
    case 'LOAN_STATUS':
      return {
        unit: 'MONEY',
        rows: [
          row('ACTIVE', 'Active', 41e6, 212),
          row('PENDING', 'Pending', 3.9e6, 14),
          row('CLOSED', 'Closed', 9.1e6, 77),
          row('REJECTED', 'Rejected', 6.2e6, 38),
        ],
      }
    case 'LOAN_PURPOSE':
      return {
        unit: 'MONEY',
        rows: [
          row('HOME', 'Home improvement', 12e6, 51),
          row('EDU', 'Education', 8.4e6, 44),
          row('BIZ', 'Small business', 11e6, 39),
          row('VEHICLE', 'Vehicle', 6.1e6, 33),
          row('MEDICAL', 'Medical', 2.2e6, 17),
          { key: 'WEDDING', label: 'Wedding', value: null, count: null, suppressed: true },
        ],
      }
    case 'FUNDING_SOURCE':
      return {
        unit: 'MONEY',
        rows: [
          row('DEMO', 'Demo deposit', 9.4e6, 2100),
          row('RAZORPAY', 'Razorpay (test)', 2.6e6, 390),
        ],
      }
    case 'ERROR_CODE':
      return {
        unit: 'COUNT',
        rows: [
          row('INSUFFICIENT_FUNDS', 'Insufficient funds', 180, undefined, false),
          row('LIMIT_EXCEEDED', 'Limit exceeded', 74, undefined, false),
          row('STEP_UP_REQUIRED', 'Step-up required', 61, undefined, false),
          row('ACCOUNT_FROZEN', 'Account frozen', 22, undefined, false),
          row('COOLING_PERIOD', 'New-payee cooling', 17, undefined, false),
          row('IDEMPOTENCY_CONFLICT', 'Idempotency conflict', 6, undefined, false),
        ].map((x) => ({ ...x, count: Number(x.value) })),
      }
    case 'SPEND_CATEGORY':
      return {
        unit: 'MONEY',
        rows: (
          [
            'RENT',
            'FOOD',
            'SHOPPING',
            'GROCERIES',
            'BILLS',
            'TRANSPORT',
            'TRAVEL',
            'ENTERTAINMENT',
            'HEALTH',
          ] as const
        ).map((c, k) => row(c, CATEGORY_LABEL[c], (9 - k) * 1.3e6, (9 - k) * 420)),
      }
  }
}

// ── Handlers ───────────────────────────────────────────────────────────────────────────
export const analyticsHandlers = [
  http.get(`${BASE}/admin/analytics/overview`, async ({ request }) => {
    await delay(250)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const range = dateRange(new URL(request.url))
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const n = daysBetween(range.from, range.to)
    const pTo = addDays(range.from, -1)
    const pFrom = addDays(pTo, -(n - 1))
    const k = (m: AnalyticsMetric, key: KpiKey = m) =>
      kpi(
        key,
        METRIC[m].label,
        METRIC[m].unit,
        aggregate(m, range.from, range.to),
        aggregate(m, pFrom, pTo),
        METRIC[m].good,
      )
    const funded = (to: string) => new Decimal(68.4 + (daysBetween(EPOCH, to) % 40) / 10)
    const outstanding = (to: string) => new Decimal(41_200_000 + daysBetween(EPOCH, to) * 31_000)
    const overdueRate = (to: string) => new Decimal(3.1 + (daysBetween(EPOCH, to) % 17) / 20)
    return json({
      from: range.from,
      to: range.to,
      previousFrom: pFrom,
      previousTo: pTo,
      kpis: [
        k('TOTAL_USERS'),
        k('NEW_USERS'),
        k('ACTIVE_USERS'),
        kpi('FUNDED_USERS_PCT', 'Funded users', 'PERCENT', funded(range.to), funded(pTo), 'UP'),
        k('CUSTOMER_DEPOSITS'),
        k('NET_INFLOW'),
        k('TRANSFER_VOLUME'),
        k('TRANSFER_COUNT'),
        k('TRADE_VOLUME'),
        k('TRADE_COUNT'),
        kpi(
          'OUTSTANDING_PRINCIPAL',
          'Outstanding principal',
          'MONEY',
          outstanding(range.to),
          outstanding(pTo),
          'UP',
        ),
        k('INTEREST_INCOME'),
        kpi(
          'OVERDUE_RATE_PCT',
          'Overdue rate',
          'PERCENT',
          overdueRate(range.to),
          overdueRate(pTo),
          'DOWN',
        ),
        k('FAILED_MONEY_REQUESTS'),
      ],
    })
  }),
  http.get(`${BASE}/admin/analytics/timeseries`, async ({ request }) => {
    await delay(200)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const metric = url.searchParams.get('metric') as AnalyticsMetric
    if (!METRIC[metric]) return invalid(request, 'metric', 'unknown metric')
    const g = (url.searchParams.get('granularity') ?? 'DAY') as Granularity
    if (!['DAY', 'WEEK', 'MONTH'].includes(g))
      return invalid(request, 'granularity', 'must be DAY, WEEK or MONTH')
    const range = dateRange(url)
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const n = daysBetween(range.from, range.to)
    const out = {
      metric,
      label: METRIC[metric].label,
      unit: METRIC[metric].unit,
      granularity: g,
      available: METRIC[metric].available,
      points: series(metric, range.from, range.to, g),
    }
    if (url.searchParams.get('compare') === 'true') {
      const pTo = addDays(range.from, -1)
      return json({ ...out, previous: series(metric, addDays(pTo, -(n - 1)), pTo, g) })
    }
    return json(out)
  }),
  http.get(`${BASE}/admin/analytics/breakdown`, async ({ request }) => {
    await delay(200)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const dim = url.searchParams.get('dimension') as BreakdownDimension
    const range = dateRange(url)
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const b = dim ? breakdown(dim, range.from, range.to) : null
    if (!b) return invalid(request, 'dimension', 'unknown dimension')
    const limit = Math.min(50, Number(url.searchParams.get('limit') ?? 10))
    const total = b.rows.reduce((s, x) => s.plus(x.value ?? '0'), new Decimal(0))
    const rows = b.rows
      .slice()
      .sort((a, c) => new Decimal(c.value ?? '0').cmp(a.value ?? '0'))
      .slice(0, limit)
      .map((x) => ({
        ...x,
        sharePct:
          x.value === null || total.isZero()
            ? null
            : pct(new Decimal(x.value).div(total).times(100)),
      }))
    return json({
      dimension: dim,
      unit: b.unit,
      total: b.unit === 'MONEY' ? money(total) : total.toFixed(0),
      rows,
    })
  }),
  http.get(`${BASE}/admin/analytics/funnel`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const range = dateRange(new URL(request.url))
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const reg = aggregate('NEW_USERS', range.from, range.to)
    const steps: Array<[string, string, number]> = [
      ['REGISTERED', 'Registered', 1],
      ['FUNDED', 'Funded an account', 0.71],
      ['FIRST_TRANSFER', 'First transfer', 0.52],
      ['FIRST_TRADE', 'First trade', 0.29],
      ['LOAN_APPLIED', 'Applied for a loan', 0.08],
    ]
    let prev = reg
    return json({
      steps: steps.map(([key, label, f]) => {
        const users = reg.times(f).toDecimalPlaces(0)
        const out = {
          key,
          label,
          users: users.toFixed(0),
          conversionPct: reg.isZero() ? '0.00' : pct(users.div(reg).times(100)),
          stepConversionPct: prev.isZero() ? '0.00' : pct(users.div(prev).times(100)),
        }
        prev = users
        return out
      }),
    })
  }),
  http.get(`${BASE}/admin/analytics/cohorts`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const months = Math.min(
      12,
      Math.max(1, Number(new URL(request.url).searchParams.get('months') ?? 6)),
    )
    const rnd = prng(2026)
    const cohorts = []
    for (let i = months - 1; i >= 0; i--) {
      const d = new Date()
      d.setUTCMonth(d.getUTCMonth() - i, 1)
      const size = Math.round(220 + rnd() * 120 + (months - i) * 18)
      const retention: Array<string | null> = []
      let ret = 100
      for (let m = 0; m <= i; m++) {
        retention.push(m === 0 ? '100.00' : pct(new Decimal(ret)))
        ret = ret * (m === 0 ? 0.58 + rnd() * 0.1 : 0.86 + rnd() * 0.08)
      }
      cohorts.push({
        cohort: d.toISOString().slice(0, 7),
        size: String(size),
        retentionPct: retention,
      })
    }
    return json({ cohorts })
  }),
  http.get(`${BASE}/admin/analytics/loan-book`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    return json({
      outstandingPrincipal: '41862000.0000',
      disbursed: '58410000.0000',
      repaid: '16548000.0000',
      interestEarned: '3920400.0000',
      activeCount: '212',
      approvalRatePct: '74.20',
      avgTicket: '275500.0000',
      overdueAmount: '1408200.0000',
      overdueRatePct: '3.36',
      ageing: [
        { bucket: '1-30', count: '31', amount: '842000.0000' },
        { bucket: '31-60', count: '12', amount: '351400.0000' },
        { bucket: '61-90', count: '5', amount: '149800.0000' },
        { bucket: '90+', count: '2', amount: '65000.0000' },
      ],
      byStatus: [
        { status: 'ACTIVE', count: '212', amount: '41862000.0000' },
        { status: 'PENDING', count: '14', amount: '3900000.0000' },
        { status: 'CLOSED', count: '77', amount: '9100000.0000' },
        { status: 'REJECTED', count: '38', amount: '6200000.0000' },
      ],
    })
  }),
  http.get(`${BASE}/admin/analytics/adoption`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const range = dateRange(new URL(request.url))
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const active = aggregate('ACTIVE_USERS', range.from, range.to)
    const f = (key: string, label: string, share: number, available = true) => ({
      key,
      label,
      users: active.times(share).toFixed(0),
      pct: pct(new Decimal(share * 100)),
      available,
    })
    const turns = aggregate('COPILOT_TURNS', range.from, range.to)
    const cost = aggregate('AI_COST_USD', range.from, range.to)
    return json({
      features: [
        f('HANDLE', 'QuantGen handle', 0.81),
        f('PASSKEY', 'Passkey', 0.37),
        f('GOAL', 'Savings goal', 0.46),
        f('AUTOPILOT', 'Autopilot rule', 0.19),
        f('CARD', 'Virtual card', 0.28),
        f('COPILOT', 'Used Copilot', 0.33),
      ],
      ai: {
        available: db.v2.flags.ai.enabled,
        turns: turns.toFixed(0),
        costUsd: cost.toFixed(2),
        costPerActiveUser: active.isZero()
          ? '0.00'
          : cost.div(active).toDecimalPlaces(4).toFixed(4),
      },
      autopilot: {
        available: db.v2.flags.autopilot.enabled,
        runs: aggregate('AUTOPILOT_RUNS', range.from, range.to).toFixed(0),
        successPct: '96.40',
      },
      fraud: {
        available: true,
        cases: aggregate('FRAUD_CASES', range.from, range.to).toFixed(0),
        releasedPct: '58.30',
      },
    })
  }),
  http.get(`${BASE}/admin/analytics/health`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    return json({
      reconcileBalanced: true,
      checkedAt: new Date(Date.now() - 6 * 60_000).toISOString(),
      sealLagSeconds: '2.4',
      outboxLag: '0',
      moneyErrorRatePct: '0.42',
    })
  }),
  http.get(`${BASE}/admin/analytics/export`, async ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const report = url.searchParams.get('report') ?? 'overview'
    const range = dateRange(url) ?? { from: todayIst(), to: todayIst() }
    const metric = (url.searchParams.get('metric') as AnalyticsMetric) || 'TRANSFER_VOLUME'
    const rows = series(METRIC[metric] ? metric : 'TRANSFER_VOLUME', range.from, range.to, 'DAY')
    audit(r.user, 'ANALYTICS_EXPORTED', 'REPORT', report, `${report} ${range.from}..${range.to}`)
    const csv = `date,${metric.toLowerCase()}\n${rows.map((p) => `${p.t},${p.value}`).join('\n')}\n`
    return new Response(csv, {
      headers: {
        'Content-Type': 'text/csv',
        'Content-Disposition': `attachment; filename="quantgen-${report}.csv"`,
      },
    })
  }),

  // ── Personal ─────────────────────────────────────────────────────────────────────────
  http.get(`${BASE}/analytics/summary`, async ({ request }) => {
    await delay(200)
    const r = requireUser(request)
    if (!r.user) return r.res
    const range = dateRange(new URL(request.url))
    if (!range) return invalid(request, 'from', 'must be on or before to')
    const scale = daysBetween(range.from, range.to) / 30
    const spendNow = [...syntheticSpend(r.user.id, 0).entries()]
    const spendPrev = [...syntheticSpend(r.user.id, 1).values()]
    const spending = spendNow
      .reduce((s, [, v]) => s.plus(v), new Decimal(0))
      .times(scale)
      .toDecimalPlaces(2)
    const prevSpending = spendPrev
      .reduce((s, v) => s.plus(v), new Decimal(0))
      .times(scale)
      .toDecimalPlaces(2)
    const income = new Decimal(82000).times(scale).plus(25000).toDecimalPlaces(2)
    const prevIncome = new Decimal(82000).times(scale).toDecimalPlaces(2)
    const top = spendNow.sort((a, b) => b[1].cmp(a[1]))[0]!
    const rate = (inc: Decimal, sp: Decimal) =>
      inc.isZero() ? null : pct(inc.minus(sp).div(inc).times(100))
    return json({
      from: range.from,
      to: range.to,
      income: money(income),
      spending: money(spending),
      net: money(income.minus(spending)),
      savingsRatePct: rate(income, spending),
      previous: {
        income: money(prevIncome),
        spending: money(prevSpending),
        net: money(prevIncome.minus(prevSpending)),
        savingsRatePct: rate(prevIncome, prevSpending),
      },
      topCategory: {
        key: top[0],
        label: CATEGORY_LABEL[top[0]],
        amount: money(top[1].times(scale)),
      },
      largestSpend: {
        transactionId: '0c0ffee0-0000-4000-8000-000000000003',
        amount: '12000.0000',
        description: 'Rent — October',
        date: addDays(todayIst(), -2),
      },
    })
  }),
  http.get(`${BASE}/analytics/cashflow`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const months = Math.min(
      24,
      Math.max(1, Number(new URL(request.url).searchParams.get('months') ?? 12)),
    )
    const rnd = prng(99)
    const points = []
    for (let m = months - 1; m >= 0; m--) {
      const d = new Date()
      d.setUTCMonth(d.getUTCMonth() - m, 1)
      const spending = [...syntheticSpend(r.user.id, m % 6).values()].reduce(
        (s, v) => s.plus(v),
        new Decimal(0),
      )
      const income = new Decimal(m > 8 ? 74000 : 82000).plus(Math.round(rnd() * 6000))
      const invested = new Decimal(Math.round(4000 + rnd() * 9000))
      points.push({
        month: d.toISOString().slice(0, 7),
        income: money(income),
        spending: money(spending),
        net: money(income.minus(spending)),
        invested: money(invested),
        borrowed: m === 9 ? '250000.0000' : '0.0000',
      })
    }
    return json({ points })
  }),
  http.get(`${BASE}/analytics/counterparties`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const limit = Math.min(10, Number(new URL(request.url).searchParams.get('limit') ?? 5))
    return json(
      [
        {
          label: 'Meera Iyer',
          accountNumberMasked: '••••2290',
          sent: '24000.0000',
          received: '0.0000',
          count: 2,
        },
        {
          label: 'Priya Nair',
          accountNumberMasked: '••••5518',
          sent: '1850.0000',
          received: '29200.0000',
          count: 4,
        },
        {
          label: 'BESCOM',
          accountNumberMasked: '••••4471',
          sent: '2184.0000',
          received: '0.0000',
          count: 1,
        },
        {
          label: 'Kavya Reddy',
          accountNumberMasked: '••••4402',
          sent: '3000.0000',
          received: '650.0000',
          count: 3,
        },
        {
          label: 'Airtel Postpaid',
          accountNumberMasked: '••••3210',
          sent: '799.0000',
          received: '0.0000',
          count: 1,
        },
      ].slice(0, limit),
    )
  }),
  http.get(`${BASE}/analytics/balance-history`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const accountId = url.searchParams.get('accountId')
    const accts = ownAccounts(r.user.id).filter((a) =>
      accountId ? a.id === accountId : a.type === 'SAVINGS' || a.type === 'CHECKING',
    )
    if (accountId && !accts.length) return notFound(request, 'Account')
    const days =
      { '1M': 30, '3M': 90, '6M': 180, '1Y': 365 }[url.searchParams.get('range') ?? '3M'] ?? 90
    let bal = accts.reduce((s, a) => s.plus(a.balance), new Decimal(0))
    const rnd = prng((accountId ?? 'all').charCodeAt(0) + days)
    const points = []
    const today = todayIst()
    for (let i = 0; i < days; i++) {
      const t = addDays(today, -i)
      points.push({ t, balance: money(bal.lt(0) ? new Decimal(0) : bal) })
      const dom = Number(t.slice(8))
      // Walk backwards: undo salary on the 1st, add back daily spend.
      if (dom === 1) bal = bal.minus(accountId ? 0 : 82000)
      bal = bal.plus(new Decimal(Math.round((rnd() - 0.35) * 2600)))
    }
    return json({ accountId: accountId ?? null, points: points.reverse() })
  }),
  http.get(`${BASE}/analytics/portfolio`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const range = new URL(request.url).searchParams.get('range') ?? '3M'
    const days = { '1M': 30, '3M': 90, '6M': 180, '1Y': 365, ALL: 420 }[range] ?? 90
    const holdings = db.holdings.filter((h) => h.userId === r.user.id && h.quantity > 0)
    const value = holdings.reduce(
      (s, h) => s.plus(new Decimal(db.instruments.get(h.symbol)?.price ?? '0').times(h.quantity)),
      new Decimal(0),
    )
    const invested = holdings.reduce(
      (s, h) => s.plus(new Decimal(h.avgCost).times(h.quantity)),
      new Decimal(0),
    )
    const rnd = prng(days)
    const points = []
    let mv = value
    let inv = invested
    for (let i = 0; i < days; i += days > 120 ? 3 : 1) {
      points.push({ t: addDays(todayIst(), -i), marketValue: money(mv), invested: money(inv) })
      mv = mv.times(1 - (rnd() - 0.47) * 0.018).toDecimalPlaces(2)
      if (i % 30 === 29) {
        inv = inv.minus(8000)
        mv = mv.minus(8000)
      }
      if (inv.lt(0)) inv = new Decimal(0)
    }
    const bySector = new Map<string, Decimal>()
    for (const h of holdings) {
      const i = db.instruments.get(h.symbol)!
      bySector.set(
        i.sector,
        (bySector.get(i.sector) ?? new Decimal(0)).plus(new Decimal(i.price).times(h.quantity)),
      )
    }
    const rows = holdings.map((h) => {
      const i = db.instruments.get(h.symbol)!
      const cost = new Decimal(h.avgCost).times(h.quantity)
      const pnl = new Decimal(i.price).times(h.quantity).minus(cost)
      return {
        symbol: h.symbol,
        name: i.name,
        unrealizedPnl: money(pnl),
        unrealizedPnlPct: pct(pnl.div(cost).times(100)),
        realizedPnl: money(h.realizedPnl),
      }
    })
    const sorted = [...rows].sort((a, b) => new Decimal(b.unrealizedPnlPct).cmp(a.unrealizedPnlPct))
    return json({
      points: points.reverse(),
      twrPct: invested.isZero() ? '0.00' : pct(value.minus(invested).div(invested).times(100)),
      allocation: [...bySector.entries()].map(([sector, v]) => ({
        sector,
        value: money(v),
        pct: value.isZero() ? '0.00' : pct(v.div(value).times(100)),
      })),
      holdings: rows,
      best: sorted[0] ? { symbol: sorted[0].symbol, pnlPct: sorted[0].unrealizedPnlPct } : null,
      worst: sorted.at(-1)
        ? { symbol: sorted.at(-1)!.symbol, pnlPct: sorted.at(-1)!.unrealizedPnlPct }
        : null,
    })
  }),
  http.get(`${BASE}/analytics/loans`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const loans = db.loans.filter(
      (l) => l.userId === r.user.id && (l.status === 'ACTIVE' || l.status === 'CLOSED'),
    )
    let total = new Decimal(0)
    const out = loans.map((l) => {
      const paid = l.schedule.filter((s) => s.status === 'PAID')
      const principalPaid = paid.reduce((s, x) => s.plus(x.principal), new Decimal(0))
      const interestPaid = paid.reduce((s, x) => s.plus(x.interest), new Decimal(0))
      total = total.plus(interestPaid)
      return {
        loanId: l.id,
        principalPaid: money(principalPaid),
        interestPaid: money(interestPaid),
        remainingPrincipal: money(l.outstandingPrincipal),
        projectedPayoffDate: l.schedule.at(-1)?.dueDate ?? null,
      }
    })
    return json({ totalInterestPaid: money(total), loans: out })
  }),
  http.post(`${BASE}/analytics/loans/:id/prepayment`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const loan = db.loans.find((l) => l.id === params.id && l.userId === r.user.id)
    if (!loan) return notFound(request, 'Loan')
    const b = (await request.json()) as { amount?: unknown }
    if (
      typeof b.amount !== 'string' ||
      !/^\d{1,9}(\.\d{1,2})?$/.test(b.amount) ||
      new Decimal(b.amount).lt(1)
    )
      return invalid(request, 'amount', 'must be at least 1.00')
    const outstanding = new Decimal(loan.outstandingPrincipal)
    const pre = new Decimal(b.amount)
    const remainingMonths = loan.schedule.filter((s) => s.status !== 'PAID').length
    if (pre.gte(outstanding)) {
      const interestLeft = loan.schedule
        .filter((s) => s.status !== 'PAID')
        .reduce((s, x) => s.plus(x.interest), new Decimal(0))
      return json({
        interestSaved: money(interestLeft),
        monthsSaved: remainingMonths,
        newPayoffDate: todayIst(),
      })
    }
    // Same EMI, smaller principal → fewer months. r = monthly rate.
    const rate = new Decimal(loan.annualRate).div(1200)
    const emi = new Decimal(loan.emi)
    let p = outstanding.minus(pre)
    let months = 0
    let interest = new Decimal(0)
    while (p.gt(0) && months < 600) {
      const i = p.times(rate).toDecimalPlaces(2)
      interest = interest.plus(i)
      p = p.plus(i).minus(emi)
      months++
    }
    const before = loan.schedule
      .filter((s) => s.status !== 'PAID')
      .reduce((s, x) => s.plus(x.interest), new Decimal(0))
    return json({
      interestSaved: money(before.minus(interest).lt(0) ? new Decimal(0) : before.minus(interest)),
      monthsSaved: Math.max(0, remainingMonths - months),
      newPayoffDate: addDays(todayIst(), months * 30),
    })
  }),
  http.get(`${BASE}/analytics/goals`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(
      ownAccounts(r.user.id)
        .filter((a) => a.type === 'GOAL')
        .map((a) => {
          const g = db.v2.goals.get(a.id)!
          const saved = new Decimal(a.balance)
          const target = new Decimal(g.target)
          const pace = new Decimal(a.id.endsWith('3333') ? 9600 : 12500)
          const left = target.minus(saved)
          const monthsLeft = left.lte(0) ? 0 : Math.ceil(left.div(pace).toNumber())
          const projected = addDays(todayIst(), monthsLeft * 30)
          return {
            accountId: a.id,
            name: g.name,
            target: money(target),
            saved: money(saved),
            pct: pct(saved.div(target).times(100)),
            monthlyPace: money(pace),
            projectedDate: projected,
            onTrack: !g.targetDate || projected <= g.targetDate,
            last4: last4(a.accountNumber),
          }
        })
        .map(({ last4: _l, ...x }) => x),
    )
  }),
]
