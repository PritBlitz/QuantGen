# QuantGen — Product Requirements Document

**Version:** 1.0 (core app)  ·  **Owner:** Pritish Biswas  ·  **Last updated:** 2026-10-16

> This file is the single source of truth for scope **and** the API contract between
> `backend-quant/` and `frontend-quant/`. If the contract has to change, update §7 here
> first, then implement. Never let code and this file disagree.

---

## 1. Overview

QuantGen is a unified digital banking and investment platform. One login gives a customer
bank accounts, transfers, stock trading and loans, all settled through a single
double-entry ledger with ACID guarantees.

**Version 1 (this document)** is the core app: banking, trading, loans and admin, built
correctly and deployed with Docker.
**Version 2** (§11) adds the AI agent layer and the full DevOps platform on top. Nothing
from v2 starts before v1 is done.

## 2. Goals and non-goals

**Goals**
- Money can never be created, lost or double-spent: every movement is a balanced ledger
  entry inside one database transaction.
- Secure by default: JWT auth, role-based access, validation on every input, audit
  trail for sensitive actions.
- A clean, responsive UI that someone can try in under 2 minutes using demo credentials.
- Production-style delivery: containers, tests, CI, a live demo.

**Non-goals (v1)**
- Real payment rails (UPI, NEFT), real brokerage or real market data. Funding and prices
  are simulated.
- Multi-currency. Everything is in **INR**.
- Mobile apps.

## 3. Users and roles

| Role | Who | Can do |
|---|---|---|
| `CUSTOMER` | A retail user | Manage own accounts, transfer, trade, apply for and repay loans |
| `ADMIN` | Bank operator | View users, freeze/unfreeze accounts, approve/reject loans, view audit logs and stats |

## 4. Architecture

- **Monorepo:** `backend-quant/` (Spring Boot), `frontend-quant/` (React), root `docker-compose.yml`.
- **Backend:** modular monolith. Domain packages talk to each other only through service
  interfaces, never through another module's repositories.
- **Database:** one PostgreSQL instance; schema managed by Flyway.
- **Dev wiring:** the frontend runs on `:5173` with a Vite proxy to the backend on
  `:8080` for `/api` and `/ws`, so the browser sees one origin.

```
React (Vite) ──/api, /ws──▶ Spring Boot ──JPA──▶ PostgreSQL
                              ├─ auth      ├─ ledger   ├─ trading
                              ├─ account   ├─ payment  ├─ loan
                              ├─ admin     └─ common
```

## 5. Functional requirements

### 5.1 Auth and profile (`auth`)
- **F-AUTH-1** Register with full name, email, phone, password. Email is unique. Password
  needs at least 8 characters, including an uppercase letter, a lowercase letter, a digit
  and a symbol. Stored with BCrypt.
- **F-AUTH-2** Login returns a short-lived **access token** (JWT, 15 min) in the body. A
  **refresh token** (7 days) is set as an `HttpOnly; Secure; SameSite=Strict` cookie named
  `qg_refresh`, scoped to path `/api/v1/auth`.
- **F-AUTH-3** The refresh endpoint rotates the refresh token. Reusing an old refresh token
  revokes the whole token family (forces re-login).
- **F-AUTH-4** Logout revokes the refresh token and clears the cookie.
- **F-AUTH-5** A new user automatically gets one `SAVINGS` account.
- **F-AUTH-6** Five failed logins within 15 minutes lock the login for 15 minutes.

### 5.2 Accounts (`account`)
- **F-ACC-1** A customer can hold up to 3 accounts, of type `SAVINGS` or `CHECKING`.
- **F-ACC-2** The account number has 12 digits, is unique and is generated server-side.
- **F-ACC-3** Account status is `ACTIVE`, `FROZEN` or `CLOSED`. A frozen account cannot send
  money, trade or repay loans, but it can still receive money.
- **F-ACC-4** **Demo deposit:** a customer can "add money" (max ₹1,00,000 per deposit).
  This is posted as a ledger entry from the system `BANK_CASH` account.
- **F-ACC-5** Transaction history is paginated and filterable by date range and type.
- **F-ACC-6** Statements can be downloaded as CSV (required) or PDF (stretch).

### 5.3 Ledger (`ledger`): the core
- **F-LED-1** Double-entry: each `journal_entry` has two or more `ledger_lines`, and the sum
  of debits must equal the sum of credits, or the entry is rejected.
- **F-LED-2** Every customer account and every system account (`BANK_CASH`,
  `LOAN_RECEIVABLE`, `BROKERAGE_CLEARING`, `INTEREST_INCOME`) is a ledger account.
- **F-LED-3** Posting is atomic: the balance check, the journal entry, its lines and the
  cached balance updates all happen in one DB transaction.
- **F-LED-4** Concurrency safety: the accounts involved are locked with
  `SELECT … FOR UPDATE`, **always in ascending account-id order** to avoid deadlocks.
- **F-LED-5** A customer balance can never go negative.
- **F-LED-6** Money is `NUMERIC(19,4)` in the DB, `BigDecimal` in Java, and a **string** in
  JSON (e.g. `"1250.50"`). Doubles are never used for money.
- **F-LED-7** Journal entries are immutable. A mistake is corrected with a reversing entry,
  never an `UPDATE` or `DELETE`.
- **F-LED-8** An admin-only reconciliation endpoint checks that every cached balance equals
  the sum of that account's ledger lines, and that the whole ledger sums to zero.

### 5.4 Payments (`payment`)
- **F-PAY-1** Transfer between own accounts, or to any account by its account number.
- **F-PAY-2** Every money-moving `POST` requires an `Idempotency-Key` header (a UUID). The
  same key with the same body returns the original result. The same key with a different
  body returns `409`.
- **F-PAY-3** Limits: minimum ₹1; maximum ₹2,00,000 per transfer; daily outgoing limit
  ₹5,00,000.
- **F-PAY-4** Saved beneficiaries: nickname plus account number, validated on save.

### 5.5 Trading (`trading`)
- **F-TRD-1** About 15 simulated instruments (e.g. `QGEN`, `INFY`, `TCS`, `RELI`,
  `HDFC`…) with a name, sector and current price.
- **F-TRD-2** A scheduled job updates prices every 3 seconds using a random walk (bounded
  drift and volatility, price never ≤ ₹1). Price ticks are stored for charts and pushed
  over WebSocket.
- **F-TRD-3** Market orders only in v1. A `BUY` debits cash and credits the holding; a
  `SELL` does the reverse. Both legs happen in **one transaction**, with the cash leg
  posted through `BROKERAGE_CLEARING`.
- **F-TRD-4** Whole-number quantities only, from 1 to 10,000. A sell cannot exceed the
  quantity held.
- **F-TRD-5** The portfolio shows quantity, average cost, current value, unrealized P&L and
  total realized P&L.
- **F-TRD-6** Price history for charts: ranges `1H`, `1D`, `1W`.

### 5.6 Loans (`loan`)
- **F-LOAN-1** Apply with amount (₹10,000 to ₹10,00,000), tenure (6 to 60 months), declared
  monthly income and purpose.
- **F-LOAN-2** Eligibility rule: the new EMI plus existing EMIs must be at most 50% of
  monthly income. The application must also not exceed 20× monthly income.
- **F-LOAN-3** Fixed annual interest rate of 12% in v1. EMI uses the standard amortization
  formula, rounded to 2 decimal places, with the last instalment absorbing any rounding
  difference.
- **F-LOAN-4** Status flow:
  `PENDING → APPROVED → ACTIVE → CLOSED`, or `PENDING → REJECTED`.
  Approval disburses the loan into the chosen account (ledger: `LOAN_RECEIVABLE` →
  customer), which makes it `ACTIVE`, and generates the EMI schedule.
- **F-LOAN-5** Repayment: the customer pays the next due EMI (or a custom amount, which is
  applied to the oldest dues first). Interest goes to `INTEREST_INCOME` and principal to
  `LOAN_RECEIVABLE`.
- **F-LOAN-6** A daily job auto-debits EMIs that fall due today. Each instalment can be
  `PENDING`, `PAID` or `OVERDUE`; if the balance is too low, the instalment becomes
  `OVERDUE` and a notification is sent.

### 5.7 Admin (`admin`)
- **F-ADM-1** User list with search and paging; user detail with accounts and loans.
- **F-ADM-2** Freeze/unfreeze an account. A reason is required and audited.
- **F-ADM-3** Loan queue: approve or reject with a reason.
- **F-ADM-4** Stats: total users, total deposits, active loans, outstanding principal,
  trades today.
- **F-ADM-5** Audit log viewer: who did what, when and from which IP.

### 5.8 Notifications (`common`)
- **F-NOT-1** In-app notifications for: money received, loan decision, EMI due/paid/overdue,
  account frozen. The UI shows an unread count, and items can be marked as read.

## 6. Non-functional requirements
Engineering and security rules that every contributor (human or Claude) must follow are in
[`CLAUDE.md`](CLAUDE.md), including the "think before you code" checklist for anything
touching auth, money or personal data.

- **Security:** stateless JWT signed with HS256 (secret taken from an env var); CORS
  allowlist; rate limiting on auth endpoints; no secrets in Git; and security-relevant
  actions written to `audit_logs`.
- **Validation:** Bean Validation on all request DTOs; customers can only touch their own
  resources (ownership checked in services, not just controllers).
- **Performance:** p95 under 300 ms for reads and under 500 ms for transfers on a laptop.
- **Testing:** service unit tests; integration tests with Testcontainers Postgres; and a
  **concurrency test** where 100 parallel transfers on one account end with the exact
  expected balance, no negative balance, and a ledger that sums to zero.
- **Docs:** OpenAPI served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`.
- **Ops:** Actuator health at `/actuator/health`; structured JSON logs; a correlation id
  (`X-Request-Id`) on every request.

## 7. API contract (v1)

Base path: `/api/v1`. JSON in and out. Auth header: `Authorization: Bearer <accessToken>`.
Money is always a string. Timestamps are ISO-8601 UTC strings. Ids are UUIDs.

### 7.1 Conventions

**Error body** (every non-2xx response):
```json
{ "timestamp": "2026-10-16T10:00:00Z", "status": 400, "code": "VALIDATION_FAILED",
  "message": "Request validation failed", "path": "/api/v1/transfers",
  "fieldErrors": [{ "field": "amount", "message": "must be at least 1.00" }],
  "requestId": "b3c1…" }
```
Error codes: `VALIDATION_FAILED`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`,
`INSUFFICIENT_FUNDS`, `ACCOUNT_FROZEN`, `LIMIT_EXCEEDED`, `IDEMPOTENCY_CONFLICT`,
`INSUFFICIENT_HOLDINGS`, `LOAN_NOT_ELIGIBLE`, `INVALID_STATE`, `RATE_LIMITED`,
`INTERNAL_ERROR`.

**Page envelope:**
```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 134, "totalPages": 7 }
```

**Idempotency:** `Idempotency-Key: <uuid>` is required on every endpoint marked 🔒💰.

### 7.2 Auth
| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/register` | `{fullName, email, phone, password}` | `201 UserDto` |
| POST | `/auth/login` | `{email, password}` | `200 {accessToken, expiresIn, user: UserDto}` + sets `qg_refresh` cookie |
| POST | `/auth/refresh` | (cookie) | `200 {accessToken, expiresIn, user}` + rotated cookie |
| POST | `/auth/logout` | (cookie) | `204` |
| GET | `/users/me` | | `200 UserDto` |

`UserDto = {id, fullName, email, phone, role: "CUSTOMER"|"ADMIN", createdAt}`

### 7.3 Accounts
| Method | Path | Body / Query | Response |
|---|---|---|---|
| GET | `/accounts` | | `200 AccountDto[]` |
| POST | `/accounts` | `{type: "SAVINGS"\|"CHECKING"}` | `201 AccountDto` |
| GET | `/accounts/{id}` | | `200 AccountDto` |
| POST 🔒💰 | `/accounts/{id}/deposit` | `{amount}` | `201 TransactionDto` |
| GET | `/accounts/{id}/transactions` | `?page&size&from&to&type` | `200 Page<TransactionDto>` |
| GET | `/accounts/{id}/statement` | `?from&to&format=csv` | `200 text/csv` file |

`AccountDto = {id, accountNumber, type, status, balance, currency: "INR", createdAt}`
`TransactionDto = {id, journalEntryId, accountId, direction: "DEBIT"|"CREDIT", amount, balanceAfter, type: "DEPOSIT"|"TRANSFER"|"TRADE"|"LOAN_DISBURSAL"|"LOAN_REPAYMENT"|"REVERSAL", description, counterparty, createdAt}`

### 7.4 Payments
| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST 🔒💰 | `/transfers` | `{fromAccountId, toAccountNumber, amount, note?}` | `201 TransferDto` |
| GET | `/transfers` | `?page&size` | `200 Page<TransferDto>` |
| GET | `/beneficiaries` | | `200 BeneficiaryDto[]` |
| POST | `/beneficiaries` | `{nickname, accountNumber}` | `201 BeneficiaryDto` |
| DELETE | `/beneficiaries/{id}` | | `204` |

`TransferDto = {id, fromAccountNumber, toAccountNumber, toName, amount, note, status: "COMPLETED"|"FAILED", createdAt}`
`BeneficiaryDto = {id, nickname, accountNumber, holderName, createdAt}`

### 7.5 Trading
| Method | Path | Body / Query | Response |
|---|---|---|---|
| GET | `/market/instruments` | | `200 InstrumentDto[]` |
| GET | `/market/instruments/{symbol}` | | `200 InstrumentDto` |
| GET | `/market/instruments/{symbol}/history` | `?range=1H\|1D\|1W` | `200 PricePointDto[]` |
| POST 🔒💰 | `/orders` | `{accountId, symbol, side: "BUY"\|"SELL", quantity}` | `201 OrderDto` |
| GET | `/orders` | `?page&size` | `200 Page<OrderDto>` |
| GET | `/portfolio` | | `200 PortfolioDto` |

`InstrumentDto = {symbol, name, sector, price, changePct24h, updatedAt}`
`PricePointDto = {t, price}`
`OrderDto = {id, symbol, side, quantity, price, total, status: "FILLED"|"REJECTED", createdAt}`
`PortfolioDto = {holdings: [{symbol, name, quantity, avgCost, price, marketValue, unrealizedPnl, unrealizedPnlPct}], totalMarketValue, totalCost, totalUnrealizedPnl, totalRealizedPnl}`

**WebSocket:** STOMP over the endpoint `/ws` (SockJS fallback). Subscribe to
`/topic/prices`, which pushes `InstrumentDto[]` on every tick. Send the access token in
the STOMP `CONNECT` header `Authorization`.

### 7.6 Loans
| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST | `/loans/quote` | `{amount, tenureMonths, monthlyIncome}` | `200 {emi, totalInterest, totalPayable, eligible, reason?}` |
| POST | `/loans` | `{amount, tenureMonths, monthlyIncome, purpose, disburseAccountId}` | `201 LoanDto` |
| GET | `/loans` | | `200 LoanDto[]` |
| GET | `/loans/{id}` | | `200 LoanDetailDto` |
| POST 🔒💰 | `/loans/{id}/repay` | `{accountId, amount?}` | `201 RepaymentDto` |

`LoanDto = {id, amount, tenureMonths, annualRate, emi, status, outstandingPrincipal, nextDueDate, createdAt}`
`LoanDetailDto = LoanDto + {purpose, decisionReason, schedule: [{installmentNo, dueDate, emi, principal, interest, status, paidAt}]}`
`RepaymentDto = {id, loanId, amount, principalPaid, interestPaid, createdAt}`

### 7.7 Admin (role `ADMIN`)
| Method | Path | Body / Query | Response |
|---|---|---|---|
| GET | `/admin/users` | `?q&page&size` | `200 Page<AdminUserDto>` |
| GET | `/admin/users/{id}` | | `200 AdminUserDetailDto` |
| PATCH | `/admin/accounts/{id}/status` | `{status: "ACTIVE"\|"FROZEN", reason}` | `200 AccountDto` |
| GET | `/admin/loans` | `?status=PENDING&page&size` | `200 Page<LoanDto + applicant>` |
| POST | `/admin/loans/{id}/approve` | `{reason?}` | `200 LoanDto` |
| POST | `/admin/loans/{id}/reject` | `{reason}` | `200 LoanDto` |
| GET | `/admin/stats` | | `200 StatsDto` |
| GET | `/admin/audit-logs` | `?page&size&actor&action` | `200 Page<AuditLogDto>` |
| GET | `/admin/ledger/reconcile` | | `200 {balanced, ledgerSum, mismatches: []}` |

### 7.8 Notifications
| Method | Path | Response |
|---|---|---|
| GET | `/notifications?unreadOnly=` | `200 NotificationDto[]` |
| PATCH | `/notifications/{id}/read` | `204` |
| GET | `/notifications/unread-count` | `200 {count}` |

## 8. Data model (core tables)
`users`, `refresh_tokens`, `accounts`, `ledger_accounts`, `journal_entries`,
`ledger_lines`, `idempotency_keys`, `transfers`, `beneficiaries`, `instruments`,
`price_ticks`, `orders`, `holdings`, `loans`, `loan_installments`, `loan_repayments`,
`notifications`, `audit_logs`. Exact columns are defined in the Flyway migrations, which
the backend owns.

## 9. Frontend pages
Landing · Login · Register · Dashboard (balances, recent activity, portfolio snapshot,
loan summary) · Accounts and account detail (history, statement download) · Transfer
(with beneficiaries) · Markets (live price list) · Instrument detail (chart and order
ticket) · Portfolio · Orders · Loans (EMI calculator, apply, list, detail with schedule,
repay) · Notifications · Profile · Admin (overview stats, users, loan queue, audit log,
reconcile).

Responsive down to 360 px, with light and dark themes. Every API error is surfaced as a
toast or an inline field error.

**Design and UX:** an Awwwards-calibre visual design with moderate, purposeful animation and
full `prefers-reduced-motion` support. The full spec (tokens, typography, motion, UX
rules, per-screen notes) is in [`frontend-quant/DESIGN.md`](frontend-quant/DESIGN.md).
Money actions always go fill in → review → confirm, and the UI never updates a balance
optimistically.

## 10. Milestones (v1)
| # | Milestone | Done when |
|---|---|---|
| M0 | Scaffolding | Both apps boot; Postgres runs in Docker; health check is green |
| M1 | Auth | Register, login, refresh and logout work end to end; routes are protected |
| M2 | Accounts + ledger + transfers | Concurrency test passes; dashboard and transfer UI work |
| M3 | Trading | Live prices in the UI; buy/sell settles atomically; portfolio P&L is correct |
| M4 | Loans | Apply → admin approve → disburse → repay; the EMI job runs |
| M5 | Admin + notifications + hardening | Admin pages work; audit log and rate limits are in place |
| M6 | Ship | Dockerfiles and compose; CI is green; deployed with demo data |

**Demo seed (dev profile only):** `admin@quantgen.dev / Admin@1234` and
`demo@quantgen.dev / Demo@1234`, with funded accounts, some holdings and one active loan.

## 11. Version 2 roadmap (after v1 ships)
- **AI layer:** banking copilot with tool use (agents create *intents*; the user confirms
  before the ledger posts anything); QuantGen MCP server; natural-language strategy →
  backtest → paper trade; fraud investigator agent; loan document underwriting agent.
- **Crypto and cryptography:** hash-chained, tamper-evident ledger with a daily Merkle
  root; passkeys (WebAuthn) for login and intent approval; crypto prices via free feeds;
  field-level encryption of KYC data.
- **Platform:** Kubernetes (kind locally, k3s on Oracle Cloud Always Free); Helm; Jenkins
  CI; ArgoCD GitOps; Terraform and Ansible; Prometheus, Grafana, Loki and Tempo; Redis;
  Redpanda with the outbox pattern; k6 load tests and Chaos Mesh fault injection.
