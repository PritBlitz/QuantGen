import { ArrowLeft, Loader2, Minus, Plus } from 'lucide-react'
import { ToggleGroup } from 'radix-ui'
import { useId, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { marketApi } from '@/api/endpoints'
import { useInvalidateMoney } from '@/api/queries'
import type { AccountDto, InstrumentDto, OrderDto, OrderSide } from '@/api/types'
import { Money } from '@/components/Money'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { Button } from '@/components/ui/button'
import { FieldMessage, Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { accountLabel } from '@/features/accounts/labels'
import { formatINR, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { cn } from '@/lib/utils'
import { QTY_MAX, validateQuantity } from '../validation'

export function OrderTicket({
  instrument,
  accounts,
  held,
  initialSide = 'BUY',
  onDone,
}: {
  instrument: InstrumentDto
  accounts: AccountDto[]
  /** Shares currently held (from the portfolio). */
  held: number
  initialSide?: OrderSide
  onDone?: () => void
}) {
  const usable = accounts.filter((a) => a.status === 'ACTIVE')
  const [side, setSide] = useState<OrderSide>(initialSide)
  const [accountId, setAccountId] = useState(usable[0]?.id ?? '')
  const [qty, setQty] = useState('1')
  const [touched, setTouched] = useState(false)
  // Price frozen at review time, so the review doesn't jump while you read it.
  const [reviewPrice, setReviewPrice] = useState(instrument.price)
  const action = useMoneyAction<OrderDto>()
  const uid = useId()
  const accountFieldId = `${uid}-account`
  const qtyFieldId = `${uid}-qty`
  const qtyErrorId = `${uid}-qty-error`
  const invalidate = useInvalidateMoney()

  const account = usable.find((a) => a.id === accountId)
  const qtyError = validateQuantity(qty, side, held)
  const shownError = touched ? qtyError : undefined
  const qtyNum = qtyError ? 0 : Number.parseInt(qty, 10)
  const estimate = toDecimal(instrument.price).times(qtyNum)
  const cashShort =
    side === 'BUY' && account && !qtyError && estimate.gt(toDecimal(account.balance))
      ? estimate.minus(toDecimal(account.balance))
      : null

  const step = (delta: number) => {
    const cur = /^\d+$/.test(qty) ? Number.parseInt(qty, 10) : 0
    setQty(String(Math.min(QTY_MAX, Math.max(1, cur + delta))))
    setTouched(true)
  }

  function onReview(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (qtyError || !account || cashShort) return
    setReviewPrice(instrument.price)
    action.review()
  }

  async function onConfirm() {
    const res = await action.confirm((key) =>
      marketApi.placeOrder({ accountId, symbol: instrument.symbol, side, quantity: qtyNum }, key),
    )
    if (res.ok) void invalidate('accounts', 'portfolio', 'orders')
  }

  if (usable.length === 0) {
    return (
      <p className="text-sm text-muted">
        You need an active account to trade.{' '}
        <Link to="/accounts" className="text-text underline underline-offset-4">
          Go to accounts
        </Link>
      </p>
    )
  }

  if (action.step === 'success' && action.result) {
    const o = action.result
    const filled = o.status === 'FILLED'
    return (
      <div className="space-y-5 text-center">
        {filled ? (
          <SuccessCheck label="Order filled" />
        ) : (
          <p className="text-lg font-medium text-loss">Order rejected</p>
        )}
        <div>
          <p className="font-display text-4xl tabular-nums">{formatINR(o.total)}</p>
          <p className="mt-1 text-sm text-muted">
            {o.side === 'BUY' ? 'Bought' : 'Sold'}{' '}
            <span className="num text-text">{o.quantity}</span> {o.symbol} at{' '}
            <span className="num text-text">{formatINR(o.price)}</span>
          </p>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Button
            variant="secondary"
            className="flex-1"
            onClick={() => {
              setQty('1')
              setTouched(false)
              action.restart()
            }}
          >
            New order
          </Button>
          {onDone ? (
            <Button className="flex-1" onClick={onDone}>
              Done
            </Button>
          ) : (
            <Button asChild className="flex-1">
              <Link to="/portfolio">View portfolio</Link>
            </Button>
          )}
        </div>
      </div>
    )
  }

  if (action.step === 'review' && account) {
    const est = toDecimal(reviewPrice).times(qtyNum)
    const copy = action.error
      ? moneyErrorCopy(action.error, { amount: est.toFixed(2), balance: account.balance })
      : null
    return (
      <div className="space-y-5">
        <ReviewAmount value={est} caption={`Estimated ${side === 'BUY' ? 'cost' : 'proceeds'}`} />
        <ReviewRows>
          <ReviewRow label="Order" emphasis>
            {side === 'BUY' ? 'Buy' : 'Sell'} <span className="num">{qtyNum}</span> ×{' '}
            {instrument.symbol}
          </ReviewRow>
          <ReviewRow label="Market price">
            <Money value={reviewPrice} />
          </ReviewRow>
          <ReviewRow label={side === 'BUY' ? 'Pay from' : 'Pay into'}>
            {accountLabel(account)}
          </ReviewRow>
          <ReviewRow label="Cash after (est.)">
            <Money
              value={
                side === 'BUY'
                  ? toDecimal(account.balance).minus(est)
                  : toDecimal(account.balance).plus(est)
              }
            />
          </ReviewRow>
        </ReviewRows>
        <p className="text-xs text-muted">
          Market orders fill at the price when we receive them, which can differ slightly from this
          estimate.
        </p>
        {copy && <MoneyErrorAlert copy={copy} />}
        <div className="flex gap-2">
          <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
            <ArrowLeft aria-hidden="true" /> Edit
          </Button>
          <Button
            size="lg"
            className={cn('flex-1', side === 'SELL' && 'bg-text text-bg')}
            onClick={onConfirm}
            disabled={action.pending}
          >
            {action.pending ? (
              <>
                <Loader2 className="animate-spin" aria-hidden="true" /> Placing order…
              </>
            ) : copy?.next === 'retry' ? (
              'Try again'
            ) : (
              `Confirm ${side === 'BUY' ? 'buy' : 'sell'}`
            )}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={onReview} noValidate className="space-y-5">
      <ToggleGroup.Root
        type="single"
        value={side}
        onValueChange={(v) => {
          if (!v) return
          setSide(v as OrderSide)
          action.edit()
        }}
        aria-label="Order side"
        className="grid grid-cols-2 gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1"
      >
        <ToggleGroup.Item
          value="BUY"
          className="rounded-[8px] py-2 text-sm font-medium text-muted data-[state=on]:bg-gain data-[state=on]:text-bg"
        >
          Buy
        </ToggleGroup.Item>
        <ToggleGroup.Item
          value="SELL"
          className="rounded-[8px] py-2 text-sm font-medium text-muted data-[state=on]:bg-loss data-[state=on]:text-bg"
        >
          Sell
        </ToggleGroup.Item>
      </ToggleGroup.Root>

      <div className="space-y-1.5">
        <Label htmlFor={accountFieldId}>{side === 'BUY' ? 'Pay from' : 'Pay into'}</Label>
        <Select
          id={accountFieldId}
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

      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <Label htmlFor={qtyFieldId}>Quantity</Label>
          {side === 'SELL' && (
            <span className="text-xs text-muted">
              You hold <span className="num">{held}</span>
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="icon"
            onClick={() => step(-1)}
            aria-label="One fewer share"
          >
            <Minus />
          </Button>
          <input
            id={qtyFieldId}
            inputMode="numeric"
            autoComplete="off"
            value={qty}
            onChange={(e) => {
              setQty(e.target.value.replace(/\D/g, '').slice(0, 5))
              setTouched(true)
            }}
            aria-invalid={shownError ? true : undefined}
            aria-describedby={shownError ? qtyErrorId : undefined}
            className="num h-10 min-w-0 flex-1 rounded-[var(--radius-control)] bg-surface-2 text-center text-lg hairline focus-visible:border-accent focus-visible:outline-none aria-[invalid=true]:border-loss"
          />
          <Button
            variant="secondary"
            size="icon"
            onClick={() => step(1)}
            aria-label="One more share"
          >
            <Plus />
          </Button>
          {side === 'SELL' && held > 0 && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setQty(String(Math.min(held, QTY_MAX)))
                setTouched(true)
              }}
            >
              Max
            </Button>
          )}
        </div>
        <FieldMessage id={qtyErrorId}>{shownError}</FieldMessage>
      </div>

      <dl className="space-y-2 rounded-[var(--radius-control)] bg-surface-2 p-4 text-sm">
        <div className="flex justify-between">
          <dt className="text-muted">Market price</dt>
          <dd>
            <Money value={instrument.price} />
          </dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-muted">Estimated {side === 'BUY' ? 'cost' : 'proceeds'}</dt>
          <dd className="font-medium">
            <Money value={estimate} />
          </dd>
        </div>
        {account && (
          <div className="flex justify-between">
            <dt className="text-muted">Cash available</dt>
            <dd>
              <Money value={account.balance} />
            </dd>
          </div>
        )}
      </dl>
      {cashShort && (
        <p role="alert" className="text-xs text-loss">
          You need {formatINR(cashShort)} more in this account.
        </p>
      )}

      <Button
        type="submit"
        size="lg"
        className={cn('w-full', side === 'SELL' && 'bg-text text-bg')}
        disabled={!!cashShort || (side === 'SELL' && held === 0)}
      >
        Review {side === 'BUY' ? 'buy' : 'sell'}
      </Button>
    </form>
  )
}
