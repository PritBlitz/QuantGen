/**
 * In-memory state for the v2 mocks (PRD §12). Lives on `db.v2` so `resetMockDb()` clears it.
 * Seed data is deterministic (seeded PRNG) so tests and screenshots are stable.
 * Dev/test only: never bundled into production builds.
 */
import type {
  AdminFeatureFlagDto,
  BacktestDto,
  BillerDto,
  BillPaymentDto,
  CardDto,
  Category,
  ConversationDetailDto,
  FeatureFlag,
  FixedDepositDto,
  FraudCaseDetailDto,
  GoalInfo,
  GroupDto,
  HandleDto,
  IntentDto,
  KycDto,
  LoanDocumentDto,
  PasskeyDto,
  PaymentRequestDto,
  PersonalTokenDto,
  PriceAlertDto,
  RoundUpRequest,
  RuleDto,
  RuleRunDto,
  ScheduledTransferDto,
  SessionDto,
  TopupDto,
  TraceDto,
  WatchlistDto,
} from '@/api/types'

export const PEOPLE = {
  demo: { id: '7c9e6679-7425-40de-944b-e07fc1f90ae7', handle: 'aarav@qg', name: 'Aarav Sharma' },
  priya: { id: '11110000-0000-4000-8000-000000000001', handle: 'priya@qg', name: 'Priya Nair' },
  meera: { id: '11110000-0000-4000-8000-000000000002', handle: 'meera@qg', name: 'Meera Iyer' },
  rohan: { id: '11110000-0000-4000-8000-000000000003', handle: 'rohan@qg', name: 'Rohan Mehta' },
  kavya: { id: '11110000-0000-4000-8000-000000000004', handle: 'kavya@qg', name: 'Kavya Reddy' },
} as const

export const DEMO_SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'
export const DEMO_CHECKING = 'a1f3c2d4-0002-4b5e-9c7d-222222222222'
export const DEMO_GOAL_GOA = 'a1f3c2d4-0003-4b5e-9c7d-333333333333'
export const DEMO_GOAL_EMERGENCY = 'a1f3c2d4-0004-4b5e-9c7d-444444444444'
export const DEMO_FD_ACCOUNT = 'a1f3c2d4-0005-4b5e-9c7d-555555555555'

type Owned<T> = T & { userId: string }

export interface V2State {
  flags: Record<FeatureFlag, AdminFeatureFlagDto>
  passkeys: Array<Owned<PasskeyDto> & { credentialId: string }>
  pins: Map<string, { pin: string; fails: number; lockedUntil: number }>
  stepUpTokens: Map<string, { userId: string; action: string; exp: number }>
  /** Last issued challenge per user (mock passkey verification just checks it was issued). */
  stepUpChallenges: Map<string, string>
  sessions: Array<Owned<SessionDto>>
  limits: Map<string, { perTransfer: string; daily: string }>
  kyc: Map<string, KycDto>
  handles: Map<string, HandleDto & { userId: string }>
  holds: Array<{ id: string; accountId: string; amount: string; reason: string; status: 'ACTIVE' | 'RELEASED' | 'CAPTURED' }>
  requests: Array<PaymentRequestDto>
  blocked: Array<{ userId: string; blockedHandle: string }>
  scheduled: Array<Owned<ScheduledTransferDto> & { toAccountNumber: string }>
  billers: BillerDto[]
  bills: Map<string, { userId: string; billerId: string; consumerNumber: string; amount: string; dueDate: string }>
  billPayments: Array<Owned<BillPaymentDto>>
  groups: Array<GroupDto & { memberIds: string[] }>
  topups: Array<Owned<TopupDto>>
  goals: Map<string, GoalInfo>
  roundUps: Map<string, RoundUpRequest>
  fds: Array<Owned<FixedDepositDto> & { fromAccountId: string }>
  budgets: Map<string, Array<{ category: Category; monthlyLimit: string }>>
  categoryOverrides: Map<string, Category>
  conversations: Array<Owned<ConversationDetailDto>>
  intents: Array<Owned<IntentDto> & { payload: Record<string, unknown> }>
  traces: Map<string, TraceDto>
  rules: Array<Owned<RuleDto>>
  ruleRuns: Map<string, RuleRunDto[]>
  fraudCases: FraudCaseDetailDto[]
  loanDocs: Map<string, LoanDocumentDto[]>
  watchlists: Array<Owned<WatchlistDto>>
  alerts: Array<Owned<PriceAlertDto>>
  backtests: Array<Owned<BacktestDto>>
  cards: Array<Owned<CardDto> & { pan: string }>
  cardAuths: Map<string, { cardId: string; amount: string; holdId: string; merchant: string; status: 'AUTHORIZED' | 'CAPTURED' | 'REFUNDED' }>
  tokens: Array<Owned<PersonalTokenDto> & { tokenHash: string }>
  aiCalls: number
  /** Mock fraud rule: transfers to others at or above this amount go to review. null = off (tests). */
  riskReviewAbove: string | null
}

/** Deterministic PRNG (mulberry32): same numbers on every run. */
export function prng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const iso = (daysAgo: number, hour = 10) => {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() - daysAgo)
  d.setUTCHours(hour, 0, 0, 0)
  return d.toISOString()
}
const isoDate = (daysFromNow: number) => {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() + daysFromNow)
  return d.toISOString().slice(0, 10)
}

export function createV2State(): V2State {
  const flagNames: FeatureFlag[] = ['ai', 'autopilot', 'razorpay', 'cards', 'mcp']
  const flags = Object.fromEntries(
    flagNames.map((name) => [name, { name, enabled: true, updatedAt: iso(3), updatedBy: 'admin@quantgen.dev' }]),
  ) as Record<FeatureFlag, AdminFeatureFlagDto>

  const s: V2State = {
    flags,
    passkeys: [
      {
        id: 'b0000000-0000-4000-8000-00000000pk01'.replace('pk', 'aa'),
        userId: PEOPLE.demo.id,
        name: 'MacBook Touch ID',
        createdAt: iso(40),
        lastUsedAt: iso(1),
        credentialId: 'mock-cred-1',
      },
    ],
    pins: new Map([[PEOPLE.demo.id, { pin: '246810', fails: 0, lockedUntil: 0 }]]),
    stepUpTokens: new Map(),
    stepUpChallenges: new Map(),
    sessions: [
      { id: 'd0000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, device: 'Windows PC', browser: 'Chrome 141', ipCity: 'Bengaluru', current: true, createdAt: iso(2), lastUsedAt: iso(0) },
      { id: 'd0000000-0000-4000-8000-000000000002', userId: PEOPLE.demo.id, device: 'iPhone 16', browser: 'Safari', ipCity: 'Bengaluru', current: false, createdAt: iso(9), lastUsedAt: iso(1, 21) },
      { id: 'd0000000-0000-4000-8000-000000000003', userId: PEOPLE.demo.id, device: 'iPad', browser: 'Safari', ipCity: 'Mumbai', current: false, createdAt: iso(31), lastUsedAt: iso(12) },
    ],
    limits: new Map(),
    kyc: new Map([
      [PEOPLE.demo.id, { status: 'VERIFIED', panMasked: 'ABCPS••••K', dateOfBirth: '1996-03-14', address: '12 MG Road, Bengaluru 560001', verifiedAt: iso(80) }],
    ]),
    handles: new Map(
      Object.values(PEOPLE).map((p, i) => [
        p.id,
        {
          userId: p.id,
          handle: p.handle,
          accountId:
            p.id === PEOPLE.demo.id
              ? DEMO_SAVINGS
              : ['a2000000-0000-4000-8000-000000000001', 'a2000000-0000-4000-8000-000000000002', 'a2000000-0000-4000-8000-000000000004', 'a2000000-0000-4000-8000-000000000005'][i - 1]!,
          changeableAt: iso(-12),
        },
      ]),
    ),
    holds: [{ id: 'h0000000-0000-4000-8000-000000000001'.replace('h', 'e'), accountId: DEMO_CHECKING, amount: '2499.0000', reason: 'CARD_AUTH', status: 'ACTIVE' }],
    requests: [
      { id: 'f1000000-0000-4000-8000-000000000001', fromHandle: PEOPLE.meera.handle, toHandle: PEOPLE.demo.handle, amount: '1850.0000', note: 'Dinner at Toit', status: 'OPEN', transferId: null, expiresAt: iso(-5), createdAt: iso(2, 20) },
      { id: 'f1000000-0000-4000-8000-000000000002', fromHandle: PEOPLE.demo.handle, toHandle: PEOPLE.kavya.handle, amount: '650.0000', note: 'Cab share', status: 'OPEN', transferId: null, expiresAt: iso(-6), createdAt: iso(1, 18) },
      { id: 'f1000000-0000-4000-8000-000000000003', fromHandle: PEOPLE.priya.handle, toHandle: PEOPLE.demo.handle, amount: '4200.0000', note: 'Concert tickets', status: 'PAID', transferId: null, expiresAt: iso(2), createdAt: iso(9) },
    ],
    blocked: [],
    scheduled: [
      { id: 'f2000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, fromAccountId: DEMO_SAVINGS, toAccountNumber: '502193342290', toAccountNumberMasked: '••••2290', toName: 'Meera Iyer', amount: '12000.0000', note: 'Rent', frequency: 'MONTHLY', nextRunDate: isoDate(13), endDate: null, remainingCount: null, status: 'ACTIVE', lastRun: { date: isoDate(-18), outcome: 'SUCCEEDED', transferId: null } },
      { id: 'f2000000-0000-4000-8000-000000000002', userId: PEOPLE.demo.id, fromAccountId: DEMO_CHECKING, toAccountNumber: '502197784402', toAccountNumberMasked: '••••4402', toName: 'Kavya Reddy', amount: '1500.0000', note: 'Gym buddy fund', frequency: 'WEEKLY', nextRunDate: isoDate(3), endDate: isoDate(60), remainingCount: 9, status: 'PAUSED', lastRun: null },
    ],
    billers: [
      ['BESCOM', 'ELECTRICITY', 'Account ID', '^\\d{10}$'],
      ['Tata Power', 'ELECTRICITY', 'Consumer number', '^\\d{12}$'],
      ['Airtel Postpaid', 'MOBILE', 'Mobile number', '^[6-9]\\d{9}$'],
      ['Jio Prepaid', 'MOBILE', 'Mobile number', '^[6-9]\\d{9}$'],
      ['ACT Fibernet', 'BROADBAND', 'Account number', '^\\d{8,10}$'],
      ['Tata Play', 'DTH', 'Subscriber ID', '^\\d{10}$'],
      ['Indane Gas', 'GAS', 'Consumer number', '^\\d{9,17}$'],
      ['BWSSB', 'WATER', 'RR number', '^[A-Z0-9]{6,12}$'],
      ['HDFC Credit Card', 'CREDIT_CARD', 'Last 4 digits + mobile', '^\\d{4}[6-9]\\d{9}$'],
      ['LIC Premium', 'INSURANCE', 'Policy number', '^\\d{9}$'],
      ['Mahanagar Gas', 'GAS', 'BP number', '^\\d{10}$'],
      ['Hathway Broadband', 'BROADBAND', 'Customer ID', '^\\d{9}$'],
    ].map(([name, category, label, pattern], i) => ({
      id: `b1000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
      name: name!,
      category: category as BillerDto['category'],
      consumerNumberLabel: label!,
      consumerNumberPattern: pattern!,
    })),
    bills: new Map(),
    billPayments: [
      { id: 'f3000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, billerId: 'b1000000-0000-4000-8000-000000000001', billerName: 'BESCOM', category: 'ELECTRICITY', consumerNumberMasked: '••••4471', amount: '2184.0000', fromAccountId: DEMO_SAVINGS, transactionId: '0c0ffee0-0000-4000-8000-0000000000b1', createdAt: iso(21) },
      { id: 'f3000000-0000-4000-8000-000000000002', userId: PEOPLE.demo.id, billerId: 'b1000000-0000-4000-8000-000000000003', billerName: 'Airtel Postpaid', category: 'MOBILE', consumerNumberMasked: '••••3210', amount: '799.0000', fromAccountId: DEMO_CHECKING, transactionId: '0c0ffee0-0000-4000-8000-0000000000b2', createdAt: iso(6) },
    ],
    groups: [
      {
        id: 'f4000000-0000-4000-8000-000000000001',
        name: 'Goa trip ’26',
        memberIds: [PEOPLE.demo.id, PEOPLE.priya.id, PEOPLE.meera.id, PEOPLE.kavya.id],
        members: [],
        expenses: [
          { id: 'f4100000-0000-4000-8000-000000000001', paidByHandle: PEOPLE.demo.handle, amount: '18400.0000', description: 'Villa (2 nights)', splitType: 'EQUAL', shares: [], createdAt: iso(12) },
          { id: 'f4100000-0000-4000-8000-000000000002', paidByHandle: PEOPLE.priya.handle, amount: '6240.0000', description: 'Scooters + fuel', splitType: 'EQUAL', shares: [], createdAt: iso(11) },
          { id: 'f4100000-0000-4000-8000-000000000003', paidByHandle: PEOPLE.meera.handle, amount: '4380.0000', description: 'Seafood at Fisherman’s Wharf', splitType: 'EQUAL', shares: [], createdAt: iso(11, 21) },
        ],
        createdAt: iso(14),
      },
      {
        id: 'f4000000-0000-4000-8000-000000000002',
        name: 'Flat 4B',
        memberIds: [PEOPLE.demo.id, PEOPLE.rohan.id],
        members: [],
        expenses: [
          { id: 'f4100000-0000-4000-8000-000000000004', paidByHandle: PEOPLE.rohan.handle, amount: '3600.0000', description: 'Wi-Fi + maid (Oct)', splitType: 'EQUAL', shares: [], createdAt: iso(4) },
        ],
        createdAt: iso(60),
      },
    ],
    topups: [],
    goals: new Map([
      [DEMO_GOAL_GOA, { name: 'Goa in December', target: '60000.0000', targetDate: isoDate(55), emoji: '🏖️' }],
      [DEMO_GOAL_EMERGENCY, { name: 'Emergency fund', target: '300000.0000', targetDate: null, emoji: '🛟' }],
    ]),
    roundUps: new Map([[DEMO_CHECKING, { enabled: true, roundTo: 10, goalAccountId: DEMO_GOAL_GOA }]]),
    fds: [
      { id: 'f5000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, fromAccountId: DEMO_SAVINGS, accountNumberMasked: '••••7712', principal: '100000.0000', annualRate: '7.10', tenureDays: 365, startDate: isoDate(-120), maturityDate: isoDate(245), accruedInterest: '2334.2500', maturityAmount: '107100.0000', onMaturity: 'PAYOUT', status: 'ACTIVE' },
    ],
    budgets: new Map([
      [
        PEOPLE.demo.id,
        [
          { category: 'FOOD', monthlyLimit: '8000.0000' },
          { category: 'SHOPPING', monthlyLimit: '6000.0000' },
          { category: 'TRANSPORT', monthlyLimit: '3000.0000' },
          { category: 'ENTERTAINMENT', monthlyLimit: '2500.0000' },
          { category: 'BILLS', monthlyLimit: '5000.0000' },
        ],
      ],
    ]),
    categoryOverrides: new Map(),
    conversations: [],
    intents: [],
    traces: new Map(),
    rules: [],
    ruleRuns: new Map(),
    fraudCases: [],
    loanDocs: new Map(),
    watchlists: [
      { id: 'f6000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, name: 'Banks', symbols: ['HDFC', 'ICIC', 'SBIN', 'QGEN'], createdAt: iso(30) },
      { id: 'f6000000-0000-4000-8000-000000000002', userId: PEOPLE.demo.id, name: 'Tech', symbols: ['INFY', 'TCS', 'WIPR'], createdAt: iso(20) },
    ],
    alerts: [
      { id: 'f7000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, symbol: 'QGEN', direction: 'ABOVE', price: '1350.0000', status: 'ACTIVE', triggeredAt: null, createdAt: iso(5) },
      { id: 'f7000000-0000-4000-8000-000000000002', userId: PEOPLE.demo.id, symbol: 'RELI', direction: 'BELOW', price: '2900.0000', status: 'TRIGGERED', triggeredAt: iso(2), createdAt: iso(10) },
    ],
    backtests: [],
    cards: [
      { id: 'f8000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, accountId: DEMO_CHECKING, last4: '4417', expiry: '09/30', status: 'ACTIVE', pan: '9100 4521 7783 4417', controls: { frozen: false, online: true, perTxnLimit: '25000.0000', blockedCategories: ['GAMBLING'] } },
    ],
    cardAuths: new Map(),
    tokens: [
      { id: 'f9000000-0000-4000-8000-000000000001', userId: PEOPLE.demo.id, name: 'Claude Desktop', scope: 'read+propose', lastUsedAt: iso(1), expiresAt: iso(-60), tokenHash: 'x' },
    ],
    aiCalls: 0,
    riskReviewAbove: '150000',
  }

  seedRules(s)
  seedFraud(s)
  return s
}

function seedRules(s: V2State) {
  const salary: RuleDto & { userId: string } = {
    id: 'fa000000-0000-4000-8000-000000000001',
    userId: PEOPLE.demo.id,
    name: 'Salary splitter',
    version: 2,
    status: 'ACTIVE',
    rule: {
      dslVersion: 1,
      trigger: { type: 'MONEY_RECEIVED', accountId: DEMO_SAVINGS, minAmount: '40000.00', counterpartyContains: 'ACME' },
      conditions: [],
      actions: [
        { type: 'TRANSFER', fromAccountId: DEMO_SAVINGS, toAccountId: DEMO_GOAL_GOA, amount: { mode: 'PERCENT_OF_TRIGGER', value: '20' } },
        { type: 'BUY', accountId: DEMO_SAVINGS, symbol: 'QGEN', amount: { mode: 'FIXED', value: '5000.00' } },
      ],
      caps: { perRun: '30000.00', perMonth: '60000.00' },
    },
    rendering:
      'When at least ₹40,000.00 from “ACME” lands in Savings ••••4821: move 20% of it to Goa in December, then buy ₹5,000.00 of QGEN. At most ₹30,000.00 per run and ₹60,000.00 a month.',
    pausedReason: null,
    lastRunAt: iso(17),
    runCount: 3,
    movedThisMonth: '0.0000',
    createdAt: iso(70),
    updatedAt: iso(40),
  }
  const sweep: RuleDto & { userId: string } = {
    id: 'fa000000-0000-4000-8000-000000000002',
    userId: PEOPLE.demo.id,
    name: 'Keep checking lean',
    version: 1,
    status: 'ACTIVE',
    rule: {
      dslVersion: 1,
      trigger: { type: 'BALANCE_ABOVE', accountId: DEMO_CHECKING, threshold: '50000.00' },
      conditions: [],
      actions: [
        { type: 'TRANSFER', fromAccountId: DEMO_CHECKING, toAccountId: DEMO_GOAL_EMERGENCY, amount: { mode: 'EXCESS_ABOVE', value: '50000.00' } },
      ],
      caps: { perRun: '50000.00', perMonth: '100000.00' },
    },
    rendering:
      'Every day, if Checking ••••9067 holds more than ₹50,000.00: move everything above ₹50,000.00 to Emergency fund. At most ₹50,000.00 per run and ₹1,00,000.00 a month.',
    pausedReason: null,
    lastRunAt: iso(6),
    runCount: 5,
    movedThisMonth: '8240.0000',
    createdAt: iso(55),
    updatedAt: iso(55),
  }
  const sip: RuleDto & { userId: string } = {
    id: 'fa000000-0000-4000-8000-000000000003',
    userId: PEOPLE.demo.id,
    name: 'Monthly SIP · TCS',
    version: 1,
    status: 'PAUSED',
    rule: {
      dslVersion: 1,
      trigger: { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: 5 },
      conditions: [{ type: 'BALANCE_AT_LEAST', accountId: DEMO_SAVINGS, amount: '20000.00' }],
      actions: [{ type: 'BUY', accountId: DEMO_SAVINGS, symbol: 'TCS', amount: { mode: 'FIXED', value: '8000.00' } }],
      caps: { perRun: '8000.00', perMonth: '8000.00' },
    },
    rendering:
      'On the 5th of every month at 08:00 IST, if Savings ••••4821 holds at least ₹20,000.00: buy ₹8,000.00 of TCS (whole shares). At most ₹8,000.00 per run and ₹8,000.00 a month.',
    pausedReason: 'Paused after 3 failed runs in a row (insufficient funds).',
    lastRunAt: iso(13),
    runCount: 4,
    movedThisMonth: '0.0000',
    createdAt: iso(120),
    updatedAt: iso(13),
  }
  s.rules.push(salary, sweep, sip)
  s.ruleRuns.set(salary.id, [
    { id: 'fb000000-0000-4000-8000-000000000001', ruleVersion: 2, triggerEventId: 'evt-1001', triggerSummary: '₹82,000.00 from ACME Payroll into Savings ••••4821', startedAt: iso(17, 4), outcome: 'SUCCEEDED', actions: [{ type: 'TRANSFER', amount: '16400.0000' }, { type: 'BUY', amount: '3853.5000', orderId: undefined }] },
    { id: 'fb000000-0000-4000-8000-000000000002', ruleVersion: 1, triggerEventId: 'evt-0874', triggerSummary: '₹82,000.00 from ACME Payroll into Savings ••••4821', startedAt: iso(48, 4), outcome: 'SUCCEEDED', actions: [{ type: 'TRANSFER', amount: '16400.0000' }] },
    { id: 'fb000000-0000-4000-8000-000000000003', ruleVersion: 1, triggerEventId: 'evt-0702', triggerSummary: '₹78,500.00 from ACME Payroll into Savings ••••4821', startedAt: iso(78, 4), outcome: 'PARTIAL', actions: [{ type: 'TRANSFER', amount: '15700.0000' }, { type: 'BUY', amount: null, error: 'INSUFFICIENT_FUNDS' }] },
  ])
  s.ruleRuns.set(sweep.id, [
    { id: 'fb000000-0000-4000-8000-000000000004', ruleVersion: 1, triggerEventId: 'daily-check', triggerSummary: 'Daily check: Checking ••••9067 held ₹58,240.00', startedAt: iso(6, 2), outcome: 'SUCCEEDED', actions: [{ type: 'TRANSFER', amount: '8240.0000' }] },
  ])
  s.ruleRuns.set(sip.id, [
    { id: 'fb000000-0000-4000-8000-000000000005', ruleVersion: 1, triggerEventId: 'schedule', triggerSummary: 'Scheduled: 5th of the month', startedAt: iso(13, 2), outcome: 'FAILED', actions: [{ type: 'BUY', amount: null, error: 'Condition not met: Savings below ₹20,000.00' }] },
  ])
}

function seedFraud(s: V2State) {
  const mk = (
    n: number,
    status: FraudCaseDetailDto['status'],
    amount: string,
    who: { id: string; name: string },
    toName: string,
    score: number,
    topSignal: string,
    daysAgo: number,
  ): FraudCaseDetailDto => ({
    id: `fc000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
    status,
    score,
    transferId: `fd000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
    amount,
    fromAccountNumberMasked: '••••' + String(5518 + n * 7).slice(-4),
    toAccountNumberMasked: '••••' + String(9031 + n * 13).slice(-4),
    toName,
    customer: { userId: who.id, fullName: who.name },
    topSignal,
    createdAt: iso(daysAgo, 23),
    decidedAt: status === 'OPEN' ? null : iso(daysAgo - 1),
    decisionReason: status === 'OPEN' ? null : status === 'RELEASED' ? 'Customer confirmed by phone.' : 'Customer did not recognise the payee.',
    memo:
      status === 'OPEN' || n === 3
        ? {
            summary:
              n === 1
                ? `${who.name} sent ${'₹1,45,000.00'} to a payee added 40 minutes earlier, at 01:12 IST. Their usual transfers average ₹6,800 and happen between 09:00 and 21:00. The payee account was opened 3 days ago and has received money from 4 unrelated customers this week. This matches a common "urgent help" scam pattern.`
                : `${who.name} made 6 round-amount transfers of ₹20,000 within 18 minutes to 3 new payees, right after a password change from a new device. Their baseline is ~4 transfers a month.`,
            suggestedAction: n === 1 ? 'CONTACT_CUSTOMER' : 'REJECT',
            confidence: n === 1 ? 'HIGH' : 'MEDIUM',
            generatedAt: iso(daysAgo, 23),
            runId: `fe000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
          }
        : null,
    signals: [
      { code: 'NEW_PAYEE_LARGE', label: 'Large amount to a payee added < 24 h ago', weight: 35 },
      { code: 'ODD_HOUR', label: 'Between 00:00 and 05:00 IST', weight: 20 },
      { code: 'VELOCITY', label: '6 transfers in 18 minutes', weight: 25 },
      { code: 'DEVICE_CHANGE', label: 'First transfer after a password change on a new device', weight: 15 },
    ].slice(0, n === 1 ? 2 : 4),
    customerBaseline: { avgTransfer: '6800.0000', transfers30d: 4, usualHoursIst: '09:00–21:00', accountAgeDays: 92 + n },
  })
  s.fraudCases.push(
    mk(1, 'OPEN', '145000.0000', PEOPLE.priya, 'R. K. Traders', 86, 'Large amount to a payee added < 24 h ago', 0),
    mk(2, 'OPEN', '120000.0000', PEOPLE.kavya, 'Multiple payees', 74, '6 transfers in 18 minutes', 1),
    mk(3, 'RELEASED', '62000.0000', PEOPLE.meera, 'Sunrise Interiors', 61, 'Large amount to a payee added < 24 h ago', 6),
    mk(4, 'REJECTED', '98000.0000', PEOPLE.rohan, 'Crypto OTC Desk', 91, 'First transfer after a password change on a new device', 9),
  )
}
