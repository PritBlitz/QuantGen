import { FileSearch } from 'lucide-react'
import { Tabs } from 'radix-ui'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useAccounts, usePortfolio } from '@/api/queries'
import type { OrderSide, PriceRange } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { RollingMoney } from '@/components/RollingMoney'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatINR } from '@/lib/money'
import { useMediaQuery } from '@/lib/useMediaQuery'
import { FeedBadge } from './components/FeedBadge'
import { OrderTicket } from './components/OrderTicket'
import { PriceChart } from './components/PriceChart'
import { ChangePct } from './components/PriceBits'
import { useInstrument, usePriceHistory } from './hooks'

const RANGES: PriceRange[] = ['1H', '1D', '1W']
const ANNOUNCE_EVERY_MS = 10_000

/** aria-live text updated at most every 10 s, so screen readers aren't flooded by ticks. */
function useThrottledAnnouncement(text: string | undefined) {
  const [said, setSaid] = useState(text)
  const [at, setAt] = useState(0)
  useEffect(() => {
    if (!text || text === said) return
    const wait = Math.max(0, at + ANNOUNCE_EVERY_MS - Date.now())
    const t = window.setTimeout(() => {
      setSaid(text)
      setAt(Date.now())
    }, wait)
    return () => window.clearTimeout(t)
  }, [text, said, at])
  return said
}

export function InstrumentPage() {
  const { symbol: raw = '' } = useParams()
  const symbol = raw.toUpperCase()
  const instrument = useInstrument(symbol)
  const [range, setRange] = useState<PriceRange>('1D')
  const history = usePriceHistory(symbol, range, instrument.isSuccess)
  const accounts = useAccounts()
  const portfolio = usePortfolio()
  const [sheet, setSheet] = useState<OrderSide | null>(null)
  // Mount exactly one ticket: pinned on desktop, a sheet on mobile.
  const isDesktop = useMediaQuery('(min-width: 1024px)')
  const i = instrument.data
  const announcement = useThrottledAnnouncement(i ? `${i.symbol} ${formatINR(i.price)}` : undefined)

  if (instrument.isError && instrument.error.status === 404) {
    return (
      <EmptyState
        icon={<FileSearch />}
        title={`No instrument called “${symbol}”`}
        action={
          <Button asChild variant="secondary">
            <Link to="/markets">Back to markets</Link>
          </Button>
        }
      />
    )
  }

  const holding = portfolio.data?.holdings.find((h) => h.symbol === symbol)
  const held = holding?.quantity ?? 0

  const ticket =
    i && accounts.data ? (
      <OrderTicket instrument={i} accounts={accounts.data} held={held} />
    ) : (
      <div className="space-y-3">
        <Skeleton className="h-10" />
        <Skeleton className="h-11" />
        <Skeleton className="h-10" />
        <Skeleton className="h-24" />
      </div>
    )

  return (
    <div className="pb-20 lg:pb-0">
      <PageHeader
        back={{ to: '/markets', label: 'Markets' }}
        title={
          <span className="flex flex-wrap items-baseline gap-x-4">
            {symbol}
            {i && <span className="text-2xl text-muted">{i.name}</span>}
          </span>
        }
        actions={
          <>
            {i && <Badge>{i.sector}</Badge>}
            <FeedBadge />
          </>
        }
      />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="space-y-4">
          <Card>
            {instrument.isPending ? (
              <Skeleton className="h-16 w-1/2" />
            ) : instrument.isError ? (
              <CardError what="this instrument" onRetry={() => void instrument.refetch()} />
            ) : (
              <div className="flex flex-wrap items-end gap-x-4 gap-y-1">
                <RollingMoney
                  value={instrument.data.price}
                  className="font-display text-[clamp(2.5rem,8vw,4rem)]"
                />
                <ChangePct value={instrument.data.changePct24h} className="mb-2 text-sm" />
                <span className="sr-only" aria-live="polite">
                  {announcement}
                </span>
              </div>
            )}

            <Tabs.Root
              value={range}
              onValueChange={(v) => setRange(v as PriceRange)}
              className="mt-6"
            >
              <Tabs.List
                aria-label="Chart range"
                className="inline-flex gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1"
              >
                {RANGES.map((r) => (
                  <Tabs.Trigger
                    key={r}
                    value={r}
                    className="num rounded-[8px] px-3 py-1.5 text-xs text-muted data-[state=active]:bg-surface data-[state=active]:text-text"
                  >
                    {r}
                  </Tabs.Trigger>
                ))}
              </Tabs.List>
              <div className="mt-4">
                {history.isPending ? (
                  <Skeleton className="h-72 sm:h-96" />
                ) : history.isError ? (
                  <CardError what="the chart" onRetry={() => void history.refetch()} />
                ) : (
                  <PriceChart
                    points={history.data}
                    live={range === '1H' ? i : undefined}
                    label={`${symbol} price, last ${range}`}
                  />
                )}
              </div>
            </Tabs.Root>
          </Card>

          {holding && (
            <Card>
              <h2 className="font-medium">Your position</h2>
              <dl className="mt-4 grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
                <div>
                  <dt className="text-xs text-muted">Shares</dt>
                  <dd className="num mt-0.5 text-lg">{holding.quantity}</dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">Avg cost</dt>
                  <dd className="mt-0.5">
                    <Money value={holding.avgCost} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">Market value</dt>
                  <dd className="mt-0.5">
                    <Money value={holding.marketValue} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">Unrealized P&amp;L</dt>
                  <dd className="mt-0.5">
                    <Money value={holding.unrealizedPnl} tone="pnl" />{' '}
                    <ChangePct value={holding.unrealizedPnlPct} className="text-xs" />
                  </dd>
                </div>
              </dl>
            </Card>
          )}
        </div>

        {/* Desktop: ticket pinned on the right. */}
        {isDesktop && (
          <aside className="hidden lg:block" aria-label="Order ticket">
            <Card className="sticky top-24">
              <h2 className="mb-4 font-medium">Trade {symbol}</h2>
              {ticket}
            </Card>
          </aside>
        )}
      </div>

      {/* Mobile: bottom bar opens the ticket as a sheet. */}
      <div className="fixed inset-x-0 bottom-[calc(60px+env(safe-area-inset-bottom))] z-20 flex gap-2 border-t border-border bg-bg/95 p-3 backdrop-blur-md lg:hidden">
        <Button className="flex-1 bg-gain text-bg" onClick={() => setSheet('BUY')} disabled={!i}>
          Buy
        </Button>
        <Button
          className="flex-1 bg-loss text-bg"
          onClick={() => setSheet('SELL')}
          disabled={!i || held === 0}
        >
          Sell
        </Button>
      </div>
      <Dialog open={sheet !== null} onOpenChange={(o) => !o && setSheet(null)}>
        <DialogContent
          title={`Trade ${symbol}`}
          className="bottom-0 top-auto max-h-[90dvh] max-w-none translate-y-0 overflow-y-auto rounded-b-none sm:bottom-auto sm:top-1/2 sm:max-w-md sm:-translate-y-1/2 sm:rounded-b-[var(--radius-card)]"
        >
          {i && accounts.data && sheet && (
            <OrderTicket
              instrument={i}
              accounts={accounts.data}
              held={held}
              initialSide={sheet}
              onDone={() => setSheet(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
