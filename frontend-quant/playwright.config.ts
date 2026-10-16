import { defineConfig, devices } from '@playwright/test'

/** E2E against the dev server with MSW mocks (no backend needed). */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // Dev server compiles lazy chunks on first hit; allow for cold starts.
  expect: { timeout: 10_000 },
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 360, height: 780 } } },
  ],
  webServer: {
    // QG_LIVE=1 runs the real API path (Vite proxy → :8080) for e2e/live.spec.ts.
    command: process.env.QG_LIVE ? 'npm run dev' : 'npm run dev:mock',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
