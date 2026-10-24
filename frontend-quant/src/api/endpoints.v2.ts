/**
 * Typed functions for every v2 endpoint (PRD §12, §7.9). Paths, verbs and bodies match the
 * contract exactly. Money-moving calls take an idempotency key; step-up is handled centrally by
 * the client interceptor (a `401 STEP_UP_REQUIRED` opens the step-up dialog and retries).
 */
import { api, idempotencyHeader } from './client'
import type {
  AccountDto,
  AccountV2Fields,
  AdminFeatureFlagDto,
  AdoptionDto,
  AiUsageDto,
  BacktestDto,
  BacktestRequest,
  BalanceHistoryDto,
  BalanceProofDto,
  BillDto,
  BillerCategory,
  BillerDto,
  SavedBillerDto,
  BillPaymentDto,
  BreakdownDimension,
  BreakdownDto,
  BudgetDto,
  CardControls,
  CardDto,
  CardDisputeDto,
  CardPaymentDto,
  AdminCardDisputeDto,
  DisputeReason,
  DisputeStatus,
  CardRevealDto,
  CashflowDto,
  ChainVerifyDto,
  CohortsDto,
  CompileResultDto,
  ConversationDetailDto,
  ConversationDto,
  CounterpartyDto,
  CreatedTokenDto,
  CreateGoalRequest,
  DateRangeQuery,
  ExpenseDto,
  ExpenseRequest,
  FdRateDto,
  FeatureFlag,
  FeatureFlagsDto,
  FixedDepositDto,
  FixedDepositRequest,
  ForecastDto,
  FraudCaseDetailDto,
  FraudCaseDto,
  FraudCaseStatus,
  FunnelDto,
  GoalProgressDto,
  Granularity,
  GroupDto,
  HandleDto,
  HealthDto,
  HistoryRange,
  IntentDto,
  IntentStatus,
  KycDto,
  KycRequest,
  LimitsDto,
  LoanAnalyticsDto,
  LoanBookDto,
  LoanDocumentDto,
  MerchantAuthDto,
  MerchantAuthRequest,
  MoneySummaryDto,
  MoneyString,
  NetWorthDto,
  OrderDto,
  OrderRequestV2,
  OrderV2Fields,
  OverviewDto,
  Page,
  PageQuery,
  PasskeyDto,
  PayeeDto,
  PaymentRequestDto,
  PaymentRequestStatus,
  PersonalTokenDto,
  PortfolioAnalyticsDto,
  PortfolioRange,
  PrepaymentDto,
  PriceAlertDto,
  ProofRootDto,
  PublicKeyCreationOptionsJson,
  PublicKeyRequestOptionsJson,
  RoundUpRequest,
  RuleDsl,
  RuleDto,
  RuleRunDto,
  ScheduledTransferDto,
  ScheduledTransferRequest,
  SeriesDto,
  SessionDto,
  SimulationDto,
  SolvencyDto,
  SpendingRowDto,
  StepUpAction,
  StepUpOptionsDto,
  StepUpTokenDto,
  StepUpVerifyRequest,
  TaxPnlDto,
  TemplateDto,
  TokenScope,
  TopupDto,
  TraceDto,
  TransferDto,
  TransferRequestV2,
  UnderwritingDto,
  AnalyticsMetric,
  AuthResponse,
  Category,
  ValidateResultDto,
  WatchlistDto,
  TransactionDto,
} from './types'

const seg = (value: string) => encodeURIComponent(value)
const data = <T>(r: { data: T }) => r.data

// Feature flags (§12.14) ───────────────────────────────────────────────────────────
export const flagsApi = {
  get: () => api.get<FeatureFlagsDto>('/feature-flags').then(data),
  adminList: () => api.get<AdminFeatureFlagDto[]>('/admin/feature-flags').then(data),
  set: (name: FeatureFlag, enabled: boolean) =>
    api.put(`/admin/feature-flags/${seg(name)}`, { enabled }).then(() => undefined),
}

// §12.3 Security 2.0 ───────────────────────────────────────────────────────────────
export const securityApi = {
  passkeyRegisterOptions: () =>
    api.post<PublicKeyCreationOptionsJson>('/auth/passkeys/register/options').then(data),
  passkeyRegister: (credential: Record<string, unknown>, name: string) =>
    api.post<PasskeyDto>('/auth/passkeys/register', { credential, name }).then(data),
  passkeys: () => api.get<PasskeyDto[]>('/auth/passkeys').then(data),
  removePasskey: (id: string) => api.delete(`/auth/passkeys/${seg(id)}`).then(() => undefined),
  passkeyLoginOptions: (email?: string) =>
    api
      .post<PublicKeyRequestOptionsJson>('/auth/passkeys/login/options', email ? { email } : {})
      .then(data),
  passkeyLogin: (credential: Record<string, unknown>) =>
    api.post<AuthResponse>('/auth/passkeys/login', { credential }).then(data),
  stepUpOptions: (action: StepUpAction) =>
    api.post<StepUpOptionsDto>('/auth/step-up/options', { action }).then(data),
  stepUpVerify: (body: StepUpVerifyRequest) =>
    api.post<StepUpTokenDto>('/auth/step-up/verify', body).then(data),
  /** The first PIN needs the account password; changing it later needs step-up instead. */
  setPin: (pin: string, password?: string) =>
    api.put('/users/me/pin', password ? { pin, password } : { pin }).then(() => undefined),
  sessions: () => api.get<SessionDto[]>('/users/me/sessions').then(data),
  revokeSession: (id: string) => api.delete(`/users/me/sessions/${seg(id)}`).then(() => undefined),
  revokeOtherSessions: () =>
    api.delete('/users/me/sessions', { params: { others: true } }).then(() => undefined),
  limits: () => api.get<LimitsDto>('/users/me/limits').then(data),
  setLimits: (body: { perTransfer?: MoneyString; daily?: MoneyString }) =>
    api.put<LimitsDto>('/users/me/limits', body).then(data),
  kyc: () => api.get<KycDto>('/users/me/kyc').then(data),
  setKyc: (body: KycRequest) => api.put<KycDto>('/users/me/kyc', body).then(data),
}

// §12.4 Payments 2.0 + §7.9 top-ups ────────────────────────────────────────────────
export const payApi = {
  setHandle: (handle: string, accountId: string) =>
    api.put<HandleDto>('/users/me/handle', { handle, accountId }).then(data),
  resolve: (q: { handle: string } | { accountNumber: string }) =>
    api.get<PayeeDto>('/payees/resolve', { params: q }).then(data),
  transfer: (body: TransferRequestV2, idempotencyKey: string) =>
    api
      .post<TransferDto>('/transfers', body, { headers: idempotencyHeader(idempotencyKey) })
      .then(data),
  createRequest: (body: { toHandle: string; amount: MoneyString; note?: string }) =>
    api.post<PaymentRequestDto>('/payment-requests', body).then(data),
  requests: (
    params: { direction: 'INCOMING' | 'OUTGOING'; status?: PaymentRequestStatus } & PageQuery,
  ) => api.get<Page<PaymentRequestDto>>('/payment-requests', { params }).then(data),
  payRequest: (id: string, fromAccountId: string, idempotencyKey: string) =>
    api
      .post<TransferDto>(
        `/payment-requests/${seg(id)}/pay`,
        { fromAccountId },
        { headers: idempotencyHeader(idempotencyKey) },
      )
      .then(data),
  declineRequest: (id: string, block = false) =>
    api.post<PaymentRequestDto>(`/payment-requests/${seg(id)}/decline`, { block }).then(data),
  createScheduled: (body: ScheduledTransferRequest, idempotencyKey: string) =>
    api
      .post<ScheduledTransferDto>('/scheduled-transfers', body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  scheduled: () => api.get<ScheduledTransferDto[]>('/scheduled-transfers').then(data),
  pauseScheduled: (id: string) =>
    api.post<ScheduledTransferDto>(`/scheduled-transfers/${seg(id)}/pause`).then(data),
  resumeScheduled: (id: string) =>
    api.post<ScheduledTransferDto>(`/scheduled-transfers/${seg(id)}/resume`).then(data),
  cancelScheduled: (id: string) =>
    api.delete(`/scheduled-transfers/${seg(id)}`).then(() => undefined),
  billers: (category?: BillerCategory) =>
    api.get<BillerDto[]>('/billers', { params: category ? { category } : {} }).then(data),
  fetchBill: (billerId: string, consumerNumber: string) =>
    api.post<BillDto>(`/billers/${seg(billerId)}/fetch`, { consumerNumber }).then(data),
  payBill: (billId: string, fromAccountId: string, idempotencyKey: string) =>
    api
      .post<BillPaymentDto>(
        '/bill-payments',
        { billId, fromAccountId },
        { headers: idempotencyHeader(idempotencyKey) },
      )
      .then(data),
  savedBillers: () => api.get<SavedBillerDto[]>('/saved-billers').then(data),
  saveBiller: (body: { billerId: string; consumerNumber: string; nickname?: string }) =>
    api.post<SavedBillerDto>('/saved-billers', body).then(data),
  /** This cycle's bill for a saved biller, so the UI never needs the full consumer number. */
  fetchSavedBill: (id: string) => api.post<BillDto>(`/saved-billers/${seg(id)}/fetch`).then(data),
  deleteSavedBiller: (id: string) => api.delete(`/saved-billers/${seg(id)}`).then(() => undefined),
  billPayments: (params: PageQuery = {}) =>
    api.get<Page<BillPaymentDto>>('/bill-payments', { params }).then(data),
  groups: () => api.get<GroupDto[]>('/split-groups').then(data),
  group: (id: string) => api.get<GroupDto>(`/split-groups/${seg(id)}`).then(data),
  createGroup: (body: { name: string; memberHandles: string[] }) =>
    api.post<GroupDto>('/split-groups', body).then(data),
  addExpense: (groupId: string, body: ExpenseRequest) =>
    api.post<ExpenseDto>(`/split-groups/${seg(groupId)}/expenses`, body).then(data),
  settleGroup: (groupId: string) =>
    api.post<PaymentRequestDto[]>(`/split-groups/${seg(groupId)}/settle`).then(data),
  createTopup: (accountId: string, amount: MoneyString, idempotencyKey: string) =>
    api
      .post<TopupDto>(
        `/accounts/${seg(accountId)}/topups`,
        { amount },
        { headers: idempotencyHeader(idempotencyKey) },
      )
      .then(data),
  verifyTopup: (id: string, gatewayPaymentId: string, gatewaySignature: string) =>
    api
      .post<TopupDto>(`/topups/${seg(id)}/verify`, { gatewayPaymentId, gatewaySignature })
      .then(data),
  topup: (id: string) => api.get<TopupDto>(`/topups/${seg(id)}`).then(data),
  topups: (accountId: string, params: PageQuery = {}) =>
    api.get<Page<TopupDto>>(`/accounts/${seg(accountId)}/topups`, { params }).then(data),
}

// §12.5 Savings and insights ───────────────────────────────────────────────────────
export const savingsApi = {
  createGoal: (body: CreateGoalRequest) =>
    api.post<AccountDto & AccountV2Fields>('/accounts', body).then(data),
  roundUp: (accountId: string) =>
    api
      .get<RoundUpRequest & { accountId: string }>(`/accounts/${seg(accountId)}/round-up`)
      .then(data),
  setRoundUp: (accountId: string, body: RoundUpRequest) =>
    api.put(`/accounts/${seg(accountId)}/round-up`, body).then(() => undefined),
  fdRates: () => api.get<FdRateDto[]>('/fixed-deposits/rates').then(data),
  bookFd: (body: FixedDepositRequest, idempotencyKey: string) =>
    api
      .post<FixedDepositDto>('/fixed-deposits', body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  fds: () => api.get<FixedDepositDto[]>('/fixed-deposits').then(data),
  closeFd: (id: string, idempotencyKey: string) =>
    api
      .post<FixedDepositDto>(`/fixed-deposits/${seg(id)}/close`, undefined, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  spending: (params: DateRangeQuery & { groupBy: 'category' | 'month' }) =>
    api.get<SpendingRowDto[]>('/insights/spending', { params }).then(data),
  recategorize: (transactionId: string, category: Category, applyToCounterparty: boolean) =>
    api
      .patch<TransactionDto>(`/transactions/${seg(transactionId)}/category`, {
        category,
        applyToCounterparty,
      })
      .then(data),
  budgets: () => api.get<BudgetDto[]>('/budgets').then(data),
  setBudgets: (body: Array<{ category: Category; monthlyLimit: MoneyString }>) =>
    api.put<BudgetDto[]>('/budgets', body).then(data),
  forecast: (days = 30) =>
    api.get<ForecastDto>('/insights/forecast', { params: { days } }).then(data),
  netWorth: (months = 12) =>
    api.get<NetWorthDto>('/insights/net-worth', { params: { months } }).then(data),
}

// §12.6 Copilot and intents (the SSE stream lives in features/copilot/stream.ts) ────
export const copilotApi = {
  createConversation: () => api.post<{ id: string }>('/copilot/conversations').then(data),
  conversations: () => api.get<ConversationDto[]>('/copilot/conversations').then(data),
  conversation: (id: string) =>
    api.get<ConversationDetailDto>(`/copilot/conversations/${seg(id)}`).then(data),
  deleteConversation: (id: string) =>
    api.delete(`/copilot/conversations/${seg(id)}`).then(() => undefined),
  trace: (runId: string) => api.get<TraceDto>(`/copilot/runs/${seg(runId)}/trace`).then(data),
  intents: (status?: IntentStatus) =>
    api.get<IntentDto[]>('/intents', { params: status ? { status } : {} }).then(data),
  approveIntent: (id: string, idempotencyKey: string) =>
    api
      .post<IntentDto>(`/intents/${seg(id)}/approve`, undefined, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  rejectIntent: (id: string) => api.post<IntentDto>(`/intents/${seg(id)}/reject`).then(data),
}

// §12.7 Autopilot ──────────────────────────────────────────────────────────────────
export const autopilotApi = {
  compile: (text: string) => api.post<CompileResultDto>('/autopilot/compile', { text }).then(data),
  validate: (rule: RuleDsl) =>
    api.post<ValidateResultDto>('/autopilot/validate', { rule }).then(data),
  simulate: (rule: RuleDsl, days = 90) =>
    api.post<SimulationDto>('/autopilot/simulate', { rule, days }).then(data),
  create: (name: string, rule: RuleDsl) =>
    api.post<RuleDto>('/autopilot/rules', { name, rule }).then(data),
  rules: () => api.get<RuleDto[]>('/autopilot/rules').then(data),
  rule: (id: string) => api.get<RuleDto>(`/autopilot/rules/${seg(id)}`).then(data),
  update: (id: string, name: string, rule: RuleDsl) =>
    api.put<RuleDto>(`/autopilot/rules/${seg(id)}`, { name, rule }).then(data),
  pause: (id: string) => api.post<RuleDto>(`/autopilot/rules/${seg(id)}/pause`).then(data),
  resume: (id: string) => api.post<RuleDto>(`/autopilot/rules/${seg(id)}/resume`).then(data),
  remove: (id: string) => api.delete(`/autopilot/rules/${seg(id)}`).then(() => undefined),
  runs: (id: string, params: PageQuery = {}) =>
    api.get<Page<RuleRunDto>>(`/autopilot/rules/${seg(id)}/runs`, { params }).then(data),
  templates: () => api.get<TemplateDto[]>('/autopilot/templates').then(data),
}

// §12.8 Risk and back office ───────────────────────────────────────────────────────
export const riskApi = {
  cases: (params: { status?: FraudCaseStatus } & PageQuery) =>
    api.get<Page<FraudCaseDto>>('/admin/fraud-cases', { params }).then(data),
  case: (id: string) => api.get<FraudCaseDetailDto>(`/admin/fraud-cases/${seg(id)}`).then(data),
  // Both post or free held money, so each takes an Idempotency-Key (V2-M6).
  release: (id: string, reason: string, idempotencyKey: string) =>
    api
      .post<FraudCaseDto>(
        `/admin/fraud-cases/${seg(id)}/release`,
        { reason },
        { headers: idempotencyHeader(idempotencyKey) },
      )
      .then(data),
  reject: (id: string, reason: string, idempotencyKey: string) =>
    api
      .post<FraudCaseDto>(
        `/admin/fraud-cases/${seg(id)}/reject`,
        { reason },
        { headers: idempotencyHeader(idempotencyKey) },
      )
      .then(data),
  uploadDocument: (loanId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    form.append('consent', 'true')
    return api.post<LoanDocumentDto>(`/loans/${seg(loanId)}/documents`, form).then(data)
  },
  documents: (loanId: string) =>
    api.get<LoanDocumentDto[]>(`/loans/${seg(loanId)}/documents`).then(data),
  underwriting: (loanId: string) =>
    api.get<UnderwritingDto>(`/admin/loans/${seg(loanId)}/underwriting`).then(data),
  aiUsage: (params: DateRangeQuery = {}) =>
    api.get<AiUsageDto>('/admin/ai/usage', { params }).then(data),
  aiRun: (runId: string) => api.get<TraceDto>(`/admin/ai/runs/${seg(runId)}`).then(data),
}

// §12.9 Trading 2.0 ────────────────────────────────────────────────────────────────
export const tradeApi = {
  placeOrder: (body: OrderRequestV2, idempotencyKey: string) =>
    api
      .post<OrderDto & OrderV2Fields>('/orders', body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  cancelOrder: (id: string) =>
    api.post<OrderDto & OrderV2Fields>(`/orders/${seg(id)}/cancel`).then(data),
  watchlists: () => api.get<WatchlistDto[]>('/watchlists').then(data),
  createWatchlist: (name: string) => api.post<WatchlistDto>('/watchlists', { name }).then(data),
  deleteWatchlist: (id: string) => api.delete(`/watchlists/${seg(id)}`).then(() => undefined),
  addToWatchlist: (id: string, symbol: string) =>
    api.post<WatchlistDto>(`/watchlists/${seg(id)}/symbols/${seg(symbol)}`).then(data),
  removeFromWatchlist: (id: string, symbol: string) =>
    api.delete(`/watchlists/${seg(id)}/symbols/${seg(symbol)}`).then(() => undefined),
  alerts: () => api.get<PriceAlertDto[]>('/price-alerts').then(data),
  createAlert: (body: { symbol: string; direction: 'ABOVE' | 'BELOW'; price: MoneyString }) =>
    api.post<PriceAlertDto>('/price-alerts', body).then(data),
  deleteAlert: (id: string) => api.delete(`/price-alerts/${seg(id)}`).then(() => undefined),
  backtest: (body: BacktestRequest) => api.post<BacktestDto>('/backtests', body).then(data),
  backtests: () => api.get<BacktestDto[]>('/backtests').then(data),
  taxPnl: (fy: string) =>
    api.get<TaxPnlDto>('/reports/tax-pnl', { params: { fy, format: 'json' } }).then(data),
  taxPnlCsv: (fy: string) =>
    api
      .get<Blob>('/reports/tax-pnl', { params: { fy, format: 'csv' }, responseType: 'blob' })
      .then(data),
}

// §12.10 Proof of Balance ──────────────────────────────────────────────────────────
export const proofApi = {
  roots: () => api.get<ProofRootDto[]>('/public/proofs/roots').then(data),
  proof: (accountId: string, date?: string) =>
    api
      .get<BalanceProofDto>(`/proofs/accounts/${seg(accountId)}`, { params: date ? { date } : {} })
      .then(data),
  verifyChain: () => api.get<ChainVerifyDto>('/admin/ledger/verify-chain').then(data),
  solvency: () => api.get<SolvencyDto>('/admin/ledger/solvency').then(data),
}

// §12.11 Cards ─────────────────────────────────────────────────────────────────────
export const cardsApi = {
  create: (accountId: string) => api.post<CardDto>(`/accounts/${seg(accountId)}/cards`).then(data),
  list: () => api.get<CardDto[]>('/cards').then(data),
  reveal: (id: string) => api.post<CardRevealDto>(`/cards/${seg(id)}/reveal`).then(data),
  controls: (id: string, body: Partial<CardControls>) =>
    api.patch<CardDto>(`/cards/${seg(id)}/controls`, body).then(data),
  // All three move money (a hold, its capture, a reversal), so each takes an Idempotency-Key (V2-M9).
  authorize: (body: MerchantAuthRequest, idempotencyKey: string) =>
    api
      .post<MerchantAuthDto>('/sandbox/merchant/authorize', body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
  capture: (authId: string, idempotencyKey: string, amount?: MoneyString) =>
    api
      .post(`/sandbox/merchant/${seg(authId)}/capture`, amount ? { amount } : {}, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(() => undefined),
  refund: (authId: string, idempotencyKey: string, amount?: MoneyString) =>
    api
      .post(`/sandbox/merchant/${seg(authId)}/refund`, amount ? { amount } : {}, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(() => undefined),
  // F-CARD-4 disputes. Opening one moves no money (🔒, no key); the admin's resolve does (🔒💰).
  payments: (cardId: string, params: PageQuery = {}) =>
    api.get<Page<CardPaymentDto>>(`/cards/${seg(cardId)}/payments`, { params }).then(data),
  openDispute: (
    cardId: string,
    authId: string,
    body: { reason: DisputeReason; amount?: MoneyString; note?: string },
  ) =>
    api
      .post<CardDisputeDto>(`/cards/${seg(cardId)}/payments/${seg(authId)}/dispute`, body)
      .then(data),
  disputes: () => api.get<CardDisputeDto[]>('/card-disputes').then(data),
  adminDisputes: (params: PageQuery & { status?: DisputeStatus } = {}) =>
    api.get<Page<AdminCardDisputeDto>>('/admin/card-disputes', { params }).then(data),
  resolveDispute: (
    id: string,
    body: { outcome: 'REFUNDED' | 'REJECTED'; reason: string },
    idempotencyKey: string,
  ) =>
    api
      .post<CardDisputeDto>(`/admin/card-disputes/${seg(id)}/resolve`, body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then(data),
}

// §12.12 Developer access ──────────────────────────────────────────────────────────
export const tokensApi = {
  create: (body: { name: string; scope: TokenScope; expiresInDays: number }) =>
    api.post<CreatedTokenDto>('/users/me/tokens', body).then(data),
  list: () => api.get<PersonalTokenDto[]>('/users/me/tokens').then(data),
  revoke: (id: string) => api.delete(`/users/me/tokens/${seg(id)}`).then(() => undefined),
}

// §12.13 Analytics ─────────────────────────────────────────────────────────────────
export type AdminReport =
  'overview' | 'timeseries' | 'breakdown' | 'funnel' | 'cohorts' | 'loan-book'

export const analyticsApi = {
  overview: (q: DateRangeQuery) =>
    api.get<OverviewDto>('/admin/analytics/overview', { params: q }).then(data),
  series: (
    q: DateRangeQuery & { metric: AnalyticsMetric; granularity: Granularity; compare?: boolean },
  ) => api.get<SeriesDto>('/admin/analytics/timeseries', { params: q }).then(data),
  breakdown: (q: DateRangeQuery & { dimension: BreakdownDimension; limit?: number }) =>
    api.get<BreakdownDto>('/admin/analytics/breakdown', { params: q }).then(data),
  funnel: (q: DateRangeQuery) =>
    api.get<FunnelDto>('/admin/analytics/funnel', { params: q }).then(data),
  cohorts: (months = 6) =>
    api.get<CohortsDto>('/admin/analytics/cohorts', { params: { months } }).then(data),
  loanBook: () => api.get<LoanBookDto>('/admin/analytics/loan-book').then(data),
  adoption: (q: DateRangeQuery) =>
    api.get<AdoptionDto>('/admin/analytics/adoption', { params: q }).then(data),
  health: () => api.get<HealthDto>('/admin/analytics/health').then(data),
  exportCsv: (report: AdminReport, q: Record<string, string | number | boolean | undefined>) =>
    api
      .get<Blob>('/admin/analytics/export', {
        params: { report, ...q, format: 'csv' },
        responseType: 'blob',
      })
      .then(data),
  // Personal
  summary: (q: DateRangeQuery) =>
    api.get<MoneySummaryDto>('/analytics/summary', { params: q }).then(data),
  cashflow: (months = 12) =>
    api.get<CashflowDto>('/analytics/cashflow', { params: { months } }).then(data),
  counterparties: (q: DateRangeQuery & { limit?: number }) =>
    api.get<CounterpartyDto[]>('/analytics/counterparties', { params: q }).then(data),
  balanceHistory: (q: { accountId?: string; range: HistoryRange }) =>
    api.get<BalanceHistoryDto>('/analytics/balance-history', { params: q }).then(data),
  portfolio: (range: PortfolioRange) =>
    api.get<PortfolioAnalyticsDto>('/analytics/portfolio', { params: { range } }).then(data),
  loans: () => api.get<LoanAnalyticsDto>('/analytics/loans').then(data),
  prepayment: (loanId: string, amount: MoneyString) =>
    api.post<PrepaymentDto>(`/analytics/loans/${seg(loanId)}/prepayment`, { amount }).then(data),
  goals: () => api.get<GoalProgressDto[]>('/analytics/goals').then(data),
}
