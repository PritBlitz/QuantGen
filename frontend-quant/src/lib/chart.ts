import type { DecimalString } from '@/api/types'
import { toDecimal } from './money'

/**
 * Convert an API decimal string to a plot coordinate. **Geometry only**: charts need numbers to
 * place points, but every label and tooltip formats the original string (kept alongside as
 * `raw`), so no amount shown to the user ever passes through a float.
 */
export function plotValue(value: DecimalString | null | undefined): number {
  if (value === null || value === undefined) return 0
  return toDecimal(value).toNumber()
}

/** Short axis label for an ISO date: "18 Oct" (day/week) or "Oct ’26" (month). */
export function axisDate(t: string, month = false): string {
  const d = new Date(`${t.length === 7 ? `${t}-01` : t.slice(0, 10)}T00:00:00Z`)
  return month
    ? new Intl.DateTimeFormat('en-IN', { month: 'short', year: '2-digit', timeZone: 'UTC' })
        .format(d)
        .replace(' ', ' ’')
    : new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(
        d,
      )
}

/** Chart palette from CSS tokens (DESIGN.md v2 §1). */
export const CHART_COLORS = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'var(--chart-6)',
] as const
