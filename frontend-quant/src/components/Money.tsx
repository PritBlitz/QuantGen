import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import type { MoneyString } from '@/api/types'
import { formatINR, signOf, type Decimal } from '@/lib/money'
import { cn } from '@/lib/utils'

interface MoneyProps {
  value: MoneyString | Decimal
  /**
   * `plain`: neutral colour. `pnl`: gain/loss colour **plus** sign and arrow icon, so it's
   * never conveyed by colour alone (DESIGN.md §1). `credit`/`debit`: ledger direction.
   */
  tone?: 'plain' | 'pnl' | 'credit' | 'debit'
  compact?: boolean
  className?: string
}

/** The only way to render an amount. Always mono + tabular figures. */
export function Money({ value, tone = 'plain', compact, className }: MoneyProps) {
  if (tone === 'credit' || tone === 'debit') {
    const isCredit = tone === 'credit'
    const body = formatINR(value, { compact })
    return (
      <span
        className={cn('num whitespace-nowrap', isCredit ? 'text-gain' : 'text-text', className)}
      >
        {isCredit ? '+' : '−'}
        {body}
      </span>
    )
  }

  if (tone === 'pnl') {
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
        {sign !== 'zero' && <Icon aria-hidden="true" className="size-[1em] shrink-0" />}
        {formatINR(value, { signed: true, compact })}
      </span>
    )
  }

  return (
    <span className={cn('num whitespace-nowrap', className)}>{formatINR(value, { compact })}</span>
  )
}
