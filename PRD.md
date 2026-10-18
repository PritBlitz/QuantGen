# QuantGen — Product Requirements Document

**Version:** 2.0-draft (v1 core shipped, v2 planned)  ·  **Owner:** Pritish Biswas  ·  **Last updated:** 2026-10-18

> This file is the single source of truth for scope **and** the API contract between
> `backend-quant/` and `frontend-quant/`. If the contract has to change, update §7 here
> first, then implement. Never let code and this file disagree.

---

## 1. Overview

QuantGen is a unified digital banking and investment platform. One login gives a customer
bank accounts, transfers, stock trading and loans, all settled through a single
double-entry ledger with ACID guarantees.

**Version 1** (§1–§10) is the core app: banking, trading, loans and admin, built
correctly and deployed with Docker. It is feature-complete (audit in §11.0).
**Version 2** (§11–§13) turns it into a modern money app: Payments 2.0, savings and
insights, a multi-agent AI copilot that can only *propose* actions, **Autopilot** (plain-language
money rules run by a deterministic engine) and **Proof of Balance** (customers verify their
own balance cryptographically). v2 status: **draft, awaiting owner approval.**
Every trade-off behind these choices is logged in [`DECISIONS.md`](DECISIONS.md); the
plain-language guide to working on the project is [`FLOW.md`](FLOW.md).

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
`LoanDetailDto = LoanDto + {purpose, decisionReason, schedule: [{installmentNo, dueDate, emi, principal, interest, amountDue, status, paidAt}]}`
(`amountDue` was added at M4 because repayments can be partial. It is what is still owed on that
instalment: equal to `emi` when untouched, less after a partial payment, `"0.00"` when `PAID`.
A repay with no `amount` charges exactly the oldest unpaid instalment's `amountDue`.)
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
| GET | `/admin/ledger/reconcile` | | `200 ReconcileDto` |

`AdminUserDto = {id, fullName, email, phone, role, accountCount, createdAt}` (`q` matches name, email or phone, case-insensitive)
`AdminUserDetailDto = AdminUserDto + {accounts: AccountDto[], loans: LoanDto[]}`
`AdminLoanDto = LoanDto + {applicant: {userId, fullName, email}, monthlyIncome, purpose, decisionReason}` (the "LoanDto + applicant" row above; `status` filter is optional, newest first)
`StatsDto = {totalUsers, totalDeposits, activeLoans, outstandingPrincipal, tradesToday}` (`totalDeposits` and `outstandingPrincipal` are money strings; "today" is the IST calendar day)
`AuditLogDto = {id, actorId, actorEmail, action, targetType, targetId, details, ip, requestId, createdAt}` (`actor` filters by email substring; `action` is exact; newest first; `details` never contains secrets or full account numbers)
`ReconcileDto = {balanced, ledgerSum, mismatches: [{ledgerAccountId, code, cachedBalance, computedBalance}]}` (`code` is the system account code such as `BANK_CASH`, null for customer accounts; `computedBalance` is the sum of that account's ledger lines)

Freeze/unfreeze and loan decisions write an audit log entry and notify the customer.
Approve and reject return `409 INVALID_STATE` unless the loan is `PENDING`.

### 7.8 Notifications
| Method | Path | Response |
|---|---|---|
| GET | `/notifications?unreadOnly=` | `200 NotificationDto[]` |
| PATCH | `/notifications/{id}/read` | `204` |
| GET | `/notifications/unread-count` | `200 {count}` |
| POST | `/notifications/read-all` | `204` |

`NotificationDto = {id, type, title, body, read, createdAt}`, where `type` is one of
`MONEY_RECEIVED`, `LOAN_APPROVED`, `LOAN_REJECTED`, `EMI_DUE`, `EMI_PAID`, `EMI_OVERDUE`,
`ACCOUNT_FROZEN`, `ACCOUNT_UNFROZEN`. The list is newest first and capped at the 50 most
recent. Another user's notification returns `404`. `read-all` was added in v1.0 for the
"mark all as read" action.

### 7.9 Gateway top-ups (Razorpay test mode) — PROPOSED, scheduled for V2-M2, not implemented
> **Status:** proposal written 2026-10-16, folded into the v2 plan on 2026-10-18 (§11.7,
> D-016). Nothing below exists yet, and no session builds against it until the owner
> approves V2-M2 and this banner is removed. Test mode only: no real money
> moves, so the §2 non-goal ("real payment rails") still holds. The demo deposit (§7.3) stays.

**Why Razorpay, not Stripe:** Stripe is invite-only for new Indian accounts (since May 2024).
Razorpay gives test keys (`rzp_test_…`) before KYC, works in INR and supports UPI, cards and
netbanking in its test checkout.

**Flow:** (1) the client asks the backend to create a top-up; the backend creates a Razorpay
order for that exact amount and returns its id. (2) The client opens Razorpay Checkout with
that order id. (3) On success the client sends the payment id and signature to `/verify`; the
backend checks the signature and that Razorpay reports the payment as captured for the same
amount, then credits the account through the ledger. (4) Razorpay's signed webhook does the
same credit if the user closes the tab before step 3. Whichever arrives first credits; the
other is a no-op. The account is never credited on the client's word alone.

| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST 🔒 | `/accounts/{id}/topups` | `{amount}` | `201 TopupDto` (status `CREATED`) |
| POST | `/topups/{id}/verify` | `{gatewayPaymentId, gatewaySignature}` | `200 TopupDto` (status `CREDITED`) |
| GET | `/topups/{id}` | | `200 TopupDto` |
| GET | `/accounts/{id}/topups` | `?page&size` | `200 Page<TopupDto>` |
| POST | `/webhooks/razorpay` | raw Razorpay event (no JWT) | `200` (`400` on a bad signature) |

`TopupDto = {id, accountId, amount, currency: "INR", status: "CREATED"|"CREDITED"|"FAILED"|"EXPIRED", gateway: "RAZORPAY", gatewayOrderId, gatewayKeyId, transactionId, failureReason, createdAt, updatedAt}`
(`gatewayKeyId` is the public `rzp_test_…` key Checkout needs; the key secret never leaves the
server. `transactionId` is the ledger transaction once `CREDITED`, otherwise null.)

Rules:
- `POST /accounts/{id}/topups` takes an `Idempotency-Key` (a retry returns the same order, so
  a double-click never opens two checkouts). It doesn't move money; the credit happens at
  verify or webhook time. Limits match the demo deposit: ₹1.00 to ₹1,00,000.00. The account
  must be the caller's own (else `404`) and `ACTIVE` (else `ACCOUNT_FROZEN` / `INVALID_STATE`).
- `verify` checks `HMAC-SHA256(gatewayOrderId + "|" + gatewayPaymentId, keySecret)` and then
  fetches the payment from Razorpay: it must be `captured`, in INR, for exactly the top-up's
  amount. A bad signature returns `400 PAYMENT_VERIFICATION_FAILED`; an already `CREDITED`
  top-up returns `200` with the same body. Someone else's top-up returns `404`.
- The webhook verifies `X-Razorpay-Signature` (HMAC-SHA256 of the raw body with the webhook
  secret) and drops repeats by `x-razorpay-event-id`. `payment.captured` credits;
  `payment.failed` marks the top-up `FAILED`. Unknown events are acknowledged with `200`.
- Crediting posts one ledger entry, system account `GATEWAY_CLEARING` → customer account
  (new system account, added to F-LED-2), and shows up in history as a `DEPOSIT` transaction
  with counterparty `Razorpay`. The `TransactionDto.type` enum doesn't change.
- If the account was frozen after the order was created, a captured payment is still credited
  (frozen accounts can receive, F-ACC-3). An unpaid `CREATED` top-up becomes `EXPIRED` after
  30 minutes.
- Every credit and every failed verification is audited (`TOPUP_CREDITED`,
  `TOPUP_VERIFICATION_FAILED`). Key secrets, webhook secrets and signatures are never logged.
- New error codes: `PAYMENT_VERIFICATION_FAILED` (400) and `GATEWAY_UNAVAILABLE` (503, when
  the Razorpay keys aren't configured or Razorpay can't be reached while creating the order).
- Config (env only): `QG_RAZORPAY_KEY_ID`, `QG_RAZORPAY_KEY_SECRET`,
  `QG_RAZORPAY_WEBHOOK_SECRET`. With them unset the feature is off and the endpoints return
  `503 GATEWAY_UNAVAILABLE`.
- Frontend impact: load `https://checkout.razorpay.com/v1/checkout.js` (CSP must allow it and
  Razorpay's frames), add an "Add money via Razorpay" option next to the demo deposit, then
  poll `GET /topups/{id}` until `CREDITED` or `FAILED`, with no optimistic balance.
- Local webhooks need a public tunnel (Razorpay rejects `localhost`, `ngrok.io` and
  `loca.lt`; Cloudflare Tunnel or zrok work). Without one, steps 1–3 still work locally.

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


---

# Version 2 — draft

> **Status:** draft written 2026-10-18 for the owner to approve. No session builds a v2
> feature until its milestone (§11.7) is approved and the matching contract section in §12
> loses its `DRAFT` tag. The v1 contract (§7) stays valid: every v2 change to it is
> **additive** (new fields, new endpoints, new enum values) unless a row below says otherwise.

## 11. Version 2 scope

### 11.0 v1 audit (2026-10-18)
Checked against the code and by running both test suites on 2026-10-18.

| Area | Result |
|---|---|
| F-AUTH-1…6, F-ACC-1…5, F-LED-1…8, F-PAY-1…4, F-TRD-1…6, F-LOAN-1…6, F-ADM-1…5, F-NOT-1 | ✅ Implemented; every §7 endpoint exists |
| F-ACC-6 statements | ✅ CSV · ⏳ PDF (stretch, never in the contract) |
| Ledger DB guards (immutability triggers, deferred balance check) | ✅ in `V1__init.sql` |
| Backend tests | ✅ `./mvnw verify`: 103 tests (24 unit + 79 integration incl. 100-way concurrency + idempotency), 0 failures |
| Frontend tests | ⚠️ typecheck + lint clean; Vitest 85/86. `transfer.test.tsx:62` fails because the holder name now appears more than once on the success screen (an ambiguous test query, not a product bug). Frontend session to fix. |
| Live e2e (`e2e/live.spec.ts`) against the compose stack | ✅ per `frontend-quant/HANDOFF.md` (2026-10-16) |
| M6 "CI is green" | ⏳ workflows exist (`ci.yml`, `deploy.yml`) but have never run: nothing has been pushed |
| M6 "deployed with demo data" | ⏳ runs locally via `docker-compose.yml`; no public deployment yet |
| Metrics | ⏳ `ARCHITECTURE.md` §10 promised `/actuator/prometheus` + custom counters; actuator only exposes `health,info` |

**Verdict:** v1 is feature-complete. The ⏳ items are delivery work and move into **V2-M0**
(D-015), which must finish before any other v2 milestone starts.

### 11.1 Why v2
v1 is a correct but plain bank: you can hold money, send it, trade and borrow. Today's money
apps (Revolut, Monzo, CRED, Jupiter, Zerodha, Google Pay) win on three things v1 lacks:
1. **Payments that fit real life:** pay a handle or a QR, request money, split a bill,
   schedule rent, pay bills, top up with a real checkout.
2. **Money that manages itself:** goals, round-ups, salary-day automation, budgets, a
   forecast of next month.
3. **An assistant that understands your money** and can do things for you, safely.

QuantGen v2 adds those, plus two things no mainstream app offers, which become the
project's identity:
- **Autopilot** (§11.4.6): write money rules in plain English; an AI compiles them, they're
  replayed against your real history before you approve, and a deterministic engine runs
  them with no AI in the loop.
- **Proof of Balance** (§11.4.9): every night the bank publishes a cryptographic root of all
  balances; you can verify **in your own browser** that your balance is included and the
  ledger hasn't been rewritten.

### 11.2 Goals and non-goals (v2)
**Goals**
- Every v1 money invariant still holds. New features move money only through
  `LedgerService.post`, with idempotency and the same tests.
- **AI can explain and propose, never execute.** No model output moves money without a
  human approval in the UI (D-017).
- The app still works fully with the AI layer switched off (D-039).
- Each v2 milestone is demoable on its own.

**Non-goals (v2)**
- Real money. Razorpay runs in **test mode** only; no payouts to external banks (D-036).
- Real market data for equities (D-035). Real KYC verification against government systems.
- Multi-currency, native mobile apps (the web app becomes an installable PWA instead).
- Autonomous agents with spending power. Fully automated loan or fraud decisions.

### 11.3 Roles and principals
| Principal | New in v2 | Notes |
|---|---|---|
| `CUSTOMER`, `ADMIN` | — | Unchanged |
| **Agent (delegated)** | ✅ | Not a user. A short-lived token minted by the core for one user's conversation, scopes `agent:read agent:propose` (D-022). Can read that user's data and create intents; can never approve, execute or touch admin endpoints. |
| **Back-office agent** | ✅ | Fraud Investigator / Underwriting Analyst. Service credential with `agent:backoffice` scope: reads case data, writes memos. Never decides. |
| **MCP client** | ✅ (V2-M10) | A personal access token the customer creates, `read` or `read+propose`, revocable. |

### 11.4 Functional requirements (v2)
IDs continue the v1 style. **Priority:** P0 = the milestone isn't done without it,
P1 = should ship, P2 = stretch.

#### 11.4.1 Foundations (`common`, `ledger`) — V2-M1
- **F-EVT-1 (P0)** Every ledger post also writes an `outbox_events` row in the same
  transaction (`MONEY_MOVED` with entry id, accounts, amounts, type). Consumers are
  idempotent by event id (D-028).
- **F-HOLD-1 (P0)** Holds earmark money without moving it. `availableBalance = balance −
  active holds`. Debits check available balance. A hold is `ACTIVE → CAPTURED | RELEASED |
  EXPIRED` (D-027).
- **F-RT-1 (P0)** Real-time user notifications over STOMP `/user/queue/notifications`
  (the unread badge updates without polling).
- **F-FLAG-1 (P1)** Feature flags read from config + an admin toggle (`ai`, `autopilot`,
  `razorpay`, `cards`, `mcp`), so a broken feature can be switched off without a deploy.

#### 11.4.2 Security 2.0 (`auth`) — V2-M1
- **F-SEC-1 (P0)** **Passkeys** (WebAuthn): register up to 5, log in without a password, name and remove them.
- **F-SEC-2 (P0)** **Step-up auth** for risky actions: a transfer above ₹50,000; the first
  transfer to a payee added less than 24 h ago; approving an agent intent marked
  `requiresStepUp`; activating an Autopilot rule; revealing a card number; changing security
  settings. The server answers `401 STEP_UP_REQUIRED`; the client completes a passkey or PIN
  check and retries with `X-Step-Up-Token` (valid 5 min, bound to user + action) (D-029).
- **F-SEC-3 (P0)** **Transaction PIN** (6 digits, BCrypt, 5 wrong tries lock it for 30 min) as the step-up fallback.
- **F-SEC-4 (P1)** **Sessions & devices:** list active sessions (one per refresh-token family:
  device, browser, approximate location from IP, last used), revoke one or all others.
  Login from a new device sends a notification (and email when configured).
- **F-SEC-5 (P1)** **New-payee cooling:** at most ₹25,000 in total to a payee during the first
  24 h after it is saved.
- **F-SEC-6 (P1)** **KYC profile:** PAN (format-validated, stored encrypted with a blind
  index for uniqueness), date of birth, address. Required before FDs, loans above ₹2,00,000
  or Autopilot `BUY` actions. Existing users are grandfathered as `VERIFIED`. No Aadhaar
  numbers are ever accepted (D-032).
- **F-SEC-7 (P1)** Customers can lower (never raise) their own per-transfer and daily limits.

#### 11.4.3 Payments 2.0 (`payment`) — V2-M2
- **F-PAY-5 (P0)** **QuantGen handle** (`name@qg`, 3–30 chars `[a-z0-9.]`, unique, changeable
  once per 30 days) mapped to a primary account. Pay by handle, account number or QR.
- **F-PAY-6 (P0)** **Confirmation of payee:** resolving a handle or account number returns the
  holder's masked name ("Aarav S.") before paying. Resolve is rate-limited (30/min per user)
  to stop enumeration.
- **F-PAY-7 (P0)** **QR:** every user has a QR encoding an https link
  `https://<host>/transfer?to=<handle>&am=<amount>&tn=<note>` (amount and note optional), so any
  phone camera opens the pre-filled send screen; scanners also accept the older
  `quantgen://pay?h=…&am=…&tn=…` form (changed 2026-10-18: a custom scheme can't open a web app). The web app can show and scan it (camera via `BarcodeDetector`, upload fallback).
- **F-PAY-8 (P0)** **Request money:** ask a handle for an amount with a note; the payer can
  pay (a normal transfer linked to the request), decline or block the requester. Requests
  expire after 7 days. At most 20 open outgoing requests per user.
- **F-PAY-9 (P0)** **Scheduled and recurring transfers:** one-off on a future date, or weekly /
  monthly (day 1–28) until an end date or count. Run daily at 07:00 IST; each run is idempotent
  (`instructionId + runDate`). Insufficient funds → retry once next day, then `MISSED` +
  notification. Pause, resume, cancel.
- **F-PAY-10 (P0)** **Gateway top-ups** via Razorpay test mode: full spec §7.9 (D-016).
- **F-PAY-11 (P1)** **Bill payments** to ~12 simulated billers (electricity, mobile, broadband,
  DTH, gas, water, credit card, insurance…). "Fetch bill" returns a deterministic simulated
  amount for a consumer number; paying posts to a new `BILLER_CLEARING` system account and
  produces a receipt. Saved billers.
- **F-PAY-12 (P1)** **Split groups:** a group of handles; add an expense (equal, exact or
  percent split, computed server-side, leftover paisa assigned deterministically to the payer);
  running balances; "settle up" creates payment requests. No money moves until someone pays.
- **F-PAY-13 (P2)** Downloadable payment receipts (PDF) for transfers and bill payments.

#### 11.4.4 Savings and insights (`account`, new `insights`) — V2-M3
- **F-SAV-1 (P0)** **Goals:** up to 10 `GOAL` accounts with name, emoji, target amount and
  optional date; progress shown; money can only move between a goal and the owner's own
  accounts (D-037).
- **F-SAV-2 (P0)** **Round-ups:** optional per account; each outgoing transfer, bill or card
  spend is rounded up to the next ₹10/₹50/₹100 and the difference moved to a chosen goal
  (one posting per day, summed, to avoid ledger noise).
- **F-SAV-3 (P1)** **Fixed deposits:** book from an account (₹1,000 to ₹10,00,000; 7 days to
  5 years; rate from a published slab table). Interest is computed daily on the server and
  paid at maturity from a new `INTEREST_EXPENSE` system account. Premature closure pays the
  rate for the period actually held minus 1%. On maturity: pay out or auto-renew.
- **F-SAV-4 (P1)** **Savings interest:** 3% p.a. on the daily closing balance of `SAVINGS`
  accounts, credited monthly from `INTEREST_EXPENSE`.
- **F-INS-1 (P0)** **Categories:** every transaction gets a category (deterministic from its
  type, biller and counterparty; the user can recategorize and the choice is remembered for that
  counterparty). An optional low-cost model classifies free-text notes when rules can't (D-020).
- **F-INS-2 (P0)** **Monthly budgets** per category with alerts at 80% and 100%.
- **F-INS-3 (P1)** **Cash-flow forecast:** projected balance per day for the next 30 days from
  known future items (EMIs, scheduled transfers, Autopilot schedules, detected salary). Warns
  when the balance is projected to drop below ₹1,000 or an EMI would fail.
- **F-INS-4 (P1)** **Net worth:** cash + goals + FDs + portfolio − outstanding loans, with a monthly trend.

#### 11.4.5 AI copilot and multi-agent layer (new `agent-quant` service) — V2-M4
- **F-AGT-1 (P0)** **Copilot chat** (side panel + `⌘K`): ask about your money in plain
  language ("how much did I spend on food last month?", "why did my balance drop on
  Tuesday?", "can I afford a ₹3L loan?"). Answers stream token by token and cite the
  transactions or figures they're based on.
- **F-AGT-2 (P0)** **Intents:** when the user asks the copilot to *do* something (pay, schedule,
  buy, move to a goal, apply for a loan, create a rule), an agent creates an **intent**. The
  UI shows an approval card **rendered by the server from the validated payload** (D-023),
  and nothing happens until the user approves (with step-up when required). Intents expire
  after 15 minutes. The payload is re-validated at execution time (limits, balance, frozen status).
- **F-AGT-3 (P0)** **Multi-agent topology** (D-021): a *Concierge* orchestrator delegates to
  specialists, each with a narrow tool allowlist:

  | Agent | Can read | Can propose |
  |---|---|---|
  | Ledger Analyst | accounts, transactions, categories, budgets, forecast | — |
  | Payments | beneficiaries, handles (resolve), billers, schedules | transfer, bill payment, schedule, payment request |
  | Markets | instruments, history, portfolio, orders | order; runs backtests |
  | Planner | goals, FDs, budgets, Autopilot rules | goal transfer, FD booking, Autopilot rule |
  | Loan Advisor | loans, quote, eligibility | loan application |

- **F-AGT-4 (P0)** **Guardrails:** agent tokens are rejected by every non-agent endpoint;
  user-controlled text (notes, nicknames, document content) is passed to models as quoted data,
  never as instructions; full account numbers are masked before anything reaches a model;
  each agent sees only its tools; a per-turn tool-call cap (10) and a per-user budget (D-039).
- **F-AGT-5 (P0)** **Traceability:** every agent run stores its steps (agent, tool, arguments,
  result size, latency, tokens, cost), redacted. Customers see "why did the assistant say this?";
  admins see full traces and spend.
- **F-AGT-6 (P0)** **Kill switch:** the `ai` feature flag turns the whole layer off; the UI hides
  copilot entry points and v1 flows keep working.
- **F-AGT-7 (P1)** **Conversation memory:** conversations are kept for 30 days, then deleted.
  The user can delete any conversation at once.
- **F-AGT-8 (P1)** **Evals in CI:** a golden set (≥ 50 tasks with expected tool calls / answers)
  and a red-team set (≥ 30 prompt-injection and social-engineering cases). Gate: ≥ 90% golden
  pass, 100% red-team "no unsafe intent created". Evals cost money, so they run on demand
  and on release branches, not on every push.

#### 11.4.6 Autopilot — signature feature (`autopilot` module + Planner agent) — V2-M5
- **F-AUTO-1 (P0)** A **rule** = one trigger, optional conditions, 1–5 ordered actions, and
  caps. Stored as typed JSON (DSL v1, §12.7), versioned; every edit makes a new version.
  - Triggers: `MONEY_RECEIVED` (account, min amount, counterparty contains, type),
    `SCHEDULE` (monthly day 1–28 / weekly weekday, run at 08:00 IST), `BALANCE_ABOVE`
    (account, threshold; checked daily), `PRICE_CROSSES` (symbol, above/below, price),
    `BUDGET_THRESHOLD` (category, percent).
  - Actions: `TRANSFER` (to own account, goal or a beneficiary saved > 24 h; amount fixed,
    percent of trigger amount, or "everything above X"), `BUY` (symbol, rupee amount → whole
    shares, rounded down), `PAY_BILL` (saved biller), `ROUND_UP_SWEEP`, `NOTIFY`.
  - Caps (required): max per run, max per calendar month.
- **F-AUTO-2 (P0)** **Authoring in plain language:** the user types a rule; the Planner
  agent compiles it into DSL; the core validates it and returns a **plain-language rendering
  generated from the DSL by the server** (not the model), so the user reads what will
  actually run. Rules can also be built with a form (no AI needed).
- **F-AUTO-3 (P0)** **Replay before you approve:** the core dry-runs the rule against the
  user's last 90 days of real ledger events and shows every time it would have fired, what it
  would have moved, and runs that would have failed (e.g. insufficient funds).
- **F-AUTO-4 (P0)** **Activation needs step-up** (F-SEC-2). Rules can be paused, resumed, edited (new version) and deleted.
- **F-AUTO-5 (P0)** **Deterministic execution:** a core engine consumes outbox events and the
  scheduler; no model is involved. Each run is idempotent on `(ruleId, version, triggerEventId)`.
  Actions run in order, each through the normal service (limits, holds, frozen checks apply).
  A failed action stops the run, records why, and notifies. Three consecutive failed runs pause the rule.
- **F-AUTO-6 (P0)** **Safety limits:** max 10 active rules per user; an Autopilot daily
  outgoing cap of ₹1,00,000 per user; rules can't trigger other rules (an Autopilot-made
  movement never fires `MONEY_RECEIVED` rules), so no loops.
- **F-AUTO-7 (P1)** **Run history:** each run shows trigger, rule version, actions, ledger
  entries and outcome. "Explain this run" opens the copilot with the run as context.
- **F-AUTO-8 (P1)** Templates: "Salary splitter", "Round-up saver", "SIP every month",
  "Keep ₹X in checking, sweep the rest", "Pay rent on the 1st".

#### 11.4.7 Risk and back-office agents (`risk` module + `agent-quant`) — V2-M6
- **F-RISK-1 (P0)** **Fraud scoring** on every outgoing transfer, bill payment and card
  authorization, synchronously, in the core: deterministic rules (velocity, new payee + large
  amount, first transfer after a password or device change, 00:00–05:00 IST + large amount,
  many round amounts in a short window). Score ≥ threshold → the transfer becomes
  `PENDING_REVIEW` with a hold; the customer is told it's under review (D-030).
- **F-RISK-2 (P0)** **Fraud case queue** for admins: release (posts the transfer) or reject
  (releases the hold, notifies), with a reason; both audited.
- **F-RISK-3 (P0)** **Fraud Investigator agent** writes a case memo for every case: what
  triggered it, the customer's normal pattern, similar past cases, a suggested action and
  confidence. Advisory only.
- **F-RISK-4 (P1)** **Loan documents:** a customer can attach up to 3 PDFs/images (≤ 5 MB each,
  type-sniffed, stored outside the DB) to a loan application, with explicit consent to AI processing.
- **F-RISK-5 (P1)** **Underwriting Analyst agent** extracts income and obligations from the
  documents with page citations, compares them with the declared income, and writes a memo with
  a recommendation and flags. The admin still approves or rejects (D-031).
- **F-RISK-6 (P2)** **Reconcile explainer:** when `/admin/ledger/reconcile` finds a mismatch, an
  agent drafts an explanation from the affected entries.

#### 11.4.8 Trading 2.0 (`trading`) — V2-M7
- **F-TRD-7 (P0)** **Limit orders** (`BUY`/`SELL` at a price or better) and **stop-loss**
  (`SELL` market when price ≤ trigger). Validity `DAY` (24 h) or `GTC` (90 days). An open buy
  places a hold for `qty × limit`; an open sell reserves shares. Orders are checked on every
  price tick and filled at the limit price (stop: at the tick price), each in its own
  transaction. Open orders can be cancelled.
- **F-TRD-8 (P0)** **Watchlists** (up to 5, 50 symbols each) and **price alerts** (one-shot, notify when crossed).
- **F-TRD-9 (P1)** **SIP:** monthly fixed-rupee purchases, implemented as an Autopilot
  `SCHEDULE → BUY` rule from a template.
- **F-TRD-10 (P1)** **Strategy backtester:** strategies in a small DSL (SMA crossover,
  RSI thresholds, buy-the-dip, rebalance) run over stored price history; returns equity
  curve, trades, return, max drawdown, win rate. The Markets agent can turn plain language into
  a strategy. The UI states the data is simulated (D-035).
- **F-TRD-11 (P1)** **Tax P&L report** per financial year (Apr–Mar): realized gains split
  into short and long term by holding period, downloadable as CSV.
- **F-TRD-12 (P2)** Live crypto prices from a free public feed, read-only, behind a flag.

#### 11.4.9 Proof of Balance — signature feature (`ledger`) — V2-M8
- **F-POB-1 (P0)** **Sealed ledger:** every journal entry is appended to a SHA-256 hash chain
  (`hash = SHA256(prevHash ‖ canonical(entry + lines))`) by an async sealer, normally within
  5 seconds of commit (D-026).
- **F-POB-2 (P0)** **Daily snapshot** at 00:00 IST: a Merkle **sum** tree over all customer
  ledger accounts (leaf = `SHA256(accountId ‖ balance ‖ salt)`, salt derived per account per
  day from a server secret), with the root, the total liabilities and the chain head published
  at a public, unauthenticated endpoint.
- **F-POB-3 (P0)** **Verify my balance:** a customer fetches the inclusion proof for their
  account and the browser recomputes the path to the published root with WebCrypto, showing ✅
  or ❌. The page explains in plain language what it proves and what it doesn't.
- **F-POB-4 (P0)** **Admin chain verification:** recompute the chain from genesis (or from a
  checkpoint) and report the first broken link, if any. Also run nightly; a break raises an
  audit event and an admin alert.
- **F-POB-5 (P1)** **Solvency view:** published total liabilities vs total system assets (simulated reserves).
- **F-POB-6 (P2)** External anchoring: commit each daily root to a public Git repository.

#### 11.4.10 Virtual cards and merchant sandbox (`card`) — V2-M9
- **F-CARD-1 (P1)** One virtual debit card per account: 16-digit Luhn-valid number in a
  non-issuer test range, stored encrypted, shown as `•••• 1234`; full number and a
  time-based CVV are revealed only after step-up and hidden again after 30 s.
- **F-CARD-2 (P1)** Controls: freeze/unfreeze, online on/off, per-transaction limit, blocked merchant categories.
- **F-CARD-3 (P1)** **Merchant sandbox:** a demo checkout page ("QuantGen Store") creates
  authorizations → holds; captures after a delay; supports refunds (reversal entries) and
  declines with reasons (insufficient funds, frozen, limit, blocked category, fraud).
- **F-CARD-4 (P2)** Disputes for card transactions: the customer raises one, the admin
  resolves it, a reversal entry is posted.

#### 11.4.11 MCP server and developer access — V2-M10
- **F-MCP-1 (P1)** A QuantGen **MCP server** (in `agent-quant`) exposes the same read and
  propose tools to MCP clients such as Claude Desktop. Proposed intents still need approval in
  the QuantGen UI.
- **F-MCP-2 (P1)** **Personal access tokens:** named, scoped (`read` or `read+propose`),
  expiring (≤ 90 days), shown once, stored hashed, revocable, audited on every use.

#### 11.4.12 Experience upgrades (frontend) — across milestones
- **F-UX-1 (P0)** Installable **PWA** (manifest, offline shell; no API data containing money is cached).
- **F-UX-2 (P0)** Command palette `⌘K`: navigate, run actions, ask the copilot.
- **F-UX-3 (P1)** Onboarding checklist (passkey, handle, first goal, first rule).
- **F-UX-4 (P1)** Accessibility pass to WCAG 2.2 AA; every new screen keyboard-operable.
- **F-UX-5 (P2)** Hindi UI strings (i18n framework from the start, strings externalized).

#### 11.4.13 Platform — V2-M11 (can run in parallel from V2-M1, owned by the frontend/infra session)
- **F-OPS-1 (P0)** Metrics (`/actuator/prometheus`, internal only), OpenTelemetry traces across
  SPA → core → `agent-quant` → Claude API, JSON logs. Grafana dashboards: API, ledger post
  latency, transfers/min, Autopilot runs, LLM tokens and cost, fraud cases.
- **F-OPS-2 (P1)** Kubernetes (kind locally, k3s on Oracle Cloud Always Free), Helm charts,
  ArgoCD GitOps, Terraform for the VM/network, Ansible for bootstrap. GitHub Actions for CI (D-033).
- **F-OPS-3 (P1)** Redis for shared rate limits and caches (D-034); Redpanda for the outbox
  relay to `agent-quant` (D-028); STOMP relay so the API can run 2+ replicas (D-011).
- **F-OPS-4 (P1)** k6 load tests (transfers, orders, copilot) with pass thresholds in CI;
  Chaos Mesh experiments (kill the API mid-transfer, DB latency, Claude API outage) with the
  expected outcome written down before each run.

#### 11.4.14 Analytics (new `analytics` module) — V2-MA (starts right after V2-M0, runs in parallel)
Two audiences, one rule: **analytics never moves money and never leaks a person.**
Definitions used everywhere (so admin and customer numbers agree):
- **Income** = `DEPOSIT`, `TOPUP`, `INTEREST` credits and incoming `TRANSFER`s from *another* user.
- **Spending** = outgoing `TRANSFER`s to another user, `BILL_PAYMENT`, `CARD`, `LOAN_REPAYMENT`.
- **Excluded from both:** moves between a user's own accounts, goals and FDs; trades (reported
  as *invested* / *divested*); loan disbursals (reported as *borrowed*).
- **Active user** = signed in or moved money in the period. Days are IST calendar days;
  `from`/`to` are inclusive `YYYY-MM-DD`.

**Business analytics (role `ADMIN`, page `/admin/analytics`)**
- **F-ANL-1 (P0) Overview KPIs** for a period (Today, 7D, 30D, 90D, 12M, custom), each
  compared with the previous period of equal length: total users, new users, active users,
  funded users %, customer deposits (total liabilities), net inflow, transfer volume and count,
  trade volume and count, outstanding loan principal, interest income, overdue rate, failed
  money-request rate.
- **F-ANL-2 (P0) Time series** for every KPI at `DAY` / `WEEK` / `MONTH` granularity, with an
  optional previous-period overlay.
- **F-ANL-3 (P0) Breakdowns:** transaction-type mix, account type and status, top instruments
  and sectors traded, buy vs sell, loan status and purpose, funding source (demo deposit vs
  Razorpay), top error codes on money endpoints, spending categories (v2).
- **F-ANL-4 (P0) Acquisition funnel** over users who registered in the period:
  registered → funded → first transfer → first trade → applied for a loan.
- **F-ANL-5 (P1) Retention cohorts** by signup month (active = moved money), up to 12 months.
- **F-ANL-6 (P0) Loan book:** outstanding principal, disbursed, repaid, interest earned,
  approval rate, average ticket size, overdue amount and ageing buckets (1–30, 31–60, 61–90, 90+ days).
- **F-ANL-7 (P1) Product adoption (v2 features):** % of active users with a handle, passkey, goal,
  Autopilot rule, card; copilot users, turns and AI cost per active user; Autopilot runs and
  success rate; fraud cases and the released (false-positive) share. Each metric appears when
  its milestone ships; until then the API returns it with `available: false`.
- **F-ANL-8 (P1) Platform health:** reconcile status, seal lag, outbox lag, money-endpoint
  error rate (from the metrics added in V2-M0).
- **F-ANL-9 (P1) CSV export** of any table or series. Every export is audited (`ANALYTICS_EXPORTED`).
- **F-ANL-10 (P0) Privacy:** responses hold aggregates only, with no names, emails, phones or
  account numbers. Any breakdown or cohort cell covering fewer than 5 users is returned as
  `suppressed: true` with a null value. Looking at an individual stays in the existing admin
  user pages, which are already audited.

**Personal analytics (role `CUSTOMER`, page `/insights`)**
- **F-ANL-11 (P0) Money summary** for a period: income, spending, net, savings rate (net ÷
  income), each vs the previous period; top spending category; largest single spend.
- **F-ANL-12 (P0) Cash flow:** monthly income vs spending vs invested vs borrowed, last 12 months.
- **F-ANL-13 (P0) Where it went:** spending by category (F-INS-1) with drill-down to the
  transactions; top 5 counterparties (masked).
- **F-ANL-14 (P0) Balance history:** end-of-day balance per account and in total, for
  1M / 3M / 6M / 1Y, from the ledger's `balance_after` values.
- **F-ANL-15 (P1) Portfolio performance:** daily market value vs amount invested, time-weighted
  return, allocation by sector, P&L per holding, best and worst performer. Needs a nightly
  `portfolio_snapshots` job, because price ticks are pruned after 7 days.
- **F-ANL-16 (P1) Loan analytics:** principal vs interest paid so far, remaining, projected
  payoff date, and a "what if I prepay ₹X" calculation (interest saved, months saved). The
  what-if is computed by the server and never moves money.
- **F-ANL-17 (P1) Goals and budgets:** on-track / behind per goal at the current saving pace;
  budgets hit or missed per month.
- **F-ANL-18 (P1)** Every personal analytics endpoint is 🤖, so the copilot answers
  "how did I do this month?" from the same numbers the page shows.

**How it's computed (D-040):** plain SQL aggregates on Postgres; a nightly `daily_metrics`
roll-up (one row per day per metric) feeds admin history, and "today" is computed live.
Admin responses are cached for 60 s, personal ones for 60 s per user. No separate warehouse.

### 11.5 Non-functional requirements (v2 additions)
- **Money safety:** all v1 money tests keep passing. New money paths (scheduled runs,
  Autopilot runs, holds, captures, FD payouts, interest) each get an integration test plus a
  concurrency test and an idempotency test.
- **AI latency:** first streamed token in < 2.5 s p95; a read-only answer completes in < 10 s p95.
- **AI cost:** per-user daily token budget; global monthly cap with automatic kill switch at
  100%; prompt caching on system prompts and tool definitions. Cost per copilot turn is
  measured and shown on the admin dashboard (D-039).
- **AI privacy:** no full account numbers, PAN, passwords, tokens or PINs are ever sent to a model;
  documents only with consent; conversations deleted after 30 days; the model provider's data
  retention is stated on the consent screen.
- **AI availability:** when the Claude API is down or the budget is exhausted the copilot
  says so and the rest of the app is unaffected. No money path in the core ever waits on the agent service.
- **Security:** step-up on risky actions; every new endpoint has an authz test proving user A
  can't reach user B's resource (`404`) and an agent token can't reach non-agent endpoints (`403`).
- **Proof of Balance:** sealing lag < 10 s p95; daily snapshot for 10,000 accounts < 60 s.

### 11.6 Success metrics for the demo
- A first-time visitor can, in under 3 minutes with demo credentials: ask the copilot a
  question, approve a copilot-proposed transfer, create an Autopilot rule from a sentence and
  see its 90-day replay, and verify their balance proof in the browser.
- Zero ledger reconcile mismatches and an intact hash chain after the k6 and chaos runs.

### 11.7 Milestones (v2)
Each milestone ends with: tests green, PRD/ARCHITECTURE/DECISIONS updated, a short demo
script in `QA_CHECKLIST.md`, and the owner's sign-off.

| # | Milestone | Main content | Done when |
|---|---|---|---|
| V2-M0 | v1 close-out | First CI run green on GitHub, public deploy with demo data, `/actuator/prometheus` + custom counters, fix the failing frontend test, (optional) PDF statements | M6 fully true; v1 tagged `v1.0.0` |
| V2-MA | Analytics | Admin `/admin/analytics` (KPIs, series, breakdowns, funnel, cohorts, loan book, adoption, health, export) and customer `/insights` (summary, cash flow, categories, counterparties, balance history, portfolio, loans). Built on v1 data first; v2 metrics light up as their milestones ship | Admin and customer numbers agree on a shared fixture; small-cell suppression tested; every export audited |
| V2-M1 | Foundations + Security 2.0 | Outbox, holds + `availableBalance`, realtime notifications, feature flags, passkeys, step-up, PIN, sessions, new-payee cooling, KYC + field encryption | Step-up enforced on a > ₹50k transfer in e2e; hold concurrency test passes |
| V2-M2 | Payments 2.0 | Handles, confirmation of payee, QR, requests, scheduled/recurring, Razorpay top-ups, bill pay, split groups | A Razorpay test payment credits exactly once via verify **and** via webhook (both orders); scheduled-run idempotency test passes |
| V2-M3 | Savings + insights | Goals, round-ups, FDs, savings interest, categories, budgets, forecast, net worth | Interest matches a hand-computed fixture to the paisa |
| V2-M4 | Agent platform + copilot | `agent-quant`, delegated tokens, intents, Concierge + 5 specialists, SSE chat, traces, budgets, kill switch, evals | Red-team set 100% safe; golden set ≥ 90%; app works with `ai` off |
| V2-M5 | **Autopilot** | DSL v1, compiler, validator, server rendering, 90-day replay, engine, run history, templates | A salary-splitter rule fires exactly once per salary under duplicate events and a crash mid-run |
| V2-M6 | Risk + back-office agents | Fraud scoring, `PENDING_REVIEW`, case queue, investigator memos, loan documents, underwriting memos | A scripted fraud scenario is held, memo'd and released/rejected end to end |
| V2-M7 | Trading 2.0 | Limit/stop orders, watchlists, alerts, SIP template, backtester, tax P&L | Limit-order fills are correct under concurrent ticks and cancels |
| V2-M8 | **Proof of Balance** | Sealer, daily Merkle sum tree, public roots, in-browser verification, chain verifier | Tampering with a row in a test DB is detected; browser verification passes for every demo account |
| V2-M9 | Cards + merchant sandbox | Virtual cards, controls, sandbox checkout, auth/capture/refund | Auth → capture → refund leaves balances and holds exact |
| V2-M10 | MCP + developer access | MCP server, personal access tokens | Claude Desktop can read balances and propose an intent that is approved in the UI |
| V2-M11 | Platform | Observability stack, k8s/Helm/ArgoCD, Terraform/Ansible, Redis, Redpanda, k6, Chaos Mesh | Deployed to k3s via ArgoCD; k6 and chaos runs leave the ledger reconciled |

Order rationale: foundations first (holds, step-up and the outbox are needed by almost
everything); payments and savings before agents so the agents have real things to do;
Autopilot right after agents because it reuses the Planner; Proof of Balance late because the
sealer can chain all history from genesis whenever it starts. V2-MA and V2-M11 can run in parallel.

## 12. API contract (v2) — DRAFT
Conventions from §7.1 apply unchanged (base path `/api/v1`, money as strings, UUID ids,
standard error body, `Page` envelope, 🔒💰 = `Idempotency-Key` required). Each subsection
is finalised (exact validation rules, edge cases) at the start of its milestone, following
the contract-change protocol. 🛡️ = may require step-up. 🤖 = accepts agent tokens.

### 12.1 Conventions added in v2
- **New error codes:** `STEP_UP_REQUIRED` (401, body adds `stepUp: {methods: ["PASSKEY","PIN"], action}`),
  `PIN_LOCKED` (423), `COOLING_PERIOD` (422), `INTENT_EXPIRED` (409), `RULE_INVALID` (422, with
  `fieldErrors` pointing into the DSL), `BUDGET_EXHAUSTED` (429, AI budget), `FEATURE_DISABLED`
  (503), `KYC_REQUIRED` (403), plus §7.9's `PAYMENT_VERIFICATION_FAILED` and `GATEWAY_UNAVAILABLE`.
- **Step-up header:** `X-Step-Up-Token: <token>` on the retried 🛡️ request.
- **Agent tokens** carry `aud=agent`; endpoints marked 🤖 accept them, every other endpoint returns `403`.

### 12.2 Changes to v1 shapes (all additive)
- `AccountDto` + `{availableBalance, holdsTotal, nickname?, goal?: {name, target, targetDate, emoji}}`;
  `type` adds `GOAL`, `FIXED_DEPOSIT`.
- `TransferDto.status` adds `PENDING_REVIEW`, `REJECTED`; + `{source: "MANUAL"|"SCHEDULED"|"AUTOPILOT"|"INTENT"|"REQUEST", category}`.
- `TransactionDto` + `{category}`; `type` adds `BILL_PAYMENT`, `INTEREST`, `CARD`, `REFUND`, `TOPUP`.
  (§7.9 said top-ups appear as `DEPOSIT`; once v2 is approved they use `TOPUP`.)
- `OrderDto` + `{orderType: "MARKET"|"LIMIT"|"STOP_LOSS", limitPrice?, triggerPrice?, validity, filledAt?}`;
  `status` adds `OPEN`, `CANCELLED`, `EXPIRED`.
- `UserDto` + `{handle, kycStatus: "NONE"|"PENDING"|"VERIFIED", hasPasskey, hasPin}`.
- `NotificationDto.type` adds `PAYMENT_REQUEST`, `SCHEDULED_FAILED`, `RULE_RUN`, `RULE_PAUSED`,
  `BUDGET_ALERT`, `NEW_DEVICE_LOGIN`, `TRANSFER_UNDER_REVIEW`, `PRICE_ALERT`, `FD_MATURED`, `CARD_DECLINED`.

New enum values can break a client that switches exhaustively, so the frontend must treat
unknown values gracefully from V2-M1 on.

### 12.3 Security 2.0
| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/passkeys/register/options` | | `200 PublicKeyCredentialCreationOptions` |
| POST | `/auth/passkeys/register` | `{credential, name}` | `201 PasskeyDto` |
| GET | `/auth/passkeys` | | `200 PasskeyDto[]` |
| DELETE 🛡️ | `/auth/passkeys/{id}` | | `204` |
| POST | `/auth/passkeys/login/options` | `{email?}` | `200 PublicKeyCredentialRequestOptions` |
| POST | `/auth/passkeys/login` | `{credential}` | same as `/auth/login` |
| POST | `/auth/step-up/options` | `{action}` | `200 {methods, passkeyOptions?}` |
| POST | `/auth/step-up/verify` | `{action, credential?, pin?}` | `200 {stepUpToken, expiresIn}` |
| PUT 🛡️ | `/users/me/pin` | `{pin}` | `204` |
| GET | `/users/me/sessions` | | `200 SessionDto[]` |
| DELETE | `/users/me/sessions/{id}` · `/users/me/sessions?others=true` | | `204` |
| GET | `/users/me/limits` | | `200 LimitsDto` |
| PUT 🛡️ | `/users/me/limits` | `{perTransfer?, daily?}` | `200 LimitsDto` |
| GET | `/users/me/kyc` | | `200 KycDto` |
| PUT | `/users/me/kyc` | `{pan, dateOfBirth, address}` | `200 KycDto` (PAN returned masked) |

Auth notes (2026-10-18): `/auth/step-up/*` and passkey management (`/auth/passkeys`,
`/register*`, `DELETE`) require the bearer token; only `/auth/login`, `/register`, `/refresh`,
`/logout` and `/auth/passkeys/login*` are public. A wrong PIN or a failed passkey assertion on
`/auth/step-up/verify` returns `400 VALIDATION_FAILED` (field `pin` or `credential`), never `401`,
so clients don't mistake it for an expired session. A user with no PIN and no passkey gets
`stepUp.methods: []` and must set one up first.

Backend notes (V2-M1, implemented 2026-10-18):
- **PIN:** `PUT /users/me/pin` body is `{pin, password?}`. The *first* PIN is proven with the account password
  (`400` field `password` if missing or wrong; wrong passwords count toward the login lockout). Once any step-up
  method exists, changing the PIN needs `X-Step-Up-Token` for `SECURITY_SETTINGS`. A PIN is 6 digits and not
  all-same-digit or an obvious run (`123456`…). 5 wrong tries lock the PIN for 30 min (`423 PIN_LOCKED` + `Retry-After`).
- **Step-up token:** valid 5 min, bound to (user, action), **reusable inside the window** so a retry with the same
  `Idempotency-Key` works; a replay of an already-completed request needs no token. The `401` happens before any
  money moves and does not consume the idempotency key.
- **When a transfer needs step-up:** external transfer (not between own accounts) above ₹50,000, or the first
  transfer to a saved payee added < 24 h ago. **Cooling:** at most ₹25,000 in total to a saved payee during its
  first 24 h (`422 COOLING_PERIOD`), checked before step-up. Order of checks: per-transfer/daily limit, cooling, step-up.
- **Limits:** platform maximums ₹2,00,000 per transfer and ₹5,00,000 per IST day to other people. `PUT /users/me/limits`
  needs step-up `SECURITY_SETTINGS`; values may not exceed the platform maximum and `perTransfer` may not exceed `daily`.
  A customer-chosen limit applies to external transfers only (own-account moves are exempt, as before).
- **Sessions:** a session is a refresh-token family; the access token carries a `sid` claim so `current` can be
  marked. `ipCity` is always `null` (no GeoIP). Revoking stops refresh at once; an already-issued access token
  lives out its ≤ 15 min. `DELETE /users/me/sessions` requires `?others=true`. A sign-in from a user agent the
  account has not used before creates a `NEW_DEVICE_LOGIN` notification.
- **KYC:** no real verification in the sandbox, so a valid submission is `VERIFIED` immediately. PAN is
  `AAAAA9999A` (case-insensitive), unique across users (`409` field `pan`), age ≥ 18. Replacing details already on
  file needs step-up `SECURITY_SETTINGS`. `POST /loans` above ₹2,00,000 returns `403 KYC_REQUIRED` until `VERIFIED`.
  Users that existed before V2-M1 are `VERIFIED` (no PAN on file).
- **Errors/headers:** `STEP_UP_REQUIRED` body adds `stepUp: {methods, action}`; CORS allows `X-Step-Up-Token`.
  `UserDto` now has `handle` (null until payments 2.0), `kycStatus`, `hasPasskey`, `hasPin`.
- **Passkeys (WebAuthn, Yubico java-webauthn-server):** `register/options` and `login/options` return the WebAuthn
  JSON *directly* (`challenge`, `rp`, `user`, … as base64url strings), i.e. what `PublicKeyCredential.parse*OptionsFromJSON`
  takes; credentials are posted as the standard `credential.toJSON()` object. Up to 5 per user (`422 LIMIT_EXCEEDED`).
  Challenges are server-side, single-use and expire after 5 min; a challenge is bound to its purpose (register /
  sign-in / step-up) and a step-up challenge to its user and action. `login/options` ignores `email` and never names an
  account, so it reveals nothing about who has a passkey (sign-in uses discoverable credentials; user verification is
  required). Any failed sign-in is the same `401`; a failed registration or step-up is `400` field `credential`.
  A regressing signature counter is rejected as a cloned authenticator. `DELETE /auth/passkeys/{id}` needs step-up
  `SECURITY_SETTINGS`. `step-up/options` returns `passkeyOptions` only when the user has a passkey, and
  `methods` lists `PASSKEY` before `PIN`. Config: `QG_WEBAUTHN_RP_ID` (default `localhost`; must be the domain the SPA
  is served from), allowed origins = `QG_CORS_ORIGINS`.
- **Demo:** the `demo` user's PIN is `246810` (env `QG_DEMO_PIN`), reapplied on every start; demo KYC is pre-filled.

`PasskeyDto = {id, name, createdAt, lastUsedAt}` · `SessionDto = {id, device, browser, ipCity, current, createdAt, lastUsedAt}` ·
`LimitsDto = {perTransfer, daily, perTransferMax, dailyMax, usedToday}`

### 12.4 Payments 2.0
| Method | Path | Body / Query | Response |
|---|---|---|---|
| PUT | `/users/me/handle` | `{handle, accountId}` | `200 HandleDto` |
| GET 🤖 | `/payees/resolve` | `?handle=` or `?accountNumber=` | `200 {holderNameMasked, handle?, accountNumberMasked}` |
| POST 🔒💰🛡️ | `/transfers` | v1 body + `{toHandle?}` (exactly one of `toAccountNumber` / `toHandle`) | `201 TransferDto` (status may be `PENDING_REVIEW`) |
| POST | `/payment-requests` | `{toHandle, amount, note?}` | `201 PaymentRequestDto` |
| GET | `/payment-requests` | `?direction=INCOMING\|OUTGOING&status` | `200 Page<PaymentRequestDto>` |
| POST 🔒💰🛡️ | `/payment-requests/{id}/pay` | `{fromAccountId}` | `201 TransferDto` |
| POST | `/payment-requests/{id}/decline` | `{block?}` | `200 PaymentRequestDto` |
| POST 🔒 | `/scheduled-transfers` | `{fromAccountId, toAccountNumber\|toHandle, amount, note?, startDate, frequency: "ONCE"\|"WEEKLY"\|"MONTHLY", endDate?, count?}` | `201 ScheduledTransferDto` |
| GET | `/scheduled-transfers` | | `200 ScheduledTransferDto[]` |
| POST | `/scheduled-transfers/{id}/pause` · `/resume` | | `200 ScheduledTransferDto` |
| DELETE | `/scheduled-transfers/{id}` | | `204` |
| GET | `/billers` | `?category` | `200 BillerDto[]` |
| POST | `/billers/{id}/fetch` | `{consumerNumber}` | `200 {billId, amount, dueDate, customerNameMasked}` |
| POST 🔒💰 | `/bill-payments` | `{billId, fromAccountId}` | `201 BillPaymentDto` |
| GET | `/bill-payments` | `?page&size` | `200 Page<BillPaymentDto>` |
| POST / GET | `/split-groups` · `/split-groups/{id}` | `{name, memberHandles[]}` | `GroupDto` |
| POST | `/split-groups/{id}/expenses` | `{paidByHandle, amount, description, split: {type: "EQUAL"\|"EXACT"\|"PERCENT", shares?}}` | `201 ExpenseDto` |
| POST | `/split-groups/{id}/settle` | | `200 PaymentRequestDto[]` |
| — | Razorpay top-ups | | as specified in §7.9 |

`PaymentRequestDto = {id, fromHandle, toHandle, amount, note, status: "OPEN"|"PAID"|"DECLINED"|"EXPIRED"|"CANCELLED", transferId, expiresAt, createdAt}` ·
`ScheduledTransferDto = {id, fromAccountId, toAccountNumberMasked, toName, amount, note, frequency, nextRunDate, endDate, remainingCount, status: "ACTIVE"|"PAUSED"|"COMPLETED"|"CANCELLED", lastRun: {date, outcome, transferId}}`

### 12.5 Savings and insights
| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST | `/accounts` | v1 body; `type: "GOAL"` adds `{name, target, targetDate?, emoji?}` | `201 AccountDto` |
| PUT | `/accounts/{id}/round-up` | `{enabled, roundTo: 10\|50\|100, goalAccountId}` | `200` |
| GET | `/fixed-deposits/rates` | | `200 [{minDays, maxDays, annualRate}]` |
| POST 🔒💰 | `/fixed-deposits` | `{fromAccountId, amount, tenureDays, onMaturity: "PAYOUT"\|"RENEW"}` | `201 FixedDepositDto` |
| GET | `/fixed-deposits` | | `200 FixedDepositDto[]` |
| POST 🔒💰🛡️ | `/fixed-deposits/{id}/close` | | `200 FixedDepositDto` |
| GET 🤖 | `/insights/spending` | `?from&to&groupBy=category\|month` | `200 [{key, amount, count}]` |
| PATCH | `/transactions/{id}/category` | `{category, applyToCounterparty?}` | `200 TransactionDto` |
| GET 🤖 · PUT | `/budgets` | `[{category, monthlyLimit}]` | `200 BudgetDto[]` (with `spent`, `pct`) |
| GET 🤖 | `/insights/forecast` | `?days=30` | `200 {points: [{date, balance}], warnings: [...]}` |
| GET 🤖 | `/insights/net-worth` | `?months=12` | `200 {current, breakdown, history}` |

`FixedDepositDto = {id, accountNumberMasked, principal, annualRate, tenureDays, startDate, maturityDate, accruedInterest, maturityAmount, onMaturity, status: "ACTIVE"|"MATURED"|"CLOSED"}`

### 12.6 Copilot and intents
| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/copilot/conversations` | | `201 {id}` |
| POST | `/copilot/conversations/{id}/messages` | `{text}` | `200 text/event-stream` (events below) |
| GET | `/copilot/conversations` · `/copilot/conversations/{id}` | | conversations / messages |
| DELETE | `/copilot/conversations/{id}` | | `204` |
| GET | `/copilot/runs/{runId}/trace` | | `200 TraceDto` (redacted steps, own runs only) |
| POST 🤖 | `/intents` | `{kind, payload, conversationId, rationale}` | `201 IntentDto` |
| GET | `/intents` | `?status=PROPOSED` | `200 IntentDto[]` |
| POST 🔒💰🛡️ | `/intents/{id}/approve` | | `200 IntentDto` (status `EXECUTED` + `resultRef`) |
| POST | `/intents/{id}/reject` | | `200 IntentDto` |

The browser only ever talks to the core; the core proxies copilot traffic to `agent-quant`.
SSE wire format: each event is `event: <type>
data: <JSON payload>

`, where `<type>` is the event name and the payload is the object shown. Events: `token {text}`, `tool {agent, tool, status}` (progress only, no raw data),
`intent {IntentDto}`, `citation {type, id, label}`, `done {runId, usage}`, `error {code, message}`.

`IntentDto = {id, kind: "TRANSFER"|"BILL_PAYMENT"|"SCHEDULED_TRANSFER"|"PAYMENT_REQUEST"|"ORDER"|"GOAL_TRANSFER"|"FD_BOOKING"|"LOAN_APPLICATION"|"AUTOPILOT_RULE",
status: "PROPOSED"|"EXECUTED"|"REJECTED"|"EXPIRED"|"FAILED", summary: {title, lines: [{label, value}]}, rationale,
riskLevel: "LOW"|"MEDIUM"|"HIGH", requiresStepUp, proposedBy: {agent}, conversationId, failureCode?, resultRef?: {type, id}, expiresAt, createdAt}`.
`summary` is rendered by the core from the validated payload (D-023); `rationale` is the
model's own text and is labelled as such in the UI. The raw `payload` is not echoed back.

### 12.7 Autopilot
| Method | Path | Body | Response |
|---|---|---|---|
| POST 🤖 | `/autopilot/compile` | `{text}` | `200 {rule: RuleDsl, rendering, warnings}` (calls the Planner; needs the `ai` flag) |
| POST 🤖 | `/autopilot/validate` | `{rule}` | `200 {valid, rendering, fieldErrors}` |
| POST 🤖 | `/autopilot/simulate` | `{rule, days: 90}` | `200 SimulationDto` |
| POST 🛡️ | `/autopilot/rules` | `{name, rule}` | `201 RuleDto` (status `ACTIVE`) |
| GET | `/autopilot/rules` · `/autopilot/rules/{id}` | | `RuleDto[]` / `RuleDto` |
| PUT 🛡️ | `/autopilot/rules/{id}` | `{name, rule}` | `200 RuleDto` (new version) |
| POST | `/autopilot/rules/{id}/pause` · `/resume` (🛡️) | | `200 RuleDto` |
| DELETE | `/autopilot/rules/{id}` | | `204` |
| GET | `/autopilot/rules/{id}/runs` | `?page&size` | `200 Page<RuleRunDto>` |
| GET | `/autopilot/templates` | | `200 TemplateDto[]` |

`RuleDsl` (v1) example:
```json
{ "dslVersion": 1,
  "trigger": { "type": "MONEY_RECEIVED", "accountId": "…", "minAmount": "40000.00", "counterpartyContains": "ACME" },
  "conditions": [ { "type": "BALANCE_AT_LEAST", "accountId": "…", "amount": "5000.00" } ],
  "actions": [
    { "type": "TRANSFER", "toAccountId": "<goal id>", "amount": { "mode": "PERCENT_OF_TRIGGER", "value": "20" } },
    { "type": "TRANSFER", "toBeneficiaryId": "…", "amount": { "mode": "FIXED", "value": "15000.00" } } ],
  "caps": { "perRun": "30000.00", "perMonth": "60000.00" } }
```
`SimulationDto = {firings: [{date, triggerSummary, actions: [{summary, amount, wouldSucceed, reason?}]}], totalMoved, failures}` ·
`RuleRunDto = {id, ruleVersion, triggerEventId, startedAt, outcome: "SUCCEEDED"|"PARTIAL"|"FAILED"|"SKIPPED_CAP", actions: [{type, amount, transferId?, orderId?, error?}]}`

### 12.8 Risk and back office (role `ADMIN` unless noted)
| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/admin/fraud-cases` | `?status=OPEN&page&size` | `200 Page<FraudCaseDto>` |
| GET | `/admin/fraud-cases/{id}` | | `200 FraudCaseDto + {memo, signals, customerBaseline}` |
| POST | `/admin/fraud-cases/{id}/release` · `/reject` | `{reason}` | `200 FraudCaseDto` |
| POST (customer) | `/loans/{id}/documents` | multipart `file`, `consent=true` | `201 LoanDocumentDto` |
| GET | `/admin/loans/{id}/underwriting` | | `200 {memo, extracted: [{field, value, page}], flags, recommendation}` |
| GET | `/admin/ai/usage` | `?from&to` | `200 {tokens, costUsd, byAgent, byDay}` |
| GET | `/admin/ai/runs/{runId}` | | `200 TraceDto` (full) |
| PUT | `/admin/feature-flags/{name}` | `{enabled}` | `200` (audited) |

### 12.9 Trading 2.0
| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST 🔒💰 | `/orders` | v1 body + `{orderType, limitPrice?, triggerPrice?, validity?}` | `201 OrderDto` (`OPEN` or `FILLED`) |
| POST | `/orders/{id}/cancel` | | `200 OrderDto` |
| GET / POST / DELETE | `/watchlists` · `/watchlists/{id}/symbols/{symbol}` | | `WatchlistDto` |
| GET / POST / DELETE | `/price-alerts` | `{symbol, direction: "ABOVE"\|"BELOW", price}` | `PriceAlertDto` |
| POST 🤖 | `/backtests` | `{strategy, symbols, from, to, initialCash}` | `201 BacktestDto` |
| GET | `/reports/tax-pnl` | `?fy=2026-27&format=json\|csv` | P&L report |

### 12.10 Proof of Balance
| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/public/proofs/roots` | none | `200 [{date, root, totalLiabilities, leafCount, chainHead: {position, hash}}]` |
| GET | `/proofs/accounts/{accountId}` | customer (own account) | `?date=` → `200 {date, leaf: {accountId, balance, salt}, path: [{hash, sum, side}], root, totalLiabilities}` |
| GET | `/admin/ledger/verify-chain` | admin | `200 {intact, checkedUpTo, firstBrokenPosition?}` |
| GET | `/admin/ledger/solvency` | admin | `200 {totalLiabilities, totalAssets, ratio}` |

**Canonical hashing (exact; the browser recomputes it, so both sides must match byte for byte):**
- Amounts are plain decimal strings with exactly 4 fraction digits (`"1250.5000"`, `"0.0000"`); hashes are lowercase hex SHA-256 of the UTF-8 string.
- `leaf.hash = SHA256("<accountId>|<balance>|<salt>")`, `leaf.sum = balance`.
- `node.hash = SHA256("<left.hash>|<left.sum>|<right.hash>|<right.sum>")`, `node.sum = left.sum + right.sum`.
- Leaves are ordered by account id, then padded to a power of two with `SHA256("pad-<index>|0.0000|pad")` zero-sum leaves.
- In `path`, each node is the **sibling** at that level, from the leaf upward; `side` says which side the sibling sits on (`LEFT` → parent = H(sibling, current)).
- A verifier rejects any negative `sum` in the path, a root mismatch, or a total that differs from `/public/proofs/roots`.
- Reference implementation: `frontend-quant/src/lib/merkle.ts` (with tests).

### 12.11 Cards
| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/accounts/{id}/cards` | | `201 CardDto` |
| GET | `/cards` | | `200 CardDto[]` |
| POST 🛡️ | `/cards/{id}/reveal` | | `200 {pan, cvv, expiry, hideAfterSeconds: 30}` |
| PATCH | `/cards/{id}/controls` | `{frozen?, online?, perTxnLimit?, blockedCategories?}` | `200 CardDto` |
| POST | `/sandbox/merchant/authorize` | `{cardNumber, expiry, cvv, amount, merchant, category}` | `200 {authId, status: "APPROVED"\|"DECLINED", reason?}` |
| POST | `/sandbox/merchant/{authId}/capture` · `/refund` | `{amount?}` | `200` |

`CardDto = {id, accountId, last4, expiry, status: "ACTIVE"|"FROZEN", controls}`. Sandbox
endpoints exist only when the `cards` flag is on, and are rate-limited.

### 12.12 Developer access
| Method | Path | Body | Response |
|---|---|---|---|
| POST 🛡️ | `/users/me/tokens` | `{name, scope: "read"\|"read+propose", expiresInDays ≤ 90}` | `201 {id, token (shown once), name, scope, expiresAt}` |
| GET | `/users/me/tokens` | | `200 [{id, name, scope, lastUsedAt, expiresAt}]` |
| DELETE | `/users/me/tokens/{id}` | | `204` |

### 12.13 Analytics
Common rules: read-only (no idempotency key); `from`/`to` are inclusive IST dates
(`YYYY-MM-DD`), default the last 30 days, max span 366 days (admin: 3 years); `400
VALIDATION_FAILED` when `from > to` or the span is too long. **Every metric value is a decimal
string**, including counts, so the client handles one type; `unit` says how to format it.
`changePct` is null when the previous value is 0.

**Business (role `ADMIN`)**
| Method | Path | Query | Response |
|---|---|---|---|
| GET | `/admin/analytics/overview` | `from&to` | `200 {from, to, previousFrom, previousTo, kpis: KpiDto[]}` |
| GET | `/admin/analytics/timeseries` | `metric&from&to&granularity=DAY\|WEEK\|MONTH&compare=true` | `200 SeriesDto` |
| GET | `/admin/analytics/breakdown` | `dimension&from&to&limit=10` | `200 BreakdownDto` |
| GET | `/admin/analytics/funnel` | `from&to` | `200 {steps: [{key, label, users, conversionPct, stepConversionPct}]}` |
| GET | `/admin/analytics/cohorts` | `months=6` (1–12) | `200 {cohorts: [{cohort: "2026-05", size, retentionPct: (string\|null)[]}]}` |
| GET | `/admin/analytics/loan-book` | | `200 LoanBookDto` |
| GET | `/admin/analytics/adoption` | `from&to` | `200 {features: [{key, label, users, pct, available}], ai: {available, turns, costUsd, costPerActiveUser}, autopilot: {available, runs, successPct}, fraud: {available, cases, releasedPct}}` |
| GET | `/admin/analytics/health` | | `200 {reconcileBalanced, checkedAt, sealLagSeconds?, outboxLag?, moneyErrorRatePct?}` |
| GET | `/admin/analytics/export` | `report=overview\|timeseries\|breakdown\|funnel\|cohorts\|loan-book` + that report's query | `200 text/csv` (audited) |

`KpiDto = {key, label, unit: "MONEY"|"COUNT"|"PERCENT", value, previous, changePct, goodDirection: "UP"|"DOWN"}`
(`goodDirection` lets the UI colour a change: more overdue is bad, more users is good.)
`SeriesDto = {metric, label, unit, granularity, available, points: [{t, value}], previous?: [{t, value}]}` (`t` = bucket start date)
`BreakdownDto = {dimension, unit, total, rows: [{key, label, value, count, sharePct, suppressed}]}`
`LoanBookDto = {outstandingPrincipal, disbursed, repaid, interestEarned, activeCount, approvalRatePct, avgTicket, overdueAmount, overdueRatePct, ageing: [{bucket: "1-30"|"31-60"|"61-90"|"90+", count, amount}], byStatus: [{status, count, amount}]}`
- `metric`: `TOTAL_USERS`, `NEW_USERS`, `ACTIVE_USERS`, `CUSTOMER_DEPOSITS`, `NET_INFLOW`, `TRANSFER_VOLUME`,
  `TRANSFER_COUNT`, `TRADE_VOLUME`, `TRADE_COUNT`, `LOAN_DISBURSED`, `LOAN_REPAID`, `INTEREST_INCOME`,
  `OVERDUE_AMOUNT`, `FAILED_MONEY_REQUESTS`, and from their milestones `COPILOT_TURNS`, `AI_COST_USD`,
  `AUTOPILOT_RUNS`, `FRAUD_CASES`. Before its milestone ships, a metric still returns `200`, with
  empty `points` and `available: false`, so the dashboard can show "coming soon" instead of an error.
- `dimension`: `TRANSACTION_TYPE`, `ACCOUNT_TYPE`, `ACCOUNT_STATUS`, `INSTRUMENT`, `SECTOR`, `ORDER_SIDE`,
  `LOAN_STATUS`, `LOAN_PURPOSE`, `FUNDING_SOURCE`, `ERROR_CODE`, `SPEND_CATEGORY`.
- KPI keys match the metric names above plus `FUNDED_USERS_PCT`, `OUTSTANDING_PRINCIPAL`, `OVERDUE_RATE_PCT`.

**Personal (role `CUSTOMER`, own data only)**
| Method | Path | Query / Body | Response |
|---|---|---|---|
| GET 🤖 | `/analytics/summary` | `from&to` | `200 {from, to, income, spending, net, savingsRatePct, previous: {income, spending, net, savingsRatePct}, topCategory: {key, label, amount}\|null, largestSpend: {transactionId, amount, description, date}\|null}` |
| GET 🤖 | `/analytics/cashflow` | `months=12` (1–24) | `200 {points: [{month: "2026-10", income, spending, net, invested, borrowed}]}` |
| GET 🤖 | `/analytics/counterparties` | `from&to&limit=5` | `200 [{label, accountNumberMasked, sent, received, count}]` |
| GET 🤖 | `/analytics/balance-history` | `accountId?&range=1M\|3M\|6M\|1Y` | `200 {accountId, points: [{t, balance}]}` (all accounts summed when `accountId` is omitted) |
| GET 🤖 | `/analytics/portfolio` | `range=1M\|3M\|6M\|1Y\|ALL` | `200 {points: [{t, marketValue, invested}], twrPct, allocation: [{sector, value, pct}], holdings: [{symbol, name, unrealizedPnl, unrealizedPnlPct, realizedPnl}], best, worst}` |
| GET 🤖 | `/analytics/loans` | | `200 {totalInterestPaid, loans: [{loanId, principalPaid, interestPaid, remainingPrincipal, projectedPayoffDate}]}` |
| POST 🤖 | `/analytics/loans/{id}/prepayment` | `{amount}` | `200 {interestSaved, monthsSaved, newPayoffDate}` (calculation only; another user's loan → `404`) |
| GET 🤖 | `/analytics/goals` | | `200 [{accountId, name, target, saved, pct, monthlyPace, projectedDate, onTrack}]` (V2-M3) |

The existing `/insights/spending` (§12.5) supplies category breakdowns for both the page and the copilot.

Backend notes (V2-MA, implemented 2026-10-18):
- **Computed live** from the ledger on every request (D-043); there is no `daily_metrics` roll-up and no 60-second
  cache yet. Responses are the shapes above.
- `NET_INFLOW` = deposits received, net of reversals (v1 has no outflow to the outside world yet).
- `FAILED_MONEY_REQUESTS` counts failed POSTs to `/transfers`, `/orders`, `/accounts/{id}/deposit` and
  `/loans/{id}/repay` (stored in `request_failures`). A `401 STEP_UP_REQUIRED` is not a failure.
  `health.outboxLag` is the number of pending outbox events.
- **Suppression:** a suppressed breakdown row has an opaque key `SUPPRESSED_n` and a generic label. A cohort under
  5 users has `retentionPct` null in every cell but still shows `size` (it equals the `NEW_USERS` KPI, which is not suppressed).
- `summary.topCategory` is coarse (`TRANSFERS` / `LOAN_REPAYMENTS`) until V2-M3 categories exist; `SPEND_CATEGORY`
  likewise. `/analytics/goals` returns `[]` until V2-M3.
- **Portfolio history** comes from nightly `portfolio_snapshots` (23:55 IST) plus a live point for today, so a fresh
  install shows a single point until snapshots accumulate.
- The 🤖 marker on `/analytics/*` is not implemented yet (agent tokens arrive with V2-M4).

### 12.14 DTO reference (added 2026-10-18 by the frontend session)
§12.3–§12.12 named these shapes without defining them. They are defined here so both sides build
the same thing; `frontend-quant/src/api/types.v2.ts` mirrors this list exactly. Money is a
string, other decimals (percentages, rates) are strings too, counts are integers unless noted.

**Feature flags** (new endpoints, needed so the UI can hide switched-off features, F-AGT-6 / F-FLAG-1)
| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/feature-flags` | any signed-in user | `200 {ai, autopilot, razorpay, cards, mcp}` (booleans) |
| GET | `/admin/feature-flags` | admin | `200 [{name, enabled, updatedAt, updatedBy}]` |

**Security:** `KycDto = {status, panMasked, dateOfBirth, address, verifiedAt}`; `StepUpChallenge`
(extra body on 401) `= {methods: ("PASSKEY"|"PIN")[], action}`; step-up `action` is one of
`TRANSFER`, `INTENT_APPROVE`, `RULE_ACTIVATE`, `CARD_REVEAL`, `SECURITY_SETTINGS`, `FD_CLOSE`, `TOKEN_CREATE`.

**Payments:**
- `HandleDto = {handle, accountId, changeableAt}`
- `BillerDto = {id, name, category: "ELECTRICITY"|"MOBILE"|"BROADBAND"|"DTH"|"GAS"|"WATER"|"CREDIT_CARD"|"INSURANCE", consumerNumberLabel, consumerNumberPattern}`
- `BillPaymentDto = {id, billerId, billerName, category, consumerNumberMasked, amount, fromAccountId, transactionId, createdAt}`
- `GroupDto = {id, name, members: [{handle, displayName, balance}], expenses: ExpenseDto[], createdAt}` (member `balance` > 0: the group owes them)
- `ExpenseDto = {id, paidByHandle, amount, description, splitType, shares: [{handle, amount}], createdAt}`; request `split.shares` = `[{handle, value}]` (rupees for `EXACT`, percent for `PERCENT`)
- `ScheduledTransferDto.lastRun.outcome` = `SUCCEEDED` | `RETRYING` | `MISSED`

**Savings:** `PUT /accounts/{id}/round-up` returns `200 {accountId, enabled, roundTo, goalAccountId}`
and `GET /accounts/{id}/round-up` returns the same (added). `BudgetDto = {category, monthlyLimit, spent, pct}`;
`ForecastDto.warnings = [{date, code: "LOW_BALANCE"|"EMI_AT_RISK"|"SCHEDULED_AT_RISK", message}]`;
`NetWorthDto = {current, breakdown: {cash, goals, fixedDeposits, investments, loans}, history: [{month, value}]}`.
Category keys: `FOOD`, `GROCERIES`, `SHOPPING`, `TRANSPORT`, `BILLS`, `RENT`, `ENTERTAINMENT`, `HEALTH`,
`EDUCATION`, `TRAVEL`, `TRANSFERS`, `INVESTMENTS`, `LOANS`, `INCOME`, `OTHER`.

**Copilot:**
- `ConversationDto = {id, title, createdAt, updatedAt}`; `GET /copilot/conversations/{id}` → `ConversationDto + {messages: ChatMessageDto[]}`
- `ChatMessageDto = {id, role: "USER"|"ASSISTANT", text, intents: IntentDto[], citations: [{type, id, label}], runId, createdAt}`
- citation `type`: `TRANSACTION` | `ACCOUNT` | `INSTRUMENT` | `LOAN` | `RULE` | `METRIC`
- `tool` SSE event `status`: `STARTED` | `DONE` | `FAILED`; `done.usage = {inputTokens, outputTokens}`
- `TraceDto = {runId, startedAt, totalMs, costUsd, steps: [{agent, kind: "MODEL"|"TOOL", name, status: "OK"|"ERROR", latencyMs, inputTokens, outputTokens, detail}]}` (`detail` is redacted)
- `agent` names: `CONCIERGE`, `LEDGER_ANALYST`, `PAYMENTS`, `MARKETS`, `PLANNER`, `LOAN_ADVISOR`, `FRAUD_INVESTIGATOR`, `UNDERWRITING_ANALYST`

**Autopilot:**
- `RuleDto = {id, name, version, status: "ACTIVE"|"PAUSED", rule: RuleDsl, rendering, pausedReason, lastRunAt, runCount, movedThisMonth, createdAt, updatedAt}`
- `RuleRunDto` adds `triggerSummary` (server-rendered sentence) to §12.7
- `TemplateDto = {id, name, description, prompt, rule: RuleDsl|null}`
- DSL v1 in full: trigger `MONEY_RECEIVED {accountId, minAmount?, counterpartyContains?, transactionType?}` ·
  `SCHEDULE {frequency: "MONTHLY"|"WEEKLY", dayOfMonth? (1–28), weekday? ("MON"…"SUN")}` ·
  `BALANCE_ABOVE {accountId, threshold}` · `PRICE_CROSSES {symbol, direction: "ABOVE"|"BELOW", price}` ·
  `BUDGET_THRESHOLD {category, percent}`; condition `BALANCE_AT_LEAST {accountId, amount}`;
  amount `{mode: "FIXED"|"PERCENT_OF_TRIGGER"|"EXCESS_ABOVE", value}`; actions
  `TRANSFER {fromAccountId?, toAccountId? | toBeneficiaryId?, amount}` · `BUY {accountId, symbol, amount}` ·
  `PAY_BILL {fromAccountId, billerId, consumerNumber}` · `ROUND_UP_SWEEP {goalAccountId}` · `NOTIFY {message}`

**Risk / back office:**
- `FraudCaseDto = {id, status: "OPEN"|"RELEASED"|"REJECTED", score (0–100), transferId, amount, fromAccountNumberMasked, toAccountNumberMasked, toName, customer: {userId, fullName}, topSignal, createdAt, decidedAt, decisionReason}`
- detail adds `memo: {summary, suggestedAction: "RELEASE"|"REJECT"|"CONTACT_CUSTOMER", confidence: "LOW"|"MEDIUM"|"HIGH", generatedAt, runId} | null`,
  `signals: [{code, label, weight}]`, `customerBaseline: {avgTransfer, transfers30d, usualHoursIst, accountAgeDays}`
- `LoanDocumentDto = {id, fileName, contentType, sizeBytes, uploadedAt}`; `GET /loans/{id}/documents` → `LoanDocumentDto[]` (added)
- underwriting response adds `status: "PENDING"|"READY"|"FAILED"` and `generatedAt`; `recommendation`: `APPROVE` | `REJECT` | `REVIEW` | null
- `/admin/ai/usage` adds `budget: {monthlyCapUsd, usedPct}`; `byAgent = [{agent, runs, costUsd}]`, `byDay = [{date, costUsd, turns}]`

**Trading:**
- `WatchlistDto = {id, name, symbols, createdAt}`; `POST /watchlists {name}`; `POST /watchlists/{id}/symbols/{symbol}` (added verb); `DELETE` both
- `PriceAlertDto = {id, symbol, direction, price, status: "ACTIVE"|"TRIGGERED", triggeredAt, createdAt}`
- `StrategyDsl`: `SMA_CROSS {fast, slow}` · `RSI {period, buyBelow, sellAbove}` · `BUY_THE_DIP {dropPct, takeProfitPct}` · `REBALANCE {everyDays, weights: {symbol: pct}}`
- `BacktestDto = {id, strategy, symbols, from, to, initialCash, finalValue, returnPct, maxDrawdownPct, winRatePct, equity: [{t, value}], trades: [{t, symbol, side, quantity, price}], createdAt}`; `GET /backtests` lists the user's last 20
- `TaxPnlDto = {fy, shortTermGains, longTermGains, total, rows: [{symbol, buyDate, sellDate, quantity, cost, proceeds, gain, term: "SHORT"|"LONG"}]}`

**Cards:** `controls = {frozen, online, perTxnLimit, blockedCategories}`; merchant categories:
`GROCERY`, `DINING`, `TRAVEL`, `ELECTRONICS`, `GAMBLING`, `FUEL`, `ENTERTAINMENT`, `OTHER`.

## 13. Data model (v2 additions)
New tables (exact columns live in Flyway migrations, owned by the backend; `agent-quant` has
its own schema and no access to the core schema):
- **Foundations:** `outbox_events`, `holds`, `feature_flags`.
- **Security:** `passkeys`, `step_up_tokens`, `user_pins`, `user_limits`, `kyc_profiles` (encrypted columns + blind index), `personal_access_tokens`.
- **Payments:** `handles`, `payment_requests`, `blocked_requesters`, `scheduled_transfers`, `scheduled_transfer_runs`, `billers`, `bills`, `bill_payments`, `split_groups`, `split_members`, `split_expenses`, `split_shares`, `topups`, `webhook_events`.
- **Savings and insights:** `goals` (1:1 with `accounts` of type `GOAL`), `round_up_settings`, `fixed_deposits`, `interest_accruals`, `categories`, `category_overrides`, `budgets`.
- **Agents:** core side `intents`; `agent-quant` schema `conversations`, `messages`, `agent_runs`, `agent_steps`, `ai_usage_daily`.
- **Autopilot:** `autopilot_rules`, `autopilot_rule_versions`, `autopilot_runs`, `autopilot_run_actions`.
- **Risk:** `fraud_signals`, `fraud_cases`, `loan_documents`, `underwriting_memos`.
- **Trading:** `orders` gains order-type columns; `holdings` gains `reserved_qty`; `watchlists`, `watchlist_symbols`, `price_alerts`, `backtests`.
- **Proof of Balance:** `ledger_seals` (insert-only: `position`, `entry_id`, `prev_hash`, `hash`), `balance_snapshots`, `balance_snapshot_nodes`.
- **Cards:** `cards` (encrypted PAN), `card_authorizations`.
- **Analytics:** `daily_metrics` (day, metric, value; nightly roll-up), `portfolio_snapshots` (user, day, market value, invested).
- **New system accounts:** `GATEWAY_CLEARING`, `BILLER_CLEARING`, `INTEREST_EXPENSE`, `CARD_CLEARING`.
