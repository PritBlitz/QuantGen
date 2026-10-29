import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, Check, Clock, Loader2, ShieldCheck, Sparkles, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { copilotApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import type { IntentDto } from '@/api/types'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { moneyErrorCopy } from '@/lib/moneyErrors'
import { cn } from '@/lib/utils'
import { AGENT_LABEL } from '../labels'

/**
 * One Idempotency-Key per intent, kept for the whole tab session (not per card instance), so an
 * approve retried after a timeout, from the sheet or the page, replays instead of executing
 * twice. Dropped once the server confirms. In memory only.
 */
const approveKeys = new Map<string, string>()
function approveKey(intentId: string): string {
  let k = approveKeys.get(intentId)
  if (!k) {
    k = crypto.randomUUID()
    approveKeys.set(intentId, k)
  }
  return k
}

const RISK: Record<IntentDto['riskLevel'], { tone: BadgeTone; label: string }> = {
  LOW: { tone: 'gain', label: 'Low risk' },
  MEDIUM: { tone: 'warn', label: 'Medium risk' },
  HIGH: { tone: 'loss', label: 'High risk' },
}

const FAILURE: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'There wasn’t enough balance when you approved, so nothing moved.',
  ACCOUNT_FROZEN: 'The account is frozen, so nothing moved.',
  LIMIT_EXCEEDED: 'This would go over your transfer limit, so nothing moved.',
  NOT_FOUND: 'The account or payee is no longer available, so nothing moved.',
  COOLING_PERIOD: 'The payee is new and inside the 24-hour cooling period, so nothing moved.',
  LOAN_NOT_ELIGIBLE: 'You’re not eligible for this loan right now, so nothing was applied for.',
  KYC_REQUIRED: 'This needs your KYC details first (Settings → Profile), so nothing happened.',
  VALIDATION_FAILED:
    'This can’t go through any more (for example, they’ve blocked requests from you), so nothing happened.',
}

/** Where the result of an executed intent can be seen. Keyed by `resultRef.type`, which is the result's
 *  own type and not always the intent kind (FD_BOOKING → FIXED_DEPOSIT, LOAN_APPLICATION → LOAN). */
const RESULT_LINK: Record<string, { to: string; label: string }> = {
  TRANSFER: { to: '/pay', label: 'See payments' },
  ORDER: { to: '/orders', label: 'See orders' },
  BILL_PAYMENT: { to: '/pay/bills', label: 'See bills' },
  SCHEDULED_TRANSFER: { to: '/pay/scheduled', label: 'See scheduled' },
  PAYMENT_REQUEST: { to: '/pay/requests', label: 'See requests' },
  FIXED_DEPOSIT: { to: '/savings', label: 'See savings' },
  LOAN: { to: '/loans', label: 'See loans' },
  AUTOPILOT_RULE: { to: '/autopilot', label: 'See Autopilot' },
}

function useNow(ms = 15_000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), ms)
    return () => clearInterval(t)
  }, [ms])
  return now
}

/**
 * A proposed action from Copilot (PRD §12.6, D-023). What the user approves is the **summary the
 * core rendered** from the validated payload; the model's `rationale` is shown separately and
 * labelled as the assistant's words. Approve = idempotent POST; the API client handles step-up.
 * Status changes only when the server answers (no optimistic state).
 */
export function IntentCard({ intent: fromServer }: { intent: IntentDto }) {
  const qc = useQueryClient()
  // The approve/reject response, shown until the refetched list catches up. Once the server's
  // copy has left PROPOSED it is the source of truth.
  const [answered, setIntent] = useState<IntentDto | null>(null)
  const intent =
    fromServer.status === 'PROPOSED' && answered?.id === fromServer.id ? answered : fromServer
  const now = useNow()

  const expired =
    intent.status === 'EXPIRED' ||
    (intent.status === 'PROPOSED' && Date.parse(intent.expiresAt) <= now)
  const status = expired ? 'EXPIRED' : intent.status
  const minsLeft = Math.max(1, Math.ceil((Date.parse(intent.expiresAt) - now) / 60_000))

  const refresh = () =>
    Promise.all(
      [
        ['accounts'],
        ['transfers'],
        ['orders'],
        ['portfolio'],
        ['pay'],
        ['savings'],
        ['notifications'],
        ['insights'],
        ['me-analytics'],
        ['copilot'],
      ].map((k) => qc.invalidateQueries({ queryKey: k })),
    )

  const approve = useMutation({
    mutationFn: () => copilotApi.approveIntent(intent.id, approveKey(intent.id)),
    onSuccess: (res) => {
      approveKeys.delete(intent.id)
      setIntent(res)
      if (res.status === 'EXECUTED') toast.success('Done. Approved and carried out.')
      void refresh()
    },
    onError: (e) => {
      const err = toApiError(e)
      if (err.code === 'INTENT_EXPIRED') setIntent({ ...intent, status: 'EXPIRED' })
      if (err.code === 'INVALID_STATE') void refresh()
    },
  })
  const reject = useMutation({
    mutationFn: () => copilotApi.rejectIntent(intent.id),
    onSuccess: (res) => {
      setIntent(res)
      void qc.invalidateQueries({ queryKey: ['copilot'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
  })

  const busy = approve.isPending || reject.isPending
  const err = approve.error ? toApiError(approve.error) : null
  const errCopy = err && err.code !== 'INTENT_EXPIRED' ? moneyErrorCopy(err) : null
  const risk = RISK[intent.riskLevel] ?? RISK.MEDIUM
  const link = intent.resultRef ? RESULT_LINK[intent.resultRef.type] : undefined

  return (
    <article
      aria-label={`Proposed action: ${intent.summary.title}`}
      className={cn(
        'overflow-hidden rounded-[var(--radius-card)] bg-surface-solid hairline',
        status === 'PROPOSED' &&
          'border-violet/45',
      )}
    >
      <header className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <p className="eyebrow mb-1">
            Needs your approval · {AGENT_LABEL[intent.proposedBy.agent] ?? 'Copilot'}
          </p>
          <h3 className="font-medium tracking-tight">{intent.summary.title}</h3>
        </div>
        <StatusBadge status={status} riskTone={risk.tone} riskLabel={risk.label} />
      </header>

      <dl className="divide-y divide-border px-4 text-sm">
        {intent.summary.lines.map((l) => (
          <div key={l.label} className="flex items-baseline justify-between gap-4 py-2">
            <dt className="text-muted">{l.label}</dt>
            <dd className="text-right font-medium tabular-nums">{l.value}</dd>
          </div>
        ))}
      </dl>

      {intent.rationale && (
        <div className="mx-4 mb-3 mt-1 rounded-[var(--radius-control)] bg-violet/8 p-3 text-sm">
          <p className="mb-1 flex items-center gap-1.5 text-xs font-medium text-violet">
            <Sparkles aria-hidden="true" className="size-3.5" /> Copilot’s reasoning (AI-written,
            not verified)
          </p>
          <p className="text-muted">{intent.rationale}</p>
        </div>
      )}

      <footer className="space-y-3 px-4 pb-4">
        {status === 'PROPOSED' && (
          <>
            {errCopy && <MoneyErrorAlert copy={errCopy} />}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="violet"
                disabled={busy}
                onClick={() => approve.mutate()}
                className="flex-1"
              >
                {approve.isPending ? (
                  <>
                    <Loader2 className="animate-spin" aria-hidden="true" /> Approving…
                  </>
                ) : (
                  <>
                    {intent.requiresStepUp ? (
                      <ShieldCheck aria-hidden="true" />
                    ) : (
                      <Check aria-hidden="true" />
                    )}
                    {err?.code === 'NETWORK' || err?.code === 'INTERNAL_ERROR'
                      ? 'Try again'
                      : 'Approve'}
                  </>
                )}
              </Button>
              <Button variant="ghost" disabled={busy} onClick={() => reject.mutate()}>
                <X aria-hidden="true" /> Not now
              </Button>
            </div>
            <p className="flex items-center gap-1.5 text-xs text-muted">
              <Clock aria-hidden="true" className="size-3.5" />
              Expires in {minsLeft} min.
              {intent.requiresStepUp && ' You’ll confirm with your passkey or PIN.'} Nothing happens
              until you approve.
            </p>
          </>
        )}
        {status === 'EXECUTED' && (
          <p className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span className="flex items-center gap-1.5 text-gain">
              <Check aria-hidden="true" className="size-4" /> Approved and carried out.
            </span>
            {link && (
              <Link
                to={link.to}
                className="inline-flex items-center gap-1 text-muted hover:text-text"
              >
                {link.label} <ArrowRight aria-hidden="true" className="size-3.5" />
              </Link>
            )}
          </p>
        )}
        {status === 'FAILED' && (
          <p role="alert" className="text-sm text-loss">
            {FAILURE[intent.failureCode ?? ''] ?? 'This couldn’t be carried out, so nothing moved.'}
          </p>
        )}
        {status === 'REJECTED' && (
          <p className="text-sm text-muted">You declined this. Nothing happened.</p>
        )}
        {status === 'EXPIRED' && (
          <p className="text-sm text-muted">
            This expired before it was approved. Ask Copilot again if you still want it.
          </p>
        )}
      </footer>
    </article>
  )
}

function StatusBadge({
  status,
  riskTone,
  riskLabel,
}: {
  status: IntentDto['status']
  riskTone: BadgeTone
  riskLabel: string
}) {
  switch (status) {
    case 'PROPOSED':
      return (
        <Badge tone={riskTone} dot>
          {riskLabel}
        </Badge>
      )
    case 'EXECUTED':
      return <Badge tone="gain">Done</Badge>
    case 'FAILED':
      return <Badge tone="loss">Failed</Badge>
    case 'REJECTED':
      return <Badge>Declined</Badge>
    case 'EXPIRED':
      return <Badge>Expired</Badge>
    default:
      return <Badge>{String(status)}</Badge>
  }
}
