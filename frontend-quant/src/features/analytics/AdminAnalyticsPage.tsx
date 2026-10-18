import {
  Activity,
  Bot,
  Download,
  Gauge,
  HandCoins,
  Landmark,
  ShieldAlert,
  Users,
  Wallet,
  Workflow,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { analyticsApi } from '@/api/endpoints.v2'
import { errorMessage } from '@/api/errors'
import type { AnalyticsMetric, BreakdownDimension, KpiDto, KpiKey } from '@/api/types'
import { CohortHeatmap, Funnel, ShareBars } from '@/components/charts/Bits'
import { Donut } from '@/components/charts/Donut'
import { TrendChart } from '@/components/charts/TrendChart'
import { KpiTile, KpiTileSkeleton } from '@/components/KpiTile'
import { PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { RangePicker } from '@/components/RangePicker'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { downloadBlob } from '@/lib/download'
import { formatCount, formatMetric, formatPercent, isUsdMetric } from '@/lib/format'
import { formatINR, toDecimal } from '@/lib/money'
import { granularityFor, rangeFor, type RangePreset } from '@/lib/range'
import { formatRelative } from '@/lib/time'
import {
  useAdoption,
  useBreakdown,
  useCohorts,
  useFunnel,
  useHealth,
  useLoanBook,
  useOverview,
  useSeries,
} from './hooks'

const HERO_METRICS: Array<{ value: AnalyticsMetric; label: string }> = [
  { value: 'TRANSFER_VOLUME', label: 'Transfers' },
  { value: 'CUSTOMER_DEPOSITS', label: 'Deposits' },
  { value: 'TRADE_VOLUME', label: 'Trading' },
  { value: 'NEW_USERS', label: 'Sign-ups' },
  { value: 'ACTIVE_USERS', label: 'Active' },
  { value: 'INTEREST_INCOME', label: 'Interest' },
]

const DIMENSIONS: Array<{ value: BreakdownDimension; text: string }> = [
  { value: 'TRANSACTION_TYPE', text: 'Transaction type' },
  { value: 'INSTRUMENT', text: 'Top instruments' },
  { value: 'SECTOR', text: 'Sectors traded' },
  { value: 'ORDER_SIDE', text: 'Buy vs sell' },
  { value: 'ACCOUNT_TYPE', text: 'Account type' },
  { value: 'ACCOUNT_STATUS', text: 'Account status' },
  { value: 'LOAN_STATUS', text: 'Loan status' },
  { value: 'LOAN_PURPOSE', text: 'Loan purpose' },
  { value: 'FUNDING_SOURCE', text: 'Funding source' },
  { value: 'SPEND_CATEGORY', text: 'Spending category' },
  { value: 'ERROR_CODE', text: 'Errors on money endpoints' },
]

const KPI_ICON: Partial<Record<KpiKey, typeof Users>> = {
  TOTAL_USERS: Users,
  NEW_USERS: Users,
  ACTIVE_USERS: Activity,
  CUSTOMER_DEPOSITS: Wallet,
  OUTSTANDING_PRINCIPAL: Landmark,
  INTEREST_INCOME: HandCoins,
}

/** The four KPIs that get a sparkline (the rest stay compact). */
const SPARK: KpiKey[] = ['CUSTOMER_DEPOSITS', 'TRANSFER_VOLUME', 'ACTIVE_USERS', 'NEW_USERS']

/**
 * Business analytics (PRD F-ANL-1…10). Aggregates only: no names, emails or account numbers ever
 * appear here, and any cell covering fewer than 5 people arrives suppressed and is shown locked.
 */
export function AdminAnalyticsPage() {
  const [preset, setPreset] = useState<RangePreset>('30D')
  const range = useMemo(() => rangeFor(preset), [preset])
  const granularity = granularityFor(preset)
  const [metric, setMetric] = useState<AnalyticsMetric>('TRANSFER_VOLUME')
  const [dimension, setDimension] = useState<BreakdownDimension>('INSTRUMENT')
  const [exporting, setExporting] = useState(false)

  const overview = useOverview(range)
  const hero = useSeries({ ...range, metric, granularity, compare: true })
  const funnel = useFunnel(range)
  const mix = useBreakdown({ ...range, dimension: 'TRANSACTION_TYPE', limit: 8 })
  const explorer = useBreakdown({ ...range, dimension, limit: 10 })
  const cohorts = useCohorts(6)
  const book = useLoanBook()
  const adoption = useAdoption(range)
  const health = useHealth()

  async function exportCsv() {
    setExporting(true)
    try {
      const blob = await analyticsApi.exportCsv('timeseries', { ...range, metric, granularity })
      downloadBlob(blob, `quantgen-${metric.toLowerCase()}-${range.from}-to-${range.to}.csv`)
      toast.success('Export downloaded. It’s recorded in the audit log.')
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setExporting(false)
    }
  }

  const heroDef = HERO_METRICS.find((m) => m.value === metric)
  const heroTotal = overview.data?.kpis.find((k) => k.key === metric)

  return (
    <div>
      <PageHeader
        eyebrow="Business analytics"
        title={
          <>
            The bank, <em className="text-gradient">at a glance.</em>
          </>
        }
        description="Aggregated across every customer. Small groups are hidden to protect privacy, and every export is audited."
        actions={
          <>
            <RangePicker value={preset} onChange={setPreset} />
            <Button variant="secondary" onClick={() => void exportCsv()} disabled={exporting}>
              <Download aria-hidden="true" /> {exporting ? 'Exporting…' : 'Export CSV'}
            </Button>
          </>
        }
      />

      {/* KPI grid */}
      {overview.isError ? (
        <Panel title="Key figures">
          <PanelError what="key figures" onRetry={() => void overview.refetch()} />
        </Panel>
      ) : (
        <section aria-label="Key figures" className="grid grid-cols-2 gap-3 md:grid-cols-4">
          {overview.data
            ? orderKpis(overview.data.kpis).map((k, i) => (
                <KpiCell key={k.key} kpi={k} range={range} granularity={granularity} big={i < 4} />
              ))
            : Array.from({ length: 8 }).map((_, i) => <KpiTileSkeleton key={i} />)}
        </section>
      )}

      {/* Hero trend */}
      <Panel
        className="mt-4"
        tone="cyan"
        eyebrow={`${heroDef?.label ?? 'Metric'} · ${granularity.toLowerCase()} buckets`}
        title={
          heroTotal ? (
            <span className="num text-3xl font-medium sm:text-4xl">
              {formatMetric(heroTotal.value, heroTotal.unit, { compact: heroTotal.unit === 'MONEY' })}
            </span>
          ) : (
            <Skeleton className="h-9 w-40" />
          )
        }
        description="Solid line: this period. Dashed: the previous period of the same length."
        action={
          <Segmented
            label="Metric"
            value={metric}
            onValueChange={setMetric}
            options={HERO_METRICS.map((m) => ({ value: m.value, label: m.label }))}
            className="hidden lg:inline-flex"
          />
        }
      >
        <div className="mb-3 lg:hidden">
          <Select aria-label="Metric"
            value={metric}
            onValueChange={(v) => setMetric(v as AnalyticsMetric)}
            options={HERO_METRICS.map((m) => ({ value: m.value, label: m.label, text: m.label }))}
          />
        </div>
        {hero.isError ? (
          <PanelError what="the trend" onRetry={() => void hero.refetch()} />
        ) : !hero.data ? (
          <Skeleton className="h-[300px] w-full rounded-xl" />
        ) : !hero.data.available ? (
          <p className="py-16 text-center text-sm text-muted">This metric arrives with its milestone.</p>
        ) : (
          <TrendChart
            ariaLabel={`${hero.data.label} over the selected period`}
            height={300}
            unit={hero.data.unit}
            currency={isUsdMetric(metric) ? 'USD' : 'INR'}
            monthAxis={granularity === 'MONTH'}
            rows={hero.data.points.map((p, i) => ({
              t: p.t,
              values: { now: p.value, prev: hero.data.previous?.[i]?.value ?? null },
            }))}
            series={[
              { key: 'now', label: 'This period', color: 'var(--chart-3)', kind: 'area' },
              { key: 'prev', label: 'Previous period', color: 'var(--text-muted)', kind: 'dashed' },
            ]}
          />
        )}
      </Panel>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel className="lg:col-span-7" eyebrow="Acquisition" title="From sign-up to first trade" description="People who registered in this period, and how far they got.">
          {funnel.isError ? (
            <PanelError what="the funnel" onRetry={() => void funnel.refetch()} />
          ) : funnel.data ? (
            <Funnel steps={funnel.data.steps} />
          ) : (
            <Skeleton className="h-56 w-full" />
          )}
        </Panel>
        <Panel className="lg:col-span-5" eyebrow="Money in motion" title="Transaction mix">
          {mix.isError ? (
            <PanelError what="the mix" onRetry={() => void mix.refetch()} />
          ) : mix.data ? (
            <Donut
              ariaLabel="Share of money moved by transaction type"
              size={170}
              slices={mix.data.rows.filter((r) => !r.suppressed && r.value).map((r) => ({ key: r.key, label: r.label, value: r.value ?? '0' }))}
              formatValue={(s) => formatINR(s.value, { compact: true })}
              center={(a) => (
                <>
                  <p className="text-[0.6875rem] text-muted">{a ? a.label : 'Total moved'}</p>
                  <p className="num text-lg font-medium">{formatINR(a ? a.value : mix.data.total, { compact: true })}</p>
                </>
              )}
            />
          ) : (
            <Skeleton className="h-44 w-full" />
          )}
        </Panel>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel className="lg:col-span-7" eyebrow="Retention" title="Do customers keep using QuantGen?" description="Share of each signup month still moving money N months later.">
          {cohorts.isError ? (
            <PanelError what="cohorts" onRetry={() => void cohorts.refetch()} />
          ) : cohorts.data ? (
            <CohortHeatmap cohorts={cohorts.data.cohorts} />
          ) : (
            <Skeleton className="h-56 w-full" />
          )}
        </Panel>
        <Panel className="lg:col-span-5" eyebrow="Credit" title="Loan book" action={book.data && <Badge tone={toDecimal(book.data.overdueRatePct).gte(5) ? 'warn' : 'gain'} dot>{formatPercent(book.data.overdueRatePct)} overdue</Badge>}>
          {book.isError ? (
            <PanelError what="the loan book" onRetry={() => void book.refetch()} />
          ) : book.data ? (
            <div className="space-y-5">
              <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
                <Stat label="Outstanding" value={formatINR(book.data.outstandingPrincipal, { compact: true })} />
                <Stat label="Interest earned" value={formatINR(book.data.interestEarned, { compact: true })} />
                <Stat label="Approval rate" value={formatPercent(book.data.approvalRatePct)} />
                <Stat label="Average ticket" value={formatINR(book.data.avgTicket, { compact: true })} />
              </dl>
              <div>
                <p className="eyebrow mb-3">Overdue ageing</p>
                <ShareBars
                  format={(v) => formatINR(v, { compact: true })}
                  rows={book.data.ageing.map((a) => ({
                    key: a.bucket,
                    label: `${a.bucket} days`,
                    value: a.amount,
                    sharePct: percentOf(a.amount, book.data.overdueAmount),
                    sub: `${formatCount(a.count)} instalments`,
                  }))}
                />
              </div>
            </div>
          ) : (
            <Skeleton className="h-64 w-full" />
          )}
        </Panel>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel
          className="lg:col-span-7"
          eyebrow="Explore"
          title="Breakdown"
          action={
            <Select aria-label="Break down by"
              value={dimension}
              onValueChange={(v) => setDimension(v as BreakdownDimension)}
              options={DIMENSIONS.map((d) => ({ value: d.value, label: d.text, text: d.text }))}
              className="h-9 w-56"
            />
          }
        >
          {explorer.isError ? (
            <PanelError what="the breakdown" onRetry={() => void explorer.refetch()} />
          ) : explorer.data ? (
            <ShareBars
              format={(v) => formatMetric(v, explorer.data.unit, { compact: true })}
              rows={explorer.data.rows.map((r) => ({
                key: r.key,
                label: r.label,
                value: r.value,
                sharePct: r.sharePct,
                suppressed: r.suppressed,
                sub: r.count !== null && explorer.data.unit === 'MONEY' ? `${formatCount(String(r.count))} transactions` : undefined,
              }))}
            />
          ) : (
            <Skeleton className="h-64 w-full" />
          )}
        </Panel>

        <div className="flex flex-col gap-4 lg:col-span-5">
          <Panel eyebrow="v2 features" title="Product adoption" tone="violet" description="Share of active users who use each feature.">
            {adoption.isError ? (
              <PanelError what="adoption" onRetry={() => void adoption.refetch()} />
            ) : adoption.data ? (
              <div className="space-y-5">
                <ShareBars
                  format={(v) => formatCount(v)}
                  rows={adoption.data.features.map((f) => ({ key: f.key, label: f.available ? f.label : `${f.label} (soon)`, value: f.users, sharePct: f.pct }))}
                />
                <div className="grid grid-cols-3 gap-2 text-center">
                  <MiniStat icon={<Bot />} label="AI per active user" value={adoption.data.ai.available ? `$${adoption.data.ai.costPerActiveUser}` : '—'} />
                  <MiniStat icon={<Workflow />} label="Autopilot success" value={adoption.data.autopilot.available ? formatPercent(adoption.data.autopilot.successPct) : '—'} />
                  <MiniStat icon={<ShieldAlert />} label="Fraud released" value={adoption.data.fraud.available ? formatPercent(adoption.data.fraud.releasedPct) : '—'} />
                </div>
              </div>
            ) : (
              <Skeleton className="h-64 w-full" />
            )}
          </Panel>
          <Panel eyebrow="Platform" title="Health" action={<Gauge aria-hidden="true" className="size-4 text-muted" />}>
            {health.data ? (
              <ul className="space-y-2.5 text-sm">
                <HealthRow ok={health.data.reconcileBalanced} label="Ledger reconciled" detail={`checked ${formatRelative(health.data.checkedAt)}`} />
                {health.data.sealLagSeconds != null && <HealthRow ok label="Seal lag" detail={`${health.data.sealLagSeconds}s`} />}
                {health.data.outboxLag != null && <HealthRow ok={health.data.outboxLag === '0'} label="Outbox backlog" detail={`${health.data.outboxLag} events`} />}
                {health.data.moneyErrorRatePct != null && <HealthRow ok label="Money endpoint errors" detail={formatPercent(health.data.moneyErrorRatePct, 2)} />}
              </ul>
            ) : health.isError ? (
              <PanelError what="health" onRetry={() => void health.refetch()} />
            ) : (
              <Skeleton className="h-28 w-full" />
            )}
          </Panel>
        </div>
      </div>
    </div>
  )
}

function KpiCell({
  kpi,
  range,
  granularity,
  big,
}: {
  kpi: KpiDto
  range: { from: string; to: string }
  granularity: 'DAY' | 'WEEK' | 'MONTH'
  big: boolean
}) {
  const sparkMetric = SPARK.includes(kpi.key) ? (kpi.key as AnalyticsMetric) : null
  const series = useSeries({ ...range, metric: sparkMetric ?? 'NEW_USERS', granularity }, big && !!sparkMetric)
  const Icon = KPI_ICON[kpi.key]
  return (
    <KpiTile
      kpi={kpi}
      tone={big ? 'cyan' : 'accent'}
      icon={Icon ? <Icon aria-hidden="true" /> : undefined}
      spark={big && sparkMetric ? series.data?.points.map((p) => p.value) : undefined}
    />
  )
}

/** Put the four sparkline KPIs first, then the rest in server order. */
function orderKpis(kpis: KpiDto[]) {
  const first = SPARK.map((k) => kpis.find((x) => x.key === k)).filter((x): x is KpiDto => !!x)
  return [...first, ...kpis.filter((k) => !SPARK.includes(k.key))]
}

/** Share of `part` in `whole` as a decimal string (Decimal maths, never floats). */
function percentOf(part: string, whole: string) {
  const w = toDecimal(whole)
  if (w.isZero()) return '0'
  return toDecimal(part).div(w).times(100).toDecimalPlaces(2).toFixed(2)
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="num mt-0.5 text-base font-medium">{value}</dd>
    </div>
  )
}

function MiniStat({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-xl bg-surface-2/70 p-3 hairline">
      <span className="mx-auto grid size-7 place-items-center rounded-lg bg-violet/14 text-violet [&_svg]:size-3.5">{icon}</span>
      <p className="num mt-2 text-sm font-medium">{value}</p>
      <p className="mt-0.5 text-[0.6875rem] leading-tight text-muted">{label}</p>
    </div>
  )
}

function HealthRow({ ok, label, detail }: { ok: boolean; label: string; detail: string }) {
  return (
    <li className="flex items-center justify-between gap-3">
      <span className="flex items-center gap-2">
        <span className={`size-2 rounded-full ${ok ? 'bg-gain' : 'bg-warn'}`} aria-hidden="true" />
        {label}
      </span>
      <span className="num text-xs text-muted">
        {ok ? '' : 'Attention · '}
        {detail}
      </span>
    </li>
  )
}
