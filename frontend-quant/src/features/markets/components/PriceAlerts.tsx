import { BellRing, Loader2, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { isUnavailable } from '@/api/availability'
import { errorMessage, toApiError } from '@/api/errors'
import type { MoneyString, PriceAlertDto } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { toDecimal } from '@/lib/money'
import { formatRelative } from '@/lib/time'
import { useCreateAlert, useDeleteAlert, usePriceAlerts } from '../tradingHooks'
import { validateOrderPrice } from '../validation'

function AlertRow({ a, showSymbol }: { a: PriceAlertDto; showSymbol?: boolean }) {
  const del = useDeleteAlert()
  const verb = a.direction === 'ABOVE' ? 'rises above' : 'falls below'
  return (
    <li className="flex items-center gap-3 py-2.5 text-sm">
      <BellRing aria-hidden="true" className="size-4 shrink-0 text-muted" />
      <span className="min-w-0 flex-1">
        {showSymbol && (
          <>
            <Link to={`/markets/${a.symbol}`} className="font-semibold hover:underline">
              {a.symbol}
            </Link>{' '}
          </>
        )}
        {verb} <Money value={a.price} />
        {a.triggeredAt && (
          <span className="block text-xs text-muted">Triggered {formatRelative(a.triggeredAt)}</span>
        )}
      </span>
      <Badge tone={a.status === 'ACTIVE' ? 'accent' : 'neutral'}>
        {a.status === 'ACTIVE' ? 'Watching' : 'Triggered'}
      </Badge>
      <Button
        size="icon"
        variant="ghost"
        disabled={del.isPending}
        onClick={() => del.mutate(a.id)}
        aria-label={`Delete alert: ${a.symbol} ${verb} ${a.price}`}
      >
        <Trash2 />
      </Button>
    </li>
  )
}

/** Instrument page (F-TRD-8): one-shot alerts that notify when the price crosses a level. */
export function PriceAlertsCard({ symbol, price }: { symbol: string; price: MoneyString }) {
  const alerts = usePriceAlerts()
  const create = useCreateAlert()
  const [direction, setDirection] = useState<'ABOVE' | 'BELOW'>('ABOVE')
  const [level, setLevel] = useState('')
  const [touched, setTouched] = useState(false)
  const [serverError, setServerError] = useState<string>()
  if (alerts.isError && isUnavailable(alerts.error)) return null

  const mine = (alerts.data ?? []).filter((a) => a.symbol === symbol)
  const levelError = validateOrderPrice(level)
  const wrongSide =
    !levelError &&
    (direction === 'ABOVE'
      ? toDecimal(level).lte(toDecimal(price))
      : toDecimal(level).gte(toDecimal(price)))
  const shown = touched
    ? (levelError ??
      (wrongSide
        ? `The price is already ${direction === 'ABOVE' ? 'above' : 'below'} that`
        : undefined))
    : undefined

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    setServerError(undefined)
    if (levelError || wrongSide) return
    create.mutate(
      { symbol, direction, price: level },
      {
        onSuccess: () => {
          setLevel('')
          setTouched(false)
        },
        onError: (err) => {
          const ae = toApiError(err)
          setServerError(
            ae.code === 'LIMIT_EXCEEDED' ? 'You can have up to 20 active alerts.' : errorMessage(ae),
          )
        },
      },
    )
  }

  return (
    <Card>
      <h2 className="font-medium">Price alerts</h2>
      <p className="mt-1 text-sm text-muted">
        We notify you once when {symbol} crosses your level.
      </p>
      <form onSubmit={submit} noValidate className="mt-4 space-y-3">
        <Segmented<'ABOVE' | 'BELOW'>
          label="Alert when the price"
          value={direction}
          onValueChange={setDirection}
          options={[
            { value: 'ABOVE', label: 'Rises above' },
            { value: 'BELOW', label: 'Falls below' },
          ]}
        />
        <AmountInput
          label="Price"
          value={level}
          onValueChange={setLevel}
          error={shown}
          hint={
            <>
              Now <Money value={price} />
            </>
          }
        />
        {serverError && (
          <p role="alert" className="text-sm text-loss">
            {serverError}
          </p>
        )}
        <Button type="submit" variant="secondary" disabled={create.isPending}>
          {create.isPending && <Loader2 className="animate-spin" aria-hidden="true" />}
          Set alert
        </Button>
      </form>
      {mine.length > 0 && (
        <ul className="mt-4 divide-y divide-border border-t border-border">
          {mine.map((a) => (
            <AlertRow key={a.id} a={a} />
          ))}
        </ul>
      )}
    </Card>
  )
}

/** Markets page: every alert you've set, across symbols. */
export function AllAlertsList() {
  const alerts = usePriceAlerts()
  if (!alerts.data || alerts.data.length === 0) return null
  return (
    <Card className="mt-4">
      <h2 className="font-medium">Your price alerts</h2>
      <ul className="mt-2 divide-y divide-border">
        {alerts.data.map((a) => (
          <AlertRow key={a.id} a={a} showSymbol />
        ))}
      </ul>
    </Card>
  )
}
