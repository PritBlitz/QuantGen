import { ArrowDownToLine, Ban, Send } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { payApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useAccounts } from '@/api/queries'
import type { PaymentRequestDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { TabPanel, Tabs } from '@/components/ui/tabs'
import { useMe } from '@/features/security/hooks'
import { AMOUNT_INPUT_RE, toDecimal } from '@/lib/money'
import { formatRelative } from '@/lib/time'
import { Avatar, PayTabs } from './components/PayBits'
import { PayRequestDialog } from './components/PayRequestDialog'
import { useInvalidatePayments, useRequests } from './hooks'

const STATUS: Record<PaymentRequestDto['status'], { label: string; tone: BadgeTone }> = {
  OPEN: { label: 'Open', tone: 'accent' },
  PAID: { label: 'Paid', tone: 'gain' },
  DECLINED: { label: 'Declined', tone: 'loss' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
}

/** Request money (F-PAY-8): ask a handle, pay or decline what others ask you for. */
export function RequestsPage() {
  const me = useMe()
  const accounts = useAccounts()
  const [tab, setTab] = useState<'in' | 'out'>('in')
  const incoming = useRequests('INCOMING')
  const outgoing = useRequests('OUTGOING')
  const invalidate = useInvalidatePayments()
  const [paying, setPaying] = useState<PaymentRequestDto | null>(null)
  const decline = useMutation({
    mutationFn: ({ id, block }: { id: string; block: boolean }) => payApi.declineRequest(id, block),
    onSuccess: (_d, v) => {
      toast.success(v.block ? 'Declined and blocked' : 'Declined')
      void invalidate()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <div>
      <PageHeader
        eyebrow="Pay"
        title="Requests"
        description="Ask for money with a handle. Requests expire after 7 days and you can block anyone."
      />
      <PayTabs />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <div className="lg:col-span-5">
          {me.data && !me.data.handle ? (
            <NoHandle />
          ) : (
            <NewRequest onSent={() => void invalidate()} />
          )}
        </div>
        <div className="lg:col-span-7">
          <Tabs
            label="Requests"
            value={tab}
            onValueChange={setTab}
            tabs={[
              {
                value: 'in',
                label: 'To pay',
                badge: incoming.data?.content.filter((r) => r.status === 'OPEN').length ? (
                  <Badge tone="accent">
                    {incoming.data.content.filter((r) => r.status === 'OPEN').length}
                  </Badge>
                ) : undefined,
              },
              { value: 'out', label: 'You asked' },
            ]}
          >
            <TabPanel value="in">
              <RequestList
                q={incoming}
                who={(r) => r.fromHandle}
                empty="Nobody has asked you for money."
                actions={(r) =>
                  r.status === 'OPEN' && (
                    <div className="flex gap-1.5">
                      <Button size="sm" onClick={() => setPaying(r)}>
                        Pay
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => decline.mutate({ id: r.id, block: false })}
                        disabled={decline.isPending}
                      >
                        Decline
                      </Button>
                      <Button
                        size="icon-sm"
                        variant="ghost"
                        aria-label={`Decline and block ${r.fromHandle}`}
                        title="Decline and block"
                        onClick={() => decline.mutate({ id: r.id, block: true })}
                        disabled={decline.isPending}
                      >
                        <Ban />
                      </Button>
                    </div>
                  )
                }
              />
            </TabPanel>
            <TabPanel value="out">
              <RequestList
                q={outgoing}
                who={(r) => r.toHandle}
                empty="You haven’t asked anyone for money."
                actions={(r) =>
                  r.status === 'OPEN' && (
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => decline.mutate({ id: r.id, block: false })}
                      disabled={decline.isPending}
                    >
                      Cancel
                    </Button>
                  )
                }
              />
            </TabPanel>
          </Tabs>
        </div>
      </div>
      {paying && accounts.data && (
        <PayRequestDialog
          request={paying}
          accounts={accounts.data}
          onClose={() => setPaying(null)}
        />
      )}
    </div>
  )
}

function RequestList({
  q,
  who,
  empty,
  actions,
}: {
  q: ReturnType<typeof useRequests>
  who: (r: PaymentRequestDto) => string
  empty: string
  actions: (r: PaymentRequestDto) => React.ReactNode
}) {
  if (q.isError) return <PanelError what="requests" onRetry={() => void q.refetch()} />
  if (!q.data) return <Skeleton className="h-48 w-full" />
  if (q.data.content.length === 0) return <EmptyState icon={<ArrowDownToLine />} title={empty} />
  return (
    <ul className="space-y-2">
      {q.data.content.map((r) => (
        <li
          key={r.id}
          className="flex flex-wrap items-center gap-3 rounded-2xl bg-surface p-4 hairline"
        >
          <Avatar name={who(r)} />
          <div className="min-w-0 flex-1">
            <p className="flex items-center gap-2 text-sm font-medium">
              {who(r)} <Badge tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Badge>
            </p>
            <p className="truncate text-xs text-muted">
              {r.note ?? 'No note'} · {formatRelative(r.createdAt)}
              {r.status === 'OPEN' && ` · expires ${formatRelative(r.expiresAt)}`}
            </p>
          </div>
          <Money value={r.amount} className="font-medium" />
          {actions(r)}
        </li>
      ))}
    </ul>
  )
}

function NewRequest({ onSent }: { onSent: () => void }) {
  const [to, setTo] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const send = useMutation({ mutationFn: payApi.createRequest })

  function submit(e: FormEvent) {
    e.preventDefault()
    const handle = to.trim().toLowerCase().includes('@')
      ? to.trim().toLowerCase()
      : `${to.trim().toLowerCase()}@qg`
    const next: Record<string, string> = {}
    if (!/^[a-z0-9.]{3,30}@qg$/.test(handle)) next.toHandle = 'Handles look like name@qg'
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1)) next.amount = 'Enter at least ₹1'
    else if (toDecimal(amount).gt(200000)) next.amount = 'At most ₹2,00,000'
    setErrors(next)
    if (Object.keys(next).length) return
    send.mutate(
      { toHandle: handle, amount, ...(note.trim() ? { note: note.trim() } : {}) },
      {
        onSuccess: (r) => {
          toast.success(`Asked ${r.toHandle} for ₹${toDecimal(r.amount).toFixed(2)}`)
          setTo('')
          setAmount('')
          setNote('')
          onSent()
        },
        onError: (err) => {
          const ae = toApiError(err)
          if (ae.fieldErrors.length)
            setErrors(Object.fromEntries(ae.fieldErrors.map((f) => [f.field, f.message])))
          else if (ae.code === 'INVALID_STATE')
            toast.error('Set your handle first, so people know who’s asking.')
          else toast.error(errorMessage(ae))
        },
      },
    )
  }

  return (
    <Panel eyebrow="Ask" title="Request money" tone="cyan">
      <form onSubmit={submit} className="space-y-4" noValidate>
        <FormField
          label="From (handle)"
          placeholder="meera@qg"
          autoCapitalize="none"
          spellCheck={false}
          value={to}
          onChange={(e) => setTo(e.target.value.replace(/\s/g, ''))}
          error={errors.toHandle}
        />
        <AmountInput
          label="Amount"
          value={amount}
          onValueChange={setAmount}
          error={errors.amount}
        />
        <FormField
          label="What’s it for? (optional)"
          maxLength={80}
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        <Button type="submit" className="w-full" disabled={send.isPending}>
          <Send aria-hidden="true" /> {send.isPending ? 'Sending…' : 'Send request'}
        </Button>
      </form>
    </Panel>
  )
}

function NoHandle() {
  return (
    <Panel eyebrow="Ask" title="Request money">
      <p className="text-sm text-muted">
        Claim your handle on the Pay overview first, so people know who’s asking.
      </p>
    </Panel>
  )
}
