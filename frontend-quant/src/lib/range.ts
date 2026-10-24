import type { DateRangeQuery } from '@/api/types'

export type RangePreset = '7D' | '30D' | '90D' | '12M'

const DAYS: Record<RangePreset, number> = { '7D': 7, '30D': 30, '90D': 90, '12M': 365 }

/** Today in IST as yyyy-MM-dd (analytics days are IST calendar days, PRD §11.4.14). */
export function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())
}

function minusDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() - days)
  return d.toISOString().slice(0, 10)
}

/** Inclusive IST range for a preset, e.g. 30D → the last 30 days including today. */
export function rangeFor(preset: RangePreset): Required<DateRangeQuery> {
  const to = todayIst()
  return { from: minusDays(to, DAYS[preset] - 1), to }
}

export const granularityFor = (preset: RangePreset) =>
  preset === '12M' ? ('MONTH' as const) : preset === '90D' ? ('WEEK' as const) : ('DAY' as const)
