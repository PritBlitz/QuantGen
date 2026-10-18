# QuantGen — Decision Log

Every meaningful design or product decision, the options we weighed, and the price we pay
for the one we picked. Read this before re-opening a settled question; add to it whenever
you make a new one.

- **Scope and API contract:** [`PRD.md`](PRD.md). **How it's built:** [`ARCHITECTURE.md`](ARCHITECTURE.md).
  **How to work here:** [`FLOW.md`](FLOW.md). **Rules:** [`CLAUDE.md`](CLAUDE.md).
- Status values: **Accepted** (built or agreed), **Proposed** (written down, waiting for the
  owner's OK), **Superseded** (replaced; see the newer entry), **Rejected** (kept so nobody
  re-proposes it without new facts).

## How to add a decision
1. Take the next number. Never renumber or delete an entry; supersede it instead.
2. Copy the template below and fill in every field. "Trade-off" is mandatory: if you can't
   name a downside, you haven't thought about it enough.
3. If it changes the API, also follow the contract-change protocol in `CLAUDE.md`.
4. Add a row to the index.

```markdown
### D-0XX · Title
- **Date / status:** 2026-MM-DD · Proposed | Accepted | Superseded by D-0YY | Rejected
- **Context:** what problem forced a choice
- **Options:** A, B, C (one line each)
- **Decision:** what we picked
- **Trade-off:** what we give up, and what it costs us
- **Revisit when:** the signal that should make us reconsider
```

## Index
| # | Decision | Status |
|---|---|---|
| D-001 | Modular monolith for the core | Accepted |
| D-002 | PostgreSQL as the only system of record | Accepted |
| D-003 | Double-entry, insert-only ledger | Accepted |
| D-004 | Pessimistic row locks in ascending id order | Accepted |
| D-005 | Idempotency keys in the DB, same transaction | Accepted |
| D-006 | Access token in memory + rotating refresh cookie | Accepted |
| D-007 | Simulated market data and funding in v1 | Accepted (partly superseded by D-016) |
| D-008 | Money as JSON strings | Accepted |
| D-009 | Dev Postgres in Docker on :5433 | Accepted |
| D-010 | Another user's resource returns 404, not 403 | Accepted |
| D-011 | Simple in-process STOMP broker for prices | Accepted (revisit at V2-M11) |
| D-012 | Partial loan repayments + `amountDue` | Accepted |
| D-013 | Demo seed as a separate `demo` profile | Accepted |
| D-014 | PDF statements left as a stretch goal | Accepted |
| D-015 | v1 declared feature-complete; close-out items move to V2-M0 | Proposed |
| D-016 | Razorpay (test mode) for gateway top-ups, not Stripe | Accepted (built in V2-M2) |
| D-017 | Agents never move money: they propose *intents* | Proposed |
| D-018 | Separate `agent-quant` service, in Java | Proposed |
| D-019 | Self-hosted agent loop on the Claude API, not Managed Agents | Proposed |
| D-020 | Model tiers: Opus 5.5 for reasoning, Haiku 5.5 for bulk work | Proposed |
| D-021 | Orchestrator + specialist agents ("agents as tools") | Proposed |
| D-022 | Delegated, scoped agent tokens | Proposed |
| D-023 | Intent summaries rendered by the server, never by the LLM | Proposed |
| D-024 | Autopilot: LLM at authoring time, deterministic engine at run time | Proposed |
| D-025 | Proof of Balance: hash-chained seals + daily Merkle sum tree | Proposed |
| D-026 | Seal the ledger asynchronously in a separate table | Proposed |
| D-027 | Holds and "available balance" outside the ledger lines | Accepted |
| D-028 | Transactional outbox; Redpanda only for cross-service consumers | Accepted |
| D-029 | Step-up auth: passkeys first, transaction PIN as fallback | Accepted |
| D-030 | Fraud: deterministic rules decide, the LLM only explains | Proposed |
| D-031 | Underwriting agent recommends, the admin decides | Proposed |
| D-032 | Field-level encryption (AES-256-GCM) + blind index for KYC and card data | Accepted |
| D-033 | GitHub Actions only; drop Jenkins from the roadmap | Proposed |
| D-034 | Idempotency stays in Postgres; Redis only for rate limits and caches | Proposed |
| D-035 | Market data stays simulated in v2; live crypto feeds are a stretch | Proposed |
| D-036 | No real payouts / withdrawals to external banks | Proposed |
| D-037 | Goals and fixed deposits are ledger accounts, outside the 3-account cap | Proposed |
| D-038 | Copilot chat streams over SSE, prices stay on STOMP | Proposed |
| D-039 | LLM spend controls: per-user budgets + global kill switch | Proposed |
| D-040 | Analytics on Postgres with a nightly roll-up, no warehouse | Accepted |
| D-041 | v2 approved; frontend redesign "Signal" | Accepted |
| D-042 | Sandbox KYC, reusable step-up tokens, keyed PIN hashes | Accepted |
| D-043 | Analytics computed live; the nightly roll-up waits for a slow query | Accepted |

---

## v1 decisions

### D-001 · Modular monolith for the core
- **Date / status:** 2026-10 (v1 start) · Accepted
- **Context:** banking, trading and loans all move money through one ledger.
- **Options:** microservices with sagas; a modular monolith; a plain layered monolith.
- **Decision:** one Spring Boot deployable split into modules (`auth`, `account`, `ledger`,
  `payment`, `trading`, `loan`, `admin`, `common`) that talk only through service interfaces.
- **Trade-off:** module boundaries are enforced by discipline, not by the network. One
  slow module can slow the whole process. Scaling is vertical.
- **Revisit when:** a module needs an independent release cadence or a different runtime.
  v2 extracts only the AI layer (D-018); the ledger stays.

### D-002 · PostgreSQL as the only system of record
- **Date / status:** 2026-10 · Accepted
- **Context:** we need ACID, row locks, exact decimals, constraints and triggers.
- **Options:** PostgreSQL, MySQL, a document store.
- **Decision:** PostgreSQL 17, schema owned by Flyway.
- **Trade-off:** vertical scaling only; fine at demo scale.
- **Revisit when:** sustained write load exceeds one primary (not expected for this project).

### D-003 · Double-entry, insert-only ledger
- **Date / status:** 2026-10 · Accepted
- **Context:** money must never be created or lost, and every change must be explainable.
- **Options:** a `balance` column updated in place; double-entry journal + lines.
- **Decision:** double-entry. Rows are never updated or deleted (DB triggers enforce it);
  mistakes are fixed with reversing entries.
- **Trade-off:** more rows and more code than a balance column; reports must aggregate lines.
- **Revisit when:** never for correctness; history tables may be partitioned later.

### D-004 · Pessimistic row locks in ascending id order
- **Date / status:** 2026-10 · Accepted
- **Context:** concurrent transfers on the same account must not double-spend.
- **Options:** `SERIALIZABLE` + client retries; optimistic versions; `SELECT … FOR UPDATE`
  in a fixed order.
- **Decision:** lock every involved ledger account `FOR UPDATE`, sorted by id.
- **Trade-off:** writes to one hot account are serialized (e.g. `BANK_CASH` during a burst
  of deposits). Deadlock-free and needs no client retries.
- **Revisit when:** a system account becomes a measured bottleneck. Fix then: sharded
  system sub-accounts, not a different isolation level.

### D-005 · Idempotency keys in the DB, same transaction
- **Date / status:** 2026-10 · Accepted
- **Context:** double-clicks and retries after timeouts must not move money twice.
- **Options:** Redis cache; DB table in a separate transaction; DB table in the same transaction.
- **Decision:** `idempotency_keys` row claimed and completed in the same transaction as the
  money movement. Same key + same body replays; same key + different body → `409`.
- **Trade-off:** one extra row per money request; concurrent duplicates wait on a unique index.
- **Revisit when:** never; see D-034.

### D-006 · Access token in memory + rotating refresh cookie
- **Date / status:** 2026-10 · Accepted
- **Context:** tokens in `localStorage` can be stolen by any XSS.
- **Options:** session cookies; JWT in storage; JWT in memory + HttpOnly refresh cookie.
- **Decision:** 15-minute access JWT held only in memory; 7-day refresh token in an
  `HttpOnly; Secure; SameSite=Strict` cookie scoped to `/api/v1/auth`, rotated on every use,
  with family revocation on reuse.
- **Trade-off:** every page reload costs a `/auth/refresh` call; multi-tab needs care.
- **Revisit when:** passkeys (D-029) make passwordless login the default; the session model stays.

### D-007 · Simulated market data and funding in v1
- **Date / status:** 2026-10 · Accepted (funding part superseded by D-016 in v2)
- **Context:** real feeds and payment rails need keys, KYC, money and legal cover.
- **Decision:** a random-walk price simulator and a "demo deposit" from `BANK_CASH`.
- **Trade-off:** prices aren't real, so backtests and P&L are illustrative only.
- **Revisit when:** see D-016 (top-ups) and D-035 (market data).

### D-008 · Money as JSON strings
- **Date / status:** 2026-10 · Accepted
- **Decision:** `"1250.50"` on the wire, `BigDecimal` / `NUMERIC(19,4)` on the server, never
  arithmetic on JS `number` in the browser.
- **Trade-off:** the client needs a decimal library for anything beyond display.

### D-009 · Dev Postgres in Docker on :5433
- **Date / status:** 2026-10 · Accepted
- **Context:** a native PostgreSQL 18 Windows service already owns `:5432` on the dev machine.
- **Decision:** Postgres 17 in `docker-compose.dev.yml` on host port 5433. Matches prod and Testcontainers.
- **Trade-off:** Docker Desktop must be running to develop or test.

### D-010 · Another user's resource returns 404, not 403
- **Date / status:** 2026-10 · Accepted
- **Decision:** ownership is checked in services; foreign ids look exactly like missing ids.
- **Trade-off:** slightly harder debugging ("is it missing or not mine?"). Worth it: no id enumeration.

### D-011 · Simple in-process STOMP broker for prices
- **Date / status:** 2026-10 · Accepted
- **Decision:** Spring's simple broker on `/topic/prices`, JWT checked on `CONNECT`.
- **Trade-off:** works with one API instance only; a second replica wouldn't share subscriptions.
- **Revisit when:** V2-M11 runs more than one API replica → relay through Redis or a STOMP broker.

### D-012 · Partial loan repayments + `amountDue`
- **Date / status:** 2026-10-16 · Accepted
- **Decision:** custom repay amounts apply to the oldest dues first, interest before
  principal; schedule rows expose `amountDue` so the UI never computes it.
- **Trade-off:** one extra field and more states per instalment.

### D-013 · Demo seed as a separate `demo` profile
- **Date / status:** 2026-10-16 · Accepted
- **Decision:** `DemoDataSeeder` runs under `dev` or `demo`; compose runs `prod,demo`;
  passwords come from `QG_DEMO_*_PASSWORD` and are reapplied on start.
- **Trade-off:** a public demo resets demo passwords on every restart, by design.

### D-014 · PDF statements left as a stretch goal
- **Date / status:** 2026-10-16 · Accepted
- **Decision:** CSV is shipped; `format=pdf` is not in the contract yet.
- **Trade-off:** less polished statements. Picked up in V2-M0 if time allows.

---

## v2 decisions

### D-015 · v1 declared feature-complete; close-out items move to V2-M0
- **Date / status:** 2026-10-18 · Proposed
- **Context:** an audit on 2026-10-18 (PRD §11.0) found every v1 functional requirement
  implemented and tested, but three delivery items not done: CI has never run on GitHub
  (nothing has been pushed), there is no public deployment, and `ARCHITECTURE.md` §10
  promised a Prometheus endpoint that the code doesn't expose.
- **Options:** hold v2 until they're done; fold them into the first v2 milestone.
- **Decision:** fold them into **V2-M0 (close-out)**, which must finish before V2-M1 starts.
- **Trade-off:** "v1 done" is true for features, not yet for M6's "deployed" clause. We say so plainly.
- **Revisit when:** V2-M0 is done; then mark v1 shipped.

### D-016 · Razorpay (test mode) for gateway top-ups, not Stripe
- **Date / status:** 2026-10-16 · Accepted, built in V2-M2 on 2026-10-18 (full spec: PRD §7.9)
- **Context:** "proper payments" needs a real checkout, not only the demo deposit.
- **Options:** Stripe; Razorpay; Cashfree; keep only the simulator.
- **Decision:** Razorpay in **test mode**: order → Checkout → server-side signature check
  **and** a server-side payment fetch → ledger credit from a new `GATEWAY_CLEARING` system
  account. The signed webhook is the backup path; whichever arrives first credits.
- **Trade-off:** a third-party dependency, a public tunnel for local webhooks, and CSP
  exceptions for Razorpay's script and frames. Stripe is invite-only for new Indian accounts.
- **Revisit when:** Razorpay test mode changes terms, or we want non-INR.

### D-017 · Agents never move money: they propose *intents*
- **Date / status:** 2026-10-18 · Proposed
- **Context:** LLMs can be wrong, and they can be manipulated by text they read (prompt
  injection through a transfer note, a beneficiary nickname or an uploaded PDF).
- **Options:** let the agent call transfer endpoints with the user's token; let it act and
  allow an undo; make it propose and let the human approve.
- **Decision:** an agent can only create an **intent** (`PROPOSED`, expiring, validated by
  the server). Only the user, through the normal UI and an `Idempotency-Key`, can approve it,
  with step-up auth above a threshold. Approval runs the same service code as a manual action.
- **Trade-off:** one extra tap for every agent action; "fully autonomous agent" demos aren't
  possible. That's the point. Autonomy lives in Autopilot rules (D-024), which the user
  approves once and which run deterministically.
- **Revisit when:** never for money. Read-only and notification actions may run without approval.

### D-018 · Separate `agent-quant` service, in Java
- **Date / status:** 2026-10-18 · Proposed (**owner to confirm the language**)
- **Context:** the AI layer has different failure modes (slow, costly, non-deterministic)
  and a different risk profile from the ledger. It should be unable to touch the DB.
- **Options:** (A) an `agent` module inside the monolith; (B) a separate Java/Spring Boot
  service; (C) a separate Python/FastAPI service.
- **Decision:** (B). Its own deployable and its own folder (`agent-quant/`), owned by a new
  **agent session**. It has **no database credentials for the core DB**; it talks to the core
  only over HTTP with delegated tokens (D-022). Java because the official Anthropic Java SDK
  supports tool use, the tool runner and structured outputs, and one language keeps the
  toolchain, CI and code review simpler for a solo developer.
- **Trade-off:** Python has the richer ecosystem for evals and notebooks, and more agent
  examples online. A second service also adds a network hop, a second image and service-to-service auth.
- **Revisit when:** eval tooling in Java becomes the bottleneck → evals alone can move to Python scripts.

### D-019 · Self-hosted agent loop on the Claude API, not Managed Agents
- **Date / status:** 2026-10-18 · Proposed
- **Context:** Anthropic offers a hosted agent runtime (Managed Agents) and a client-side
  tool runner in the SDK.
- **Options:** Managed Agents; SDK tool runner / own loop; a third-party framework (LangChain etc.).
- **Decision:** our own orchestration on top of the Messages API (tool runner where it fits,
  a manual loop where we need approval gates). Every tool call executes inside `agent-quant`,
  so we own authorization, audit and tracing of each step.
- **Trade-off:** we write and maintain the loop, retries, context management and traces ourselves.
  No framework means no free integrations, but also no heavy dependency (CLAUDE.md: prefer few, well-known deps).
- **Revisit when:** we need long-running sandboxes (e.g. code execution for backtests) that Managed Agents gives for free.

### D-020 · Model tiers: Opus 5.5 for reasoning, Haiku 5.5 for bulk work
- **Date / status:** 2026-10-18 · Proposed (**owner to confirm; this is a cost decision**)
- **Context:** model choice drives quality and cost. Pricing checked 2026-10-18: Opus 5.5
  $4/$20, Sonnet 5.5 $2/$10, Haiku 5.5 $0.10/$0.50 per million input/output tokens.
- **Decision:** `claude-opus-5-5` for the orchestrator, Autopilot compiler, underwriting
  and fraud case memos (low volume, high stakes). `claude-haiku-5-5` for high-volume, simple
  jobs: transaction categorization fallback, notification copy, intent-title suggestions.
  Model ids live in config, not code, so tiers can change without a release.
- **Trade-off:** two models mean two prompt-cache namespaces and two sets of evals. Opus on
  every chat turn costs more than Sonnet; we accept that for answer quality and measure it.
- **Revisit when:** evals show Sonnet 5.5 or Opus at `low` effort matching quality at lower cost per finished task.

### D-021 · Orchestrator + specialist agents ("agents as tools")
- **Date / status:** 2026-10-18 · Proposed
- **Context:** one giant prompt with every tool gets confused and is hard to secure.
- **Options:** single agent with all tools; peer agents passing messages; one orchestrator
  calling specialists as tools.
- **Decision:** a **Concierge** orchestrator that delegates to specialists (Ledger Analyst,
  Payments, Markets, Planner, Loan Advisor), each with its own short prompt and a **narrow
  tool allowlist**. Back-office agents (Fraud Investigator, Underwriting Analyst) are
  separate, event-driven and admin-facing. Detail: ARCHITECTURE §14.
- **Trade-off:** more model calls per user turn (latency, cost); routing mistakes are possible.
  Gains: smaller blast radius per agent, easier evals per specialist.
- **Revisit when:** evals show routing errors > 5%, or latency p95 > 8 s for a simple question.

### D-022 · Delegated, scoped agent tokens
- **Date / status:** 2026-10-18 · Proposed
- **Context:** `agent-quant` must act *as the user* but with less power than the user.
- **Decision:** the core mints a short-lived JWT (`aud=agent`, `sub=userId`,
  `act=agent-quant`, `scope=agent:read agent:propose`, 15 min) per conversation turn and passes
  it to `agent-quant` over the internal network. Spring Security rejects agent tokens on
  every endpoint that is not explicitly agent-allowed (deny by default), and on all
  money-executing and intent-approving endpoints, always.
- **Trade-off:** another token type to test and rotate; service-to-service mTLS or a shared
  secret is needed between core and agent service.
- **Revisit when:** we expose the MCP server to third-party clients → move to OAuth 2.1 with consent screens.

### D-023 · Intent summaries rendered by the server, never by the LLM
- **Date / status:** 2026-10-18 · Proposed
- **Context:** if the model writes "Pay ₹500 to Mom" but the payload says ₹50,000 to a
  stranger, the user approves the wrong thing.
- **Decision:** the approval card shows a summary the **core** renders from the validated
  payload (resolved holder name, masked account, exact amount, fees, limits left). The
  model's text is shown separately and labelled as the assistant's explanation.
- **Trade-off:** less natural phrasing on the card. Correctness wins.

### D-024 · Autopilot: LLM at authoring time, deterministic engine at run time
- **Date / status:** 2026-10-18 · Proposed — **signature feature**
- **Context:** users want "set and forget" money automation, but an LLM in the execution
  path is non-deterministic, slow, costly and injectable.
- **Options:** LLM agent watches events and decides each time; hand-built rule forms only;
  LLM compiles natural language into a rule that a deterministic engine runs.
- **Decision:** the Planner agent compiles plain language ("when salary lands, move 20% to
  my Goa goal and pay my EMI") into a **typed JSON rule** (trigger → conditions → actions,
  with caps). The core validates it, **dry-runs it against the user's last 90 days of real
  ledger history**, and shows the result. The user approves once (step-up). After that, a
  deterministic engine in the core runs it on ledger events, with no LLM involved.
- **Trade-off:** rules are limited to the DSL's vocabulary (no free-form "do something smart").
  The DSL needs careful versioning.
- **Revisit when:** users keep asking for things the DSL can't express → extend the DSL, never the runtime.

### D-025 · Proof of Balance: hash-chained seals + daily Merkle sum tree
- **Date / status:** 2026-10-18 · Proposed — **signature feature**
- **Context:** "trust us" is the weakest part of any bank. Crypto exchanges popularised
  proof-of-reserves; no retail bank demo lets a customer verify their own balance.
- **Decision:** (1) every journal entry is sealed into a SHA-256 hash chain, so any edit to
  history breaks the chain; (2) every night, a **Merkle sum tree** over all customer balances
  produces a published root and total liabilities; (3) any customer can fetch their inclusion
  proof and **verify it in the browser** (WebCrypto), without trusting the server's maths.
- **Trade-off:** proves *consistency and inclusion*, not that reserves exist in the real world
  (they're simulated). A sum tree leaks partial sums along a path; salted leaves and padding
  limit that. Extra storage and a nightly job.
- **Revisit when:** we want external anchoring (publish roots to a public Git repo or a timestamping service).

### D-026 · Seal the ledger asynchronously in a separate table
- **Date / status:** 2026-10-18 · Proposed
- **Context:** a hash chain needs each entry to know the previous hash. Doing that inside
  `LedgerService.post` means one global lock on every posting.
- **Decision:** a `LedgerSealer` job assigns chain positions **after commit**, in batches,
  writing to an insert-only `ledger_seals` table. `journal_entries` stay untouched (still immutable).
- **Trade-off:** a few seconds where the newest entries aren't yet sealed; the proof covers
  "everything up to seal N". No throughput cost on the money path.

### D-027 · Holds and "available balance" outside the ledger lines
- **Date / status:** 2026-10-18 · Accepted (built in V2-M1, 2026-10-18)
- **Context:** card authorizations, open limit-buy orders, fraud reviews and scheduled
  payments need money earmarked but not yet moved.
- **Options:** post to a "pending" ledger account and reverse later; an earmark table checked by the ledger.
- **Decision:** a `holds` table. Available balance = ledger balance − active holds.
  `LedgerService.post` checks *available* balance for debits, with the hold rows locked under
  the same ledger-account row lock. Capturing a hold posts the real entry and closes the hold
  in one transaction.
- **Trade-off:** two balance numbers in the UI ("balance" vs "available"), and every debit
  path must respect holds. Avoids filling the ledger with reverse-and-repost noise.

### D-028 · Transactional outbox; Redpanda only for cross-service consumers
- **Date / status:** 2026-10-18 · Accepted (built in V2-M1, 2026-10-18)
- **Context:** Autopilot, notifications, fraud scoring and insights must react to money events reliably.
- **Decision:** every ledger post writes an `outbox_events` row in the same transaction.
  In-core consumers (Autopilot, notifications, insights) read the outbox directly with
  `FOR UPDATE SKIP LOCKED`. A relay publishes to **Redpanda** only for consumers in other
  services (`agent-quant` fraud investigator) and that only arrives at V2-M6.
- **Trade-off:** at-least-once delivery, so every consumer must be idempotent (keyed by event id).
  Polling adds up to ~1 s latency.

### D-029 · Step-up auth: passkeys first, transaction PIN as fallback
- **Date / status:** 2026-10-18 · Accepted (built in V2-M1, 2026-10-18)
- **Context:** large transfers, approving agent intents, activating Autopilot rules and
  revealing card numbers need proof the human is present, not just a valid token.
- **Decision:** WebAuthn passkeys (Yubico `java-webauthn-server` on the server,
  `@simplewebauthn/browser` on the client) for passwordless login and step-up. A 6-digit
  transaction PIN (BCrypt, lockout after 5 tries) for devices without passkeys. Step-up
  yields a 5-minute token bound to the user and the action.
- **Trade-off:** two new dependencies and a harder e2e test setup (virtual authenticators in Playwright). No SMS OTP (cost, SIM-swap risk).

### D-030 · Fraud: deterministic rules decide, the LLM only explains
- **Decision:** a rules + score engine in the core (velocity, new payee + large amount, odd
  hour, device change, round-amount bursts) can put a transfer in `PENDING_REVIEW` with a
  hold. The **Fraud Investigator agent** reads the case and writes a memo for the admin. The
  model never blocks, releases or reverses anything itself.
- **Trade-off:** rules miss novel patterns; false positives annoy users. The memo saves admin time.

### D-031 · Underwriting agent recommends, the admin decides
- **Decision:** customers can attach income documents to a loan application; the
  Underwriting Analyst extracts figures **with citations to the page**, flags mismatches with
  declared income, and writes a recommendation. Approve and reject stay human (F-ADM-3).
- **Trade-off:** sending documents to an LLM provider is a privacy cost: explicit consent,
  demo documents only, retention limits, no Aadhaar numbers accepted.

### D-032 · Field-level encryption + blind index for KYC and card data
- **Decision:** AES-256-GCM with a versioned key from env (`QG_DATA_KEY_V1`, later a KMS).
  A keyed HMAC "blind index" allows uniqueness checks on PAN without decrypting.
- **Trade-off:** no SQL search on encrypted fields; key rotation needs a re-encrypt job.

### D-033 · GitHub Actions only; drop Jenkins from the roadmap
- **Context:** the old roadmap listed Jenkins *and* GitHub Actions, *and* ArgoCD.
- **Decision:** GitHub Actions for CI, ArgoCD for CD into Kubernetes. Jenkins is dropped.
- **Trade-off:** one fewer tool on the CV. Running and securing a Jenkins server costs a
  solo developer far more than it teaches.
- **Revisit when:** the owner explicitly wants Jenkins experience → add it as a mirror pipeline, never the source of truth.

### D-034 · Idempotency stays in Postgres; Redis only for rate limits and caches
- **Decision:** Redis (V2-M11) holds rate-limit buckets (shared across replicas) and read
  caches (price snapshot). Idempotency keys stay in the DB transaction (D-005).
- **Trade-off:** no "fast path" for replays. Exactly-once beats a few milliseconds.

### D-035 · Market data stays simulated in v2; live crypto feeds are a stretch
- **Context:** NSE/BSE have no free, licensable real-time API.
- **Decision:** keep the simulator for equities. An optional crypto price feed from a free
  public API is a stretch goal, read-only, behind a feature flag, with simulator fallback.
- **Trade-off:** backtests on random-walk data prove the machinery, not a strategy. The UI says so.

### D-036 · No real payouts / withdrawals to external banks
- **Decision:** money can come *in* through Razorpay test mode, never go *out* to real bank accounts.
- **Trade-off:** less realistic. Payout APIs need full KYC, a current account and legal cover we don't have.

### D-037 · Goals and fixed deposits are ledger accounts, outside the 3-account cap
- **Decision:** new account types `GOAL` (up to 10) and `FIXED_DEPOSIT` are real ledger
  accounts owned by the customer, so every rupee in them is covered by the ledger invariants
  and Proof of Balance. They don't count toward F-ACC-1's limit of 3, and they can only send
  money back to the owner's own accounts.
- **Trade-off:** more ledger accounts per user; the account list UI must group them.

### D-038 · Copilot chat streams over SSE, prices stay on STOMP
- **Decision:** chat responses stream as Server-Sent Events from `POST /copilot/...`
  (fetch + ReadableStream, bearer header as usual). Live prices and user notifications use STOMP.
- **Trade-off:** two real-time mechanisms. SSE fits request/response streaming, needs no
  broker, and passes through nginx with buffering off.

### D-039 · LLM spend controls: per-user budgets + global kill switch
- **Decision:** per-user limits (30 copilot turns/hour, a daily token budget), a monthly
  global spend cap, prompt caching of system prompts and tool lists, and a feature flag that
  turns the AI layer off instantly. When it's off, the app works exactly like v1.
- **Trade-off:** heavy users hit limits. A public demo without caps is an open wallet.

### D-040 · Analytics on Postgres with a nightly roll-up, no warehouse
- **Date / status:** 2026-10-18 · Accepted (owner asked for admin + customer analytics)
- **Context:** admins need business KPIs and trends; customers need personal insights. Both
  must agree with the ledger to the paisa.
- **Options:** a separate warehouse (ClickHouse, DuckDB exports, BigQuery); live SQL only;
  live SQL + a nightly `daily_metrics` roll-up.
- **Decision:** SQL aggregates on the same Postgres, a nightly roll-up for history, live
  computation for "today", 60-second caches. Shared definitions of income/spending (PRD §11.4.14)
  so admin and customer numbers reconcile. Aggregates only; cells under 5 users suppressed.
- **Trade-off:** heavy admin queries compete with OLTP on one database (mitigated by the
  roll-up, indexes and caching). No ad-hoc BI tool.
- **Revisit when:** an analytics query shows up in the money-path p95, or we want ad-hoc
  exploration → add a read replica first, then a warehouse.

### D-041 · v2 approved; frontend redesign "Signal"
- **Date / status:** 2026-10-18 · Accepted
- **Context:** the owner asked to build v2 ("create all of that"), with the frontend session
  doing the UI and a separate backend session doing the API, and said the current UI isn't
  impressive enough.
- **Decision:** the frontend builds every v2 screen against the §12 contract with MSW mocks,
  switching each feature to the live API as the backend announces it (same hybrid mechanism as
  v1). The visual language moves from "Quiet precision" to **"Signal"** (`frontend-quant/DESIGN.md`
  v2): an ambient aurora field, glass surfaces, oversized editorial numerals, a floating
  command dock, and richer data visualisation. Money rules are unchanged (no optimistic
  balances, decimal strings only).
- **Trade-off:** the frontend runs ahead of the backend, so mock shapes can drift. That's
  controlled by keeping every mock in `src/mocks/` typed from `src/api/types.ts`, and
  checking each feature against `/v3/api-docs` when it goes live.
- **Revisit when:** a live endpoint disagrees with §12 → fix the PRD first (contract protocol).

### D-042 · Sandbox KYC, reusable step-up tokens, keyed PIN hashes
- **Date / status:** 2026-10-18 · Accepted (built in V2-M1)
- **Context:** F-SEC-2/3/6 left three details open: what "verified" means with no government API,
  whether a step-up token is single-use, and how to store a 6-digit PIN safely.
- **Decision:** (1) a well-formed KYC submission is `VERIFIED` immediately; nothing checks it against a real
  registry (a stated non-goal), and replacing details on file needs step-up. (2) A step-up token is bound to
  (user, action) and reusable for 5 minutes, because the idempotency claim, not the token, is what makes a money
  effect happen once, and a retry after a timeout must still work. (3) PINs are BCrypt over an HMAC keyed with the
  server's index key, so a leaked table cannot be brute-forced offline (only 10^6 PINs exist).
- **Trade-off:** one passkey/PIN tap authorises several risky actions of the same kind for 5 minutes. Sandbox KYC
  proves nothing about identity. Losing `QG_INDEX_KEY` invalidates every PIN (users reset via step-up or password).
- **Revisit when:** real KYC is in scope (add a `PENDING` review path), or a risky action needs per-request
  binding (bind the token to a hash of the request body).

### D-043 · Analytics computed live; the nightly roll-up waits for a slow query
- **Date / status:** 2026-10-18 · Accepted (built in V2-MA; refines D-040)
- **Context:** D-040 planned a nightly `daily_metrics` roll-up plus 60-second caches. At demo scale (hundreds of
  users, thousands of ledger lines) every analytics query runs in milliseconds straight off the ledger.
- **Options:** build the roll-up now; compute everything live and add the roll-up later; live plus caches only.
- **Decision:** compute every metric live from the ledger with the shared definitions in `analytics/LedgerKinds`.
  No roll-up table and no cache yet. Portfolio history is the one exception: market value can't be rebuilt from the
  ledger, so a nightly `portfolio_snapshots` job stores it.
- **Trade-off:** admin queries hit the OLTP tables directly, so a big date range costs a full scan of the range.
  Numbers are always exact and there is no roll-up to drift or backfill.
- **Revisit when:** any analytics endpoint exceeds its p95 budget (500 ms), or shows up in the money-path p95 →
  add the `daily_metrics` roll-up (PRD §13) for closed days, then a read replica.
