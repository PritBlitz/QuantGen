/**
 * In-memory mock database for MSW. Shapes are PRD §7 DTOs exactly. Money math uses Decimal,
 * same as the app. Dev/test only: never bundled into production builds.
 */
import type {
  AccountDto,
  BeneficiaryDto,
  InstrumentDto,
  LoanDto,
  OrderDto,
  TransactionDto,
  TransferDto,
  UserDto,
} from '@/api/types'
import { Decimal } from '@/lib/money'

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

export interface MockDb {
  users: Map<string, MockUser>
  accounts: Map<string, AccountDto & { userId: string }>
  transactions: TransactionDto[]
  transfers: Array<TransferDto & { userId: string }>
  beneficiaries: Array<BeneficiaryDto & { userId: string }>
  instruments: Map<string, InstrumentDto & { open: string }>
  holdings: MockHolding[]
  orders: Array<OrderDto & { userId: string }>
  loans: Array<LoanDto & { userId: string }>
  idempotency: Map<string, { hash: string; status: number; body: unknown }>
  failedLogins: Map<string, number[]>
  unread: Map<string, number>
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
    unread: new Map(),
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
    [savings.id, 'CREDIT', '25000.0000', 'TRANSFER', 'Transfer from Priya Nair', 'Priya Nair', 14],
    [checking.id, 'DEBIT', '6425.0000', 'TRADE', 'Bought 5 × QGEN', null, 95],
    [savings.id, 'DEBIT', '9414.6900', 'LOAN_REPAYMENT', 'EMI 7 of 24', null, 60 * 26],
    [savings.id, 'DEBIT', '12000.0000', 'TRANSFER', 'Rent — October', 'Meera Iyer', 60 * 50],
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
    { userId: DEMO_USER_ID, symbol: 'QGEN', quantity: 40, avgCost: '1180.2500', realizedPnl: '0' },
    {
      userId: DEMO_USER_ID,
      symbol: 'TCS',
      quantity: 12,
      avgCost: '3890.0000',
      realizedPnl: '1240.5000',
    },
    { userId: DEMO_USER_ID, symbol: 'HDFC', quantity: 30, avgCost: '1745.1000', realizedPnl: '0' },
    {
      userId: DEMO_USER_ID,
      symbol: 'ITC',
      quantity: 100,
      avgCost: '451.8000',
      realizedPnl: '-310.0000',
    },
  )

  db.loans.push({
    id: 'b0a11111-2222-4333-8444-555566667777',
    userId: DEMO_USER_ID,
    amount: '200000.0000',
    tenureMonths: 24,
    annualRate: '12.00',
    emi: '9414.6900',
    status: 'ACTIVE',
    outstandingPrincipal: '142318.4600',
    nextDueDate: '2026-11-05',
    createdAt: '2026-03-28T10:00:00Z',
  })

  db.unread.set(DEMO_USER_ID, 3)
  return db
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
