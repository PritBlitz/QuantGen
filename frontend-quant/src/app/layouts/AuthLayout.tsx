import { ArrowLeft, ShieldCheck } from 'lucide-react'
import { Link, Outlet } from 'react-router'
import { Logo } from '@/components/Logo'
import { ThemeToggle } from '@/components/ThemeToggle'
import { MeshGradient } from '@/features/auth/components/MeshGradient'

/** Split screen: form on the left, a quiet tinted panel on the right (DESIGN.md v3 §4). */
export function AuthLayout() {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <div className="flex flex-col px-4 py-6 sm:px-10">
        <header className="flex items-center justify-between">
          <Link to="/" aria-label="QuantGen home">
            <Logo />
          </Link>
          <ThemeToggle />
        </header>
        <main className="flex flex-1 items-center justify-center py-12">
          <Outlet />
        </main>
        <footer className="flex items-center justify-between text-xs text-muted">
          <Link to="/" className="inline-flex items-center gap-1.5 hover:text-text">
            <ArrowLeft aria-hidden="true" className="size-3.5" /> Back to home
          </Link>
          <span className="inline-flex items-center gap-1.5">
            <ShieldCheck aria-hidden="true" className="size-3.5" /> Encrypted session
          </span>
        </footer>
      </div>

      <aside className="relative hidden overflow-hidden border-l border-border lg:block">
        <MeshGradient />
        <div className="relative flex h-full flex-col justify-end p-14">
          <blockquote className="max-w-md">
            <p className="font-display text-4xl text-text">
              Every rupee, accounted for. <span className="text-muted">Twice.</span>
            </p>
            <footer className="mt-6 text-sm text-muted">
              Double-entry ledger · ACID transfers · Idempotent by design
            </footer>
          </blockquote>
        </div>
      </aside>
    </div>
  )
}
