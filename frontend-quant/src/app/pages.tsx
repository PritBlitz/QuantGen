import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/features/auth/store'
import { homeFor } from './nav'

/** 404 inside the app shell (unknown in-app route, or a page this role can't see). */
export function InShellNotFound() {
  const role = useAuthStore((s) => s.user?.role)
  return (
    <div className="grid place-items-center py-20 text-center">
      <div>
        <p className="num text-sm text-muted">404</p>
        <h1 className="mt-2 font-display text-5xl">Nothing here.</h1>
        <p className="mt-3 text-muted">That page doesn’t exist, or it moved.</p>
        <Button asChild className="mt-8">
          <Link to={homeFor(role)}>
            <ArrowLeft aria-hidden="true" /> Back to {role === 'ADMIN' ? 'overview' : 'dashboard'}
          </Link>
        </Button>
      </div>
    </div>
  )
}

export function NotFoundPage() {
  return (
    <div className="grid min-h-dvh place-items-center px-4 text-center">
      <div>
        <p className="num text-sm text-muted">404</p>
        <h1 className="mt-2 font-display text-5xl">Nothing here.</h1>
        <p className="mt-3 text-muted">That page doesn’t exist, or it moved.</p>
        <Button asChild className="mt-8">
          <Link to="/">Go home</Link>
        </Button>
      </div>
    </div>
  )
}

export function RouteErrorPage() {
  return (
    <div className="grid min-h-dvh place-items-center px-4 text-center">
      <div>
        <h1 className="font-display text-5xl">Something broke.</h1>
        <p className="mt-3 text-muted">An unexpected error happened. Your money is safe.</p>
        <Button className="mt-8" onClick={() => window.location.assign('/')}>
          Reload QuantGen
        </Button>
      </div>
    </div>
  )
}
