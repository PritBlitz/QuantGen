import { useRef, type HTMLAttributes, type PointerEvent } from 'react'
import { cn } from '@/lib/utils'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('rounded-[var(--radius-card)] bg-surface hairline p-5 sm:p-6', className)}
      {...props}
    />
  )
}

/**
 * Card with a cursor-following radial highlight on its border (DESIGN.md §2 "Hover").
 * Pure CSS custom properties; no re-render on pointer move.
 */
export function SpotlightCard({ className, children, ...props }: HTMLAttributes<HTMLDivElement>) {
  const ref = useRef<HTMLDivElement>(null)

  function onPointerMove(e: PointerEvent<HTMLDivElement>) {
    const el = ref.current
    if (!el || e.pointerType !== 'mouse') return
    const rect = el.getBoundingClientRect()
    el.style.setProperty('--mx', `${e.clientX - rect.left}px`)
    el.style.setProperty('--my', `${e.clientY - rect.top}px`)
  }

  return (
    <div
      ref={ref}
      onPointerMove={onPointerMove}
      className={cn(
        'group/spot relative rounded-[var(--radius-card)] bg-surface p-5 sm:p-6',
        'hairline transition-colors duration-[var(--dur-ui)]',
        // Highlight ring drawn with a masked pseudo-element so only the 1px border glows.
        "before:pointer-events-none before:absolute before:inset-[-1px] before:rounded-[inherit] before:p-px before:opacity-0 before:content-['']",
        'before:transition-opacity before:duration-[var(--dur-ui)] hover:before:opacity-100',
        'before:[background:radial-gradient(240px_circle_at_var(--mx,50%)_var(--my,50%),color-mix(in_oklab,var(--accent)_70%,transparent),transparent_70%)]',
        'before:[mask:linear-gradient(#000_0_0)_content-box_exclude,linear-gradient(#000_0_0)]',
        className,
      )}
      {...props}
    >
      {children}
    </div>
  )
}
