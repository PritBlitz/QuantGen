import { cn } from '@/lib/utils'

/** Background of the auth split screen's right panel (DESIGN.md v3 §4): a flat tint with a faint
 *  dot grid. v2 drew drifting blurred colour fields here; the name stays for its call sites. */
export function MeshGradient({ className }: { className?: string }) {
  return (
    <div aria-hidden="true" className={cn('absolute inset-0 bg-surface-2', className)}>
      <div className="dot-grid absolute inset-0 opacity-60 [mask-image:linear-gradient(to_bottom,black,transparent_85%)]" />
    </div>
  )
}
