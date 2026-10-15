import { ArrowLeft, Hammer } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { NAV, SECONDARY_NAV } from './nav'

/** Placeholder for pages scheduled in later milestones (PRD §10). */
export function ComingSoonPage() {
  const { pathname } = useLocation()
  const item = [...NAV, ...SECONDARY_NAV].find((n) => n.to === pathname)
  return (
    <div className="space-y-6">
      <h1 className="font-display text-4xl">{item?.label ?? 'Coming soon'}</h1>
      <Card className="flex flex-col items-start gap-4 sm:flex-row sm:items-center">
        <span className="grid size-10 shrink-0 place-items-center rounded-full bg-surface-2">
          <Hammer aria-hidden="true" className="size-4 text-muted" />
        </span>
        <div className="flex-1">
          <p className="font-medium">This screen is being built.</p>
          <p className="text-sm text-muted">
            It ships with milestone {item?.milestone ?? 'soon'}. Your dashboard has a live summary
            in the meantime.
          </p>
        </div>
        <Button variant="secondary" asChild>
          <Link to="/dashboard">
            <ArrowLeft aria-hidden="true" /> Dashboard
          </Link>
        </Button>
      </Card>
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
