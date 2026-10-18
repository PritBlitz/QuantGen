import { useId, useMemo } from 'react'
import {
  Area,
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { MetricUnit } from '@/api/types'
import { axisDate, plotValue } from '@/lib/chart'
import { formatMetric } from '@/lib/format'
import { cn } from '@/lib/utils'

export interface TrendSeries {
  key: string
  label: string
  color: string
  kind?: 'area' | 'line' | 'bar' | 'dashed'
  /** Stack id for bars/areas that stack. */
  stack?: string
}

export interface TrendRow {
  t: string
  /** Decimal strings by series key (display truth). */
  values: Record<string, string | null | undefined>
}

/**
 * The one time-series chart (DESIGN.md v2 §5): soft gradient areas, crisp lines, optional bars,
 * dashed "previous period" overlays, a glass tooltip that formats the **original strings**.
 * Recharts gets numbers for geometry only (lib/chart.ts `plotValue`).
 */
export function TrendChart({
  rows,
  series,
  unit,
  currency,
  height = 260,
  monthAxis,
  compactAxis = true,
  className,
  ariaLabel,
  hideYAxis,
  yDomain = 'zero',
}: {
  rows: TrendRow[]
  series: TrendSeries[]
  unit: MetricUnit
  currency?: 'INR' | 'USD'
  height?: number
  monthAxis?: boolean
  compactAxis?: boolean
  className?: string
  ariaLabel: string
  hideYAxis?: boolean
  /** `auto` fits the data (balances, portfolio value); `zero` anchors at 0 (volumes, counts). */
  yDomain?: 'zero' | 'auto'
}) {
  const gid = useId().replace(/:/g, '')
  const data = useMemo(
    () =>
      rows.map((r) => {
        const o: Record<string, number | string> = { t: r.t }
        for (const s of series) o[s.key] = plotValue(r.values[s.key])
        return o
      }),
    [rows, series],
  )
  const raw = useMemo(() => new Map(rows.map((r) => [r.t, r.values])), [rows])

  return (
    <div className={cn('w-full', className)} role="img" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: hideYAxis ? 4 : 0 }}>
          <defs>
            {series.map((s) => (
              <linearGradient key={s.key} id={`${gid}-${s.key}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={s.color} stopOpacity={0.32} />
                <stop offset="100%" stopColor={s.color} stopOpacity={0} />
              </linearGradient>
            ))}
          </defs>
          <CartesianGrid vertical={false} stroke="var(--grid)" />
          <XAxis
            dataKey="t"
            tickFormatter={(t: string) => axisDate(t, monthAxis)}
            tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            minTickGap={28}
          />
          {!hideYAxis && (
            <YAxis
              width={64}
              domain={yDomain === 'auto' ? ['auto', 'auto'] : [0, 'auto']}
              tick={{ fill: 'var(--text-muted)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
              axisLine={false}
              tickLine={false}
              tickFormatter={(v: number) =>
                // Axis ticks are computed by Recharts; format them as compact labels only.
                formatMetric(String(Math.round(v)), unit, { compact: compactAxis, currency })
              }
            />
          )}
          <Tooltip
            cursor={{ stroke: 'var(--border-strong)', strokeWidth: 1 }}
            content={(p) => (
              <ChartTooltip
                active={p.active}
                label={typeof p.label === 'string' ? p.label : undefined}
                series={series}
                raw={raw}
                unit={unit}
                currency={currency}
                monthAxis={monthAxis}
              />
            )}
          />
          {series.map((s) =>
            s.kind === 'bar' ? (
              <Bar
                key={s.key}
                dataKey={s.key}
                stackId={s.stack}
                fill={s.color}
                radius={[6, 6, 0, 0]}
                maxBarSize={28}
                isAnimationActive={false}
              />
            ) : s.kind === 'line' || s.kind === 'dashed' ? (
              <Line
                key={s.key}
                dataKey={s.key}
                type="monotone"
                stroke={s.color}
                strokeWidth={s.kind === 'dashed' ? 1.5 : 2}
                strokeDasharray={s.kind === 'dashed' ? '4 4' : undefined}
                strokeOpacity={s.kind === 'dashed' ? 0.6 : 1}
                dot={false}
                activeDot={{ r: 4, strokeWidth: 0, fill: s.color }}
                isAnimationActive={false}
              />
            ) : (
              <Area
                key={s.key}
                dataKey={s.key}
                type="monotone"
                stackId={s.stack}
                stroke={s.color}
                strokeWidth={2}
                fill={`url(#${gid}-${s.key})`}
                dot={false}
                activeDot={{ r: 4, strokeWidth: 0, fill: s.color }}
                isAnimationActive={false}
              />
            ),
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

function ChartTooltip({
  active,
  label,
  series,
  raw,
  unit,
  currency,
  monthAxis,
}: {
  active?: boolean
  label?: string
  series: TrendSeries[]
  raw: Map<string, TrendRow['values']>
  unit: MetricUnit
  currency?: 'INR' | 'USD'
  monthAxis?: boolean
}) {
  if (!active || !label) return null
  const values = raw.get(label)
  if (!values) return null
  return (
    <div className="glass min-w-40 rounded-xl px-3 py-2.5 text-xs shadow-[var(--shadow-pop)]">
      <p className="mb-1.5 text-muted">{axisDate(label, monthAxis)}</p>
      <ul className="space-y-1">
        {series.map((s) => (
          <li key={s.key} className="flex items-center justify-between gap-4">
            <span className="flex items-center gap-1.5 text-muted">
              <span className="size-2 rounded-full" style={{ background: s.color }} />
              {s.label}
            </span>
            <span className="num font-medium text-text">
              {formatMetric(values[s.key] ?? null, unit, { currency })}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
