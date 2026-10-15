import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Users } from 'lucide-react'
import { AlertDialog } from 'radix-ui'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import { paymentsApi } from '@/api/endpoints'
import { queryKeys, useBeneficiaries } from '@/api/queries'
import type { BeneficiaryDto } from '@/api/types'
import { EmptyState } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { maskShort } from '@/lib/mask'
import { AddBeneficiaryDialog } from './AddBeneficiaryDialog'

export function BeneficiariesCard({ onPick }: { onPick?: (b: BeneficiaryDto) => void }) {
  const qc = useQueryClient()
  const { data, isPending, isError, refetch } = useBeneficiaries()
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<BeneficiaryDto | null>(null)

  const remove = useMutation({
    mutationFn: (id: string) => paymentsApi.removeBeneficiary(id),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: queryKeys.beneficiaries })
      toast.success('Recipient removed')
      setRemoving(null)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <Card>
      <div className="flex items-center justify-between">
        <h2 className="font-medium">Saved recipients</h2>
        <Button variant="ghost" size="sm" className="-mr-2" onClick={() => setAdding(true)}>
          <Plus aria-hidden="true" /> Add
        </Button>
      </div>
      <div className="mt-3">
        {isPending ? (
          <div className="space-y-2">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : isError ? (
          <CardError what="recipients" onRetry={() => void refetch()} />
        ) : data.length === 0 ? (
          <EmptyState icon={<Users />} title="No saved recipients">
            Save people you pay often. We verify the name once, up front.
          </EmptyState>
        ) : (
          <ul className="-mx-2">
            {data.map((b) => (
              <li
                key={b.id}
                className="group flex items-center gap-3 rounded-[var(--radius-control)] px-2 py-2.5 hover:bg-surface-2"
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-full bg-surface-2 text-xs font-semibold group-hover:bg-bg">
                  {b.nickname.slice(0, 2).toUpperCase()}
                </span>
                <button
                  type="button"
                  className="min-w-0 flex-1 text-left"
                  onClick={() => onPick?.(b)}
                  disabled={!onPick}
                  aria-label={`Send to ${b.nickname}`}
                >
                  <p className="truncate text-sm font-medium">{b.nickname}</p>
                  <p className="truncate text-xs text-muted">
                    {b.holderName} · <span className="num">{maskShort(b.accountNumber)}</span>
                  </p>
                </button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-8 text-muted"
                  aria-label={`Remove ${b.nickname}`}
                  onClick={() => setRemoving(b)}
                >
                  <Trash2 />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <AddBeneficiaryDialog open={adding} onOpenChange={setAdding} />

      <AlertDialog.Root open={removing !== null} onOpenChange={(o) => !o && setRemoving(null)}>
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="fixed inset-0 z-50 bg-black/60" />
          <AlertDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-sm -translate-x-1/2 -translate-y-1/2 rounded-[var(--radius-card)] bg-surface p-6 hairline shadow-[var(--shadow-pop)]">
            <AlertDialog.Title className="text-lg font-semibold">
              Remove {removing?.nickname}?
            </AlertDialog.Title>
            <AlertDialog.Description className="mt-1.5 text-sm text-muted">
              You can add them again later. Past transfers aren’t affected.
            </AlertDialog.Description>
            <div className="mt-6 flex justify-end gap-2">
              <AlertDialog.Cancel asChild>
                <Button variant="ghost">Cancel</Button>
              </AlertDialog.Cancel>
              <Button
                variant="destructive"
                disabled={remove.isPending}
                onClick={() => removing && remove.mutate(removing.id)}
              >
                Remove
              </Button>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </Card>
  )
}
