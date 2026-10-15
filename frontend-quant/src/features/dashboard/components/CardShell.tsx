import { AlertCircle, RotateCw } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { SpotlightCard } from '@/components/ui/card'
import { cn } from '@/lib/utils'

export function DashCard({
  title,
  action,
  className,
  children,
  labelledBy,
}: {
  title: string
  action?: ReactNode
  className?: string
  children: ReactNode
  labelledBy: string
}) {
  return (
    <SpotlightCard className={cn('flex flex-col', className)}>
      <section aria-labelledby={labelledBy} className="flex flex-1 flex-col">
        <div className="flex items-center justify-between gap-3">
          <h2 id={labelledBy} className="text-sm font-medium text-muted">
            {title}
          </h2>
          {action}
        </div>
        <div className="mt-4 flex flex-1 flex-col">{children}</div>
      </section>
    </SpotlightCard>
  )
}

export function CardError({ onRetry, what }: { onRetry: () => void; what: string }) {
  return (
    <div className="flex flex-1 flex-col items-start justify-center gap-3 text-sm" role="alert">
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
