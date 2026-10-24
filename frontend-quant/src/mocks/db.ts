/**
 * In-memory mock database for MSW. Shapes are PRD §7 DTOs exactly. Money math uses Decimal,
 * same as the app. Dev/test only: never bundled into production builds.
 */
import type {
  AccountDto,
  AuditLogDto,
  BeneficiaryDto,
  InstrumentDto,
  LoanDetailDto,
  NotificationDto,
  OrderDto,
  TransactionDto,
  TransferDto,
  UserDto,
} from '@/api/types'
import { emi, schedule } from '@/lib/emi'
import { Decimal } from '@/lib/money'
import { createV2State, type V2State } from './v2/state'

export interface MockUser {
  user: UserDto
  password: string
}

export interface MockHolding {
  userId: string
  symbol: string
  quantity: number
  avgCost: string
  realizedPnl: string
}

export type MockLoan = LoanDetailDto & {
  userId: string
  monthlyIncome: string
  disburseAccountId: string
}

export interface MockDb {
  users: Map<string, MockUser>
  accounts: Map<string, AccountDto & { userId: string }>
  transactions: TransactionDto[]
  transfers: Array<TransferDto & { userId: string }>
  beneficiaries: Array<BeneficiaryDto & { userId: string }>
  instruments: Map<string, InstrumentDto & { open: string }>
  holdings: MockHolding[]
  orders: Array<OrderDto & { userId: string }>
  loans: MockLoan[]
  idempotency: Map<string, { hash: string; status: number; body: unknown }>
  failedLogins: Map<string, number[]>
  notifications: Array<NotificationDto & { userId: string }>
  auditLogs: AuditLogDto[]
  /** v2 mock state (PRD §12). */
  v2: V2State
}

export const DEMO_USER_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7'
export const ADMIN_USER_ID = '0b1c2d3e-4f50-4a61-8b72-93a4b5c6d7e8'

const iso = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString()

const INSTRUMENTS: Array<[string, string, string, string, string]> = [
  ['QGEN', 'QuantGen Holdings', 'Financials', '1284.50', '1262.10'],
  ['INFY', 'Infosys', 'IT Services', '1876.25', '1890.00'],
  ['TCS', 'Tata Consultancy', 'IT Services', '4125.80', '4098.35'],
  ['RELI', 'Reliance Industries', 'Energy', '2944.10', '2961.75'],
  ['HDFC', 'HDFC Bank', 'Financials', '1712.40', '1698.90'],
  ['ICIC', 'ICICI Bank', 'Financials', '1265.75', '1270.20'],
  ['WIPR', 'Wipro', 'IT Services', '548.30', '541.05'],
  ['SBIN', 'State Bank of India', 'Financials', '842.15', '836.60'],
  ['BHAR', 'Bharti Airtel', 'Telecom', '1689.00', '1702.45'],
  ['ITC', 'ITC', 'Consumer', '486.90', '482.25'],
  ['LT', 'Larsen & Toubro', 'Industrials', '3712.60', '3688.10'],
  ['MARU', 'Maruti Suzuki', 'Auto', '12480.00', '12555.40'],
  ['SUNP', 'Sun Pharma', 'Healthcare', '1798.35', '1781.90'],
  ['TITN', 'Titan Company', 'Consumer', '3410.20', '3436.85'],
  ['ASPT', 'Asian Paints', 'Materials', '2875.45', '2869.00'],
]

function pct(price: string, open: string): string {
  return new Decimal(price).minus(open).div(open).times(100).toDecimalPlaces(2).toFixed(2)
}

export function createDb(): MockDb {
  const now = new Date().toISOString()
  const db: MockDb = {
    users: new Map(),
    accounts: new Map(),
    transactions: [],
    transfers: [],
    beneficiaries: [],
    instruments: new Map(),
    holdings: [],
    orders: [],
    loans: [],
    idempotency: new Map(),
    failedLogins: new Map(),
    notifications: [],
    auditLogs: [],
    v2: createV2State(),
  }

  db.users.set('demo@quantgen.dev', {
    password: 'Demo@1234',
    user: {
      id: DEMO_USER_ID,
      fullName: 'Aarav Sharma',
      email: 'demo@quantgen.dev',
      phone: '+919876543210',
      role: 'CUSTOMER',
      createdAt: '2026-07-02T09:12:00Z',
    },
  })
  db.users.set('admin@quantgen.dev', {
    password: 'Admin@1234',
    user: {
      id: ADMIN_USER_ID,
      fullName: 'Operations Admin',
      email: 'admin@quantgen.dev',
      phone: '+919800000001',
      role: 'ADMIN',
      createdAt: '2026-06-01T08:00:00Z',
    },
  })

  const savings = {
    id: 'a1f3c2d4-0001-4b5e-9c7d-111111111111',
    userId: DEMO_USER_ID,
    accountNumber: '502184734821',
    type: 'SAVINGS' as const,
    status: 'ACTIVE' as const,
    balance: '184250.7500',
    currency: 'INR' as const,
    createdAt: '2026-07-02T09:12:00Z',
  }
  const checking = {
    id: 'a1f3c2d4-0002-4b5e-9c7d-222222222222',
    userId: DEMO_USER_ID,
    accountNumber: '502184739067',
    type: 'CHECKING' as const,
    status: 'ACTIVE' as const,
    balance: '42310.2000',
    currency: 'INR' as const,
    createdAt: '2026-08-14T11:40:00Z',
  }
  db.accounts.set(savings.id, savings)
  db.accounts.set(checking.id, checking)
  // v2: two goals and one fixed deposit (PRD F-SAV-1/3). Not counted toward the 3-account cap.
  for (const [id, number, type, balance, created] of [
    [
      'a1f3c2d4-0003-4b5e-9c7d-333333333333',
      '502184731180',
      'GOAL',
      '38400.0000',
      '2026-08-20T10:00:00Z',
    ],
    [
      'a1f3c2d4-0004-4b5e-9c7d-444444444444',
      '502184735562',
      'GOAL',
      '126500.0000',
      '2026-07-10T10:00:00Z',
    ],
    [
      'a1f3c2d4-0005-4b5e-9c7d-555555555555',
      '502184737712',
      'FIXED_DEPOSIT',
      '100000.0000',
      '2026-06-20T10:00:00Z',
    ],
  ] as const) {
    db.accounts.set(id, {
      id,
      userId: DEMO_USER_ID,
      accountNumber: number,
      type,
      status: 'ACTIVE',
      balance,
      currency: 'INR',
      createdAt: created,
    })
  }

  // Recent activity, newest first. balanceAfter walks back from the current balance.
  const seed: Array<
    [
      string,
      TransactionDto['direction'],
      string,
      TransactionDto['type'],
      string,
      string | null,
      number,
    ]
  > = [
    [
      savings.id,
      'CREDIT',
      '25000.0000',
      'TRANSFER',
      'Transfer from Priya Nair',
      'From Priya Nair ••••5518',
      14,
    ],
    [checking.id, 'DEBIT', '6425.0000', 'TRADE', 'Bought 5 × QGEN', null, 95],
    [savings.id, 'DEBIT', '9414.6900', 'LOAN_REPAYMENT', 'EMI 7 of 24', null, 60 * 26],
    [
      savings.id,
      'DEBIT',
      '12000.0000',
      'TRANSFER',
      'Rent — October',
      'To Meera Iyer ••••2290',
      60 * 50,
    ],
    [
      checking.id,
      'CREDIT',
      '20000.0000',
      'TRANSFER',
      'Own account transfer',
      'Aarav Sharma',
      60 * 72,
    ],
    [
      savings.id,
      'DEBIT',
      '20000.0000',
      'TRANSFER',
      'Own account transfer',
      'Aarav Sharma',
      60 * 72,
    ],
    [savings.id, 'CREDIT', '100000.0000', 'DEPOSIT', 'Demo deposit', null, 60 * 120],
  ]
  const running = new Map<string, Decimal>([
    [savings.id, new Decimal(savings.balance)],
    [checking.id, new Decimal(checking.balance)],
  ])
  seed.forEach(([accountId, direction, amount, type, description, counterparty, minsAgo], i) => {
    const bal = running.get(accountId) ?? new Decimal(0)
    db.transactions.push({
      id: `0c0ffee0-0000-4000-8000-${String(i).padStart(12, '0')}`,
      journalEntryId: `0e0e0e0e-0000-4000-8000-${String(i).padStart(12, '0')}`,
      accountId,
      direction,
      amount,
      balanceAfter: bal.toFixed(4),
      type,
      description,
      counterparty,
      createdAt: iso(minsAgo),
    })
    running.set(accountId, direction === 'CREDIT' ? bal.minus(amount) : bal.plus(amount))
  })

  for (const [symbol, name, sector, price, open] of INSTRUMENTS) {
    db.instruments.set(symbol, {
      symbol,
      name,
      sector,
      price,
      open,
      changePct24h: pct(price, open),
      updatedAt: now,
    })
  }

  db.holdings.push(
    { userId: DEMO_USER_ID, symbol: 'QGEN', quantity: 40, avgCost: '1180.25', realizedPnl: '0' },
    {
      userId: DEMO_USER_ID,
      symbol: 'TCS',
      quantity: 12,
      avgCost: '3890.00',
      realizedPnl: '1240.5000',
    },
    { userId: DEMO_USER_ID, symbol: 'HDFC', quantity: 30, avgCost: '1745.10', realizedPnl: '0' },
    {
      userId: DEMO_USER_ID,
      symbol: 'ITC',
      quantity: 100,
      avgCost: '451.80',
      realizedPnl: '-310.0000',
    },
  )

  db.loans.push(seededLoan())

  seedCustomers(db)
  seedNotifications(db)
  seedAuditLogs(db)
  return db
}

/** Other customers, so the admin screens have a queue and a user list to work with. */
function seedCustomers(db: MockDb) {
  const people: Array<{
    id: string
    name: string
    email: string
    phone: string
    joined: string
    accounts: Array<[string, string, AccountDto['type'], AccountDto['status'], string]>
    pendingLoan?: [string, string, number, string, string]
  }> = [
    {
      id: '11110000-0000-4000-8000-000000000001',
      name: 'Priya Nair',
      email: 'priya.nair@example.com',
      phone: '+919811100001',
      joined: '2026-07-19T06:30:00Z',
      accounts: [
        [
          'a2000000-0000-4000-8000-000000000001',
          '502190125518',
          'SAVINGS',
          'ACTIVE',
          '318420.5000',
        ],
      ],
      pendingLoan: [
        'c0000000-0000-4000-8000-000000000001',
        '300000.00',
        36,
        '110000.00',
        'Small business working capital',
      ],
    },
    {
      id: '11110000-0000-4000-8000-000000000002',
      name: 'Meera Iyer',
      email: 'meera.iyer@example.com',
      phone: '+919811100002',
      joined: '2026-08-02T12:10:00Z',
      accounts: [
        ['a2000000-0000-4000-8000-000000000002', '502193342290', 'SAVINGS', 'ACTIVE', '96210.0000'],
        [
          'a2000000-0000-4000-8000-000000000003',
          '502193347731',
          'CHECKING',
          'ACTIVE',
          '12400.0000',
        ],
      ],
    },
    {
      id: '11110000-0000-4000-8000-000000000003',
      name: 'Rohan Mehta',
      email: 'rohan.mehta@example.com',
      phone: '+919811100003',
      joined: '2026-09-11T09:45:00Z',
      accounts: [
        ['a2000000-0000-4000-8000-000000000004', '502195560614', 'SAVINGS', 'FROZEN', '2150.0000'],
      ],
    },
    {
      id: '11110000-0000-4000-8000-000000000004',
      name: 'Kavya Reddy',
      email: 'kavya.reddy@example.com',
      phone: '+919811100004',
      joined: '2026-10-09T15:20:00Z',
      accounts: [
        ['a2000000-0000-4000-8000-000000000005', '502197784402', 'SAVINGS', 'ACTIVE', '45000.0000'],
      ],
      pendingLoan: [
        'c0000000-0000-4000-8000-000000000002',
        '50000.00',
        12,
        '42000.00',
        'Laptop for freelance work',
      ],
    },
  ]
  for (const p of people) {
    db.users.set(p.email, {
      password: 'Customer@1234',
      user: {
        id: p.id,
        fullName: p.name,
        email: p.email,
        phone: p.phone,
        role: 'CUSTOMER',
        createdAt: p.joined,
      },
    })
    for (const [id, accountNumber, type, status, balance] of p.accounts) {
      db.accounts.set(id, {
        id,
        userId: p.id,
        accountNumber,
        type,
        status,
        balance,
        currency: 'INR',
        createdAt: p.joined,
      })
    }
    if (p.pendingLoan) {
      const [id, amount, tenureMonths, monthlyIncome, purpose] = p.pendingLoan
      const disburse = p.accounts[0]
      if (!disburse) throw new Error('seed customer without an account')
      db.loans.push({
        id,
        userId: p.id,
        amount,
        tenureMonths,
        annualRate: '12.00',
        emi: emi(amount, tenureMonths).toFixed(2),
        status: 'PENDING',
        outstandingPrincipal: '0.00',
        nextDueDate: null,
        createdAt: iso(60 * 5 + tenureMonths),
        purpose,
        decisionReason: null,
        schedule: [],
        monthlyIncome,
        disburseAccountId: disburse[0],
      })
    }
  }
}

function seedNotifications(db: MockDb) {
  const rows: Array<[NotificationDto['type'], string, string, number, boolean]> = [
    [
      'MONEY_RECEIVED',
      'Money received',
      '₹25,000.00 from Priya Nair into Savings ••••4821.',
      14,
      false,
    ],
    [
      'EMI_DUE',
      'EMI due soon',
      'EMI 8 of 24 (₹9,414.69) on your home renovation loan is due in 5 days.',
      60 * 3,
      false,
    ],
    [
      'EMI_PAID',
      'EMI paid',
      'EMI 7 of 24 (₹9,414.69) was paid from Savings ••••4821.',
      60 * 26,
      false,
    ],
    [
      'LOAN_APPROVED',
      'Loan approved',
      'Your ₹2,00,000.00 loan was approved and disbursed into Savings ••••4821.',
      60 * 24 * 30,
      true,
    ],
  ]
  rows.forEach(([type, title, body, minsAgo, read], i) => {
    db.notifications.push({
      id: `0a0a0a0a-0000-4000-8000-${String(i).padStart(12, '0')}`,
      userId: DEMO_USER_ID,
      type,
      title,
      body,
      read,
      createdAt: iso(minsAgo),
    })
  })
}

function seedAuditLogs(db: MockDb) {
  const rows: Array<
    [string | null, string | null, string, string | null, string | null, string | null, number]
  > = [
    [DEMO_USER_ID, 'demo@quantgen.dev', 'LOGIN_SUCCESS', 'USER', DEMO_USER_ID, null, 20],
    [null, 'unknown@example.com', 'LOGIN_FAILED', null, null, 'Invalid credentials', 45],
    [
      ADMIN_USER_ID,
      'admin@quantgen.dev',
      'ACCOUNT_FROZEN',
      'ACCOUNT',
      'a2000000-0000-4000-8000-000000000004',
      'Account ••••0614 frozen: suspected card fraud reported by customer',
      60 * 30,
    ],
    [
      DEMO_USER_ID,
      'demo@quantgen.dev',
      'LARGE_TRANSFER',
      'TRANSFER',
      null,
      '₹1,50,000.00 to ••••2290',
      60 * 50,
    ],
    [
      ADMIN_USER_ID,
      'admin@quantgen.dev',
      'LOAN_APPROVED',
      'LOAN',
      'b0a11111-2222-4333-8444-555566667777',
      'Meets income and EMI criteria',
      60 * 24 * 30,
    ],
    [
      ADMIN_USER_ID,
      'admin@quantgen.dev',
      'LOGIN_SUCCESS',
      'USER',
      ADMIN_USER_ID,
      null,
      60 * 24 * 30 + 5,
    ],
  ]
  rows.forEach(([actorId, actorEmail, action, targetType, targetId, details, minsAgo], i) => {
    db.auditLogs.push({
      id: `0d0d0d0d-0000-4000-8000-${String(i).padStart(12, '0')}`,
      actorId,
      actorEmail,
      action,
      targetType,
      targetId,
      details,
      ip: i % 2 ? '10.0.0.24' : '127.0.0.1',
      requestId: `req-${String(i).padStart(4, '0')}`,
      createdAt: iso(minsAgo),
    })
  })
}

/** Random walk, bounded and never ≤ ₹1 (mirrors F-TRD-2), applied lazily on read. */
export function tickPrices(db: MockDb) {
  const now = new Date().toISOString()
  for (const inst of db.instruments.values()) {
    // Mock-only jitter: up to ±0.4% per tick, in basis points as an integer.
    const bps = Math.round((Math.random() - 0.5) * 80)
    let next = new Decimal(inst.price)
      .times(new Decimal(10_000 + bps).div(10_000))
      .toDecimalPlaces(2)
    if (next.lte(1)) next = new Decimal('1.01')
    inst.price = next.toFixed(2)
    inst.changePct24h = pct(inst.price, inst.open)
    inst.updatedAt = now
  }
}

/** yyyy-mm-dd, `months` after 2026-04-05 (the seeded loan's first due date). */
export function addMonths(isoDate: string, months: number): string {
  const [y, m, d] = isoDate.split('-').map((x) => Number.parseInt(x, 10)) as [
    number,
    number,
    number,
  ]
  const total = m - 1 + months
  const yy = y + Math.floor(total / 12)
  const mm = (total % 12) + 1
  return `${yy}-${String(mm).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

/** Build a loan's instalment schedule (F-LOAN-3) starting at `firstDue`. */
export function buildSchedule(amount: string, months: number, firstDue: string) {
  return schedule(amount, months).map((r) => ({
    installmentNo: r.installmentNo,
    dueDate: addMonths(firstDue, r.installmentNo - 1),
    emi: r.emi.toFixed(2),
    principal: r.principal.toFixed(2),
    interest: r.interest.toFixed(2),
    amountDue: r.emi.toFixed(2),
    status: 'PENDING' as const,
    paidAt: null as string | null,
  }))
}

/** ₹2,00,000 over 24 months, 7 EMIs paid: numbers come from the real schedule. */
function seededLoan(): MockLoan {
  const sched = buildSchedule('200000', 24, '2026-04-05')
  const paidCount = 7
  sched.slice(0, paidCount).forEach((row) => {
    row.status = 'PAID' as never
    row.amountDue = '0.00'
    row.paidAt = `${row.dueDate}T00:30:00Z`
  })
  const first = sched[0]
  const next = sched[paidCount]
  if (!first || !next) throw new Error('seed schedule too short')
  const outstanding = sched.slice(paidCount).reduce((s, r) => s.plus(r.principal), new Decimal(0))
  return {
    id: 'b0a11111-2222-4333-8444-555566667777',
    userId: DEMO_USER_ID,
    amount: '200000.00',
    tenureMonths: 24,
    annualRate: '12.00',
    emi: first.emi,
    status: 'ACTIVE',
    outstandingPrincipal: outstanding.toFixed(2),
    nextDueDate: next.dueDate,
    createdAt: '2026-03-28T10:00:00Z',
    purpose: 'Home renovation',
    decisionReason: 'Meets income and EMI criteria',
    schedule: sched,
    monthlyIncome: '85000.00',
    disburseAccountId: 'a1f3c2d4-0001-4b5e-9c7d-111111111111',
  }
}
