import { useState } from 'react'
import type { AgentName, IntentDto } from '@/api/types'
import { Panel, PanelError } from '@/components/Panel'
import { Skeleton } from '@/components/ui/skeleton'
import { useIntents } from '../hooks'
import { IntentCard } from './IntentCard'

/**
 * Every open proposal, so nothing waits unseen (they expire after 15 minutes). Shown on /copilot,
 * and on Settings → Developer for proposals from connected apps (`MCP_CLIENT`), which work with
 * the `ai` flag off.
 */
export function WaitingApprovals({
  className,
  agent,
  empty = 'Nothing to approve. When Copilot prepares an action, it waits here until you decide.',
}: {
  className?: string
  /** Only proposals from this agent. */
  agent?: AgentName
  empty?: string
}) {
  const intents = useIntents('PROPOSED')
  // Keep every card shown during this visit mounted: once approved it leaves the PROPOSED list, and
  // unmounting it would also throw away its "Approved and carried out" confirmation. New ones go on top.
  const [seen, setSeen] = useState<{ from: IntentDto[] | undefined; list: IntentDto[] }>({
    from: undefined,
    list: [],
  })
  if (intents.data && intents.data !== seen.from) {
    const known = new Set(seen.list.map((i) => i.id))
    const fresh = intents.data.filter(
      (i) => !known.has(i.id) && (!agent || i.proposedBy.agent === agent),
    )
    setSeen({ from: intents.data, list: [...fresh, ...seen.list] })
  }
  const shown = seen.list
  return (
    <Panel
      title="Waiting for you"
      eyebrow="Approvals"
      tone="violet"
      className={className}
      bodyClassName="mt-3"
    >
      {intents.isPending ? (
        <Skeleton className="h-40" />
      ) : intents.isError ? (
        <PanelError what="approvals" onRetry={() => void intents.refetch()} />
      ) : shown.length === 0 ? (
        <p className="py-4 text-sm text-muted">{empty}</p>
      ) : (
        <div className="space-y-3">
          {shown.map((i) => (
            <IntentCard key={i.id} intent={i} />
          ))}
        </div>
      )}
    </Panel>
  )
}
