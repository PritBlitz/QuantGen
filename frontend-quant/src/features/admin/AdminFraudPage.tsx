import { ArrowRight, Check, ShieldAlert, Sparkles, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { toApiError } from '@/api/errors'
import type { FraudCaseDetailDto, FraudCaseDto, FraudCaseStatus } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Panel, PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TraceDialog } from '@/features/copilot/components/TraceDialog'
import { formatAbsolute, formatRelative } from '@/lib/time'
import { usePageClamp } from '@/lib/usePageClamp'
import { cn } from '@/lib/utils'
import { ReasonDialog } from './components/ReasonDialog'
import { useFraudCase, useFraudCases, useFraudDecision } from './riskHooks'

type Filter = FraudCaseStatus | 'ALL'
const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'OPEN', label: 'Open' },
  { value: 'RELEASED', label: 'Released' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'ALL', label: 'All' },
]
const STATUS: Record<FraudCaseStatus, { label: string; tone: BadgeTone }> = {
  OPEN: { label: 'Needs review', tone: 'warn' },
  RELEASED: { label: 'Released', tone: 'gain' },
  REJECTED: { label: 'Rejected', tone: 'loss' },
}
const scoreTone = (s: number) => (s >= 80 ? 'text-loss' : s >= 50 ? 'text-warn' : 'text-muted')

/** `/admin/fraud` (F-RISK-2): transfers held for review, highest risk first. */
export function AdminFraudPage() {
  const [filter, setFilter] = useState<Filter>('OPEN')
  const [page, setPage] = useState(0)
  const cases = useFraudCases(filter, page)
  usePageClamp(cases.data, page, setPage)
  return (
    <div>
      <PageHeader
        title="Fraud cases"
        description="Transfers the risk engine held before any money moved. Releasing sends the money; rejecting returns it to the customer. Every decision is audited."
      />
      <Segmented<Filter>
        label="Status"
        value={filter}
        onValueChange={(v) => {
          setFilter(v)
          setPage(0)
        }}
        options={FILTERS}
        className="mb-4"
      />
      {cases.isPending ? (
        <Skeleton className="h-64" />
      ) : cases.isError ? (
        <Card>
          <PanelError what="fraud cases" onRetry={() => void cases.refetch()} />
        </Card>
      ) : cases.data.content.length === 0 ? (
        <Card>
          <EmptyState
            icon={<ShieldAlert />}
            title={filter === 'OPEN' ? 'Nothing waiting for review' : 'No cases here'}
          >
            {filter === 'OPEN'
              ? 'Held transfers appear here as soon as the risk engine flags them.'
              : undefined}
          </EmptyState>
        </Card>
      ) : (
        <Card className="p-0 sm:p-0">
          <ul className={cn('divide-y divide-border', cases.isPlaceholderData && 'opacity-60')}>
            {cases.data.content.map((c) => (
              <CaseRow key={c.id} c={c} />
            ))}
          </ul>
          <div className="px-5 pb-4">
            <Pagination
              page={cases.data.page}
              totalPages={cases.data.totalPages}
              totalElements={cases.data.totalElements}
              onPage={setPage}
              label="cases"
            />
          </div>
        </Card>
      )}
    </div>
  )
}

function CaseRow({ c }: { c: FraudCaseDto }) {
  const s = STATUS[c.status] ?? { label: c.status, tone: 'neutral' as const }
  return (
    <li>
      <Link
        to={`/admin/fraud/${c.id}`}
        className="flex items-center gap-4 px-5 py-4 transition-colors hover:bg-surface-2/60"
      >
        <span
          className={cn('num w-10 shrink-0 text-center text-2xl font-medium', scoreTone(c.score))}
          aria-label={`Risk score ${c.score} of 100`}
        >
          {c.score}
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{c.customer.fullName}</span>
            <Badge tone={s.tone}>{s.label}</Badge>
          </span>
          <span className="block truncate text-sm text-muted">
            {c.topSignal} · {formatRelative(c.createdAt)}
          </span>
        </span>
        <span className="text-right">
          <Money value={c.amount} className="block font-medium" />
          <span className="num block text-xs text-muted">
            {c.fromAccountNumberMasked} → {c.toAccountNumberMasked}
          </span>
        </span>
        <ArrowRight aria-hidden="true" className="size-4 shrink-0 text-muted" />
      </Link>
    </li>
  )
}

/** `/admin/fraud/:id`: signals, the customer's normal behaviour, the AI memo, and the decision. */
export function AdminFraudCasePage() {
  const { id = '' } = useParams()
  const kase = useFraudCase(id)
  const decide = useFraudDecision()
  const [decision, setDecision] = useState<'release' | 'reject' | null>(null)
  const [trace, setTrace] = useState<string | null>(null)
  const c = kase.data

  return (
    <div>
      <PageHeader
        back={{ to: '/admin/fraud', label: 'Fraud cases' }}
        eyebrow={c ? `Held ${formatAbsolute(c.createdAt)}` : 'Fraud case'}
        title={c ? <Money value={c.amount} /> : 'Fraud case'}
      />
      {kase.isError ? (
        toApiError(kase.error).code === 'NOT_FOUND' ? (
          <p className="text-sm text-muted">This case doesn’t exist.</p>
        ) : (
          <PanelError what="this case" onRetry={() => void kase.refetch()} />
        )
      ) : !c ? (
        <Skeleton className="h-80" />
      ) : (
        <div className="grid gap-4 lg:grid-cols-12">
          <div className="space-y-4 lg:col-span-7">
            <Panel title="The transfer" eyebrow={STATUS[c.status]?.label ?? c.status}>
              <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
                <Fact label="Customer">
                  <Link to={`/admin/users/${c.customer.userId}`} className="hover:underline">
                    {c.customer.fullName}
                  </Link>
                </Fact>
                <Fact label="Risk score">
                  <span className={cn('num font-medium', scoreTone(c.score))}>{c.score} / 100</span>
                </Fact>
                <Fact label="From">
                  <span className="num">{c.fromAccountNumberMasked}</span>
                </Fact>
                <Fact label="To">
                  {c.toName} <span className="num text-muted">{c.toAccountNumberMasked}</span>
                </Fact>
              </dl>
              {c.status !== 'OPEN' && c.decisionReason && (
                <p className="mt-4 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2 text-sm">
                  <span className="text-muted">
                    Decided {c.decidedAt ? formatRelative(c.decidedAt) : ''}:{' '}
                  </span>
                  {c.decisionReason}
                </p>
              )}
              {c.status === 'OPEN' && (
                <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                  <Button variant="outline" onClick={() => setDecision('reject')}>
                    <X aria-hidden="true" /> Reject and return money
                  </Button>
                  <Button onClick={() => setDecision('release')}>
                    <Check aria-hidden="true" /> Release transfer
                  </Button>
                </div>
              )}
            </Panel>
            <Panel title="Why it was held" eyebrow="Signals">
              <ul className="space-y-2">
                {[...c.signals]
                  .sort((a, b) => b.weight - a.weight)
                  .map((s) => (
                    <li key={s.code} className="flex items-center gap-3 text-sm">
                      <span className="min-w-0 flex-1">{s.label}</span>
                      <span
                        className="h-1.5 w-24 overflow-hidden rounded-full bg-surface-2"
                        aria-hidden="true"
                      >
                        <span
                          className="block h-full rounded-full bg-warn"
                          style={{ width: `${Math.min(100, s.weight)}%` }}
                        />
                      </span>
                      <span className="num w-8 text-right text-xs text-muted">+{s.weight}</span>
                    </li>
                  ))}
              </ul>
            </Panel>
          </div>
          <div className="space-y-4 lg:col-span-5">
            <Memo memo={c.memo} onTrace={setTrace} />
            <Baseline c={c} />
          </div>
        </div>
      )}

      {c && (
        <ReasonDialog
          open={decision !== null}
          onOpenChange={(o) => !o && setDecision(null)}
          title={decision === 'release' ? 'Release this transfer?' : 'Reject this transfer?'}
          description={
            decision === 'release'
              ? 'The held money is sent to the recipient now. This can’t be undone.'
              : 'The hold is released and the money stays with the customer. They’re notified.'
          }
          summary={
            <p className="text-sm">
              <Money value={c.amount} className="font-medium" /> from {c.customer.fullName} to{' '}
              {c.toName}
            </p>
          }
          reasonRequired
          placeholder={
            decision === 'release'
              ? 'e.g. Called the customer; they confirmed the payee.'
              : 'e.g. Payee matches a reported scam account.'
          }
          confirmLabel={decision === 'release' ? 'Release' : 'Reject'}
          destructive={decision === 'reject'}
          onConfirm={async (reason) => {
            if (!decision) return
            await decide.mutateAsync({ id: c.id, kind: decision, reason })
            toast.success(
              decision === 'release'
                ? 'Released. The money has been sent.'
                : 'Rejected. The customer keeps the money.',
            )
            setDecision(null)
          }}
        />
      )}
      {trace && <TraceDialog runId={trace} admin onClose={() => setTrace(null)} />}
    </div>
  )
}

const SUGGESTION: Record<NonNullable<FraudCaseDetailDto['memo']>['suggestedAction'], string> = {
  RELEASE: 'Release',
  REJECT: 'Reject',
  CONTACT_CUSTOMER: 'Contact the customer first',
}

function Memo({
  memo,
  onTrace,
}: {
  memo: FraudCaseDetailDto['memo']
  onTrace: (runId: string) => void
}) {
  return (
    <Panel title="Investigator memo" eyebrow="AI · advisory only" tone="violet">
      {!memo ? (
        <p className="text-sm text-muted">
          No memo yet. The case can be decided without one; the AI never decides.
        </p>
      ) : (
        <div className="space-y-3 text-sm">
          <p className="flex flex-wrap items-center gap-2">
            <Badge tone="violet">
              <Sparkles aria-hidden="true" className="size-3" /> Suggests:{' '}
              {SUGGESTION[memo.suggestedAction] ?? memo.suggestedAction}
            </Badge>
            <span className="text-xs text-muted">
              {memo.confidence.toLowerCase()} confidence · {formatRelative(memo.generatedAt)}
            </span>
          </p>
          <p className="whitespace-pre-wrap">{memo.summary}</p>
          {memo.runId && (
            <button
              type="button"
              onClick={() => onTrace(memo.runId as string)}
              className="text-xs text-muted hover:text-violet"
            >
              See how it was written →
            </button>
          )}
        </div>
      )}
    </Panel>
  )
}

function Baseline({ c }: { c: FraudCaseDetailDto }) {
  const b = c.customerBaseline
  return (
    <Panel title="What’s normal for this customer" eyebrow="Baseline">
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <Fact label="Average transfer">
          <Money value={b.avgTransfer} />
        </Fact>
        <Fact label="Transfers in 30 days">
          <span className="num">{b.transfers30d}</span>
        </Fact>
        <Fact label="Usual hours (IST)">{b.usualHoursIst}</Fact>
        <Fact label="Account age">
          <span className="num">{b.accountAgeDays}</span> days
        </Fact>
      </dl>
    </Panel>
  )
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  )
}
