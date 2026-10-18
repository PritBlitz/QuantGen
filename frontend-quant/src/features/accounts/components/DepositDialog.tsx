import { ArrowLeft, Loader2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { accountsApi } from '@/api/endpoints'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto, TransactionDto } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Segmented } from '@/components/Segmented'
import { useFlag } from '@/api/flags'
import { TopupFlow } from '@/features/payments/components/TopupFlow'
import { formatINR, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { accountLabel } from '../labels'
import { validateDeposit } from '../validation'

const QUICK = ['1000', '5000', '10000', '50000']

/**
 * F-ACC-4 demo deposit: fill in → review → confirm, idempotent. The flow lives in an inner
 * component that Radix unmounts on close, so each opening starts fresh with a new key.
 */
export function DepositDialog({
  account,
  open,
  onOpenChange,
}: {
  account: AccountDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  if (!account) return null
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title="Add money">
        <AddMoney account={account} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

/** v2: demo deposit or a Razorpay test-mode top-up (PRD §7.9), when the flag is on. */
function AddMoney({ account, onDone }: { account: AccountDto; onDone: () => void }) {
  const razorpay = useFlag('razorpay')
  const [method, setMethod] = useState<'demo' | 'razorpay'>('demo')
  if (!razorpay) return <DepositFlow account={account} onDone={onDone} />
  return (
    <div className="space-y-5">
      <Segmented
        label="How to add money"
        value={method}
        onValueChange={setMethod}
        className="w-full"
        options={[
          { value: 'demo', label: 'Demo deposit' },
          { value: 'razorpay', label: 'Razorpay (test)' },
        ]}
      />
      {method === 'demo' ? <DepositFlow account={account} onDone={onDone} /> : <TopupFlow account={account} onDone={onDone} />}
    </div>
  )
}

function DepositFlow({ account, onDone }: { account: AccountDto; onDone: () => void }) {
  const [amount, setAmount] = useState('')
  const [touched, setTouched] = useState(false)
  const action = useMoneyAction<TransactionDto>()
  const invalidate = useInvalidateMoney()
  const error = touched ? validateDeposit(amount) : undefined

  const onReview = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (validateDeposit(amount)) return
    action.review()
  }

  const onConfirm = async () => {
    const res = await action.confirm((key) =>
      accountsApi.deposit(account.id, { amount: toDecimal(amount).toFixed(2) }, key),
    )
    if (res.ok) void invalidate('accounts')
  }

  const copy = action.error ? moneyErrorCopy(action.error) : null

  if (action.step === 'success' && action.result) {
    return (
      <div className="space-y-5 text-center">
        <SuccessCheck label="Deposit confirmed" />
        <p className="text-sm font-medium">Money added</p>
        <p className="font-display text-4xl tabular-nums">{formatINR(action.result.amount)}</p>
        <p className="text-sm text-muted">
          New balance <Money value={action.result.balanceAfter} className="text-text" />
        </p>
        <Button className="w-full" onClick={onDone} autoFocus>
          Done
        </Button>
      </div>
    )
  }

  if (action.step === 'review') {
    return (
      <div className="space-y-5">
        <ReviewAmount value={amount} caption={`Add to ${accountLabel(account)}`} />
        <ReviewRows>
          <ReviewRow label="From">Demo funding (simulated)</ReviewRow>
          <ReviewRow label="Fee">
            <Money value="0" />
          </ReviewRow>
          <ReviewRow label="Balance after" emphasis>
            <Money value={toDecimal(account.balance).plus(amount)} />
          </ReviewRow>
        </ReviewRows>
        {copy && <MoneyErrorAlert copy={copy} />}
        <div className="flex gap-2">
          <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
            <ArrowLeft aria-hidden="true" /> Edit
          </Button>
          <Button className="flex-1" size="lg" onClick={onConfirm} disabled={action.pending}>
            {action.pending ? (
              <>
                <Loader2 className="animate-spin" aria-hidden="true" /> Adding…
              </>
            ) : copy?.next === 'retry' ? (
              'Try again'
            ) : (
              `Add ${formatINR(amount)}`
            )}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={onReview} noValidate className="space-y-5">
      <p className="text-sm text-muted">
        Simulated funding into {accountLabel(account)}. Up to ₹1,00,000 per deposit.
      </p>
      <AmountInput
        label="Amount"
        value={amount}
        onValueChange={setAmount}
        onBlur={() => setTouched(true)}
        error={error}
        autoFocus
      />
      <div className="flex flex-wrap gap-2" aria-label="Quick amounts">
        {QUICK.map((q) => (
          <Button
            key={q}
            size="sm"
            variant="secondary"
            onClick={() => {
              setAmount(q)
              setTouched(true)
            }}
          >
            <span className="num">{formatINR(q).replace('.00', '')}</span>
          </Button>
        ))}
      </div>
      <Button type="submit" size="lg" className="w-full">
        Review
      </Button>
    </form>
  )
}
