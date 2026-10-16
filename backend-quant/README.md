# QuantGen API (`backend-quant/`)

Spring Boot 4 / Java 25 modular monolith: auth, accounts, a double-entry ledger, payments,
trading, loans, admin and notifications. Scope and the API contract are in
[`../PRD.md`](../PRD.md) (§7 is the contract), and the design is in [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

## Run it

```bash
docker compose -f ../docker-compose.dev.yml up -d          # Postgres 17 on localhost:5433
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev      # API on :8080, demo data seeded
```

- Swagger UI: http://localhost:8080/swagger-ui.html · OpenAPI: `/v3/api-docs` · health: `/actuator/health`
- Demo logins (dev and `demo` profiles): `admin@quantgen.dev / Admin@1234`, `demo@quantgen.dev / Demo@1234`

## Test

```bash
./mvnw verify      # unit tests + integration tests (Testcontainers; Docker must be running)
```

The integration tests run the whole app over real HTTP against a throwaway Postgres. They cover
the money invariants:
- 100 parallel transfers end with exact balances and a ledger that sums to zero
- idempotent replay and conflict
- buy/sell atomicity and oversell
- EMI math across the whole product range
- the loan lifecycle, including the auto-debit job and overdue handling
- parallel approvals disburse once; parallel repayments never overpay
- role checks on every admin endpoint; other users' resources return 404
- demo seeding is idempotent

## Profiles

| Profile | Use | Notes |
|---|---|---|
| `dev` | local development | `localhost:5433`, dev-only JWT secret, demo data |
| `test` | `./mvnw verify` | test-only secret, cheap BCrypt, no simulator or rate limits |
| `prod` | containers | every setting from env vars, JSON logs, `forward-headers-strategy=native` |
| `demo` | add to `prod` for the live demo | seeds the PRD §10 demo data; passwords from env |

## Configuration (prod)

| Env var | Required | Meaning |
|---|---|---|
| `QG_DB_URL`, `QG_DB_USER`, `QG_DB_PASSWORD` | yes | JDBC URL and credentials |
| `QG_JWT_SECRET` | yes | base64, ≥ 32 bytes (`openssl rand -base64 48`); startup fails otherwise |
| `QG_CORS_ORIGINS` | yes | comma-separated allowlist (same-origin through nginx needs none in practice) |
| `QG_DEMO_ADMIN_PASSWORD`, `QG_DEMO_USER_PASSWORD` | with `demo` | change both on any public deployment; reapplied on each start |
| `QG_RATE_LIMIT_ENABLED` | no (default `true`) | set `false` only for automated e2e runs |
| `QG_DB_POOL_SIZE` | no (default 20) | Hikari pool size |

## Docker

```bash
docker build -t quantgen-api .
```

The build is multi-stage: Temurin 25 JDK builds the jar, which runs on `eclipse-temurin:25-jre-alpine`
as uid 10001, with Spring Boot layered extraction and a `wget` healthcheck on `/actuator/health`. The
root [`../docker-compose.yml`](../docker-compose.yml) runs it behind nginx with `prod,demo`.

**Never publish port 8080 directly.** In `prod`, `X-Forwarded-For` is trusted from private-network
addresses (the nginx container). Exposing the API itself would let clients choose the IP that rate
limits and audit logs see.

## Scheduled jobs

| Job | When | What |
|---|---|---|
| `PriceSimulator` | every 3 s | random-walk prices, store ticks, push `/topic/prices` |
| tick pruning | 03:30 IST | keep 7 days of ticks |
| `EmiAutoDebitJob` | 06:00 IST | `EMI_DUE` reminders 3 days ahead; auto-debit due EMIs (one tx each); mark `OVERDUE` and notify when that fails |

Each job takes a Postgres advisory lock, so only one instance runs it at a time.
