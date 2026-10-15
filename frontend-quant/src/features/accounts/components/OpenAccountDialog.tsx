import { useMutation } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { RadioGroup } from 'radix-ui'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { accountsApi } from '@/api/endpoints'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountType } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

const OPTIONS: Array<{ value: AccountType; title: string; body: string }> = [
  {
    value: 'SAVINGS',
    title: 'Savings',
    body: 'For money you’re keeping. Hold up to 3 accounts in total.',
  },
  { value: 'CHECKING', title: 'Checking', body: 'For everyday spending, transfers and trading.' },
]

export function OpenAccountDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [type, setType] = useState<AccountType>('CHECKING')
  const invalidate = useInvalidateMoney()
  const create = useMutation({
    mutationFn: () => accountsApi.create({ type }),
    onSuccess: async (acc) => {
      await invalidate('accounts')
      toast.success(`${acc.type === 'SAVINGS' ? 'Savings' : 'Checking'} account opened`)
      onOpenChange(false)
    },
    onError: (e) => {
      const err = toApiError(e)
      toast.error(
        err.code === 'LIMIT_EXCEEDED'
          ? 'You already have the maximum of 3 accounts.'
          : errorMessage(err),
      )
    },
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Open an account" description="It’s free and ready instantly.">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (!create.isPending) create.mutate()
          }}
          className="space-y-5"
        >
          <RadioGroup.Root
            value={type}
            onValueChange={(v) => setType(v as AccountType)}
            aria-label="Account type"
            className="grid gap-2"
          >
            {OPTIONS.map((o) => (
              <RadioGroup.Item
                key={o.value}
                value={o.value}
                className={cn(
                  'rounded-[var(--radius-control)] p-4 text-left hairline transition-colors',
                  'data-[state=checked]:border-accent data-[state=checked]:bg-surface-2',
                )}
              >
                <span className="flex items-center justify-between">
                  <span className="font-medium">{o.title}</span>
                  <span
                    aria-hidden="true"
                    className={cn(
                      'grid size-4 place-items-center rounded-full border border-muted',
                      type === o.value && 'border-accent',
                    )}
                  >
                    <RadioGroup.Indicator className="size-2 rounded-full bg-accent" />
                  </span>
                </span>
                <span className="mt-1 block text-sm text-muted">{o.body}</span>
              </RadioGroup.Item>
            ))}
          </RadioGroup.Root>
          <Button type="submit" size="lg" className="w-full" disabled={create.isPending}>
            {create.isPending ? (
              <>
                <Loader2 className="animate-spin" aria-hidden="true" /> Opening…
              </>
            ) : (
              'Open account'
            )}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}
