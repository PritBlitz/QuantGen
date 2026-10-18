# Backend handoff (2026-10-18)

State of the backend for the next session. Latest commits: V2-M0 `61f4f8d`, V2-M1 `7c2970c` `f29bb64` `84a5533`,
V2-MA (analytics, see `git log`). Test status: 30 unit + 128 integration, all green (`./mvnw verify`, JDK 25).

## Done
- **V2-M0 (backend part):** `/actuator/prometheus` (loopback/private only) with `qg.*` metrics.
- **V2-M1:** outbox + poller, holds + `availableBalance`, STOMP `/user/queue/notifications`, feature flags,
  PIN, step-up (401 `STEP_UP_REQUIRED`), limits, new-payee cooling, sessions, KYC (AES-GCM + blind index), passkeys.
- **V2-MA:** `/admin/analytics/*` (overview, timeseries, breakdown, funnel, cohorts, loan-book, adoption, health,
  export) and `/analytics/*` (summary, cashflow, counterparties, balance-history, portfolio, loans, prepayment, goals).
  Shared classification in `analytics/LedgerKinds`. Migrations V4 to V7.
- Contract notes are in `PRD.md` §12.3 ("Backend notes"); decisions in `DECISIONS.md` (D-042).

## Not done / next
1. **Tell the frontend session (quantgen-d1) that the analytics endpoints are live** (not yet messaged), with the
   deviations below, so it can drop `/admin/analytics`, `/analytics` from `NOT_YET_LIVE` in `frontend-quant/src/mocks/live.ts`.
2. **Add a "Backend notes (V2-MA)" block to PRD §12.13** with the points under "Deviations" and add a decision
   (D-043: analytics computed live from the ledger instead of a `daily_metrics` roll-up; add the roll-up when a query
   exceeds its budget). Not written yet.
3. **V2-M2 Payments 2.0** (handles, payee resolve, QR, requests, scheduled/recurring, Razorpay test top-ups, bills,
   split groups). Exit test: a Razorpay payment credits exactly once via verify and via webhook, in either order.
4. Then M3 savings/insights (categories feed `SPEND_CATEGORY`, goals feed `/analytics/goals`), M4 copilot + `agent-quant`
   (needs `agent-quant/` row in `CLAUDE.md` first), M5 Autopilot, M6 risk, M7 trading 2.0, M8 Proof of Balance
   (hashing is pinned in PRD §12.10; cross-check against `frontend-quant/src/lib/merkle.ts`), M9 cards, M10 MCP, M11 platform.
5. **Smoke-run V2-MA on a clone of the dev DB** (the V2-M1 run was done that way; V7 has only had Testcontainers so far).
   Recipe: `pg_dump` the dev DB into a scratch DB on `:5433`, run with `QG_DB_URL=... --server.port=8091`, drop it after.

## Deviations from the draft contract (tell the frontend)
- Metrics are computed live from the ledger, not from a nightly roll-up, and are not cached for 60 s yet.
- `NET_INFLOW` = deposits received (net of reversals); v1 has no outflow to the outside.
- `FAILED_MONEY_REQUESTS` counts failed POSTs to `/transfers`, `/orders`, `/accounts/{id}/deposit`, `/loans/{id}/repay`
  (stored in `request_failures` from V7). A step-up prompt is not a failure. `outboxLag` is the number of pending events.
- Suppressed breakdown rows have an opaque key (`SUPPRESSED_n`) and label; cohort retention is null and `size` is still shown
  for cohorts under 5 users (the size equals the NEW_USERS KPI, which is not suppressed).
- `topCategory` is coarse (`TRANSFERS` / `LOAN_REPAYMENTS`) until V2-M3 categories exist. `/analytics/goals` returns `[]`.
- Portfolio history comes from nightly `portfolio_snapshots` (23:55 IST) plus a live point for today, so a new install
  shows one point until snapshots accumulate. `PortfolioSnapshotJob.snapshot(day)` can be called to backfill.
- Agent tokens (🤖) do not exist yet, so the "accepts agent tokens" marker on `/analytics/*` is not implemented.

## Known gaps and cautions
- Cooling and first-transfer step-up cover **saved payees only** (spec F-SEC-5); an unsaved recipient skips both.
- Sandbox KYC auto-verifies (D-042).
- `/actuator/prometheus` relies on nginx never proxying `/actuator`; do not add that route.
- Prod needs `QG_FIELD_KEY`, `QG_INDEX_KEY`, and `QG_WEBAUTHN_RP_ID` (default `localhost`).
- Shared git index: commit with explicit paths. `backend-quant/Setup.md` has an uncommitted edit from the owner; leave it.
- The `mvn` build does not clean `target/`: delete stale files from `target/classes` if a renamed migration clashes.
