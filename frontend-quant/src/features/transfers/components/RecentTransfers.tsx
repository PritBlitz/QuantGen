import { ArrowUpRight, Send } from 'lucide-react'
import { useTransfers } from '@/api/queries'
import { Money } from '@/components/Money'
import { ReceiptButton } from '@/components/money/ReceiptButton'
import { EmptyState } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { maskShort } from '@/lib/mask'
import { transferReceipt } from '@/lib/receipts'
import { formatRelative } from '@/lib/time'

export function RecentTransfers() {
  const { data, isPending, isError, refetch } = useTransfers({ page: 0, size: 5 })
  return (
    <Card>
      <h2 className="font-medium">Recent transfers</h2>
      <div className="mt-3">
        {isPending ? (
          <div className="space-y-2">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : isError ? (
          <CardError what="transfers" onRetry={() => void refetch()} />
        ) : data.content.length === 0 ? (
          <EmptyState icon={<Send />} title="No transfers yet" />
        ) : (
          <ul className="divide-y divide-border">
            {data.content.map((t) => (
              <li key={t.id} className="flex items-center gap-3 py-2.5">
                <span className="grid size-9 shrink-0 place-items-center rounded-full bg-surface-2">
                  <ArrowUpRight aria-hidden="true" className="size-4 text-muted" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{t.toName}</p>
                  <p className="truncate text-xs text-muted">
                    <span className="num">{maskShort(t.toAccountNumber)}</span> ·{' '}
                    {formatRelative(t.createdAt)}
                  </p>
                </div>
                <div className="text-right">
                  <Money value={t.amount} tone="debit" className="text-sm" />
                  {t.status === 'FAILED' && (
                    <Badge tone="loss" className="ml-2">
                      Failed
                    </Badge>
                  )}
                </div>
                {t.status === 'COMPLETED' && (
                  <ReceiptButton
                    receipt={transferReceipt(t)}
                    variant="ghost"
                    size="icon-sm"
                    aria-label={`Download receipt for ${t.toName}`}
                    title="Download receipt"
                  >
                    {null}
                  </ReceiptButton>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Card>
  )
}
