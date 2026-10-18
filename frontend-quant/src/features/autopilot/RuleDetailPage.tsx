import { Loader2, Pause, PenLine, Play, Sparkles, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import type { RuleRunDto } from '@/api/types'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Panel, PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { useCopilotUi } from '@/features/copilot/uiStore'
import { formatAbsolute, formatRelative } from '@/lib/time'
import { useRule, useRuleMutation, useRuleRuns } from './hooks'
import { ACTION_LABEL, OUTCOME } from './rules'

/** `/autopilot/:id`: one rule, its controls and every run (F-AUTO-5, F-AUTO-7). */
export function RuleDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const rule = useRule(id)
  const { pause, resume, remove } = useRuleMutation()
  const [confirmDelete, setConfirmDelete] = useState(false)
  const r = rule.data

  if (rule.isError) {
    return (
      <>
        <PageHeader back={{ to: '/autopilot', label: 'Autopilot' }} title="Rule" />
        {toApiError(rule.error).code === 'NOT_FOUND' ? (
          <p className="text-sm text-muted">This rule doesn’t exist, or it was deleted.</p>
        ) : (
          <PanelError what="this rule" onRetry={() => void rule.refetch()} />
        )}
      </>
    )
  }

  const busy = pause.isPending || resume.isPending
  const toggle = () => {
    if (!r) return
    const m = r.status === 'ACTIVE' ? pause : resume
    m.mutate(r.id, {
      onError: (e) => {
        const ae = toApiError(e)
        toast.error(ae.code === 'STEP_UP_CANCELLED' ? 'Verification was cancelled, so the rule stays paused.' : errorMessage(ae))
      },
    })
  }

  return (
    <>
      <PageHeader
        back={{ to: '/autopilot', label: 'Autopilot' }}
        eyebrow={r ? `Version ${r.version} · created ${formatRelative(r.createdAt)}` : 'Autopilot rule'}
        title={r?.name ?? <Skeleton className="h-12 w-64" />}
        actions={
          r && (
            <>
              <Button variant="secondary" disabled={busy} onClick={toggle}>
                {r.status === 'ACTIVE' ? (
                  <>
                    <Pause aria-hidden="true" /> Pause
                  </>
                ) : (
                  <>
                    <Play aria-hidden="true" /> Resume
                  </>
                )}
              </Button>
              <Button variant="secondary" asChild>
                <Link to={`/autopilot/${r.id}/edit`}>
                  <PenLine aria-hidden="true" /> Edit
                </Link>
              </Button>
              <Button variant="ghost" onClick={() => setConfirmDelete(true)}>
                <Trash2 aria-hidden="true" /> Delete
              </Button>
            </>
          )
        }
      />
      {!r ? (
        <Skeleton className="h-64" />
      ) : (
        <div className="grid gap-4 lg:grid-cols-12">
          <div className="space-y-4 lg:col-span-5">
            <Panel eyebrow="What it does" title={r.status === 'ACTIVE' ? 'Running' : 'Paused'} tone={r.status === 'ACTIVE' ? 'violet' : undefined}>
              <p className="font-display text-xl leading-snug">“{r.rendering}”</p>
              {r.status === 'PAUSED' && r.pausedReason && <p className="mt-3 text-sm text-warn">{r.pausedReason}</p>}
              <dl className="mt-5 grid grid-cols-2 gap-3 text-sm">
                <Stat label="Runs so far" value={String(r.runCount)} />
                <Stat label="Moved this month" value={<Money value={r.movedThisMonth} />} />
                <Stat label="Cap per run" value={<Money value={r.rule.caps.perRun} />} />
                <Stat label="Cap per month" value={<Money value={r.rule.caps.perMonth} />} />
              </dl>
            </Panel>
          </div>
          <Runs ruleId={r.id} ruleName={r.name} className="lg:col-span-7" />
        </div>
      )}

      {confirmDelete && r && (
        <Dialog open onOpenChange={(o) => !o && !remove.isPending && setConfirmDelete(false)}>
          <DialogContent title={`Delete “${r.name}”?`} description="It stops at once and won’t run again. Its run history is kept for your records.">
            <div className="flex justify-end gap-2">
              <Button variant="ghost" disabled={remove.isPending} onClick={() => setConfirmDelete(false)}>
                Keep it
              </Button>
              <Button
                variant="destructive"
                disabled={remove.isPending}
                onClick={() =>
                  remove.mutate(r.id, {
                    onSuccess: () => {
                      toast.success('Rule deleted.')
                      void navigate('/autopilot')
                    },
                    onError: (e) => toast.error(errorMessage(e)),
                  })
                }
              >
                {remove.isPending && <Loader2 className="animate-spin" aria-hidden="true" />} Delete rule
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      )}
    </>
  )
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-[var(--radius-control)] bg-surface-2/60 px-3 py-2.5 hairline">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5 font-medium tabular-nums">{value}</dd>
    </div>
  )
}

function Runs({ ruleId, ruleName, className }: { ruleId: string; ruleName: string; className?: string }) {
  const [page, setPage] = useState(0)
  const runs = useRuleRuns(ruleId, page)
  const aiOn = useFlag('ai')
  const ask = useCopilotUi((s) => s.open)
  return (
    <Panel eyebrow="History" title="Runs" className={className}>
      {runs.isPending ? (
        <Skeleton className="h-40" />
      ) : runs.isError ? (
        <PanelError what="runs" onRetry={() => void runs.refetch()} />
      ) : runs.data.content.length === 0 ? (
        <p className="py-6 text-sm text-muted">No runs yet. When the trigger happens, each run shows up here with exactly what moved.</p>
      ) : (
        <>
          <ol className="space-y-2">
            {runs.data.content.map((run) => (
              <RunRow key={run.id} run={run} onExplain={aiOn ? () => ask(`Explain the run of my Autopilot rule “${ruleName}” on ${formatAbsolute(run.startedAt)}: ${run.triggerSummary}`) : undefined} />
            ))}
          </ol>
          <Pagination page={runs.data.page} totalPages={runs.data.totalPages} totalElements={runs.data.totalElements} onPage={setPage} label="runs" />
        </>
      )}
    </Panel>
  )
}

function RunRow({ run, onExplain }: { run: RuleRunDto; onExplain?: () => void }) {
  const o = OUTCOME[run.outcome] ?? { label: run.outcome, tone: 'neutral' as const }
  return (
    <li className="rounded-[var(--radius-control)] bg-surface-2/50 p-3 hairline">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium">{run.triggerSummary}</p>
          <p className="text-xs text-muted">
            {formatAbsolute(run.startedAt)} · version {run.ruleVersion}
          </p>
        </div>
        <Badge tone={o.tone}>{o.label}</Badge>
      </div>
      {run.actions.length > 0 && (
        <ul className="mt-2 space-y-1 text-sm">
          {run.actions.map((a, i) => (
            <li key={i} className="flex items-baseline justify-between gap-3">
              <span className={a.error ? 'text-muted' : undefined}>
                {ACTION_LABEL[a.type] ?? a.type}
                {a.error && <span className="text-loss"> · {a.error}</span>}
              </span>
              {a.amount && <Money value={a.amount} className={a.error ? 'text-muted line-through' : undefined} />}
            </li>
          ))}
        </ul>
      )}
      {onExplain && (
        <button type="button" onClick={onExplain} className="mt-2 inline-flex items-center gap-1 text-xs text-muted hover:text-violet">
          <Sparkles aria-hidden="true" className="size-3.5" /> Explain this run
        </button>
      )}
    </li>
  )
}
