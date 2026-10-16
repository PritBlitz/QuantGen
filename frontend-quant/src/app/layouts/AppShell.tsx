import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { Bell, ChevronDown, Command as CommandIcon, LogOut, UserRound } from 'lucide-react'
import { DropdownMenu } from 'radix-ui'
import { useCallback, useState } from 'react'
import { Link, NavLink, useLocation, useOutlet } from 'react-router'
import { useUnreadCount } from '@/api/queries'
import { Logo, LogoMark } from '@/components/Logo'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { useIdleTimeout, useLogout, useSilentRefresh } from '@/features/auth/session'
import { useAuthStore } from '@/features/auth/store'
import { useLivePriceFeed } from '@/features/markets/hooks'
import { pageVariants, pageVariantsReduced } from '@/lib/motion'
import { cn } from '@/lib/utils'
import { CommandPalette } from '../CommandPalette'
import { homeFor, useMainNav } from '../nav'

export function AppShell() {
  const location = useLocation()
  const outlet = useOutlet()
  const reduce = useReducedMotion()
  const user = useAuthStore((s) => s.user)
  const logout = useLogout()
  const [paletteOpen, setPaletteOpen] = useState(false)

  useSilentRefresh()
  useLivePriceFeed()
  const onIdle = useCallback(() => void logout('idle'), [logout])
  const { secondsLeft, stayActive } = useIdleTimeout(onIdle)

  const nav = useMainNav()
  const home = homeFor(user?.role)
  const isAdmin = user?.role === 'ADMIN'
  const unread = useUnreadCount()
  const unreadCount = unread.data?.count ?? 0

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[240px_minmax(0,1fr)]">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-ink"
      >
        Skip to content
      </a>

      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-border px-4 py-5 lg:flex">
        <Link to={home} className="px-2" aria-label="QuantGen home">
          <Logo />
        </Link>
        {isAdmin && (
          <p className="mt-8 px-3 text-[0.6875rem] font-medium uppercase tracking-wider text-muted">
            Operations
          </p>
        )}
        <nav aria-label="Main" className={cn('flex flex-col gap-0.5', isAdmin ? 'mt-2' : 'mt-10')}>
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'group flex items-center gap-3 rounded-[var(--radius-control)] px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'bg-surface-2 text-text'
                    : 'text-muted hover:bg-surface-2/60 hover:text-text',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <item.icon
                    aria-hidden="true"
                    className={cn('size-4', isActive && 'text-accent')}
                  />
                  {item.label}
                  {isActive && (
                    <span aria-hidden="true" className="ml-auto size-1.5 rounded-full bg-accent" />
                  )}
                </>
              )}
            </NavLink>
          ))}
        </nav>
        <button
          type="button"
          onClick={() => setPaletteOpen(true)}
          className="mt-auto flex items-center justify-between rounded-[var(--radius-control)] px-3 py-2 text-sm text-muted hairline hover:text-text"
        >
          Quick actions
          <kbd className="num rounded bg-surface-2 px-1.5 py-0.5 text-[0.6875rem]">Ctrl K</kbd>
        </button>
      </aside>

      <div className="flex min-w-0 flex-col">
        {/* Top bar */}
        <header className="sticky top-0 z-30 flex h-16 items-center gap-2 border-b border-border bg-bg/80 px-4 backdrop-blur-md sm:px-6">
          <Link to={home} className="lg:hidden" aria-label="QuantGen home">
            <LogoMark />
          </Link>
          <div className="ml-auto flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setPaletteOpen(true)}
              aria-label="Open command menu"
              className="lg:hidden"
            >
              <CommandIcon />
            </Button>
            <Button variant="ghost" size="icon" asChild>
              <Link
                to="/notifications"
                aria-label={unreadCount ? `Notifications, ${unreadCount} unread` : 'Notifications'}
                className="relative"
              >
                <Bell />
                {unreadCount > 0 && (
                  <span className="num absolute right-1.5 top-1.5 grid min-w-4 place-items-center rounded-full bg-accent px-1 text-[0.625rem] font-semibold leading-4 text-accent-ink">
                    {unreadCount > 9 ? '9+' : unreadCount}
                  </span>
                )}
              </Link>
            </Button>
            <ThemeToggle />
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <Button variant="ghost" className="gap-2 px-2">
                  <span
                    aria-hidden="true"
                    className="grid size-7 place-items-center rounded-full bg-surface-2 text-xs font-semibold hairline"
                  >
                    {initials(user?.fullName)}
                  </span>
                  <span className="sr-only">Account menu</span>
                  <ChevronDown aria-hidden="true" className="hidden text-muted sm:block" />
                </Button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content
                  align="end"
                  sideOffset={8}
                  className="z-50 min-w-56 rounded-[var(--radius-card)] bg-surface p-1.5 hairline shadow-[var(--shadow-pop)]"
                >
                  <div className="px-3 py-2">
                    <p className="truncate text-sm font-medium">{user?.fullName}</p>
                    <p className="truncate text-xs text-muted">{user?.email}</p>
                  </div>
                  <DropdownMenu.Separator className="my-1 h-px bg-border" />
                  <DropdownMenu.Item asChild className={menuItem}>
                    <Link to="/profile">
                      <UserRound aria-hidden="true" /> Profile
                    </Link>
                  </DropdownMenu.Item>
                  <DropdownMenu.Item className={menuItem} onSelect={() => void logout()}>
                    <LogOut aria-hidden="true" /> Sign out
                  </DropdownMenu.Item>
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          </div>
        </header>

        <main id="main" className="flex-1 px-4 pb-28 pt-6 sm:px-6 lg:px-10 lg:pb-12 lg:pt-8">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={location.pathname}
              variants={reduce ? pageVariantsReduced : pageVariants}
              initial="initial"
              animate="animate"
              exit="exit"
              className="mx-auto w-full max-w-[1280px]"
            >
              {outlet}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      {/* Mobile bottom tab bar */}
      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-30 grid grid-cols-5 border-t border-border bg-bg/90 pb-[env(safe-area-inset-bottom)] backdrop-blur-md lg:hidden"
      >
        {nav
          .filter((i) => i.mobile)
          .map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'flex flex-col items-center gap-1 py-2.5 text-[0.6875rem] transition-colors',
                  isActive ? 'text-text' : 'text-muted',
                )
              }
            >
              {({ isActive }) => (
                <>
                  <item.icon
                    aria-hidden="true"
                    className={cn('size-5', isActive && 'text-accent')}
                  />
                  {item.label}
                </>
              )}
            </NavLink>
          ))}
      </nav>

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onLogout={() => void logout()}
      />

      <Dialog open={secondsLeft !== null} onOpenChange={(open) => !open && stayActive()}>
        <DialogContent
          title="Still there?"
          description="For your security, we’ll sign you out soon because you’ve been inactive."
        >
          <p className="text-sm">
            Signing out in{' '}
            <span className="num font-semibold" aria-live="polite">
              {secondsLeft ?? 0}s
            </span>
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => void logout()}>
              Sign out now
            </Button>
            <Button onClick={stayActive} autoFocus>
              Stay signed in
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}

const menuItem =
  'flex cursor-pointer items-center gap-2.5 rounded-[var(--radius-control)] px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-2 [&_svg]:size-4 [&_svg]:text-muted'

function initials(name?: string) {
  if (!name) return '·'
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts.at(-1)?.[0] ?? '') : '')).toUpperCase()
}
