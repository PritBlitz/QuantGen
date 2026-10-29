import {
  ArrowDownToLine,
  ArrowLeftRight,
  CandlestickChart,
  HandCoins,
  LayoutGrid,
  Plus,
  QrCode,
  ReceiptText,
  Sparkles,
  Target,
} from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, NavLink, useNavigate } from 'react-router'
import { useFlag } from '@/api/flags'
import { Dialog, SheetContent } from '@/components/ui/dialog'
import { useAuthStore } from '@/features/auth/store'
import { useCopilotUi } from '@/features/copilot/uiStore'
import { cn } from '@/lib/utils'
import { SECONDARY_NAV, useMainNav, useNavGroups, type NavItem } from '../nav'

/**
 * Mobile navigation (DESIGN.md v2 §4): a floating glass dock with four destinations, a centre
 * action button (customers) and a "More" sheet listing every page. Hidden from `lg` up.
 */
export function MobileDock({ onOpenPalette }: { onOpenPalette: () => void }) {
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const flat = useMainNav()
  const [actionsOpen, setActionsOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)

  // Five cells: customers get [link][link][+ action][link][More]; admins [link]×4 [More].
  const pinned = flat.filter((i) => i.mobile)
  const links = pinned.slice(0, isAdmin ? 4 : 3)
  const cells: Array<NavItem | 'action' | null> = isAdmin
    ? [...links]
    : [links[0] ?? null, links[1] ?? null, 'action', links[2] ?? null]
  while (cells.length < 4) cells.push(null)

  return (
    <>
      <nav
        aria-label="Main"
        className="glass fixed inset-x-3 bottom-[calc(0.75rem+env(safe-area-inset-bottom))] z-30 grid h-16 grid-cols-5 items-center rounded-[12px] px-1 lg:hidden"
      >
        {cells.map((cell, i) =>
          cell === 'action' ? (
            <div key="action" className="grid place-items-center">
              <button
                type="button"
                onClick={() => setActionsOpen(true)}
                aria-label="Quick actions"
                className="grid size-12 place-items-center rounded-full bg-accent text-accent-ink transition-transform active:scale-95"
              >
                <Plus aria-hidden="true" className="size-6" />
              </button>
            </div>
          ) : cell ? (
            <DockLink key={cell.to} item={cell} />
          ) : (
            <span key={`pad-${i}`} />
          ),
        )}
        <button
          type="button"
          onClick={() => setMoreOpen(true)}
          className="flex flex-col items-center gap-1 py-2 text-[0.6875rem] text-muted"
        >
          <LayoutGrid aria-hidden="true" className="size-5" />
          More
        </button>
      </nav>

      {!isAdmin && (
        <QuickActionsSheet
          open={actionsOpen}
          onOpenChange={setActionsOpen}
          onOpenPalette={onOpenPalette}
        />
      )}
      <MoreSheet open={moreOpen} onOpenChange={setMoreOpen} />
    </>
  )
}

function DockLink({ item }: { item: NavItem }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      className={({ isActive }) =>
        cn(
          'flex flex-col items-center gap-1 py-2 text-[0.6875rem] transition-colors',
          isActive ? 'text-text' : 'text-muted',
        )
      }
    >
      {({ isActive }) => (
        <>
          <item.icon
            aria-hidden="true"
            className={cn(
              'size-5',
              isActive && (item.flag === 'ai' ? 'text-violet' : 'text-accent'),
            )}
          />
          {item.label}
        </>
      )}
    </NavLink>
  )
}

function QuickActionsSheet({
  open,
  onOpenChange,
  onOpenPalette,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  onOpenPalette: () => void
}) {
  const navigate = useNavigate()
  const aiOn = useFlag('ai')
  const openCopilot = useCopilotUi((s) => s.open)
  const go = (to: string) => () => {
    onOpenChange(false)
    void navigate(to)
  }
  const actions: Array<{ label: string; icon: ReactNode; onClick: () => void; tone?: 'violet' }> = [
    { label: 'Send', icon: <ArrowLeftRight />, onClick: go('/transfer') },
    { label: 'Request', icon: <ArrowDownToLine />, onClick: go('/pay/requests') },
    { label: 'Scan / my QR', icon: <QrCode />, onClick: go('/pay/qr') },
    { label: 'Pay a bill', icon: <ReceiptText />, onClick: go('/pay/bills') },
    { label: 'Buy shares', icon: <CandlestickChart />, onClick: go('/markets') },
    { label: 'New goal', icon: <Target />, onClick: go('/savings') },
    { label: 'Get a loan', icon: <HandCoins />, onClick: go('/loans') },
  ]
  if (aiOn)
    actions.push({
      label: 'Ask Copilot',
      icon: <Sparkles />,
      tone: 'violet',
      onClick: () => {
        onOpenChange(false)
        openCopilot()
      },
    })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" title="Quick actions" description="What would you like to do?">
        <div className="grid grid-cols-4 gap-2 px-4 pb-6 pt-5">
          {actions.map((a) => (
            <button
              key={a.label}
              type="button"
              onClick={a.onClick}
              className="flex flex-col items-center gap-2 rounded-2xl p-3 text-center text-xs transition-colors hover:bg-surface-2"
            >
              <span
                className={cn(
                  'grid size-12 place-items-center rounded-2xl hairline [&_svg]:size-5',
                  a.tone === 'violet' ? 'bg-violet/14 text-violet' : 'bg-surface-2 text-text',
                )}
              >
                {a.icon}
              </span>
              {a.label}
            </button>
          ))}
        </div>
        <button
          type="button"
          onClick={() => {
            onOpenChange(false)
            onOpenPalette()
          }}
          className="mx-4 mb-5 rounded-xl py-3 text-sm text-muted hairline"
        >
          Search everything…
        </button>
      </SheetContent>
    </Dialog>
  )
}

function MoreSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const groups = useNavGroups()
  const close = () => onOpenChange(false)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <SheetContent side="bottom" title="All pages">
        <div className="scroll-thin overflow-y-auto px-4 pb-6 pt-3">
          {[...groups, { label: 'You', items: SECONDARY_NAV }].map((g) => (
            <div key={g.label} className="mt-3">
              <p className="eyebrow px-1 pb-2">{g.label}</p>
              <div className="grid grid-cols-2 gap-2">
                {g.items.map((item) => (
                  <Link
                    key={item.to}
                    to={item.to}
                    onClick={close}
                    className="flex items-center gap-2.5 rounded-xl bg-surface-2/60 px-3 py-3 text-sm hairline"
                  >
                    <item.icon aria-hidden="true" className="size-4 text-muted" />
                    {item.label}
                  </Link>
                ))}
              </div>
            </div>
          ))}
        </div>
      </SheetContent>
    </Dialog>
  )
}
