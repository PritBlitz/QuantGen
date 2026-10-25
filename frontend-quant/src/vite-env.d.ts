/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" → MSW mocks in dev. Ignored in production builds. */
  readonly VITE_USE_MOCKS?: string
  /** "true" → live endpoints (src/mocks/live.ts) pass through to the real backend. */
  readonly VITE_MOCKS_HYBRID?: string
  /** agent-quant's MCP endpoint shown on Settings → Developer (default http://127.0.0.1:8090/mcp). */
  readonly VITE_MCP_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
