import { Navigate, Outlet, useLocation } from 'react-router'
import { LogoMark } from '@/components/Logo'
import { InShellNotFound } from './pages'
import { homeFor } from './nav'
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

/** Login/register: bounce signed-in users to their home screen. */
export function GuestOnly() {
  const status = useAuthStore((s) => s.status)
  const role = useAuthStore((s) => s.user?.role)
  if (status === 'unknown') return <SessionSplash />
  if (status === 'authenticated') return <Navigate to={homeFor(role)} replace />
  return <Outlet />
}

/**
 * Admin routes. UX only: the server returns 403 to non-admins regardless. A customer who
 * types an /admin URL sees the ordinary 404, so the console's existence isn't advertised.
 */
export function RequireAdmin() {
  const role = useAuthStore((s) => s.user?.role)
  if (role !== 'ADMIN') return <InShellNotFound />
  return <Outlet />
}
