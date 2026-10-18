import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react'
import type { ReactNode } from 'react'
import type { DecimalString, KpiDto } from '@/api/types'
import { SpotlightCard } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatChange, formatMetric } from '@/lib/format'
import { toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { Sparkline } from './charts/Bits'

/**
 * Change pill: arrow + signed % (never colour alone). Green when the change is *good* for this
 * metric (`goodDirection`): more users is good, more overdue is bad.
 */
export function ChangePill({
  changePct,
  goodDirection = 'UP',
  className,
}: {
  changePct: DecimalString | null
  goodDirection?: 'UP' | 'DOWN'
  className?: string
}) {
  if (changePct === null)
    return <span className={cn('rounded-full bg-surface-2 px-1.5 py-0.5 text-[0.6875rem] text-muted', className)}>new</span>
  const d = toDecimal(changePct)
  const up = d.gt(0)
  const flat = d.toDecimalPlaces(1).isZero()
  const good = flat ? null : up === (goodDirection === 'UP')
  const Icon = flat ? Minus : up ? ArrowUpRight : ArrowDownRight
  return (
    <span
      className={cn(
        'num inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[0.6875rem] font-medium',
        good === null ? 'bg-surface-2 text-muted' : good ? 'bg-gain/12 text-gain' : 'bg-loss/12 text-loss',
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-3" />
      {formatChange(changePct)}
    </span>
  )
}

export function KpiTile({
  kpi,
  spark,
  icon,
  currency,
  tone = 'accent',
  className,
  footer,
}: {
  kpi: Pick<KpiDto, 'label' | 'unit' | 'value' | 'changePct' | 'goodDirection'> & { previous?: DecimalString }
  spark?: Array<DecimalString | null>
  icon?: ReactNode
  currency?: 'INR' | 'USD'
  tone?: 'accent' | 'violet' | 'cyan'
  className?: string
  footer?: ReactNode
}) {
  return (
    <SpotlightCard tone={tone} className={cn('flex flex-col gap-3 p-5 sm:p-5', className)}>
      <div className="flex items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-[0.8125rem] text-muted [&_svg]:size-3.5">
          {icon}
          {kpi.label}
        </p>
        <ChangePill changePct={kpi.changePct} goodDirection={kpi.goodDirection} />
      </div>
      <p className="num text-[1.625rem] font-medium leading-none tracking-tight">
        {formatMetric(kpi.value, kpi.unit, { compact: kpi.unit === 'MONEY', currency })}
      </p>
      {spark && spark.length > 1 ? (
        <Sparkline values={spark} color={`var(--${tone === 'accent' ? 'chart-1' : tone === 'violet' ? 'chart-2' : 'chart-3'})`} height={34} />
      ) : null}
      {kpi.previous !== undefined && (
        <p className="text-xs text-muted">
          vs <span className="num">{formatMetric(kpi.previous, kpi.unit, { compact: true, currency })}</span> before
        </p>
      )}
      {footer}
    </SpotlightCard>
  )
}

export function KpiTileSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn('rounded-[var(--radius-card)] bg-surface p-5 hairline', className)}>
      <Skeleton className="h-4 w-24" />
      <Skeleton className="mt-4 h-7 w-32" />
      <Skeleton className="mt-4 h-8 w-full" />
    </div>
  )
}
