import { AlertTriangle, ArrowDownLeft, ArrowUpRight, Calculator, PiggyBank, Target, TrendingUp, Wallet } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { analyticsApi } from '@/api/endpoints.v2'
import { errorMessage } from '@/api/errors'
import { useAccounts } from '@/api/queries'
import type { DecimalString, HistoryRange, MoneyString, PortfolioRange, PrepaymentDto } from '@/api/types'
import { ShareBars } from '@/components/charts/Bits'
import { Donut } from '@/components/charts/Donut'
import { TrendChart } from '@/components/charts/TrendChart'
import { KpiTile, KpiTileSkeleton } from '@/components/KpiTile'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { PageHeader, EmptyState } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { RangePicker } from '@/components/RangePicker'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { TabPanel, Tabs } from '@/components/ui/tabs'
import { accountLabel } from '@/features/accounts/labels'
import { categoryOf } from '@/lib/categories'
import { formatPercent } from '@/lib/format'
import { AMOUNT_INPUT_RE, formatINR, ratio, toDecimal } from '@/lib/money'
import { rangeFor, type RangePreset } from '@/lib/range'
import { formatDate } from '@/lib/time'
import { cn } from '@/lib/utils'
import {
  useBalanceHistory,
  useCashflow,
  useCounterparties,
  useGoalProgress,
  useLoanAnalytics,
  useMoneySummary,
  usePortfolioAnalytics,
} from '@/features/analytics/hooks'
import { BudgetsDialog } from './BudgetsDialog'
import { useBudgets, useForecast, useNetWorth, useSpending } from './hooks'

type Tab = 'overview' | 'spending' | 'wealth' | 'plan'

/** % change between two decimal strings, as a decimal string (null when the base is 0). */
function change(now: DecimalString, before: DecimalString): DecimalString | null {
  const b = toDecimal(before)
  if (b.isZero()) return null
  return toDecimal(now).minus(b).div(b.abs()).times(100).toDecimalPlaces(2).toFixed(2)
}

/**
 * Personal insights (PRD F-ANL-11…17, F-INS-1…4). Everything here is read-only and computed by
 * the server with the same definitions the bank uses (income, spending, savings rate).
 */
export function InsightsPage() {
  const [params, setParams] = useSearchParams()
  const tab = (['overview', 'spending', 'wealth', 'plan'].includes(params.get('tab') ?? '') ? params.get('tab') : 'overview') as Tab
  const [preset, setPreset] = useState<RangePreset>('30D')
  const range = useMemo(() => rangeFor(preset), [preset])

  return (
    <div>
      <PageHeader
        eyebrow="Insights"
        title={
          <>
            Where your money <em className="text-gradient">actually goes.</em>
          </>
        }
        description="Income, spending, savings rate and your wealth over time, computed from your ledger."
        actions={tab === 'overview' || tab === 'spending' ? <RangePicker value={preset} onChange={setPreset} /> : undefined}
      />
      <Tabs
        label="Insights sections"
        value={tab}
        onValueChange={(v) => setParams(v === 'overview' ? {} : { tab: v }, { replace: true })}
        tabs={[
          { value: 'overview', label: 'Overview' },
          { value: 'spending', label: 'Spending' },
          { value: 'wealth', label: 'Wealth' },
          { value: 'plan', label: 'Plan ahead' },
        ]}
      >
        <TabPanel value="overview">
          <OverviewTab range={range} />
        </TabPanel>
        <TabPanel value="spending">
          <SpendingTab range={range} />
        </TabPanel>
        <TabPanel value="wealth">
          <WealthTab />
        </TabPanel>
        <TabPanel value="plan">
          <PlanTab />
        </TabPanel>
      </Tabs>
    </div>
  )
}

// ── Overview ────────────────────────────────────────────────────────────────────────────
function OverviewTab({ range }: { range: { from: string; to: string } }) {
  const summary = useMoneySummary(range)
  const cashflow = useCashflow(12)
  const people = useCounterparties(range)
  const s = summary.data

  return (
    <div className="space-y-4">
      {summary.isError ? (
        <Panel title="Summary">
          <PanelError what="your summary" onRetry={() => void summary.refetch()} />
        </Panel>
      ) : (
        <section aria-label="This period" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {s ? (
            <>
              <KpiTile icon={<ArrowDownLeft aria-hidden="true" />} kpi={{ label: 'Money in', unit: 'MONEY', value: s.income, changePct: change(s.income, s.previous.income), goodDirection: 'UP', previous: s.previous.income }} />
              <KpiTile icon={<ArrowUpRight aria-hidden="true" />} kpi={{ label: 'Spent', unit: 'MONEY', value: s.spending, changePct: change(s.spending, s.previous.spending), goodDirection: 'DOWN', previous: s.previous.spending }} />
              <KpiTile icon={<Wallet aria-hidden="true" />} kpi={{ label: 'Net', unit: 'MONEY', value: s.net, changePct: change(s.net, s.previous.net), goodDirection: 'UP', previous: s.previous.net }} />
              <KpiTile
                tone="cyan"
                icon={<PiggyBank aria-hidden="true" />}
                kpi={{
                  label: 'Savings rate',
                  unit: 'PERCENT',
                  value: s.savingsRatePct ?? '0',
                  changePct: s.savingsRatePct && s.previous.savingsRatePct ? toDecimal(s.savingsRatePct).minus(s.previous.savingsRatePct).toFixed(2) : null,
                  goodDirection: 'UP',
                }}
                footer={<p className="text-xs text-muted">Change shown in percentage points.</p>}
              />
            </>
          ) : (
            Array.from({ length: 4 }).map((_, i) => <KpiTileSkeleton key={i} />)
          )}
        </section>
      )}

      {s && (s.topCategory || s.largestSpend) && (
        <div className="grid gap-3 sm:grid-cols-2">
          {s.topCategory && (
            <Callout icon={categoryOf(s.topCategory.key).icon} title="Biggest category">
              <span className="font-medium text-text">{s.topCategory.label}</span> took{' '}
              <Money value={s.topCategory.amount} className="text-text" /> this period.
            </Callout>
          )}
          {s.largestSpend && (
            <Callout icon={ArrowUpRight} title="Largest single spend">
              <Money value={s.largestSpend.amount} className="text-text" /> · {s.largestSpend.description} on{' '}
              {formatDate(s.largestSpend.date)}.
            </Callout>
          )}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel className="lg:col-span-8" tone="cyan" eyebrow="Last 12 months" title="Cash flow" description="Money in vs spending each month. The line is what you kept.">
          {cashflow.isError ? (
            <PanelError what="cash flow" onRetry={() => void cashflow.refetch()} />
          ) : cashflow.data ? (
            <TrendChart
              ariaLabel="Monthly income and spending for the last 12 months"
              height={280}
              unit="MONEY"
              monthAxis
              rows={cashflow.data.points.map((p) => ({ t: p.month, values: { income: p.income, spending: p.spending, net: p.net } }))}
              series={[
                { key: 'income', label: 'Money in', color: 'var(--chart-5)', kind: 'bar' },
                { key: 'spending', label: 'Spent', color: 'var(--chart-4)', kind: 'bar' },
                { key: 'net', label: 'Kept', color: 'var(--chart-1)', kind: 'line' },
              ]}
            />
          ) : (
            <Skeleton className="h-[280px] w-full rounded-xl" />
          )}
        </Panel>
        <Panel className="lg:col-span-4" eyebrow="People & places" title="Who you pay most">
          {people.isError ? (
            <PanelError what="counterparties" onRetry={() => void people.refetch()} />
          ) : people.data ? (
            people.data.length === 0 ? (
              <p className="py-6 text-center text-sm text-muted">No payments in this period.</p>
            ) : (
              <ul className="divide-y divide-border">
                {people.data.map((p) => (
                  <li key={p.label + p.accountNumberMasked} className="flex items-center gap-3 py-3">
                    <span className="grid size-9 shrink-0 place-items-center rounded-full bg-gradient-to-br from-violet/30 to-cyan/20 text-xs font-semibold hairline">
                      {p.label.split(/\s+/).map((w) => w[0]).slice(0, 2).join('')}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">{p.label}</p>
                      <p className="num text-xs text-muted">
                        {p.accountNumberMasked} · {p.count} payment{p.count === 1 ? '' : 's'}
                      </p>
                    </div>
                    <div className="text-right text-xs">
                      <Money value={p.sent} tone="debit" className="block" />
                      {toDecimal(p.received).gt(0) && <Money value={p.received} tone="credit" className="block" />}
                    </div>
                  </li>
                ))}
              </ul>
            )
          ) : (
            <Skeleton className="h-64 w-full" />
          )}
        </Panel>
      </div>
    </div>
  )
}

function Callout({ icon: Icon, title, children }: { icon: typeof Wallet; title: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-3 rounded-[var(--radius-card)] bg-surface p-4 hairline">
      <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-accent/12 text-accent">
        <Icon aria-hidden="true" className="size-4" />
      </span>
      <div>
        <p className="eyebrow">{title}</p>
        <p className="mt-1 text-sm text-muted">{children}</p>
      </div>
    </div>
  )
}

// ── Spending ────────────────────────────────────────────────────────────────────────────
function SpendingTab({ range }: { range: { from: string; to: string } }) {
  const byCat = useSpending({ ...range, groupBy: 'category' })
  const byMonth = useSpending({ groupBy: 'month' })
  const budgets = useBudgets()
  const [editing, setEditing] = useState(false)
  const total = byCat.data?.reduce((sum, r) => sum.plus(r.amount), toDecimal('0'))

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
      <Panel className="lg:col-span-7" eyebrow="By category" title="Where it went">
        {byCat.isError ? (
          <PanelError what="spending" onRetry={() => void byCat.refetch()} />
        ) : byCat.data && total ? (
          byCat.data.length === 0 ? (
            <EmptyState icon={<Wallet />} title="No spending yet">Payments, bills and card spends will show up here.</EmptyState>
          ) : (
            <Donut
              ariaLabel="Spending by category"
              slices={byCat.data.map((r) => ({ key: r.key, label: categoryOf(r.key).label, value: r.amount, color: categoryOf(r.key).color }))}
              formatValue={(sl) => formatINR(sl.value, { compact: true })}
              center={(a) => (
                <>
                  <p className="text-[0.6875rem] text-muted">{a ? a.label : 'Total spent'}</p>
                  <p className="num text-xl font-medium">{formatINR(a ? a.value : total, { compact: true })}</p>
                  {a && <p className="num text-[0.6875rem] text-muted">{formatPercent(toDecimal(a.value).div(total).times(100).toFixed(2))}</p>}
                </>
              )}
            />
          )
        ) : (
          <Skeleton className="h-56 w-full" />
        )}
      </Panel>

      <Panel
        className="lg:col-span-5"
        eyebrow="This month"
        title="Budgets"
        action={
          budgets.data && (
            <Button variant="secondary" size="sm" onClick={() => setEditing(true)}>
              {budgets.data.length ? 'Edit' : 'Set budgets'}
            </Button>
          )
        }
      >
        {budgets.isError ? (
          <PanelError what="budgets" onRetry={() => void budgets.refetch()} />
        ) : budgets.data ? (
          budgets.data.length === 0 ? (
            <EmptyState icon={<Target />} title="No budgets yet" action={<Button size="sm" onClick={() => setEditing(true)}>Set a budget</Button>}>
              Pick a few categories and a monthly limit. We’ll warn you before you go over.
            </EmptyState>
          ) : (
            <ul className="space-y-4">
              {budgets.data.map((b) => {
                const pct = toDecimal(b.pct)
                const tone = pct.gte(100) ? 'bg-loss' : pct.gte(80) ? 'bg-warn' : 'bg-gain'
                const c = categoryOf(b.category)
                return (
                  <li key={b.category}>
                    <div className="mb-1.5 flex items-center justify-between gap-3 text-sm">
                      <span className="flex items-center gap-2">
                        <c.icon aria-hidden="true" className="size-4 text-muted" />
                        {c.label}
                      </span>
                      <span className="num text-xs text-muted">
                        <span className={cn(pct.gte(100) ? 'text-loss' : 'text-text')}>{formatINR(b.spent, { compact: true })}</span> / {formatINR(b.monthlyLimit, { compact: true })}
                      </span>
                    </div>
                    <div
                      className="h-2 overflow-hidden rounded-full bg-surface-2"
                      role="progressbar"
                      aria-label={`${c.label} budget used`}
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.min(100, Math.round(ratio(b.spent, b.monthlyLimit) * 100))}
                    >
                      <div className={cn('h-full rounded-full', tone)} style={{ width: `${Math.min(100, ratio(b.spent, b.monthlyLimit) * 100)}%` }} />
                    </div>
                    {pct.gte(80) && (
                      <p className={cn('mt-1 text-xs', pct.gte(100) ? 'text-loss' : 'text-warn')}>
                        {pct.gte(100) ? 'Over budget' : `${formatPercent(b.pct, 0)} used`}
                      </p>
                    )}
                  </li>
                )
              })}
            </ul>
          )
        ) : (
          <Skeleton className="h-56 w-full" />
        )}
        {editing && budgets.data && <BudgetsDialog open onOpenChange={setEditing} budgets={budgets.data} />}
      </Panel>

      <Panel className="lg:col-span-12" eyebrow="Last 6 months" title="Monthly spending">
        {byMonth.data ? (
          <TrendChart
            ariaLabel="Total spending per month"
            height={220}
            unit="MONEY"
            monthAxis
            rows={byMonth.data.map((r) => ({ t: r.key, values: { spent: r.amount } }))}
            series={[{ key: 'spent', label: 'Spent', color: 'var(--chart-2)', kind: 'bar' }]}
          />
        ) : byMonth.isError ? (
          <PanelError what="monthly spending" onRetry={() => void byMonth.refetch()} />
        ) : (
          <Skeleton className="h-[220px] w-full" />
        )}
      </Panel>
    </div>
  )
}

// ── Wealth ──────────────────────────────────────────────────────────────────────────────
function WealthTab() {
  const net = useNetWorth(12)
  const accounts = useAccounts()
  const [account, setAccount] = useState<string>('all')
  const [range, setRange] = useState<HistoryRange>('3M')
  const history = useBalanceHistory({ accountId: account === 'all' ? undefined : account, range })
  const [pRange, setPRange] = useState<PortfolioRange>('3M')
  const portfolio = usePortfolioAnalytics(pRange)

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel className="lg:col-span-8" tone="cyan" eyebrow="Net worth" title={net.data ? <span className="font-display text-5xl font-normal">{formatINR(net.data.current)}</span> : <Skeleton className="h-12 w-64" />} description="Cash + goals + fixed deposits + investments − loans.">
          {net.data ? (
            <TrendChart
              ariaLabel="Net worth over the last 12 months"
              yDomain="auto"
              height={220}
              unit="MONEY"
              monthAxis
              rows={net.data.history.map((h) => ({ t: h.month, values: { v: h.value } }))}
              series={[{ key: 'v', label: 'Net worth', color: 'var(--chart-3)' }]}
            />
          ) : net.isError ? (
            <PanelError what="net worth" onRetry={() => void net.refetch()} />
          ) : (
            <Skeleton className="h-[220px] w-full" />
          )}
        </Panel>
        <Panel className="lg:col-span-4" eyebrow="Breakdown" title="What it’s made of">
          {net.data ? (
            <ul className="space-y-3 text-sm">
              {(
                [
                  ['Cash', net.data.breakdown.cash, 'var(--chart-1)'],
                  ['Goals', net.data.breakdown.goals, 'var(--chart-5)'],
                  ['Fixed deposits', net.data.breakdown.fixedDeposits, 'var(--chart-2)'],
                  ['Investments', net.data.breakdown.investments, 'var(--chart-3)'],
                ] as const
              ).map(([label, v, color]) => (
                <li key={label} className="flex items-center justify-between gap-3">
                  <span className="flex items-center gap-2">
                    <span className="size-2.5 rounded-full" style={{ background: color }} />
                    {label}
                  </span>
                  <Money value={v} />
                </li>
              ))}
              <li className="flex items-center justify-between gap-3 border-t border-border pt-3">
                <span className="flex items-center gap-2">
                  <span className="size-2.5 rounded-full bg-loss" />
                  Loans owed
                </span>
                <Money value={toDecimal(net.data.breakdown.loans).neg()} />
              </li>
            </ul>
          ) : (
            <Skeleton className="h-48 w-full" />
          )}
        </Panel>
      </div>

      <Panel
        eyebrow="Balance history"
        title="End-of-day balance"
        action={
          <div className="flex flex-wrap items-center gap-2">
            <Select
              value={account}
              onValueChange={setAccount}
              className="h-9 w-48"
              options={[
                { value: 'all', label: 'All cash accounts', text: 'All cash accounts' },
                ...(accounts.data ?? []).map((a) => ({ value: a.id, label: accountLabel(a), text: accountLabel(a) })),
              ]}
            />
            <Segmented label="Range" value={range} onValueChange={setRange} options={(['1M', '3M', '6M', '1Y'] as const).map((v) => ({ value: v, label: v }))} />
          </div>
        }
      >
        {history.data ? (
          <TrendChart
            ariaLabel="End-of-day balance"
            height={240}
            unit="MONEY"
            rows={history.data.points.map((p) => ({ t: p.t, values: { b: p.balance } }))}
            series={[{ key: 'b', label: 'Balance', color: 'var(--chart-1)' }]}
          />
        ) : history.isError ? (
          <PanelError what="balance history" onRetry={() => void history.refetch()} />
        ) : (
          <Skeleton className="h-[240px] w-full" />
        )}
      </Panel>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Panel
          className="lg:col-span-8"
          tone="violet"
          eyebrow="Portfolio performance"
          title={
            portfolio.data ? (
              <span className="flex items-baseline gap-3">
                Time-weighted return
                <Badge tone={toDecimal(portfolio.data.twrPct).gte(0) ? 'gain' : 'loss'}>
                  <TrendingUp aria-hidden="true" className="size-3" />
                  {formatPercent(portfolio.data.twrPct)}
                </Badge>
              </span>
            ) : (
              'Time-weighted return'
            )
          }
          action={<Segmented label="Range" value={pRange} onValueChange={setPRange} options={(['1M', '3M', '6M', '1Y', 'ALL'] as const).map((v) => ({ value: v, label: v }))} />}
        >
          {portfolio.data ? (
            <TrendChart
              ariaLabel="Portfolio market value against the amount invested"
              yDomain="auto"
              height={240}
              unit="MONEY"
              rows={portfolio.data.points.map((p) => ({ t: p.t, values: { mv: p.marketValue, inv: p.invested } }))}
              series={[
                { key: 'mv', label: 'Market value', color: 'var(--chart-2)' },
                { key: 'inv', label: 'Invested', color: 'var(--text-muted)', kind: 'dashed' },
              ]}
            />
          ) : portfolio.isError ? (
            <PanelError what="portfolio performance" onRetry={() => void portfolio.refetch()} />
          ) : (
            <Skeleton className="h-[240px] w-full" />
          )}
        </Panel>
        <Panel className="lg:col-span-4" eyebrow="Allocation" title="By sector">
          {portfolio.data ? (
            <div className="space-y-5">
              <ShareBars format={(v) => formatINR(v, { compact: true })} rows={portfolio.data.allocation.map((a) => ({ key: a.sector, label: a.sector, value: a.value, sharePct: a.pct }))} />
              {(portfolio.data.best || portfolio.data.worst) && (
                <div className="grid grid-cols-2 gap-2 text-sm">
                  {portfolio.data.best && (
                    <div className="rounded-xl bg-gain/[0.07] p-3 hairline">
                      <p className="text-xs text-muted">Best</p>
                      <p className="mt-1 font-medium">{portfolio.data.best.symbol}</p>
                      <p className="num text-xs text-gain">{formatPercent(portfolio.data.best.pnlPct)}</p>
                    </div>
                  )}
                  {portfolio.data.worst && (
                    <div className="rounded-xl bg-loss/[0.07] p-3 hairline">
                      <p className="text-xs text-muted">Worst</p>
                      <p className="mt-1 font-medium">{portfolio.data.worst.symbol}</p>
                      <p className={cn('num text-xs', toDecimal(portfolio.data.worst.pnlPct).lt(0) ? 'text-loss' : 'text-gain')}>{formatPercent(portfolio.data.worst.pnlPct)}</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          ) : (
            <Skeleton className="h-56 w-full" />
          )}
        </Panel>
      </div>
    </div>
  )
}

// ── Plan ahead ──────────────────────────────────────────────────────────────────────────
function PlanTab() {
  const forecast = useForecast(30)
  const goals = useGoalProgress()
  const loans = useLoanAnalytics()

  return (
    <div className="space-y-4">
      <Panel tone="cyan" eyebrow="Next 30 days" title="Cash-flow forecast" description="Known items only: salary pattern, EMIs, scheduled transfers and Autopilot schedules.">
        {forecast.data ? (
          <>
            {forecast.data.warnings.length > 0 && (
              <ul className="mb-4 space-y-2">
                {forecast.data.warnings.map((w) => (
                  <li key={w.date + w.code} className="flex items-start gap-2 rounded-xl border border-warn/30 bg-warn/[0.07] p-3 text-sm">
                    <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warn" />
                    {w.message}
                  </li>
                ))}
              </ul>
            )}
            <TrendChart
              ariaLabel="Projected balance for the next 30 days"
              yDomain="auto"
              height={240}
              unit="MONEY"
              rows={forecast.data.points.map((p) => ({ t: p.date, values: { b: p.balance } }))}
              series={[{ key: 'b', label: 'Projected balance', color: 'var(--chart-3)' }]}
            />
          </>
        ) : forecast.isError ? (
          <PanelError what="the forecast" onRetry={() => void forecast.refetch()} />
        ) : (
          <Skeleton className="h-[240px] w-full" />
        )}
      </Panel>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Panel eyebrow="Goals" title="Are you on track?">
          {goals.data ? (
            goals.data.length === 0 ? (
              <EmptyState icon={<Target />} title="No goals yet">Create one in Savings and we’ll track your pace here.</EmptyState>
            ) : (
              <ul className="space-y-4">
                {goals.data.map((g) => (
                  <li key={g.accountId}>
                    <div className="flex items-baseline justify-between gap-3 text-sm">
                      <span className="font-medium">{g.name}</span>
                      <Badge tone={g.onTrack ? 'gain' : 'warn'} dot>
                        {g.onTrack ? 'On track' : 'Behind'}
                      </Badge>
                    </div>
                    <div className="mt-2 h-2 overflow-hidden rounded-full bg-surface-2">
                      <div className="h-full rounded-full bg-gradient-to-r from-chart-5 to-chart-3" style={{ width: `${Math.min(100, ratio(g.saved, g.target) * 100)}%` }} />
                    </div>
                    <p className="num mt-1.5 text-xs text-muted">
                      {formatINR(g.saved, { compact: true })} of {formatINR(g.target, { compact: true })} · saving {formatINR(g.monthlyPace, { compact: true })}/month
                      {g.projectedDate ? ` · done by ${formatDate(g.projectedDate)}` : ''}
                    </p>
                  </li>
                ))}
              </ul>
            )
          ) : goals.isError ? (
            <PanelError what="goals" onRetry={() => void goals.refetch()} />
          ) : (
            <Skeleton className="h-48 w-full" />
          )}
        </Panel>
        <Panel eyebrow="Loans" title="Paying it down" action={<Calculator aria-hidden="true" className="size-4 text-muted" />}>
          {loans.data ? (
            loans.data.loans.length === 0 ? (
              <EmptyState icon={<Wallet />} title="No active loans">You’re debt-free here.</EmptyState>
            ) : (
              <div className="space-y-5">
                {loans.data.loans.map((l) => (
                  <div key={l.loanId}>
                    <div className="h-3 overflow-hidden rounded-full bg-surface-2">
                      <div className="h-full rounded-full bg-chart-1" style={{ width: `${ratio(l.principalPaid, toDecimal(l.principalPaid).plus(l.remainingPrincipal)) * 100}%` }} />
                    </div>
                    <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
                      <div>
                        <dt className="text-xs text-muted">Principal paid</dt>
                        <dd><Money value={l.principalPaid} /></dd>
                      </div>
                      <div>
                        <dt className="text-xs text-muted">Interest paid</dt>
                        <dd><Money value={l.interestPaid} /></dd>
                      </div>
                      <div>
                        <dt className="text-xs text-muted">Left</dt>
                        <dd><Money value={l.remainingPrincipal} /></dd>
                      </div>
                    </dl>
                    <PrepaymentCalc loanId={l.loanId} remaining={l.remainingPrincipal} />
                  </div>
                ))}
              </div>
            )
          ) : loans.isError ? (
            <PanelError what="loans" onRetry={() => void loans.refetch()} />
          ) : (
            <Skeleton className="h-48 w-full" />
          )}
        </Panel>
      </div>
    </div>
  )
}

/** "What if I prepay ₹X?" — a server-side calculation (F-ANL-16). Never moves money. */
function PrepaymentCalc({ loanId, remaining }: { loanId: string; remaining: MoneyString }) {
  const [amount, setAmount] = useState('')
  const [result, setResult] = useState<PrepaymentDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run() {
    setError(null)
    setResult(null)
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1)) {
      setError('Enter an amount of at least ₹1.')
      return
    }
    setBusy(true)
    try {
      setResult(await analyticsApi.prepayment(loanId, amount))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-4 rounded-xl bg-surface-2/60 p-4 hairline">
      <p className="text-sm font-medium">What if I prepay?</p>
      <div className="mt-3 flex items-end gap-2">
        <div className="flex-1">
          <AmountInput label="Prepayment amount" className="h-11 [&_input]:text-base" value={amount} onValueChange={setAmount} hint={`Outstanding: ${formatINR(remaining)}`} />
        </div>
        <Button variant="secondary" className="mb-6" onClick={() => void run()} disabled={busy}>
          {busy ? 'Calculating…' : 'Calculate'}
        </Button>
      </div>
      {error && <p role="alert" className="text-sm text-loss">{error}</p>}
      {result && (
        <p className="text-sm" aria-live="polite">
          You’d save <Money value={result.interestSaved} className="font-medium text-gain" /> in interest and finish{' '}
          <span className="font-medium">{result.monthsSaved} month{result.monthsSaved === 1 ? '' : 's'}</span> earlier ({formatDate(result.newPayoffDate)}). Nothing has been paid.
        </p>
      )}
    </div>
  )
}
