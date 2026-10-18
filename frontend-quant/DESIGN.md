# QuantGen — Design System & UX Spec (v2, "Signal")

**Direction: "Quiet precision", now with a signal.** v1 set the tone: an editorial fintech with
big confident type, generous space, tabular numbers and motion that explains. v2 ("Signal")
keeps all of that and adds depth and meaning. An ambient aurora sits behind translucent
surfaces, and two secondary accents mark *who is acting*: **violet** for the AI layer
(Copilot, Autopilot, memos) and **cyan** for data and proof (analytics, Proof of Balance). The
landing page is where we show off. Inside the app, calm and clarity still win, because people
are looking at their money.

We take *patterns* from Awwwards-style sites (kinetic display type, marquees, scroll reveals,
bento grids, grain, cursor-aware highlights). We never copy any site's layout, assets or brand.

Section numbers are stable: code comments cite them (`DESIGN.md v2 §n`, and v1's `§3.n`).

---

## 1. Brand tokens

Source of truth: `src/styles/tokens.css`. Dark is the default; light is first-class
(`[data-theme='light']`, chosen by the user and stored as a UI preference in `lib/prefs.ts`).

### Colour
| Token | Dark | Light | Use |
|---|---|---|---|
| `--bg` / `--bg-2` | `#060709` / `#0B0D11` | `#F3F1EC` / `#EBE8E1` | Page |
| `--surface` | `rgb(17 19 24 / .74)` | `rgb(255 255 255 / .8)` | Cards (translucent so the aurora reads through) |
| `--surface-solid` | `#111318` | `#FFFFFF` | Dialogs, menus, mobile tables; the reference for contrast maths |
| `--surface-2` / `-3` | `#191C23` / `#22262F` | `#ECE9E2` / `#E1DDD4` | Inputs, hover, nested blocks |
| `--border` / `-strong` | white 7.5% / 14% | ink 9% / 18% | 1px hairlines |
| `--text` / `--text-muted` | `#F3F4F6` / `#969DAB` | `#111215` / `#5B616D` | Copy |
| `--accent` (+ `-ink`) | `#D4FF3F` | `#3047E0` | CTAs, focus ring, active nav. **Never used for numbers** |
| `--violet` | `#A898FF` | `#5B3FD6` | AI: Copilot, Autopilot, investigator and underwriting memos |
| `--cyan` | `#5FD8FF` | `#06708F` | Data and proof: analytics, insights, Proof of Balance |
| `--gain` / `--loss` / `--warn` | `#33E1A6` / `#FF5F6D` / `#FFB83D` | `#07744E` / `#C0262F` / `#8F5600` | P&L, credits/debits, destructive, holds/frozen |
| `--chart-1…6` | accent, violet, cyan, orange, green, pink | darker equivalents | Series, in this order |
| `--aurora-1…3` | accent / violet / cyan at 10–16% | 8–10% | Ambient background fields |

Rules:
- Gain and loss are **never conveyed by colour alone**: always a sign (`+`/`−`) and/or an arrow.
- Every text/background pair meets WCAG AA (4.5:1) against `--bg`, `--surface-solid` and
  `--surface-2`. Check any pair you introduce. (2026-10-16: light accent/gain/loss/warn were
  darkened after measuring 2.9–4.8:1.)
- Violet means "the AI did or suggested this". Never use it for a human or system action, and
  never for something the AI did *without* the user's approval (it can't).
- Grain overlay (`grain` utility, 3–4.5% opacity) on the landing, auth pages and the aurora.

### Typography
| Role | Font | Notes |
|---|---|---|
| Display | **Instrument Serif** (self-hosted, `@fontsource`) | Landing headlines, page greetings, review amounts. Tracking `-0.02em`; italic + `text-muted` for the second clause |
| UI | **Geist** (`@fontsource-variable/geist`) | Everything else |
| Numbers | **Geist Mono** (`num` utility: tabular nums) | All money, quantities, account numbers, prices, hashes |

Scale (rem): 0.75 · 0.8125 · 0.875 · 0.9375 · 1 · 1.125 · 1.5 · 2 · 2.75 · 3.75 · 4.5 · 7 (landing
hero, fluid via `clamp`). `eyebrow` utility: 0.6875rem uppercase, wide tracking, muted.

### Space, shape, depth
- 4px grid. Card padding 20–24px. Landing section gap 160px.
- Radii: `--radius-control` 12px (inputs, buttons, inner blocks) · `--radius-card` 22px ·
  `--radius-sheet` 28px · pills 999px.
- Depth: hairlines, translucent surfaces and `--shadow-card`; `--shadow-pop` only for dialogs,
  sheets and popovers. `edge-light` adds a 1px top highlight to glass panels.
- Layout: 12-column grid, max width 1280px; Home and the landing use **bento** grids.

### Formatting
- Money: `formatINR` / `<Money>` → `₹1,25,000.00` (en-IN grouping). Parse with
  `decimal.js-light`; `Number()` on money is forbidden (charts use `plotValue` for geometry only).
- Account numbers masked (`•••• 4821`); card numbers `•••• •••• •••• 4417`.
- Dates: relative within a day ("2 min ago"), absolute after (`16 Oct 2026, 14:05`). Calendar
  dates (`yyyy-mm-dd`) are formatted as local dates so they never shift across time zones.
- Financial years are Indian (April–March), written `FY 2026-27`.

## 2. Motion

Library: `motion`. Tokens in `src/lib/motion.ts`.

| Pattern | Spec |
|---|---|
| Durations | Micro 120ms · UI 240ms · Page 320–360ms · Landing reveals 600–900ms |
| Easing | expo-out `cubic-bezier(0.22, 1, 0.36, 1)`; spring `{stiffness 300, damping 30}` for layout |
| Page transition | Fade + 8px rise; the old page leaves in 120ms |
| Lists | Stagger 40ms, capped at 8 items |
| Numbers | Balances and prices **roll** (≈400ms); a tick flashes gain/loss tint for 600ms |
| Aurora | Three blurred fields drifting over 40–60s; frozen under reduced motion |
| Hover | Buttons scale 0.98 on press; `SpotlightCard` follows the cursor with a radial edge light |
| Loading | Shimmer skeletons where the layout is known; a spinner only inside a pressed button |
| Success | Check-mark path draw (`SuccessCheck`), once per action |

Landing only: split-line hero reveal, live price marquee, parallax product shot, Lenis smooth
scroll, the sticky "four steps behind every rupee" ledger story.

**Reduced motion:** opacity-only fades, no parallax, marquee, drift or number rolling. Nothing
blocks for more than 300ms; never animate a form field's layout while the user types.

## 3. UX principles and building blocks

### Principles (v1, unchanged numbering)
1. **Money actions take two steps** and are reversible until confirmed: fill in → review
   (amount in big serif, recipient resolved, fee, balance after) → confirm. The confirm button
   disables on click and shows progress. `useMoneyAction` creates the `Idempotency-Key` when the
   review opens and reuses it on every retry; editing the form discards it.
2. **Never optimistic on money.** Pending state, then the server's truth. This includes toggles
   that move or block money (card freeze, rule resume): the UI writes the server's response.
3. **Errors speak human.** Every PRD error code maps to copy plus a next step
   (`lib/moneyErrors.ts`; `409 INVALID_STATE` on admin actions → "Another admin may have acted
   first. The list has been refreshed."). Field errors inline, everything else in a toast.
4. **Keyboard first.** `⌘K` / `Ctrl+K` palette: navigate, run actions, **Ask Copilot**. Every
   form submits with Enter.
5. **Empty states teach.** They say what goes here and offer the first step.
6. **Session safety.** Idle warning at 10 minutes, 60-second countdown, then logout; silent
   refresh near token expiry.
7. **Responsive.** Rail on desktop, bottom dock + sheets on mobile (from 360px). Tables collapse
   into cards or scroll inside their panel; the page never scrolls sideways.
8. **Accessible.** WCAG 2.2 AA; keyboard-operable; visible 2px accent focus ring; Radix
   primitives; throttled `aria-live` on prices and balances.

### Principles added in v2
9. **Step-up is transparent.** Risky calls (large transfer, new payee, card reveal, token
   creation, resuming a rule) may answer `401 STEP_UP_REQUIRED`; the API client opens the PIN
   dialog and retries once with the short-lived token. Screens never handle PINs themselves.
10. **Available, not just balance.** Wherever money is spent, show `availableBalance` (balance
    minus holds) and say what's held: pending reviews, open limit buys, card authorizations.
11. **The AI proposes, the person disposes.** Anything an agent wants to do with money arrives
    as an approval card with the exact amount and recipient, approved with the PIN. AI content
    is violet, labelled ("AI-written, advisory"), and links to its trace.
12. **Sensitive values are shown, not kept.** Revealed card numbers, CVVs and new access tokens
    live only in component state: never in the query cache, storage, URLs or logs. They hide
    on a timer, when the tab is hidden and on unmount.
13. **Feature flags and capabilities.** A switched-off feature disappears from navigation and
    its page says so plainly; the rest of the app is unaffected. A v2 feature that reuses a v1
    route with new fields (limit orders on `POST /orders`) is offered only once the backend has
    shipped it (`api/capabilities.ts`), so an older backend can never misread it.
14. **Simulated means labelled.** Backtests, market prices and the demo store say they're
    simulated, next to the numbers.

### Building blocks (`src/components`)
| Block | Use |
|---|---|
| `Panel` | Titled section card: eyebrow + title + optional action, then content; labels its region |
| `SpotlightCard` / `Card` | Glass surfaces; `tone` = accent / violet / cyan |
| `PageHeader`, `EmptyState` | Page title with back link, eyebrow, actions; teaching empty states |
| `Money`, `RollingMoney` | All amounts; `tone="pnl"` adds sign + colour |
| `AmountInput` | Rupee field; string in, string out, max 2 decimals |
| `Review*`, `SuccessCheck`, `MoneyErrorAlert` | The review/confirm/result steps of a money action |
| `Segmented` | Single-choice filter or mode (radio semantics); not for switching panels |
| `KpiTile`, `ProgressRing`, `charts/*` | Metrics and data (see §5) |
| `Aurora` | The ambient background; `intensity="hero"` on the landing |

## 4. Screens

- **Shell:** grouped rail (Overview · Money · Invest · Credit · Automate · Trust; admin:
  Business · Customers · Platform), flag-filtered; mobile dock with four items and the centre
  action; Copilot sheet from anywhere.
- **Landing:** hero ("Banking, with the precision of a quant.") over the hero aurora with the
  product shot; live price marquee; **Signature** bento (Autopilot sentence + 90-day replay,
  Copilot approval card, Proof of Balance with the latest *published* root when available);
  product bento (Ledger, Trade, Pay, Save, Loans, Cards, Insights); the ledger scroll story;
  security grid (passkeys + PIN, memory-only sessions, risk holds, idempotency, audit,
  encryption) with a link to verify your balance; CTA; footer. Excellent at 360px.
- **Auth:** split screen, form + animated mesh gradient with grain; strength meter; demo login
  in dev only.
- **Home:** greeting; net worth (rolling serif) · quick actions · setup checklist (passkey,
  handle, first goal, first rule; disappears when done) · Copilot strip · accounts · spending ·
  upcoming · portfolio · activity · loan ring · Autopilot · Proof.
- **Pay:** handle + QR hub, send by handle with confirmation of payee, requests, scheduled,
  bills, split groups, top-ups.
- **Savings / Insights:** goals with progress, round-ups, fixed deposits; categories, budgets,
  cash flow, net worth and forecast.
- **Markets / Instrument:** dense live table with sector and **watchlist** filters; instrument
  chart, order ticket (market, limit, stop-loss when available) pinned right or as a bottom
  sheet, Watch button, price alerts.
- **Orders:** status per order (Open, Filled, Cancelled, Expired, Rejected); open orders cancel
  after confirmation. **Strategy lab** (`/lab`): strategy, instruments, period → equity curve,
  return, drawdown, win rate, trades; "Simulated" everywhere. **Capital gains**
  (`/portfolio/tax`): per FY, short vs long term, CSV.
- **Cards:** the card face (masked until PIN; countdown while revealed), controls (freeze,
  online, per-payment limit, blocked merchant types); **QuantGen Store** sandbox at
  `/cards/sandbox` (authorize → hold → capture → refund, declines with reasons).
- **Autopilot / Copilot / Proof:** see PRD §11.4.5, §11.4.6, §11.4.9; violet for the first two,
  cyan for Proof.
- **Admin:** utilitarian and dense; same tokens, no flourish. Fraud queue sorted by score with a
  case page (signals, baseline, memo, release/reject with a reason), AI usage, feature flags,
  analytics, ledger integrity.
- **Offline:** `public/offline.html`, a self-contained page in the same tokens.

## 5. Data visualisation

- One time-series component, `TrendChart` (Recharts): gradient areas, crisp lines, bars,
  dashed "previous period"; glass tooltip that formats the **original decimal strings**.
- Series colours follow `--chart-1…6` in order; money is never the accent's job in body copy,
  but `chart-1` may carry the primary series.
- Axes: compact INR (`₹1.2L`), dates via `axisDate`; `yDomain="auto"` for levels (balances,
  equity), `"zero"` for volumes and counts.
- Small multiples (`Sparkline`, `ShareBars`, `Donut`, `Funnel`, `CohortHeatmap`) share the same
  palette. Every chart has an `aria-label` summarising what it shows; data that matters is also
  in text or a table.
- Price charts use `lightweight-charts` (instrument page).

## 6. Stack

Vite · React 19 · TypeScript (strict) · React Router · TanStack Query · Zustand (auth, in
memory) · Tailwind CSS v4 · Radix · motion · lenis (landing) · lightweight-charts + Recharts ·
@stomp/stompjs · react-hook-form + zod · sonner · cmdk · lucide-react · decimal.js-light ·
MSW (mock and hybrid modes) · Vitest + Testing Library · Playwright. PWA: hand-written
`public/sw.js` (static assets and the offline page only; never `/api` or `/ws`) +
`manifest.webmanifest`.
