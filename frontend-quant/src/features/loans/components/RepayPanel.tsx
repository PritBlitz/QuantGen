import { ArrowLeft, Loader2 } from 'lucide-react'
import { ToggleGroup } from 'radix-ui'
import { useState, type FormEvent } from 'react'
import { loansApi } from '@/api/endpoints'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto, LoanDetailDto, RepaymentDto } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { DepositDialog } from '@/features/accounts/components/DepositDialog'
import { accountLabel, isEveryday } from '@/features/accounts/labels'
import { AMOUNT_INPUT_RE, Decimal, formatINR, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { formatDate } from '@/lib/time'

/** F-LOAN-5 repayment: the next EMI or a custom amount (oldest dues first). Idempotent. */
export function RepayPanel({ loan, accounts }: { loan: LoanDetailDto; accounts: AccountDto[] }) {
  const usable = accounts.filter((a) => a.status === 'ACTIVE' && isEveryday(a))
  const unpaid = loan.schedule.filter((r) => r.status !== 'PAID')
  const next = unpaid[0]
  // amountDue (not emi): a partly paid instalment only owes its remainder (PRD §7.6).
  const maxDue = unpaid.reduce((s, r) => s.plus(r.amountDue), new Decimal(0))
  const [accountId, setAccountId] = useState(usable[0]?.id ?? '')
  const [mode, setMode] = useState<'next' | 'custom'>('next')
  const [custom, setCustom] = useState('')
  const [error, setError] = useState<string>()
  const [depositOpen, setDepositOpen] = useState(false)
  const action = useMoneyAction<RepaymentDto>()
  const invalidate = useInvalidateMoney()

  const account = usable.find((a) => a.id === accountId)
  const amount = mode === 'next' ? (next?.amountDue ?? '0') : custom

  if (!next) return <p className="text-sm text-muted">Nothing is due on this loan.</p>
  if (usable.length === 0)
    return <p className="text-sm text-muted">You need an active account to repay.</p>

  function validate(): string | undefined {
    if (mode === 'next') return undefined
    if (!custom || !AMOUNT_INPUT_RE.test(custom)) return 'Enter an amount with up to 2 decimals'
    const d = toDecimal(custom)
    if (d.lt(1)) return `The minimum is ${formatINR('1')}`
    if (d.gt(maxDue)) return `The most you can pay is ${formatINR(maxDue)} (everything due)`
    return undefined
  }

  function onReview(e: FormEvent) {
    e.preventDefault()
    const err = validate()
    setError(err)
    if (!err && account) action.review()
  }

  async function onConfirm() {
    const res = await action.confirm((key) =>
      loansApi.repay(
        loan.id,
        { accountId, ...(mode === 'custom' ? { amount: toDecimal(custom).toFixed(2) } : {}) },
        key,
      ),
    )
    if (res.ok) void invalidate('loans', 'accounts')
  }

  if (action.step === 'success' && action.result) {
    const r = action.result
    return (
      <div className="space-y-4 text-center">
        <SuccessCheck label="Repayment confirmed" />
        <p className="font-display text-4xl tabular-nums">{formatINR(r.amount)}</p>
        <ReviewRows>
          <ReviewRow label="Principal">
            <Money value={r.principalPaid} />
          </ReviewRow>
          <ReviewRow label="Interest">
            <Money value={r.interestPaid} />
          </ReviewRow>
        </ReviewRows>
        <Button
          variant="secondary"
          className="w-full"
          onClick={() => {
            setCustom('')
            action.restart()
          }}
        >
          Done
        </Button>
      </div>
    )
  }

  if (action.step === 'review' && account) {
    const copy = action.error
      ? moneyErrorCopy(action.error, {
          amount: toDecimal(amount).toFixed(2),
          balance: account.balance,
        })
      : null
    return (
      <div className="space-y-5">
        <ReviewAmount
          value={amount}
          caption={
            mode === 'next' ? `EMI ${next.installmentNo} of ${loan.tenureMonths}` : 'Repayment'
          }
        />
        <ReviewRows>
          <ReviewRow label="From">{accountLabel(account)}</ReviewRow>
          <ReviewRow label="Applied to">Oldest dues first</ReviewRow>
          <ReviewRow label="Balance after" emphasis>
            <Money value={toDecimal(account.balance).minus(amount)} />
          </ReviewRow>
        </ReviewRows>
        {copy && (
          <MoneyErrorAlert copy={copy}>
            {copy.next === 'add-money' && (
              <Button size="sm" variant="secondary" onClick={() => setDepositOpen(true)}>
                Add money
              </Button>
            )}
          </MoneyErrorAlert>
        )}
        <div className="flex gap-2">
          <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
            <ArrowLeft aria-hidden="true" /> Edit
          </Button>
          <Button size="lg" className="flex-1" onClick={onConfirm} disabled={action.pending}>
            {action.pending ? (
              <>
                <Loader2 className="animate-spin" aria-hidden="true" /> Paying…
              </>
            ) : copy?.next === 'retry' ? (
              'Try again'
            ) : (
              `Pay ${formatINR(amount)}`
            )}
          </Button>
        </div>
        <DepositDialog account={account} open={depositOpen} onOpenChange={setDepositOpen} />
      </div>
    )
  }

  return (
    <form onSubmit={onReview} noValidate className="space-y-5">
      <ToggleGroup.Root
        type="single"
        value={mode}
        onValueChange={(v) => {
          if (!v) return
          setMode(v as 'next' | 'custom')
          setError(undefined)
        }}
        aria-label="Repayment amount"
        className="grid grid-cols-2 gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1"
      >
        <ToggleGroup.Item
          value="next"
          className="rounded-[8px] py-2 text-sm text-muted data-[state=on]:bg-surface data-[state=on]:text-text"
        >
          Next EMI
        </ToggleGroup.Item>
        <ToggleGroup.Item
          value="custom"
          className="rounded-[8px] py-2 text-sm text-muted data-[state=on]:bg-surface data-[state=on]:text-text"
        >
          Custom amount
        </ToggleGroup.Item>
      </ToggleGroup.Root>

      {mode === 'next' ? (
        <div className="rounded-[var(--radius-control)] bg-surface-2 p-4">
          <p className="text-xs text-muted">
            EMI {next.installmentNo} of {loan.tenureMonths} · due {formatDate(next.dueDate)}
            {next.status === 'OVERDUE' && <span className="text-warn"> · overdue</span>}
          </p>
          <Money value={next.amountDue} className="mt-1 block text-2xl font-medium" />
          {toDecimal(next.amountDue).lt(next.emi) && (
            <p className="mt-0.5 text-xs text-muted">
              Remaining of <Money value={next.emi} /> (part already paid)
            </p>
          )}
        </div>
      ) : (
        <AmountInput
          label="Amount"
          value={custom}
          onValueChange={setCustom}
          error={error}
          hint={
            <>
              Up to <Money value={maxDue} /> (everything still due)
            </>
          }
          autoFocus
        />
      )}

      <div className="space-y-1.5">
        <Label htmlFor="repay-from">Pay from</Label>
        <Select
          id="repay-from"
          value={accountId}
          onValueChange={setAccountId}
          options={usable.map((a) => ({
            value: a.id,
            text: accountLabel(a),
            label: (
              <span className="flex w-full items-center justify-between gap-3 whitespace-nowrap">
                <span>{accountLabel(a)}</span>
                <Money value={a.balance} className="text-muted" />
              </span>
            ),
          }))}
        />
      </div>
      <Button type="submit" size="lg" className="w-full">
        Review repayment
      </Button>
    </form>
  )
}
