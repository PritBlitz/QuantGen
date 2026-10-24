/**
 * Backend milestones that are live on the real API (announced by the backend session). Some v2
 * features reuse a v1 route with new body fields (e.g. a LIMIT order is a `POST /orders`); a
 * backend that predates the milestone would ignore those fields and do the v1 thing instead
 * (fill at market). The UI offers such features only when the milestone is listed here, or when
 * every request is mocked. Add a milestone when the backend announces it.
 */
export const SHIPPED_MILESTONES: ReadonlyArray<string> = [
  'V2-M0',
  'V2-M1',
  'V2-MA',
  'V2-M2',
  'V2-M3',
  'V2-M7',
  'V2-M8',
  'V2-M9',
  'V2-M6',
  'V2-M10',
  'V2-M5',
  'V2-M5-extended',
  'V2-M5-bills',
]

const fullyMocked =
  import.meta.env.MODE === 'test' ||
  (import.meta.env.VITE_USE_MOCKS === 'true' && import.meta.env.VITE_MOCKS_HYBRID !== 'true')

export const backendHas = (milestone: string): boolean =>
  fullyMocked || SHIPPED_MILESTONES.includes(milestone)
