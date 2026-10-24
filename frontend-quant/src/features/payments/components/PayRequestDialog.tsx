import { Loader2 } from 'lucide-react'
import { useState } from 'react'
import { payApi } from '@/api/endpoints.v2'
import type { AccountDto, PaymentRequestDto, TransferDto } from '@/api/types'
import { Money } from '@/components/Money'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { accountLabel } from '@/features/accounts/labels'
import { formatINR, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { useInvalidatePayments } from '../hooks'

/**
 * Pay an incoming request (F-PAY-8): pick an account → review → confirm. The Idempotency-Key is
 * created at review and reused on retry; step-up (>₹50,000) is handled by the client.
 */
export function PayRequestDialog({
  request,
  accounts,
  onClose,
}: {
  request: PaymentRequestDto
  accounts: AccountDto[]
  onClose: () => void
}) {
  const senders = accounts.filter(
    (a) => a.status === 'ACTIVE' && (a.type === 'SAVINGS' || a.type === 'CHECKING'),
  )
  const [fromId, setFromId] = useState(senders[0]?.id ?? '')
  const action = useMoneyAction<TransferDto>()
  const invalidate = useInvalidatePayments()
  const from = senders.find((a) => a.id === fromId)
  const available = from ? (from.availableBalance ?? from.balance) : '0'
  const copy = action.error
    ? moneyErrorCopy(action.error, { amount: request.amount, balance: available })
    : null

  return (
    <Dialog open onOpenChange={(o) => !o && !action.pending && onClose()}>
      <DialogContent
        title={action.step === 'success' ? 'Paid' : `Pay ${request.fromHandle}`}
        description={request.note ?? undefined}
      >
        {action.step === 'success' && action.result ? (
          <div className="space-y-5 py-2 text-center">
            <SuccessCheck label="Request paid" />
            <p className="font-display text-4xl">{formatINR(action.result.amount)}</p>
            <p className="text-sm text-muted">sent to {action.result.toName}</p>
            <Button className="w-full" onClick={onClose}>
              Done
            </Button>
          </div>
        ) : (
          <div className="space-y-5">
            <ReviewAmount value={toDecimal(request.amount).toFixed(2)} caption="They asked for" />
            {action.step === 'form' ? (
              <div className="space-y-1.5">
                <Label>Pay from</Label>
                <Select
                  aria-label="Pay from"
                  value={fromId}
                  onValueChange={setFromId}
                  options={senders.map((a) => ({
                    value: a.id,
                    text: accountLabel(a),
                    label: (
                      <span className="flex w-full justify-between gap-3">
                        {accountLabel(a)}{' '}
                        <Money value={a.availableBalance ?? a.balance} className="text-muted" />
                      </span>
                    ),
                  }))}
                />
              </div>
            ) : (
              <ReviewRows>
                <ReviewRow label="To" emphasis>
                  {request.fromHandle}
                </ReviewRow>
                <ReviewRow label="From">{from ? accountLabel(from) : '—'}</ReviewRow>
                <ReviewRow label="Available after" emphasis>
                  <Money value={toDecimal(available).minus(request.amount)} />
                </ReviewRow>
              </ReviewRows>
            )}
            {copy && <MoneyErrorAlert copy={copy} />}
            <div className="flex gap-2">
              {action.step === 'review' && (
                <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
                  Back
                </Button>
              )}
              <Button
                size="lg"
                className="flex-1"
                disabled={!from || action.pending}
                onClick={() => {
                  if (action.step === 'form') action.review()
                  else
                    void action
                      .confirm((key) => payApi.payRequest(request.id, fromId, key))
                      .then((r) => {
                        if (r.ok) void invalidate()
                      })
                }}
              >
                {action.pending ? (
                  <>
                    <Loader2 className="animate-spin" aria-hidden="true" /> Paying…
                  </>
                ) : action.step === 'form' ? (
                  'Review'
                ) : (
                  `Pay ${formatINR(request.amount)}`
                )}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
