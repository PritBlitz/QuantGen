import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { payApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import type { AccountDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { accountLabel } from '@/features/accounts/labels'
import { HANDLE_RE } from '@/features/transfers/validation'

/** Claim or change your QuantGen handle (F-PAY-5): `name@qg`, changeable once every 30 days. */
export function HandleDialog({
  open,
  onOpenChange,
  current,
  accounts,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  current: string | null | undefined
  accounts: AccountDto[]
}) {
  const qc = useQueryClient()
  const cash = accounts.filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING')
  const [name, setName] = useState(current?.replace(/@qg$/, '') ?? '')
  const [accountId, setAccountId] = useState(cash[0]?.id ?? '')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const handle = `${name.trim().toLowerCase()}@qg`
    if (!HANDLE_RE.test(handle)) return setError('Use 3–30 letters, digits or dots, not starting or ending with a dot.')
    setBusy(true)
    try {
      await payApi.setHandle(handle, accountId)
      await qc.invalidateQueries({ queryKey: ['users', 'me'] })
      toast.success(`You’re ${handle}`)
      onOpenChange(false)
    } catch (err) {
      const ae = toApiError(err)
      setError(
        ae.code === 'LIMIT_EXCEEDED'
          ? 'You can change your handle once every 30 days. You can still point it at another of your accounts.'
          : ae.fieldErrors[0]?.message
            ? `That handle ${ae.fieldErrors[0].message}.`
            : errorMessage(ae),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={current ? 'Change your handle' : 'Claim your handle'} description="People can pay you with it instead of your account number. You can change it once every 30 days.">
        <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
          <FormField
            label="Handle"
            value={name}
            autoFocus
            autoCapitalize="none"
            spellCheck={false}
            onChange={(e) => setName(e.target.value.toLowerCase().replace(/[^a-z0-9.]/g, '').slice(0, 30))}
            trailing={<span className="pr-3 text-sm text-muted">@qg</span>}
          />
          <div className="space-y-1.5">
            <Label>Money paid to your handle lands in</Label>
            <Select aria-label="Money paid to your handle lands in" value={accountId} onValueChange={setAccountId} options={cash.map((a) => ({ value: a.id, label: accountLabel(a), text: accountLabel(a) }))} />
          </div>
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? 'Saving…' : 'Save handle'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
