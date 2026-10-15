/// <reference types="vitest/config" />
import { rm } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, type Plugin } from 'vite'

/** public/mockServiceWorker.js is for dev mocks only; never ship it. */
function stripMockWorker(): Plugin {
  let outDir = ''
  return {
    name: 'qg-strip-mock-worker',
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir)
    },
    async closeBundle() {
      await rm(resolve(outDir, 'mockServiceWorker.js'), { force: true })
    },
  }
}

export default defineConfig(({ mode }) => {
  // `npm run dev:mock` → MSW on. Real `.env.*` files are git-ignored at the repo root,
  // so the mock switch is derived from the mode instead of a committed env file.
  if (mode === 'mock' || mode === 'hybrid') process.env.VITE_USE_MOCKS ??= 'true'
  // `npm run dev:hybrid` → endpoints listed in src/mocks/live.ts hit the real backend.
  if (mode === 'hybrid') process.env.VITE_MOCKS_HYBRID ??= 'true'

  return {
    plugins: [react(), tailwindcss(), stripMockWorker()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: 'http://localhost:8080', changeOrigin: false },
        '/ws': { target: 'http://localhost:8080', ws: true, changeOrigin: false },
      },
    },
    build: {
      sourcemap: false,
    },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
      testTimeout: 15_000,
      include: ['src/**/*.test.{ts,tsx}'],
    },
  }
})
