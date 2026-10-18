# Frontend → Backend handoff

## 2026-10-18: v2 frontend — progress and what's left (session paused for context)

**Read first in a new session:** this section, PRD §11–§13 (esp. §12.14 DTO reference, §12.13
analytics), DECISIONS.md D-040/D-041, `src/api/types.v2.ts`, `src/mocks/live.ts`.

### Done (committed)
- **Design "Signal"**: tokens (`styles/tokens.css`: aurora, glass, violet/cyan, chart palette),
  utilities in `globals.css` (glass, eyebrow, edge-light, text-gradient, dot-grid), floating rail +
  glass top bar (`app/layouts/AppShell.tsx`), mobile dock + quick-action sheet (`MobileDock.tsx`),
  grouped nav with feature-flag filtering (`app/nav.ts`), restyled Card/Button/Dialog(+Sheet)/Badge/
  PageHeader, `Tabs`, `Panel`, `KpiTile`, chart kit (`components/charts/*`).
- **API layer**: `types.v2.ts`, `endpoints.v2.ts` (every §12 route), `flags.ts`, `availability.ts`,
  step-up bridge (`api/stepUp.ts` + interceptor in `client.ts`; non-public `/auth/*` now carry the bearer).
- **Mocks for every §12 group** (`src/mocks/v2/*`, combined in `allHandlers.ts`), incl. SSE copilot,
  autopilot renderer/simulator, real Merkle sum tree for Proof of Balance (`lib/merkle.ts` + tests).
- **Screens**: Step-up dialog (PIN/passkey), passkey sign-in on login, Settings (`/settings/*`:
  profile, passkeys, PIN, sessions, limits, KYC, MCP tokens), Admin analytics (`/admin/analytics`),
  customer Insights (`/insights`, 4 tabs), redesigned Home dashboard, Payments 2.0 (`/pay`,
  `/pay/requests|scheduled|bills|splits|splits/:id|qr`, handle + confirmation of payee in `/transfer`,
  Razorpay test top-ups in Add money), Savings (`/savings`: goals, round-ups, FDs).
- Realtime notifications over STOMP `/user/queue/notifications`; nginx CSP allows Razorpay.
- Backend integration so far (backend session `backend-quant-9d`): V2-M1 fully live — flags,
  availableBalance/holds, step-up+PIN, limits, sessions, KYC, passkeys. Marked in `live.ts`;
  `NOT_YET_LIVE` keeps unbuilt v2 routes mocked in hybrid mode. Backend next: V2-MA or V2-M2 (owner to pick).

### Left to do (in this order)
1. **Copilot UI** (V2-M4): replace stub `features/copilot/CopilotSheet.tsx` with the streaming chat
   (SSE parser for `event:/data:` per PRD §12.6), intent approval cards (server-rendered summary;
   approve = idempotent POST + step-up), trace viewer, `/copilot` full page, "Ask Copilot" in ⌘K
   (`app/CommandPalette.tsx` still v1 — add v2 pages + ask-copilot item). Hooks/endpoints exist.
2. **Autopilot UI** (`/autopilot`, `/autopilot/new`, `/autopilot/:id`): templates, NL compile →
   server rendering → 90-day replay → activate (step-up), rule form builder, runs history. Hooks exist.
3. **Proof of Balance page** (`/proof`): verify in browser with `lib/merkle.ts`, step-by-step path view,
   published roots; admin chain verify + solvency on `/admin/reconcile`.
4. **Admin v2**: overview redesign (`/admin`), fraud queue + case detail (`/admin/fraud`), AI usage
   (`/admin/ai`), feature flags (`/admin/flags`), underwriting memo in loan queue; loan document upload
   on customer loan detail. Endpoints/mocks exist; routes not added yet.
5. **Trading 2.0**: limit/stop in `OrderTicket`, cancel open orders in `OrdersPage`, watchlists +
   price alerts on Markets, Strategy lab (`/lab`, backtests), tax P&L report. Mocks exist.
6. **Cards** (`/cards`): virtual card UI, reveal (step-up, auto-hide 30 s), controls, merchant sandbox page.
7. **Landing page redesign** (still v1 look) + **DESIGN.md v2** write-up (tokens, shell, motion).
8. **PWA** manifest/offline shell (F-UX-1); CommandPalette v2.
9. **Tests**: add component tests for step-up flow, handle payment, requests, savings, analytics;
   update `e2e/live.spec.ts` for >₹50k step-up (PIN 246810); run full suite + `npm run build`.
10. **Integration**: when the backend announces each group, remove its pattern from `NOT_YET_LIVE`
    and add the prefix to `LIVE_PREFIXES`; check shapes against `/v3/api-docs`.

Routes referenced in nav but not built yet (show 404 in-shell): `/copilot`, `/autopilot`, `/cards`,
`/lab`, `/proof`, `/admin/fraud`, `/admin/ai`, `/admin/flags`.

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
