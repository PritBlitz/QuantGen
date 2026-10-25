import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { accountsApi } from '@/api/endpoints'
import { errorMessage, toApiError } from '@/api/errors'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto } from '@/api/types'
import { Money } from '@/components/Money'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { toDecimal } from '@/lib/money'
import { accountLabel } from '../labels'

/**
 * Close a savings, checking or goal account (§7.3, added 2026-10-25). The server asks for a
 * step-up (SECURITY_SETTINGS; the client interceptor shows it) and refuses with `409` and a list
 * of blockers if the balance isn't zero or anything still depends on the account. Nothing here
 * moves money: the balance has to be moved out first.
 */
export function CloseAccountDialog({
  account,
  open,
  onOpenChange,
}: {
  account: AccountDto
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const invalidate = useInvalidateMoney()
  const [error, setError] = useState<string | null>(null)
  const balance = toDecimal(account.balance)
  const close = useMutation({
    mutationFn: () => accountsApi.close(account.id),
    onSuccess: () => {
      toast.success(`${accountLabel(account)} is closed`)
      void invalidate('accounts')
      onOpenChange(false)
    },
    onError: (e) => {
      const ae = toApiError(e)
      // The 409 message lists each blocker ("a loan uses it; …"), so show it as is.
      setError(
        ae.code === 'STEP_UP_CANCELLED'
          ? null
          : ae.code === 'INVALID_STATE'
            ? ae.message
            : errorMessage(ae),
      )
    },
  })

  return (
    <Dialog
      open={open}
      onOpenChange={(o) => {
        if (!o) setError(null)
        onOpenChange(o)
      }}
    >
      <DialogContent
        title="Close this account?"
        description={`${accountLabel(account)}. A closed account can’t send or receive money, and this can’t be undone.`}
      >
        <div className="space-y-4">
          <ul className="list-disc space-y-1 pl-5 text-sm text-muted">
            <li>The balance must be ₹0, with nothing on hold.</li>
            <li>
              Nothing may still use it: a loan, a fixed deposit, a scheduled transfer, an Autopilot
              rule, your handle or round-ups.
            </li>
            <li>Its card is frozen, and it stops counting toward your 3 accounts.</li>
          </ul>
          {!balance.isZero() && (
            <p className="rounded-[var(--radius-control)] bg-warn/10 px-3 py-2 text-sm text-warn">
              It still holds <Money value={account.balance} />. Move it to another account first.
            </p>
          )}
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)}>
              Keep it
            </Button>
            <Button
              variant="destructive"
              onClick={() => {
                setError(null)
                close.mutate()
              }}
              disabled={!balance.isZero() || close.isPending}
            >
              {close.isPending ? 'Closing…' : 'Close account'}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
