import { useQueries } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Search, Settings2, Star } from 'lucide-react'
import { ToggleGroup } from 'radix-ui'
import { useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { marketApi } from '@/api/endpoints'
import { queryKeys, useInstruments } from '@/api/queries'
import type { InstrumentDto } from '@/api/types'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Segmented } from '@/components/Segmented'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { CryptoPrices } from './components/CryptoPrices'
import { FeedBadge } from './components/FeedBadge'
import { AllAlertsList } from './components/PriceAlerts'
import { ALL_INSTRUMENTS, ManageWatchlistsDialog } from './components/Watchlists'
import { useWatchlists } from './tradingHooks'
import { ChangePct, PriceCell, Sparkline } from './components/PriceBits'

type SortKey = 'symbol' | 'price' | 'change'
const ALL = 'All'

function compare(a: InstrumentDto, b: InstrumentDto, key: SortKey) {
  if (key === 'symbol') return a.symbol.localeCompare(b.symbol)
  if (key === 'price') return toDecimal(a.price).comparedTo(toDecimal(b.price))
  return toDecimal(a.changePct24h).comparedTo(toDecimal(b.changePct24h))
}

type SortState = { key: SortKey; dir: 'asc' | 'desc' }

function SortHeader({
  k,
  label,
  align = 'left',
  sort,
  onSort,
}: {
  k: SortKey
  label: string
  align?: 'left' | 'right'
  sort: SortState
  onSort: (next: SortState) => void
}) {
  const active = sort.key === k
  return (
    <th
      scope="col"
      aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
      className={cn('py-2.5 font-normal', align === 'right' ? 'pr-2 text-right' : 'pr-4 text-left')}
    >
      <button
        type="button"
        onClick={() => onSort({ key: k, dir: active && sort.dir === 'desc' ? 'asc' : 'desc' })}
        className={cn('inline-flex items-center gap-1 hover:text-text', active && 'text-text')}
      >
        {label}
        {active &&
          (sort.dir === 'asc' ? (
            <ArrowUp aria-hidden="true" className="size-3" />
          ) : (
            <ArrowDown aria-hidden="true" className="size-3" />
          ))}
      </button>
    </th>
  )
}

export function MarketsPage() {
  const navigate = useNavigate()
  const { data, isPending, isError, refetch } = useInstruments()
  const [q, setQ] = useState('')
  const [sector, setSector] = useState(ALL)
  const watchlists = useWatchlists()
  const [list, setList] = useState(ALL_INSTRUMENTS)
  const [managing, setManaging] = useState(false)
  const activeList = watchlists.data?.find((w) => w.id === list)
  const [sort, setSort] = useState<SortState>({
    key: 'change',
    dir: 'desc',
  })

  const sectors = useMemo(
    () => [ALL, ...Array.from(new Set((data ?? []).map((i) => i.sector))).sort()],
    [data],
  )
  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (data ?? [])
      .filter((i) => !activeList || activeList.symbols.includes(i.symbol))
      .filter((i) => sector === ALL || i.sector === sector)
      .filter(
        (i) =>
          !needle ||
          i.symbol.toLowerCase().includes(needle) ||
          i.name.toLowerCase().includes(needle),
      )
      .sort((a, b) => compare(a, b, sort.key) * (sort.dir === 'asc' ? 1 : -1))
  }, [data, q, sector, sort, activeList])

  // 1D sparklines (cached; they don't need to tick).
  const history = useQueries({
    queries: (data ?? []).map((i) => ({
      queryKey: queryKeys.history(i.symbol, '1D'),
      queryFn: () => marketApi.history(i.symbol, '1D'),
      staleTime: 5 * 60_000,
    })),
  })
  const sparkOf = (symbol: string) =>
    history[(data ?? []).findIndex((i) => i.symbol === symbol)]?.data

  return (
    <div>
      <PageHeader
        title="Markets"
        description="Anchored to each day’s NSE closing price, then simulated every 3 seconds between closes. Market orders fill instantly."
        actions={<FeedBadge />}
      />

      {watchlists.data && (
        <div className="mb-4 flex items-center gap-2">
          <Segmented<string>
            label="Watchlist"
            value={activeList ? list : ALL_INSTRUMENTS}
            onValueChange={setList}
            options={[
              { value: ALL_INSTRUMENTS, label: 'All instruments' },
              ...watchlists.data.map((w) => ({
                value: w.id,
                label: (
                  <span className="inline-flex items-center gap-1.5">
                    <Star aria-hidden="true" className="size-3" />
                    {w.name}
                  </span>
                ),
              })),
            ]}
          />
          <Button variant="ghost" size="sm" onClick={() => setManaging(true)}>
            <Settings2 aria-hidden="true" /> Lists
          </Button>
        </div>
      )}
      <ManageWatchlistsDialog
        open={managing}
        onOpenChange={(o) => {
          setManaging(o)
          if (!o) setList(ALL_INSTRUMENTS)
        }}
      />

      <Card>
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="relative lg:w-72">
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted"
            />
            <Input
              type="search"
              placeholder="Search symbol or name"
              aria-label="Search instruments"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              className="pl-9"
            />
          </div>
          <ToggleGroup.Root
            type="single"
            value={sector}
            onValueChange={(v) => v && setSector(v)}
            aria-label="Filter by sector"
            className="-mx-1 flex gap-1.5 overflow-x-auto px-1 pb-1 lg:flex-wrap lg:pb-0"
          >
            {sectors.map((s) => (
              <ToggleGroup.Item
                key={s}
                value={s}
                className="shrink-0 rounded-full px-3 py-1.5 text-xs text-muted hairline transition-colors data-[state=on]:border-accent data-[state=on]:bg-accent data-[state=on]:text-accent-ink hover:text-text"
              >
                {s}
              </ToggleGroup.Item>
            ))}
          </ToggleGroup.Root>
        </div>

        <div className="mt-5">
          {isPending ? (
            <div className="space-y-2">
              {Array.from({ length: 8 }, (_, i) => (
                <Skeleton key={i} className="h-14" />
              ))}
            </div>
          ) : isError ? (
            <CardError what="prices" onRetry={() => void refetch()} />
          ) : rows.length === 0 ? (
            activeList && activeList.symbols.length === 0 ? (
              <EmptyState icon={<Star />} title={`“${activeList.name}” is empty`}>
                Open an instrument and tap Watch to add it here.
              </EmptyState>
            ) : (
              <EmptyState icon={<Search />} title="No instruments match">
                Try another name or sector.
              </EmptyState>
            )
          ) : (
            <>
              <table className="hidden w-full text-sm md:table">
                <caption className="sr-only">Instruments and live prices</caption>
                <thead>
                  <tr className="border-b border-border text-xs text-muted">
                    <SortHeader k="symbol" label="Instrument" sort={sort} onSort={setSort} />
                    <th
                      scope="col"
                      className="hidden py-2.5 pr-4 text-left font-normal lg:table-cell"
                    >
                      Sector
                    </th>
                    <th scope="col" className="py-2.5 pr-4 text-left font-normal">
                      1D
                    </th>
                    <SortHeader
                      k="price"
                      label="Price"
                      align="right"
                      sort={sort}
                      onSort={setSort}
                    />
                    <SortHeader k="change" label="24h" align="right" sort={sort} onSort={setSort} />
                    <th scope="col">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((i) => (
                    <tr
                      key={i.symbol}
                      onClick={() => void navigate(`/markets/${i.symbol}`)}
                      className="cursor-pointer border-b border-border transition-colors last:border-0 hover:bg-surface-2/60"
                    >
                      <td className="py-3 pr-4">
                        <Link
                          to={`/markets/${i.symbol}`}
                          className="font-semibold hover:underline"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {i.symbol}
                        </Link>
                        <p className="text-xs text-muted">{i.name}</p>
                      </td>
                      <td className="hidden py-3 pr-4 text-muted lg:table-cell">{i.sector}</td>
                      <td className="py-3 pr-4">
                        <Sparkline points={sparkOf(i.symbol)} />
                      </td>
                      <td className="py-3 pr-2 text-right">
                        <PriceCell price={i.price} />
                      </td>
                      <td className="py-3 pr-2 text-right">
                        <ChangePct value={i.changePct24h} />
                      </td>
                      <td className="py-3 text-right">
                        <Button
                          asChild
                          size="sm"
                          variant="secondary"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <Link to={`/markets/${i.symbol}`} aria-label={`Trade ${i.symbol}`}>
                            Trade
                          </Link>
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <ul className="divide-y divide-border md:hidden">
                {rows.map((i) => (
                  <li key={i.symbol}>
                    <Link to={`/markets/${i.symbol}`} className="flex items-center gap-3 py-3">
                      <div className="min-w-0 flex-1">
                        <p className="font-semibold">{i.symbol}</p>
                        <p className="truncate text-xs text-muted">{i.name}</p>
                      </div>
                      <Sparkline points={sparkOf(i.symbol)} width={64} />
                      <div className="w-28 text-right">
                        <PriceCell price={i.price} className="text-sm" />
                        <ChangePct value={i.changePct24h} className="block text-xs" />
                      </div>
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </Card>
      <CryptoPrices />
      <AllAlertsList />
    </div>
  )
}
