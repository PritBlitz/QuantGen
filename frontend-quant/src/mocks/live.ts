/**
 * Endpoints the backend has announced as live (via the backend session). In hybrid mode
 * (`npm run dev:hybrid`) these pass through to the real API on :8080 and everything else
 * stays mocked. Add a prefix here each time a milestone goes live.
 */
export const LIVE_PREFIXES: ReadonlyArray<{ prefix: string; milestone: string }> = [
  { prefix: '/api/v1/auth/', milestone: 'M1' },
  { prefix: '/api/v1/users/me', milestone: 'M1' },
  { prefix: '/api/v1/accounts', milestone: 'M2' },
  { prefix: '/api/v1/transfers', milestone: 'M2' },
  { prefix: '/api/v1/beneficiaries', milestone: 'M2' },
  { prefix: '/api/v1/market/', milestone: 'M3' },
  { prefix: '/api/v1/orders', milestone: 'M3' },
  { prefix: '/api/v1/portfolio', milestone: 'M3' },
  { prefix: '/api/v1/loans', milestone: 'M4' },
  { prefix: '/api/v1/admin/', milestone: 'M4/M5' },
  { prefix: '/api/v1/notifications', milestone: 'M5' },
  // v2 (backend 7c2970c): flags; AccountDto.availableBalance/holdsTotal ride on /accounts.
  { prefix: '/api/v1/feature-flags', milestone: 'V2-M1' },
  { prefix: '/api/v1/admin/feature-flags', milestone: 'V2-M1' },
  // Backend f29bb64: step-up (PIN), PIN, limits, sessions, KYC. (/auth/ and /users/me prefixes
  // above already cover them; listed for the record.)
  { prefix: '/api/v1/auth/step-up/', milestone: 'V2-M1' },
  { prefix: '/api/v1/users/me/pin', milestone: 'V2-M1' },
  { prefix: '/api/v1/users/me/limits', milestone: 'V2-M1' },
  { prefix: '/api/v1/users/me/sessions', milestone: 'V2-M1' },
  { prefix: '/api/v1/users/me/kyc', milestone: 'V2-M1' },
  { prefix: '/api/v1/auth/passkeys', milestone: 'V2-M1' },
  // Backend cb0ee45 (announced 2026-10-18): business + personal analytics, §12.13.
  { prefix: '/api/v1/admin/analytics', milestone: 'V2-MA' },
  { prefix: '/api/v1/analytics/', milestone: 'V2-MA' },
  // Backend 807e831 9bc5dba a5101f5 f3cd38c (announced 2026-10-18): Payments 2.0 parts 1–4.
  // Handles ride on /users/me; transfers by handle on /transfers.
  { prefix: '/api/v1/payees/', milestone: 'V2-M2' },
  { prefix: '/api/v1/payment-requests', milestone: 'V2-M2' },
  { prefix: '/api/v1/scheduled-transfers', milestone: 'V2-M2' },
  { prefix: '/api/v1/billers', milestone: 'V2-M2' },
  { prefix: '/api/v1/bill-payments', milestone: 'V2-M2' },
  { prefix: '/api/v1/split-groups', milestone: 'V2-M2' },
  // Backend 81487e2: Razorpay top-ups (§7.9). Without keys on :8080, create returns 503
  // GATEWAY_UNAVAILABLE; the live `razorpay` flag is off, so the UI hides the option anyway.
  { prefix: '/api/v1/topups', milestone: 'V2-M2' },
  // Backend d737264 (announced 2026-10-18): V2-M3 savings + insights. Goals ride on
  // POST /accounts; round-ups on /accounts/{id}/round-up; /analytics/goals is real.
  { prefix: '/api/v1/fixed-deposits', milestone: 'V2-M3' },
  { prefix: '/api/v1/insights/', milestone: 'V2-M3' },
  { prefix: '/api/v1/budgets', milestone: 'V2-M3' },
  { prefix: '/api/v1/transactions/', milestone: 'V2-M3' },
  // Backend 0604a02 (announced 2026-10-18): V2-M7 Trading 2.0. Limit/stop + cancel ride on /orders.
  { prefix: '/api/v1/watchlists', milestone: 'V2-M7' },
  { prefix: '/api/v1/price-alerts', milestone: 'V2-M7' },
  { prefix: '/api/v1/backtests', milestone: 'V2-M7' },
  { prefix: '/api/v1/reports/', milestone: 'V2-M7' },
  // Backend 15a114d (announced 2026-10-19): V2-M8 Proof of Balance. verify-chain/solvency ride on /admin/.
  { prefix: '/api/v1/public/proofs/', milestone: 'V2-M8' },
  { prefix: '/api/v1/proofs/', milestone: 'V2-M8' },
  // Backend 0d224de (announced 2026-10-19): V2-M9 cards + merchant sandbox. Issuing rides on
  // /accounts/{id}/cards; authorize/capture/refund need an Idempotency-Key.
  { prefix: '/api/v1/cards', milestone: 'V2-M9' },
  { prefix: '/api/v1/sandbox/merchant/', milestone: 'V2-M9' },
  // Backend b2ade2e (announced 2026-10-19): V2-M10 personal access tokens ride on /users/me.
  { prefix: '/api/v1/users/me/tokens', milestone: 'V2-M10' },
  // Backend 5ac903a (announced 2026-10-19): V2-M5 Autopilot core. /autopilot/compile is live too (the
  // Planner in agent-quant); AI_UNAVAILABLE falls back to the form, AI_BUSY shows inline.
  { prefix: '/api/v1/autopilot/', milestone: 'V2-M5' },
  // Backend 71f7715 (announced 2026-10-24): saved billers (+ /saved-billers/{id}/fetch) and Autopilot PAY_BILL.
  { prefix: '/api/v1/saved-billers', milestone: 'V2-M5-bills' },
  // Backend 5c03a2e (V24), on :8080 from 55a320c (2026-10-25): F-CARD-4 disputes. Opening one rides on
  // /cards/{id}/payments/{authId}/dispute; the admin queue on /admin/.
  { prefix: '/api/v1/card-disputes', milestone: 'F-CARD-4' },
  // Copilot (backend 96c282b + agent-quant, :8090 at 7d90533) and intents, flipped together on
  // 2026-10-25: a mocked copilot's intents carry mock account ids, so they must never mix with a
  // live /intents. On the Gemini free tier a turn may end in `event: error {code: AI_BUSY}`.
  { prefix: '/api/v1/copilot/', milestone: 'V2-M4' },
  { prefix: '/api/v1/intents', milestone: 'V2-M4' },
]
// Every PRD §7 endpoint is live as of M5; hybrid mode now equals the real API.

/**
 * v2 routes that sit *under* a live v1 prefix but aren't built by the backend yet. They stay
 * mocked in hybrid mode. Remove a pattern when the backend announces the route.
 */
export const NOT_YET_LIVE: ReadonlyArray<RegExp> = [
  // Empty since 2026-10-25: /admin/ai, F-CARD-4 disputes and /autopilot/compile (the Planner) are live.
]

export const isLive = (pathname: string) =>
  LIVE_PREFIXES.some((l) => pathname.startsWith(l.prefix)) &&
  !NOT_YET_LIVE.some((re) => re.test(pathname))
