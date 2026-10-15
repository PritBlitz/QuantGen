import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/features/auth/store'
import { toApiError } from './errors'
import type { AuthResponse } from './types'

export const API_BASE = '/api/v1'

export const api = axios.create({
  baseURL: API_BASE,
  timeout: 15_000,
  headers: { Accept: 'application/json' },
  // Credentials (the qg_refresh cookie) are only sent to /auth/* — see request interceptor.
  withCredentials: false,
})

const isAuthPath = (url: string | undefined) => !!url && url.startsWith('/auth/')

api.interceptors.request.use((config) => {
  if (isAuthPath(config.url)) {
    // The refresh cookie is scoped to /api/v1/auth; no bearer header on auth calls.
    config.withCredentials = true
    return config
  }
  const token = useAuthStore.getState().accessToken
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

// ── Single-flight refresh ───────────────────────────────────────────────────────────
let refreshInFlight: Promise<AuthResponse | null> | null = null

const REFRESH_LOCK = 'qg-auth-refresh'

/**
 * The refresh cookie is strictly single-use: the backend treats a second use of the same
 * cookie as theft and revokes the whole session family. So refreshes must never overlap:
 * - within a tab, concurrent callers share one in-flight promise;
 * - across tabs, a Web Lock serialises them, so each tab sends the cookie the previous
 *   refresh rotated in (the cookie jar is shared between tabs).
 */
function withRefreshLock<T>(fn: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator) {
    return navigator.locks.request(REFRESH_LOCK, { mode: 'exclusive' }, fn)
  }
  return fn()
}

/**
 * Exchange the refresh cookie for a new access token. Resolves `null` (and clears the
 * session) when the refresh is rejected.
 */
export function refreshSession(): Promise<AuthResponse | null> {
  refreshInFlight ??= withRefreshLock(() => api.post<AuthResponse>('/auth/refresh'))
    .then((res) => {
      useAuthStore.getState().setSession(res.data)
      return res.data
    })
    .catch(() => {
      useAuthStore.getState().clearSession()
      return null
    })
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

type RetriableConfig = InternalAxiosRequestConfig & { _qgRetried?: boolean }

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const config = error.config as RetriableConfig | undefined
    if (error.response?.status === 401 && config && !config._qgRetried && !isAuthPath(config.url)) {
      config._qgRetried = true
      // If another call already refreshed while this one was in flight, just retry with
      // the newer token rather than spending another single-use refresh.
      const sentWith = String(config.headers.get('Authorization') ?? '')
      const current = useAuthStore.getState().accessToken
      const token =
        current && sentWith !== `Bearer ${current}`
          ? current
          : (await refreshSession())?.accessToken
      if (token) {
        config.headers.set('Authorization', `Bearer ${token}`)
        return api.request(config)
      }
    }
    throw toApiError(error)
  },
)

/** Header for money-moving POSTs (PRD §7.1 🔒💰). */
export const idempotencyHeader = (key: string) => ({ 'Idempotency-Key': key })
