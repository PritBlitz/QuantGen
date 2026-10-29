import { cn } from '@/lib/utils'

/**
 * The page background (DESIGN.md v3 §1). v2 drew drifting colour fields and grain here; v3 is a
 * plain, solid page colour. The component and its `intensity` prop stay so layouts keep working.
 */
export function Aurora({
  className,
}: {
  className?: string
  intensity?: 'app' | 'hero'
}) {
  return (
    <div
      aria-hidden="true"
      className={cn('pointer-events-none fixed inset-0 -z-10 bg-bg', className)}
    />
  )
}
