import { useQuery } from '@tanstack/react-query'
import { Bot, Wrench } from 'lucide-react'
import { copilotApi, riskApi } from '@/api/endpoints.v2'
import { PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { toDecimal } from '@/lib/money'
import { AGENT_LABEL, toolLabel } from '../labels'

/**
 * "How did Copilot get this answer?" (F-AGT-5): the redacted run trace — which agent did what,
 * how long it took, tokens and cost. `detail` is already redacted by the server.
 */
export function TraceDialog({
  runId,
  onClose,
  admin,
}: {
  runId: string
  onClose: () => void
  admin?: boolean
}) {
  // Customers see their own runs (redacted); admins use the back-office endpoint (PRD §12.8).
  const trace = useQuery({
    queryKey: admin ? ['admin', 'ai-run', runId] : ['copilot', 'trace', runId],
    queryFn: () => (admin ? riskApi.aiRun(runId) : copilotApi.trace(runId)),
    staleTime: Infinity,
    retry: false,
  })
  const t = trace.data
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        title={admin ? 'Agent run' : 'How Copilot answered'}
        description="Each step the agents took. Inputs are redacted; no raw account data is shown."
        className="max-w-lg"
      >
        {trace.isPending ? (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-12" />
            ))}
          </div>
        ) : trace.isError || !t ? (
          <PanelError what="the trace" onRetry={() => void trace.refetch()} />
        ) : (
          <div className="space-y-4">
            <div className="grid grid-cols-3 gap-2 text-center text-sm">
              <Stat label="Time" value={`${(t.totalMs / 1000).toFixed(1)} s`} />
              <Stat label="Steps" value={String(t.steps.length)} />
              <Stat label="Cost" value={`$${toDecimal(t.costUsd).toFixed(4)}`} />
            </div>
            <ol className="relative space-y-2 border-l border-border pl-5">
              {t.steps.map((s, i) => (
                <li key={i} className="relative">
                  <span className="absolute -left-[1.6rem] top-2.5 grid size-5 place-items-center rounded-full bg-surface-2 text-muted hairline [&_svg]:size-3">
                    {s.kind === 'MODEL' ? (
                      <Bot aria-hidden="true" />
                    ) : (
                      <Wrench aria-hidden="true" />
                    )}
                  </span>
                  <div className="rounded-[var(--radius-control)] bg-surface-2/60 px-3 py-2 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <p className="font-medium">
                        {toolLabel(s.name)}{' '}
                        <span className="font-normal text-muted">
                          · {AGENT_LABEL[s.agent] ?? s.agent}
                        </span>
                      </p>
                      {s.status === 'ERROR' ? (
                        <Badge tone="loss">Error</Badge>
                      ) : (
                        <span className="text-xs tabular-nums text-muted">{s.latencyMs} ms</span>
                      )}
                    </div>
                    {(s.detail || s.inputTokens != null) && (
                      <p className="mt-0.5 text-xs text-muted">
                        {s.detail}
                        {s.detail && s.inputTokens != null && ' · '}
                        {s.inputTokens != null &&
                          `${s.inputTokens} in / ${s.outputTokens ?? 0} out tokens`}
                      </p>
                    )}
                  </div>
                </li>
              ))}
            </ol>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[var(--radius-control)] bg-surface-2/60 px-2 py-2.5 hairline">
      <p className="text-xs text-muted">{label}</p>
      <p className="mt-0.5 font-medium tabular-nums">{value}</p>
    </div>
  )
}
