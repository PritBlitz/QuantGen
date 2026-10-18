import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { resetMockDb } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { resetProofSnapshots } from '@/mocks/v2/proof'

// jsdom has no canvas, so lightweight-charts can't render; the chart is exercised in
// browser screenshots/e2e instead. A no-op stub keeps the rest of the page testable.
vi.mock('lightweight-charts', () => {
  const series = { setData: vi.fn(), update: vi.fn(), applyOptions: vi.fn() }
  const chart = {
    addSeries: vi.fn(() => series),
    applyOptions: vi.fn(),
    remove: vi.fn(),
    timeScale: vi.fn(() => ({ fitContent: vi.fn() })),
  }
  return { createChart: vi.fn(() => chart), AreaSeries: {}, ColorType: { Solid: 'solid' } }
})

// Lazy routes are transformed on first import in tests; allow for that cold start.
configure({ asyncUtilTimeout: 10_000 })

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

// jsdom lacks pointer capture and scrollIntoView, which Radix Select uses when it opens.
Element.prototype.hasPointerCapture ??= () => false
Element.prototype.releasePointerCapture ??= () => {}
Element.prototype.scrollIntoView ??= () => {}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  resetMockDb()
  resetProofSnapshots()
  // The mock refresh cookie lands in jsdom's jar; drop it so sessions don't leak across tests.
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; Max-Age=0; Path=/`
  }
  useAuthStore.setState({ accessToken: null, expiresAt: null, user: null, status: 'unknown' })
})
afterAll(() => server.close())
