import { AlertTriangle, ArrowLeft, Loader2, ShieldCheck } from 'lucide-react'
import { ToggleGroup } from 'radix-ui'
import { useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { paymentsApi } from '@/api/endpoints'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto, BeneficiaryDto, TransferDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { DepositDialog } from '@/features/accounts/components/DepositDialog'
import { accountLabel } from '@/features/accounts/labels'
import { maskShort } from '@/lib/mask'
import { formatINR, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { cn } from '@/lib/utils'
import {
  normaliseAccountNumber,
  NOTE_MAX,
  validateTransfer,
  type TransferDraft,
  type TransferErrors,
} from '../validation'
import { AddBeneficiaryDialog } from './AddBeneficiaryDialog'

type Mode = 'saved' | 'own' | 'new'

interface Recipient {
  accountNumber: string
  name: string | null
  /** Name confirmed by the server (own account or saved beneficiary). */
  verified: boolean
}

export function TransferFlow({
  accounts,
  beneficiaries,
  initialFromId,
  picked,
}: {
  accounts: AccountDto[]
  beneficiaries: BeneficiaryDto[]
  initialFromId?: string | null
  /** A beneficiary chosen from the side panel. */
  picked?: BeneficiaryDto | null
}) {
  const senders = accounts.filter((a) => a.status === 'ACTIVE')
  const [fromId, setFromId] = useState(
    senders.find((a) => a.id === initialFromId)?.id ?? senders[0]?.id ?? '',
  )
  const [mode, setMode] = useState<Mode>(beneficiaries.length ? 'saved' : 'new')
  const [savedId, setSavedId] = useState('')
  const [ownId, setOwnId] = useState('')
  const [newNumber, setNewNumber] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<TransferErrors>({})
  const [verifyOpen, setVerifyOpen] = useState(false)
  const [depositOpen, setDepositOpen] = useState(false)

  const action = useMoneyAction<TransferDto>()
  const invalidate = useInvalidateMoney()

  // A recipient picked in the side panel fills the form (render-time state adjustment).
  // Ignored mid-review so a confirmed-looking screen never changes under the user.
  const [lastPicked, setLastPicked] = useState(picked)
  if (picked !== lastPicked) {
    setLastPicked(picked)
    if (picked && action.step === 'form') {
      setMode('saved')
      setSavedId(picked.id)
    }
  }

  const from = accounts.find((a) => a.id === fromId)
  const ownTargets = accounts.filter((a) => a.id !== fromId && a.status !== 'CLOSED')

  const recipient: Recipient | null = useMemo(() => {
    if (mode === 'saved') {
      const b = beneficiaries.find((x) => x.id === savedId)
      return b
        ? {
            accountNumber: b.accountNumber,
            name: `${b.holderName} (${b.nickname})`,
            verified: true,
          }
        : null
    }
    if (mode === 'own') {
      const a = accounts.find((x) => x.id === ownId)
      return a
        ? { accountNumber: a.accountNumber, name: `You · ${accountLabel(a)}`, verified: true }
        : null
    }
    const n = normaliseAccountNumber(newNumber)
    return n ? { accountNumber: n, name: null, verified: false } : null
  }, [mode, savedId, ownId, newNumber, beneficiaries, accounts])

  const draft: TransferDraft = {
    fromAccountId: fromId,
    toAccountNumber: recipient?.accountNumber ?? '',
    amount,
    note,
  }

  /** Any edit invalidates the review's idempotency key (CLAUDE.md). */
  const edited =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v)
      setErrors((e) => (Object.keys(e).length ? {} : e))
    }

  function onReview(e: FormEvent) {
    e.preventDefault()
    const errs = validateTransfer(draft, from?.balance)
    if (recipient && recipient.accountNumber === from?.accountNumber)
      errs.toAccountNumber = 'You can’t send to the same account'
    setErrors(errs)
    if (Object.keys(errs).length === 0) action.review()
  }

  async function onConfirm() {
    const res = await action.confirm((key) =>
      paymentsApi.transfer(
        {
          fromAccountId: fromId,
          toAccountNumber: draft.toAccountNumber,
          amount: toDecimal(amount).toFixed(2),
          ...(note.trim() ? { note: note.trim() } : {}),
        },
        key,
      ),
    )
    if (res.ok) {
      void invalidate('accounts', 'transfers')
      return
    }
    // Server field errors send the user back to the form with the message inline.
    const err = res.error
    if (err?.code !== 'VALIDATION_FAILED') return
    const next: TransferErrors = {}
    for (const fe of err.fieldErrors) {
      if (fe.field === 'amount') next.amount = fe.message.replace(/^must/, 'Amount must')
      if (fe.field === 'toAccountNumber')
        next.toAccountNumber =
          fe.message === 'account not found'
            ? 'We couldn’t find an open account with that number.'
            : fe.message
      if (fe.field === 'note') next.note = fe.message
    }
    if (Object.keys(next).length) {
      setErrors(next)
      action.edit()
    }
  }

  if (senders.length === 0) {
    return (
      <p className="text-sm text-muted">
        You need an active account to send money.{' '}
        <Link to="/accounts" className="text-text underline underline-offset-4">
          Go to accounts
        </Link>
      </p>
    )
  }

  // ── Success ──────────────────────────────────────────────────────────────────────
  if (action.step === 'success' && action.result) {
    const t = action.result
    return (
      <div className="space-y-6 py-4 text-center">
        <SuccessCheck label="Transfer completed" />
        <div>
          <p className="font-display text-5xl">{formatINR(t.amount)}</p>
          <p className="mt-2 text-muted">
            sent to <span className="text-text">{t.toName}</span>{' '}
            <span className="num">{maskShort(t.toAccountNumber)}</span>
          </p>
        </div>
        <p className="num text-xs text-muted">Reference {t.id.slice(0, 8).toUpperCase()}</p>
        <div className="flex flex-col justify-center gap-2 sm:flex-row">
          <Button
            variant="secondary"
            onClick={() => {
              setAmount('')
              setNote('')
              action.restart()
            }}
          >
            Make another transfer
          </Button>
          <Button asChild>
            <Link to="/dashboard">Done</Link>
          </Button>
        </div>
      </div>
    )
  }

  // ── Review ───────────────────────────────────────────────────────────────────────
  if (action.step === 'review' && from && recipient) {
    const copy = action.error
      ? moneyErrorCopy(action.error, { amount, balance: from.balance })
      : null
    return (
      <div className="space-y-6">
        <ReviewAmount value={amount} caption="You’re sending" />
        <ReviewRows>
          <ReviewRow label="To" emphasis>
            {recipient.name ?? 'New recipient'}
            <span className="num block text-xs font-normal text-muted">
              {maskShort(recipient.accountNumber)}
            </span>
          </ReviewRow>
          <ReviewRow label="From">{accountLabel(from)}</ReviewRow>
          {note.trim() && <ReviewRow label="Note">{note.trim()}</ReviewRow>}
          <ReviewRow label="Fee">
            <Money value="0" />
          </ReviewRow>
          <ReviewRow label="Balance after" emphasis>
            <Money value={toDecimal(from.balance).minus(amount)} />
          </ReviewRow>
        </ReviewRows>

        {recipient.verified ? (
          <p className="flex items-center gap-2 text-xs text-muted">
            <ShieldCheck aria-hidden="true" className="size-4 text-gain" /> Recipient name verified
            by QuantGen
          </p>
        ) : (
          <div className="flex items-start gap-3 rounded-[var(--radius-control)] border border-warn/40 bg-warn/10 p-3.5 text-sm">
            <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warn" />
            <div>
              <p className="font-medium">We haven’t verified this recipient’s name.</p>
              <p className="mt-0.5 text-muted">
                Double-check the number, or save them first to see the account holder’s name.
              </p>
              <Button
                variant="secondary"
                size="sm"
                className="mt-2.5"
                onClick={() => setVerifyOpen(true)}
              >
                Save &amp; verify
              </Button>
            </div>
          </div>
        )}

        {copy && (
          <MoneyErrorAlert copy={copy}>
            {copy.next === 'add-money' && (
              <Button size="sm" variant="secondary" onClick={() => setDepositOpen(true)}>
                Add money
              </Button>
            )}
            {copy.next === 'edit' && (
              <Button size="sm" variant="secondary" onClick={action.edit}>
                Change amount
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
                <Loader2 className="animate-spin" aria-hidden="true" /> Sending…
              </>
            ) : copy?.next === 'retry' ? (
              'Try again'
            ) : (
              `Send ${formatINR(amount)}`
            )}
          </Button>
        </div>

        <AddBeneficiaryDialog
          open={verifyOpen}
          onOpenChange={setVerifyOpen}
          initialAccountNumber={recipient.accountNumber}
          onSaved={(b) => {
            setMode('saved')
            setSavedId(b.id)
            // The recipient's details changed: a new review gets a new key.
            action.edit()
            action.review()
          }}
        />
        <DepositDialog account={from} open={depositOpen} onOpenChange={setDepositOpen} />
      </div>
    )
  }

  // ── Form ─────────────────────────────────────────────────────────────────────────
  return (
    <form onSubmit={onReview} noValidate className="space-y-6">
      <div className="space-y-1.5">
        <Label htmlFor="from-account">From</Label>
        <Select
          id="from-account"
          value={fromId}
          onValueChange={edited(setFromId)}
          invalid={!!errors.fromAccountId}
          options={senders.map((a) => ({
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
        {accounts.some((a) => a.status === 'FROZEN') && (
          <p className="text-xs text-muted">
            Frozen accounts can’t send money, so they aren’t listed.
          </p>
        )}
      </div>

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium">To</legend>
        <ToggleGroup.Root
          type="single"
          value={mode}
          onValueChange={(v) => v && edited(setMode)(v as Mode)}
          aria-label="Recipient type"
          className="grid grid-cols-3 gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1"
        >
          {(
            [
              ['saved', 'Saved'],
              ['own', 'My accounts'],
              ['new', 'New'],
            ] as const
          ).map(([v, l]) => (
            <ToggleGroup.Item
              key={v}
              value={v}
              className={cn(
                'rounded-[8px] py-2 text-sm text-muted transition-colors',
                'data-[state=on]:bg-surface data-[state=on]:text-text data-[state=on]:shadow-sm',
              )}
            >
              {l}
            </ToggleGroup.Item>
          ))}
        </ToggleGroup.Root>

        {mode === 'saved' &&
          (beneficiaries.length ? (
            <Select
              value={savedId}
              onValueChange={edited(setSavedId)}
              placeholder="Choose a saved recipient"
              invalid={!!errors.toAccountNumber}
              aria-describedby={errors.toAccountNumber ? 'to-error' : undefined}
              options={beneficiaries.map((b) => ({
                value: b.id,
                text: b.nickname,
                label: (
                  <span>
                    {b.nickname} <span className="text-muted">· {b.holderName}</span>{' '}
                    <span className="num text-muted">{maskShort(b.accountNumber)}</span>
                  </span>
                ),
              }))}
            />
          ) : (
            <p className="text-sm text-muted">
              No saved recipients yet. Use “New”, or save one from the panel.
            </p>
          ))}

        {mode === 'own' &&
          (ownTargets.length ? (
            <Select
              value={ownId}
              onValueChange={edited(setOwnId)}
              placeholder="Choose one of your accounts"
              invalid={!!errors.toAccountNumber}
              options={ownTargets.map((a) => ({
                value: a.id,
                text: accountLabel(a),
                label: accountLabel(a),
              }))}
            />
          ) : (
            <p className="text-sm text-muted">
              You have no other account.{' '}
              <Link to="/accounts" className="text-text underline underline-offset-4">
                Open one
              </Link>
            </p>
          ))}

        {mode === 'new' && (
          <FormField
            label="Account number"
            inputMode="numeric"
            autoComplete="off"
            placeholder="12 digits"
            value={newNumber}
            onChange={(e) =>
              edited(setNewNumber)(e.target.value.replace(/[^\d\s]/g, '').slice(0, 15))
            }
            className="num tracking-wide"
          />
        )}
        {errors.toAccountNumber && (
          <p id="to-error" role="alert" className="text-xs text-loss">
            {errors.toAccountNumber}
          </p>
        )}
      </fieldset>

      <AmountInput
        label="Amount"
        value={amount}
        onValueChange={edited(setAmount)}
        error={errors.amount}
        hint={
          from && (
            <>
              Available <Money value={from.balance} className="text-text" /> · up to ₹2,00,000 per
              transfer
            </>
          )
        }
      />

      <FormField
        label="Note (optional)"
        value={note}
        maxLength={NOTE_MAX + 20}
        onChange={(e) => edited(setNote)(e.target.value)}
        error={errors.note}
        placeholder="What’s it for?"
      />

      <Button type="submit" size="lg" className="w-full">
        Review transfer
      </Button>
    </form>
  )
}
