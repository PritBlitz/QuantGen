import { useMutation } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import { paymentsApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queries'
import type { BeneficiaryDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { useQueryClient } from '@tanstack/react-query'
import { isAccountNumber, normaliseAccountNumber } from '../validation'

/** F-PAY-4: saving validates the number server-side and resolves the holder's name. */
export function AddBeneficiaryDialog({
  open,
  onOpenChange,
  initialAccountNumber = '',
  onSaved,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  initialAccountNumber?: string
  onSaved?: (b: BeneficiaryDto) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Save a recipient"
        description="We check the account and show the holder’s name before you send anything."
      >
        <BeneficiaryForm
          initialAccountNumber={initialAccountNumber}
          onSaved={(b) => {
            onSaved?.(b)
            onOpenChange(false)
          }}
        />
      </DialogContent>
    </Dialog>
  )
}

/** Inner form: unmounted on close, so it always starts fresh. */
function BeneficiaryForm({
  initialAccountNumber,
  onSaved,
}: {
  initialAccountNumber: string
  onSaved: (b: BeneficiaryDto) => void
}) {
  const qc = useQueryClient()
  const [nickname, setNickname] = useState('')
  const [accountNumber, setAccountNumber] = useState(initialAccountNumber)
  const [errors, setErrors] = useState<{ nickname?: string; accountNumber?: string }>({})

  const save = useMutation({
    mutationFn: () =>
      paymentsApi.addBeneficiary({
        nickname: nickname.trim(),
        accountNumber: normaliseAccountNumber(accountNumber),
      }),
    onSuccess: async (b) => {
      await qc.invalidateQueries({ queryKey: queryKeys.beneficiaries })
      toast.success(`Saved ${b.nickname}`, { description: `Account holder: ${b.holderName}` })
      onSaved(b)
    },
    onError: (e) => {
      const fe = e.fieldErrors.find((f) => f.field === 'accountNumber' || f.field === 'nickname')
      if (fe) {
        const friendly: Record<string, string> = {
          'account not found': 'We couldn’t find an open account with that number.',
          'is your own account': 'That’s one of your own accounts. Pick it under “My accounts”.',
          'is already saved': 'You’ve already saved this account.',
        }
        setErrors({ [fe.field]: friendly[fe.message] ?? fe.message })
      } else toast.error(errorMessage(e))
    },
  })

  function submit() {
    const n = normaliseAccountNumber(accountNumber)
    const next: typeof errors = {}
    if (!nickname.trim()) next.nickname = 'Give them a nickname'
    else if (nickname.trim().length > 50) next.nickname = 'Keep it under 50 characters'
    if (!isAccountNumber(n)) next.accountNumber = 'Account numbers have 12 digits'
    setErrors(next)
    if (Object.keys(next).length === 0 && !save.isPending) save.mutate()
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
      noValidate
      className="space-y-5"
    >
      <FormField
        label="Nickname"
        value={nickname}
        onChange={(e) => setNickname(e.target.value)}
        error={errors.nickname}
        autoFocus
        maxLength={60}
      />
      <FormField
        label="Account number"
        inputMode="numeric"
        autoComplete="off"
        value={accountNumber}
        onChange={(e) => setAccountNumber(e.target.value.replace(/[^\d\s]/g, '').slice(0, 15))}
        error={errors.accountNumber}
        className="num tracking-wide"
      />
      <Button type="submit" size="lg" className="w-full" disabled={save.isPending}>
        {save.isPending ? (
          <>
            <Loader2 className="animate-spin" aria-hidden="true" /> Checking…
          </>
        ) : (
          'Save & verify'
        )}
      </Button>
    </form>
  )
}
