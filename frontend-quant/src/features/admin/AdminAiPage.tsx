import { Bot, Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useFlag } from '@/api/flags'
import { TrendChart } from '@/components/charts/TrendChart'
import { ShareBars } from '@/components/charts/Bits'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { RangePicker } from '@/components/RangePicker'
import { Button } from '@/components/ui/button'
import { Input, Label } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { TraceDialog } from '@/features/copilot/components/TraceDialog'
import { AGENT_LABEL } from '@/features/copilot/labels'
import { CHART_COLORS } from '@/lib/chart'
import { formatCount, formatPercent, formatUsd } from '@/lib/format'
import { Decimal } from '@/lib/money'
import { rangeFor, type RangePreset } from '@/lib/range'
import { cn } from '@/lib/utils'
import { useAiUsage } from './riskHooks'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** `/admin/ai` (F-AGT-7): token spend, cost per agent and day, the monthly budget, and run traces. */
export function AdminAiPage() {
  const on = useFlag('ai')
  const [preset, setPreset] = useState<RangePreset>('30D')
  const range = useMemo(() => rangeFor(preset), [preset])
  const usage = useAiUsage(range.from, range.to)
  const [runId, setRunId] = useState('')
  const [open, setOpen] = useState<string | null>(null)
  const u = usage.data
  const used = u ? new Decimal(u.budget.usedPct) : null

  return (
    <div>
      <PageHeader
        title="AI usage"
        description="What the agents cost and who used them. The monthly cap is a hard stop: at 100% Copilot switches itself off until the next month."
        actions={<RangePicker value={preset} onChange={setPreset} />}
      />
      {!on && (
        <p className="mb-4 rounded-[var(--radius-control)] bg-warn/10 px-3 py-2 text-sm text-warn">
          The AI flag is off, so no new runs are happening. History is still shown.
        </p>
      )}
      {usage.isError ? (
        <PanelError what="AI usage" onRetry={() => void usage.refetch()} />
      ) : !u ? (
        <Skeleton className="h-80" />
      ) : (
        <div className={cn('grid gap-4 lg:grid-cols-12', usage.isPlaceholderData && 'opacity-60')}>
          <div className="grid grid-cols-2 gap-3 lg:col-span-12 lg:grid-cols-4">
            <Tile
              label="Cost"
              value={
                new Decimal(u.costUsd).isZero()
                  ? `${formatUsd(u.costUsd)} · free tier`
                  : formatUsd(u.costUsd)
              }
            />
            <Tile label="Input tokens" value={formatCount(String(u.tokens.input), true)} />
            <Tile label="Output tokens" value={formatCount(String(u.tokens.output), true)} />
            <Tile
              label="Monthly budget used"
              value={formatPercent(u.budget.usedPct, 0)}
              sub={`of ${formatUsd(u.budget.monthlyCapUsd)}`}
              tone={used && used.gte(90) ? 'loss' : used && used.gte(75) ? 'warn' : undefined}
              bar={used ? Math.min(100, used.toNumber()) : 0}
            />
          </div>
          <Panel title="Cost per day" eyebrow="Spend" className="lg:col-span-8">
            {u.byDay.length === 0 ? (
              <EmptyState icon={<Bot />} title="No AI runs in this period" />
            ) : (
              <TrendChart
                ariaLabel="AI cost per day in US dollars"
                unit="MONEY"
                currency="USD"
                rows={u.byDay.map((d) => ({ t: d.date, values: { cost: d.costUsd } }))}
                series={[
                  {
                    key: 'cost',
                    label: 'Cost',
                    color: CHART_COLORS[0] ?? 'var(--violet)',
                    kind: 'bar',
                  },
                ]}
              />
            )}
          </Panel>
          <Panel title="By agent" eyebrow="Who spends it" className="lg:col-span-4">
            {u.byAgent.length === 0 ? (
              <p className="text-sm text-muted">No runs yet.</p>
            ) : (
              <ShareBars
                format={(v) => formatUsd(v)}
                rows={[...u.byAgent]
                  .sort((a, b) => new Decimal(b.costUsd).cmp(a.costUsd))
                  .map((a) => ({
                    key: a.agent,
                    label: AGENT_LABEL[a.agent] ?? a.agent,
                    value: a.costUsd,
                    sharePct: new Decimal(u.costUsd).isZero()
                      ? '0'
                      : new Decimal(a.costUsd).div(u.costUsd).times(100).toFixed(1),
                    sub: `${formatCount(String(a.runs))} runs`,
                  }))}
              />
            )}
          </Panel>
          <Panel title="Look up a run" eyebrow="Traces" className="lg:col-span-12">
            <form
              className="flex flex-col gap-2 sm:flex-row sm:items-end"
              onSubmit={(e) => {
                e.preventDefault()
                if (UUID.test(runId.trim())) setOpen(runId.trim())
              }}
            >
              <div className="flex-1 space-y-1.5">
                <Label htmlFor="run-id">
                  Run id (from a fraud memo, a customer report or the logs)
                </Label>
                <Input
                  id="run-id"
                  value={runId}
                  onChange={(e) => setRunId(e.target.value)}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  className="num"
                />
              </div>
              <Button type="submit" variant="secondary" disabled={!UUID.test(runId.trim())}>
                <Search aria-hidden="true" /> Open trace
              </Button>
            </form>
          </Panel>
        </div>
      )}
      {open && <TraceDialog runId={open} admin onClose={() => setOpen(null)} />}
    </div>
  )
}

function Tile({
  label,
  value,
  sub,
  tone,
  bar,
}: {
  label: string
  value: string
  sub?: string
  tone?: 'warn' | 'loss'
  bar?: number
}) {
  return (
    <div className="rounded-[var(--radius-card)] bg-surface p-4 hairline">
      <p className="text-xs text-muted">{label}</p>
      <p
        className={cn(
          'mt-1 font-display text-2xl tabular-nums',
          tone === 'loss' && 'text-loss',
          tone === 'warn' && 'text-warn',
        )}
      >
        {value}
      </p>
      {sub && <p className="text-xs text-muted">{sub}</p>}
      {bar !== undefined && (
        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-2" role="presentation">
          <div
            className={cn(
              'h-full rounded-full',
              tone === 'loss' ? 'bg-loss' : tone === 'warn' ? 'bg-warn' : 'bg-violet',
            )}
            style={{ width: `${bar}%` }}
          />
        </div>
      )}
    </div>
  )
}
