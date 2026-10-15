import { useMutation } from '@tanstack/react-query'
import { ArrowLeft, Check, Loader2 } from 'lucide-react'
import { useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '@/api/errors'
import { loansApi } from '@/api/endpoints'
import { useAccounts, useInvalidateMoney } from '@/api/queries'
import type { LoanDto } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { FieldMessage, Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { accountLabel } from '@/features/accounts/labels'
import { LOAN_MAX, LOAN_MIN, TENURE_MAX, TENURE_MIN } from '@/lib/emi'
import { AMOUNT_INPUT_RE, formatINR, toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { useLoanQuote } from '../hooks'
import type { LoanDraft } from './EmiCalculator'

const STEPS = ['Loan', 'About you', 'Payout', 'Review'] as const
const TENURES = Array.from(
  { length: (TENURE_MAX - TENURE_MIN) / 6 + 1 },
  (_, i) => TENURE_MIN + i * 6,
)

export function ApplyDialog({
  draft,
  onOpenChange,
}: {
  draft: LoanDraft | null
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Dialog open={draft !== null} onOpenChange={onOpenChange}>
      <DialogContent title="Apply for a loan" className="max-w-lg">
        {draft && <ApplyFlow draft={draft} onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  )
}

function Stepper({ step }: { step: number }) {
  return (
    <ol className="mb-6 flex items-center gap-2" aria-label="Application progress">
      {STEPS.map((label, i) => (
        <li
          key={label}
          className="flex flex-1 items-center gap-2"
          aria-current={i === step ? 'step' : undefined}
        >
          <span
            className={cn(
              'grid size-6 shrink-0 place-items-center rounded-full text-[0.6875rem] font-semibold',
              i < step && 'bg-accent text-accent-ink',
              i === step && 'border-2 border-accent',
              i > step && 'bg-surface-2 text-muted',
            )}
          >
            {i < step ? <Check aria-hidden="true" className="size-3.5" /> : i + 1}
          </span>
          <span className={cn('hidden text-xs sm:inline', i === step ? 'text-text' : 'text-muted')}>
            {label}
          </span>
          {i < STEPS.length - 1 && <span aria-hidden="true" className="h-px flex-1 bg-border" />}
        </li>
      ))}
    </ol>
  )
}

function ApplyFlow({ draft, onClose }: { draft: LoanDraft; onClose: () => void }) {
  const accounts = useAccounts()
  const usable = (accounts.data ?? []).filter((a) => a.status === 'ACTIVE')
  const [step, setStep] = useState(0)
  const [amount, setAmount] = useState(String(draft.amount))
  const [tenure, setTenure] = useState(String(draft.tenureMonths))
  const [income, setIncome] = useState(draft.monthlyIncome)
  const [purpose, setPurpose] = useState('')
  const [accountId, setAccountId] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submitting = useRef(false)
  const invalidate = useInvalidateMoney()
  const effectiveAccount = accountId || usable[0]?.id || ''
  const payout = usable.find((a) => a.id === effectiveAccount)

  const quote = useLoanQuote(
    step === 3
      ? {
          amount: toDecimal(amount).toFixed(2),
          tenureMonths: Number.parseInt(tenure, 10),
          monthlyIncome: toDecimal(income).toFixed(2),
        }
      : null,
  )

  const apply = useMutation<LoanDto>({
    mutationFn: () =>
      loansApi.apply({
        amount: toDecimal(amount).toFixed(2),
        tenureMonths: Number.parseInt(tenure, 10),
        monthlyIncome: toDecimal(income).toFixed(2),
        purpose: purpose.trim(),
        disburseAccountId: effectiveAccount,
      }),
    onSuccess: () => void invalidate('loans'),
    onSettled: () => {
      submitting.current = false
    },
    onError: (e) => {
      const fe: Record<string, string> = {}
      for (const f of e.fieldErrors) fe[f.field] = f.message
      if (Object.keys(fe).length) {
        setErrors(fe)
        setStep(fe.amount || fe.tenureMonths ? 0 : fe.monthlyIncome || fe.purpose ? 1 : 2)
      }
    },
  })

  function validate(s: number): Record<string, string> {
    const e: Record<string, string> = {}
    if (s === 0) {
      if (!AMOUNT_INPUT_RE.test(amount)) e.amount = 'Enter an amount'
      else if (toDecimal(amount).lt(LOAN_MIN) || toDecimal(amount).gt(LOAN_MAX))
        e.amount = `Borrow between ${formatINR(String(LOAN_MIN))} and ${formatINR(String(LOAN_MAX))}`
    }
    if (s === 1) {
      if (!AMOUNT_INPUT_RE.test(income) || toDecimal(income).lte(0))
        e.monthlyIncome = 'Enter your monthly income'
      if (purpose.trim().length < 3) e.purpose = 'Tell us briefly what it’s for'
      else if (purpose.trim().length > 200) e.purpose = 'Keep it under 200 characters'
    }
    if (s === 2 && !effectiveAccount) e.disburseAccountId = 'Choose an account for the payout'
    return e
  }

  function next(e: FormEvent) {
    e.preventDefault()
    const errs = validate(step)
    setErrors(errs)
    if (Object.keys(errs).length === 0) setStep((s) => Math.min(3, s + 1))
  }

  function submit() {
    if (submitting.current) return // no duplicate applications on double-click
    submitting.current = true
    apply.mutate()
  }

  if (apply.isSuccess) {
    return (
      <div className="space-y-5 text-center">
        <SuccessCheck label="Application submitted" />
        <div>
          <p className="font-medium">Application submitted</p>
          <p className="mt-1 text-sm text-muted">
            An admin will review it. You’ll get a notification with the decision; once approved, the
            money lands in your account.
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Button variant="secondary" className="flex-1" asChild>
            <Link to={`/loans/${apply.data.id}`} onClick={onClose}>
              View application
            </Link>
          </Button>
          <Button className="flex-1" onClick={onClose}>
            Done
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div>
      <Stepper step={step} />
      {step === 0 && (
        <form onSubmit={next} noValidate className="space-y-5">
          <AmountInput
            label="How much?"
            value={amount}
            onValueChange={setAmount}
            error={errors.amount}
            hint="₹10,000 to ₹10,00,000"
            autoFocus
          />
          <div className="space-y-1.5">
            <Label htmlFor="tenure">Tenure</Label>
            <Select
              id="tenure"
              value={tenure}
              onValueChange={setTenure}
              options={[...new Set([...TENURES, Number.parseInt(tenure, 10)])]
                .sort((a, b) => a - b)
                .map((t) => ({ value: String(t), label: `${t} months` }))}
            />
          </div>
          <Button type="submit" size="lg" className="w-full">
            Continue
          </Button>
        </form>
      )}

      {step === 1 && (
        <form onSubmit={next} noValidate className="space-y-5">
          <AmountInput
            label="Monthly income"
            value={income}
            onValueChange={setIncome}
            error={errors.monthlyIncome}
            autoFocus
          />
          <div className="space-y-1.5">
            <Label htmlFor="purpose">Purpose</Label>
            <textarea
              id="purpose"
              rows={3}
              maxLength={220}
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              aria-invalid={errors.purpose ? true : undefined}
              aria-describedby={errors.purpose ? 'purpose-error' : undefined}
              placeholder="e.g. Home renovation"
              className="w-full resize-none rounded-[var(--radius-control)] bg-surface-2 p-3.5 text-[0.9375rem] hairline placeholder:text-muted/70 focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30 aria-[invalid=true]:border-loss"
            />
            <FieldMessage id="purpose-error">{errors.purpose}</FieldMessage>
          </div>
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setStep(0)}>
              <ArrowLeft aria-hidden="true" /> Back
            </Button>
            <Button type="submit" size="lg" className="flex-1">
              Continue
            </Button>
          </div>
        </form>
      )}

      {step === 2 && (
        <form onSubmit={next} noValidate className="space-y-5">
          {usable.length === 0 ? (
            <p className="text-sm text-muted">You need an active account to receive the loan.</p>
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="payout">Pay the loan into</Label>
              <Select
                id="payout"
                value={effectiveAccount}
                onValueChange={setAccountId}
                invalid={!!errors.disburseAccountId}
                options={usable.map((a) => ({
                  value: a.id,
                  text: accountLabel(a),
                  label: accountLabel(a),
                }))}
              />
              <FieldMessage id="payout-error">{errors.disburseAccountId}</FieldMessage>
              <p className="text-xs text-muted">
                EMIs are auto-debited from this account on each due date.
              </p>
            </div>
          )}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setStep(1)}>
              <ArrowLeft aria-hidden="true" /> Back
            </Button>
            <Button type="submit" size="lg" className="flex-1" disabled={usable.length === 0}>
              Continue
            </Button>
          </div>
        </form>
      )}

      {step === 3 && (
        <div className="space-y-5">
          <ReviewAmount value={amount} caption={`Over ${tenure} months at 12% a year`} />
          <ReviewRows>
            <ReviewRow label="Monthly EMI" emphasis>
              {quote.data ? (
                <Money value={quote.data.emi} />
              ) : (
                <Loader2 aria-label="Loading" className="ml-auto size-4 animate-spin" />
              )}
            </ReviewRow>
            <ReviewRow label="Total interest">
              {quote.data ? <Money value={quote.data.totalInterest} /> : '—'}
            </ReviewRow>
            <ReviewRow label="Total payable">
              {quote.data ? <Money value={quote.data.totalPayable} /> : '—'}
            </ReviewRow>
            <ReviewRow label="Payout to">{payout ? accountLabel(payout) : '—'}</ReviewRow>
            <ReviewRow label="Purpose">{purpose.trim()}</ReviewRow>
          </ReviewRows>
          {quote.data && !quote.data.eligible && (
            <MoneyErrorAlert
              copy={{ title: 'This application isn’t eligible.', detail: quote.data.reason }}
            />
          )}
          {apply.isError && !apply.error.fieldErrors.length && (
            <MoneyErrorAlert
              copy={{
                title:
                  apply.error.code === 'LOAN_NOT_ELIGIBLE'
                    ? 'This application isn’t eligible.'
                    : errorMessage(apply.error),
                detail: apply.error.code === 'LOAN_NOT_ELIGIBLE' ? apply.error.message : undefined,
              }}
            />
          )}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setStep(2)} disabled={apply.isPending}>
              <ArrowLeft aria-hidden="true" /> Back
            </Button>
            <Button
              size="lg"
              className="flex-1"
              onClick={submit}
              disabled={apply.isPending || !quote.data?.eligible}
            >
              {apply.isPending ? (
                <>
                  <Loader2 className="animate-spin" aria-hidden="true" /> Submitting…
                </>
              ) : (
                'Submit application'
              )}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}
