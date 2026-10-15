const absoluteFmt = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

const dateFmt = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto', style: 'short' })

/** `16 Oct 2026, 14:05` in the viewer's local time zone. */
export function formatAbsolute(iso: string): string {
  const parts = absoluteFmt.formatToParts(new Date(iso))
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value
  return `${get('day')} ${get('month')} ${get('year')}, ${get('hour')}:${get('minute')}`
}

/** `16 Oct 2026` for dates without a time (e.g. EMI due dates, yyyy-mm-dd). */
export function formatDate(isoDate: string): string {
  // Parse yyyy-mm-dd as a local calendar date so it never shifts a day across time zones.
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate)
  const d = m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : new Date(isoDate)
  return dateFmt.format(d)
}

/** "just now", "2 min ago", "5 hr ago" within a day; absolute after that (DESIGN.md §1). */
export function formatRelative(iso: string, now: number = Date.now()): string {
  const diffSec = Math.round((new Date(iso).getTime() - now) / 1000)
  const abs = Math.abs(diffSec)
  if (abs < 45) return 'just now'
  if (abs < 3600) return rtf.format(Math.round(diffSec / 60), 'minute')
  if (abs < 86_400) return rtf.format(Math.round(diffSec / 3600), 'hour')
  return formatAbsolute(iso)
}

const istDateFmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' })

/** Today's date in IST as yyyy-MM-dd (the backend's date filters are IST calendar days). */
export function todayIST(now: Date = new Date()): string {
  return istDateFmt.format(now)
}

/** yyyy-MM-dd in IST, `days` before today. */
export function daysAgoIST(days: number, now: Date = new Date()): string {
  return istDateFmt.format(new Date(now.getTime() - days * 86_400_000))
}

/** Whole days between two yyyy-MM-dd dates (b − a). */
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000)
}
