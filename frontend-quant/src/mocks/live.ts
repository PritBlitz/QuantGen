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
]
// Every PRD §7 endpoint is live as of M5; hybrid mode now equals the real API.

/**
 * v2 routes that sit *under* a live v1 prefix but aren't built by the backend yet. They stay
 * mocked in hybrid mode. Remove a pattern when the backend announces the route.
 */
export const NOT_YET_LIVE: ReadonlyArray<RegExp> = [
  /^\/api\/v1\/auth\/passkeys/,
  /^\/api\/v1\/users\/me\/(handle|tokens)/,
  /^\/api\/v1\/accounts\/[^/]+\/(topups|round-up|cards)/,
  /^\/api\/v1\/loans\/[^/]+\/documents/,
  /^\/api\/v1\/orders\/[^/]+\/cancel/,
  /^\/api\/v1\/admin\/(analytics|fraud-cases|ai)(\/|$)/,
  /^\/api\/v1\/admin\/loans\/[^/]+\/underwriting/,
  /^\/api\/v1\/admin\/ledger\/(verify-chain|solvency)/,
]

export const isLive = (pathname: string) =>
  LIVE_PREFIXES.some((l) => pathname.startsWith(l.prefix)) &&
  !NOT_YET_LIVE.some((re) => re.test(pathname))
