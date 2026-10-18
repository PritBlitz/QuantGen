import { BellOff, CheckCheck, Loader2 } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useNotifications, useUnreadCount } from '@/api/queries'
import type { NotificationDto } from '@/api/types'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Segmented } from '@/components/Segmented'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuthStore } from '@/features/auth/store'
import { CardError } from '@/features/dashboard/components/CardShell'
import { EASE_OUT_EXPO, staggerDelay } from '@/lib/motion'
import { formatRelative, todayIST } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useMarkAllRead, useMarkRead } from './hooks'
import { metaFor, TONE_CLASS } from './meta'

type Filter = 'all' | 'unread'

const istDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' })

/** F-NOT-1: newest first, grouped Today / Earlier, with mark-read and mark-all-read. */
export function NotificationsPage() {
  const [filter, setFilter] = useState<Filter>('all')
  const list = useNotifications(filter === 'unread')
  const unread = useUnreadCount()
  const markAll = useMarkAllRead()
  const unreadCount = unread.data?.count ?? 0

  const today = todayIST()
  const groups = list.data
    ? [
        {
          label: 'Today',
          items: list.data.filter((n) => istDay.format(new Date(n.createdAt)) === today),
        },
        {
          label: 'Earlier',
          items: list.data.filter((n) => istDay.format(new Date(n.createdAt)) !== today),
        },
      ].filter((g) => g.items.length > 0)
    : []

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Notifications"
        description={
          unreadCount > 0 ? (
            <>
              <span className="num text-text">{unreadCount}</span> unread
            </>
          ) : (
            'You’re all caught up.'
          )
        }
        actions={
          <Button
            variant="secondary"
            onClick={() => markAll.mutate()}
            disabled={unreadCount === 0 || markAll.isPending}
          >
            {markAll.isPending ? (
              <Loader2 className="animate-spin" aria-hidden="true" />
            ) : (
              <CheckCheck aria-hidden="true" />
            )}
            Mark all as read
          </Button>
        }
      />

      <Segmented<Filter>
        label="Show"
        value={filter}
        onValueChange={setFilter}
        options={[
          { value: 'all', label: 'All' },
          {
            value: 'unread',
            label: (
              <>Unread{unreadCount > 0 && <span className="num ml-1">({unreadCount})</span>}</>
            ),
          },
        ]}
        className="mb-4"
      />

      <Card className="p-2 sm:p-2">
        {list.isPending ? (
          <div className="space-y-2 p-3">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-16" />
            ))}
          </div>
        ) : list.isError ? (
          <div className="p-4">
            <CardError what="notifications" onRetry={() => void list.refetch()} />
          </div>
        ) : groups.length === 0 ? (
          <EmptyState
            icon={<BellOff />}
            title={filter === 'unread' ? 'No unread notifications' : 'Nothing yet'}
          >
            Money received, loan decisions, EMI reminders and account alerts show up here.
          </EmptyState>
        ) : (
          <div className={cn(list.isPlaceholderData && 'opacity-60')}>
            {groups.map((g) => (
              <section key={g.label} aria-labelledby={`notif-${g.label}`}>
                <h2
                  id={`notif-${g.label}`}
                  className="px-3 pb-1 pt-3 text-xs font-medium uppercase tracking-wider text-muted"
                >
                  {g.label}
                </h2>
                <ul>
                  {g.items.map((n, i) => (
                    <NotificationRow key={n.id} n={n} index={i} />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

function NotificationRow({ n, index }: { n: NotificationDto; index: number }) {
  const reduce = useReducedMotion()
  const navigate = useNavigate()
  const markRead = useMarkRead()
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const meta = metaFor(n.type)
  const Icon = meta.icon

  function open() {
    if (!n.read) markRead.mutate(n.id)
    if (!isAdmin) void navigate(meta.to)
  }

  return (
    <motion.li
      initial={reduce ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.32, ease: EASE_OUT_EXPO, delay: staggerDelay(index) }}
      className="group relative flex items-start gap-3 rounded-[var(--radius-control)] px-3 py-3 transition-colors hover:bg-surface-2/60"
    >
      <span
        className={cn(
          'mt-0.5 grid size-9 shrink-0 place-items-center rounded-full',
          TONE_CLASS[meta.tone],
        )}
      >
        <Icon aria-hidden="true" className="size-4" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-2">
          {/* The whole row is the link target; the button stays the single tab stop. */}
          <button
            type="button"
            onClick={open}
            className={cn(
              'rounded-sm text-left text-sm after:absolute after:inset-0 after:content-[""]',
              n.read ? 'text-text' : 'font-semibold text-text',
            )}
          >
            {n.title}
            {!n.read && (
              <>
                {' '}
                <span className="sr-only">(unread)</span>
              </>
            )}
          </button>
          {!n.read && (
            <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-accent" />
          )}
        </p>
        <p className="mt-0.5 text-sm text-muted">{n.body}</p>
        <p className="mt-1 text-xs text-muted">
          {meta.label} · <time dateTime={n.createdAt}>{formatRelative(n.createdAt)}</time>
        </p>
      </div>
      {!n.read && (
        <Button
          variant="ghost"
          size="sm"
          className="relative z-10 shrink-0 text-xs text-muted opacity-100 sm:opacity-0 sm:focus-visible:opacity-100 sm:group-hover:opacity-100"
          onClick={() => markRead.mutate(n.id)}
          disabled={markRead.isPending}
          aria-label={`Mark read: ${n.title}`}
        >
          Mark read
        </Button>
      )}
    </motion.li>
  )
}
