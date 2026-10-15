import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { refreshSession } from '@/api/client'
import { authApi } from '@/api/endpoints'
import type { LoginRequest, RegisterRequest } from '@/api/types'
import { useAuthStore } from './store'

/** On app load: exchange the refresh cookie for an access token (once, single-flight). */
export function useRestoreSession() {
  const status = useAuthStore((s) => s.status)
  useEffect(() => {
    if (status === 'unknown') void refreshSession()
  }, [status])
  return status
}

/** Refresh silently ~60 s before the access token expires (DESIGN.md §3.6). */
export function useSilentRefresh() {
  const expiresAt = useAuthStore((s) => s.expiresAt)
  useEffect(() => {
    if (!expiresAt) return
    const wait = Math.max(5_000, expiresAt - Date.now() - 60_000)
    const t = window.setTimeout(() => void refreshSession(), wait)
    return () => window.clearTimeout(t)
  }, [expiresAt])
}

export function useLogin() {
  const setSession = useAuthStore((s) => s.setSession)
  return useMutation({
    mutationFn: (body: LoginRequest) => authApi.login(body),
    onSuccess: setSession,
  })
}

export function useRegister() {
  const setSession = useAuthStore((s) => s.setSession)
  return useMutation({
    // Register returns a UserDto only; sign in straight after with the same credentials.
    mutationFn: async (body: RegisterRequest) => {
      await authApi.register(body)
      return authApi.login({ email: body.email, password: body.password })
    },
    onSuccess: setSession,
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  const clearSession = useAuthStore((s) => s.clearSession)
  const navigate = useNavigate()
  return useCallback(
    async (reason?: 'idle') => {
      try {
        await authApi.logout()
      } catch {
        // Even if the server call fails, the local session must end.
      }
      clearSession()
      queryClient.clear()
      void navigate('/login', { replace: true, state: reason ? { reason } : undefined })
    },
    [clearSession, navigate, queryClient],
  )
}

const IDLE_MS = 10 * 60_000
const WARNING_SECONDS = 60
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const

/**
 * After 10 minutes without input, show a warning with a 60 s countdown, then log out
 * (DESIGN.md §3.6). Returns the remaining seconds while warning, otherwise null.
 */
export function useIdleTimeout(onTimeout: () => void) {
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null)
  const warning = secondsLeft !== null

  useEffect(() => {
    if (warning) return
    let timer = window.setTimeout(() => setSecondsLeft(WARNING_SECONDS), IDLE_MS)
    const onActivity = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => setSecondsLeft(WARNING_SECONDS), IDLE_MS)
    }
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, onActivity, { passive: true }))
    return () => {
      window.clearTimeout(timer)
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, onActivity))
    }
  }, [warning])

  useEffect(() => {
    if (secondsLeft === null) return
    if (secondsLeft <= 0) {
      onTimeout()
      return
    }
    const t = window.setTimeout(() => setSecondsLeft((s) => (s === null ? null : s - 1)), 1000)
    return () => window.clearTimeout(t)
  }, [secondsLeft, onTimeout])

  const stayActive = useCallback(() => setSecondsLeft(null), [])
  return { secondsLeft, stayActive }
}
