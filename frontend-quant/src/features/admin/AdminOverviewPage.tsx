import {
  ArrowRight,
  CandlestickChart,
  HandCoins,
  Inbox,
  Landmark,
  Scale,
  ShieldAlert,
  ToggleRight,
  Users,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router'
import { useAdminLoans, useAdminStats, useAuditLogs } from '@/api/queries'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useHealth } from '@/features/analytics/hooks'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatRelative } from '@/lib/time'
import { cn } from '@/lib/utils'
import { StatTile } from './components/StatTile'
import { auditAction } from './labels'
import { useAdminFlags, useFraudCases } from './riskHooks'

const count = new Intl.NumberFormat('en-IN')

/** F-ADM-4 stats, plus the two things an operator checks first: the queue and the log. */
export function AdminOverviewPage() {
  const stats = useAdminStats()
  const pending = useAdminLoans({ status: 'PENDING', page: 0, size: 5 })
  const audit = useAuditLogs({ page: 0, size: 6 })
  const s = stats.data

  return (
    <div>
      <PageHeader
        title="Overview"
        description="Bank-wide totals, updated when you open this page."
        actions={
          <Button variant="secondary" asChild>
            <Link to="/admin/reconcile">
              <Scale aria-hidden="true" /> Reconcile ledger
            </Link>
          </Button>
        }
      />

      {stats.isError ? (
        <Card>
          <CardError what="stats" onRetry={() => void stats.refetch()} />
        </Card>
      ) : (
        <section
          aria-label="Key figures"
          className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5"
        >
          <StatTile
            label="Total users"
            icon={Users}
            loading={!s}
            value={s && count.format(s.totalUsers)}
          />
          <StatTile
            label="Total deposits"
            icon={Wallet}
            loading={!s}
            value={s && <Money value={s.totalDeposits} compact />}
            hint="Customer account balances"
          />
          <StatTile
            label="Active loans"
            icon={HandCoins}
            loading={!s}
            value={s && count.format(s.activeLoans)}
          />
          <StatTile
            label="Outstanding principal"
            icon={Landmark}
            loading={!s}
            value={s && <Money value={s.outstandingPrincipal} compact />}
          />
          <StatTile
            label="Trades today"
            icon={CandlestickChart}
            loading={!s}
            value={s && count.format(s.tradesToday)}
            hint="IST calendar day"
          />
        </section>
      )}

      <NeedsAttention pendingLoans={pending.data?.totalElements} />

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <section aria-labelledby="queue-h">
            <div className="flex items-center justify-between gap-3">
              <h2 id="queue-h" className="flex items-center gap-2 font-medium">
                <Inbox aria-hidden="true" className="size-4 text-muted" /> Loan queue
                {pending.data && pending.data.totalElements > 0 && (
                  <Badge tone="warn">
                    <span className="num">{pending.data.totalElements}</span> pending
                  </Badge>
                )}
              </h2>
              <Button variant="link" size="sm" asChild>
                <Link to="/admin/loans">
                  Open queue <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </div>
            <div className="mt-4">
              {pending.isPending ? (
                <ListSkeleton />
              ) : pending.isError ? (
                <CardError what="the queue" onRetry={() => void pending.refetch()} />
              ) : pending.data.content.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted">
                  No applications waiting. Nice.
                </p>
              ) : (
                <ul className="divide-y divide-border">
                  {pending.data.content.map((l) => (
                    <li key={l.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                      <div className="min-w-0">
                        <p className="truncate font-medium">{l.applicant.fullName}</p>
                        <p className="truncate text-xs text-muted">
                          {l.purpose} · {formatRelative(l.createdAt)}
                        </p>
                      </div>
                      <div className="shrink-0 text-right">
                        <Money value={l.amount} />
                        <p className="num text-xs text-muted">{l.tenureMonths} mo</p>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>
        </Card>

        <Card>
          <section aria-labelledby="audit-h">
            <div className="flex items-center justify-between gap-3">
              <h2 id="audit-h" className="font-medium">
                Recent activity
              </h2>
              <Button variant="link" size="sm" asChild>
                <Link to="/admin/audit">
                  Audit log <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </div>
            <div className="mt-4">
              {audit.isPending ? (
                <ListSkeleton />
              ) : audit.isError ? (
                <CardError what="the audit log" onRetry={() => void audit.refetch()} />
              ) : audit.data.content.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted">Nothing logged yet.</p>
              ) : (
                <ul className="divide-y divide-border">
                  {audit.data.content.map((a) => {
                    const meta = auditAction(a.action)
                    return (
                      <li
                        key={a.id}
                        className="flex items-center justify-between gap-3 py-3 text-sm"
                      >
                        <div className="min-w-0">
                          <Badge tone={meta.tone}>{meta.label}</Badge>
                          <p className="mt-1 truncate text-xs text-muted">
                            {a.actorEmail ?? 'system'}
                          </p>
                        </div>
                        <time dateTime={a.createdAt} className="shrink-0 text-xs text-muted">
                          {formatRelative(a.createdAt)}
                        </time>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          </section>
        </Card>
      </div>
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="space-y-2">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-11" />
      ))}
    </div>
  )
}

/**
 * v2 (PRD §11.4 admin): what needs a person right now. Each chip links to its queue; a chip
 * whose endpoint isn't live yet is simply left out.
 */
function NeedsAttention({ pendingLoans }: { pendingLoans?: number }) {
  const fraud = useFraudCases('OPEN', 0)
  const health = useHealth()
  const flags = useAdminFlags()
  const openCases = fraud.data?.totalElements
  const on = flags.data?.filter((f) => f.enabled).length
  return (
    <section aria-label="Needs attention" className="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
      {openCases !== undefined && (
        <AttentionChip
          to="/admin/fraud"
          icon={ShieldAlert}
          label="Fraud cases to review"
          value={count.format(openCases)}
          tone={openCases > 0 ? 'warn' : 'ok'}
        />
      )}
      {pendingLoans !== undefined && (
        <AttentionChip
          to="/admin/loans"
          icon={Inbox}
          label="Loans to decide"
          value={count.format(pendingLoans)}
          tone={pendingLoans > 0 ? 'warn' : 'ok'}
        />
      )}
      {health.data && (
        <AttentionChip
          to="/admin/reconcile"
          icon={Scale}
          label="Ledger"
          value={health.data.reconcileBalanced ? 'Balanced' : 'Out of balance'}
          tone={health.data.reconcileBalanced ? 'ok' : 'bad'}
        />
      )}
      {on !== undefined && flags.data && (
        <AttentionChip
          to="/admin/flags"
          icon={ToggleRight}
          label="Features switched on"
          value={`${on} of ${flags.data.length}`}
          tone="neutral"
        />
      )}
    </section>
  )
}

function AttentionChip({
  to,
  icon: Icon,
  label,
  value,
  tone,
}: {
  to: string
  icon: LucideIcon
  label: string
  value: string
  tone: 'ok' | 'warn' | 'bad' | 'neutral'
}) {
  return (
    <Link
      to={to}
      className={cn(
        'flex items-center gap-3 rounded-[var(--radius-card)] bg-surface p-3.5 hairline transition-colors hover:border-border-strong',
        tone === 'warn' && 'border-warn/40',
        tone === 'bad' && 'border-loss/50',
      )}
    >
      <span
        className={cn(
          'grid size-9 shrink-0 place-items-center rounded-xl',
          tone === 'ok'
            ? 'bg-gain/12 text-gain'
            : tone === 'warn'
              ? 'bg-warn/12 text-warn'
              : tone === 'bad'
                ? 'bg-loss/12 text-loss'
                : 'bg-surface-2 text-muted',
        )}
      >
        <Icon aria-hidden="true" className="size-4" />
      </span>
      <span className="min-w-0">
        <span className="block truncate text-xs text-muted">{label}</span>
        <span className="block font-medium">{value}</span>
      </span>
    </Link>
  )
}
