import { Scale } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import type { AdminCardDisputeDto, DisputeStatus } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { DISPUTE_REASON, DISPUTE_STATUS } from '@/features/cards/labels'
import { useAdminDisputes, useResolveDispute } from '@/features/cards/hooks'
import { formatRelative } from '@/lib/time'
import { usePageClamp } from '@/lib/usePageClamp'
import { cn } from '@/lib/utils'
import { ReasonDialog } from './components/ReasonDialog'

type Filter = DisputeStatus | 'ALL'
const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'OPEN', label: 'Open' },
  { value: 'REFUNDED', label: 'Refunded' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All' },
]

/** `/admin/disputes` (F-CARD-4): customers' card disputes. Refunding posts a reversing entry. */
export function AdminDisputesPage() {
  const [filter, setFilter] = useState<Filter>('OPEN')
  const [page, setPage] = useState(0)
  const list = useAdminDisputes(filter, page)
  const resolve = useResolveDispute()
  const [deciding, setDeciding] = useState<{ d: AdminCardDisputeDto; outcome: 'REFUNDED' | 'REJECTED' } | null>(null)
  usePageClamp(list.data, page, setPage)

  return (
    <div>
      <PageHeader
        title="Card disputes"
        description="Payments customers say were wrong. Refunding returns the money from card clearing; rejecting moves nothing. Both are audited and the customer is notified."
      />
      <Segmented<Filter>
        label="Status"
        value={filter}
        onValueChange={(v) => {
          setFilter(v)
          setPage(0)
        }}
        options={FILTERS}
        className="mb-4"
      />
      {list.isPending ? (
        <Skeleton className="h-64" />
      ) : list.isError ? (
        <Card>
          <PanelError what="card disputes" onRetry={() => void list.refetch()} />
        </Card>
      ) : list.data.content.length === 0 ? (
        <Card>
          <EmptyState icon={<Scale />} title={filter === 'OPEN' ? 'No open disputes' : 'No disputes here'} />
        </Card>
      ) : (
        <Card className="p-0 sm:p-0">
          <ul className={cn('divide-y divide-border', list.isPlaceholderData && 'opacity-60')}>
            {list.data.content.map((d) => {
              const s = DISPUTE_STATUS[d.status] ?? { label: d.status, tone: 'neutral' as const }
              return (
                <li key={d.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4">
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{d.merchant}</span>
                      <Badge tone={s.tone}>{s.label}</Badge>
                    </span>
                    <span className="block text-sm text-muted">
                      {DISPUTE_REASON[d.reason] ?? d.reason} · •••• {d.last4} · {formatRelative(d.createdAt)}
                    </span>
                    {/* The customer's note is untrusted text: rendered as plain text, never as HTML. */}
                    {d.note && <span className="mt-1 block text-sm">“{d.note}”</span>}
                    {d.resolutionReason && <span className="mt-1 block text-xs text-muted">Decision: {d.resolutionReason}</span>}
                  </span>
                  <Money value={d.amount} className="font-medium" />
                  {d.status === 'OPEN' && (
                    <span className="flex gap-2">
                      <Button size="sm" onClick={() => setDeciding({ d, outcome: 'REFUNDED' })} aria-label={`Refund ${d.merchant} dispute`}>
                        Refund
                      </Button>
                      <Button size="sm" variant="secondary" onClick={() => setDeciding({ d, outcome: 'REJECTED' })} aria-label={`Reject ${d.merchant} dispute`}>
                        Reject
                      </Button>
                    </span>
                  )}
                </li>
              )
            })}
          </ul>
          <div className="px-5 pb-4">
            <Pagination page={list.data.page} totalPages={list.data.totalPages} totalElements={list.data.totalElements} onPage={setPage} label="disputes" />
          </div>
        </Card>
      )}

      <ReasonDialog
        open={deciding !== null}
        onOpenChange={(o) => !o && setDeciding(null)}
        title={deciding?.outcome === 'REFUNDED' ? 'Refund this dispute?' : 'Reject this dispute?'}
        description={
          deciding?.outcome === 'REFUNDED'
            ? 'The amount goes back to the customer’s account now. This can’t be undone.'
            : 'No money moves. The customer is told the dispute wasn’t upheld.'
        }
        summary={
          deciding && (
            <p className="text-sm">
              <Money value={deciding.d.amount} className="font-medium" /> at {deciding.d.merchant} · •••• {deciding.d.last4}
            </p>
          )
        }
        reasonRequired
        maxLength={280}
        placeholder={deciding?.outcome === 'REFUNDED' ? 'e.g. Merchant confirmed the order was never shipped.' : 'e.g. Delivery proof matches the customer’s address.'}
        confirmLabel={deciding?.outcome === 'REFUNDED' ? 'Refund' : 'Reject'}
        destructive={deciding?.outcome === 'REJECTED'}
        onConfirm={async (reason) => {
          if (!deciding) return
          await resolve.mutateAsync({ id: deciding.d.id, outcome: deciding.outcome, reason })
          toast.success(deciding.outcome === 'REFUNDED' ? 'Refunded. The customer has the money back.' : 'Rejected. No money moved.')
          setDeciding(null)
        }}
      />
    </div>
  )
}
