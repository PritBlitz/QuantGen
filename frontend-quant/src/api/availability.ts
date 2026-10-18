import { toApiError } from './errors'

/**
 * True when an endpoint simply isn't built on this backend yet (404 / 503 FEATURE_DISABLED).
 * v2 dashboard cards hide themselves in that case instead of showing an error, so the app
 * still looks right against a backend that hasn't shipped that milestone.
 */
export function isUnavailable(error: unknown): boolean {
  if (!error) return false
  const e = toApiError(error)
  return e.status === 404 || e.code === 'FEATURE_DISABLED' || e.code === 'GATEWAY_UNAVAILABLE'
}
