import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { Bell, ChevronDown, LogOut, Search, Sparkles } from 'lucide-react'
import { DropdownMenu } from 'radix-ui'
import { useCallback, useState } from 'react'
import { Link, NavLink, useLocation, useOutlet } from 'react-router'
import { useFlag } from '@/api/flags'
import { useUnreadCount } from '@/api/queries'
import { Aurora } from '@/components/Aurora'
import { Logo, LogoMark } from '@/components/Logo'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { useIdleTimeout, useLogout, useSilentRefresh } from '@/features/auth/session'
import { useAuthStore } from '@/features/auth/store'
import { CopilotSheet } from '@/features/copilot/CopilotSheet'
import { useCopilotUi } from '@/features/copilot/uiStore'
import { useLivePriceFeed } from '@/features/markets/hooks'
import { LedgerSealChip } from '@/features/proof/LedgerSealChip'
import { StepUpDialog } from '@/features/security/StepUpDialog'
import { pageVariants, pageVariantsReduced } from '@/lib/motion'
import { cn } from '@/lib/utils'
import { CommandPalette } from '../CommandPalette'
import { homeFor, SECONDARY_NAV, useNavGroups } from '../nav'
import { MobileDock } from './MobileDock'

export function AppShell() {
  const location = useLocation()
  const outlet = useOutlet()
  const reduce = useReducedMotion()
  const user = useAuthStore((s) => s.user)
  const logout = useLogout()
  const [paletteOpen, setPaletteOpen] = useState(false)
  const aiOn = useFlag('ai')
  const openCopilot = useCopilotUi((s) => s.open)

  useSilentRefresh()
  useLivePriceFeed()
  const onIdle = useCallback(() => void logout('idle'), [logout])
  const { secondsLeft, stayActive } = useIdleTimeout(onIdle)

  const groups = useNavGroups()
  const home = homeFor(user?.role)
  const isAdmin = user?.role === 'ADMIN'
  const unread = useUnreadCount()
  const unreadCount = unread.data?.count ?? 0

  return (
    <div className="min-h-dvh">
      <Aurora />
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[60] focus:rounded-md focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-ink"
      >
        Skip to content
      </a>

      {/* Desktop rail: floating glass panel. */}
      <aside className="glass fixed inset-y-3 left-3 z-40 hidden w-[var(--rail-w)] flex-col rounded-[26px] lg:flex">
        <div className="flex items-center justify-between px-5 pb-2 pt-5">
          <Link to={home} aria-label="QuantGen home">
            <Logo />
          </Link>
          {isAdmin && (
            <span className="rounded-full bg-violet/14 px-2 py-0.5 text-[0.625rem] font-semibold uppercase tracking-wider text-violet">
              Ops
            </span>
          )}
        </div>
        <nav aria-label="Main" className="scroll-thin flex-1 overflow-y-auto px-3 pb-3">
          {groups.map((group) => (
            <div key={group.label} className="mt-4">
              <p className="eyebrow px-3 pb-1.5 text-[0.625rem]">{group.label}</p>
              <ul className="flex flex-col gap-0.5">
                {group.items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.end}
                      className={({ isActive }) =>
                        cn(
                          'group relative flex items-center gap-3 rounded-[12px] px-3 py-[0.5625rem] text-sm transition-colors',
                          isActive ? 'text-text' : 'text-muted hover:text-text',
                        )
                      }
                    >
                      {({ isActive }) => (
                        <>
                          {isActive && (
                            <motion.span
                              layoutId="rail-active"
                              transition={{ type: 'spring', stiffness: 420, damping: 36 }}
                              className="absolute inset-0 -z-10 rounded-[12px] bg-surface-2 hairline"
                            />
                          )}
                          <item.icon
                            aria-hidden="true"
                            className={cn(
                              'size-[1.0625rem] transition-colors',
                              isActive
                                ? item.flag === 'ai' || item.flag === 'autopilot'
                                  ? 'text-violet'
                                  : 'text-accent'
                                : 'group-hover:text-text',
                            )}
                          />
                          {item.label}
                          {(item.flag === 'ai' || item.flag === 'autopilot') && (
                            <span className="ml-auto rounded-full bg-violet/14 px-1.5 py-px text-[0.5625rem] font-semibold uppercase tracking-wider text-violet">
                              AI
                            </span>
                          )}
                        </>
                      )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <div className="flex flex-col gap-2 border-t border-border p-3">
          {!isAdmin && <LedgerSealChip />}
          <button
            type="button"
            onClick={() => setPaletteOpen(true)}
            className="flex items-center justify-between rounded-[12px] px-3 py-2 text-sm text-muted hairline transition-colors hover:border-border-strong hover:text-text"
          >
            Quick actions
            <kbd className="num rounded-md bg-surface-2 px-1.5 py-0.5 text-[0.6875rem]">Ctrl K</kbd>
          </button>
        </div>
      </aside>

      <div className="flex min-h-dvh min-w-0 flex-col lg:pl-[calc(var(--rail-w)+1.5rem)]">
        {/* Floating top bar. */}
        <header className="sticky top-0 z-30 px-3 pt-3 lg:pl-0 lg:pr-3">
          <div className="glass flex h-14 items-center gap-2 rounded-[18px] px-3 sm:px-4">
            <Link to={home} className="lg:hidden" aria-label="QuantGen home">
              <LogoMark />
            </Link>
            <button
              type="button"
              onClick={() => setPaletteOpen(true)}
              className="group ml-1 flex h-9 min-w-0 flex-1 items-center gap-2.5 rounded-[11px] bg-surface-2/70 px-3 text-left text-sm text-muted transition-colors hover:text-text sm:max-w-md"
            >
              <Search aria-hidden="true" className="size-4 shrink-0" />
              <span className="truncate">
                {aiOn && !isAdmin ? 'Search, jump, or ask Copilot…' : 'Search or jump to…'}
              </span>
              <kbd className="num ml-auto hidden rounded-md bg-surface px-1.5 py-0.5 text-[0.6875rem] sm:block">
                Ctrl K
              </kbd>
            </button>
            <div className="ml-auto flex items-center gap-0.5">
              {aiOn && !isAdmin && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => openCopilot()}
                  className="hidden gap-1.5 text-violet hover:bg-violet/10 sm:inline-flex"
                >
                  <Sparkles aria-hidden="true" /> Copilot
                </Button>
              )}
              <Button variant="ghost" size="icon" asChild>
                <Link
                  to="/notifications"
                  aria-label={
                    unreadCount ? `Notifications, ${unreadCount} unread` : 'Notifications'
                  }
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
                  <Button variant="ghost" className="gap-2 px-1.5">
                    <span
                      aria-hidden="true"
                      className="grid size-8 place-items-center rounded-full bg-gradient-to-br from-violet/40 to-cyan/30 text-xs font-semibold hairline"
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
                    sideOffset={10}
                    className="z-50 min-w-60 rounded-[18px] bg-surface-solid p-1.5 hairline shadow-[var(--shadow-pop)]"
                  >
                    <div className="px-3 py-2.5">
                      <p className="truncate text-sm font-medium">{user?.fullName}</p>
                      <p className="truncate text-xs text-muted">{user?.email}</p>
                    </div>
                    <DropdownMenu.Separator className="my-1 h-px bg-border" />
                    {SECONDARY_NAV.map((item) => (
                      <DropdownMenu.Item key={item.to} asChild className={menuItem}>
                        <Link to={item.to}>
                          <item.icon aria-hidden="true" /> {item.label}
                        </Link>
                      </DropdownMenu.Item>
                    ))}
                    <DropdownMenu.Separator className="my-1 h-px bg-border" />
                    <DropdownMenu.Item className={menuItem} onSelect={() => void logout()}>
                      <LogOut aria-hidden="true" /> Sign out
                    </DropdownMenu.Item>
                  </DropdownMenu.Content>
                </DropdownMenu.Portal>
              </DropdownMenu.Root>
            </div>
          </div>
        </header>

        <main
          id="main"
          className="flex-1 px-4 pb-32 pt-8 sm:px-6 lg:pb-16 lg:pl-3 lg:pr-8 lg:pt-10"
        >
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={location.pathname}
              variants={reduce ? pageVariantsReduced : pageVariants}
              initial="initial"
              animate="animate"
              exit="exit"
              className="mx-auto w-full max-w-[1320px]"
            >
              {outlet}
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      <MobileDock onOpenPalette={() => setPaletteOpen(true)} />

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onLogout={() => void logout()}
      />
      {!isAdmin && <CopilotSheet />}
      <StepUpDialog />

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
  'flex cursor-pointer items-center gap-2.5 rounded-[11px] px-3 py-2 text-sm outline-none data-[highlighted]:bg-surface-2 [&_svg]:size-4 [&_svg]:text-muted'

function initials(name?: string) {
  if (!name) return '·'
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts.at(-1)?.[0] ?? '') : '')).toUpperCase()
}
