/**
 * Types for the QuantGen API, mirroring PRD §7 exactly. Never add a field the PRD does not
 * define; when the backend is live, verify against /v3/api-docs.
 *
 * Conventions (§7): money is a decimal **string**, timestamps are ISO-8601 UTC strings,
 * ids are UUID strings.
 */

/** A decimal amount serialised as a string, e.g. "1250.50". Never convert to `number`. */
export type MoneyString = string
export type Uuid = string
export type IsoDateTime = string
/** ISO date (yyyy-mm-dd) for due dates. */
export type IsoDate = string

// §7.1 ─────────────────────────────────────────────────────────────────────────────
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'INSUFFICIENT_FUNDS'
  | 'ACCOUNT_FROZEN'
  | 'LIMIT_EXCEEDED'
  | 'IDEMPOTENCY_CONFLICT'
  | 'INSUFFICIENT_HOLDINGS'
  | 'LOAN_NOT_ELIGIBLE'
  | 'INVALID_STATE'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR'

export interface FieldError {
  field: string
  message: string
}

export interface ErrorBody {
  timestamp: IsoDateTime
  status: number
  code: ErrorCode
  message: string
  path: string
  fieldErrors?: FieldError[]
  requestId: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PageQuery {
  page?: number
  size?: number
}

// §7.2 Auth ────────────────────────────────────────────────────────────────────────
export type Role = 'CUSTOMER' | 'ADMIN'

export interface UserDto {
  id: Uuid
  fullName: string
  email: string
  phone: string
  role: Role
  createdAt: IsoDateTime
}

export interface RegisterRequest {
  fullName: string
  email: string
  phone: string
  password: string
}

export interface LoginRequest {
  email: string
  password: string
}

/** Returned by /auth/login and /auth/refresh. `expiresIn` is in seconds (900). */
export interface AuthResponse {
  accessToken: string
  expiresIn: number
  user: UserDto
}

// §7.3 Accounts ────────────────────────────────────────────────────────────────────
export type AccountType = 'SAVINGS' | 'CHECKING'
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'

export interface AccountDto {
  id: Uuid
  accountNumber: string
  type: AccountType
  status: AccountStatus
  balance: MoneyString
  currency: 'INR'
  createdAt: IsoDateTime
}

export interface CreateAccountRequest {
  type: AccountType
}

export interface DepositRequest {
  amount: MoneyString
}

export type Direction = 'DEBIT' | 'CREDIT'
export type TransactionType =
  'DEPOSIT' | 'TRANSFER' | 'TRADE' | 'LOAN_DISBURSAL' | 'LOAN_REPAYMENT' | 'REVERSAL'

export interface TransactionDto {
  id: Uuid
  journalEntryId: Uuid
  accountId: Uuid
  direction: Direction
  amount: MoneyString
  balanceAfter: MoneyString
  type: TransactionType
  description: string
  counterparty: string | null
  createdAt: IsoDateTime
}

export interface TransactionQuery extends PageQuery {
  from?: IsoDateTime
  to?: IsoDateTime
  type?: TransactionType
}

// §7.4 Payments ────────────────────────────────────────────────────────────────────
export interface TransferRequest {
  fromAccountId: Uuid
  toAccountNumber: string
  amount: MoneyString
  note?: string
}

export interface TransferDto {
  id: Uuid
  fromAccountNumber: string
  toAccountNumber: string
  toName: string
  amount: MoneyString
  note: string | null
  status: 'COMPLETED' | 'FAILED'
  createdAt: IsoDateTime
}

export interface BeneficiaryDto {
  id: Uuid
  nickname: string
  accountNumber: string
  holderName: string
  createdAt: IsoDateTime
}

export interface CreateBeneficiaryRequest {
  nickname: string
  accountNumber: string
}

// §7.5 Trading ─────────────────────────────────────────────────────────────────────
export interface InstrumentDto {
  symbol: string
  name: string
  sector: string
  price: MoneyString
  /** Percentage change over 24h as a decimal string, e.g. "-1.42". */
  changePct24h: string
  updatedAt: IsoDateTime
}

export type PriceRange = '1H' | '1D' | '1W'

export interface PricePointDto {
  t: IsoDateTime
  price: MoneyString
}

export type OrderSide = 'BUY' | 'SELL'

export interface OrderRequest {
  accountId: Uuid
  symbol: string
  side: OrderSide
  quantity: number
}

export interface OrderDto {
  id: Uuid
  symbol: string
  side: OrderSide
  quantity: number
  price: MoneyString
  total: MoneyString
  status: 'FILLED' | 'REJECTED'
  createdAt: IsoDateTime
}

export interface HoldingDto {
  symbol: string
  name: string
  quantity: number
  avgCost: MoneyString
  price: MoneyString
  marketValue: MoneyString
  unrealizedPnl: MoneyString
  unrealizedPnlPct: string
}

export interface PortfolioDto {
  holdings: HoldingDto[]
  totalMarketValue: MoneyString
  totalCost: MoneyString
  totalUnrealizedPnl: MoneyString
  totalRealizedPnl: MoneyString
}

// §7.6 Loans ───────────────────────────────────────────────────────────────────────
export type LoanStatus = 'PENDING' | 'APPROVED' | 'ACTIVE' | 'CLOSED' | 'REJECTED'
export type InstallmentStatus = 'PENDING' | 'PAID' | 'OVERDUE'

export interface LoanQuoteRequest {
  amount: MoneyString
  tenureMonths: number
  monthlyIncome: MoneyString
}

export interface LoanQuoteDto {
  emi: MoneyString
  totalInterest: MoneyString
  totalPayable: MoneyString
  eligible: boolean
  reason?: string
}

export interface LoanApplicationRequest {
  amount: MoneyString
  tenureMonths: number
  monthlyIncome: MoneyString
  purpose: string
  disburseAccountId: Uuid
}

export interface LoanDto {
  id: Uuid
  amount: MoneyString
  tenureMonths: number
  /** Annual rate as a decimal string, e.g. "12.00". */
  annualRate: string
  emi: MoneyString
  status: LoanStatus
  outstandingPrincipal: MoneyString
  nextDueDate: IsoDate | null
  createdAt: IsoDateTime
}

export interface LoanInstallmentDto {
  installmentNo: number
  dueDate: IsoDate
  emi: MoneyString
  principal: MoneyString
  interest: MoneyString
  status: InstallmentStatus
  paidAt: IsoDateTime | null
}

export interface LoanDetailDto extends LoanDto {
  purpose: string
  decisionReason: string | null
  schedule: LoanInstallmentDto[]
}

export interface RepayRequest {
  accountId: Uuid
  amount?: MoneyString
}

export interface RepaymentDto {
  id: Uuid
  loanId: Uuid
  amount: MoneyString
  principalPaid: MoneyString
  interestPaid: MoneyString
  createdAt: IsoDateTime
}

// §7.8 Notifications ───────────────────────────────────────────────────────────────
// NotificationDto's fields are not yet defined in PRD §7; the backend will add them at M5.
// Until then only the unread count is typed.
export interface UnreadCountDto {
  count: number
}

// §7.7 Admin: AdminUserDto, AdminUserDetailDto, StatsDto, AuditLogDto and the loan
// "applicant" shape are not yet defined in PRD §7 (backend proposes them at M5).
