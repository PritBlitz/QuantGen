import { useQuery } from '@tanstack/react-query'
import { BriefcaseBusiness, FileText, History } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'
import { marketApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queries'
import type { HoldingDto } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { ChangePct } from '@/features/markets/components/PriceBits'
import { Decimal, ratio, toDecimal } from '@/lib/money'

/** Categorical series colours: theme tokens first, then muted steps (never gain/loss). */
const SERIES = [
  'var(--accent)',
  'var(--text)',
  '#7C8CF8',
  '#C9A227',
  'var(--text-muted)',
  'var(--border)',
]

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Card className="p-4 sm:p-5">
      <p className="text-xs text-muted">{label}</p>
      <div className="mt-1.5 text-xl font-medium sm:text-2xl">{children}</div>
    </Card>
  )
}

function Allocation({ holdings, total }: { holdings: HoldingDto[]; total: string }) {
  const sorted = [...holdings].sort((a, b) =>
    toDecimal(b.marketValue).comparedTo(toDecimal(a.marketValue)),
  )
  const top = sorted.slice(0, 5)
  const rest = sorted.slice(5)
  const slices = [
    ...top.map((h) => ({ name: h.symbol, value: h.marketValue })),
    ...(rest.length
      ? [
          {
            name: 'Other',
            value: rest.reduce((s, h) => s.plus(h.marketValue), new Decimal(0)).toFixed(2),
          },
        ]
      : []),
  ]
  // Chart geometry only: numbers here never feed back into money.
  const chartData = slices.map((s) => ({ name: s.name, value: toDecimal(s.value).toNumber() }))
  return (
    <div className="flex flex-col items-center gap-6 sm:flex-row">
      <div
        className="relative size-48 shrink-0"
        role="img"
        aria-label="Portfolio allocation by market value"
      >
        <ResponsiveContainer>
          <PieChart>
            <Pie
              data={chartData}
              dataKey="value"
              innerRadius="68%"
              outerRadius="100%"
              paddingAngle={2}
              stroke="none"
              isAnimationActive={false}
            >
              {chartData.map((_, i) => (
                <Cell key={i} fill={SERIES[i % SERIES.length]} />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 grid place-items-center text-center">
          <div>
            <p className="text-xs text-muted">Holdings</p>
            <p className="num text-2xl font-medium">{holdings.length}</p>
          </div>
        </div>
      </div>
      <ul className="w-full space-y-2 text-sm">
        {slices.map((s, i) => (
          <li key={s.name} className="flex items-center gap-2.5">
            <span
              aria-hidden="true"
              className="size-2.5 shrink-0 rounded-full"
              style={{ background: SERIES[i % SERIES.length] }}
            />
            <span className="font-medium">{s.name}</span>
            <span className="ml-auto text-muted">
              <Money value={s.value} />
            </span>
            <span className="num w-14 text-right">{(ratio(s.value, total) * 100).toFixed(1)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function PortfolioPage() {
  // Positions are marked to market by the server; refresh while the page is open.
  const { data, isPending, isError, refetch } = useQuery({
    queryKey: queryKeys.portfolio,
    queryFn: marketApi.portfolio,
    refetchInterval: 10_000,
  })

  const pnlPct =
    data && !toDecimal(data.totalCost).isZero()
      ? toDecimal(data.totalUnrealizedPnl)
          .div(data.totalCost)
          .times(100)
          .toDecimalPlaces(2)
          .toFixed(2)
      : '0.00'

  return (
    <div>
      <PageHeader
        title="Portfolio"
        description="Valued at live market prices. Realized P&L includes positions you’ve sold."
        actions={
          <>
            <Button variant="ghost" asChild>
              <Link to="/portfolio/tax">
                <FileText aria-hidden="true" /> Capital gains
              </Link>
            </Button>
            <Button variant="secondary" asChild>
              <Link to="/orders">
                <History aria-hidden="true" /> Order history
              </Link>
            </Button>
          </>
        }
      />

      {isPending ? (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-24 rounded-[var(--radius-card)]" />
            ))}
          </div>
          <Skeleton className="h-80 rounded-[var(--radius-card)]" />
        </div>
      ) : isError ? (
        <CardError what="your portfolio" onRetry={() => void refetch()} />
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <Stat label="Market value">
              <Money value={data.totalMarketValue} />
            </Stat>
            <Stat label="Invested">
              <Money value={data.totalCost} />
            </Stat>
            <Stat label="Unrealized P&L">
              <Money value={data.totalUnrealizedPnl} tone="pnl" />
              <ChangePct value={pnlPct} className="block text-xs" />
            </Stat>
            <Stat label="Realized P&L">
              <Money value={data.totalRealizedPnl} tone="pnl" />
            </Stat>
          </div>

          {data.holdings.length === 0 ? (
            <Card>
              <EmptyState
                icon={<BriefcaseBusiness />}
                title="No holdings yet"
                action={
                  <Button asChild>
                    <Link to="/markets">Buy your first share</Link>
                  </Button>
                }
              >
                Pick an instrument from the market. Orders fill instantly at the live price.
              </EmptyState>
            </Card>
          ) : (
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)]">
              <Card>
                <h2 className="mb-5 font-medium">Allocation</h2>
                <Allocation holdings={data.holdings} total={data.totalMarketValue} />
              </Card>
              <Card>
                <h2 className="mb-3 font-medium">Holdings</h2>
                <table className="hidden w-full text-sm md:table">
                  <caption className="sr-only">Holdings</caption>
                  <thead>
                    <tr className="border-b border-border text-left text-xs text-muted">
                      <th scope="col" className="py-2.5 pr-3 font-normal">
                        Instrument
                      </th>
                      <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                        Qty
                      </th>
                      <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                        Avg cost
                      </th>
                      <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                        Price
                      </th>
                      <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                        Value
                      </th>
                      <th scope="col" className="py-2.5 text-right font-normal">
                        P&amp;L
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.holdings.map((h) => (
                      <tr key={h.symbol} className="border-b border-border last:border-0">
                        <td className="py-3 pr-3">
                          <Link
                            to={`/markets/${h.symbol}`}
                            className="font-semibold hover:underline"
                          >
                            {h.symbol}
                          </Link>
                          <p className="text-xs text-muted">{h.name}</p>
                        </td>
                        <td className="num py-3 pr-3 text-right">{h.quantity}</td>
                        <td className="py-3 pr-3 text-right">
                          <Money value={h.avgCost} />
                        </td>
                        <td className="py-3 pr-3 text-right">
                          <Money value={h.price} />
                        </td>
                        <td className="py-3 pr-3 text-right">
                          <Money value={h.marketValue} />
                        </td>
                        <td className="py-3 text-right">
                          <Money value={h.unrealizedPnl} tone="pnl" />
                          <ChangePct value={h.unrealizedPnlPct} className="block text-xs" />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <ul className="divide-y divide-border md:hidden">
                  {data.holdings.map((h) => (
                    <li key={h.symbol}>
                      <Link
                        to={`/markets/${h.symbol}`}
                        className="flex items-start justify-between gap-3 py-3"
                      >
                        <div>
                          <p className="font-semibold">{h.symbol}</p>
                          <p className="text-xs text-muted">
                            <span className="num">{h.quantity}</span> × avg{' '}
                            <Money value={h.avgCost} />
                          </p>
                        </div>
                        <div className="text-right text-sm">
                          <Money value={h.marketValue} />
                          <Money value={h.unrealizedPnl} tone="pnl" className="block text-xs" />
                        </div>
                      </Link>
                    </li>
                  ))}
                </ul>
              </Card>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
