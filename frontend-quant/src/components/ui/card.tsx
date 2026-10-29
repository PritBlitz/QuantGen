import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

const BASE = 'rounded-[var(--radius-card)] bg-surface hairline p-5 shadow-[var(--shadow-card)] sm:p-6'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn(BASE, className)} {...props} />
}

/**
 * A card that reacts to hover with a stronger border (DESIGN.md v3 §2). v2 had a cursor-following
 * glow; `tone` is still accepted so call sites keep their meaning, but it no longer draws anything.
 */
export function SpotlightCard({
  className,
  children,
  tone: _tone = 'accent',
  ...props
}: HTMLAttributes<HTMLDivElement> & { tone?: 'accent' | 'violet' | 'cyan' }) {
  return (
    <div
      className={cn(
        'relative overflow-hidden',
        BASE,
        'transition-colors duration-[var(--dur-ui)] hover:border-border-strong',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  )
}
