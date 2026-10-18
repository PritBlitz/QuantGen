import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import type { BudgetDto, Category } from '@/api/types'
import { AmountInput } from '@/components/money/AmountInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Select } from '@/components/ui/select'
import { BUDGETABLE, CATEGORIES } from '@/lib/categories'
import { AMOUNT_INPUT_RE, toDecimal } from '@/lib/money'
import { useSaveBudgets } from './hooks'

interface Row {
  category: Category
  limit: string
}

/** Edit monthly budgets (F-INS-2). Amounts stay strings; the server validates again. */
export function BudgetsDialog({
  open,
  onOpenChange,
  budgets,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  budgets: BudgetDto[]
}) {
  const save = useSaveBudgets()
  const [rows, setRows] = useState<Row[]>(() =>
    budgets.map((b) => ({ category: b.category, limit: toDecimal(b.monthlyLimit).toDecimalPlaces(0).toFixed(0) })),
  )
  const [error, setError] = useState<string | null>(null)
  const free = BUDGETABLE.filter((c) => !rows.some((r) => r.category === c))

  function submit() {
    setError(null)
    for (const r of rows) {
      if (!AMOUNT_INPUT_RE.test(r.limit) || toDecimal(r.limit).lt(100)) {
        setError(`${CATEGORIES[r.category].label}: enter at least ₹100.`)
        return
      }
    }
    save.mutate(
      rows.map((r) => ({ category: r.category, monthlyLimit: r.limit })),
      {
        onSuccess: () => {
          toast.success('Budgets saved')
          onOpenChange(false)
        },
        onError: (e) => setError(toApiError(e).fieldErrors[0]?.message ?? errorMessage(e)),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Monthly budgets"
        description="We’ll nudge you at 80% and 100% of each budget."
        className="max-w-lg"
      >
        <ul className="space-y-3">
          {rows.map((r, i) => (
            <li key={r.category} className="grid grid-cols-[1fr_9rem_auto] items-end gap-2">
              <Select
                value={r.category}
                onValueChange={(v) =>
                  setRows((rs) => rs.map((x, j) => (j === i ? { ...x, category: v as Category } : x)))
                }
                options={[r.category, ...free].map((c) => ({ value: c, label: CATEGORIES[c].label, text: CATEGORIES[c].label }))}
              />
              <AmountInput
                label={`${CATEGORIES[r.category].label} limit`}
                className="h-11 [&_input]:text-base"
                value={r.limit}
                onValueChange={(v) => setRows((rs) => rs.map((x, j) => (j === i ? { ...x, limit: v } : x)))}
              />
              <Button
                variant="ghost"
                size="icon"
                aria-label={`Remove ${CATEGORIES[r.category].label} budget`}
                onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}
              >
                <Trash2 />
              </Button>
            </li>
          ))}
        </ul>
        {free.length > 0 && (
          <Button
            variant="outline"
            size="sm"
            className="mt-4"
            onClick={() => setRows((rs) => [...rs, { category: free[0] as Category, limit: '2000' }])}
          >
            <Plus aria-hidden="true" /> Add a budget
          </Button>
        )}
        {error && (
          <p role="alert" className="mt-4 text-sm text-loss">
            {error}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save budgets'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
