# Backend handoff (2026-10-18, after V2-M2)

State of the backend for the next session. Latest commits: V2-M2 `807e831` (handles, resolve, requests), `9bc5dba`
(scheduled), `a5101f5` (bills), `f3cd38c` (split groups), `81487e2` (Razorpay top-ups). Test status: 30 unit + 158
integration, all green (`./mvnw verify`, JDK 25). Migrations V8–V12 were smoke-run on a clone of the dev DB, and the
dev DB on :5433 is now at **V12**. :8080 runs the current build from a jar copy in this session's scratchpad (dev
profile); restart it from `target/` after the next build.

## Done
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
1. **V2-M3 savings/insights** (§11.4.4, §12.5): GOAL / FIXED_DEPOSIT accounts (D-037: outside the 3-account cap),
   round-ups, FDs + interest accruals (`INTEREST_EXPENSE` system account), categories + overrides + budgets,
   `/insights/*`, net worth, forecast. Then replace the coarse `topCategory` / `SPENDING_CATEGORY` and make
   `/analytics/goals` real. `POST /accounts {type: GOAL}` returns 400 today; the frontend expects that until M3.
2. Then M4 copilot + `agent-quant` (needs an `agent-quant/` row in `CLAUDE.md` first). The frontend's expectations
   (sent 2026-10-18): SSE frames `event: <type>
data: <json>`, errors before the stream are normal §7.1 JSON
   (503 FEATURE_DISABLED, 429 BUDGET_EXHAUSTED, 404), mid-stream `event: error {code, message}`, both USER and
   ASSISTANT messages persisted *before* the stream closes, `/intents/{id}/approve` retried with the same key (+
   `X-Step-Up-Token` for INTENT_APPROVE), 200 with status FAILED + failureCode for business refusals.
   Then M5 Autopilot, M6 risk, M7 trading 2.0, M8 Proof of Balance (hashing pinned in PRD §12.10; cross-check against
   `frontend-quant/src/lib/merkle.ts`), M9 cards, M10 MCP, M11 platform.

## Known gaps and cautions
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
