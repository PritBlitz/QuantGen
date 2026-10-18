# Frontend → Backend handoff

## 2026-10-18 (end of day): v2 frontend state, for a cold restart

**Read first:** this section, PRD §11–§13 (§12.14 DTO reference; the "Backend notes" blocks in §12.3,
§12.4, §7.9, §12.13 record every backend deviation), `src/api/types.v2.ts`, `src/mocks/live.ts`.
The backend's own list is in `backend-quant/HANDOFF.md`. Find the backend session with `ListAgents`
(its name changes; it was `backend-quant-04` today) and message it via `SendMessage`.

### Backend state (from the backend session)
V2-M0, V2-M1, V2-MA (analytics) and V2-M2 (Payments 2.0 incl. Razorpay top-ups) are **done and live
on :8080**; dev DB at migration V12. Next backend milestone: **V2-M3 savings/insights**, in this order:
(1) GOAL accounts + round-ups, (2) fixed deposits + interest, (3) categories/overrides/budgets
(`/insights/*`, real `/analytics/goals`, `SPEND_CATEGORY`), (4) net worth + forecast. The backend
pings per group. All five feature flags are **false** on :8080, so in hybrid mode Copilot, Autopilot,
Cards and Razorpay are hidden until an admin turns them on (`/admin/flags`).

### Live vs mocked (`src/mocks/live.ts`)
- **Live in hybrid mode:** all v1 (§7); V2-M1 (flags, holds/availableBalance, step-up/PIN, limits,
  sessions, KYC, passkeys); analytics `/admin/analytics/*`, `/analytics/*`; Payments 2.0
  `/users/me/handle`, `/payees/resolve`, transfers by handle, `/payment-requests`,
  `/scheduled-transfers`, `/billers`, `/bill-payments`, `/split-groups`, `/topups` +
  `/accounts/{id}/topups` (no Razorpay keys on :8080 → 503 GATEWAY_UNAVAILABLE; flag off anyway).
- **Still mocked** (`NOT_YET_LIVE` or no live prefix): `/users/me/tokens`, `/accounts/{id}/round-up`,
  `/accounts/{id}/cards`, `/loans/{id}/documents`, `/orders/{id}/cancel`, `/admin/fraud-cases`,
  `/admin/ai`, `/admin/loans/{id}/underwriting`, `/admin/ledger/verify-chain|solvency`, and everything
  outside the live prefixes: `/fixed-deposits`, `/insights/*`, `/budgets`, `/transactions/{id}/category`,
  `/copilot/*`, `/intents`, `/autopilot/*`, `/watchlists`, `/price-alerts`, `/backtests`, `/reports/*`,
  `/public/proofs/*`, `/proofs/*`, `/cards`, `/sandbox/*`.
- Known hybrid gap (agreed with backend): `POST /accounts` with `type: GOAL` goes to the real API and
  gets `400` until V2-M3.

### Built (all committed; 110 tests green, lint + build clean)
- Foundation: "Signal" design system, shell, nav with flag filtering, API layer for every §12 route,
  step-up bridge, mocks for every §12 group, STOMP notifications.
- Settings/security, Insights, Home, Payments 2.0 (incl. top-ups), Savings, admin analytics.
- **Copilot** (68706f2): fetch-SSE client + parser, `useCopilotChat`, intent approval cards (one
  Idempotency-Key per intent, step-up), trace viewer, sheet + `/copilot`, Ask Copilot in ⌘K. 7 tests.
- **Payments 2.0 go-live** (161f316, ff3c449): live prefixes + the backend's 9 deviations.
- **Autopilot** (5159610): `/autopilot`, `/autopilot/new`, `/autopilot/:id`, `/autopilot/:id/edit`;
  templates, NL compile, structured DSL form, server-rendered sentence via `/validate`, 90-day replay
  required before switch-on, step-up, runs history. 6 tests.
- **Proof of Balance** (8184478): `/proof` in-browser Merkle verification with step-by-step view,
  root history; chain verify + solvency on `/admin/reconcile`. Mock snapshots frozen per date. 4 tests.
- **Admin v2** (304d409): `/admin/fraud` + `/admin/fraud/:id`, `/admin/ai`, `/admin/flags`,
  underwriting memo in the loan queue, loan document upload on customer loan detail, overview
  "needs attention" chips. **No dedicated tests yet** (see next steps).

### Next, in this order
1. **Tests for Admin v2**: fraud release/reject (reason required, 409 refresh), flags toggle refreshes
   `/feature-flags`, document upload validation, underwriting memo render.
2. **Trading 2.0**: limit/stop in `OrderTicket`, cancel open orders in `OrdersPage`, watchlists +
   price alerts on Markets, Strategy lab (`/lab`, backtests), tax P&L report. Endpoints/mocks exist.
3. **Cards** (`/cards`): virtual card, reveal (step-up `CARD_REVEAL`, auto-hide 30 s, never cached),
   controls, merchant sandbox page.
4. **Landing redesign** (still v1 look) + **DESIGN.md v2** write-up.
5. **PWA** manifest/offline shell (F-UX-1).
6. **Tests/e2e**: step-up, handle payment, requests, savings, analytics; update `e2e/live.spec.ts` for
   the >₹50k step-up (PIN 246810); visual check at 360/768/1440, both themes, reduced motion
   (not done for any v2 page yet).
7. **Integration** as the backend announces V2-M3+ groups: add the prefix to `LIVE_PREFIXES`, drop
   the `NOT_YET_LIVE` pattern, spot-check shapes against `/v3/api-docs` with a demo login.

### Open requests to the backend
- **V2-M4 Copilot:** persist both the USER and ASSISTANT messages before closing the SSE stream (the
  client refetches the conversation on close); pre-stream errors as §7.1 JSON; same Idempotency-Key
  on every approve retry (sent 2026-10-18).
- **V2-M8 Proof:** `GET /public/proofs/roots` must be `permitAll`; `/proofs/accounts/{id}?date=` for
  any published date, `404` = account not in that snapshot. Recorded in backend HANDOFF (e39daab).

### Gotchas
- Shared git index: `git commit -- <paths>` **skips untracked files**; `git add` new files first.
- Vitest filters `console.log`; debug by throwing an `Error`.
- Radix Select in tests needs the jsdom stubs now in `src/test/setup.ts`.
- MSW handlers that `fetch` their own route must be `{ once: true }` or they recurse.

## 2026-10-16 (end of day): all v1 milestones integrated

Every PRD §7 endpoint is live and `src/mocks/live.ts` lists them all, so hybrid mode now
behaves the same as the real API.

**Contract changes today:** all additive, all agreed and shipped by the backend.
- §7.7 Admin and §7.8 Notification DTOs, plus `POST /notifications/read-all` (f5cdad0).
- `ReconcileDto` mismatches follow the implementation: `{ledgerAccountId, code, cachedBalance, computedBalance}`
  (6225140).
- `amountDue` on loan schedule rows, because repayments can be partial (353975f / backend 5ba0695).

**Verified end to end:**
- `e2e/live.spec.ts` passes against the dev API and against the production compose stack
  (`QG_BASE_URL=http://localhost:8088`). It covers register → deposit → transfer → buy, and
  apply → admin approve/disburse → repay `amountDue` → notifications → freeze → audit.
- The backend independently verified the compose stack: Secure/HttpOnly/SameSite=Strict
  cookie, API not published and running as uid 10001, real client IP in the audit log.

**Working agreement:** we share one git index, so commit with explicit paths
(`git commit -- <paths>`).

**Open items / ideas (not blocking v1):**
- F-ACC-6 PDF statements (stretch goal); CSV is done.
- First real GitHub Actions run (nothing has been pushed yet).
