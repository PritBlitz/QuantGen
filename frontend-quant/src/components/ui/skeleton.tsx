import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/** Shimmer-sweep skeleton (DESIGN.md §2): used wherever the layout is known. */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        'relative overflow-hidden rounded-md bg-surface-2',
        'after:absolute after:inset-0 after:animate-shimmer after:content-[""]',
        'after:bg-[linear-gradient(90deg,transparent,color-mix(in_oklab,var(--text)_6%,transparent),transparent)]',
        className,
      )}
      {...props}
    />
  )
}
