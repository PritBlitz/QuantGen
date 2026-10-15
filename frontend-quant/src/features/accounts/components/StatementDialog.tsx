import { Download, Loader2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import { accountsApi } from '@/api/endpoints'
import type { AccountDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { daysAgoIST, daysBetween, todayIST } from '@/lib/time'

function validateRange(from: string, to: string): string | undefined {
  if (!from || !to) return 'Choose both dates'
  if (from > to) return 'The start date must be on or before the end date'
  if (to > todayIST()) return 'The end date can’t be in the future'
  if (daysBetween(from, to) > 366) return 'Choose a range of 366 days or fewer'
  return undefined
}

/** F-ACC-6 CSV statement. Dates are IST calendar days, both inclusive. */
export function StatementDialog({
  account,
  open,
  onOpenChange,
}: {
  account: AccountDto
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Download statement"
        description="A CSV of every transaction in the range (IST)."
      >
        <StatementForm account={account} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

/** Inner form: unmounted on close, so each opening defaults to the last 30 days. */
function StatementForm({ account, onDone }: { account: AccountDto; onDone: () => void }) {
  const [from, setFrom] = useState(daysAgoIST(30))
  const [to, setTo] = useState(todayIST())
  const [pending, setPending] = useState(false)

  const error = validateRange(from, to)

  async function download() {
    if (error || pending) return
    setPending(true)
    try {
      const blob = await accountsApi.statement(account.id, { from, to })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      // Last 4 digits only in the file name (no full account number).
      a.download = `quantgen-statement-${account.accountNumber.slice(-4)}-${from}-to-${to}.csv`
      document.body.append(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      onDone()
    } catch (e) {
      toast.error(errorMessage(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void download()
      }}
      className="space-y-5"
    >
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField
          label="From"
          type="date"
          value={from}
          max={to}
          onChange={(e) => setFrom(e.target.value)}
        />
        <FormField
          label="To"
          type="date"
          value={to}
          min={from}
          max={todayIST()}
          onChange={(e) => setTo(e.target.value)}
          error={error}
        />
      </div>
      <Button type="submit" size="lg" className="w-full" disabled={!!error || pending}>
        {pending ? (
          <>
            <Loader2 className="animate-spin" aria-hidden="true" /> Preparing…
          </>
        ) : (
          <>
            <Download aria-hidden="true" /> Download CSV
          </>
        )}
      </Button>
    </form>
  )
}
