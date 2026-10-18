import { useMemo, useState, type ReactNode } from 'react'
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts'
import { CHART_COLORS, plotValue } from '@/lib/chart'
import { cn } from '@/lib/utils'

export interface DonutSlice {
  key: string
  label: string
  /** Decimal string (display truth). */
  value: string
  color?: string
}

/**
 * Allocation donut with a centre readout that follows hover/focus. Legend is a real list (screen
 * readers get the values; colour is never the only cue because each row carries its label).
 */
export function Donut({
  slices,
  center,
  formatValue,
  size = 200,
  className,
  ariaLabel,
}: {
  slices: DonutSlice[]
  center: (active: DonutSlice | null) => ReactNode
  formatValue: (s: DonutSlice) => string
  size?: number
  className?: string
  ariaLabel: string
}) {
  const [active, setActive] = useState<number | null>(null)
  const data = useMemo(
    () =>
      slices.map((s, i) => ({
        ...s,
        plot: Math.max(plotValue(s.value), 0),
        fill: s.color ?? CHART_COLORS[i % CHART_COLORS.length],
      })),
    [slices],
  )
  const current = active === null ? null : (slices[active] ?? null)

  return (
    <div className={cn('flex flex-col items-center gap-5 sm:flex-row', className)}>
      <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={ariaLabel}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              dataKey="plot"
              nameKey="label"
              innerRadius="72%"
              outerRadius="100%"
              paddingAngle={2}
              cornerRadius={6}
              stroke="none"
              isAnimationActive={false}
              onMouseLeave={() => setActive(null)}
            >
              {data.map((d, i) => (
                <Cell
                  key={d.key}
                  fill={d.fill}
                  opacity={active === null || active === i ? 1 : 0.35}
                  onMouseEnter={() => setActive(i)}
                />
              ))}
            </Pie>
          </PieChart>
        </ResponsiveContainer>
        <div className="pointer-events-none absolute inset-0 grid place-items-center text-center">
          <div>{center(current)}</div>
        </div>
      </div>
      <ul className="grid w-full min-w-0 gap-1.5">
        {data.map((d, i) => (
          <li key={d.key}>
            <button
              type="button"
              onMouseEnter={() => setActive(i)}
              onMouseLeave={() => setActive(null)}
              onFocus={() => setActive(i)}
              onBlur={() => setActive(null)}
              className={cn(
                'flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left text-sm transition-colors',
                active === i ? 'bg-surface-2' : 'hover:bg-surface-2/60',
              )}
            >
              <span className="size-2.5 shrink-0 rounded-full" style={{ background: d.fill }} />
              <span className="min-w-0 flex-1 truncate">{d.label}</span>
              <span className="num text-xs text-muted">{formatValue(d)}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
