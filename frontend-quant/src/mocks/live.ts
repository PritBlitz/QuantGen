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
]
// Every PRD §7 endpoint is live as of M5; hybrid mode now equals the real API.

export const isLive = (pathname: string) => LIVE_PREFIXES.some((l) => pathname.startsWith(l.prefix))
