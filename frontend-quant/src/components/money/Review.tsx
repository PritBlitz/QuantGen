import type { ReactNode } from 'react'
import type { MoneyString } from '@/api/types'
import { formatINR, type Decimal } from '@/lib/money'
import { cn } from '@/lib/utils'

/** The big Instrument Serif amount on review screens (DESIGN.md §3.1). */
export function ReviewAmount({
  value,
  caption,
}: {
  value: MoneyString | Decimal
  caption: string
}) {
  return (
    <div className="text-center">
      <p className="text-xs uppercase tracking-[0.16em] text-muted">{caption}</p>
      <p className="mt-3 font-display text-[clamp(2.75rem,12vw,4.5rem)] leading-none tabular-nums">
        {formatINR(value)}
      </p>
    </div>
  )
}

export function ReviewRows({ children }: { children: ReactNode }) {
  return (
    <dl className="divide-y divide-border rounded-[var(--radius-control)] hairline">{children}</dl>
  )
}

export function ReviewRow({
  label,
  children,
  emphasis,
}: {
  label: string
  children: ReactNode
  emphasis?: boolean
}) {
  return (
    <div className="flex items-start justify-between gap-4 px-4 py-3 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className={cn('min-w-0 text-right', emphasis && 'font-medium')}>{children}</dd>
    </div>
  )
}
