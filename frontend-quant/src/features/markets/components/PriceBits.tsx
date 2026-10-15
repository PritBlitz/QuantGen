import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import { useId } from 'react'
import type { MoneyString, PricePointDto } from '@/api/types'
import { formatINR, formatPct, signOf, toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { usePriceFlash } from '../hooks'

/** Price with a 600 ms gain/loss tint behind it when it ticks (DESIGN.md §2). */
export function PriceCell({ price, className }: { price: MoneyString; className?: string }) {
  const flash = usePriceFlash(price)
  return (
    <span
      className={cn(
        'num inline-block rounded px-1.5 py-0.5 transition-colors duration-[600ms]',
        flash === 'up' && 'bg-gain/20',
        flash === 'down' && 'bg-loss/20',
        className,
      )}
    >
      {formatINR(price)}
    </span>
  )
}

/** Percent change with sign + arrow (never colour alone). */
export function ChangePct({ value, className }: { value: string; className?: string }) {
  const sign = signOf(value)
  const Icon = sign === 'negative' ? ArrowDownRight : ArrowUpRight
  return (
    <span
      className={cn(
        'num inline-flex items-center gap-0.5 whitespace-nowrap',
        sign === 'positive' && 'text-gain',
        sign === 'negative' && 'text-loss',
        sign === 'zero' && 'text-muted',
        className,
      )}
    >
      {sign !== 'zero' && <Icon aria-hidden="true" className="size-[1em]" />}
      {formatPct(value)}
    </span>
  )
}

/**
 * Tiny SVG trend line. Prices are normalised with Decimal; only the resulting 0–1 plot
 * ratios become numbers (display geometry, not money arithmetic).
 */
export function Sparkline({
  points,
  className,
  width = 96,
  height = 28,
}: {
  points: PricePointDto[] | undefined
  className?: string
  width?: number
  height?: number
}) {
  const gid = useId()
  if (!points || points.length < 2) {
    return (
      <span
        aria-hidden="true"
        className={cn('inline-block', className)}
        style={{ width, height }}
      />
    )
  }
  const values = points.map((p) => toDecimal(p.price))
  const min = values.reduce((m, v) => (v.lt(m) ? v : m))
  const max = values.reduce((m, v) => (v.gt(m) ? v : m))
  const span = max.minus(min)
  const ys = values.map((v) => (span.isZero() ? 0.5 : v.minus(min).div(span).toNumber()))
  const step = width / (ys.length - 1)
  const d = ys
    .map(
      (y, i) =>
        `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(2)} ${(height - 2 - y * (height - 4)).toFixed(2)}`,
    )
    .join(' ')
  const first = values[0]
  const last = values[values.length - 1]
  const up = first && last ? last.gte(first) : true
  const color = up ? 'var(--gain)' : 'var(--loss)'
  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      aria-hidden="true"
      className={className}
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.25" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${d} L${width} ${height} L0 ${height} Z`} fill={`url(#${gid})`} />
      <path d={d} fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  )
}
