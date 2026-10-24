import type { ReactNode } from 'react'
import { useId } from 'react'
import { AlertCircle, RotateCw } from 'lucide-react'
import { SpotlightCard } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * A titled section card (DESIGN.md v2 §3): eyebrow + title on the left, an action on the right,
 * then content. The heading labels the region for screen readers.
 */
export function Panel({
  title,
  eyebrow,
  description,
  action,
  children,
  className,
  bodyClassName,
  tone,
}: {
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
  tone?: 'accent' | 'violet' | 'cyan'
}) {
  const id = useId()
  return (
    <SpotlightCard tone={tone} className={cn('flex flex-col', className)}>
      <section aria-labelledby={id} className="flex flex-1 flex-col">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            {eyebrow && <p className="eyebrow mb-1.5">{eyebrow}</p>}
            <h2 id={id} className="text-[0.9375rem] font-medium tracking-tight">
              {title}
            </h2>
            {description && <p className="mt-1 text-sm text-muted">{description}</p>}
          </div>
          {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
        </div>
        <div className={cn('mt-5 flex flex-1 flex-col', bodyClassName)}>{children}</div>
      </section>
    </SpotlightCard>
  )
}

export function PanelError({ what, onRetry }: { what: string; onRetry: () => void }) {
  return (
    <div
      className="flex flex-1 flex-col items-start justify-center gap-3 py-6 text-sm"
      role="alert"
    >
      <p className="flex items-center gap-2 text-muted">
        <AlertCircle aria-hidden="true" className="size-4 text-warn" />
        Couldn’t load {what}.
      </p>
      <Button variant="secondary" size="sm" onClick={onRetry}>
        <RotateCw aria-hidden="true" /> Try again
      </Button>
    </div>
  )
}
