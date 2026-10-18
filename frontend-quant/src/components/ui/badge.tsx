import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

const TONES = {
  neutral: 'bg-surface-2 text-muted',
  gain: 'bg-gain/12 text-gain',
  loss: 'bg-loss/12 text-loss',
  warn: 'bg-warn/12 text-warn',
  accent: 'bg-accent/15 text-text',
  violet: 'bg-violet/14 text-violet',
  cyan: 'bg-cyan/12 text-cyan',
} as const

export type BadgeTone = keyof typeof TONES

export function Badge({
  tone = 'neutral',
  children,
  className,
  dot,
}: {
  tone?: BadgeTone
  children: ReactNode
  className?: string
  /** A leading status dot in the tone's colour. */
  dot?: boolean
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[0.6875rem] font-medium whitespace-nowrap',
        TONES[tone],
        className,
      )}
    >
      {dot && <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />}
      {children}
    </span>
  )
}
