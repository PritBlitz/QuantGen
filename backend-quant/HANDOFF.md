# Backend handoff (2026-10-18, after V2-M7)

State of the backend for the next session. Latest commits: V2-M2 `807e831` (handles, resolve, requests), `9bc5dba`
(scheduled), `a5101f5` (bills), `f3cd38c` (split groups), `81487e2` (Razorpay top-ups). Test status: 30 unit + 158
integration, all green (`./mvnw verify`, JDK 25). Migrations V8–V12 were smoke-run on a clone of the dev DB, and the
dev DB on :5433 is now at **V12**. :8080 runs the current build from a jar copy in this session's scratchpad (dev
profile); restart it from `target/` after the next build.

## Resume here
1. Read `CLAUDE.md`, then this file. The frontend's state is in `frontend-quant/HANDOFF.md`; the frontend session
   was `quantgen-69` on 2026-10-18 (find it with `ListAgents`).
2. Start the stack: `docker compose -f docker-compose.dev.yml up -d`, then
   `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (or run a jar copy from the scratchpad). Dev DB is at **V16**.
3. `./mvnw verify` should show 33 unit + 182 IT green. Don't compile while a verify runs (it deletes classes mid-run).
4. Next, in this order (told to the frontend): **V2-M8 Proof of Balance** (hashing pinned in PRD §12.10; cross-check
   `frontend-quant/src/lib/merkle.ts`; `GET /public/proofs/roots` must be permitAll; `/proofs/accounts/{id}?date=` →
   404 when the account isn't in that snapshot), V2-M9 Cards, V2-M6 rules-based fraud parts, V2-M10 tokens, then the
   core side of V2-M4 (intents, delegated tokens, SSE proxy, budgets) and V2-M5 (Autopilot engine; also F-TRD-9 SIP). `agent-quant/` belongs to a
   separate agent session per ARCHITECTURE.md; ask the owner before building it.

## Done
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
