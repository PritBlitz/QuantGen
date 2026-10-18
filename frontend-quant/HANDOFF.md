# Frontend → Backend handoff

## 2026-10-18 (late): v2 frontend state, for a cold restart  ← READ THIS FIRST

**Read first:** this section, PRD §11–§13 (the "Backend notes" blocks in §12.3, §12.4, §12.5, §12.9,
§7.9 and §12.13 record every backend deviation), `src/api/types.v2.ts`, `src/mocks/live.ts`,
`src/api/capabilities.ts`. The backend's own list is in `backend-quant/HANDOFF.md`. Find the backend
session with `ListAgents` (its name changes; it was `backend-quant-c0` here) and message it via `SendMessage`.

### Backend state (as announced to this session)
Live on :8080: all v1, **V2-M0, V2-M1, V2-MA, V2-M2, V2-M3 (d737264, savings + insights), V2-M7
(0604a02, Trading 2.0)**, dev DB at V16. The backend's order from here: **V2-M8 Proof of Balance** (next),
then M9 Cards, M6 (rules-based fraud), M10 tokens, then the core side of M4 Copilot / M5 Autopilot.
It pings per milestone. All five feature flags are **false** on :8080 (turn them on in `/admin/flags`).

### Going live with a milestone (checklist)
1. Add its prefixes to `LIVE_PREFIXES` in `src/mocks/live.ts` and drop its `NOT_YET_LIVE` patterns.
2. Add the milestone to `SHIPPED_MILESTONES` in `src/api/capabilities.ts`. This gates features that
   reuse a v1 route with new fields (LIMIT/STOP orders on `POST /orders`); an older backend would
   silently do the v1 thing.
3. Spot-check shapes: `curl :8080/v3/api-docs` plus a demo login (`demo@quantgen.dev` / `Demo@1234`).
   Money is serialized as strings even though OpenAPI says `number`.
4. Run the page in hybrid mode (`npm run dev:hybrid`) and, if it moves money, add a step to
   `e2e/live.spec.ts`.

### Live vs mocked now
- **Live in hybrid mode:** all v1; V2-M1; analytics (incl. the real `/analytics/goals`); Payments 2.0
  (and `/payees/`, which was never actually live before because a comment swallowed it; fixed);
  **V2-M3**: goals (`POST /accounts {type: GOAL}`), round-ups, `/fixed-deposits`, `/insights/*`,
  `/budgets`, `/transactions/{id}/category`; **V2-M7**: limit/stop/cancel, `/watchlists`,
  `/price-alerts`, `/backtests`, `/reports/tax-pnl`.
- **Still mocked:** `/users/me/tokens`, `/accounts/{id}/cards`, `/cards`, `/sandbox/*`,
  `/loans/{id}/documents`, `/admin/fraud-cases`, `/admin/ai`, `/admin/loans/{id}/underwriting`,
  `/admin/ledger/verify-chain|solvency`, `/public/proofs/*`, `/proofs/*`, `/copilot/*`, `/intents`,
  `/autopilot/*`.

### Built this session (all committed; 137 unit tests green, lint + typecheck + build clean)
- `072a27f` Admin v2 tests (fraud release/reject + 409, flag toggle refresh, document upload, memo).
  Vitest now uses `maxWorkers: '50%'`: one jsdom per core starved the CPU and timed out `findBy`s.
- `a9dd0ea` **Trading 2.0 UI**: order ticket with Market/Limit/Stop-loss + validity (stop only on SELL,
  `availableBalance` used), Orders page with statuses and cancel-with-confirm, watchlists (filter on
  Markets, Watch button on instrument), price alerts, `/lab` strategy lab, `/portfolio/tax` capital-gains report + CSV.
- `b69329e` **Cards** `/cards` (step-up reveal, kept only in component state, auto-hides after
  `hideAfterSeconds`, on tab hide and on unmount; freeze/online/limit/blocked categories) +
  `/cards/sandbox` "QuantGen Store" (authorize → hold → auto-capture after 5 s → refund).
- `927ad30` **Setup checklist** on Home (F-UX-3).
- `69e3bd7` **PWA** (F-UX-1): `public/sw.js` (static assets + offline page only, never `/api` or `/ws`),
  manifest, icons, `public/offline.html`; registered in production builds only (`src/lib/pwa.ts`).
  Verified in a real production build: offline navigation shows the offline page and no API response is cached.
- `008d6bc` **Landing redesign** (Signature section: Autopilot / Copilot / Proof with a live root; v2
  product bento; security section reflects what shipped) + **DESIGN.md v2 "Signal"**, rewritten.
  Section numbers are stable because code cites them.
- `2b7e52e` Payments 2.0 tests (handle payment > ₹50k with step-up, cancelled step-up, requests).
- `b152ffa` **V2-M3 go-live** + FD/goal accounts kept out of pickers they can't use (`isEveryday()` in
  `features/accounts/labels.ts`); the FD mock no longer has an invented KYC gate; a11y fixes (17
  unlabelled Selects now take `aria-label`, focusable heatmap, visible text inside accessible names);
  e2e updated for the v2 nav; a new live e2e test (PIN → goal → FD → close early with step-up) passes against :8080.
- `320abd9` **V2-M7 go-live** (endpoints spot-checked against :8080).

### Next, in this order
1. **Finish the mocked e2e repair.** It was mid-run when the session ended:
   - Run it against your own mock server: `npx vite --mode mock --port 5199`, then
     `QG_BASE_URL=http://localhost:5199 npx playwright test`. **Don't rely on :5173.** The owner
     sometimes runs a real-API `vite` there, and `reuseExistingServer` would test against the live backend.
   - Still failing: `e2e/admin.spec.ts`. On desktop, "Run reconciliation" stays disabled (the mock takes
     900 ms, so check the button label or the flow; v2 also renamed the page to "Ledger integrity"). On
     mobile, the dock's "More" button is "not stable", probably a sonner toast over the dock (dismiss it
     first). `e2e/money.spec.ts` step "command palette → Orders": the option is off-screen, so type
     "Orders" into the combobox first.
   - Mock e2e must navigate **in-app** (`go()` uses the rail, the dock or the More sheet): a page
     reload resets the in-browser MSW state.
   - `e2e/a11y.spec.ts` now audits every v1+v2 screen by path, in both themes. Fix whatever it finds.
2. **Visual pass** at 360/768/1440, both themes, reduced motion, for v2 pages (not done yet except the landing page).
3. **Integrate the next backend milestones** with the checklist above: M8 Proof (public roots
   `permitAll`, 404 = not in snapshot), M9 Cards (backend CVV should accept the previous 30 s window,
   like the mock does, or the sandbox races), M6 fraud, M10 tokens, M4/M5.
4. Backend V2-M7 bounds that differ from the client checks (the server's 400s show inline, so nothing
   breaks): backtest `initialCash` minimum is ₹1,000 server-side (the client asks for ₹10,000);
   `from` ≥ 2021-01-01 and a 30-day to 5-year range aren't checked client-side yet.
5. Not built (P2 or no contract): F-CARD-4 disputes (no endpoint in §12.11), F-UX-5 Hindi/i18n,
   F-TRD-12 crypto feed, F-TRD-9 SIP (an Autopilot template, waits for M5).

### Open requests to the backend
- **V2-M4 Copilot:** persist the USER and ASSISTANT messages before closing the SSE stream; pre-stream
  errors as §7.1 JSON; the same Idempotency-Key on every approve retry.
- **V2-M8 Proof:** `GET /public/proofs/roots` `permitAll`; `/proofs/accounts/{id}?date=` → 404 when the
  account isn't in that snapshot (backend confirmed).
- **V2-M9 Cards:** accept the previous CVV window (30 s TOTP-style tolerance).

### Gotchas
- Shared git index: `git commit -- <paths>` **skips untracked files**; `git add` new files first.
- Some files are CRLF (`src/mocks/live.ts` and a few payments files). Python `str.replace` with `\n`
  misses them, so use the Edit tool. Long heredocs in Git Bash fail; write files with the Write tool.
- Vitest filters `console.log`; debug by throwing an `Error`. Radix Select in tests needs the jsdom
  stubs in `src/test/setup.ts`. MSW handlers that `fetch` their own route must be `{ once: true }`.
- jsdom `FormData` can't be parsed by undici's multipart parser. Tests of uploads assert on the raw
  body (see `adminV2.test.tsx`).

## 2026-10-18 (end of day, superseded by the section above): v2 frontend state

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
