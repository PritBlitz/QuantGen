import { FlaskConical, Info, Loader2, Play } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { isUnavailable } from '@/api/availability'
import { errorMessage, toApiError } from '@/api/errors'
import { useInstruments } from '@/api/queries'
import type { BacktestDto, StrategyDsl } from '@/api/types'
import { TrendChart } from '@/components/charts/TrendChart'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { FieldMessage, Input, Label } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useBacktests, useRunBacktest } from '@/features/markets/tradingHooks'
import { formatPercent } from '@/lib/format'
import { formatINR, toDecimal } from '@/lib/money'
import { daysAgoIST, formatDate, formatRelative, todayIST } from '@/lib/time'
import { cn } from '@/lib/utils'

type Kind = StrategyDsl['type']
const KINDS: Array<{ value: Kind; label: string; about: string }> = [
  {
    value: 'SMA_CROSS',
    label: 'SMA crossover',
    about:
      'Buy when the fast moving average crosses above the slow one; sell when it crosses below.',
  },
  {
    value: 'RSI',
    label: 'RSI',
    about: 'Buy when RSI drops below the lower threshold (oversold); sell above the upper one.',
  },
  {
    value: 'BUY_THE_DIP',
    label: 'Buy the dip',
    about: 'Buy after a drop over 5 days; sell once it’s up by the take-profit.',
  },
  {
    value: 'REBALANCE',
    label: 'Rebalance',
    about: 'Hold the picked symbols in equal weights, rebalancing on a fixed schedule.',
  },
]
const MAX_SYMBOLS = 5
/** Earliest price history the server backtests over. */
const MIN_FROM = '2021-01-01'
const isoUtc = (d: string) => Date.UTC(+d.slice(0, 4), +d.slice(5, 7) - 1, +d.slice(8, 10))
const isoDaysBetween = (a: string, b: string) => Math.round((isoUtc(b) - isoUtc(a)) / 86_400_000)
/** `to` minus whole years, as a calendar date (29 Feb falls back to 28 Feb, like Java's minusYears). */
function isoMinusYears(d: string, years: number): string {
  const y = String(+d.slice(0, 4) - years).padStart(4, '0')
  const md = d.slice(5) === '02-29' ? '02-28' : d.slice(5)
  return `${y}-${md}`
}
const KIND_LABEL = Object.fromEntries(KINDS.map((k) => [k.value, k.label])) as Record<Kind, string>

/** Whole number in [min, max], or an error message. Counts, not money. */
function intIn(raw: string, min: number, max: number, what: string): string | undefined {
  if (!/^\d{1,4}$/.test(raw)) return `${what}: a whole number`
  const n = Number.parseInt(raw, 10)
  return n < min || n > max ? `${what}: ${min}–${max}` : undefined
}
function pctIn(raw: string, min: string, max: string, what: string): string | undefined {
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(raw)) return `${what}: a percentage`
  const d = toDecimal(raw)
  return d.lt(min) || d.gt(max) ? `${what}: ${min}–${max}%` : undefined
}

function describe(s: StrategyDsl): string {
  switch (s.type) {
    case 'SMA_CROSS':
      return `SMA ${s.fast}/${s.slow} crossover`
    case 'RSI':
      return `RSI(${s.period}) buy < ${s.buyBelow}, sell > ${s.sellAbove}`
    case 'BUY_THE_DIP':
      return `Buy a ${s.dropPct}% dip, take profit at ${s.takeProfitPct}%`
    case 'REBALANCE':
      return `Equal weights, rebalance every ${s.everyDays} days`
  }
}

/** `/lab` (F-TRD-10): backtest a simple strategy over simulated price history. */
export function LabPage() {
  const instruments = useInstruments()
  const history = useBacktests()
  const run = useRunBacktest()
  const uid = useId()
  const [kind, setKind] = useState<Kind>('SMA_CROSS')
  const [params, setParams] = useState({
    fast: '10',
    slow: '30',
    period: '14',
    buyBelow: '30',
    sellAbove: '70',
    dropPct: '5',
    takeProfitPct: '8',
    everyDays: '30',
  })
  const [symbols, setSymbols] = useState<string[]>(['QGEN'])
  const [from, setFrom] = useState(daysAgoIST(365))
  const [to, setTo] = useState(todayIST())
  const [cash, setCash] = useState('100000')
  const [touched, setTouched] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const set = (k: keyof typeof params) => (v: string) =>
    setParams((p) => ({ ...p, [k]: v.replace(/[^\d.]/g, '').slice(0, 6) }))

  const errors = useMemo(() => {
    const e: Record<string, string | undefined> = {}
    if (kind === 'SMA_CROSS') {
      e.fast = intIn(params.fast, 2, 199, 'Fast')
      e.slow = intIn(params.slow, 3, 200, 'Slow')
      if (
        !e.fast &&
        !e.slow &&
        Number.parseInt(params.slow, 10) <= Number.parseInt(params.fast, 10)
      )
        e.slow = 'Slow must be longer than fast'
    } else if (kind === 'RSI') {
      e.period = intIn(params.period, 2, 50, 'Period')
      e.buyBelow = intIn(params.buyBelow, 1, 99, 'Buy below')
      e.sellAbove = intIn(params.sellAbove, 1, 99, 'Sell above')
      if (
        !e.buyBelow &&
        !e.sellAbove &&
        Number.parseInt(params.sellAbove, 10) <= Number.parseInt(params.buyBelow, 10)
      )
        e.sellAbove = 'Sell level must be above the buy level'
    } else if (kind === 'BUY_THE_DIP') {
      e.dropPct = pctIn(params.dropPct, '0.5', '50', 'Drop')
      e.takeProfitPct = pctIn(params.takeProfitPct, '0.5', '100', 'Take profit')
    } else {
      e.everyDays = intIn(params.everyDays, 1, 365, 'Every')
    }
    if (symbols.length === 0) e.symbols = 'Pick at least one instrument'
    // Same bounds as the server (PRD §12.9 Backtests): from ≥ 2021-01-01, 30 days to 5 years.
    if (!from || !to || from >= to) e.dates = 'The start must be before the end'
    else if (to > todayIST()) e.dates = 'The end can’t be in the future'
    else if (from < MIN_FROM) e.dates = 'Price history starts on 1 Jan 2021'
    else if (isoDaysBetween(from, to) < 30) e.dates = 'Pick a period of at least 30 days'
    else if (from < isoMinusYears(to, 5)) e.dates = 'Pick a period of at most 5 years'
    if (
      !/^\d{1,8}(\.\d{1,2})?$/.test(cash) ||
      toDecimal(cash).lt(1000) ||
      toDecimal(cash).gt(10000000)
    )
      e.cash = 'Between ₹1,000 and ₹1,00,00,000'
    return e
  }, [kind, params, symbols, from, to, cash])
  const invalid = Object.values(errors).some(Boolean)
  const err = (k: string) => (touched ? errors[k] : undefined)

  function strategy(): StrategyDsl {
    const n = (s: string) => Number.parseInt(s, 10)
    switch (kind) {
      case 'SMA_CROSS':
        return { type: kind, fast: n(params.fast), slow: n(params.slow) }
      case 'RSI':
        return {
          type: kind,
          period: n(params.period),
          buyBelow: n(params.buyBelow),
          sellAbove: n(params.sellAbove),
        }
      case 'BUY_THE_DIP':
        return { type: kind, dropPct: params.dropPct, takeProfitPct: params.takeProfitPct }
      case 'REBALANCE': {
        // Equal weights that add up to exactly 100 (the last one takes the rounding).
        const each = toDecimal('100').div(symbols.length).toDecimalPlaces(2, 1)
        const weights = Object.fromEntries(
          symbols.map((s, i) => [
            s,
            (i === symbols.length - 1
              ? toDecimal('100').minus(each.times(symbols.length - 1))
              : each
            ).toFixed(2),
          ]),
        )
        return { type: kind, everyDays: n(params.everyDays), weights }
      }
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (invalid || run.isPending) return
    run.mutate(
      { strategy: strategy(), symbols, from, to, initialCash: toDecimal(cash).toFixed(2) },
      { onSuccess: (r) => setSelectedId((r as BacktestDto).id) },
    )
  }

  const runs = history.data ?? []
  const shown =
    runs.find((r) => r.id === selectedId) ?? (run.data as BacktestDto | undefined) ?? runs[0]
  const runError = run.error ? toApiError(run.error) : null

  if (history.isError && isUnavailable(history.error)) {
    return (
      <div>
        <PageHeader title="Strategy lab" />
        <Card>
          <EmptyState icon={<FlaskConical />} title="The strategy lab isn’t available yet">
            Backtesting arrives with Trading 2.0.
          </EmptyState>
        </Card>
      </div>
    )
  }

  const toggleSymbol = (s: string) =>
    setSymbols((cur) =>
      cur.includes(s) ? cur.filter((x) => x !== s) : cur.length >= MAX_SYMBOLS ? cur : [...cur, s],
    )

  return (
    <div>
      <PageHeader
        title="Strategy lab"
        description="Test a trading idea against past prices before you risk real money."
      />
      <p className="mb-6 flex items-start gap-2 rounded-[var(--radius-control)] bg-warn/10 px-4 py-3 text-sm">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warn" />
        <span>
          <strong className="font-medium">Simulated data.</strong> QuantGen’s prices are simulated,
          so these results show how a rule behaves, not what you would have earned. Nothing here
          places an order.
        </span>
      </p>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <form onSubmit={submit} noValidate className="min-w-0 space-y-4 lg:col-span-5">
          <Panel title="Strategy" eyebrow="1">
            <Segmented<Kind>
              label="Strategy"
              value={kind}
              onValueChange={setKind}
              options={KINDS.map((k) => ({ value: k.value, label: k.label }))}
            />
            <p className="mt-3 text-sm text-muted">{KINDS.find((k) => k.value === kind)?.about}</p>
            <div className="mt-4 grid grid-cols-2 gap-3">
              {kind === 'SMA_CROSS' && (
                <>
                  <NumField
                    id={`${uid}-fast`}
                    label="Fast (days)"
                    value={params.fast}
                    onChange={set('fast')}
                    error={err('fast')}
                  />
                  <NumField
                    id={`${uid}-slow`}
                    label="Slow (days)"
                    value={params.slow}
                    onChange={set('slow')}
                    error={err('slow')}
                  />
                </>
              )}
              {kind === 'RSI' && (
                <>
                  <NumField
                    id={`${uid}-period`}
                    label="Period (days)"
                    value={params.period}
                    onChange={set('period')}
                    error={err('period')}
                  />
                  <span />
                  <NumField
                    id={`${uid}-buy`}
                    label="Buy below"
                    value={params.buyBelow}
                    onChange={set('buyBelow')}
                    error={err('buyBelow')}
                  />
                  <NumField
                    id={`${uid}-sell`}
                    label="Sell above"
                    value={params.sellAbove}
                    onChange={set('sellAbove')}
                    error={err('sellAbove')}
                  />
                </>
              )}
              {kind === 'BUY_THE_DIP' && (
                <>
                  <NumField
                    id={`${uid}-drop`}
                    label="Drop (%)"
                    value={params.dropPct}
                    onChange={set('dropPct')}
                    error={err('dropPct')}
                    decimal
                  />
                  <NumField
                    id={`${uid}-tp`}
                    label="Take profit (%)"
                    value={params.takeProfitPct}
                    onChange={set('takeProfitPct')}
                    error={err('takeProfitPct')}
                    decimal
                  />
                </>
              )}
              {kind === 'REBALANCE' && (
                <NumField
                  id={`${uid}-every`}
                  label="Every (days)"
                  value={params.everyDays}
                  onChange={set('everyDays')}
                  error={err('everyDays')}
                />
              )}
            </div>
          </Panel>

          <Panel title="Instruments" eyebrow="2" description={`Pick up to ${MAX_SYMBOLS}.`}>
            {instruments.isPending ? (
              <Skeleton className="h-20" />
            ) : instruments.isError ? (
              <PanelError what="instruments" onRetry={() => void instruments.refetch()} />
            ) : (
              <div role="group" aria-label="Instruments" className="flex flex-wrap gap-1.5">
                {instruments.data.map((i) => {
                  const on = symbols.includes(i.symbol)
                  return (
                    <button
                      key={i.symbol}
                      type="button"
                      aria-pressed={on}
                      disabled={!on && symbols.length >= MAX_SYMBOLS}
                      onClick={() => toggleSymbol(i.symbol)}
                      className={cn(
                        'num rounded-full px-3 py-1.5 text-xs hairline transition-colors disabled:opacity-40',
                        on
                          ? 'border-accent bg-accent text-accent-ink'
                          : 'text-muted hover:text-text',
                      )}
                    >
                      {i.symbol}
                    </button>
                  )
                })}
              </div>
            )}
            {err('symbols') && (
              <p role="alert" className="mt-2 text-xs text-loss">
                {err('symbols')}
              </p>
            )}
          </Panel>

          <Panel title="Period and cash" eyebrow="3">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor={`${uid}-from`}>From</Label>
                <Input
                  id={`${uid}-from`}
                  type="date"
                  value={from}
                  min={MIN_FROM}
                  max={to}
                  onChange={(e) => setFrom(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor={`${uid}-to`}>To</Label>
                <Input
                  id={`${uid}-to`}
                  type="date"
                  value={to}
                  max={todayIST()}
                  onChange={(e) => setTo(e.target.value)}
                />
              </div>
            </div>
            {err('dates') && (
              <p role="alert" className="mt-2 text-xs text-loss">
                {err('dates')}
              </p>
            )}
            <div className="mt-4">
              <AmountInput
                label="Starting cash"
                value={cash}
                onValueChange={setCash}
                error={err('cash')}
              />
            </div>
          </Panel>

          {runError && (
            <p role="alert" className="text-sm text-loss">
              {runError.fieldErrors[0]
                ? `${runError.fieldErrors[0].field}: ${runError.fieldErrors[0].message}`
                : errorMessage(runError)}
            </p>
          )}
          <Button type="submit" size="lg" className="w-full" disabled={run.isPending}>
            {run.isPending ? (
              <Loader2 className="animate-spin" aria-hidden="true" />
            ) : (
              <Play aria-hidden="true" />
            )}
            {run.isPending ? 'Running…' : 'Run backtest'}
          </Button>
        </form>

        <div className="min-w-0 space-y-4 lg:col-span-7">
          {run.isPending ? (
            <Skeleton className="h-96" />
          ) : shown ? (
            <Result r={shown} />
          ) : history.isPending ? (
            <Skeleton className="h-96" />
          ) : (
            <Card>
              <EmptyState icon={<FlaskConical />} title="No backtests yet">
                Set up a strategy and run it to see how it would have done.
              </EmptyState>
            </Card>
          )}
          {runs.length > 1 && (
            <Panel title="Earlier runs" eyebrow="History">
              <ul className="divide-y divide-border">
                {runs.map((r) => (
                  <li key={r.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedId(r.id)}
                      aria-current={r.id === shown?.id ? 'true' : undefined}
                      className="flex w-full items-center gap-3 py-2.5 text-left text-sm aria-[current=true]:font-medium"
                    >
                      <span className="min-w-0 flex-1 truncate">
                        {describe(r.strategy)} · {r.symbols.join(', ')}
                        <span className="block text-xs font-normal text-muted">
                          {formatRelative(r.createdAt)}
                        </span>
                      </span>
                      <ReturnText pct={r.returnPct} />
                    </button>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      </div>
    </div>
  )
}

function NumField({
  id,
  label,
  value,
  onChange,
  error,
  decimal,
}: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  error?: string
  decimal?: boolean
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        inputMode={decimal ? 'decimal' : 'numeric'}
        className="num"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
      />
      <FieldMessage id={`${id}-error`}>{error}</FieldMessage>
    </div>
  )
}

function ReturnText({ pct }: { pct: string }) {
  const up = !toDecimal(pct).isNegative()
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss')}>
      {up ? '+' : ''}
      {formatPercent(pct, 2)}
    </span>
  )
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-[var(--radius-control)] bg-surface-2 p-3">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="num mt-1 text-lg">{children}</dd>
    </div>
  )
}

function Result({ r }: { r: BacktestDto }) {
  return (
    <Panel
      title={describe(r.strategy)}
      eyebrow={`${KIND_LABEL[r.strategy.type]} · ${r.symbols.join(', ')}`}
      description={`${formatDate(r.from)} to ${formatDate(r.to)}, starting with ${formatINR(r.initialCash)}`}
      action={<Badge tone="warn">Simulated</Badge>}
    >
      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Stat label="Final value">
          <Money value={r.finalValue} />
        </Stat>
        <Stat label="Return">
          <ReturnText pct={r.returnPct} />
        </Stat>
        <Stat label="Max drawdown">{formatPercent(r.maxDrawdownPct, 2)}</Stat>
        <Stat label="Win rate">{formatPercent(r.winRatePct, 0)}</Stat>
      </dl>
      <TrendChart
        className="mt-5"
        ariaLabel={`Portfolio value over the backtest, from ${r.initialCash} to ${r.finalValue}`}
        unit="MONEY"
        yDomain="auto"
        height={240}
        rows={r.equity.map((p) => ({ t: p.t, values: { value: p.value } }))}
        series={[{ key: 'value', label: 'Portfolio value', color: 'var(--chart-1)', kind: 'area' }]}
      />
      <h3 className="mt-6 text-sm font-medium">
        Trades{' '}
        <span className="font-normal text-muted">
          ({r.trades.length}
          {r.trades.length >= 60 ? ', latest 60' : ''})
        </span>
      </h3>
      {r.trades.length === 0 ? (
        <p className="mt-2 text-sm text-muted">The rule never fired in this period.</p>
      ) : (
        <div className="mt-2 max-h-72 overflow-y-auto">
          <table className="w-full text-sm">
            <caption className="sr-only">Simulated trades</caption>
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted">
                <th scope="col" className="py-2 pr-3 font-normal">
                  Date
                </th>
                <th scope="col" className="py-2 pr-3 font-normal">
                  Trade
                </th>
                <th scope="col" className="py-2 text-right font-normal">
                  Price
                </th>
              </tr>
            </thead>
            <tbody>
              {[...r.trades].reverse().map((t, i) => (
                <tr
                  key={`${t.t}-${t.symbol}-${i}`}
                  className="border-b border-border last:border-0"
                >
                  <td className="py-2 pr-3 text-muted">{formatDate(t.t)}</td>
                  <td className="py-2 pr-3">
                    <Badge tone={t.side === 'BUY' ? 'gain' : 'loss'}>
                      {t.side === 'BUY' ? 'Buy' : 'Sell'}
                    </Badge>{' '}
                    <span className="num">{t.quantity}</span> × {t.symbol}
                  </td>
                  <td className="py-2 text-right">
                    <Money value={t.price} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}
