import { Navigate, Outlet, useLocation } from 'react-router'
import { LogoMark } from '@/components/Logo'
import { useAuthStore } from '@/features/auth/store'

export function SessionSplash() {
  return (
    <div className="grid min-h-dvh place-items-center" role="status" aria-label="Loading">
      <LogoMark className="size-10 animate-pulse" />
    </div>
  )
}

/** Authenticated routes. Server-side authZ is the real gate; this is UX only. */
export function RequireAuth() {
  const status = useAuthStore((s) => s.status)
  const location = useLocation()
  if (status === 'unknown') return <SessionSplash />
  if (status === 'anonymous') {
    // Path only (no query/hash): never carry identifiers through navigation state.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}

/** Login/register: bounce signed-in users to the dashboard. */
export function GuestOnly() {
  const status = useAuthStore((s) => s.status)
  if (status === 'unknown') return <SessionSplash />
  if (status === 'authenticated') return <Navigate to="/dashboard" replace />
  return <Outlet />
}
