import { cn } from '@/lib/utils'

/** Animated mesh gradient for the auth split screen. Decorative only; motion stops under
 *  prefers-reduced-motion via the global CSS rule. */
export function MeshGradient({ className }: { className?: string }) {
  return (
    <div aria-hidden="true" className={cn('absolute inset-0 overflow-hidden bg-bg', className)}>
      <div className="absolute -left-1/4 -top-1/4 size-[80%] animate-drift rounded-full bg-accent opacity-50 blur-[110px]" />
      <div className="absolute -bottom-1/4 -right-1/4 size-[75%] animate-drift-slow rounded-full bg-gain opacity-35 blur-[120px]" />
      <div className="absolute left-1/3 top-1/3 size-[45%] animate-drift-slow rounded-full bg-[#5b6cff] opacity-40 blur-[100px]" />
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_30%,var(--bg)_95%)]" />
    </div>
  )
}
