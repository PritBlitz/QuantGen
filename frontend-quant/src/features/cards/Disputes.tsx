import { Flag, Loader2, Scale } from 'lucide-react'
import { useId, useState } from 'react'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import type { CardDisputeDto, CardDto, CardPaymentDto, DisputeReason } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { Panel, PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { FieldMessage, Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { AMOUNT_INPUT_RE, toDecimal } from '@/lib/money'
import { formatDate, formatRelative } from '@/lib/time'
import { useCardPayments, useMyDisputes, useOpenDispute } from './hooks'
import { DISPUTE_REASON, DISPUTE_STATUS } from './labels'

const NOTE_MAX = 280
const WINDOW_DAYS = 60

/** F-CARD-4: a card's captured payments, each disputable within 60 days of capture. */
export function CardPayments({ card }: { card: CardDto }) {
  const payments = useCardPayments(card.id, true)
  const [disputing, setDisputing] = useState<CardPaymentDto | null>(null)
  // The 60-day window only needs day precision: read the clock once, not on every render.
  const [now] = useState(() => Date.now())
  return (
    <Panel
      eyebrow={`•••• ${card.last4}`}
      title="Payments"
      description="Something wrong with a payment? Raise a dispute and our team reviews it."
    >
      {payments.isError ? (
        <PanelError what="card payments" onRetry={() => void payments.refetch()} />
      ) : !payments.data ? (
        <Skeleton className="h-20 w-full" />
      ) : payments.data.content.length === 0 ? (
        <p className="py-2 text-sm text-muted">No card payments yet.</p>
      ) : (
        <ul className="divide-y divide-border">
          {payments.data.content.map((p) => {
            const tooOld = now - Date.parse(p.capturedAt) > WINDOW_DAYS * 86_400_000
            const canDispute = !p.openDisputeId && !tooOld && toDecimal(p.disputableAmount).gt(0)
            return (
              <li key={p.authId} className="flex items-center gap-3 py-2.5 text-sm">
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{p.merchant}</span>
                  <span className="block text-xs text-muted">
                    {formatRelative(p.capturedAt)}
                    {toDecimal(p.refundedAmount).gt(0) && (
                      <>
                        {' · '}
                        <Money value={p.refundedAmount} /> refunded
                      </>
                    )}
                  </span>
                </span>
                <Money value={p.capturedAmount} tone="debit" />
                {p.openDisputeId ? (
                  <Badge tone="warn">Disputed</Badge>
                ) : canDispute ? (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setDisputing(p)}
                    aria-label={`Dispute ${p.merchant} payment`}
                  >
                    <Flag aria-hidden="true" /> Dispute
                  </Button>
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
      <DisputeDialog card={card} payment={disputing} onClose={() => setDisputing(null)} />
    </Panel>
  )
}

function DisputeDialog({
  card,
  payment,
  onClose,
}: {
  card: CardDto
  payment: CardPaymentDto | null
  onClose: () => void
}) {
  const open = useOpenDispute(card.id)
  return (
    <Dialog open={!!payment} onOpenChange={(o) => !o && !open.isPending && onClose()}>
      <DialogContent
        title="Dispute this payment?"
        description="No money moves yet. Our team reviews it, and if it’s upheld the amount comes back to your account. You’ll get a notification either way."
      >
        {payment && (
          <DisputeForm
            key={payment.authId}
            payment={payment}
            submit={open.mutateAsync}
            pending={open.isPending}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function DisputeForm({
  payment,
  submit,
  pending,
  onDone,
}: {
  payment: CardPaymentDto
  submit: ReturnType<typeof useOpenDispute>['mutateAsync']
  pending: boolean
  onDone: () => void
}) {
  const id = useId()
  const [reason, setReason] = useState<DisputeReason | ''>('')
  const [amount, setAmount] = useState(toDecimal(payment.disputableAmount).toFixed(2))
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ reason?: string; amount?: string; note?: string }>({})
  const [failure, setFailure] = useState<string | null>(null)

  // UX checks only; the server validates the same bounds (PRD §12.11).
  function check() {
    const e: typeof errors = {}
    if (!reason) e.reason = 'Choose what went wrong'
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lte(0)) e.amount = 'Enter an amount'
    else if (toDecimal(amount).gt(payment.disputableAmount))
      e.amount = `At most ${toDecimal(payment.disputableAmount).toFixed(2)}`
    if (note.trim().length > NOTE_MAX) e.note = `Keep it under ${NOTE_MAX} characters`
    setErrors(e)
    return Object.keys(e).length === 0
  }

  async function send() {
    if (!check() || !reason) return
    setFailure(null)
    try {
      const full = toDecimal(amount).eq(payment.disputableAmount)
      await submit({
        authId: payment.authId,
        reason,
        ...(full ? {} : { amount: toDecimal(amount).toFixed(2) }),
        ...(note.trim() ? { note: note.trim() } : {}),
      })
      toast.success('Dispute raised. We’ll let you know the outcome.')
      onDone()
    } catch (e) {
      const ae = toApiError(e)
      const byField = Object.fromEntries(ae.fieldErrors.map((f) => [f.field, f.message]))
      if (byField.amount || byField.note || byField.reason)
        setErrors({ amount: byField.amount, note: byField.note, reason: byField.reason })
      else if (ae.code === 'INVALID_STATE')
        setFailure(
          'This payment can’t be disputed any more (it may already be disputed, or it’s over 60 days old).',
        )
      else if (ae.code === 'LIMIT_EXCEEDED')
        setFailure('You have too many disputes open right now. Wait for one to be resolved.')
      else setFailure(errorMessage(ae))
    }
  }

  return (
    <form
      noValidate
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault()
        void send()
      }}
    >
      <div className="rounded-[var(--radius-control)] bg-surface-2 p-4 text-sm">
        <Money value={payment.capturedAmount} className="font-medium" /> at {payment.merchant} ·{' '}
        {formatDate(payment.capturedAt)}
      </div>
      <div className="space-y-1.5">
        <Label htmlFor={`${id}-reason`}>What went wrong?</Label>
        <Select
          id={`${id}-reason`}
          value={reason}
          placeholder="Choose a reason"
          invalid={!!errors.reason}
          onValueChange={(v) => setReason(v as DisputeReason)}
          options={(Object.keys(DISPUTE_REASON) as DisputeReason[]).map((r) => ({
            value: r,
            label: DISPUTE_REASON[r],
            text: DISPUTE_REASON[r],
          }))}
        />
        <FieldMessage id={`${id}-reason-error`}>{errors.reason}</FieldMessage>
      </div>
      <AmountInput
        label="Amount to dispute"
        value={amount}
        onValueChange={setAmount}
        error={errors.amount}
      />
      <div className="space-y-1.5">
        <Label htmlFor={`${id}-note`}>
          Details <span className="font-normal text-muted">(optional)</span>
        </Label>
        <Textarea
          id={`${id}-note`}
          value={note}
          maxLength={NOTE_MAX + 20}
          onChange={(e) => setNote(e.target.value)}
          aria-invalid={errors.note ? true : undefined}
          placeholder="e.g. The order never arrived"
        />
        <FieldMessage id={`${id}-note-error`}>{errors.note}</FieldMessage>
      </div>
      {failure && <MoneyErrorAlert copy={{ title: 'That didn’t go through.', detail: failure }} />}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="ghost" onClick={onDone} disabled={pending}>
          Cancel
        </Button>
        <Button type="submit" disabled={pending}>
          {pending && <Loader2 className="animate-spin" aria-hidden="true" />}
          Raise dispute
        </Button>
      </div>
    </form>
  )
}

/** Every dispute the customer raised, with its outcome. */
export function MyDisputes() {
  const list = useMyDisputes(true)
  if (list.isError) return <PanelError what="your disputes" onRetry={() => void list.refetch()} />
  if (!list.data || list.data.length === 0) return null
  return (
    <Panel eyebrow="Disputes" title="Your disputes">
      <ul className="divide-y divide-border">
        {list.data.map((d: CardDisputeDto) => {
          const s = DISPUTE_STATUS[d.status] ?? { label: d.status, tone: 'neutral' as const }
          return (
            <li key={d.id} className="flex items-start gap-3 py-3 text-sm">
              <Scale aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted" />
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{d.merchant}</span>
                  <Badge tone={s.tone}>{s.label}</Badge>
                </span>
                <span className="block text-xs text-muted">
                  {DISPUTE_REASON[d.reason] ?? d.reason} · •••• {d.last4} ·{' '}
                  {formatRelative(d.createdAt)}
                </span>
                {d.resolutionReason && (
                  <span className="mt-1 block text-xs">{d.resolutionReason}</span>
                )}
              </span>
              <Money value={d.amount} />
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}
