import { ArrowDownLeft, ArrowUpRight, Coins, Landmark, Loader2, Plus, Target } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { payApi, savingsApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useAccounts, useInvalidateMoney } from '@/api/queries'
import type { AccountDto, FixedDepositDto, TransferDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { ProgressRing } from '@/components/ProgressRing'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SpotlightCard } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { accountLabel } from '@/features/accounts/labels'
import { useGoalProgress } from '@/features/analytics/hooks'
import { AMOUNT_INPUT_RE, formatINR, ratio, sumMoney, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { daysBetween, formatDate, todayIST } from '@/lib/time'
import { useFdRates, useFixedDeposits, useRoundUp } from './hooks'

const EMOJI = ['🏖️', '🛟', '🏠', '🚗', '🎓', '💍', '✈️', '🎁', '💻', '🌱']

/** Savings (PRD §11.4.4): goals, round-ups and fixed deposits. All money moves via the ledger. */
export function SavingsPage() {
  const accounts = useAccounts()
  const fds = useFixedDeposits()
  const progress = useGoalProgress()
  const [creating, setCreating] = useState(false)
  const [moving, setMoving] = useState<{ goal: AccountDto; direction: 'in' | 'out' } | null>(null)
  const [booking, setBooking] = useState(false)

  const goals = (accounts.data ?? []).filter((a) => a.type === 'GOAL')
  const cash = (accounts.data ?? []).filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING')
  const saved = sumMoney([...goals.map((g) => g.balance), ...(fds.data ?? []).filter((f) => f.status === 'ACTIVE').map((f) => f.principal)])

  return (
    <div>
      <PageHeader
        eyebrow="Savings"
        title={
          <>
            Grow it <em className="text-gradient">on purpose.</em>
          </>
        }
        description="Goals keep money ring-fenced from everyday spending. Fixed deposits earn more for money you won’t touch."
        actions={
          <>
            <Button variant="secondary" onClick={() => setBooking(true)}>
              <Landmark aria-hidden="true" /> Book an FD
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Plus aria-hidden="true" /> New goal
            </Button>
          </>
        }
      />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Saved in total" value={<Money value={saved} />} />
        <Stat label="Goals" value={goals.length} />
        <Stat label="Fixed deposits" value={(fds.data ?? []).filter((f) => f.status === 'ACTIVE').length} />
        <Stat label="Interest accrued" value={<Money value={sumMoney((fds.data ?? []).filter((f) => f.status === 'ACTIVE').map((f) => f.accruedInterest))} className="text-gain" />} />
      </div>

      <section aria-labelledby="goals-h">
        <h2 id="goals-h" className="eyebrow mb-3">
          Goals
        </h2>
        {accounts.isError ? (
          <PanelError what="your goals" onRetry={() => void accounts.refetch()} />
        ) : !accounts.data ? (
          <Skeleton className="h-48 w-full" />
        ) : goals.length === 0 ? (
          <Panel title="No goals yet">
            <EmptyState icon={<Target />} title="What are you saving for?" action={<Button onClick={() => setCreating(true)}>Create a goal</Button>}>
              A trip, a laptop, an emergency fund. Each goal is its own ring-fenced account.
            </EmptyState>
          </Panel>
        ) : (
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {goals.map((g) => {
              const p = progress.data?.find((x) => x.accountId === g.id)
              const target = g.goal?.target ?? '0'
              return (
                <li key={g.id}>
                  <SpotlightCard tone="cyan" className="flex h-full flex-col">
                    <div className="flex items-start gap-4">
                      <ProgressRing value={ratio(g.balance, target)} label={`${Math.round(ratio(g.balance, target) * 100)}% of target`} size={88} caption="saved" />
                      <div className="min-w-0 flex-1">
                        <p className="text-2xl" aria-hidden="true">
                          {g.goal?.emoji ?? '🎯'}
                        </p>
                        <p className="truncate font-medium">{g.goal?.name ?? 'Goal'}</p>
                        <p className="text-sm text-muted">
                          <Money value={g.balance} className="text-text" /> of <Money value={target} />
                        </p>
                        {p && (
                          <Badge tone={p.onTrack ? 'gain' : 'warn'} dot className="mt-2">
                            {p.onTrack ? 'On track' : 'Behind'}
                            {p.projectedDate && ` · ${formatDate(p.projectedDate)}`}
                          </Badge>
                        )}
                      </div>
                    </div>
                    {g.goal?.targetDate && <p className="mt-4 text-xs text-muted">Target date {formatDate(g.goal.targetDate)}</p>}
                    <RoundUpNote goalId={g.id} cash={cash} />
                    <div className="mt-auto flex gap-2 pt-5">
                      <Button size="sm" className="flex-1" onClick={() => setMoving({ goal: g, direction: 'in' })}>
                        <ArrowDownLeft aria-hidden="true" /> Add
                      </Button>
                      <Button size="sm" variant="secondary" className="flex-1" onClick={() => setMoving({ goal: g, direction: 'out' })} disabled={toDecimal(g.balance).isZero()}>
                        <ArrowUpRight aria-hidden="true" /> Withdraw
                      </Button>
                    </div>
                  </SpotlightCard>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-7">
          <FdList />
        </div>
        <div className="lg:col-span-5">
          <RoundUpPanel cash={cash} goals={goals} />
        </div>
      </div>

      {creating && <CreateGoal onClose={() => setCreating(false)} />}
      {moving && <GoalMove goal={moving.goal} direction={moving.direction} cash={cash} onClose={() => setMoving(null)} />}
      {booking && <BookFd cash={cash} onClose={() => setBooking(false)} />}
    </div>
  )
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-[var(--radius-card)] bg-surface p-4 hairline">
      <p className="text-xs text-muted">{label}</p>
      <p className="num mt-1.5 text-xl font-medium">{value}</p>
    </div>
  )
}

function RoundUpNote({ goalId, cash }: { goalId: string; cash: AccountDto[] }) {
  // Only shown when some cash account rounds up into this goal.
  const a = useRoundUp(cash[0]?.id ?? '')
  const b = useRoundUp(cash[1]?.id ?? '')
  const feeding = [a.data, b.data].filter((r) => r?.enabled && r.goalAccountId === goalId)
  if (!feeding.length) return null
  return (
    <p className="mt-3 flex items-center gap-1.5 text-xs text-muted">
      <Coins aria-hidden="true" className="size-3.5 text-accent" /> Round-ups to ₹{feeding[0]?.roundTo} feed this goal
    </p>
  )
}

function CreateGoal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [target, setTarget] = useState('')
  const [date, setDate] = useState('')
  const [emoji, setEmoji] = useState(EMOJI[0] ?? '🎯')
  const [error, setError] = useState<string | null>(null)
  const create = useMutation({ mutationFn: savingsApi.createGoal })

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!name.trim()) return setError('Name your goal.')
    if (!AMOUNT_INPUT_RE.test(target) || toDecimal(target).lt(100)) return setError('Set a target of at least ₹100.')
    create.mutate(
      { type: 'GOAL', name: name.trim(), target: toDecimal(target).toFixed(2), emoji, ...(date ? { targetDate: date } : {}) },
      {
        onSuccess: () => {
          toast.success('Goal created')
          void qc.invalidateQueries({ queryKey: ['accounts'] })
          void qc.invalidateQueries({ queryKey: ['me-analytics'] })
          onClose()
        },
        onError: (err) => setError(toApiError(err).fieldErrors[0]?.message ?? errorMessage(err)),
      },
    )
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="New goal" description="It gets its own account, separate from everyday money.">
        <form onSubmit={submit} className="space-y-4" noValidate>
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Icon">
            {EMOJI.map((e) => (
              <button key={e} type="button" role="radio" aria-checked={emoji === e} onClick={() => setEmoji(e)} className={`grid size-10 place-items-center rounded-xl text-xl hairline ${emoji === e ? 'border-accent bg-accent/10' : 'bg-surface-2'}`}>
                {e}
              </button>
            ))}
          </div>
          <FormField label="Name" maxLength={40} placeholder="Goa in December" value={name} onChange={(e) => setName(e.target.value)} />
          <AmountInput label="Target" value={target} onValueChange={setTarget} />
          <FormField label="Target date (optional)" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          {error && <p role="alert" className="text-sm text-loss">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              Create goal
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** Add to / withdraw from a goal: a normal own-account transfer (idempotent, no step-up). */
function GoalMove({ goal, direction, cash, onClose }: { goal: AccountDto; direction: 'in' | 'out'; cash: AccountDto[]; onClose: () => void }) {
  const [otherId, setOtherId] = useState(cash[0]?.id ?? '')
  const [amount, setAmount] = useState('')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const action = useMoneyAction<TransferDto>()
  const invalidate = useInvalidateMoney()
  const other = cash.find((a) => a.id === otherId)
  const from = direction === 'in' ? other : goal
  const to = direction === 'in' ? goal : other
  const fromAvail = from ? (from.availableBalance ?? from.balance) : '0'
  const name = goal.goal?.name ?? 'goal'

  function review() {
    setFieldError(null)
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1)) return setFieldError('Enter at least ₹1')
    if (toDecimal(amount).gt(fromAvail)) return setFieldError(`Only ${formatINR(fromAvail)} available`)
    action.review()
  }

  return (
    <Dialog open onOpenChange={(o) => !o && !action.pending && onClose()}>
      <DialogContent title={direction === 'in' ? `Add to ${name}` : `Withdraw from ${name}`}>
        {action.step === 'success' ? (
          <div className="space-y-4 py-2 text-center">
            <SuccessCheck label="Moved" />
            <p className="font-display text-4xl">{formatINR(amount)}</p>
            <Button className="w-full" onClick={onClose}>
              Done
            </Button>
          </div>
        ) : action.step === 'review' && from && to ? (
          <div className="space-y-5">
            <ReviewAmount value={amount} caption={direction === 'in' ? 'Into your goal' : 'Back to everyday money'} />
            <ReviewRows>
              <ReviewRow label="From">{accountLabel(from)}</ReviewRow>
              <ReviewRow label="To">{accountLabel(to)}</ReviewRow>
            </ReviewRows>
            {action.error && <MoneyErrorAlert copy={moneyErrorCopy(action.error, { amount, balance: fromAvail })} />}
            <div className="flex gap-2">
              <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
                Edit
              </Button>
              <Button
                className="flex-1"
                disabled={action.pending}
                onClick={() =>
                  void action
                    .confirm((key) => payApi.transfer({ fromAccountId: from.id, toAccountNumber: to.accountNumber, amount: toDecimal(amount).toFixed(2), note: direction === 'in' ? `Saved to ${name}` : `From ${name}` }, key))
                    .then((r) => r.ok && void invalidate('accounts', 'transfers'))
                }
              >
                {action.pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null} Confirm
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>{direction === 'in' ? 'From' : 'To'}</Label>
              <Select aria-label={direction === 'in' ? 'From' : 'To'} value={otherId} onValueChange={setOtherId} options={cash.map((a) => ({ value: a.id, text: accountLabel(a), label: <span className="flex w-full justify-between gap-3">{accountLabel(a)} <Money value={a.availableBalance ?? a.balance} className="text-muted" /></span> }))} />
            </div>
            <AmountInput label="Amount" value={amount} onValueChange={setAmount} error={fieldError ?? undefined} autoFocus hint={<>Available <Money value={fromAvail} className="text-text" /></>} />
            <Button className="w-full" onClick={review}>
              Review
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function RoundUpPanel({ cash, goals }: { cash: AccountDto[]; goals: AccountDto[] }) {
  const qc = useQueryClient()
  const [accountId, setAccountId] = useState(cash[0]?.id ?? '')
  const current = useRoundUp(accountId)
  const save = useMutation({
    mutationFn: (b: { enabled: boolean; roundTo: 10 | 50 | 100; goalAccountId: string }) => savingsApi.setRoundUp(accountId, b),
    onSuccess: () => {
      toast.success('Round-ups updated')
      void qc.invalidateQueries({ queryKey: ['savings', 'round-up'] })
    },
    onError: (e) => toast.error(toApiError(e).fieldErrors[0]?.message ?? errorMessage(e)),
  })
  const r = current.data
  return (
    <Panel eyebrow="Spare change" title="Round-ups" description="Every spend rounds up; the change is swept into a goal once a day.">
      {goals.length === 0 ? (
        <p className="text-sm text-muted">Create a goal first.</p>
      ) : (
        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label>Round up spends from</Label>
            <Select aria-label="Round up spends from" value={accountId} onValueChange={setAccountId} options={cash.map((a) => ({ value: a.id, text: accountLabel(a), label: accountLabel(a) }))} />
          </div>
          {!r ? (
            <Skeleton className="h-28 w-full" />
          ) : (
            <>
              <div className="space-y-1.5">
                <Label>Round to the next</Label>
                <Segmented label="Round to" value={String(r.roundTo) as '10' | '50' | '100'} onValueChange={(v) => save.mutate({ enabled: r.enabled, roundTo: Number(v) as 10 | 50 | 100, goalAccountId: r.goalAccountId || (goals[0]?.id ?? '') })} options={[{ value: '10', label: '₹10' }, { value: '50', label: '₹50' }, { value: '100', label: '₹100' }]} />
              </div>
              <div className="space-y-1.5">
                <Label>Into</Label>
                <Select aria-label="Into" value={r.goalAccountId || (goals[0]?.id ?? '')} onValueChange={(v) => save.mutate({ enabled: r.enabled, roundTo: r.roundTo, goalAccountId: v })} options={goals.map((g) => ({ value: g.id, text: g.goal?.name ?? 'Goal', label: `${g.goal?.emoji ?? '🎯'} ${g.goal?.name ?? 'Goal'}` }))} />
              </div>
              <Button variant={r.enabled ? 'secondary' : 'primary'} disabled={save.isPending} onClick={() => save.mutate({ enabled: !r.enabled, roundTo: r.roundTo, goalAccountId: r.goalAccountId || (goals[0]?.id ?? '') })}>
                <Coins aria-hidden="true" /> {r.enabled ? 'Turn off round-ups' : 'Turn on round-ups'}
              </Button>
              <p className="text-xs text-muted">Example: a ₹243 bill rounds to ₹250, so ₹7 goes to your goal.</p>
            </>
          )}
        </div>
      )}
    </Panel>
  )
}

function FdList() {
  const fds = useFixedDeposits()
  const [closing, setClosing] = useState<FixedDepositDto | null>(null)
  return (
    <Panel eyebrow="Fixed deposits" title="Locked in, earning more">
      {fds.isError ? (
        <PanelError what="deposits" onRetry={() => void fds.refetch()} />
      ) : !fds.data ? (
        <Skeleton className="h-32 w-full" />
      ) : fds.data.length === 0 ? (
        <EmptyState icon={<Landmark />} title="No fixed deposits">Book one from the top of the page.</EmptyState>
      ) : (
        <ul className="space-y-2">
          {fds.data.map((f) => {
            const held = Math.max(0, daysBetween(f.startDate, todayIST()))
            return (
              <li key={f.id} className="rounded-2xl bg-surface-2/50 p-4 hairline">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <p className="flex items-center gap-2 font-medium">
                      <Money value={f.principal} /> <Badge tone="cyan">{f.annualRate}% p.a.</Badge>
                      {f.status !== 'ACTIVE' && <Badge>{f.status.toLowerCase()}</Badge>}
                    </p>
                    <p className="text-xs text-muted">
                      {f.accountNumberMasked} · {f.tenureDays} days · matures {formatDate(f.maturityDate)} · {f.onMaturity === 'RENEW' ? 'auto-renews' : 'pays out'}
                    </p>
                  </div>
                  <div className="text-right text-sm">
                    <p>
                      <Money value={f.maturityAmount} className="font-medium" />
                    </p>
                    <p className="text-xs text-gain">
                      +<Money value={f.accruedInterest} /> so far
                    </p>
                  </div>
                </div>
                <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-surface-3">
                  <div className="h-full rounded-full bg-chart-3" style={{ width: `${Math.min(100, (held / f.tenureDays) * 100)}%` }} />
                </div>
                {f.status === 'ACTIVE' && (
                  <Button size="sm" variant="ghost" className="mt-2 -ml-2" onClick={() => setClosing(f)}>
                    Close early
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {closing && <CloseFd fd={closing} onClose={() => setClosing(null)} />}
    </Panel>
  )
}

function CloseFd({ fd, onClose }: { fd: FixedDepositDto; onClose: () => void }) {
  const action = useMoneyAction<FixedDepositDto>()
  const qc = useQueryClient()
  return (
    <Dialog open onOpenChange={(o) => !o && !action.pending && onClose()}>
      <DialogContent title="Close this deposit early?" description="You’ll get the rate for the time it was held, minus 1%. The money returns to the account it came from.">
        {action.step === 'success' && action.result ? (
          <div className="space-y-4 text-center">
            <SuccessCheck label="Deposit closed" />
            <p className="text-sm text-muted">
              <Money value={action.result.maturityAmount} className="text-text" /> returned, including <Money value={action.result.accruedInterest} /> interest.
            </p>
            <Button className="w-full" onClick={onClose}>
              Done
            </Button>
          </div>
        ) : (
          <div className="space-y-4">
            <ReviewRows>
              <ReviewRow label="Principal">
                <Money value={fd.principal} />
              </ReviewRow>
              <ReviewRow label="Booked rate">{fd.annualRate}% p.a.</ReviewRow>
            </ReviewRows>
            {action.error && <MoneyErrorAlert copy={moneyErrorCopy(action.error)} />}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={onClose} disabled={action.pending}>
                Keep it
              </Button>
              <Button
                variant="destructive"
                disabled={action.pending}
                onClick={() => {
                  action.review()
                  void action.confirm((key) => savingsApi.closeFd(fd.id, key)).then((r) => {
                    if (r.ok) {
                      void qc.invalidateQueries({ queryKey: ['savings'] })
                      void qc.invalidateQueries({ queryKey: ['accounts'] })
                    }
                  })
                }}
              >
                {action.pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null} Close deposit
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

const TENURES = [
  { days: 30, label: '1 month' },
  { days: 90, label: '3 months' },
  { days: 180, label: '6 months' },
  { days: 365, label: '1 year' },
  { days: 730, label: '2 years' },
  { days: 1825, label: '5 years' },
]

function BookFd({ cash, onClose }: { cash: AccountDto[]; onClose: () => void }) {
  const rates = useFdRates()
  const qc = useQueryClient()
  const [fromId, setFromId] = useState(cash[0]?.id ?? '')
  const [amount, setAmount] = useState('')
  const [days, setDays] = useState(365)
  const [onMaturity, setOnMaturity] = useState<'PAYOUT' | 'RENEW'>('PAYOUT')
  const [fieldError, setFieldError] = useState<string | null>(null)
  const action = useMoneyAction<FixedDepositDto>()
  const from = cash.find((a) => a.id === fromId)
  const rate = rates.data?.find((r) => days >= r.minDays && days <= r.maxDays)?.annualRate
  // Indicative only (simple interest for display); the server computes the real figure.
  const estimate = rate && AMOUNT_INPUT_RE.test(amount) ? toDecimal(amount).times(rate).div(100).times(days).div(365).toDecimalPlaces(2) : null

  function review() {
    setFieldError(null)
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1000)) return setFieldError('At least ₹1,000')
    if (toDecimal(amount).gt(1000000)) return setFieldError('At most ₹10,00,000')
    action.review()
  }

  return (
    <Dialog open onOpenChange={(o) => !o && !action.pending && onClose()}>
      <DialogContent title="Book a fixed deposit" description="A fixed rate for a fixed term. Closing early pays the rate for the time held, minus 1%." className="max-w-lg">
        {action.step === 'success' && action.result ? (
          <div className="space-y-4 text-center">
            <SuccessCheck label="Deposit booked" />
            <p className="font-display text-4xl">{formatINR(action.result.principal)}</p>
            <p className="text-sm text-muted">
              at {action.result.annualRate}% p.a., maturing {formatDate(action.result.maturityDate)} at <Money value={action.result.maturityAmount} className="text-text" />.
            </p>
            <Button className="w-full" onClick={onClose}>
              Done
            </Button>
          </div>
        ) : action.step === 'review' && from && rate ? (
          <div className="space-y-5">
            <ReviewAmount value={amount} caption={`Fixed for ${TENURES.find((t) => t.days === days)?.label}`} />
            <ReviewRows>
              <ReviewRow label="Rate" emphasis>
                {rate}% p.a.
              </ReviewRow>
              <ReviewRow label="From">{accountLabel(from)}</ReviewRow>
              {estimate && (
                <ReviewRow label="Estimated interest">
                  <Money value={estimate} />
                </ReviewRow>
              )}
              <ReviewRow label="At maturity">{onMaturity === 'RENEW' ? 'Renew automatically' : 'Pay out to this account'}</ReviewRow>
            </ReviewRows>
            {action.error && <MoneyErrorAlert copy={moneyErrorCopy(action.error, { amount, balance: from.availableBalance ?? from.balance })} />}
            <div className="flex gap-2">
              <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
                Edit
              </Button>
              <Button
                className="flex-1"
                disabled={action.pending}
                onClick={() =>
                  void action.confirm((key) => savingsApi.bookFd({ fromAccountId: from.id, amount: toDecimal(amount).toFixed(2), tenureDays: days, onMaturity }, key)).then((r) => {
                    if (r.ok) {
                      void qc.invalidateQueries({ queryKey: ['savings'] })
                      void qc.invalidateQueries({ queryKey: ['accounts'] })
                    }
                  })
                }
              >
                {action.pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null} Book deposit
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>From</Label>
              <Select aria-label="From" value={fromId} onValueChange={setFromId} options={cash.map((a) => ({ value: a.id, text: accountLabel(a), label: <span className="flex w-full justify-between gap-3">{accountLabel(a)} <Money value={a.availableBalance ?? a.balance} className="text-muted" /></span> }))} />
            </div>
            <AmountInput label="Amount" value={amount} onValueChange={setAmount} error={fieldError ?? undefined} />
            <div className="space-y-1.5">
              <Label>Tenure</Label>
              <div className="grid grid-cols-3 gap-2">
                {TENURES.map((t) => {
                  const r = rates.data?.find((x) => t.days >= x.minDays && t.days <= x.maxDays)?.annualRate
                  return (
                    <button key={t.days} type="button" aria-pressed={days === t.days} onClick={() => setDays(t.days)} className={`rounded-xl p-2.5 text-left text-sm hairline transition-colors ${days === t.days ? 'border-accent bg-accent/10' : 'bg-surface-2 hover:border-border-strong'}`}>
                      <span className="block font-medium">{t.label}</span>
                      <span className="num text-xs text-muted">{r ? `${r}%` : '—'}</span>
                    </button>
                  )
                })}
              </div>
            </div>
            <div className="space-y-1.5">
              <Label>At maturity</Label>
              <Segmented label="On maturity" value={onMaturity} onValueChange={setOnMaturity} options={[{ value: 'PAYOUT', label: 'Pay out' }, { value: 'RENEW', label: 'Renew' }]} />
            </div>
            {estimate && <p className="text-sm text-muted">You’d earn about <Money value={estimate} className="text-gain" /> (indicative).</p>}
            <Button className="w-full" onClick={review} disabled={!rate}>
              Review
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
