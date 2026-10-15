import { ReceiptText } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useOrders } from '@/api/queries'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatAbsolute } from '@/lib/time'

export function OrdersPage() {
  const [page, setPage] = useState(0)
  const { data, isPending, isError, refetch, isPlaceholderData } = useOrders({ page, size: 20 })

  return (
    <div>
      <PageHeader
        title="Orders"
        back={{ to: '/portfolio', label: 'Portfolio' }}
        description="Every market order you’ve placed."
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
                    Fill price
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
                      </Link>
                    </td>
                    <td className="py-3 pr-3 text-right">
                      <Money value={o.price} />
                    </td>
                    <td className="py-3 pr-3 text-right">
                      <Money value={o.total} />
                    </td>
                    <td className="py-3 text-right">
                      <Badge tone={o.status === 'FILLED' ? 'neutral' : 'loss'}>
                        {o.status === 'FILLED' ? 'Filled' : 'Rejected'}
                      </Badge>
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
                    <p className="mt-1 text-xs text-muted">{formatAbsolute(o.createdAt)}</p>
                  </div>
                  <div className="text-right text-sm">
                    <Money value={o.total} />
                    <p className="text-xs text-muted">
                      @ <Money value={o.price} />
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
    </div>
  )
}
