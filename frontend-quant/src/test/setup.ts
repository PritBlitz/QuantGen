import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { resetMockDb } from '@/mocks/handlers'
import { server } from '@/mocks/node'

// Lazy routes are transformed on first import in tests; allow for that cold start.
configure({ asyncUtilTimeout: 5000 })

// jsdom lacks matchMedia (used by motion's reduced-motion hook).
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

// jsdom lacks IntersectionObserver/ResizeObserver (motion whileInView, Radix).
class NoopObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return []
  }
}
globalThis.IntersectionObserver ??= NoopObserver as unknown as typeof IntersectionObserver
globalThis.ResizeObserver ??= NoopObserver as unknown as typeof ResizeObserver

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  resetMockDb()
  // The mock refresh cookie lands in jsdom's jar; drop it so sessions don't leak across tests.
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; Max-Age=0; Path=/`
  }
  useAuthStore.setState({ accessToken: null, expiresAt: null, user: null, status: 'unknown' })
})
afterAll(() => server.close())
