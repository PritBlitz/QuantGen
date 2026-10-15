import type { ReactNode } from 'react'
import { Money } from '@/components/Money'
import { RollingMoney } from '@/components/RollingMoney'
import { Skeleton } from '@/components/ui/skeleton'
import { Decimal, sumMoney } from '@/lib/money'
import { useAccounts, useLoans, usePortfolio } from '../hooks'
import { CardError, DashCard } from './CardShell'

/**
 * Net worth = cash across accounts + portfolio market value − outstanding loan principal.
 * All Decimal; shown only once every input is the server's truth (no optimistic values).
 */
export function NetWorthCard({ className }: { className?: string }) {
  const accounts = useAccounts()
  const portfolio = usePortfolio()
  const loans = useLoans()

  const pending = accounts.isPending || portfolio.isPending || loans.isPending
  const failed = accounts.isError || portfolio.isError || loans.isError

  let body
  if (pending) {
    body = (
      <div className="space-y-6">
        <Skeleton className="h-16 w-3/4 sm:h-20" />
        <div className="grid grid-cols-3 gap-4">
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
        </div>
      </div>
    )
  } else if (failed) {
    body = (
      <CardError
        what="your net worth"
        onRetry={() => {
          void accounts.refetch()
          void portfolio.refetch()
          void loans.refetch()
        }}
      />
    )
  } else {
    const cash = sumMoney(accounts.data.map((a) => a.balance))
    const invested = new Decimal(portfolio.data.totalMarketValue)
    const owed = sumMoney(
      loans.data.filter((l) => l.status === 'ACTIVE').map((l) => l.outstandingPrincipal),
    )
    const net = cash.plus(invested).minus(owed)
    body = (
      <>
        <div aria-live="polite" aria-atomic="true">
          <RollingMoney value={net} className="font-display text-[clamp(2.75rem,8vw,4.5rem)]" />
        </div>
        <p className="mt-2 text-sm text-muted">
          Unrealized P&amp;L{' '}
          <Money value={portfolio.data.totalUnrealizedPnl} tone="pnl" className="text-sm" />
        </p>
        <dl className="mt-auto grid grid-cols-1 gap-3 pt-8 text-sm min-[420px]:grid-cols-3">
          <Breakdown label="Cash" value={<Money value={cash} />} />
          <Breakdown label="Investments" value={<Money value={invested} />} />
          <Breakdown
            label="Loans owed"
            value={owed.isZero() ? <Money value={owed} /> : <Money value={owed.neg()} />}
          />
        </dl>
      </>
    )
  }

  return (
    <DashCard title="Net worth" labelledBy="networth-title" className={className}>
      {body}
    </DashCard>
  )
}

function Breakdown({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-[var(--radius-control)] bg-surface-2 px-3.5 py-3">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 font-medium">{value}</dd>
    </div>
  )
}
