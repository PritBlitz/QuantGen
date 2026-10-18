/**
 * v2 types (PRD §11–§13, contract §12 + §7.9), re-exported from `types.ts`. Fields that v2 adds
 * to v1 DTOs are optional (`AccountV2Fields`, …) until the backend ships them, so the UI keeps
 * working against the live v1 API (PRD §12.2: all additive).
 */
import type {
  AccountType,
  FieldError,
  IsoDate,
  IsoDateTime,
  LoanStatus,
  MoneyString,
  NotificationType,
  OrderRequest,
  OrderSide,
  TransactionType,
  Uuid,
} from './types'

/** A decimal (non-money) value serialised as a string, e.g. a percentage "12.50". */
export type DecimalString = string

export type ErrorCodeV2 =
  | 'STEP_UP_REQUIRED'
  | 'PIN_LOCKED'
  | 'COOLING_PERIOD'
  | 'INTENT_EXPIRED'
  | 'RULE_INVALID'
  | 'BUDGET_EXHAUSTED'
  | 'FEATURE_DISABLED'
  | 'KYC_REQUIRED'
  | 'PAYMENT_VERIFICATION_FAILED'
  | 'GATEWAY_UNAVAILABLE'

export type StepUpMethod = 'PASSKEY' | 'PIN'
export type StepUpAction =
  | 'TRANSFER'
  | 'INTENT_APPROVE'
  | 'RULE_ACTIVATE'
  | 'CARD_REVEAL'
  | 'SECURITY_SETTINGS'
  | 'FD_CLOSE'
  | 'TOKEN_CREATE'

/** Extra body on `401 STEP_UP_REQUIRED` (§12.1). */
export interface StepUpChallenge {
  methods: StepUpMethod[]
  action: StepUpAction
}

// §12.2 additive fields on v1 shapes ───────────────────────────────────────────────
export type AccountTypeV2 = AccountType | 'GOAL' | 'FIXED_DEPOSIT'
export interface GoalInfo {
  name: string
  target: MoneyString
  targetDate: IsoDate | null
  emoji: string | null
}
export interface AccountV2Fields {
  availableBalance?: MoneyString
  holdsTotal?: MoneyString
  nickname?: string | null
  goal?: GoalInfo | null
}
export type TransactionTypeV2 =
  | TransactionType
  | 'BILL_PAYMENT'
  | 'INTEREST'
  | 'CARD'
  | 'REFUND'
  | 'TOPUP'
export type TransferStatusV2 = 'COMPLETED' | 'FAILED' | 'PENDING_REVIEW' | 'REJECTED'
export type TransferSource = 'MANUAL' | 'SCHEDULED' | 'AUTOPILOT' | 'INTENT' | 'REQUEST'
export type KycStatus = 'NONE' | 'PENDING' | 'VERIFIED'
export interface UserV2Fields {
  handle?: string | null
  kycStatus?: KycStatus
  hasPasskey?: boolean
  hasPin?: boolean
}
export type NotificationTypeV2 =
  | NotificationType
  | 'PAYMENT_REQUEST'
  | 'SCHEDULED_FAILED'
  | 'RULE_RUN'
  | 'RULE_PAUSED'
  | 'BUDGET_ALERT'
  | 'NEW_DEVICE_LOGIN'
  | 'TRANSFER_UNDER_REVIEW'
  | 'PRICE_ALERT'
  | 'FD_MATURED'
  | 'CARD_DECLINED'

// Feature flags (§12.8) ─────────────────────────────────────────────────────────────
export type FeatureFlag = 'ai' | 'autopilot' | 'razorpay' | 'cards' | 'mcp'
export type FeatureFlagsDto = Record<FeatureFlag, boolean>
export interface AdminFeatureFlagDto {
  name: FeatureFlag
  enabled: boolean
  updatedAt: IsoDateTime | null
  updatedBy: string | null
}

// §12.3 Security 2.0 ────────────────────────────────────────────────────────────────
export interface PasskeyDto {
  id: Uuid
  name: string
  createdAt: IsoDateTime
  lastUsedAt: IsoDateTime | null
}
/** WebAuthn options as JSON (base64url strings for binary fields). */
export type PublicKeyCreationOptionsJson = Record<string, unknown>
export type PublicKeyRequestOptionsJson = Record<string, unknown>
export interface StepUpOptionsDto {
  methods: StepUpMethod[]
  passkeyOptions?: PublicKeyRequestOptionsJson
}
export interface StepUpVerifyRequest {
  action: StepUpAction
  credential?: Record<string, unknown>
  pin?: string
}
export interface StepUpTokenDto {
  stepUpToken: string
  expiresIn: number
}
export interface SessionDto {
  id: Uuid
  device: string
  browser: string
  ipCity: string | null
  current: boolean
  createdAt: IsoDateTime
  lastUsedAt: IsoDateTime
}
export interface LimitsDto {
  perTransfer: MoneyString
  daily: MoneyString
  perTransferMax: MoneyString
  dailyMax: MoneyString
  usedToday: MoneyString
}
export interface KycRequest {
  pan: string
  dateOfBirth: IsoDate
  address: string
}
export interface KycDto {
  status: KycStatus
  panMasked: string | null
  dateOfBirth: IsoDate | null
  address: string | null
  verifiedAt: IsoDateTime | null
}

// §12.4 Payments 2.0 ────────────────────────────────────────────────────────────────
export interface HandleDto {
  handle: string
  accountId: Uuid
  /** When the handle may next be changed (once per 30 days). */
  changeableAt: IsoDateTime
}
export interface PayeeDto {
  holderNameMasked: string
  handle: string | null
  accountNumberMasked: string
}
export interface TransferRequestV2 {
  fromAccountId: Uuid
  toAccountNumber?: string
  toHandle?: string
  amount: MoneyString
  note?: string
}
export type PaymentRequestStatus = 'OPEN' | 'PAID' | 'DECLINED' | 'EXPIRED' | 'CANCELLED'
export interface PaymentRequestDto {
  id: Uuid
  fromHandle: string
  toHandle: string
  amount: MoneyString
  note: string | null
  status: PaymentRequestStatus
  transferId: Uuid | null
  expiresAt: IsoDateTime
  createdAt: IsoDateTime
}
export type Frequency = 'ONCE' | 'WEEKLY' | 'MONTHLY'
export type ScheduledStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED'
export interface ScheduledTransferRequest {
  fromAccountId: Uuid
  toAccountNumber?: string
  toHandle?: string
  amount: MoneyString
  note?: string
  startDate: IsoDate
  frequency: Frequency
  endDate?: IsoDate
  count?: number
}
export interface ScheduledTransferDto {
  id: Uuid
  fromAccountId: Uuid
  toAccountNumberMasked: string
  toName: string
  amount: MoneyString
  note: string | null
  frequency: Frequency
  nextRunDate: IsoDate | null
  endDate: IsoDate | null
  remainingCount: number | null
  status: ScheduledStatus
  lastRun: {
    date: IsoDate
    outcome: 'SUCCEEDED' | 'RETRYING' | 'MISSED'
    transferId: Uuid | null
  } | null
}
export type BillerCategory =
  | 'ELECTRICITY'
  | 'MOBILE'
  | 'BROADBAND'
  | 'DTH'
  | 'GAS'
  | 'WATER'
  | 'CREDIT_CARD'
  | 'INSURANCE'
export interface BillerDto {
  id: Uuid
  name: string
  category: BillerCategory
  consumerNumberLabel: string
  /** Regex the consumer number must match (validated server-side too). */
  consumerNumberPattern: string
}
export interface BillDto {
  billId: Uuid
  amount: MoneyString
  dueDate: IsoDate
  customerNameMasked: string
}
export interface BillPaymentDto {
  id: Uuid
  billerId: Uuid
  billerName: string
  category: BillerCategory
  consumerNumberMasked: string
  amount: MoneyString
  fromAccountId: Uuid
  transactionId: Uuid
  createdAt: IsoDateTime
}
export type SplitType = 'EQUAL' | 'EXACT' | 'PERCENT'
export interface SplitMemberDto {
  handle: string
  displayName: string
  /** Positive: the group owes them; negative: they owe the group. */
  balance: MoneyString
}
export interface ExpenseDto {
  id: Uuid
  paidByHandle: string
  amount: MoneyString
  description: string
  splitType: SplitType
  shares: Array<{ handle: string; amount: MoneyString }>
  createdAt: IsoDateTime
}
export interface GroupDto {
  id: Uuid
  name: string
  members: SplitMemberDto[]
  expenses: ExpenseDto[]
  createdAt: IsoDateTime
}
export interface ExpenseRequest {
  paidByHandle: string
  amount: MoneyString
  description: string
  split: { type: SplitType; shares?: Array<{ handle: string; value: DecimalString }> }
}

// §7.9 Razorpay top-ups ─────────────────────────────────────────────────────────────
export type TopupStatus = 'CREATED' | 'CREDITED' | 'FAILED' | 'EXPIRED'
export interface TopupDto {
  id: Uuid
  accountId: Uuid
  amount: MoneyString
  currency: 'INR'
  status: TopupStatus
  gateway: 'RAZORPAY'
  gatewayOrderId: string
  gatewayKeyId: string
  transactionId: Uuid | null
  failureReason: string | null
  createdAt: IsoDateTime
  updatedAt: IsoDateTime
}

// §12.5 Savings and insights ─────────────────────────────────────────────────────────
export interface CreateGoalRequest {
  type: 'GOAL'
  name: string
  target: MoneyString
  targetDate?: IsoDate
  emoji?: string
}
export interface RoundUpRequest {
  enabled: boolean
  roundTo: 10 | 50 | 100
  goalAccountId: Uuid
}
export interface FdRateDto {
  minDays: number
  maxDays: number
  annualRate: DecimalString
}
export type FdStatus = 'ACTIVE' | 'MATURED' | 'CLOSED'
export interface FixedDepositRequest {
  fromAccountId: Uuid
  amount: MoneyString
  tenureDays: number
  onMaturity: 'PAYOUT' | 'RENEW'
}
export interface FixedDepositDto {
  id: Uuid
  accountNumberMasked: string
  principal: MoneyString
  annualRate: DecimalString
  tenureDays: number
  startDate: IsoDate
  maturityDate: IsoDate
  accruedInterest: MoneyString
  maturityAmount: MoneyString
  onMaturity: 'PAYOUT' | 'RENEW'
  status: FdStatus
}
export type Category =
  | 'FOOD'
  | 'GROCERIES'
  | 'SHOPPING'
  | 'TRANSPORT'
  | 'BILLS'
  | 'RENT'
  | 'ENTERTAINMENT'
  | 'HEALTH'
  | 'EDUCATION'
  | 'TRAVEL'
  | 'TRANSFERS'
  | 'INVESTMENTS'
  | 'LOANS'
  | 'INCOME'
  | 'OTHER'
export interface SpendingRowDto {
  key: string
  amount: MoneyString
  count: number
}
export interface BudgetDto {
  category: Category
  monthlyLimit: MoneyString
  spent: MoneyString
  pct: DecimalString
}
export interface ForecastDto {
  points: Array<{ date: IsoDate; balance: MoneyString }>
  warnings: Array<{
    date: IsoDate
    code: 'LOW_BALANCE' | 'EMI_AT_RISK' | 'SCHEDULED_AT_RISK'
    message: string
  }>
}
export interface NetWorthDto {
  current: MoneyString
  breakdown: {
    cash: MoneyString
    goals: MoneyString
    fixedDeposits: MoneyString
    investments: MoneyString
    loans: MoneyString
  }
  history: Array<{ month: string; value: MoneyString }>
}

// §12.6 Copilot and intents ─────────────────────────────────────────────────────────
export type AgentName =
  | 'CONCIERGE'
  | 'LEDGER_ANALYST'
  | 'PAYMENTS'
  | 'MARKETS'
  | 'PLANNER'
  | 'LOAN_ADVISOR'
  | 'FRAUD_INVESTIGATOR'
  | 'UNDERWRITING_ANALYST'
export type IntentKind =
  | 'TRANSFER'
  | 'BILL_PAYMENT'
  | 'SCHEDULED_TRANSFER'
  | 'PAYMENT_REQUEST'
  | 'ORDER'
  | 'GOAL_TRANSFER'
  | 'FD_BOOKING'
  | 'LOAN_APPLICATION'
  | 'AUTOPILOT_RULE'
export type IntentStatus = 'PROPOSED' | 'EXECUTED' | 'REJECTED' | 'EXPIRED' | 'FAILED'
export interface IntentDto {
  id: Uuid
  kind: IntentKind
  status: IntentStatus
  /** Rendered by the core from the validated payload (D-023), never by the model. */
  summary: { title: string; lines: Array<{ label: string; value: string }> }
  /** The model's own explanation; always labelled as such in the UI. */
  rationale: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  requiresStepUp: boolean
  proposedBy: { agent: AgentName }
  conversationId: Uuid
  failureCode: string | null
  resultRef: { type: string; id: Uuid } | null
  expiresAt: IsoDateTime
  createdAt: IsoDateTime
}
export interface ConversationDto {
  id: Uuid
  title: string | null
  createdAt: IsoDateTime
  updatedAt: IsoDateTime
}
export interface CitationDto {
  type: 'TRANSACTION' | 'ACCOUNT' | 'INSTRUMENT' | 'LOAN' | 'RULE' | 'METRIC'
  id: string
  label: string
}
export interface ChatMessageDto {
  id: Uuid
  role: 'USER' | 'ASSISTANT'
  text: string
  intents: IntentDto[]
  citations: CitationDto[]
  runId: Uuid | null
  createdAt: IsoDateTime
}
export interface ConversationDetailDto extends ConversationDto {
  messages: ChatMessageDto[]
}
/** Server-Sent Events from POST /copilot/conversations/{id}/messages (§12.6). */
export type CopilotEvent =
  | { type: 'token'; text: string }
  | { type: 'tool'; agent: AgentName; tool: string; status: 'STARTED' | 'DONE' | 'FAILED' }
  | { type: 'intent'; intent: IntentDto }
  | { type: 'citation'; citation: CitationDto }
  | { type: 'done'; runId: Uuid; usage: { inputTokens: number; outputTokens: number } }
  | { type: 'error'; code: string; message: string }
export interface TraceStepDto {
  agent: AgentName
  kind: 'MODEL' | 'TOOL'
  name: string
  status: 'OK' | 'ERROR'
  latencyMs: number
  inputTokens: number | null
  outputTokens: number | null
  /** Redacted, human-readable summary of arguments (never raw PII). */
  detail: string | null
}
export interface TraceDto {
  runId: Uuid
  startedAt: IsoDateTime
  totalMs: number
  costUsd: DecimalString
  steps: TraceStepDto[]
}

// §12.7 Autopilot ───────────────────────────────────────────────────────────────────
export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'
export type RuleTrigger =
  | {
      type: 'MONEY_RECEIVED'
      accountId: Uuid
      minAmount?: MoneyString
      counterpartyContains?: string
      transactionType?: TransactionTypeV2
    }
  | { type: 'SCHEDULE'; frequency: 'MONTHLY' | 'WEEKLY'; dayOfMonth?: number; weekday?: Weekday }
  | { type: 'BALANCE_ABOVE'; accountId: Uuid; threshold: MoneyString }
  | { type: 'PRICE_CROSSES'; symbol: string; direction: 'ABOVE' | 'BELOW'; price: MoneyString }
  | { type: 'BUDGET_THRESHOLD'; category: Category; percent: number }
export type RuleCondition = { type: 'BALANCE_AT_LEAST'; accountId: Uuid; amount: MoneyString }
export type RuleAmount =
  | { mode: 'FIXED'; value: MoneyString }
  | { mode: 'PERCENT_OF_TRIGGER'; value: DecimalString }
  | { mode: 'EXCESS_ABOVE'; value: MoneyString }
export type RuleAction =
  | {
      type: 'TRANSFER'
      fromAccountId?: Uuid
      toAccountId?: Uuid
      toBeneficiaryId?: Uuid
      amount: RuleAmount
    }
  | { type: 'BUY'; accountId: Uuid; symbol: string; amount: RuleAmount }
  | { type: 'PAY_BILL'; fromAccountId: Uuid; billerId: Uuid; consumerNumber: string }
  | { type: 'ROUND_UP_SWEEP'; goalAccountId: Uuid }
  | { type: 'NOTIFY'; message: string }
export interface RuleDsl {
  dslVersion: 1
  trigger: RuleTrigger
  conditions: RuleCondition[]
  actions: RuleAction[]
  caps: { perRun: MoneyString; perMonth: MoneyString }
}
export interface CompileResultDto {
  rule: RuleDsl
  rendering: string
  warnings: string[]
}
export interface ValidateResultDto {
  valid: boolean
  rendering: string | null
  fieldErrors: FieldError[]
}
export interface SimulationDto {
  firings: Array<{
    date: IsoDate
    triggerSummary: string
    actions: Array<{ summary: string; amount: MoneyString; wouldSucceed: boolean; reason?: string }>
  }>
  totalMoved: MoneyString
  failures: number
}
export type RuleStatus = 'ACTIVE' | 'PAUSED'
export interface RuleDto {
  id: Uuid
  name: string
  version: number
  status: RuleStatus
  rule: RuleDsl
  rendering: string
  pausedReason: string | null
  lastRunAt: IsoDateTime | null
  runCount: number
  movedThisMonth: MoneyString
  createdAt: IsoDateTime
  updatedAt: IsoDateTime
}
export type RuleRunOutcome = 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'SKIPPED_CAP'
export interface RuleRunDto {
  id: Uuid
  ruleVersion: number
  triggerEventId: string
  triggerSummary: string
  startedAt: IsoDateTime
  outcome: RuleRunOutcome
  actions: Array<{
    type: RuleAction['type']
    amount: MoneyString | null
    transferId?: Uuid
    orderId?: Uuid
    error?: string
  }>
}
export interface TemplateDto {
  id: string
  name: string
  description: string
  /** Plain-language text the Planner can compile. */
  prompt: string
  rule: RuleDsl | null
}

// §12.8 Risk and back office ────────────────────────────────────────────────────────
export type FraudCaseStatus = 'OPEN' | 'RELEASED' | 'REJECTED'
export interface FraudSignalDto {
  code: string
  label: string
  weight: number
}
export interface FraudCaseDto {
  id: Uuid
  status: FraudCaseStatus
  score: number
  transferId: Uuid
  amount: MoneyString
  fromAccountNumberMasked: string
  toAccountNumberMasked: string
  toName: string
  customer: { userId: Uuid; fullName: string }
  topSignal: string
  createdAt: IsoDateTime
  decidedAt: IsoDateTime | null
  decisionReason: string | null
}
export interface FraudMemoDto {
  summary: string
  suggestedAction: 'RELEASE' | 'REJECT' | 'CONTACT_CUSTOMER'
  confidence: 'LOW' | 'MEDIUM' | 'HIGH'
  generatedAt: IsoDateTime
  runId: Uuid | null
}
export interface FraudCaseDetailDto extends FraudCaseDto {
  memo: FraudMemoDto | null
  signals: FraudSignalDto[]
  customerBaseline: {
    avgTransfer: MoneyString
    transfers30d: number
    usualHoursIst: string
    accountAgeDays: number
  }
}
export interface LoanDocumentDto {
  id: Uuid
  fileName: string
  contentType: string
  sizeBytes: number
  uploadedAt: IsoDateTime
}
export interface UnderwritingDto {
  status: 'PENDING' | 'READY' | 'FAILED'
  memo: string | null
  extracted: Array<{ field: string; value: string; page: number }>
  flags: string[]
  recommendation: 'APPROVE' | 'REJECT' | 'REVIEW' | null
  generatedAt: IsoDateTime | null
}
export interface AiUsageDto {
  tokens: { input: number; output: number }
  costUsd: DecimalString
  byAgent: Array<{ agent: AgentName; runs: number; costUsd: DecimalString }>
  byDay: Array<{ date: IsoDate; costUsd: DecimalString; turns: number }>
  budget: { monthlyCapUsd: DecimalString; usedPct: DecimalString }
}

// §12.9 Trading 2.0 ─────────────────────────────────────────────────────────────────
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP_LOSS'
export type OrderValidity = 'DAY' | 'GTC'
export type OrderStatusV2 = 'FILLED' | 'REJECTED' | 'OPEN' | 'CANCELLED' | 'EXPIRED'
export interface OrderV2Fields {
  orderType?: OrderType
  limitPrice?: MoneyString | null
  triggerPrice?: MoneyString | null
  validity?: OrderValidity | null
  filledAt?: IsoDateTime | null
}
export interface OrderRequestV2 extends OrderRequest {
  orderType?: OrderType
  limitPrice?: MoneyString
  triggerPrice?: MoneyString
  validity?: OrderValidity
}
export interface WatchlistDto {
  id: Uuid
  name: string
  symbols: string[]
  createdAt: IsoDateTime
}
export interface PriceAlertDto {
  id: Uuid
  symbol: string
  direction: 'ABOVE' | 'BELOW'
  price: MoneyString
  status: 'ACTIVE' | 'TRIGGERED'
  triggeredAt: IsoDateTime | null
  createdAt: IsoDateTime
}
export type StrategyDsl =
  | { type: 'SMA_CROSS'; fast: number; slow: number }
  | { type: 'RSI'; period: number; buyBelow: number; sellAbove: number }
  | { type: 'BUY_THE_DIP'; dropPct: DecimalString; takeProfitPct: DecimalString }
  | { type: 'REBALANCE'; everyDays: number; weights: Record<string, DecimalString> }
export interface BacktestRequest {
  strategy: StrategyDsl
  symbols: string[]
  from: IsoDate
  to: IsoDate
  initialCash: MoneyString
}
export interface BacktestDto {
  id: Uuid
  strategy: StrategyDsl
  symbols: string[]
  from: IsoDate
  to: IsoDate
  initialCash: MoneyString
  finalValue: MoneyString
  returnPct: DecimalString
  maxDrawdownPct: DecimalString
  winRatePct: DecimalString
  equity: Array<{ t: IsoDate; value: MoneyString }>
  trades: Array<{
    t: IsoDate
    symbol: string
    side: OrderSide
    quantity: number
    price: MoneyString
  }>
  createdAt: IsoDateTime
}
export interface TaxPnlDto {
  fy: string
  shortTermGains: MoneyString
  longTermGains: MoneyString
  total: MoneyString
  rows: Array<{
    symbol: string
    buyDate: IsoDate
    sellDate: IsoDate
    quantity: number
    cost: MoneyString
    proceeds: MoneyString
    gain: MoneyString
    term: 'SHORT' | 'LONG'
  }>
}

// §12.10 Proof of Balance ───────────────────────────────────────────────────────────
export interface ProofRootDto {
  date: IsoDate
  root: string
  totalLiabilities: MoneyString
  leafCount: number
  chainHead: { position: number; hash: string }
}
export interface ProofPathNode {
  hash: string
  sum: MoneyString
  side: 'LEFT' | 'RIGHT'
}
export interface BalanceProofDto {
  date: IsoDate
  leaf: { accountId: Uuid; balance: MoneyString; salt: string }
  path: ProofPathNode[]
  root: string
  totalLiabilities: MoneyString
}
export interface ChainVerifyDto {
  intact: boolean
  checkedUpTo: number
  firstBrokenPosition: number | null
}
export interface SolvencyDto {
  totalLiabilities: MoneyString
  totalAssets: MoneyString
  ratio: DecimalString
}

// §12.11 Cards ──────────────────────────────────────────────────────────────────────
export type MerchantCategory =
  | 'GROCERY'
  | 'DINING'
  | 'TRAVEL'
  | 'ELECTRONICS'
  | 'GAMBLING'
  | 'FUEL'
  | 'ENTERTAINMENT'
  | 'OTHER'
export interface CardControls {
  frozen: boolean
  online: boolean
  perTxnLimit: MoneyString
  blockedCategories: MerchantCategory[]
}
export interface CardDto {
  id: Uuid
  accountId: Uuid
  last4: string
  expiry: string
  status: 'ACTIVE' | 'FROZEN'
  controls: CardControls
}
export interface CardRevealDto {
  pan: string
  cvv: string
  expiry: string
  hideAfterSeconds: number
}
export interface MerchantAuthRequest {
  cardNumber: string
  expiry: string
  cvv: string
  amount: MoneyString
  merchant: string
  category: MerchantCategory
}
export interface MerchantAuthDto {
  authId: Uuid
  status: 'APPROVED' | 'DECLINED'
  reason?: string
}

// §12.12 Developer access ───────────────────────────────────────────────────────────
export type TokenScope = 'read' | 'read+propose'
export interface PersonalTokenDto {
  id: Uuid
  name: string
  scope: TokenScope
  lastUsedAt: IsoDateTime | null
  expiresAt: IsoDateTime
}
export interface CreatedTokenDto extends PersonalTokenDto {
  /** Shown exactly once. Never stored by the client. */
  token: string
}

// §12.13 Analytics ──────────────────────────────────────────────────────────────────
export type MetricUnit = 'MONEY' | 'COUNT' | 'PERCENT'
export type Granularity = 'DAY' | 'WEEK' | 'MONTH'
export type AnalyticsMetric =
  | 'TOTAL_USERS'
  | 'NEW_USERS'
  | 'ACTIVE_USERS'
  | 'CUSTOMER_DEPOSITS'
  | 'NET_INFLOW'
  | 'TRANSFER_VOLUME'
  | 'TRANSFER_COUNT'
  | 'TRADE_VOLUME'
  | 'TRADE_COUNT'
  | 'LOAN_DISBURSED'
  | 'LOAN_REPAID'
  | 'INTEREST_INCOME'
  | 'OVERDUE_AMOUNT'
  | 'FAILED_MONEY_REQUESTS'
  | 'COPILOT_TURNS'
  | 'AI_COST_USD'
  | 'AUTOPILOT_RUNS'
  | 'FRAUD_CASES'
export type KpiKey =
  | AnalyticsMetric
  | 'FUNDED_USERS_PCT'
  | 'OUTSTANDING_PRINCIPAL'
  | 'OVERDUE_RATE_PCT'
export type BreakdownDimension =
  | 'TRANSACTION_TYPE'
  | 'ACCOUNT_TYPE'
  | 'ACCOUNT_STATUS'
  | 'INSTRUMENT'
  | 'SECTOR'
  | 'ORDER_SIDE'
  | 'LOAN_STATUS'
  | 'LOAN_PURPOSE'
  | 'FUNDING_SOURCE'
  | 'ERROR_CODE'
  | 'SPEND_CATEGORY'
export interface DateRangeQuery {
  from?: IsoDate
  to?: IsoDate
}
export interface KpiDto {
  key: KpiKey
  label: string
  unit: MetricUnit
  value: DecimalString
  previous: DecimalString
  changePct: DecimalString | null
  goodDirection: 'UP' | 'DOWN'
}
export interface OverviewDto {
  from: IsoDate
  to: IsoDate
  previousFrom: IsoDate
  previousTo: IsoDate
  kpis: KpiDto[]
}
export interface SeriesPoint {
  t: IsoDate
  value: DecimalString
}
export interface SeriesDto {
  metric: AnalyticsMetric
  label: string
  unit: MetricUnit
  granularity: Granularity
  available: boolean
  points: SeriesPoint[]
  previous?: SeriesPoint[]
}
export interface BreakdownRow {
  key: string
  label: string
  value: DecimalString | null
  count: number | null
  sharePct: DecimalString | null
  suppressed: boolean
}
export interface BreakdownDto {
  dimension: BreakdownDimension
  unit: MetricUnit
  total: DecimalString
  rows: BreakdownRow[]
}
export interface FunnelDto {
  steps: Array<{
    key: string
    label: string
    users: DecimalString
    conversionPct: DecimalString
    stepConversionPct: DecimalString
  }>
}
export interface CohortsDto {
  cohorts: Array<{ cohort: string; size: DecimalString; retentionPct: Array<DecimalString | null> }>
}
export interface LoanBookDto {
  outstandingPrincipal: MoneyString
  disbursed: MoneyString
  repaid: MoneyString
  interestEarned: MoneyString
  activeCount: DecimalString
  approvalRatePct: DecimalString
  avgTicket: MoneyString
  overdueAmount: MoneyString
  overdueRatePct: DecimalString
  ageing: Array<{ bucket: '1-30' | '31-60' | '61-90' | '90+'; count: DecimalString; amount: MoneyString }>
  byStatus: Array<{ status: LoanStatus; count: DecimalString; amount: MoneyString }>
}
export interface AdoptionDto {
  features: Array<{
    key: string
    label: string
    users: DecimalString
    pct: DecimalString
    available: boolean
  }>
  ai: { available: boolean; turns: DecimalString; costUsd: DecimalString; costPerActiveUser: DecimalString }
  autopilot: { available: boolean; runs: DecimalString; successPct: DecimalString }
  fraud: { available: boolean; cases: DecimalString; releasedPct: DecimalString }
}
export interface HealthDto {
  reconcileBalanced: boolean
  checkedAt: IsoDateTime
  sealLagSeconds?: DecimalString | null
  outboxLag?: DecimalString | null
  moneyErrorRatePct?: DecimalString | null
}
export interface MoneySummaryDto {
  from: IsoDate
  to: IsoDate
  income: MoneyString
  spending: MoneyString
  net: MoneyString
  savingsRatePct: DecimalString | null
  previous: {
    income: MoneyString
    spending: MoneyString
    net: MoneyString
    savingsRatePct: DecimalString | null
  }
  topCategory: { key: string; label: string; amount: MoneyString } | null
  largestSpend: {
    transactionId: Uuid
    amount: MoneyString
    description: string
    date: IsoDate
  } | null
}
export interface CashflowDto {
  points: Array<{
    month: string
    income: MoneyString
    spending: MoneyString
    net: MoneyString
    invested: MoneyString
    borrowed: MoneyString
  }>
}
export interface CounterpartyDto {
  label: string
  accountNumberMasked: string
  sent: MoneyString
  received: MoneyString
  count: number
}
export type HistoryRange = '1M' | '3M' | '6M' | '1Y'
export interface BalanceHistoryDto {
  accountId: Uuid | null
  points: Array<{ t: IsoDate; balance: MoneyString }>
}
export type PortfolioRange = HistoryRange | 'ALL'
export interface PortfolioAnalyticsDto {
  points: Array<{ t: IsoDate; marketValue: MoneyString; invested: MoneyString }>
  twrPct: DecimalString
  allocation: Array<{ sector: string; value: MoneyString; pct: DecimalString }>
  holdings: Array<{
    symbol: string
    name: string
    unrealizedPnl: MoneyString
    unrealizedPnlPct: DecimalString
    realizedPnl: MoneyString
  }>
  best: { symbol: string; pnlPct: DecimalString } | null
  worst: { symbol: string; pnlPct: DecimalString } | null
}
export interface LoanAnalyticsDto {
  totalInterestPaid: MoneyString
  loans: Array<{
    loanId: Uuid
    principalPaid: MoneyString
    interestPaid: MoneyString
    remainingPrincipal: MoneyString
    projectedPayoffDate: IsoDate | null
  }>
}
export interface PrepaymentDto {
  interestSaved: MoneyString
  monthsSaved: number
  newPayoffDate: IsoDate
}
export interface GoalProgressDto {
  accountId: Uuid
  name: string
  target: MoneyString
  saved: MoneyString
  pct: DecimalString
  monthlyPace: MoneyString
  projectedDate: IsoDate | null
  onTrack: boolean
}
