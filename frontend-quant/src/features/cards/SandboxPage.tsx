import { CheckCircle2, Loader2, ShoppingBag, Store, XCircle } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { cardsApi } from '@/api/endpoints.v2'
import { errorMessage } from '@/api/errors'
import { useFlag } from '@/api/flags'
import { useInvalidateMoney } from '@/api/queries'
import type { MerchantCategory, MoneyString } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel } from '@/components/Panel'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { FieldMessage, Input, Label } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { CATEGORY_LABEL, formatPan } from './labels'

interface Product { id: string; name: string; merchant: string; category: MerchantCategory; price: MoneyString }
const COFFEE: Product = { id: 'coffee', name: 'Filter coffee beans, 500 g', merchant: 'QuantGen Store · Pantry', category: 'GROCERY', price: '649.00' }
const PRODUCTS: Product[] = [
  COFFEE,
  { id: 'dinner', name: 'Dinner for two', merchant: 'QuantGen Store · Kitchen', category: 'DINING', price: '2400.00' },
  { id: 'headphones', name: 'Wireless headphones', merchant: 'QuantGen Store · Electronics', category: 'ELECTRONICS', price: '8999.00' },
  { id: 'flight', name: 'Flight BLR → DEL', merchant: 'QuantGen Store · Travel', category: 'TRAVEL', price: '6850.00' },
  { id: 'laptop', name: 'Laptop', merchant: 'QuantGen Store · Electronics', category: 'ELECTRONICS', price: '54990.00' },
  { id: 'casino', name: 'Casino chips', merchant: 'Lucky Sandbox Casino', category: 'GAMBLING', price: '5000.00' },
]
/** The demo merchant settles a few seconds after authorizing, like a real store would later. */
const CAPTURE_AFTER_MS = 5000

type PaymentStatus = 'AUTHORIZED' | 'CAPTURED' | 'REFUNDED' | 'DECLINED'
interface Payment {
  authId: string
  product: string
  amount: MoneyString
  status: PaymentStatus
  reason?: string
}
const STATUS: Record<PaymentStatus, { label: string; tone: BadgeTone }> = {
  AUTHORIZED: { label: 'Authorized · on hold', tone: 'warn' },
  CAPTURED: { label: 'Paid', tone: 'gain' },
  REFUNDED: { label: 'Refunded', tone: 'neutral' },
  DECLINED: { label: 'Declined', tone: 'loss' },
}

/**
 * `/cards/sandbox` (F-CARD-3): a demo checkout that charges a QuantGen virtual card. Approval puts
 * a hold on the account; the store captures a few seconds later; a refund is a reversal entry.
 * Card details are typed by the user, sent once, and cleared from the form after each attempt.
 */
export function SandboxPage() {
  const on = useFlag('cards')
  const uid = useId()
  const invalidate = useInvalidateMoney()
  const [productId, setProductId] = useState(COFFEE.id)
  const [pan, setPan] = useState('')
  const [expiry, setExpiry] = useState('')
  const [cvv, setCvv] = useState('')
  const [touched, setTouched] = useState(false)
  const [paying, setPaying] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [payments, setPayments] = useState<Payment[]>([])
  const timers = useRef<number[]>([])
  useEffect(() => () => timers.current.forEach((t) => window.clearTimeout(t)), [])

  const product = PRODUCTS.find((p) => p.id === productId) ?? COFFEE
  const errors = {
    pan: pan.replace(/\s/g, '').length === 16 ? undefined : 'Enter the 16-digit card number',
    expiry: /^(0[1-9]|1[0-2])\/\d{2}$/.test(expiry) ? undefined : 'Use MM/YY',
    cvv: /^\d{3}$/.test(cvv) ? undefined : '3 digits',
  }
  const show = (k: keyof typeof errors) => (touched ? errors[k] : undefined)

  const update = (authId: string, patch: Partial<Payment>) =>
    setPayments((list) => list.map((p) => (p.authId === authId ? { ...p, ...patch } : p)))

  async function capture(authId: string) {
    try {
      await cardsApi.capture(authId)
      update(authId, { status: 'CAPTURED' })
    } catch (e) {
      update(authId, { reason: errorMessage(e) })
    } finally {
      void invalidate('accounts', 'transfers')
    }
  }
  async function refund(authId: string) {
    try {
      await cardsApi.refund(authId)
      update(authId, { status: 'REFUNDED', reason: undefined })
    } catch (e) {
      update(authId, { reason: errorMessage(e) })
    } finally {
      void invalidate('accounts', 'transfers')
    }
  }

  async function pay(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    setFailure(null)
    if (errors.pan || errors.expiry || errors.cvv || paying) return
    setPaying(true)
    try {
      const res = await cardsApi.authorize({
        cardNumber: pan.replace(/\s/g, ''),
        expiry,
        cvv,
        amount: product.price,
        merchant: product.merchant,
        category: product.category,
      })
      const payment: Payment = { authId: res.authId, product: product.name, amount: product.price, status: res.status === 'APPROVED' ? 'AUTHORIZED' : 'DECLINED', reason: res.reason }
      setPayments((list) => [payment, ...list])
      if (res.status === 'APPROVED') {
        void invalidate('accounts')
        timers.current.push(window.setTimeout(() => void capture(res.authId), CAPTURE_AFTER_MS))
      }
      // The CVV is time-based and single-use for the demo; never keep it around.
      setCvv('')
      setTouched(false)
    } catch (err) {
      setFailure(errorMessage(err))
    } finally {
      setPaying(false)
    }
  }

  if (!on)
    return (
      <>
        <PageHeader title="Demo store" back={{ to: '/cards', label: 'Cards' }} />
        <EmptyState icon={<Store />} title="The demo store is closed while cards are switched off" />
      </>
    )

  return (
    <div>
      <PageHeader
        eyebrow="Merchant sandbox"
        title="QuantGen Store"
        back={{ to: '/cards', label: 'Cards' }}
        description="A pretend shop for trying your virtual card. Payments here move real balances in this demo, and you can refund them."
      />
      <div className="grid gap-4 lg:grid-cols-12">
        <div className="lg:col-span-7">
          <Panel title="Pick something" eyebrow="1">
            <div role="radiogroup" aria-label="Product" className="grid gap-2 sm:grid-cols-2">
              {PRODUCTS.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  role="radio"
                  aria-checked={p.id === productId}
                  onClick={() => setProductId(p.id)}
                  className={cn(
                    'flex flex-col items-start gap-1 rounded-[var(--radius-control)] p-3 text-left text-sm hairline transition-colors',
                    p.id === productId ? 'border-accent bg-accent/10' : 'hover:bg-surface-2',
                  )}
                >
                  <span className="font-medium">{p.name}</span>
                  <span className="text-xs text-muted">{CATEGORY_LABEL[p.category]}</span>
                  <Money value={p.price} className="mt-1" />
                </button>
              ))}
            </div>
          </Panel>
        </div>
        <div className="lg:col-span-5">
          <Panel title="Pay by card" eyebrow="2" description="Use the details from your Cards page.">
            <form onSubmit={(e) => void pay(e)} noValidate className="space-y-3" autoComplete="off">
              <div className="space-y-1.5">
                <Label htmlFor={`${uid}-pan`}>Card number</Label>
                <Input
                  id={`${uid}-pan`}
                  inputMode="numeric"
                  autoComplete="off"
                  className="num"
                  value={pan}
                  onChange={(e) => setPan(formatPan(e.target.value))}
                  aria-invalid={show('pan') ? true : undefined}
                  aria-describedby={`${uid}-pan-error`}
                />
                <FieldMessage id={`${uid}-pan-error`}>{show('pan')}</FieldMessage>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor={`${uid}-exp`}>Expiry</Label>
                  <Input
                    id={`${uid}-exp`}
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="MM/YY"
                    className="num"
                    value={expiry}
                    onChange={(e) => {
                      const d = e.target.value.replace(/\D/g, '').slice(0, 4)
                      setExpiry(d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d)
                    }}
                    aria-invalid={show('expiry') ? true : undefined}
                    aria-describedby={`${uid}-exp-error`}
                  />
                  <FieldMessage id={`${uid}-exp-error`}>{show('expiry')}</FieldMessage>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor={`${uid}-cvv`}>CVV</Label>
                  <Input
                    id={`${uid}-cvv`}
                    type="password"
                    inputMode="numeric"
                    autoComplete="off"
                    className="num"
                    value={cvv}
                    onChange={(e) => setCvv(e.target.value.replace(/\D/g, '').slice(0, 3))}
                    aria-invalid={show('cvv') ? true : undefined}
                    aria-describedby={`${uid}-cvv-error`}
                  />
                  <FieldMessage id={`${uid}-cvv-error`}>{show('cvv')}</FieldMessage>
                </div>
              </div>
              {failure && (
                <p role="alert" className="text-sm text-loss">
                  {failure}
                </p>
              )}
              <Button type="submit" size="lg" className="w-full" disabled={paying}>
                {paying ? <Loader2 className="animate-spin" aria-hidden="true" /> : <ShoppingBag aria-hidden="true" />}
                {paying ? 'Paying…' : (
                  <>
                    Pay <Money value={product.price} />
                  </>
                )}
              </Button>
            </form>
          </Panel>
        </div>
      </div>

      {payments.length > 0 && (
        <Panel title="This visit’s payments" eyebrow="Receipts" className="mt-4">
          <ul className="divide-y divide-border" aria-live="polite">
            {payments.map((p) => (
              <li key={p.authId} className="flex flex-wrap items-center gap-3 py-3 text-sm">
                {p.status === 'DECLINED' ? (
                  <XCircle aria-hidden="true" className="size-5 text-loss" />
                ) : (
                  <CheckCircle2 aria-hidden="true" className="size-5 text-gain" />
                )}
                <span className="min-w-0 flex-1">
                  <span className="font-medium">{p.product}</span>
                  {p.reason && <span className="block text-xs text-muted">{p.reason}</span>}
                  {p.status === 'AUTHORIZED' && (
                    <span className="block text-xs text-muted">The store collects it in a few seconds.</span>
                  )}
                </span>
                <Money value={p.amount} />
                <Badge tone={STATUS[p.status].tone}>{STATUS[p.status].label}</Badge>
                {p.status === 'CAPTURED' && (
                  <Button size="sm" variant="ghost" onClick={() => void refund(p.authId)}>
                    Refund
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Panel>
      )}
    </div>
  )
}
