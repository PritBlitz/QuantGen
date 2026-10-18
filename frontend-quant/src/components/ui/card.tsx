import { useRef, type HTMLAttributes, type PointerEvent } from 'react'
import { cn } from '@/lib/utils'

const BASE =
  'edge-light rounded-[var(--radius-card)] bg-surface hairline p-5 shadow-[var(--shadow-card)] sm:p-6'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn(BASE, className)} {...props} />
}

/**
 * Card with a cursor-following light: a radial glow on the 1px border plus a faint wash inside
 * (DESIGN.md §2 "Hover"). Pure CSS custom properties; no re-render on pointer move. `tone`
 * picks the glow colour: accent (default), violet (AI features) or cyan (analytics).
 */
export function SpotlightCard({
  className,
  children,
  tone = 'accent',
  ...props
}: HTMLAttributes<HTMLDivElement> & { tone?: 'accent' | 'violet' | 'cyan' }) {
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
      style={{ ['--glow' as string]: `var(--${tone === 'accent' ? 'accent' : tone})` }}
      className={cn(
        'group/spot relative isolate overflow-hidden',
        BASE,
        'transition-[border-color,transform] duration-[var(--dur-ui)]',
        // Border glow: a masked pseudo-element so only the 1px ring lights up.
        "after:pointer-events-none after:absolute after:inset-0 after:rounded-[inherit] after:p-px after:opacity-0 after:content-['']",
        'after:transition-opacity after:duration-[var(--dur-ui)] hover:after:opacity-100',
        'after:[background:radial-gradient(260px_circle_at_var(--mx,50%)_var(--my,50%),color-mix(in_oklab,var(--glow)_75%,transparent),transparent_70%)]',
        'after:[mask:linear-gradient(#000_0_0)_content-box_exclude,linear-gradient(#000_0_0)]',
        className,
      )}
      {...props}
    >
      {/* Inner wash, under the content. */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 opacity-0 transition-opacity duration-[var(--dur-ui)] group-hover/spot:opacity-100 [background:radial-gradient(420px_circle_at_var(--mx,50%)_var(--my,50%),color-mix(in_oklab,var(--glow)_7%,transparent),transparent_65%)]"
      />
      {children}
    </div>
  )
}
