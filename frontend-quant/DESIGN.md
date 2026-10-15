# QuantGen — Design System & UX Spec

**Direction: "Quiet precision."** The aim is an editorial, Awwwards-calibre fintech: big
confident type, generous space, tabular numbers that feel engineered, and motion that
explains rather than decorates. The **landing page** is where we show off. **Inside the
app**, calm and clarity win, because people are looking at their money.

We take *patterns* from Awwwards-style sites (kinetic display type, marquees, scroll-reveals,
bento grids, grain, cursor-aware highlights). We never copy any site's layout, assets, or brand.

---

## 1. Brand tokens

### Colour
Dark is the default theme; light is a first-class alternative. All colours are CSS
variables, mapped into Tailwind v4 `@theme`.

| Token | Dark | Light | Use |
|---|---|---|---|
| `--bg` | `#0A0B0D` | `#F5F3EF` | Page |
| `--surface` | `#111317` | `#FFFFFF` | Cards |
| `--surface-2` | `#181B21` | `#EEEBE5` | Inputs, hover |
| `--border` | `#23272F` | `#DDD8CF` | Hairlines (1px) |
| `--text` | `#EDEEF0` | `#121316` | Primary text |
| `--text-muted` | `#8A909C` | `#5E6470` | Secondary |
| `--accent` | `#D2FF3A` | `#3B5BFF` | CTAs, focus ring, active nav. **Never used for numbers** |
| `--accent-ink` | `#0A0B0D` | `#FFFFFF` | Text on the accent |
| `--gain` | `#2BD9A0` | `#0F9D6B` | Positive P&L, credits |
| `--loss` | `#FF5A5F` | `#D92D3A` | Negative P&L, debits, destructive |
| `--warn` | `#FFB224` | `#B26B00` | Overdue, frozen |

Rules:
- Gain and loss are **never conveyed by colour alone**. Always add a sign (`+`/`−`) and an arrow icon.
- Text contrast must meet WCAG AA (4.5:1 for body text). Check every token pair you introduce.
- One subtle SVG **noise/grain overlay** (3–4% opacity) on the landing page and auth pages only.

### Typography
| Role | Font | Notes |
|---|---|---|
| Display | **Instrument Serif** (Google Fonts) | Landing headlines, big balance figure on the dashboard. Tight tracking (`-0.02em`) |
| UI | **Geist** (`geist` npm) | Everything else |
| Numbers / data | **Geist Mono** | All money, quantities, account numbers, prices. Always `font-variant-numeric: tabular-nums` |

Scale (rem): 0.75 · 0.875 · 1 · 1.125 · 1.25 · 1.5 · 2 · 3 · 4.5 · 7 (landing hero only).
Line-height: 1.5 for body, 1.1 for display.

### Space, shape, depth
- 4px base grid. Card padding is 20–24px. The section gap on the landing page is 160px or more.
- Corner radius: 10px for inputs and buttons, 16px for cards, 999px for pills.
- Depth comes from **borders and slight surface shifts**, not heavy shadows. One soft
  shadow is allowed, for popovers and dialogs only.
- Layout: a 12-column grid with max width 1280px. The dashboard uses a **bento grid**.

### Formatting
- Money: `Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })`, which gives
  `₹1,25,000.00`. Parse API strings with a decimal library (`decimal.js-light`) and never use `Number()` on money.
- Account numbers are masked by default (`•••• •••• 4821`), with a "reveal" toggle that auto-hides after 10 s.
- Dates are relative when recent ("2 min ago") and absolute otherwise (`16 Oct 2026, 14:05`). Show the absolute time on hover.

## 2. Motion

The library is `motion` (Framer Motion). Keep motion **moderate**: it should tell the user
what changed, then get out of the way.

| Pattern | Spec |
|---|---|
| Durations | Micro 120ms · UI 200–280ms · Page 320ms · Landing reveals 600–900ms |
| Easing | `cubic-bezier(0.22, 1, 0.36, 1)` (expo-out) by default; spring `{stiffness: 300, damping: 30}` for layout changes |
| Page transition | Fade plus 8px rise, with the old page fading out quickly (120ms) |
| Lists | Stagger children by 40ms, capped at 8 items |
| Numbers | Balances and prices **roll** to their new value (≈400ms); a price tick flashes the gain/loss tint behind the cell for 600ms |
| Hover | Buttons scale 0.98 on press; cards get a cursor-following radial highlight on the border |
| Skeletons | A shimmer sweep instead of spinners, wherever the layout is known |
| Success | Transfer and order confirmations get a check-mark path draw plus a subtle confetti burst, at most once per action |

**Landing page only:** split-line text reveal on the hero; an infinite **marquee ticker** of
live prices; scroll-linked parallax on the product shots; Lenis smooth scroll; a sticky
"how the ledger works" scroll story.

**Accessibility:** respect `prefers-reduced-motion`. Under it, use opacity-only fades,
no parallax, no marquee movement and no number rolling. Never animate anything blocking
for longer than 300ms. Never animate the layout of a form field while the user is typing.

## 3. UX principles
1. **Money actions take two steps and are reversible until confirmed:** fill in → review
   screen (amount in big Instrument Serif, recipient name resolved from the account number,
   fee, remaining balance) → confirm. The confirm button disables on click and shows progress.
   The `Idempotency-Key` is created when the review screen opens and reused on retry.
2. **Never optimistic on money.** Show a pending state, then the server truth.
3. **Errors speak human.** Map each PRD error code to clear copy plus a next step (e.g.
   `INSUFFICIENT_FUNDS` → "You need ₹2,340 more. Add money?"). Field errors show inline;
   everything else goes in a toast (`sonner`).
4. **Speed:** keyboard-first. A command palette (`⌘K` / `Ctrl+K`, via `cmdk`) offers
   "Transfer", "Buy QGEN", "Go to loans", and so on. Every form can be submitted with Enter.
5. **Empty states teach.** No holdings? Show three trending instruments plus a "Buy your first share" CTA.
6. **Session safety:** after 10 minutes idle, show a warning modal with a 60-second
   countdown, then log out. When the token is near expiry, refresh it silently.
7. **Responsive:** sidebar on desktop, bottom tab bar on mobile (from 360px). Charts and
   tables must be usable on a phone; tables collapse into cards.
8. **Accessible:** all interactive elements reachable by keyboard; visible focus ring
   (`--accent`, 2px plus offset); Radix primitives for dialogs, menus and tooltips;
   `aria-live="polite"` on balance and price updates (throttled).

## 4. Screen notes
- **Landing:** hero with a giant serif headline ("Banking, with the precision of a quant."),
  a live price marquee, a bento feature grid (Ledger · Trade · Loans · Security), the
  ledger scroll story, a security section (hash-chained ledger teaser for v2), a CTA and a
  footer. Must look excellent on mobile.
- **Auth:** a split screen, with the form on one side and an animated mesh gradient plus
  grain on the other; a password strength meter; a "Use demo account" button in the dev build.
- **Dashboard (bento):** total net worth (big serif, rolling number) · accounts · portfolio
  sparkline · recent activity · loan progress ring · quick actions.
- **Markets:** a dense live table (symbol, sparkline, price, change %, flash on tick) with
  sector filters.
- **Instrument:** a large chart (`lightweight-charts`) with range tabs, and an order ticket
  pinned on the right (desktop) or as a bottom sheet (mobile).
- **Portfolio:** an allocation donut, a holdings table and realized vs unrealized P&L.
- **Loans:** an interactive EMI calculator (sliders with a live amortization chart), the
  application stepper, and the loan detail with a schedule timeline.
- **Admin:** utilitarian and dense; same tokens, no flourish.

## 5. Stack
Vite · React 19 · TypeScript (strict) · React Router · TanStack Query · Zustand (auth state,
in memory) · Tailwind CSS v4 · shadcn/ui (Radix) · motion · lenis (landing only) ·
lightweight-charts + Recharts · @stomp/stompjs · react-hook-form + zod · sonner · cmdk ·
lucide-react · decimal.js-light · MSW (mocks until backend endpoints are live) ·
Vitest + Testing Library · Playwright (end-to-end tests for key flows).
