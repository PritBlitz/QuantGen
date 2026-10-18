import { ArrowDownToLine, ArrowRight, ArrowUpRight, CalendarClock, Copy, QrCode, ReceiptText, Split } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { isUnavailable } from '@/api/availability'
import { useAccounts } from '@/api/queries'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Panel } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { SpotlightCard } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useMe } from '@/features/security/hooks'
import { RecentTransfers } from '@/features/transfers/components/RecentTransfers'
import { buildPayLink } from '@/lib/payLink'
import { formatDate, formatRelative } from '@/lib/time'
import { Avatar, PayTabs, QrCanvas } from './components/PayBits'
import { HandleDialog } from './components/HandleDialog'
import { useRequests, useScheduled } from './hooks'
import { PayRequestDialog } from './components/PayRequestDialog'
import type { PaymentRequestDto } from '@/api/types'

const LINKS = [
  { to: '/transfer', label: 'Send money', sub: 'Handle, payee or account number', icon: ArrowUpRight },
  { to: '/pay/requests', label: 'Request money', sub: 'Ask anyone with a handle', icon: ArrowDownToLine },
  { to: '/pay/scheduled', label: 'Schedule', sub: 'Rent, SIPs, recurring transfers', icon: CalendarClock },
  { to: '/pay/bills', label: 'Pay bills', sub: 'Power, mobile, broadband, more', icon: ReceiptText },
  { to: '/pay/splits', label: 'Split a bill', sub: 'Groups that settle themselves', icon: Split },
  { to: '/pay/qr', label: 'Scan a QR', sub: 'Or show yours', icon: QrCode },
]

/** Payments hub (PRD §11.4.3): your handle + QR, shortcuts, what's waiting on you. */
export function PayHubPage() {
  const me = useMe()
  const accounts = useAccounts()
  const incoming = useRequests('INCOMING', 'OPEN')
  const scheduled = useScheduled()
  const [handleOpen, setHandleOpen] = useState(false)
  const [paying, setPaying] = useState<PaymentRequestDto | null>(null)
  const handle = me.data?.handle
  const link = handle ? buildPayLink(window.location.origin, { handle }) : null

  return (
    <div>
      <PageHeader eyebrow="Pay" title={<>Money, <em className="text-gradient">on the move.</em></>} description="Send, request, schedule, split and pay bills, all from one ledger that never loses a paisa." />
      <PayTabs />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        <SpotlightCard tone="violet" className="relative overflow-hidden lg:col-span-5">
          <div aria-hidden="true" className="pointer-events-none absolute -right-20 -top-20 size-64 rounded-full bg-violet/20 blur-3xl" />
          <p className="eyebrow">Your QuantGen handle</p>
          {me.isPending ? (
            <Skeleton className="mt-3 h-10 w-48" />
          ) : handle ? (
            <>
              <p className="num mt-3 text-3xl font-medium">{handle}</p>
              <div className="mt-5 flex items-center gap-5">
                <div className="rounded-2xl bg-surface-solid p-3 hairline">{link && <QrCanvas value={link} size={132} />}</div>
                <div className="space-y-2">
                  <p className="text-sm text-muted">Anyone can scan this to pay you. It opens a pre-filled screen; they still confirm.</p>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => {
                        if (link) void navigator.clipboard.writeText(link).then(() => toast.success('Payment link copied'))
                      }}
                    >
                      <Copy aria-hidden="true" /> Copy link
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => setHandleOpen(true)}>
                      Change
                    </Button>
                  </div>
                </div>
              </div>
            </>
          ) : (
            <div className="mt-3">
              <p className="text-sm text-muted">Claim a handle like <span className="num text-text">yourname@qg</span> so friends can pay you without your account number.</p>
              <Button className="mt-4" onClick={() => setHandleOpen(true)}>
                Claim my handle
              </Button>
            </div>
          )}
        </SpotlightCard>

        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:col-span-7">
          {LINKS.map(({ to, label, sub, icon: Icon }) => (
            <li key={to}>
              <Link to={to} className="group flex h-full flex-col justify-between gap-6 rounded-[var(--radius-card)] bg-surface p-4 hairline transition-[border-color,transform] hover:-translate-y-0.5 hover:border-border-strong">
                <span className="grid size-10 place-items-center rounded-xl bg-accent text-accent-ink">
                  <Icon aria-hidden="true" className="size-4" />
                </span>
                <span>
                  <span className="flex items-center gap-1 text-sm font-medium">
                    {label}
                    <ArrowRight aria-hidden="true" className="size-3.5 opacity-0 transition-opacity group-hover:opacity-100" />
                  </span>
                  <span className="mt-0.5 block text-xs text-muted">{sub}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>

        {!isUnavailable(incoming.error) && (
          <Panel className="lg:col-span-6" eyebrow="Waiting on you" title="Payment requests">
            {!incoming.data ? (
              <Skeleton className="h-24 w-full" />
            ) : incoming.data.content.length === 0 ? (
              <p className="py-4 text-sm text-muted">No one’s waiting on you. 🎉</p>
            ) : (
              <ul className="divide-y divide-border">
                {incoming.data.content.slice(0, 4).map((r) => (
                  <li key={r.id} className="flex items-center gap-3 py-3">
                    <Avatar name={r.fromHandle} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">{r.fromHandle}</p>
                      <p className="truncate text-xs text-muted">
                        {r.note ?? 'No note'} · {formatRelative(r.createdAt)}
                      </p>
                    </div>
                    <Money value={r.amount} className="text-sm font-medium" />
                    <Button size="sm" onClick={() => setPaying(r)}>
                      Pay
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        )}
        {!isUnavailable(scheduled.error) && (
          <Panel className="lg:col-span-6" eyebrow="Automatic" title="Scheduled transfers" action={<Link to="/pay/scheduled" className="text-sm text-muted hover:text-text">Manage</Link>}>
            {!scheduled.data ? (
              <Skeleton className="h-24 w-full" />
            ) : scheduled.data.filter((s) => s.status === 'ACTIVE').length === 0 ? (
              <p className="py-4 text-sm text-muted">Nothing scheduled yet.</p>
            ) : (
              <ul className="divide-y divide-border">
                {scheduled.data
                  .filter((s) => s.status === 'ACTIVE')
                  .slice(0, 4)
                  .map((s) => (
                    <li key={s.id} className="flex items-center gap-3 py-3">
                      <Avatar name={s.toName} />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{s.note ?? s.toName}</p>
                        <p className="text-xs text-muted">
                          {s.frequency.toLowerCase()} · next {s.nextRunDate ? formatDate(s.nextRunDate) : '—'}
                        </p>
                      </div>
                      <Money value={s.amount} className="text-sm" />
                    </li>
                  ))}
              </ul>
            )}
          </Panel>
        )}
        <div className="lg:col-span-12">
          <RecentTransfers />
        </div>
      </div>

      {handleOpen && accounts.data && <HandleDialog open onOpenChange={setHandleOpen} current={handle} accounts={accounts.data} />}
      {paying && accounts.data && <PayRequestDialog request={paying} accounts={accounts.data} onClose={() => setPaying(null)} />}
    </div>
  )
}
