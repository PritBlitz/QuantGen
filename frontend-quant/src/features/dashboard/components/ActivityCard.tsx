import { motion } from 'motion/react'
import {
  ArrowDownLeft,
  ArrowUpRight,
  CandlestickChart,
  CreditCard,
  HandCoins,
  Percent,
  PiggyBank,
  ReceiptText,
  Undo2,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import type { AccountDto, TransactionDto } from '@/api/types'
import { Money } from '@/components/Money'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tooltip } from '@/components/ui/tooltip'
import { maskShort } from '@/lib/mask'
import { DURATION, EASE_OUT_EXPO, staggerDelay } from '@/lib/motion'
import { formatAbsolute, formatRelative } from '@/lib/time'
import { useRecentActivity } from '../hooks'
import { CardError, DashCard } from './CardShell'

const ICON: Record<TransactionDto['type'], LucideIcon> = {
  DEPOSIT: PiggyBank,
  TRANSFER: ArrowUpRight,
  TRADE: CandlestickChart,
  LOAN_DISBURSAL: HandCoins,
  LOAN_REPAYMENT: HandCoins,
  REVERSAL: Undo2,
  BILL_PAYMENT: ReceiptText,
  INTEREST: Percent,
  CARD: CreditCard,
  REFUND: Undo2,
  TOPUP: Wallet,
}

export function ActivityCard({
  accounts,
  className,
}: {
  accounts: AccountDto[] | undefined
  className?: string
}) {
  const { items, isPending, isError, refetch } = useRecentActivity(accounts)
  const numberOf = new Map((accounts ?? []).map((a) => [a.id, a.accountNumber]))

  let body
  if (!accounts || isPending) {
    body = (
      <div className="space-y-3">
        {[0, 1, 2, 3, 4].map((i) => (
          <Skeleton key={i} className="h-12" />
        ))}
      </div>
    )
  } else if (isError) {
    body = <CardError what="recent activity" onRetry={refetch} />
  } else if (items.length === 0) {
    body = (
      <div className="flex flex-1 flex-col items-start justify-center gap-3">
        <p className="text-sm text-muted">No activity yet. Add money to get started.</p>
        <Button asChild size="sm" variant="secondary">
          <Link to="/accounts">Add money</Link>
        </Button>
      </div>
    )
  } else {
    body = (
      <ul className="-mx-2 divide-y divide-border">
        {items.map((tx, i) => {
          const credit = tx.direction === 'CREDIT'
          const Icon = tx.type === 'TRANSFER' && credit ? ArrowDownLeft : ICON[tx.type]
          const acct = numberOf.get(tx.accountId)
          return (
            <motion.li
              key={tx.id}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: DURATION.ui, ease: EASE_OUT_EXPO, delay: staggerDelay(i) }}
              className="flex items-center gap-3 px-2 py-3"
            >
              <span className="grid size-9 shrink-0 place-items-center rounded-full bg-surface-2">
                <Icon
                  aria-hidden="true"
                  className={credit ? 'size-4 text-gain' : 'size-4 text-muted'}
                />
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">{tx.description}</p>
                <p className="truncate text-xs text-muted">
                  <Tooltip content={formatAbsolute(tx.createdAt)}>
                    <time dateTime={tx.createdAt} tabIndex={0} className="rounded-sm">
                      {formatRelative(tx.createdAt)}
                    </time>
                  </Tooltip>
                  {acct && <span className="num"> · {maskShort(acct)}</span>}
                </p>
              </div>
              <Money value={tx.amount} tone={credit ? 'credit' : 'debit'} className="text-sm" />
            </motion.li>
          )
        })}
      </ul>
    )
  }

  return (
    <DashCard
      title="Recent activity"
      labelledBy="activity-title"
      className={className}
      action={
        <Button asChild variant="ghost" size="sm" className="-mr-2 text-muted">
          <Link to="/accounts">All transactions</Link>
        </Button>
      }
    >
      {body}
    </DashCard>
  )
}
