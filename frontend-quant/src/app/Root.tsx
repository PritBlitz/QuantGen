import { Outlet, ScrollRestoration } from 'react-router'
import { useRestoreSession } from '@/features/auth/session'

/** Top of the route tree: restores the session from the refresh cookie once on load. */
export function Root() {
  useRestoreSession()
  return (
    <>
      <ScrollRestoration />
      <Outlet />
    </>
  )
}
