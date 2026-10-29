import { Link } from 'react-router'
import { ArrowUpRight } from 'lucide-react'
import { isUnavailable } from '@/api/availability'
import { Sparkline } from '@/components/charts/Bits'
import { Money } from '@/components/Money'
import { RollingMoney } from '@/components/RollingMoney'
import { Skeleton } from '@/components/ui/skeleton'
import { useBalanceHistory } from '@/features/analytics/hooks'
import { Decimal, sumMoney } from '@/lib/money'
import { useAccounts, useLoans, usePortfolio } from '../hooks'
import { CardError, DashCard } from './CardShell'

/**
 * Net worth = cash + savings (goals, FDs) + portfolio market value − outstanding loan principal.
 * All Decimal; shown only once every input is the server's truth (no optimistic values). The
 * 30-day sparkline appears when the analytics endpoint is available (V2-MA).
 */
export function NetWorthCard({ className }: { className?: string }) {
  const accounts = useAccounts()
  const portfolio = usePortfolio()
  const loans = useLoans()
  const history = useBalanceHistory({ range: '1M' })

  const pending = accounts.isPending || portfolio.isPending || loans.isPending
  const failed = accounts.isError || portfolio.isError || loans.isError

  let body
  if (pending) {
    body = (
      <div className="space-y-6">
        <Skeleton className="h-16 w-3/4 sm:h-20" />
        <Skeleton className="h-20 w-full" />
        <div className="grid grid-cols-4 gap-3">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-12" />
          ))}
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
    const cashAccts = accounts.data.filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING')
    const cash = sumMoney(cashAccts.map((a) => a.balance))
    const saved = sumMoney(
      accounts.data
        .filter((a) => a.type === 'GOAL' || a.type === 'FIXED_DEPOSIT')
        .map((a) => a.balance),
    )
    const invested = new Decimal(portfolio.data.totalMarketValue)
    const owed = sumMoney(
      loans.data.filter((l) => l.status === 'ACTIVE').map((l) => l.outstandingPrincipal),
    )
    const net = cash.plus(saved).plus(invested).minus(owed)
    const held = sumMoney(cashAccts.map((a) => a.holdsTotal ?? '0'))
    const points = history.data?.points
    body = (
      <>
        <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2">
          <div aria-live="polite" aria-atomic="true">
            <RollingMoney
              value={net}
              className="font-display text-[clamp(1.875rem,5vw,2.75rem)] leading-none"
            />
          </div>
          <p className="flex items-center gap-2 pb-2 text-sm text-muted">
            Unrealized P&amp;L{' '}
            <Money value={portfolio.data.totalUnrealizedPnl} tone="pnl" className="text-sm" />
          </p>
        </div>
        {points && points.length > 1 ? (
          <div className="mt-5">
            <Sparkline values={points.map((p) => p.balance)} color="var(--chart-1)" height={84} />
            <p className="mt-1 flex justify-between text-[0.6875rem] text-muted">
              <span>Cash, last 30 days</span>
              <Link
                to="/insights?tab=wealth"
                className="inline-flex items-center gap-0.5 hover:text-text"
              >
                Wealth insights <ArrowUpRight aria-hidden="true" className="size-3" />
              </Link>
            </p>
          </div>
        ) : history.isPending && !isUnavailable(history.error) ? (
          <Skeleton className="mt-5 h-[84px] w-full" />
        ) : null}
        <dl className="mt-auto grid grid-cols-2 gap-2.5 pt-6 text-sm sm:grid-cols-4">
          <Breakdown
            label="Cash"
            value={<Money value={cash} />}
            hint={
              held.gt(0) ? (
                <>
                  incl. <Money value={held} /> on hold
                </>
              ) : undefined
            }
          />
          <Breakdown label="Saved" value={<Money value={saved} />} />
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

function Breakdown({
  label,
  value,
  hint,
}: {
  label: string
  value: React.ReactNode
  hint?: React.ReactNode
}) {
  return (
    <div className="rounded-[10px] bg-surface-2/70 px-3.5 py-3 hairline">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-1 font-medium">{value}</dd>
      {hint && <dd className="mt-0.5 text-[0.6875rem] text-muted">{hint}</dd>}
    </div>
  )
}
