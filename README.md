# QuantGen

**Banking, with the precision of a quant.** One login gives you bank accounts, transfers,
simulated stock trading with live prices, and loans, all settled through a single
double-entry ledger with ACID guarantees.

| | |
|---|---|
| **Backend** | Spring Boot (Java 25), modular monolith, PostgreSQL 17, Flyway, JWT + rotating refresh cookies, STOMP WebSocket prices |
| **Frontend** | React 19 + TypeScript, Vite, TanStack Query, Tailwind v4, Radix, motion, lightweight-charts, MSW |
| **Delivery** | Docker images (non-root), nginx same-origin proxy with a strict CSP, GitHub Actions CI with Trivy scans |

Specs live in the repo: [`PRD.md`](PRD.md) (scope and the **API contract, §7**),
[`ARCHITECTURE.md`](ARCHITECTURE.md) (ledger design, auth flow, ADRs),
[`frontend-quant/DESIGN.md`](frontend-quant/DESIGN.md) (visual and UX system) and
[`CLAUDE.md`](CLAUDE.md) (engineering and security rules).

## What it does

- **Accounts:** up to 3 savings/checking accounts, demo "add money", paginated history
  with filters, and CSV statements.
- **Transfers:** to your own accounts or anyone's account number, saved beneficiaries,
  ₹2,00,000 per transfer and ₹5,00,000 per day, idempotent end to end.
- **Markets:** about 15 simulated instruments on a random walk every 3 s, pushed over
  WebSocket; market buy/sell settles cash and holdings in one transaction; portfolio with
  realized and unrealized P&L.
- **Loans:** EMI calculator, eligibility (EMI ≤ 50% of income, amount ≤ 20× income),
  admin approval that disburses through the ledger, schedule, repayment, and daily
  auto-debit.
- **Admin console:** bank-wide stats, user search, freeze/unfreeze with an audited
  reason, loan queue, audit log, and one-click ledger reconciliation.
- **Notifications:** money received, loan decisions, EMI due/paid/overdue, and account
  freezes.

Money is `NUMERIC(19,4)` in the database, `BigDecimal` in Java and a **string** in JSON;
the UI never does float arithmetic on amounts and never shows an optimistic balance.

## Try it in 2 minutes (frontend only, no backend)

```bash
cd frontend-quant
npm ci
npm run dev:mock        # http://localhost:5173, every API mocked in the browser (MSW)
```

Use **demo@quantgen.dev / Demo@1234** (customer) or **admin@quantgen.dev / Admin@1234**
(admin), or the "Use demo account" button on the login page.

## Full stack in development

Requires JDK 25, Node 24 and Docker. Postgres runs on host port **5433** (5432 is often
taken by a local install).

```bash
docker compose -f docker-compose.dev.yml up -d     # Postgres 17
cd backend-quant && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # :8080, seeds demo users
cd frontend-quant && npm run dev                   # :5173, proxies /api and /ws to :8080
```

`npm run dev:hybrid` uses the real backend for the endpoints listed in
`frontend-quant/src/mocks/live.ts` and mocks the rest.

API docs: <http://localhost:8080/swagger-ui.html> · health: <http://localhost:8080/actuator/health>

## Production-style stack (Docker Compose)

```bash
cp .env.example .env          # set POSTGRES_PASSWORD and QG_JWT_SECRET (openssl rand -base64 48)
docker compose up -d --build
# → http://localhost:8088
```

Only the `web` container is published. nginx serves the SPA and reverse-proxies `/api`
and `/ws` to the API, so the browser sees one origin (no CORS). Postgres and the API sit
on an internal network. Compose refuses to start without the secrets. The `demo` profile
(on by default) seeds the demo users; set `QG_DEMO_ADMIN_PASSWORD` / `QG_DEMO_USER_PASSWORD`
on any public deployment.

## Tests

```bash
cd frontend-quant
npm run lint && npx tsc -b
npm run test          # Vitest: money maths, forms, flows against PRD-exact mocks
npm run e2e           # Playwright: login → transfer → buy, admin approve/freeze,
                      #   and a WCAG 2.1 AA axe audit of every screen, both themes, 1440 px + 360 px
QG_LIVE=1 npx playwright test e2e/live.spec.ts --project=desktop
                      # the real UI against the real API (dev backend on :8080):
                      #   register → deposit → transfer → buy, and apply → admin approve →
                      #   repay → notifications → freeze → audit
QG_LIVE=1 QG_BASE_URL=http://localhost:8088 npx playwright test e2e/live.spec.ts --project=desktop
                      # the same, against the production compose stack (nginx + prod API)
# Each run registers 2 users; the API allows 10 registrations/hour per IP. For repeated runs,
# start the backend with QG_RATE_LIMIT_ENABLED=false (never in production).

cd backend-quant
./mvnw verify         # unit + Testcontainers integration, incl. 100 parallel transfers on one account
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs all of the above, checks
that the production bundle contains no mocks or seed credentials, builds both images,
fails on HIGH/CRITICAL Trivy findings, and pushes to GHCR from `main`.
[`deploy.yml`](.github/workflows/deploy.yml) is a manual SSH deploy of a chosen tag.

## Security at a glance

- Access JWT (15 min) held **in memory only**; refresh token in an
  `HttpOnly; Secure; SameSite=Strict` cookie, rotated on every use, with reuse detection
  that revokes the whole family.
- Deny-by-default authorization with ownership checks in services; another user's
  resource is a `404`, not a `403`.
- `Idempotency-Key` on every money-moving request: same key and body replays, while the
  same key with a different body gets a `409`.
- Login lockout after 5 failures, an audit log for sensitive actions, masked account
  numbers in logs and UI, and no stack traces in responses.
- Strict CSP (`script-src 'self'`, no inline scripts), HSTS, `frame-ancestors 'none'`,
  and a read-only, capability-dropped web container.

## Licence

See [`LICENSE`](LICENSE).
