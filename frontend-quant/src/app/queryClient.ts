import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/errors'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: true,
        // Retry transient failures only; 4xx are answers, not glitches.
        retry: (count, error) =>
          count < 2 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      },
      // Never auto-retry mutations: money endpoints rely on explicit idempotent retries.
      mutations: { retry: false },
    },
  })
}
