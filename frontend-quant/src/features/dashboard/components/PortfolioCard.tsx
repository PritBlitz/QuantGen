import { ArrowDownRight, ArrowUpRight, TrendingUp } from 'lucide-react'
import { Link } from 'react-router'
import type { HoldingDto } from '@/api/types'
import { Money } from '@/components/Money'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Decimal, formatINR, formatPct, ratio, signOf } from '@/lib/money'
import { cn } from '@/lib/utils'
import { TrendChart } from '@/components/charts/TrendChart'
import { usePortfolioAnalytics } from '@/features/analytics/hooks'
import { useInstruments, usePortfolio } from '../hooks'
import { CardError, DashCard } from './CardShell'

// Fixed, theme-aware tints for the allocation bar; never encodes gain/loss.
const ALLOC = ['bg-chart-1', 'bg-chart-2', 'bg-chart-3', 'bg-chart-4', 'bg-border-strong'] as const

function Allocation({ holdings, total }: { holdings: HoldingDto[]; total: string }) {
  const sorted = [...holdings].sort((a, b) =>
    new Decimal(b.marketValue).comparedTo(new Decimal(a.marketValue)),
  )
  const top = sorted.slice(0, 4)
  const rest = sorted.slice(4)
  const slices = [
    ...top.map((h) => ({ label: h.symbol, value: h.marketValue })),
    ...(rest.length
      ? [
          {
            label: 'Other',
            value: rest.reduce((s, h) => s.plus(h.marketValue), new Decimal(0)).toFixed(4),
          },
        ]
      : []),
  ]
  return (
    <div>
      <div className="flex h-2 overflow-hidden rounded-full bg-surface-2" aria-hidden="true">
        {slices.map((s, i) => (
          <span
            key={s.label}
            className={cn('h-full', ALLOC[i])}
            style={{ width: `${ratio(s.value, total) * 100}%` }}
          />
        ))}
      </div>
      <ul className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs sm:grid-cols-3">
        {slices.map((s, i) => (
          <li key={s.label} className="flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2 rounded-full', ALLOC[i])} />
            <span className="font-medium">{s.label}</span>
            <span className="num text-muted">{(ratio(s.value, total) * 100).toFixed(1)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Empty state teaches (DESIGN.md §3.5): three trending instruments + a first-buy CTA. */
function EmptyPortfolio() {
  const instruments = useInstruments()
  const trending = [...(instruments.data ?? [])]
    .sort((a, b) => new Decimal(b.changePct24h).comparedTo(new Decimal(a.changePct24h)))
    .slice(0, 3)
  return (
    <div className="flex flex-1 flex-col">
      <p className="text-sm text-muted">You don’t own any shares yet. Trending today:</p>
      <ul className="mt-3 space-y-2">
        {instruments.isPending
          ? [0, 1, 2].map((i) => <Skeleton key={i} className="h-11" />)
          : trending.map((t) => {
              const neg = signOf(t.changePct24h) === 'negative'
              const Icon = neg ? ArrowDownRight : ArrowUpRight
              return (
                <li
                  key={t.symbol}
                  className="flex items-center justify-between rounded-[var(--radius-control)] bg-surface-2 px-3.5 py-2.5 text-sm"
                >
                  <span>
                    <span className="font-semibold">{t.symbol}</span>{' '}
                    <span className="text-muted">{t.name}</span>
                  </span>
                  <span className="flex items-center gap-3">
                    <span className="num">{formatINR(t.price)}</span>
                    <span
                      className={cn(
                        'num inline-flex items-center text-xs',
                        neg ? 'text-loss' : 'text-gain',
                      )}
                    >
                      <Icon aria-hidden="true" className="size-3.5" />
                      {formatPct(t.changePct24h)}
                    </span>
                  </span>
                </li>
              )
            })}
      </ul>
      <Button asChild className="mt-4 self-start" size="sm">
        <Link to="/markets">
          <TrendingUp aria-hidden="true" /> Buy your first share
        </Link>
      </Button>
    </div>
  )
}

export function PortfolioCard({ className }: { className?: string }) {
  const { data, isPending, isError, refetch } = usePortfolio()
  const perf = usePortfolioAnalytics('3M')

  let body
  if (isPending) {
    body = (
      <div className="space-y-4">
        <Skeleton className="h-9 w-1/2" />
        <Skeleton className="h-2" />
        <Skeleton className="h-16" />
      </div>
    )
  } else if (isError) {
    body = <CardError what="your portfolio" onRetry={() => void refetch()} />
  } else if (data.holdings.length === 0) {
    body = <EmptyPortfolio />
  } else {
    const cost = new Decimal(data.totalCost)
    const pnlPct = cost.isZero()
      ? '0.00'
      : new Decimal(data.totalUnrealizedPnl).div(cost).times(100).toDecimalPlaces(2).toFixed(2)
    body = (
      <>
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <Money value={data.totalMarketValue} className="text-3xl font-medium" />
          <span className="text-sm">
            <Money value={data.totalUnrealizedPnl} tone="pnl" />{' '}
            <span className="num text-muted">({formatPct(pnlPct)})</span>
          </span>
        </div>
        {perf.data && perf.data.points.length > 1 && (
          <div className="mt-4">
            <TrendChart
              ariaLabel="Portfolio value against amount invested, last 3 months"
              height={150}
              hideYAxis
              yDomain="auto"
              unit="MONEY"
              rows={perf.data.points.map((p) => ({
                t: p.t,
                values: { mv: p.marketValue, inv: p.invested },
              }))}
              series={[
                { key: 'mv', label: 'Value', color: 'var(--chart-2)' },
                { key: 'inv', label: 'Invested', color: 'var(--text-muted)', kind: 'dashed' },
              ]}
            />
          </div>
        )}
        <div className="mt-5">
          <Allocation holdings={data.holdings} total={data.totalMarketValue} />
        </div>
        <dl className="mt-auto grid grid-cols-2 gap-3 pt-6 text-sm">
          <div>
            <dt className="text-xs text-muted">Invested</dt>
            <dd className="mt-0.5">
              <Money value={data.totalCost} />
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Realized P&amp;L</dt>
            <dd className="mt-0.5">
              <Money value={data.totalRealizedPnl} tone="pnl" />
            </dd>
          </div>
        </dl>
      </>
    )
  }

  return (
    <DashCard
      title="Portfolio"
      labelledBy="portfolio-title"
      className={className}
      action={
        <Button asChild variant="ghost" size="sm" className="-mr-2 text-muted">
          <Link to="/portfolio">Details</Link>
        </Button>
      }
    >
      {body}
    </DashCard>
  )
}
