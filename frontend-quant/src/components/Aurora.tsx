import { cn } from '@/lib/utils'

/**
 * The ambient "Signal" background (DESIGN.md v2 §1): three large, heavily blurred colour fields
 * drifting slowly behind everything, plus a grain layer. Fixed, non-interactive, `aria-hidden`.
 * Under prefers-reduced-motion the global CSS rule freezes the drift (static gradients remain).
 */
export function Aurora({ className, intensity = 'app' }: { className?: string; intensity?: 'app' | 'hero' }) {
  const hero = intensity === 'hero'
  return (
    <div
      aria-hidden="true"
      className={cn('pointer-events-none fixed inset-0 -z-10 overflow-hidden bg-bg', className)}
    >
      <div
        className={cn(
          'absolute -left-[10%] -top-[25%] aspect-square rounded-full blur-[110px] animate-drift',
          hero ? 'w-[70vw]' : 'w-[55vw]',
        )}
        style={{ background: 'radial-gradient(circle, var(--aurora-2), transparent 65%)' }}
      />
      <div
        className={cn(
          'absolute -right-[15%] top-[10%] aspect-square rounded-full blur-[120px] animate-drift-slow',
          hero ? 'w-[60vw]' : 'w-[45vw]',
        )}
        style={{ background: 'radial-gradient(circle, var(--aurora-1), transparent 65%)' }}
      />
      <div
        className="absolute -bottom-[30%] left-[25%] aspect-square w-[50vw] rounded-full blur-[130px] animate-drift"
        style={{ background: 'radial-gradient(circle, var(--aurora-3), transparent 65%)' }}
      />
      {/* Vignette keeps text areas calm. */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,transparent_40%,var(--bg)_100%)] opacity-70" />
      <div className="grain absolute inset-0" />
    </div>
  )
}
