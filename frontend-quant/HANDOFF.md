# Frontend → Backend handoff

## 2026-10-25 (evening, quantgen-b9): :8080 back up, live 12/12  ← READ THIS FIRST
- The owner approved the restart; the backend runs **34eeb65** on :8080 (dev DB V32) and agent-quant on :8090. All
  6 flags are off (`crypto` is new). The stale root compose stack (:8088) was stopped at the owner's request (containers
  and volumes kept: `docker start quantgen-db-1 quantgen-api-1 quantgen-web-1`).
- **Live now:** F-CARD-4 disputes, `/admin/ai` (bac1a48). The full live e2e file passes **12/12** (new: "card
  disputes", "gap closes"). Bug found live and fixed: the sandbox didn't refresh the card's payment list after capture.
- `ee51e1f`: F-TRD-12 crypto panel (Markets, `crypto` flag), F-POB-6 anchor link (proof page; `anchor` is null on dev),
  F-RISK-6 reconcile explanations, F-MCP-1 "Connect Claude Desktop" card. `tokens` on `/admin/ai/usage` is one number
  (pinned). Shapes checked on :8080. Compose: the api already has egress via `edge`, so CoinGecko needs no change.
- **Still mocked, waiting for the backend's "AI answering" ping** (its eval run holds the free-tier quota):
  `/copilot/`, `/intents`, `/autopilot/compile`. Flip them together (see the `live.ts` comment), then add a live
  copilot e2e. My vite for live runs: `npx vite --port 5197 --strictPort`.

## 2026-10-25 (later, session quantgen-b9): backend gap closes integrated
The backend session was `backend-quant-cf`. :8080 stayed down all session (owner's call), so everything below is
**mocked and unit-tested, not yet verified live**.
- `875f18e` backend `bbb2f66` integrated: close account (`POST /accounts/{id}/close`, step-up via the interceptor,
  the 409 blocker list shown verbatim; button on the account page, not for FDs), blocked requesters
  (`GET/DELETE /payment-requests/blocked`, panel on Requests), leave split group (`POST /split-groups/{id}/leave`,
  `MemberDto.left`), "Explain this run" sends `context: {type: "AUTOPILOT_RUN", id}`, `SUSPECTED_FRAUD` on bills,
  `EXPIRED` sandbox holds, daily-limit copy for all three channels. Fraud memos needed no change (shape already
  matched).
- `71b94e1` backend `a65c28c` F-RISK-5 underwriting: `extracted[].documentId` (file name next to the page), `runId`
  (trace link), field enum labels, FAILED shows the server's flag.
- F-PAY-13 receipts: client-side, from TransferDto/BillPaymentDto (masked in `lib/receipts.ts`), printed via
  `#receipt-root` + `styles/print.css` so the browser saves the PDF. No endpoint, no dependency.
- All five new endpoints sit under prefixes already live in `live.ts`, so on a backend older than bbb2f66 they'd 404.
  Nothing to flip for them; just verify once :8080 runs bbb2f66+.
- **Still waiting for :8080 (+ agent-quant :8090):** F-CARD-4, `/copilot/`, `/intents`, `/admin/ai`,
  `/autopilot/compile` flips (see the next section), plus a live e2e for close/leave/unblock.
- F-UX-5 Hindi: **dropped by the owner (2026-10-25)**, English only. Not built, need an owner decision: F-TRD-12 crypto feed (no contract; which free
  feed, and through the backend or not), F-RISK-6 reconcile explainer (backend agent, no contract).

## 2026-10-25: what's live, what's waiting to flip

- **Live and verified on :8080:** `V2-M5-extended` (budget trigger, round-up sweep; e071c76) and `V2-M5-bills`
  (saved billers + PAY_BILL; 61cebc8, live e2e). The live file is 10 tests, run **in order**
  (`test.describe.configure({ mode: 'default' })`): feature flags are global, and parallel tests undid each other.
  That's how ai/autopilot/mcp were left ON on 2026-10-24. :8080 was later stopped for low memory (the owner restarts
  it). **First thing after a restart: set all 5 flags off via the admin API.**
- **Built, mocked, waiting for :8080:** F-CARD-4 disputes (4ed110c; backend 5c03a2e, V24). To flip:
  add `'F-CARD-4'` to `SHIPPED_MILESTONES` and drop the two dispute patterns from `NOT_YET_LIVE`, then add a live e2e.
- **Copilot on Gemini (D-044):** the backend is building agent-quant (owner-approved). Contract: backend message
  2026-10-24 (no shape changes to §12.6/§12.14; new codes AI_UNAVAILABLE, AI_BUSY, TOOL_LIMIT, already handled here;
  a free-tier notice is under the composer). Flip `/copilot/`, `/intents`, `/admin/ai` and `/autopilot/compile` only on
  the backend's ping.
- Fixed bugs worth knowing: intent result links are keyed by `resultRef.type` (FIXED_DEPOSIT, LOAN); a NUL byte in
  `mocks/v2/payments.ts` once made git store it CRLF (check `tr -cd '\r\000'` on commits made with scripts).
- `vite.config.ts` has an **uncommitted owner edit** (`allowedHosts: ['.ngrok-free.app']`). Leave it; the security
  caveat was raised with the owner.

## 2026-10-24: M5 verified live, audit, free-tier, infra

The frontend session was `quantgen-27` and the backend was `backend-quant-1a`.
- **Audit (both sessions agree):** the backend diffed all 168 PRD §7/§12 endpoints against its controllers. Every
  non-AI endpoint exists. Missing: `/copilot/*` (6) and `/admin/ai/*` (2), both belonging to agent-quant.
- **Free tier:** the only paid item in the whole plan is the Claude API (agent-quant, D-019/D-020). Proposed to the
  owner: a pluggable OpenAI-compatible provider (Ollama local, then the Gemini/Groq free tier, Claude later); with no
  provider, the app keeps working without AI (D-039). **Owner to decide. Don't start agent-quant before they do.**
- `8e03dfa` **V2-M5 verified live.** New live e2e "autopilot" (form rule → replay → PIN → fires once, moves exactly
  20%). The full live file passes 9/9 on :8080 (`--workers=2`, rate limiter off). Unit tests 142/142; one earlier run
  had a timing flake while the backend was building. Also fixed the 360px nits on the scheduled form.
- `52bb44d` **F-CARD-4 disputes contract** in PRD §12.11, ACCEPTED by the backend; not built yet (P2, after its queue).
- `c83b340` **infra: observability profile** (`docker compose --profile observability up -d`). Prometheus scraping
  over the private network was verified against :8080. Grafana is on loopback and refuses to start without `QG_GRAFANA_PASSWORD`.
- `245e796` **infra: k6** (`infra/k6/README.md`). Transfers are clean. **Orders fail on `409 INVALID_STATE` during
  price ticks (~1.5–4%)**, reported to the backend (not a money bug; the ledger reconciles).
- The stale v1 compose stack (:8088, image built 2026-10-16) is stopped, volumes kept. Rebuild before using it.
- The `mcp` flag was ON on :8080 (leftover?). Flagged to the backend.

**Backend queue (announced):** (a) intent kinds SCHEDULED_TRANSFER / PAYMENT_REQUEST / BILL_PAYMENT /
LOAN_APPLICATION → (b) Autopilot BUDGET_THRESHOLD + ROUND_UP_SWEEP → (c) saved billers (+ §12.4 contract) and PAY_BILL →
(d) chain checkpoints, F-POB-6 anchoring → disputes.
**Next for the frontend:** flip each one as it's announced (add the milestone tags; the form hides the extended
triggers/actions behind `backendHas('V2-M5-extended')`); disputes UI once built; F-UX-5 Hindi (P2, needs an
i18n approach). Infra items needing an account or a deploy target (Redpanda, k8s/ArgoCD, Terraform) wait for the owner.

## 2026-10-19: M8 + M9 + M6 + M10 + M4-core + M5-core live, mocked e2e green, 360 px pass

Then the 2026-10-18 (late) section below for the checklist, gotchas and the live/mocked list (still
accurate except for the changes here). The backend session was `backend-quant-d2` today.

### Done this session (all committed)
- `e1ddd35` + `3952199` **Mocked e2e green** (50 passed, 6 live-only skipped) against `vite --mode mock --port 5199`.
  The failures were the tax table scroll region (needed `tabIndex`/`role=region`), the palette option
  off-screen (type first; on mobile go via "Search everything…"), and timeouts. The a11y "signed-in" test
  is now chunked 6 screens per test. The expect timeout is 20 s: a trace showed the first post-login
  dashboard landing after 10 s on a loaded dev server. That's not an app bug.
- `825267e` **V2-M8 Proof live** (`/public/proofs/`, `/proofs/`; verify-chain/solvency ride on `/admin/`).
  Verified in a real browser against :8080: the proof verifies, and the totals match the backend's.
  The encoding was agreed point by point with the backend (PRD §12.10 "Backend notes (V2-M8)"). The proof mock
  now includes closed accounts, in ASCII id order. Added the `LEDGER_INTEGRITY` notification (→ `/admin/reconcile`).
- `825267e` + `5118fae` **V2-M9 Cards live** (`/cards`, `/sandbox/merchant/`). Authorize/capture/refund
  are 🔒💰 now: checkout uses one Idempotency-Key per attempt (reused on retry, new after an edit or an
  answer); capture/refund use one key per authId. The mock enforces keys and owner-only capture/refund, and
  uses 2-dp `perTxnLimit` and the exact decline strings. **New live e2e "cards"** (flag on via admin
  API → issue → PIN reveal → pay → hold → capture → refund → flag restored) passes on :8080.
- `72346e6` Lab checks the server's backtest bounds (≥ 2021-01-01, 30 days to 5 years, cash ≥ ₹1,000).
- `294d764` **360 px visual pass** (scripted overflow scan of 17 v2 pages at 360/768/1440 × both
  themes, plus screenshots). Fixed: /lab scrolled sideways (grid items need `min-w-0`), PIN boxes too wide,
  session rows pushed "Sign out" off-screen, scheduled rows crushed the text. Clean at 768/1440. The only
  remaining hit is the decorative glow on /pay, which is clipped by `overflow-hidden` (a false positive).
- `77d8caa` **V2-M6 prep:** fraud release/reject send an Idempotency-Key (one per case+decision+reason).

- `ec59ec2` **V2-M6 live** (fraud cases, loan documents, underwriting; `/admin/ai` stays mocked until M4).
  New live e2e "fraud" (held ₹30,000 → admin release → payee credited). The full live suite passes 5/5 on :8080.
- `d0ad600` **infra:** nginx allows 6m bodies on `^/api/v1/loans/[^/]+/documents$` only (tested in the real
  image); compose mounts the `loandocs` volume at `/app/data/loan-documents`.

- `7643e6b` **V2-M10 tokens live.** Live e2e "access tokens": mcp flag on via admin API → PIN step-up create →
  `qg_pat_` token reads `/accounts` (200), can't POST `/transfers` (403) → revoke → 401; flag restored.
  `AgentName` gains `MCP_CLIENT` ("Connected app").

- `c96eead` + `8853c43` **V2-M4 core (intents) verified live**, but **kept mocked in hybrid on purpose**: the
  scripted Copilot mock proposes intents with mock account ids, so flip `/intents` together with `/copilot/`
  once agent-quant exists (comment in `live.ts`). Live e2e "intents" (API proposal → approve with PIN → EXECUTED)
  and "connected app" (read+propose token proposes, `ai` off → approve on Settings → Developer) pass.
  Bug fixed: approved cards vanished from "Waiting for you" on refetch, taking the confirmation with them.
  `WaitingApprovals` is now shared by `/copilot` and the Developer tab (`MCP_CLIENT` only).
- **Live e2e and the rate limiter:** the full live file trips :8080's per-IP auth limit (429 on register).
  Run the backend with `QG_RATE_LIMIT_ENABLED=false` for a full run, or run tests one at a time. Each test
  passed on its own on 2026-10-19.

- `1bb8ff0` **V2-M5 Autopilot core** flipped (`/autopilot/`; `/autopilot/compile` stays mocked). The form hides
  BUDGET_THRESHOLD / PAY_BILL / ROUND_UP_SWEEP unless fully mocked (`backendHas('V2-M5-extended')`). Templates come
  with `rule: null` live, so they compile when AI is on and otherwise open the form; a 503 compile falls back
  to the form; `outcome: null` reads "Running". **Not yet verified live:** :8080 was stopped for low memory
  right after M5 was announced (backend 5ac903a, DB V22). Restart from backend-quant with
  `QG_RATE_LIMIT_ENABLED=false ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` (owner's call).

### Next
1. **Verify M5 live** once :8080 is back: spot-check validate/simulate/rules/templates/runs, then add a live e2e
   (salary-style MONEY_RECEIVED → TRANSFER rule with PIN step-up, a transfer in → one SUCCEEDED run) and run the
   whole live file (the rate limiter is off now).
2. **Needs the owner:** agent-quant (copilot SSE, agents, traces, `/admin/ai`, memos, MCP server). After it
   exists: flip `/copilot/`, `/intents`, `/admin/ai`, `/autopilot/compile`.
3. Still not built (P2 or no contract): F-CARD-4 disputes, F-UX-5 Hindi, F-TRD-12 crypto, F-TRD-9 SIP.
4. Minor visual nits seen at 360: the native date input in the scheduled form clips its text next to the
   picker icon; the "How often" segmented control is tight.

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
