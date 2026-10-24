import { Loader2, ReceiptText, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { useMutation } from '@tanstack/react-query'
import { tradeApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useInvalidateMoney, useOrders } from '@/api/queries'
import type { OrderDto, OrderStatusV2 } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatAbsolute } from '@/lib/time'

const STATUS: Record<OrderStatusV2, { label: string; tone: BadgeTone }> = {
  FILLED: { label: 'Filled', tone: 'neutral' },
  REJECTED: { label: 'Rejected', tone: 'loss' },
  OPEN: { label: 'Open', tone: 'accent' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
}

/** "Limit ₹1,250.00" / "Stop ₹1,180.00" / nothing for market orders. */
function Condition({ o }: { o: OrderDto }) {
  if (o.orderType === 'LIMIT' && o.limitPrice)
    return (
      <span className="text-xs text-muted">
        Limit <Money value={o.limitPrice} />
      </span>
    )
  if (o.orderType === 'STOP_LOSS' && o.triggerPrice)
    return (
      <span className="text-xs text-muted">
        Stop <Money value={o.triggerPrice} />
      </span>
    )
  return null
}

function StatusBadge({ status }: { status: OrderStatusV2 }) {
  const s = STATUS[status] ?? { label: status, tone: 'neutral' as const }
  return <Badge tone={s.tone}>{s.label}</Badge>
}

export function OrdersPage() {
  const [page, setPage] = useState(0)
  const [cancelling, setCancelling] = useState<OrderDto | null>(null)
  const { data, isPending, isError, refetch, isPlaceholderData } = useOrders({ page, size: 20 })

  const cancelButton = (o: OrderDto) =>
    o.status === 'OPEN' ? (
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setCancelling(o)}
        aria-label={`Cancel ${o.side === 'BUY' ? 'buy' : 'sell'} order for ${o.quantity} ${o.symbol}`}
      >
        <X aria-hidden="true" /> Cancel
      </Button>
    ) : null

  return (
    <div>
      <PageHeader
        title="Orders"
        back={{ to: '/portfolio', label: 'Portfolio' }}
        description="Every order you’ve placed. Open limit and stop-loss orders can be cancelled until they fill."
      />
      <Card>
        {isPending ? (
          <div className="space-y-2">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-12" />
            ))}
          </div>
        ) : isError ? (
          <CardError what="orders" onRetry={() => void refetch()} />
        ) : data.content.length === 0 ? (
          <EmptyState
            icon={<ReceiptText />}
            title="No orders yet"
            action={
              <Button asChild>
                <Link to="/markets">Browse markets</Link>
              </Button>
            }
          />
        ) : (
          <div className={isPlaceholderData ? 'opacity-60' : undefined}>
            <table className="hidden w-full text-sm md:table">
              <caption className="sr-only">Orders</caption>
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted">
                  <th scope="col" className="py-2.5 pr-3 font-normal">
                    When
                  </th>
                  <th scope="col" className="py-2.5 pr-3 font-normal">
                    Order
                  </th>
                  <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                    Price
                  </th>
                  <th scope="col" className="py-2.5 pr-3 text-right font-normal">
                    Total
                  </th>
                  <th scope="col" className="py-2.5 text-right font-normal">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((o) => (
                  <tr key={o.id} className="border-b border-border last:border-0">
                    <td className="py-3 pr-3 text-muted">{formatAbsolute(o.createdAt)}</td>
                    <td className="py-3 pr-3">
                      <Badge tone={o.side === 'BUY' ? 'gain' : 'loss'}>
                        {o.side === 'BUY' ? 'Buy' : 'Sell'}
                      </Badge>{' '}
                      <span className="num">{o.quantity}</span> ×{' '}
                      <Link to={`/markets/${o.symbol}`} className="font-semibold hover:underline">
                        {o.symbol}
                      </Link>{' '}
                      <Condition o={o} />
                    </td>
                    <td className="py-3 pr-3 text-right">
                      {o.status === 'FILLED' ? (
                        <Money value={o.price} />
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="py-3 pr-3 text-right">
                      <Money value={o.total} />
                    </td>
                    <td className="py-3 text-right">
                      <span className="inline-flex items-center gap-2">
                        {cancelButton(o)}
                        <StatusBadge status={o.status} />
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <ul className="divide-y divide-border md:hidden">
              {data.content.map((o) => (
                <li key={o.id} className="flex items-start justify-between gap-3 py-3">
                  <div>
                    <p className="text-sm">
                      <Badge tone={o.side === 'BUY' ? 'gain' : 'loss'}>
                        {o.side === 'BUY' ? 'Buy' : 'Sell'}
                      </Badge>{' '}
                      <span className="num">{o.quantity}</span> ×{' '}
                      <span className="font-semibold">{o.symbol}</span>
                    </p>
                    <p className="mt-1 text-xs text-muted">
                      {formatAbsolute(o.createdAt)} <Condition o={o} />
                    </p>
                    {o.status === 'OPEN' && <div className="-ml-3 mt-1">{cancelButton(o)}</div>}
                  </div>
                  <div className="text-right text-sm">
                    <Money value={o.total} />
                    <p className="mt-1">
                      <StatusBadge status={o.status} />
                    </p>
                  </div>
                </li>
              ))}
            </ul>
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              totalElements={data.totalElements}
              onPage={setPage}
              label="orders"
            />
          </div>
        )}
      </Card>
      {cancelling && <CancelOrderDialog order={cancelling} onClose={() => setCancelling(null)} />}
    </div>
  )
}

function CancelOrderDialog({ order, onClose }: { order: OrderDto; onClose: () => void }) {
  const invalidate = useInvalidateMoney()
  const cancel = useMutation({
    mutationFn: () => tradeApi.cancelOrder(order.id),
    // Filled or cancelled elsewhere in the meantime: refresh either way.
    onSettled: () => invalidate('orders', 'accounts', 'portfolio'),
    onSuccess: () => {
      toast.success(
        order.side === 'BUY'
          ? 'Order cancelled. The held money is available again.'
          : 'Order cancelled. Your shares are free to sell.',
      )
      onClose()
    },
  })
  const err = cancel.error ? toApiError(cancel.error) : null
  return (
    <Dialog open onOpenChange={(o) => !o && !cancel.isPending && onClose()}>
      <DialogContent
        title="Cancel this order?"
        description={`${order.side === 'BUY' ? 'Buy' : 'Sell'} ${order.quantity} × ${order.symbol}. If it fills before we receive this, the fill stands.`}
      >
        {err && (
          <p role="alert" className="mb-3 text-sm text-loss">
            {err.code === 'INVALID_STATE'
              ? 'This order has already filled, expired or been cancelled. The list has been refreshed.'
              : errorMessage(err)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={cancel.isPending}>
            Keep order
          </Button>
          <Button
            variant="destructive"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending || err?.code === 'INVALID_STATE'}
          >
            {cancel.isPending && <Loader2 className="animate-spin" aria-hidden="true" />}
            Cancel order
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
