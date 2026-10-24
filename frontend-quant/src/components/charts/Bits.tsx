import { motion, useReducedMotion } from 'motion/react'
import { useId, useMemo, type ReactNode } from 'react'
import { Lock } from 'lucide-react'
import type { DecimalString } from '@/api/types'
import { CHART_COLORS, plotValue } from '@/lib/chart'
import { formatCount, formatPercent } from '@/lib/format'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { cn } from '@/lib/utils'

/** Tiny inline trend line (KPI tiles, tables). Pure SVG, no axes. */
export function Sparkline({
  values,
  color = 'var(--chart-1)',
  className,
  height = 36,
  fill = true,
}: {
  values: Array<DecimalString | null>
  color?: string
  className?: string
  height?: number
  fill?: boolean
}) {
  const gid = useId().replace(/:/g, '')
  const path = useMemo(() => {
    const ys = values.map((v) => plotValue(v))
    if (ys.length < 2) return null
    const min = Math.min(...ys)
    const max = Math.max(...ys)
    const span = max - min || 1
    const pts = ys.map(
      (y, i) => [(i / (ys.length - 1)) * 100, 100 - ((y - min) / span) * 90 - 5] as const,
    )
    const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`).join(' ')
    return { line, area: `${line} L100,100 L0,100 Z` }
  }, [values])
  if (!path) return <div className={className} style={{ height }} />
  return (
    <svg
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      className={cn('w-full', className)}
      style={{ height }}
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.28} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>
      {fill && <path d={path.area} fill={`url(#${gid})`} />}
      <path
        d={path.line}
        fill="none"
        stroke={color}
        strokeWidth={1.6}
        vectorEffect="non-scaling-stroke"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** Horizontal share bars (breakdowns). Suppressed rows (<5 users, F-ANL-10) show a lock. */
export function ShareBars({
  rows,
  format,
  className,
}: {
  rows: Array<{
    key: string
    label: ReactNode
    value: DecimalString | null
    sharePct: DecimalString | null
    suppressed?: boolean
    sub?: ReactNode
  }>
  format: (v: DecimalString) => string
  className?: string
}) {
  const reduce = useReducedMotion()
  const max = Math.max(...rows.map((r) => plotValue(r.sharePct)), 1)
  return (
    <ul className={cn('space-y-3', className)}>
      {rows.map((r, i) => (
        <li key={r.key}>
          <div className="mb-1.5 flex items-baseline justify-between gap-3 text-sm">
            <span className="min-w-0 truncate">{r.label}</span>
            {r.suppressed ? (
              <span
                className="flex items-center gap-1 text-xs text-muted"
                title="Fewer than 5 people — hidden to protect privacy"
              >
                <Lock aria-hidden="true" className="size-3" /> hidden (&lt;5)
              </span>
            ) : (
              <span className="num shrink-0 text-xs text-muted">
                <span className="text-text">{r.value ? format(r.value) : '—'}</span>
                {r.sharePct && <span className="ml-2">{formatPercent(r.sharePct)}</span>}
              </span>
            )}
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-surface-2">
            {!r.suppressed && (
              <motion.div
                className="h-full rounded-full"
                style={{ background: CHART_COLORS[i % CHART_COLORS.length] }}
                initial={reduce ? false : { width: 0 }}
                animate={{ width: `${(plotValue(r.sharePct) / max) * 100}%` }}
                transition={{ duration: 0.7, ease: EASE_OUT_EXPO, delay: i * 0.04 }}
              />
            )}
          </div>
          {r.sub && <div className="mt-1 text-xs text-muted">{r.sub}</div>}
        </li>
      ))}
    </ul>
  )
}

/** Conversion funnel: tapered bars with step and overall conversion. */
export function Funnel({
  steps,
}: {
  steps: Array<{
    key: string
    label: string
    users: DecimalString
    conversionPct: DecimalString
    stepConversionPct: DecimalString
  }>
}) {
  const reduce = useReducedMotion()
  return (
    <ol className="space-y-2.5">
      {steps.map((s, i) => (
        <li
          key={s.key}
          className="grid grid-cols-[minmax(0,10rem)_1fr] items-center gap-3 sm:grid-cols-[12rem_1fr]"
        >
          <div className="min-w-0">
            <p className="truncate text-sm">{s.label}</p>
            {i > 0 && (
              <p className="text-xs text-muted">{formatPercent(s.stepConversionPct)} of previous</p>
            )}
          </div>
          <div className="flex items-center gap-3">
            <div className="relative h-9 flex-1 overflow-hidden rounded-[10px] bg-surface-2">
              <motion.div
                className="absolute inset-y-0 left-0 rounded-[10px]"
                style={{
                  background: `linear-gradient(90deg, var(--chart-${(i % 3) + 1}), color-mix(in oklab, var(--chart-${(i % 3) + 1}) 35%, transparent))`,
                }}
                initial={reduce ? false : { width: 0 }}
                animate={{ width: `${Math.max(plotValue(s.conversionPct), 2)}%` }}
                transition={{ duration: 0.8, ease: EASE_OUT_EXPO, delay: i * 0.06 }}
              />
            </div>
            <div className="w-20 shrink-0 text-right">
              <p className="num text-sm font-medium">{formatCount(s.users)}</p>
              <p className="num text-[0.6875rem] text-muted">{formatPercent(s.conversionPct)}</p>
            </div>
          </div>
        </li>
      ))}
    </ol>
  )
}

/** Retention heatmap (cohort × month). Cell opacity encodes the value; the number is always shown. */
export function CohortHeatmap({
  cohorts,
}: {
  cohorts: Array<{ cohort: string; size: DecimalString; retentionPct: Array<DecimalString | null> }>
}) {
  const cols = Math.max(...cohorts.map((c) => c.retentionPct.length), 1)
  const fmtMonth = (c: string) =>
    new Intl.DateTimeFormat('en-IN', { month: 'short', year: '2-digit', timeZone: 'UTC' }).format(
      new Date(`${c}-01T00:00:00Z`),
    )
  return (
    // Focusable so keyboard users can scroll it on narrow screens (WCAG 2.1.1).
    <div
      className="scroll-thin overflow-x-auto rounded-md focus-visible:outline-2 focus-visible:outline-accent"
      tabIndex={0}
      role="region"
      aria-label="Retention by signup month"
    >
      <table className="w-full border-separate border-spacing-1 text-xs">
        <caption className="sr-only">
          Share of each signup month still moving money N months later
        </caption>
        <thead>
          <tr className="text-muted">
            <th scope="col" className="px-2 py-1 text-left font-normal">
              Cohort
            </th>
            <th scope="col" className="px-2 py-1 text-right font-normal">
              Users
            </th>
            {Array.from({ length: cols }).map((_, i) => (
              <th key={i} scope="col" className="px-2 py-1 text-center font-normal">
                M{i}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {cohorts.map((c) => (
            <tr key={c.cohort}>
              <th scope="row" className="whitespace-nowrap px-2 py-1 text-left font-normal">
                {fmtMonth(c.cohort)}
              </th>
              <td className="num px-2 py-1 text-right text-muted">{formatCount(c.size)}</td>
              {Array.from({ length: cols }).map((_, i) => {
                const v = c.retentionPct[i]
                if (v === undefined) return <td key={i} />
                if (v === null)
                  return (
                    <td key={i} className="rounded-md bg-surface-2 text-center text-muted">
                      ·
                    </td>
                  )
                const a = plotValue(v) / 100
                return (
                  <td
                    key={i}
                    className="num min-w-12 rounded-md px-2 py-2 text-center"
                    style={{
                      background: `color-mix(in oklab, var(--chart-3) ${Math.round(a * 85)}%, var(--surface-2))`,
                      color: a > 0.55 ? 'var(--bg)' : 'var(--text)',
                    }}
                  >
                    {formatPercent(v, 0)}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
