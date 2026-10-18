import { CalendarClock, Loader2, Pause, Play, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { payApi } from '@/api/endpoints.v2'
import { errorMessage } from '@/api/errors'
import { useAccounts, useBeneficiaries } from '@/api/queries'
import type { Frequency, ScheduledTransferDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { accountLabel } from '@/features/accounts/labels'
import { maskShort } from '@/lib/mask'
import { AMOUNT_INPUT_RE, toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { formatDate } from '@/lib/time'
import { Avatar, PayTabs } from './components/PayBits'
import { useInvalidatePayments, useScheduled, useScheduledAction } from './hooks'

const FREQ_LABEL: Record<Frequency, string> = { ONCE: 'Once', WEEKLY: 'Weekly', MONTHLY: 'Monthly' }

function tomorrowIst() {
  const d = new Date(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date()) + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}

/** Scheduled and recurring transfers (F-PAY-9). Runs at 07:00 IST; each run is idempotent. */
export function ScheduledPage() {
  const list = useScheduled()
  const act = useScheduledAction()
  const run = (id: string, action: 'pause' | 'resume' | 'cancel') =>
    act.mutate(
      { id, action },
      {
        onSuccess: () => toast.success(action === 'pause' ? 'Paused' : action === 'resume' ? 'Resumed' : 'Cancelled'),
        onError: (e) => toast.error(errorMessage(e)),
      },
    )

  return (
    <div>
      <PageHeader eyebrow="Pay" title="Scheduled" description="Set it once: rent on the 1st, a weekly allowance, a one-off payment next Friday. If money’s short we retry the next day, then tell you." />
      <PayTabs />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-5">
          <NewSchedule />
        </div>
        <Panel className="lg:col-span-7" eyebrow="Your schedules" title="Upcoming and paused">
          {list.isError ? (
            <PanelError what="schedules" onRetry={() => void list.refetch()} />
          ) : !list.data ? (
            <Skeleton className="h-48 w-full" />
          ) : list.data.length === 0 ? (
            <EmptyState icon={<CalendarClock />} title="Nothing scheduled">Create your first schedule on the left.</EmptyState>
          ) : (
            <ul className="space-y-2">
              {list.data.map((s) => (
                <ScheduleRow key={s.id} s={s} busy={act.isPending} onAction={(a) => run(s.id, a)} />
              ))}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  )
}

function ScheduleRow({ s, busy, onAction }: { s: ScheduledTransferDto; busy: boolean; onAction: (a: 'pause' | 'resume' | 'cancel') => void }) {
  const done = s.status === 'CANCELLED' || s.status === 'COMPLETED'
  return (
    <li className={`flex flex-wrap items-center gap-3 rounded-2xl bg-surface-2/50 p-4 hairline ${done ? 'opacity-60' : ''}`}>
      <Avatar name={s.toName} />
      <div className="min-w-0 flex-1">
        <p className="flex items-center gap-2 text-sm font-medium">
          {s.note ?? `To ${s.toName}`}
          <Badge tone={s.status === 'ACTIVE' ? 'gain' : s.status === 'PAUSED' ? 'warn' : 'neutral'} dot>
            {s.status.toLowerCase()}
          </Badge>
        </p>
        <p className="text-xs text-muted">
          {s.toName} <span className="num">{s.toAccountNumberMasked}</span> · {FREQ_LABEL[s.frequency]}
          {s.nextRunDate && ` · next ${formatDate(s.nextRunDate)}`}
          {s.remainingCount !== null && s.frequency !== 'ONCE' && ` · ${s.remainingCount} left`}
        </p>
        {s.lastRun && (
          <p className={`mt-1 text-xs ${s.lastRun.outcome === 'SUCCEEDED' ? 'text-muted' : 'text-warn'}`}>
            Last run {formatDate(s.lastRun.date)}: {s.lastRun.outcome === 'SUCCEEDED' ? 'sent' : s.lastRun.outcome === 'RETRYING' ? 'retrying tomorrow (low balance)' : 'missed (low balance)'}
          </p>
        )}
      </div>
      <Money value={s.amount} className="font-medium" />
      {!done && (
        <div className="flex gap-1">
          {s.status === 'ACTIVE' ? (
            <Button size="icon-sm" variant="ghost" aria-label="Pause" onClick={() => onAction('pause')} disabled={busy}>
              <Pause />
            </Button>
          ) : (
            <Button size="icon-sm" variant="ghost" aria-label="Resume" onClick={() => onAction('resume')} disabled={busy}>
              <Play />
            </Button>
          )}
          <Button size="icon-sm" variant="ghost" aria-label="Cancel schedule" onClick={() => onAction('cancel')} disabled={busy}>
            <Trash2 />
          </Button>
        </div>
      )}
    </li>
  )
}

function NewSchedule() {
  const accounts = useAccounts()
  const beneficiaries = useBeneficiaries()
  const invalidate = useInvalidatePayments()
  const senders = (accounts.data ?? []).filter((a) => a.status === 'ACTIVE' && (a.type === 'SAVINGS' || a.type === 'CHECKING'))
  const [fromId, setFromId] = useState('')
  const [toBen, setToBen] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [frequency, setFrequency] = useState<Frequency>('MONTHLY')
  const [start, setStart] = useState(tomorrowIst())
  const [count, setCount] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const action = useMoneyAction<ScheduledTransferDto>()
  const from = senders.find((a) => a.id === (fromId || senders[0]?.id))
  const ben = beneficiaries.data?.find((b) => b.id === toBen)

  function review() {
    const e: Record<string, string> = {}
    if (!ben) e.to = 'Pick a saved payee'
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1)) e.amount = 'Enter at least ₹1'
    else if (toDecimal(amount).gt(200000)) e.amount = 'At most ₹2,00,000 per transfer'
    if (!start || start < tomorrowIst()) e.start = 'Pick a date from tomorrow on'
    else if (frequency === 'MONTHLY' && Number(start.slice(8)) > 28) e.start = 'Monthly schedules run on day 1–28'
    if (count && !/^\d{1,3}$/.test(count)) e.count = 'A whole number'
    setErrors(e)
    if (!Object.keys(e).length) action.review()
  }

  if (action.step === 'success' && action.result)
    return (
      <Panel eyebrow="Scheduled" title="All set" tone="cyan">
        <p className="text-sm text-muted">
          <Money value={action.result.amount} className="text-text" /> to {action.result.toName}, {FREQ_LABEL[action.result.frequency].toLowerCase()}, starting {action.result.nextRunDate ? formatDate(action.result.nextRunDate) : '—'}.
        </p>
        <Button
          className="mt-4"
          variant="secondary"
          onClick={() => {
            setAmount('')
            setNote('')
            action.restart()
          }}
        >
          Schedule another
        </Button>
      </Panel>
    )

  return (
    <Panel eyebrow="New" title="Schedule a transfer" tone="cyan">
      {action.step === 'review' && from && ben ? (
        <div className="space-y-5">
          <ReviewAmount value={amount} caption={`${FREQ_LABEL[frequency]} transfer`} />
          <ReviewRows>
            <ReviewRow label="To" emphasis>
              {ben.holderName} <span className="num text-xs text-muted">{maskShort(ben.accountNumber)}</span>
            </ReviewRow>
            <ReviewRow label="From">{accountLabel(from)}</ReviewRow>
            <ReviewRow label="First run">{formatDate(start)} · 07:00 IST</ReviewRow>
            {count && frequency !== 'ONCE' && <ReviewRow label="Runs">{count} times</ReviewRow>}
            {note.trim() && <ReviewRow label="Note">{note.trim()}</ReviewRow>}
          </ReviewRows>
          {action.error && <MoneyErrorAlert copy={moneyErrorCopy(action.error)} />}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={action.edit} disabled={action.pending}>
              Edit
            </Button>
            <Button
              className="flex-1"
              disabled={action.pending}
              onClick={() =>
                void action
                  .confirm((key) =>
                    payApi.createScheduled(
                      {
                        fromAccountId: from.id,
                        toAccountNumber: ben.accountNumber,
                        amount: toDecimal(amount).toFixed(2),
                        startDate: start,
                        frequency,
                        ...(note.trim() ? { note: note.trim() } : {}),
                        ...(count && frequency !== 'ONCE' ? { count: Number(count) } : {}),
                      },
                      key,
                    ),
                  )
                  .then((r) => r.ok && void invalidate())
              }
            >
              {action.pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null} Confirm schedule
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label>To</Label>
            <Select value={toBen} onValueChange={setToBen} placeholder="Choose a saved payee" invalid={!!errors.to} options={(beneficiaries.data ?? []).map((b) => ({ value: b.id, text: b.nickname, label: `${b.nickname} · ${b.holderName}` }))} />
            {errors.to && <p className="text-xs text-loss">{errors.to}</p>}
          </div>
          <div className="space-y-1.5">
            <Label>From</Label>
            <Select value={from?.id ?? ''} onValueChange={setFromId} options={senders.map((a) => ({ value: a.id, text: accountLabel(a), label: accountLabel(a) }))} />
          </div>
          <AmountInput label="Amount" value={amount} onValueChange={setAmount} error={errors.amount} />
          <div className="space-y-1.5">
            <Label>How often</Label>
            <Segmented label="Frequency" value={frequency} onValueChange={setFrequency} options={(['ONCE', 'WEEKLY', 'MONTHLY'] as const).map((f) => ({ value: f, label: FREQ_LABEL[f] }))} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <FormField label={frequency === 'ONCE' ? 'On' : 'Starting'} type="date" min={tomorrowIst()} value={start} onChange={(e) => setStart(e.target.value)} error={errors.start} />
            {frequency !== 'ONCE' && <FormField label="Times (optional)" inputMode="numeric" placeholder="Forever" value={count} onChange={(e) => setCount(e.target.value.replace(/\D/g, '').slice(0, 3))} error={errors.count} />}
          </div>
          <FormField label="Note (optional)" maxLength={80} placeholder="Rent" value={note} onChange={(e) => setNote(e.target.value)} />
          <Button className="w-full" onClick={review}>
            Review
          </Button>
        </div>
      )}
    </Panel>
  )
}
