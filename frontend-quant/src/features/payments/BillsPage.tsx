import { Droplets, Flame, Loader2, Receipt, ReceiptText, Satellite, ShieldPlus, Smartphone, Wifi, Zap, CreditCard, type LucideIcon } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { payApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useAccounts } from '@/api/queries'
import type { BillDto, BillerCategory, BillerDto, BillPaymentDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { ReviewAmount, ReviewRow, ReviewRows } from '@/components/money/Review'
import { SuccessCheck } from '@/components/money/SuccessCheck'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { accountLabel } from '@/features/accounts/labels'
import { toDecimal } from '@/lib/money'
import { useMoneyAction } from '@/lib/moneyAction'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { formatDate, formatRelative } from '@/lib/time'
import { cn } from '@/lib/utils'
import { PayTabs } from './components/PayBits'
import { useBillers, useBillPayments, useInvalidatePayments } from './hooks'

const CAT: Record<BillerCategory, { label: string; icon: LucideIcon }> = {
  ELECTRICITY: { label: 'Electricity', icon: Zap },
  MOBILE: { label: 'Mobile', icon: Smartphone },
  BROADBAND: { label: 'Broadband', icon: Wifi },
  DTH: { label: 'DTH', icon: Satellite },
  GAS: { label: 'Gas', icon: Flame },
  WATER: { label: 'Water', icon: Droplets },
  CREDIT_CARD: { label: 'Credit card', icon: CreditCard },
  INSURANCE: { label: 'Insurance', icon: ShieldPlus },
}

/** Bill payments (F-PAY-11): pick a biller → fetch the bill → review → pay. Simulated billers. */
export function BillsPage() {
  const billers = useBillers()
  const history = useBillPayments()
  const [category, setCategory] = useState<BillerCategory | null>(null)
  const [biller, setBiller] = useState<BillerDto | null>(null)

  const shown = (billers.data ?? []).filter((b) => !category || b.category === category)
  return (
    <div>
      <PageHeader eyebrow="Pay" title="Bills" description="Electricity, mobile, broadband and more. Billers are simulated for the demo; the money movement is real ledger." />
      <PayTabs />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="space-y-4 lg:col-span-7">
          <div className="flex flex-wrap gap-2" role="group" aria-label="Bill category">
            {(Object.keys(CAT) as BillerCategory[]).map((c) => {
              const { label, icon: Icon } = CAT[c]
              return (
                <button
                  key={c}
                  type="button"
                  aria-pressed={category === c}
                  onClick={() => setCategory((x) => (x === c ? null : c))}
                  className={cn('flex items-center gap-2 rounded-full px-3.5 py-2 text-sm hairline transition-colors', category === c ? 'bg-text text-bg' : 'text-muted hover:text-text')}
                >
                  <Icon aria-hidden="true" className="size-4" /> {label}
                </button>
              )
            })}
          </div>
          {billers.isError ? (
            <PanelError what="billers" onRetry={() => void billers.refetch()} />
          ) : !billers.data ? (
            <Skeleton className="h-64 w-full" />
          ) : (
            <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {shown.map((b) => {
                const Icon = CAT[b.category].icon
                return (
                  <li key={b.id}>
                    <button
                      type="button"
                      onClick={() => setBiller(b)}
                      className={cn('flex w-full items-center gap-3 rounded-2xl bg-surface p-4 text-left hairline transition-colors hover:border-border-strong', biller?.id === b.id && 'border-accent')}
                    >
                      <span className="grid size-10 place-items-center rounded-xl bg-surface-2 text-muted">
                        <Icon aria-hidden="true" className="size-5" />
                      </span>
                      <span>
                        <span className="block text-sm font-medium">{b.name}</span>
                        <span className="text-xs text-muted">{CAT[b.category].label}</span>
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
        <div className="space-y-4 lg:col-span-5">
          {biller ? <PayBill key={biller.id} biller={biller} /> : <Panel title="Pay a bill"><EmptyState icon={<ReceiptText />} title="Pick a biller">Choose who you’re paying on the left.</EmptyState></Panel>}
          <Panel eyebrow="History" title="Recent bill payments">
            {!history.data ? (
              <Skeleton className="h-24 w-full" />
            ) : history.data.content.length === 0 ? (
              <p className="py-3 text-sm text-muted">No bills paid yet.</p>
            ) : (
              <ul className="divide-y divide-border">
                {history.data.content.map((p: BillPaymentDto) => (
                  <li key={p.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                    <span>
                      {p.billerName} <span className="num text-xs text-muted">{p.consumerNumberMasked}</span>
                      <span className="block text-xs text-muted">{formatRelative(p.createdAt)}</span>
                    </span>
                    <Money value={p.amount} tone="debit" />
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>
    </div>
  )
}

function PayBill({ biller }: { biller: BillerDto }) {
  const accounts = useAccounts()
  const invalidate = useInvalidatePayments()
  const senders = (accounts.data ?? []).filter((a) => a.status === 'ACTIVE' && (a.type === 'SAVINGS' || a.type === 'CHECKING'))
  const [consumer, setConsumer] = useState('')
  const [bill, setBill] = useState<BillDto | null>(null)
  const [fetching, setFetching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fromId, setFromId] = useState('')
  const action = useMoneyAction<BillPaymentDto>()
  const from = senders.find((a) => a.id === (fromId || senders[0]?.id))

  async function fetchBill(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!new RegExp(biller.consumerNumberPattern).test(consumer.trim())) return setError(`That doesn’t look like a valid ${biller.consumerNumberLabel.toLowerCase()}.`)
    setFetching(true)
    try {
      setBill(await payApi.fetchBill(biller.id, consumer.trim()))
    } catch (err) {
      const ae = toApiError(err)
      setError(ae.fieldErrors[0]?.message ? `This ${ae.fieldErrors[0].message}.` : errorMessage(ae))
    } finally {
      setFetching(false)
    }
  }

  if (action.step === 'success' && action.result)
    return (
      <Panel title={biller.name}>
        <div className="space-y-4 py-2 text-center">
          <SuccessCheck label="Bill paid" />
          <p className="font-display text-4xl">
            <Money value={action.result.amount} />
          </p>
          <p className="num text-xs text-muted">Receipt {action.result.id.slice(0, 8).toUpperCase()}</p>
        </div>
      </Panel>
    )

  return (
    <Panel eyebrow={CAT[biller.category].label} title={biller.name} tone="cyan">
      {!bill ? (
        <form onSubmit={(e) => void fetchBill(e)} className="space-y-4" noValidate>
          <FormField label={biller.consumerNumberLabel} value={consumer} autoComplete="off" onChange={(e) => setConsumer(e.target.value.toUpperCase())} error={error ?? undefined} />
          <Button type="submit" className="w-full" disabled={fetching}>
            {fetching ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Receipt aria-hidden="true" />} Fetch bill
          </Button>
        </form>
      ) : (
        <div className="space-y-5">
          <ReviewAmount value={toDecimal(bill.amount).toFixed(2)} caption="Amount due" />
          <ReviewRows>
            <ReviewRow label="Name on bill">{bill.customerNameMasked}</ReviewRow>
            <ReviewRow label="Due">{formatDate(bill.dueDate)}</ReviewRow>
          </ReviewRows>
          {action.step === 'form' && (
            <div className="space-y-1.5">
              <Label>Pay from</Label>
              <Select aria-label="Pay from" value={from?.id ?? ''} onValueChange={setFromId} options={senders.map((a) => ({ value: a.id, text: accountLabel(a), label: <span className="flex w-full justify-between gap-3">{accountLabel(a)}<Money value={a.availableBalance ?? a.balance} className="text-muted" /></span> }))} />
            </div>
          )}
          {action.error && <MoneyErrorAlert copy={moneyErrorCopy(action.error, { amount: bill.amount, balance: from?.availableBalance ?? from?.balance })} />}
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => (action.step === 'review' ? action.edit() : setBill(null))} disabled={action.pending}>
              Back
            </Button>
            <Button
              className="flex-1"
              disabled={!from || action.pending}
              onClick={() => {
                if (action.step === 'form') action.review()
                else if (from) void action.confirm((key) => payApi.payBill(bill.billId, from.id, key)).then((r) => r.ok && void invalidate())
              }}
            >
              {action.pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              {action.step === 'form' ? 'Review' : <>Pay <Money value={bill.amount} /></>}
            </Button>
          </div>
        </div>
      )}
    </Panel>
  )
}
