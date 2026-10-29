import { Copy, CreditCard, Eye, EyeOff, Loader2, Lock, ShoppingBag, Snowflake } from 'lucide-react'
import { Switch } from 'radix-ui'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { cardsApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import { useAccounts } from '@/api/queries'
import type { AccountDto, CardDto, CardRevealDto, MerchantCategory } from '@/api/types'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { accountLabel } from '@/features/accounts/labels'
import { toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { CardPayments, MyDisputes } from './Disputes'
import { DISPUTES, useCardControls, useCards, useIssueCard } from './hooks'
import { CATEGORIES, CATEGORY_LABEL } from './labels'

/** `/cards` (F-CARD-1, F-CARD-2): one virtual debit card per account, its details and controls. */
export function CardsPage() {
  const on = useFlag('cards')
  const cards = useCards(on)
  const accounts = useAccounts()
  const issue = useIssueCard()

  if (!on)
    return (
      <>
        <PageHeader title="Cards" />
        <EmptyState icon={<CreditCard />} title="Cards are switched off right now">
          Existing cards can’t be used while this is off. Your accounts work as usual.
        </EmptyState>
      </>
    )

  const eligible = (accounts.data ?? []).filter(
    (a) => (a.type === 'SAVINGS' || a.type === 'CHECKING') && a.status === 'ACTIVE',
  )
  const withoutCard = eligible.filter((a) => !cards.data?.some((c) => c.accountId === a.id))

  return (
    <div>
      <PageHeader
        title="Cards"
        description="Virtual debit cards for online payments, spending straight from your account. Details stay hidden until you confirm it’s you."
        actions={
          <Button variant="secondary" asChild>
            <Link to="/cards/sandbox">
              <ShoppingBag aria-hidden="true" /> Try the demo store
            </Link>
          </Button>
        }
      />
      {cards.isError ? (
        <Card>
          <PanelError what="your cards" onRetry={() => void cards.refetch()} />
        </Card>
      ) : !cards.data || !accounts.data ? (
        <Skeleton className="h-72" />
      ) : (
        <div className="space-y-6">
          {cards.data.length === 0 && (
            <Card>
              <EmptyState icon={<CreditCard />} title="No cards yet">
                Issue a free virtual card for any savings or checking account below.
              </EmptyState>
            </Card>
          )}
          {cards.data.map((c) => (
            <CardBlock
              key={c.id}
              card={c}
              account={accounts.data.find((a) => a.id === c.accountId)}
            />
          ))}
          {DISPUTES && <MyDisputes />}
          {withoutCard.length > 0 && (
            <Panel
              title="Issue a card"
              eyebrow="New"
              description="One virtual card per account. It works immediately for online payments."
            >
              <ul className="divide-y divide-border">
                {withoutCard.map((a) => (
                  <li key={a.id} className="flex items-center gap-3 py-3 text-sm">
                    <span className="min-w-0 flex-1">{accountLabel(a)}</span>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={issue.isPending}
                      onClick={() =>
                        issue.mutate(a.id, {
                          onSuccess: () => toast.success('Your new card is ready.'),
                          onError: (e) => toast.error(errorMessage(e)),
                        })
                      }
                    >
                      {issue.isPending && issue.variables === a.id && (
                        <Loader2 className="animate-spin" aria-hidden="true" />
                      )}
                      Issue card
                    </Button>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Revealed details live only in this component's state: not in the query cache, not in
 * storage. They're wiped when the countdown ends, when the tab is hidden, and on unmount.
 */
function useReveal(cardId: string) {
  const [details, setDetails] = useState<CardRevealDto | null>(null)
  const [left, setLeft] = useState(0)
  const [pending, setPending] = useState(false)
  const timer = useRef<number | null>(null)

  const hide = useCallback(() => {
    if (timer.current) window.clearInterval(timer.current)
    timer.current = null
    setDetails(null)
    setLeft(0)
  }, [])

  const reveal = useCallback(async () => {
    setPending(true)
    try {
      // 401 STEP_UP_REQUIRED (CARD_REVEAL) opens the PIN dialog in the API client and retries.
      const d = await cardsApi.reveal(cardId)
      const seconds = Math.min(Math.max(d.hideAfterSeconds, 1), 60)
      setDetails(d)
      setLeft(seconds)
      if (timer.current) window.clearInterval(timer.current)
      timer.current = window.setInterval(() => {
        setLeft((s) => {
          if (s <= 1) {
            hide()
            return 0
          }
          return s - 1
        })
      }, 1000)
    } catch (e) {
      const err = toApiError(e)
      if (err.code !== 'STEP_UP_CANCELLED') toast.error(errorMessage(err))
    } finally {
      setPending(false)
    }
  }, [cardId, hide])

  useEffect(() => {
    const onHidden = () => document.visibilityState === 'hidden' && hide()
    document.addEventListener('visibilitychange', onHidden)
    return () => {
      document.removeEventListener('visibilitychange', onHidden)
      hide()
    }
  }, [hide])

  return { details, left, pending, reveal, hide }
}

function CardBlock({ card, account }: { card: CardDto; account?: AccountDto }) {
  const r = useReveal(card.id)
  const frozen = card.controls.frozen
  return (
    <div className="grid gap-4 lg:grid-cols-12">
      <div className="space-y-3 lg:col-span-5">
        <div
          className={cn(
            'relative aspect-[1.586] w-full max-w-sm overflow-hidden rounded-[12px] p-5 text-white shadow-lg',
            'bg-[radial-gradient(120%_120%_at_0%_0%,#6d5dfc_0%,#2a2350_55%,#0d0b1a_100%)]',
            frozen && 'grayscale',
          )}
          aria-label={`Virtual card ending ${card.last4}${frozen ? ', frozen' : ''}`}
          role="group"
        >
          <div className="flex items-start justify-between">
            <span className="font-display text-xl">QuantGen</span>
            {frozen ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-white/15 px-2 py-0.5 text-xs">
                <Snowflake aria-hidden="true" className="size-3" /> Frozen
              </span>
            ) : (
              <span className="text-xs text-white/70">Virtual · Debit</span>
            )}
          </div>
          <p className="num mt-8 text-xl tracking-[0.12em] sm:text-2xl" aria-live="polite">
            {r.details ? r.details.pan : `•••• •••• •••• ${card.last4}`}
          </p>
          <div className="num mt-4 flex gap-8 text-sm">
            <span>
              <span className="block text-[10px] uppercase tracking-wider text-white/60">
                Expires
              </span>
              {card.expiry}
            </span>
            <span>
              <span className="block text-[10px] uppercase tracking-wider text-white/60">CVV</span>
              {r.details ? r.details.cvv : '•••'}
            </span>
          </div>
          {r.details && (
            <span
              className="num absolute bottom-4 right-5 text-xs text-white/70"
              aria-hidden="true"
            >
              hides in {r.left}s
            </span>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {r.details ? (
            <>
              <Button variant="secondary" size="sm" onClick={r.hide}>
                <EyeOff aria-hidden="true" /> Hide now
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() =>
                  void navigator.clipboard
                    ?.writeText((r.details?.pan ?? '').replace(/\s/g, ''))
                    .then(() => toast.success('Card number copied.'))
                }
              >
                <Copy aria-hidden="true" /> Copy number
              </Button>
              <span className="sr-only" role="status">
                Card details shown. They hide automatically in {r.details.hideAfterSeconds} seconds.
              </span>
            </>
          ) : (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void r.reveal()}
              disabled={r.pending || frozen}
            >
              {r.pending ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Eye aria-hidden="true" />
              )}
              Show card details
            </Button>
          )}
        </div>
        <p className="flex items-center gap-1.5 text-xs text-muted">
          <Lock aria-hidden="true" className="size-3" /> Showing details asks for your PIN. The CVV
          changes every 30 seconds.
        </p>
      </div>
      <div className="space-y-4 lg:col-span-7">
        <Controls card={card} account={account} />
        {DISPUTES && <CardPayments card={card} />}
      </div>
    </div>
  )
}

function ToggleRow({
  label,
  description,
  checked,
  disabled,
  onChange,
}: {
  label: string
  description: string
  checked: boolean
  disabled?: boolean
  onChange: (next: boolean) => void
}) {
  const id = useId()
  return (
    <div className="flex items-start gap-4 py-3">
      <div className="min-w-0 flex-1">
        <label htmlFor={id} className="text-sm font-medium">
          {label}
        </label>
        <p id={`${id}-d`} className="text-xs text-muted">
          {description}
        </p>
      </div>
      <Switch.Root
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={onChange}
        aria-describedby={`${id}-d`}
        className="relative h-6 w-11 shrink-0 rounded-full bg-surface-2 hairline transition-colors data-[state=checked]:bg-accent disabled:opacity-50"
      >
        <Switch.Thumb className="block size-5 translate-x-0.5 rounded-full bg-text shadow transition-transform data-[state=checked]:translate-x-[1.375rem] data-[state=checked]:bg-accent-ink" />
      </Switch.Root>
    </div>
  )
}

function Controls({ card, account }: { card: CardDto; account?: AccountDto }) {
  const save = useCardControls(card)
  const [limit, setLimit] = useState(toDecimal(card.controls.perTxnLimit).toFixed(2))
  const [limitError, setLimitError] = useState<string>()
  const c = card.controls
  const apply = (body: Parameters<typeof save.mutate>[0], ok: string) =>
    save.mutate(body, {
      onSuccess: () => toast.success(ok),
      onError: (e) => toast.error(errorMessage(e)),
    })

  function saveLimit() {
    if (
      !/^\d{1,6}(\.\d{1,2})?$/.test(limit) ||
      toDecimal(limit).lt(1) ||
      toDecimal(limit).gt(200000)
    )
      return setLimitError('Between ₹1 and ₹2,00,000')
    setLimitError(undefined)
    apply({ perTxnLimit: toDecimal(limit).toFixed(2) }, 'Limit updated.')
  }
  const toggleCategory = (cat: MerchantCategory) =>
    apply(
      {
        blockedCategories: c.blockedCategories.includes(cat)
          ? c.blockedCategories.filter((x) => x !== cat)
          : [...c.blockedCategories, cat],
      },
      'Blocked categories updated.',
    )

  return (
    <Panel
      title={`Card •••• ${card.last4}`}
      eyebrow={account ? accountLabel(account) : 'Controls'}
      action={
        <Badge tone={card.status === 'ACTIVE' ? 'gain' : 'neutral'} dot>
          {card.status === 'ACTIVE' ? 'Active' : 'Frozen'}
        </Badge>
      }
    >
      {account && (
        <p className="-mt-2 mb-2 text-sm text-muted">
          Spends from <Money value={account.availableBalance ?? account.balance} /> available.
        </p>
      )}
      <div className="divide-y divide-border">
        <ToggleRow
          label="Freeze card"
          description="Declines every new payment until you unfreeze it. Nothing else changes."
          checked={c.frozen}
          disabled={save.isPending}
          onChange={(v) => apply({ frozen: v }, v ? 'Card frozen.' : 'Card unfrozen.')}
        />
        <ToggleRow
          label="Online payments"
          description="Allow payments at online stores."
          checked={c.online}
          disabled={save.isPending}
          onChange={(v) => apply({ online: v }, v ? 'Online payments on.' : 'Online payments off.')}
        />
        <div className="py-3">
          <AmountInput
            label="Limit per payment"
            value={limit}
            onValueChange={(v) => {
              setLimit(v)
              setLimitError(undefined)
            }}
            error={limitError}
            hint="Payments above this are declined."
          />
          <Button
            className="mt-2"
            size="sm"
            variant="secondary"
            disabled={save.isPending || toDecimal(limit || '0').eq(toDecimal(c.perTxnLimit))}
            onClick={saveLimit}
          >
            Save limit
          </Button>
        </div>
        <fieldset className="py-3">
          <legend className="text-sm font-medium">Blocked merchant types</legend>
          <p className="text-xs text-muted">Payments at these merchants are declined.</p>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {CATEGORIES.map((cat) => {
              const blocked = c.blockedCategories.includes(cat)
              return (
                <button
                  key={cat}
                  type="button"
                  aria-pressed={blocked}
                  disabled={save.isPending}
                  onClick={() => toggleCategory(cat)}
                  className={cn(
                    'rounded-full px-3 py-1.5 text-xs hairline transition-colors disabled:opacity-50',
                    blocked ? 'border-loss bg-loss/12 text-loss' : 'text-muted hover:text-text',
                  )}
                >
                  {CATEGORY_LABEL[cat]}
                </button>
              )
            })}
          </div>
        </fieldset>
      </div>
    </Panel>
  )
}
