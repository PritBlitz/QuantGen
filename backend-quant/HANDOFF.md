# Backend handoff (2026-10-19, after V2-M8, M9, M6, M10 and the M4 core side)

## Resume here
1. Read `CLAUDE.md`, then this file. The frontend's state is in `frontend-quant/HANDOFF.md`; the frontend session
   was `quantgen-25` on 2026-10-19 (find it with `ListAgents`). It integrated every milestone below the same day,
   each with a live e2e on :8080.
2. Start the stack: `docker compose -f docker-compose.dev.yml up -d`, then
   `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (or run a jar copy from the scratchpad). Dev DB is at **V21**.
   All feature flags are off on :8080 (frontend e2e turns them on and back off).
3. `./mvnw verify` should show **40 unit + 216 IT** green. Don't compile while a verify runs.
4. Routine per milestone (worked well): build → targeted ITs → full `verify` → `pg_dump` clone of the dev DB
   (`qg_smoke`) → run the jar on :8099 against it plus a node smoke script → swap :8080 → commit with explicit
   paths → message the frontend with the PRD notes. When the next milestone's files are already in the tree, stash
   just those paths (`git stash push -u -- <paths>`) before building/committing the current one.
5. **Next: V2-M5 Autopilot core** (PRD 11.4.6, §12.7, ARCHITECTURE 12.9): DSL v1 validator with `RULE_INVALID`
   fieldErrors (error code exists), server rendering, 90-day replay, deterministic engine on outbox `MONEY_MOVED` +
   scheduler, idempotent on (ruleId, version, triggerEventId), caps, 10 active rules, ₹1,00,000/day autopilot cap,
   no loops (TransferSource.AUTOPILOT never fires MONEY_RECEIVED rules), three failures pause, runs, templates
   (incl. F-TRD-9 SIP), plus `AUTOPILOT_RULE` intents (`IntentKinds`). `/autopilot/compile` needs the Planner agent.
6. **Needs the owner:** `agent-quant/` (copilot SSE, agents, traces, `/admin/ai/*`, fraud/underwriting memos, the MCP
   server) belongs to a separate agent session per ARCHITECTURE.md. Don't build it without the owner's go-ahead.

## Done 2026-10-19 (session backend-quant-d2)
- **V2-M8 Proof of Balance** `15a114d`: `com.quantgen.ledger.proof` — `LedgerSealer` (2 s, advisory lock, full scan
  on first pass then 1 h lookback), `ChainVerifier`, `BalanceSnapshotService` (daily per IST date, as of the chain
  head; salts from `QG_INDEX_KEY`), `ProofJobs` (snapshot check every minute, nightly verify 00:30 IST →
  `LEDGER_INTEGRITY` to admins). Canonical encodings pinned in PRD §12.10 notes; live proofs verified with the
  frontend's own `merkle.ts`. V17.
- **V2-M9 Cards** `0d224de`: `com.quantgen.card` — encrypted PAN + blind index, HMAC CVV (current + previous 30 s
  window), controls, sandbox authorize (hold) / capture (CARD entry, settles the whole hold) / refund (REFUND entries,
  partial ok), all three 🔒💰 (contract change, agreed). Card spend in insights by merchant category. V18,
  `CARD_CLEARING`.
- **V2-M6 risk (rules)** `82ee419`: `com.quantgen.risk` — `RiskService` rules/weights (PRD §12.8 notes), threshold
  60, `qg.risk.enabled` (off in the test profile; `RiskIT` turns it on), PENDING_REVIEW transfers with a
  FRAUD_REVIEW hold, admin case queue (release/reject 🔒💰), card "Suspected fraud", loan documents on disk
  (`QG_LOAN_DOCS_DIR`, sniffed, ≤ 5 MB, ≤ 3), underwriting PENDING until the agent. V19. Frontend added the nginx
  6m route and the `loandocs` volume.
- **V2-M10 tokens** `b2ade2e` (+ kill switch in `660de61`): `PersonalAccessTokenService`, `PatAuthenticationFilter`
  (GET allowlist; `read+propose` adds POST /intents; everything else 403; audit on every use; `mcp` flag off → 503). V20.
- **V2-M4 core: intents** `660de61`: `com.quantgen.intent` — TRANSFER, GOAL_TRANSFER, ORDER (market), FD_BOOKING;
  server-rendered cards; approve 🔒💰🛡️ with FAILED + failureCode for business refusals; 15 min expiry; MCP_CLIENT
  agent name for token proposals. V21. New error codes INTENT_EXPIRED, RULE_INVALID.

## Known gaps added 2026-10-19
- M8: no chain checkpoints (always verifies from genesis); F-POB-6 anchoring not done; snapshots of a fresh DB are
  taken ~20 s after startup.
- M9: no disputes (F-CARD-4, P2); no auto-capture job; card spends don't count toward transfer limits; failed CVV
  attempts are only rate-limited (20/min per user), no per-card lockout.
- M6: bill payments aren't scored; a request paid by a held transfer is marked PAID while the transfer waits;
  `TRANSFER_REVIEW_OPENED` outbox events have no consumer until agent-quant.
- M10/M4: no delegated agent tokens (D-022) yet; intents for BILL_PAYMENT, SCHEDULED_TRANSFER, PAYMENT_REQUEST,
  LOAN_APPLICATION, AUTOPILOT_RULE answer 400 on `kind`.
- `QG_INDEX_KEY` now also derives proof salts and card CVV/PAN lookups: rotating it breaks card lookups and old
  proofs' salts (stored salts keep old proofs verifiable).

## Done (earlier)
- **V2-M7 Trading 2.0** (commit after `d737264`, see `git log`): limit/stop-loss orders (open BUY = cash hold, open
  SELL = `holdings.reserved_qty`), matched after every tick by `OrderBook` (own tx per order; order row locked first),
  cancel, expiry job, watchlists, price alerts (`PRICE_ALERT`), deterministic simulated-daily-price backtests,
  FIFO tax P&L (JSON/CSV). Migration V16. Notes in PRD §12.9 "Backend notes (V2-M7)". Also fixed: category lookup in
  every transfer used an all-time range (100 parallel transfers went 10 s → 50 s on a big DB); a flaky forged-signature
  assertion in TopupsIT.
- **V2-M3 savings + insights** (`d737264`): goals, round-ups, FDs + maturity/renewal/closure, savings interest,
  categories + overrides, spending, budgets + alerts, net worth, forecast, real `/analytics/goals`. Notes in PRD
  §12.5 "Backend notes (V2-M3 parts 1–4)". Also fixed the outbox poller, which had never run a consumer
  (NESTED unsupported; FOR UPDATE claim deadlocked the consumer_offsets FK).
- **V2-M0 (backend part):** `/actuator/prometheus` (loopback/private only) with `qg.*` metrics.
- **V2-M1:** outbox + poller, holds + `availableBalance`, STOMP `/user/queue/notifications`, feature flags,
  PIN, step-up (401 `STEP_UP_REQUIRED`), limits, new-payee cooling, sessions, KYC (AES-GCM + blind index), passkeys.
- **V2-MA:** `/admin/analytics/*` and `/analytics/*`, computed live (D-043). Notes in PRD §12.13.
- **V2-M2 Payments 2.0:** handles + `GET /users/me/handle`, `/payees/resolve` (30/min per user), transfers by
  handle (`TransferDto.source`), payment requests (pay/decline/block, 7-day expiry), scheduled transfers (07:00 IST job,
  retry once then MISSED, step-up at creation above ₹50k), simulated bills (`BILLER_CLEARING`, fictional billers),
  split groups (server-side shares, settle-up via requests), Razorpay test-mode top-ups (`GATEWAY_CLEARING`, verify +
  HMAC webhook, exit test green). All contract details are in PRD §12.4 "Backend notes (V2-M2 …)" and §7.9
  "Backend notes". Frontend (quantgen-27) was told about every group and flipped parts 1–4 live; it decides whether
  to flip top-ups (dev has no Razorpay keys, so they answer 503).

## Not done / next
1. See "Resume here". Then M4 copilot + `agent-quant` (needs an `agent-quant/` row in `CLAUDE.md` first). The frontend's expectations
   (sent 2026-10-18): SSE frames `event: <type>
data: <json>`, errors before the stream are normal §7.1 JSON
   (503 FEATURE_DISABLED, 429 BUDGET_EXHAUSTED, 404), mid-stream `event: error {code, message}`, both USER and
   ASSISTANT messages persisted *before* the stream closes, `/intents/{id}/approve` retried with the same key (+
   `X-Step-Up-Token` for INTENT_APPROVE), 200 with status FAILED + failureCode for business refusals.
   Then M5 Autopilot, M6 risk, M7 trading 2.0, M8 Proof of Balance (hashing pinned in PRD §12.10; cross-check against
   `frontend-quant/src/lib/merkle.ts`), M9 cards, M10 MCP, M11 platform.
   For M8 the frontend asked (2026-10-18) that `GET /public/proofs/roots` be `permitAll` (the landing page may call it
   signed out); its UI calls `GET /proofs/accounts/{id}?date=YYYY-MM-DD` for any date in the roots list and treats
   `404` as "not in that snapshot" (account opened later).

## Known gaps and cautions
- V2-M7: backtests use simulated daily closes (not stored ticks); no notification on a limit fill; a fill that
  fails (e.g. account frozen) stays OPEN and retries next tick until it expires.
- V2-M3: the D-020 model classifier for notes isn't used (keywords only); the forecast pools all everyday accounts
  and has no Autopilot items yet; net worth counts FD principal only; salary detection can't be integration-tested
  (ledger rows can't be backdated).
- Cooling and first-transfer step-up cover **saved payees only** (spec F-SEC-5); an unsaved recipient (including
  one paid by handle or via a payment request) skips both.
- No unblock endpoint for blocked requesters, no saved billers, no way to leave a split group (none are in §12.4).
- Scheduled runs skip step-up by design (`TransferService.execute(..., preAuthorized=true)`); creation is stepped up.
- A captured top-up for an account that was **closed** since can't be credited (the posting guard refuses); the
  webhook then fails and Razorpay retries. No account-closing endpoint exists yet, so this can't happen today.
- Bill payments don't count toward the transfer limits (money stays in `BILLER_CLEARING`).
- Sandbox KYC auto-verifies (D-042).
- `/actuator/prometheus` relies on nginx never proxying `/actuator`; do not add that route.
- Prod needs `QG_FIELD_KEY`, `QG_INDEX_KEY`, and `QG_WEBAUTHN_RP_ID` (default `localhost`); Razorpay needs
  `QG_RAZORPAY_KEY_ID` / `_KEY_SECRET` / `_WEBHOOK_SECRET` and the `razorpay` flag on (local webhooks need a tunnel, §7.9).
- Shared git index: commit with explicit paths. `backend-quant/Setup.md` has an uncommitted edit from the owner; leave it.
- The `mvn` build does not clean `target/`: delete stale files from `target/classes` if a renamed migration clashes.
