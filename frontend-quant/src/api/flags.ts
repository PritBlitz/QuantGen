import { useQuery } from '@tanstack/react-query'
import { flagsApi } from './endpoints.v2'
import type { FeatureFlag, FeatureFlagsDto } from './types'

/** Every flag off: what the UI assumes until the backend answers (or if it can't). */
export const FLAGS_OFF: FeatureFlagsDto = {
  ai: false,
  autopilot: false,
  razorpay: false,
  cards: false,
  mcp: false,
}

/**
 * Feature flags (PRD §12.14, F-FLAG-1). UX only: a hidden entry point is not a security
 * control; the server returns `503 FEATURE_DISABLED` for switched-off features regardless.
 * If the endpoint isn't live yet (v1 backend → 404), everything stays off.
 */
export function useFlags(): FeatureFlagsDto {
  const q = useQuery({
    queryKey: ['feature-flags'],
    queryFn: flagsApi.get,
    staleTime: 60_000,
    retry: false,
  })
  return q.data ?? FLAGS_OFF
}

export function useFlag(flag: FeatureFlag): boolean {
  return useFlags()[flag]
}
