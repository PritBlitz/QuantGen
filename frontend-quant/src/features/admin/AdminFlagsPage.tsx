import { Loader2, ToggleRight } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import type { AdminFeatureFlagDto, FeatureFlag } from '@/api/types'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { PanelError } from '@/components/Panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { formatRelative } from '@/lib/time'
import { useAdminFlags, useSetFlag } from './riskHooks'

const ABOUT: Record<FeatureFlag, { name: string; on: string; off: string }> = {
  ai: {
    name: 'AI layer (Copilot, AI drafts, memos)',
    on: 'Customers can chat with Copilot and get AI-drafted Autopilot rules; back-office memos are written.',
    off: 'Copilot and AI drafting disappear. Money features, Autopilot rules and the fraud queue keep working.',
  },
  autopilot: {
    name: 'Autopilot',
    on: 'Active rules run on their triggers.',
    off: 'No rule runs. Rules are kept and resume where they left off when switched back on.',
  },
  razorpay: {
    name: 'Razorpay top-ups (test mode)',
    on: 'Customers can add money through Razorpay Checkout. Needs gateway keys on the server.',
    off: 'New top-ups are refused; ones already started still finish. The demo deposit stays available.',
  },
  cards: {
    name: 'Virtual cards',
    on: 'Customers can issue and use virtual cards in the merchant sandbox.',
    off: 'Cards and the sandbox merchant are hidden and refuse requests.',
  },
  mcp: {
    name: 'Developer access (MCP tokens)',
    on: 'Customers can create read-only or read-and-propose tokens for MCP clients.',
    off: 'New tokens can’t be created and existing tokens are refused.',
  },
  crypto: {
    name: 'Live crypto prices (read-only)',
    on: 'Markets shows BTC, ETH and other prices. The server fetches them from a free public feed; no trading.',
    off: 'The crypto tab is hidden and the server stops calling the feed.',
  },
}

/** `/admin/flags` (F-FLAG-1): kill switches. Every change is audited server-side. */
export function AdminFlagsPage() {
  const flags = useAdminFlags()
  const [pending, setPending] = useState<AdminFeatureFlagDto | null>(null)
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Feature flags"
        description="Switch v2 features on or off for everyone, instantly. Turning one off never affects balances or the ledger."
      />
      {flags.isError ? (
        <Card>
          <PanelError what="feature flags" onRetry={() => void flags.refetch()} />
        </Card>
      ) : !flags.data ? (
        <Skeleton className="h-72" />
      ) : flags.data.length === 0 ? (
        <Card>
          <EmptyState icon={<ToggleRight />} title="No flags" />
        </Card>
      ) : (
        <Card className="p-0 sm:p-0">
          <ul className="divide-y divide-border">
            {flags.data.map((f) => {
              const about = ABOUT[f.name] ?? { name: f.name, on: '', off: '' }
              return (
                <li
                  key={f.name}
                  className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center"
                >
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2 font-medium">
                      {about.name}
                      <Badge tone={f.enabled ? 'gain' : 'neutral'} dot>
                        {f.enabled ? 'On' : 'Off'}
                      </Badge>
                    </p>
                    <p className="mt-0.5 text-sm text-muted">{f.enabled ? about.on : about.off}</p>
                    {f.updatedAt && (
                      <p className="mt-1 text-xs text-muted">
                        Changed {formatRelative(f.updatedAt)}
                        {f.updatedBy && ` by ${f.updatedBy}`}
                      </p>
                    )}
                  </div>
                  <Button
                    variant={f.enabled ? 'outline' : 'primary'}
                    size="sm"
                    onClick={() => setPending(f)}
                    aria-label={`${f.enabled ? 'Turn off' : 'Turn on'} ${about.name}`}
                  >
                    {f.enabled ? 'Turn off' : 'Turn on'}
                  </Button>
                </li>
              )
            })}
          </ul>
        </Card>
      )}
      {pending && <ConfirmFlag flag={pending} onClose={() => setPending(null)} />}
    </div>
  )
}

function ConfirmFlag({ flag, onClose }: { flag: AdminFeatureFlagDto; onClose: () => void }) {
  const set = useSetFlag()
  const about = ABOUT[flag.name] ?? { name: flag.name, on: '', off: '' }
  const next = !flag.enabled
  return (
    <Dialog open onOpenChange={(o) => !o && !set.isPending && onClose()}>
      <DialogContent
        title={`${next ? 'Turn on' : 'Turn off'} ${about.name}?`}
        description={next ? about.on : about.off}
      >
        {set.error && (
          <p role="alert" className="mb-3 text-sm text-loss">
            {errorMessage(set.error)}
          </p>
        )}
        <p className="mb-4 text-xs text-muted">
          This applies to every customer at once and is recorded in the audit log.
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" disabled={set.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant={next ? 'primary' : 'destructive'}
            disabled={set.isPending}
            onClick={() =>
              set.mutate(
                { name: flag.name, enabled: next },
                {
                  onSuccess: () => {
                    toast.success(`${about.name} is ${next ? 'on' : 'off'}.`)
                    onClose()
                  },
                },
              )
            }
          >
            {set.isPending && <Loader2 className="animate-spin" aria-hidden="true" />}
            {next ? 'Turn on' : 'Turn off'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
