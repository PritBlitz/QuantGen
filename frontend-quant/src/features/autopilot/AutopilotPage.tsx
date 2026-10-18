import { ArrowRight, Pause, Play, Plus, Workflow } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import type { RuleDto } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { SpotlightCard } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatRelative } from '@/lib/time'
import { useRuleMutation, useRules, useTemplates } from './hooks'

/** `/autopilot` (F-AUTO-1…7): your money rules, what they've done, and ideas to start from. */
export function AutopilotPage() {
  const on = useFlag('autopilot')
  const rules = useRules(on)

  if (!on)
    return (
      <>
        <PageHeader eyebrow="Autopilot" title="Autopilot" />
        <EmptyState icon={<Workflow />} title="Autopilot is switched off right now">
          Your rules are kept, and none of them run while it’s off. Everything else works as usual.
        </EmptyState>
      </>
    )

  return (
    <>
      <PageHeader
        eyebrow="Autopilot"
        title={
          <>
            Money on <em className="text-gradient">autopilot.</em>
          </>
        }
        description="Rules you approve once. A plain program, not the AI, runs them every time, only within the caps you set. Pause any rule whenever you like."
        actions={
          <Button asChild>
            <Link to="/autopilot/new">
              <Plus aria-hidden="true" /> New rule
            </Link>
          </Button>
        }
      />
      <div className="space-y-6">
        {rules.isPending ? (
          <div className="grid gap-4 md:grid-cols-2">
            <Skeleton className="h-48" />
            <Skeleton className="h-48" />
          </div>
        ) : rules.isError ? (
          <PanelError what="your rules" onRetry={() => void rules.refetch()} />
        ) : rules.data.length === 0 ? (
          <EmptyState
            icon={<Workflow />}
            title="No rules yet"
            action={
              <Button asChild>
                <Link to="/autopilot/new">Create your first rule</Link>
              </Button>
            }
          >
            Try “When my salary arrives, move 20% to my Goa goal.” You’ll see what it would have done over the last 90 days before it’s switched on.
          </EmptyState>
        ) : (
          <ul className="grid gap-4 md:grid-cols-2">
            {rules.data.map((r) => (
              <li key={r.id}>
                <RuleCard rule={r} />
              </li>
            ))}
          </ul>
        )}
        <Templates />
      </div>
    </>
  )
}

function RuleCard({ rule: r }: { rule: RuleDto }) {
  const { pause, resume } = useRuleMutation()
  const busy = pause.isPending || resume.isPending
  const toggle = () => {
    const m = r.status === 'ACTIVE' ? pause : resume
    m.mutate(r.id, {
      onSuccess: (res) => toast.success(res.status === 'ACTIVE' ? `“${r.name}” is running again.` : `“${r.name}” is paused.`),
      onError: (e) => {
        const ae = toApiError(e)
        toast.error(ae.code === 'STEP_UP_CANCELLED' ? 'Verification was cancelled, so the rule stays paused.' : errorMessage(ae))
      },
    })
  }
  return (
    <SpotlightCard tone={r.status === 'ACTIVE' ? 'violet' : undefined} className="flex h-full flex-col">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate font-medium tracking-tight">
            <Link to={`/autopilot/${r.id}`} className="hover:underline">
              {r.name}
            </Link>
          </h2>
          <p className="mt-0.5 text-xs text-muted">Version {r.version}</p>
        </div>
        <Badge tone={r.status === 'ACTIVE' ? 'gain' : 'neutral'} dot>
          {r.status === 'ACTIVE' ? 'Running' : 'Paused'}
        </Badge>
      </div>
      <p className="mt-3 flex-1 text-sm">{r.rendering}</p>
      {r.status === 'PAUSED' && r.pausedReason && <p className="mt-2 text-xs text-warn">{r.pausedReason}</p>}
      <dl className="mt-4 grid grid-cols-3 gap-2 border-t border-border pt-3 text-xs">
        <div>
          <dt className="text-muted">Runs</dt>
          <dd className="mt-0.5 font-medium tabular-nums">{r.runCount}</dd>
        </div>
        <div>
          <dt className="text-muted">This month</dt>
          <dd className="mt-0.5 font-medium">
            <Money value={r.movedThisMonth} />
          </dd>
        </div>
        <div>
          <dt className="text-muted">Last run</dt>
          <dd className="mt-0.5 font-medium">{r.lastRunAt ? formatRelative(r.lastRunAt) : 'Not yet'}</dd>
        </div>
      </dl>
      <div className="mt-4 flex items-center gap-2">
        <Button variant="secondary" size="sm" disabled={busy} onClick={toggle}>
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
        <Button variant="ghost" size="sm" asChild className="ml-auto">
          <Link to={`/autopilot/${r.id}`} aria-label={`Details for ${r.name}`}>
            Details <ArrowRight aria-hidden="true" />
          </Link>
        </Button>
      </div>
    </SpotlightCard>
  )
}

function Templates() {
  const templates = useTemplates()
  if (templates.isError || (templates.data && templates.data.length === 0)) return null
  return (
    <Panel eyebrow="Ideas" title="Start from a template">
      {templates.isPending ? (
        <Skeleton className="h-28" />
      ) : (
        <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {templates.data.map((t) => (
            <li key={t.id}>
              <Link
                to={`/autopilot/new?template=${encodeURIComponent(t.id)}`}
                className="block h-full rounded-[var(--radius-control)] bg-surface-2/50 p-3.5 hairline transition-colors hover:border-violet/50 hover:bg-violet/10"
              >
                <span className="block text-sm font-medium">{t.name}</span>
                <span className="mt-0.5 block text-xs text-muted">{t.description}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}
