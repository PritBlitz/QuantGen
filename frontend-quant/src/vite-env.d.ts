/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" → MSW mocks in dev. Ignored in production builds. */
  readonly VITE_USE_MOCKS?: string
  /** "true" → live endpoints (src/mocks/live.ts) pass through to the real backend. */
  readonly VITE_MOCKS_HYBRID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
