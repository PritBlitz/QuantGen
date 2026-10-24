import { CreditCard, Loader2, ShieldCheck } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { payApi } from '@/api/endpoints.v2'
import { toApiError } from '@/api/errors'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto, TopupDto } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/features/auth/store'
import { useIdempotencyKey } from '@/lib/idempotency'
import { AMOUNT_INPUT_RE, formatINR, toDecimal } from '@/lib/money'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { openCheckout, useSimulatedCheckout, type CheckoutResult } from '../razorpay'

type Phase = 'form' | 'checkout' | 'verifying' | 'done' | 'failed'

/**
 * Razorpay top-up (PRD §7.9): create order (idempotent, so a double-click never opens two
 * checkouts) → Checkout → server verify → poll until CREDITED (covers the webhook path). The
 * balance only changes when the server says CREDITED; nothing is optimistic.
 */
export function TopupFlow({ account, onDone }: { account: AccountDto; onDone: () => void }) {
  const user = useAuthStore((s) => s.user)
  const [amount, setAmount] = useState('')
  const [phase, setPhase] = useState<Phase>('form')
  const [topup, setTopup] = useState<TopupDto | null>(null)
  const [error, setError] = useState<ReturnType<typeof moneyErrorCopy> | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [simulate, setSimulate] = useState<((r: CheckoutResult | null) => void) | null>(null)
  const { ensure, reset } = useIdempotencyKey()
  const invalidate = useInvalidateMoney()

  async function poll(id: string): Promise<TopupDto | null> {
    for (let i = 0; i < 30; i++) {
      const t = await payApi.topup(id).catch(() => null)
      if (t && t.status !== 'CREATED') return t
      await new Promise((r) => setTimeout(r, 2000))
    }
    return null
  }

  async function start(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setFieldError(null)
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1))
      return setFieldError('Enter at least ₹1')
    if (toDecimal(amount).gt(100000)) return setFieldError('At most ₹1,00,000 per top-up')
    setPhase('checkout')
    try {
      const order =
        topup?.status === 'CREATED'
          ? topup
          : await payApi.createTopup(account.id, toDecimal(amount).toFixed(2), ensure())
      setTopup(order)
      const result = useSimulatedCheckout
        ? await new Promise<CheckoutResult | null>((resolve) => setSimulate(() => resolve))
        : await openCheckout({
            keyId: order.gatewayKeyId,
            orderId: order.gatewayOrderId,
            name: user?.fullName ?? '',
            email: user?.email ?? '',
          })
      setSimulate(null)
      if (!result) {
        setPhase('form')
        return
      }
      setPhase('verifying')
      let t = await payApi
        .verifyTopup(order.id, result.paymentId, result.signature)
        .catch((err: unknown) => {
          if (toApiError(err).code === 'PAYMENT_VERIFICATION_FAILED') throw err
          return null
        })
      if (!t || t.status === 'CREATED') t = await poll(order.id)
      if (t?.status === 'CREDITED') {
        setTopup(t)
        setPhase('done')
        reset()
        void invalidate('accounts')
      } else {
        setPhase('failed')
        setError({
          title:
            t?.status === 'FAILED'
              ? 'The payment failed, so nothing was added.'
              : 'We’re still waiting for Razorpay to confirm.',
          detail:
            t?.failureReason ??
            'If money left your card, it will be credited automatically once Razorpay confirms. Check back in a few minutes.',
        })
      }
    } catch (err) {
      setSimulate(null)
      setPhase('form')
      setError(moneyErrorCopy(toApiError(err)))
    }
  }

  if (phase === 'done' && topup)
    return (
      <div className="space-y-5 text-center">
        <SuccessCheck label="Top-up credited" />
        <p className="font-display text-4xl">{formatINR(topup.amount)}</p>
        <p className="text-sm text-muted">added via Razorpay (test) · verified by QuantGen</p>
        <Button className="w-full" onClick={onDone} autoFocus>
          Done
        </Button>
      </div>
    )

  if (simulate)
    return (
      <div className="space-y-4">
        <div className="rounded-2xl border border-violet/40 bg-violet/[0.08] p-4">
          <p className="eyebrow text-violet">Razorpay test checkout · simulated (dev mocks)</p>
          <p className="mt-2 text-sm text-muted">
            Order <span className="num text-text">{topup?.gatewayOrderId}</span> for{' '}
            <Money value={topup?.amount ?? '0'} className="text-text" />. With real test keys this
            is the Razorpay window.
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => simulate(null)}>
            Close
          </Button>
          <Button
            variant="secondary"
            onClick={() => simulate({ paymentId: 'pay_forged', signature: 'bad' })}
          >
            Tamper signature
          </Button>
          <Button
            className="flex-1"
            onClick={() => {
              const id = `pay_${crypto.randomUUID().replace(/-/g, '').slice(0, 14)}`
              simulate({ paymentId: id, signature: `sig_${id}` })
            }}
          >
            Pay
          </Button>
        </div>
      </div>
    )

  return (
    <form onSubmit={(e) => void start(e)} className="space-y-5" noValidate>
      <p className="text-sm text-muted">
        Pay with UPI, card or netbanking in Razorpay’s{' '}
        <strong className="text-text">test mode</strong>. No real money moves.
      </p>
      <AmountInput
        label="Amount"
        value={amount}
        onValueChange={setAmount}
        error={fieldError ?? undefined}
        autoFocus
        disabled={phase !== 'form'}
      />
      {error && <MoneyErrorAlert copy={error} />}
      <p className="flex items-center gap-2 text-xs text-muted">
        <ShieldCheck aria-hidden="true" className="size-4 text-gain" /> We credit only after
        verifying the payment with Razorpay ourselves.
      </p>
      <Button
        type="submit"
        size="lg"
        className="w-full"
        disabled={phase !== 'form' && phase !== 'failed'}
      >
        {phase === 'checkout' || phase === 'verifying' ? (
          <>
            <Loader2 className="animate-spin" aria-hidden="true" />{' '}
            {phase === 'checkout' ? 'Waiting for checkout…' : 'Verifying payment…'}
          </>
        ) : (
          <>
            <CreditCard aria-hidden="true" /> Continue to Razorpay
          </>
        )}
      </Button>
    </form>
  )
}
