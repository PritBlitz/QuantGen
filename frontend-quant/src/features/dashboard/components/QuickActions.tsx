import {
  ArrowDownToLine,
  ArrowUpRight,
  CandlestickChart,
  HandCoins,
  PiggyBank,
  QrCode,
  ReceiptText,
  type LucideIcon,
} from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { Link } from 'react-router'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { DashCard } from './CardShell'

const ACTIONS: Array<{ to: string; label: string; icon: LucideIcon }> = [
  { to: '/transfer', label: 'Send', icon: ArrowUpRight },
  { to: '/pay/requests', label: 'Request', icon: ArrowDownToLine },
  { to: '/pay/qr', label: 'Scan / QR', icon: QrCode },
  { to: '/accounts', label: 'Add money', icon: PiggyBank },
  { to: '/pay/bills', label: 'Pay a bill', icon: ReceiptText },
  { to: '/markets', label: 'Invest', icon: CandlestickChart },
  { to: '/savings', label: 'New goal', icon: HandCoins },
]

export function QuickActions({ className }: { className?: string }) {
  const reduce = useReducedMotion()
  return (
    <DashCard title="Move money" labelledBy="actions-title" className={className}>
      <ul className="grid flex-1 grid-cols-3 gap-2 sm:grid-cols-4 lg:grid-cols-3">
        {ACTIONS.slice(0, 6).map(({ to, label, icon: Icon }, i) => (
          <motion.li
            key={label}
            initial={reduce ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, ease: EASE_OUT_EXPO, delay: 0.05 * i }}
          >
            <Link
              to={to}
              className="group flex h-full min-h-[5.5rem] flex-col items-start justify-between gap-2 rounded-[16px] bg-surface-2/70 p-3.5 text-[0.8125rem] font-medium transition-[transform,border-color,background-color] duration-[var(--dur-micro)] hairline hover:border-border-strong hover:bg-surface-2 active:scale-[0.97]"
            >
              <span className="grid size-9 place-items-center rounded-xl bg-accent text-accent-ink shadow-[0_6px_18px_-8px_color-mix(in_oklab,var(--accent)_80%,transparent)] transition-transform group-hover:-translate-y-0.5">
                <Icon aria-hidden="true" className="size-4" />
              </span>
              {label}
            </Link>
          </motion.li>
        ))}
      </ul>
      <p className="mt-3 hidden text-xs text-muted lg:block">
        Tip: press <kbd className="num rounded bg-surface-2 px-1">Ctrl K</kbd> anywhere.
      </p>
    </DashCard>
  )
}
