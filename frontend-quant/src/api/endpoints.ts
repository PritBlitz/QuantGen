/**
 * Typed functions for every customer-facing endpoint in PRD §7. Paths, verbs and bodies
 * match the contract exactly.
 */
import { api, idempotencyHeader } from './client'
import type {
  AccountDto,
  AccountStatusRequest,
  AdminLoanDto,
  CryptoPriceDto,
  AdminLoanQuery,
  AdminUserDetailDto,
  AdminUserDto,
  AdminUserQuery,
  AuditLogDto,
  AuditLogQuery,
  AuthResponse,
  LoanDecisionRequest,
  NotificationDto,
  ReconcileDto,
  StatsDto,
  BeneficiaryDto,
  CreateAccountRequest,
  CreateBeneficiaryRequest,
  DepositRequest,
  InstrumentDto,
  IsoDate,
  LoanApplicationRequest,
  LoanDetailDto,
  LoanDto,
  LoanQuoteDto,
  LoanQuoteRequest,
  LoginRequest,
  OrderDto,
  OrderRequest,
  Page,
  PageQuery,
  PortfolioDto,
  PricePointDto,
  PriceRange,
  RegisterRequest,
  RepaymentDto,
  RepayRequest,
  TransactionDto,
  TransactionQuery,
  TransferDto,
  TransferRequest,
  UnreadCountDto,
  UserDto,
} from './types'

const seg = (value: string) => encodeURIComponent(value)

// §7.2 Auth
export const authApi = {
  register: (body: RegisterRequest) =>
    api.post<UserDto>('/auth/register', body).then((r) => r.data),
  login: (body: LoginRequest) => api.post<AuthResponse>('/auth/login', body).then((r) => r.data),
  logout: () => api.post('/auth/logout').then(() => undefined),
  me: () => api.get<UserDto>('/users/me').then((r) => r.data),
}

// §7.3 Accounts
export const accountsApi = {
  list: () => api.get<AccountDto[]>('/accounts').then((r) => r.data),
  create: (body: CreateAccountRequest) =>
    api.post<AccountDto>('/accounts', body).then((r) => r.data),
  get: (id: string) => api.get<AccountDto>(`/accounts/${seg(id)}`).then((r) => r.data),
  deposit: (id: string, body: DepositRequest, idempotencyKey: string) =>
    api
      .post<TransactionDto>(`/accounts/${seg(id)}/deposit`, body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then((r) => r.data),
  transactions: (id: string, params: TransactionQuery = {}) =>
    api
      .get<Page<TransactionDto>>(`/accounts/${seg(id)}/transactions`, { params })
      .then((r) => r.data),
  /** IST dates (yyyy-MM-dd); default last 30 days, max 366-day range. */
  statement: (id: string, params: { from?: IsoDate; to?: IsoDate }) =>
    api
      .get<Blob>(`/accounts/${seg(id)}/statement`, {
        params: { ...params, format: 'csv' },
        responseType: 'blob',
      })
      .then((r) => r.data),
  /** Savings, checking or goal (added 2026-10-25). 🛡️ SECURITY_SETTINGS; 409 lists the blockers. */
  close: (id: string) => api.post<AccountDto>(`/accounts/${seg(id)}/close`).then((r) => r.data),
}

// §7.4 Payments
export const paymentsApi = {
  transfer: (body: TransferRequest, idempotencyKey: string) =>
    api
      .post<TransferDto>('/transfers', body, { headers: idempotencyHeader(idempotencyKey) })
      .then((r) => r.data),
  transfers: (params: PageQuery = {}) =>
    api.get<Page<TransferDto>>('/transfers', { params }).then((r) => r.data),
  beneficiaries: () => api.get<BeneficiaryDto[]>('/beneficiaries').then((r) => r.data),
  addBeneficiary: (body: CreateBeneficiaryRequest) =>
    api.post<BeneficiaryDto>('/beneficiaries', body).then((r) => r.data),
  removeBeneficiary: (id: string) => api.delete(`/beneficiaries/${seg(id)}`).then(() => undefined),
}

// §7.5 Trading
export const marketApi = {
  instruments: () => api.get<InstrumentDto[]>('/market/instruments').then((r) => r.data),
  instrument: (symbol: string) =>
    api.get<InstrumentDto>(`/market/instruments/${seg(symbol)}`).then((r) => r.data),
  history: (symbol: string, range: PriceRange) =>
    api
      .get<PricePointDto[]>(`/market/instruments/${seg(symbol)}/history`, { params: { range } })
      .then((r) => r.data),
  placeOrder: (body: OrderRequest, idempotencyKey: string) =>
    api
      .post<OrderDto>('/orders', body, { headers: idempotencyHeader(idempotencyKey) })
      .then((r) => r.data),
  orders: (params: PageQuery = {}) =>
    api.get<Page<OrderDto>>('/orders', { params }).then((r) => r.data),
  portfolio: () => api.get<PortfolioDto>('/portfolio').then((r) => r.data),
  /** F-TRD-12, flag `crypto`: 503 FEATURE_DISABLED when off, 503 GATEWAY_UNAVAILABLE before the first fetch. */
  crypto: () => api.get<CryptoPriceDto[]>('/market/crypto').then((r) => r.data),
}

// §7.6 Loans
export const loansApi = {
  quote: (body: LoanQuoteRequest) =>
    api.post<LoanQuoteDto>('/loans/quote', body).then((r) => r.data),
  apply: (body: LoanApplicationRequest) => api.post<LoanDto>('/loans', body).then((r) => r.data),
  list: () => api.get<LoanDto[]>('/loans').then((r) => r.data),
  get: (id: string) => api.get<LoanDetailDto>(`/loans/${seg(id)}`).then((r) => r.data),
  repay: (id: string, body: RepayRequest, idempotencyKey: string) =>
    api
      .post<RepaymentDto>(`/loans/${seg(id)}/repay`, body, {
        headers: idempotencyHeader(idempotencyKey),
      })
      .then((r) => r.data),
}

// §7.7 Admin (role ADMIN; the server enforces it, the UI only hides the routes)
export const adminApi = {
  users: (params: AdminUserQuery = {}) =>
    api.get<Page<AdminUserDto>>('/admin/users', { params }).then((r) => r.data),
  user: (id: string) => api.get<AdminUserDetailDto>(`/admin/users/${seg(id)}`).then((r) => r.data),
  setAccountStatus: (id: string, body: AccountStatusRequest) =>
    api.patch<AccountDto>(`/admin/accounts/${seg(id)}/status`, body).then((r) => r.data),
  loans: (params: AdminLoanQuery = {}) =>
    api.get<Page<AdminLoanDto>>('/admin/loans', { params }).then((r) => r.data),
  approveLoan: (id: string, body: LoanDecisionRequest) =>
    api.post<LoanDto>(`/admin/loans/${seg(id)}/approve`, body).then((r) => r.data),
  rejectLoan: (id: string, body: Required<LoanDecisionRequest>) =>
    api.post<LoanDto>(`/admin/loans/${seg(id)}/reject`, body).then((r) => r.data),
  stats: () => api.get<StatsDto>('/admin/stats').then((r) => r.data),
  auditLogs: (params: AuditLogQuery = {}) =>
    api.get<Page<AuditLogDto>>('/admin/audit-logs', { params }).then((r) => r.data),
  reconcile: () => api.get<ReconcileDto>('/admin/ledger/reconcile').then((r) => r.data),
}

// §7.8 Notifications
export const notificationsApi = {
  list: (unreadOnly = false) =>
    api
      .get<NotificationDto[]>('/notifications', { params: unreadOnly ? { unreadOnly } : {} })
      .then((r) => r.data),
  unreadCount: () => api.get<UnreadCountDto>('/notifications/unread-count').then((r) => r.data),
  markRead: (id: string) => api.patch(`/notifications/${seg(id)}/read`).then(() => undefined),
  markAllRead: () => api.post('/notifications/read-all').then(() => undefined),
}
