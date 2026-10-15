# Backend Session Brief — `backend-quant/`

You are the **backend engineer** for QuantGen. A separate Claude Code session builds the
React frontend in `../frontend-quant/` at the same time. Read this whole file, then
**read `../PRD.md` fully** before writing any code. `../CLAUDE.md` (security and money
rules, plus the "think before you code" checklist) loads automatically; follow it on every
feature. The PRD is the spec, and §7 of it is
the API contract the frontend is coding against.

## 1. Ground rules
1. **Only edit files inside `backend-quant/`.** Never touch `../frontend-quant/`. You may
   edit `../PRD.md` only to change the API contract, and only following rule 3.
2. **The contract in PRD §7 is law.** Implement paths, field names, enums, status codes
   and the error body exactly as written. Money is a JSON **string**; ids are UUIDs.
3. **Changing the contract:** if you must change it, first edit PRD §7, then tell the
   frontend session (see §8 below) what changed and why. Never change it silently.
4. **Git:** commit only files under `backend-quant/`, in small commits named like
   `backend: <what>` (e.g. `backend: add ledger posting service`). Don't push and don't
   rewrite history; the user does that.
5. Work milestone by milestone (§6). Don't start the next milestone until the current
   one's tests pass.

## 2. Environment
- **JDK:** use **Java 25 (LTS)**. Install Eclipse Temurin 25 and set `JAVA_HOME`.
  IntelliJ has also downloaded `openjdk-26` to `~/.jdks/openjdk-26`. It may work, but
  Lombok and some plugins lag behind non-LTS releases, so prefer 25. Run `java -version`
  first and tell the user if no JDK is on the PATH.
- **Build:** Maven, through the **Maven wrapper** (`./mvnw`); no global Maven needed.
- **Docker:** installed. Run Postgres from `backend-quant/docker-compose.dev.yml`.
- **Shell:** Windows, using Git Bash or PowerShell. Use `./mvnw` in Bash and `.\mvnw.cmd`
  in PowerShell.
- `.idea/` already exists from IntelliJ. Leave it alone; the root `.gitignore` keeps it out of Git.
- JDK 25 is already installed at `C:\Users\ASUS\.jdks\temurin-25.0.4.1`. If `java -version`
  fails in your shell, the terminal predates the `JAVA_HOME` change: restart the IDE.

## 3. Scaffold (milestone M0)
Generate the project **into this folder** with Spring Initializr:

```bash
curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d groupId=com.quantgen -d artifactId=backend-quant -d name=backend-quant \
  -d packageName=com.quantgen \
  -d dependencies=web,security,data-jpa,validation,postgresql,flyway,actuator,websocket,lombok,testcontainers \
  -o scaffold.zip
```
Unzip it into this folder, then delete the zip. Leave `bootVersion` unset so Initializr
picks the current stable Spring Boot. Then add these dependencies to `pom.xml`:
- `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` (JWT)
- `org.springdoc:springdoc-openapi-starter-webmvc-ui`, in a version that matches the
  Boot major version
- `org.mapstruct:mapstruct` + its annotation processor (ordered after Lombok)
- `com.bucket4j:bucket4j_jdk17-core` (rate limiting)
- `net.logstash.logback:logstash-logback-encoder` (JSON logs)
- Test: `org.testcontainers:postgresql`, `junit-jupiter`

Create `docker-compose.dev.yml` with **postgres:16** on port `5432`: database `quantgen`,
user `quantgen`, password `quantgen` (dev only), plus a named volume.

## 4. Package layout
```
com.quantgen
├── QuantGenApplication.java
├── common/       config (security, CORS, OpenAPI, WebSocket), error handling,
│                 Money utilities, idempotency, audit, notifications, request-id filter
├── auth/         User, RefreshToken, JwtService, AuthController, UserController
├── account/      Account, AccountService, AccountController
├── ledger/       LedgerAccount, JournalEntry, LedgerLine, LedgerService (posting), reconcile
├── payment/      Transfer, Beneficiary, TransferService, controllers
├── trading/      Instrument, PriceTick, Order, Holding, PriceSimulator (@Scheduled), OrderService
├── loan/         Loan, LoanInstallment, LoanRepayment, EmiCalculator, LoanService, EmiAutoDebitJob
└── admin/        AdminController, StatsService
```
Each module has `api/` (controllers and DTOs), `domain/` (entities and repositories) and
`service/`.
**Modules depend on each other only through `service` interfaces**, never by importing
another module's repository. Everything that moves money calls
`ledger.service.LedgerService`.

## 5. Non-negotiable implementation rules
- **Ledger posting** (`LedgerService.post(JournalRequest)`) is the only code path that
  changes balances. It must:
  1. validate that the lines balance (sum of debits = sum of credits, all amounts > 0);
  2. lock all involved `ledger_accounts` rows with `SELECT … FOR UPDATE`, **in ascending
     id order** (a `@Lock(PESSIMISTIC_WRITE)` repository method);
  3. check that no customer balance goes negative, else throw `INSUFFICIENT_FUNDS`;
  4. insert the journal entry and its lines, and update the cached balances;
  5. run inside the caller's `@Transactional`.

  Journal entries and lines are **insert-only**; corrections are reversing entries.
- **Money:** `BigDecimal` everywhere, `NUMERIC(19,4)` columns, `RoundingMode.HALF_EVEN`.
  Configure Jackson to serialize `BigDecimal` as a plain string (no scientific
  notation). Accept strings on input.
- **Idempotency:** implement a single `IdempotencyService` and use it on every 🔒💰
  endpoint. Store `(user_id, key) → request_hash, response_status, response_body`. The
  same key and hash replays the stored response; the same key with a different hash
  returns `409 IDEMPOTENCY_CONFLICT`. Insert the key row in the **same transaction** as
  the money movement.
- **Errors:** one `@RestControllerAdvice` that produces exactly the PRD §7.1 error body
  and codes. Never leak stack traces.
- **Security:** stateless; read the JWT secret from env `QG_JWT_SECRET` (with a dev
  default in `application-dev.yml` only). The refresh cookie follows PRD F-AUTH-2/3.
  `/api/v1/admin/**` requires `ADMIN`. **Check ownership in services**: a customer can
  only access their own accounts, orders and loans, and must get `404` (not `403`) for
  anyone else's.
- **Flyway:** migrations go in `src/main/resources/db/migration`, named `V1__init.sql`,
  `V2__…`. Never edit a migration that has already been applied; add a new one. Seed
  system ledger accounts and instruments in migrations. Seed demo users and data **only**
  in the `dev` profile (use a `CommandLineRunner` with `@Profile("dev")`).
- **Config:** `application.yml` holds the shared settings; `application-dev.yml` points at
  localhost Postgres; `application-prod.yml` reads everything from env vars. Server port
  is `8080`. CORS allows `http://localhost:5173`.
- **Observability:** Actuator health; a filter that sets or echoes `X-Request-Id` and
  puts it into MDC; JSON logs in prod.

## 6. Milestones (match PRD §10)
| # | Build | Required tests |
|---|---|---|
| M0 | Scaffold, compose, Flyway `V1`, health, Swagger, error handler, request-id filter | App context loads against Testcontainers Postgres |
| M1 | Auth: register, login, refresh rotation and reuse detection, logout, `/users/me`, login lockout, auto-created savings account | Auth flow integration test; refresh-reuse test |
| M2 | Accounts, ledger, deposit, transfers, beneficiaries, history, CSV statement, idempotency, reconcile endpoint | **Concurrency test:** 100 parallel transfers → exact balances, nothing negative, ledger sums to 0. Idempotency replay/conflict tests |
| M3 | Instruments, price simulator, `/ws` STOMP `/topic/prices`, orders, holdings, portfolio P&L, history | Buy/sell atomicity; oversell rejected; P&L math |
| M4 | Loan quote, apply, admin approve/reject, disbursal, schedule, repay, daily auto-debit job, overdue | EMI math (schedule sums correctly); full loan lifecycle |
| M5 | Admin endpoints, audit log, notifications, Bucket4j on `/auth/**` | Role checks; non-owners get 404 |
| M6 | Multi-stage `Dockerfile` (JRE runtime, non-root user), `application-prod.yml`, README section | Image builds and runs against compose Postgres |

After each milestone: run `./mvnw verify`, check Swagger at
`http://localhost:8080/swagger-ui.html`, commit, and tell the frontend session which
endpoints are now live.

## 7. Useful commands
```bash
docker compose -f docker-compose.dev.yml up -d          # start Postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # run the API on :8080
./mvnw test                                             # unit tests
./mvnw verify                                           # unit + integration (Testcontainers)
curl localhost:8080/actuator/health
```

## 8. Coordinating with the frontend session
- Both sessions run on this machine and can message each other. Use the `ListAgents` tool
  to find the frontend session, then `SendMessage` to it. Keep messages short and
  factual, for example: *"M1 live: /auth/* and /users/me match PRD §7.2. Refresh cookie is
  path=/api/v1/auth."*
- Send a message when: a milestone's endpoints are live; you change the contract (after
  editing PRD §7); or you find a contract ambiguity. For an ambiguity, propose a
  resolution rather than just asking.
- The frontend may message you with bugs or contract questions. Fix bugs in your module,
  and answer questions by pointing at PRD §7.
- If messaging isn't available, leave a dated note in `backend-quant/HANDOFF.md` and tell
  the user.

## 9. Definition of done (per feature)
The endpoint matches PRD §7 exactly · it has validation and ownership checks · errors
use the standard body · there is a test · it appears in Swagger · it's committed.
