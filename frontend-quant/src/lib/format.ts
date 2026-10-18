/**
 * Display formatting for analytics values (PRD §12.13: every metric value is a decimal string).
 * Money goes through lib/money.ts; counts and percentages are formatted from the string via
 * Decimal, never parsed with parseFloat.
 */
import type { DecimalString, MetricUnit } from '@/api/types'
import { Decimal, formatINR, toDecimal } from './money'

const countFmt = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 })
const countCompact = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })
const usdFmt = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** Integer-ish count with Indian grouping (1,24,500). */
export function formatCount(value: DecimalString | Decimal, compact = false): string {
  const d = toDecimal(value).toDecimalPlaces(0)
  return (compact ? countCompact : countFmt).format(d.toFixed(0) as `${number}`)
}

export function formatPercent(value: DecimalString | Decimal, digits = 1): string {
  const d = toDecimal(value).toDecimalPlaces(digits)
  return `${d.toFixed(digits)}%`
}

export function formatUsd(value: DecimalString | Decimal): string {
  return usdFmt.format(toDecimal(value).toDecimalPlaces(2).toFixed(2) as `${number}`)
}

/** Format any analytics value by its unit. `currency: 'USD'` for AI cost metrics. */
export function formatMetric(
  value: DecimalString | null | undefined,
  unit: MetricUnit,
  opts: { compact?: boolean; currency?: 'INR' | 'USD' } = {},
): string {
  if (value === null || value === undefined) return '—'
  if (unit === 'PERCENT') return formatPercent(value)
  if (unit === 'COUNT') return formatCount(value, opts.compact)
  if (opts.currency === 'USD') return formatUsd(value)
  return formatINR(value, { compact: opts.compact })
}

/** Signed change copy: "+12.4%" / "−3.1%" from a decimal string. */
export function formatChange(value: DecimalString | null): string {
  if (value === null) return 'new'
  const d = toDecimal(value).toDecimalPlaces(1)
  if (d.isZero()) return '0.0%'
  return `${d.isNegative() ? '−' : '+'}${d.abs().toFixed(1)}%`
}

export const isUsdMetric = (metric: string) => metric.endsWith('_USD')
