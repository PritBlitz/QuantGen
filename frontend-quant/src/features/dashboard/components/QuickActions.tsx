import {
  ArrowLeftRight,
  CandlestickChart,
  HandCoins,
  PiggyBank,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { DashCard } from './CardShell'

const ACTIONS: Array<{ to: string; label: string; icon: LucideIcon }> = [
  { to: '/transfer', label: 'Transfer', icon: ArrowLeftRight },
  { to: '/accounts', label: 'Add money', icon: PiggyBank },
  { to: '/markets', label: 'Buy shares', icon: CandlestickChart },
  { to: '/loans', label: 'Get a loan', icon: HandCoins },
]

export function QuickActions({ className }: { className?: string }) {
  return (
    <DashCard title="Quick actions" labelledBy="actions-title" className={className}>
      <ul className="grid flex-1 grid-cols-2 gap-2">
        {ACTIONS.map(({ to, label, icon: Icon }) => (
          <li key={label}>
            <Link
              to={to}
              className="flex h-full min-h-24 flex-col justify-between gap-3 rounded-[var(--radius-control)] bg-surface-2 p-4 text-sm font-medium transition-[transform,border-color] duration-[var(--dur-micro)] hairline hover:border-muted/40 active:scale-[0.98]"
            >
              <span className="grid size-8 place-items-center rounded-full bg-accent text-accent-ink">
                <Icon aria-hidden="true" className="size-4" />
              </span>
              {label}
            </Link>
          </li>
        ))}
      </ul>
      <p className="mt-3 hidden text-xs text-muted lg:block">
        Tip: press <kbd className="num rounded bg-surface-2 px-1">Ctrl K</kbd> anywhere.
      </p>
    </DashCard>
  )
}
