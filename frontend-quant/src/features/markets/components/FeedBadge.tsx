import { cn } from '@/lib/utils'
import { useFeedStatus } from '../livePrices'

const COPY = {
  idle: 'Offline',
  connecting: 'Connecting…',
  live: 'Live',
  polling: 'Updating every 3 s',
} as const

/** Shows whether prices are streaming (STOMP) or polled. */
export function FeedBadge({ className }: { className?: string }) {
  const status = useFeedStatus((s) => s.status)
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs text-muted hairline',
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'size-1.5 rounded-full',
          status === 'live' ? 'animate-pulse bg-gain' : status === 'idle' ? 'bg-muted' : 'bg-warn',
        )}
      />
      {COPY[status]}
    </span>
  )
}
