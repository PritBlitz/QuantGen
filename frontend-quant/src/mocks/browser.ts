import { http, passthrough } from 'msw'
import { setupWorker } from 'msw/browser'
import { handlers } from './allHandlers'
import { isLive } from './live'

const hybrid = import.meta.env.VITE_MOCKS_HYBRID === 'true'

// Hybrid: live endpoints go to the real backend (first matching handler wins).
const liveFirst = hybrid
  ? [
      http.all('/api/v1/*', ({ request }) =>
        isLive(new URL(request.url).pathname) ? passthrough() : undefined,
      ),
    ]
  : []

export const worker = setupWorker(...liveFirst, ...handlers)
