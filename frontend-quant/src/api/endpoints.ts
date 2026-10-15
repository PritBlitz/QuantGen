/**
 * Typed functions for every customer-facing endpoint in PRD §7. Paths, verbs and bodies
 * match the contract exactly. Admin endpoints (§7.7) are added once their DTOs are defined.
 */
import { api, idempotencyHeader } from './client'
import type {
  AccountDto,
  AuthResponse,
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

// §7.8 Notifications (list/read wait for NotificationDto to be defined)
export const notificationsApi = {
  unreadCount: () => api.get<UnreadCountDto>('/notifications/unread-count').then((r) => r.data),
  markRead: (id: string) => api.patch(`/notifications/${seg(id)}/read`).then(() => undefined),
}
