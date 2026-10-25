import { useMutation, useQuery } from '@tanstack/react-query'
import { CircleAlert, CircleCheck, Link2, Loader2, Play, Scale, Sparkles } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useState } from 'react'
import { proofApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import type { ChainVerifyFrom, ReconcileMismatch } from '@/api/types'
import { useReconcile } from '@/api/queries'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TraceDialog } from '@/features/copilot/components/TraceDialog'
import { CardError } from '@/features/dashboard/components/CardShell'
import { Decimal } from '@/lib/money'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { formatAbsolute } from '@/lib/time'
import { cn } from '@/lib/utils'

/**
 * F-LED-8: every cached balance must equal the sum of its ledger lines, and the whole
 * ledger must sum to zero. Runs on demand only (it scans the full ledger).
 */
export function AdminReconcilePage() {
  const reduce = useReducedMotion()
  const { data, isFetching, isError, refetch, dataUpdatedAt } = useReconcile()

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title="Reconcile"
        description="Checks that double-entry still holds across every account."
        actions={
          <Button onClick={() => void refetch()} disabled={isFetching}>
            {isFetching ? (
              <Loader2 className="animate-spin" aria-hidden="true" />
            ) : (
              <Play aria-hidden="true" />
            )}
            {data ? 'Run again' : 'Run reconciliation'}
          </Button>
        }
      />

      <div aria-live="polite" aria-busy={isFetching}>
        {isError && !isFetching ? (
          <Card>
            <CardError what="the reconciliation" onRetry={() => void refetch()} />
          </Card>
        ) : !data ? (
          <Card className="flex flex-col items-center py-14 text-center">
            <span className="grid size-14 place-items-center rounded-full bg-surface-2 text-muted">
              {isFetching ? (
                <Loader2 className="size-6 animate-spin" aria-hidden="true" />
              ) : (
                <Scale className="size-6" aria-hidden="true" />
              )}
            </span>
            <p className="mt-4 font-medium">
              {isFetching ? 'Scanning the ledger…' : 'Not run yet'}
            </p>
            <p className="mt-1 max-w-sm text-sm text-muted">
              Compares each account’s cached balance with its ledger lines, and sums every line in
              the ledger.
            </p>
          </Card>
        ) : (
          <motion.div
            key={dataUpdatedAt}
            initial={reduce ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, ease: EASE_OUT_EXPO }}
            className={cn('space-y-4', isFetching && 'opacity-60')}
          >
            <Card
              className={cn(
                'flex flex-col gap-4 sm:flex-row sm:items-center',
                data.balanced ? 'border-gain/40' : 'border-loss/50',
              )}
            >
              <span
                className={cn(
                  'grid size-14 shrink-0 place-items-center rounded-full',
                  data.balanced ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss',
                )}
              >
                {data.balanced ? (
                  <CircleCheck className="size-7" aria-hidden="true" />
                ) : (
                  <CircleAlert className="size-7" aria-hidden="true" />
                )}
              </span>
              <div className="flex-1">
                <p className="font-display text-3xl">
                  {data.balanced ? 'Balanced' : 'Out of balance'}
                </p>
                <p className="mt-1 text-sm text-muted">
                  Checked {formatAbsolute(new Date(dataUpdatedAt).toISOString())}
                </p>
              </div>
              <dl className="grid grid-cols-2 gap-6 text-sm sm:text-right">
                <div>
                  <dt className="text-xs text-muted">Ledger sum</dt>
                  <dd
                    className={cn(
                      'mt-0.5 text-lg',
                      !new Decimal(data.ledgerSum).isZero() && 'text-loss',
                    )}
                  >
                    <Money value={data.ledgerSum} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">Mismatches</dt>
                  <dd
                    className={cn('num mt-0.5 text-lg', data.mismatches.length > 0 && 'text-loss')}
                  >
                    {data.mismatches.length}
                  </dd>
                </div>
              </dl>
            </Card>

            {data.mismatches.length > 0 && (
              <Card className="p-0 sm:p-0">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <caption className="px-5 pt-4 text-left font-medium">
                      Accounts that don’t match
                    </caption>
                    <thead>
                      <tr className="border-b border-border text-left text-xs text-muted">
                        <th scope="col" className="px-5 py-3 font-normal">
                          Account
                        </th>
                        <th scope="col" className="py-3 pr-3 text-right font-normal">
                          Cached
                        </th>
                        <th scope="col" className="py-3 pr-3 text-right font-normal">
                          Ledger
                        </th>
                        <th scope="col" className="py-3 pr-5 text-right font-normal">
                          Difference
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.mismatches.map((m) => (
                        <tr
                          key={m.ledgerAccountId}
                          className="border-b border-border last:border-0"
                        >
                          <td className="px-5 py-3">
                            {m.code ? (
                              <span className="num">{m.code}</span>
                            ) : (
                              <span className="text-muted">Customer account</span>
                            )}
                            <span
                              className="num block text-xs text-muted"
                              title={m.ledgerAccountId}
                            >
                              {m.ledgerAccountId.slice(0, 8)}…
                            </span>
                          </td>
                          <td className="py-3 pr-3 text-right">
                            <Money value={m.cachedBalance} />
                          </td>
                          <td className="py-3 pr-3 text-right">
                            <Money value={m.computedBalance} />
                          </td>
                          <td className="py-3 pr-5 text-right">
                            <Money
                              value={new Decimal(m.cachedBalance).minus(m.computedBalance)}
                              tone="pnl"
                            />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Explanations mismatches={data.mismatches} />
              </Card>
            )}
          </motion.div>
        )}
      </div>

      <IntegrityChecks />
    </div>
  )
}

/**
 * The Reconcile Explainer's drafts (F-RISK-6): advice only. Nothing in the ledger changes; a fix is
 * still a reversing entry made by a person.
 */
function Explanations({ mismatches }: { mismatches: ReconcileMismatch[] }) {
  const ai = useFlag('ai')
  const [trace, setTrace] = useState<string | null>(null)
  const written = mismatches.flatMap((m) => (m.explanation ? [{ m, x: m.explanation }] : []))
  if (!ai && written.length === 0) return null
  return (
    <div className="space-y-3 border-t border-border px-5 py-4">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <Sparkles aria-hidden="true" className="size-4 text-violet" /> What may have happened
        <span className="text-xs font-normal text-muted">AI-written, advisory</span>
      </h3>
      {written.length === 0 ? (
        <p className="text-sm text-muted">
          The explainer drafts a note for each mismatch within a minute or two. Run the check again
          to see it.
        </p>
      ) : (
        <ul className="space-y-3">
          {written.map(({ m, x }) => {
            return (
              <li
                key={m.ledgerAccountId}
                className="rounded-[var(--radius-control)] bg-violet/[0.06] p-3 text-sm hairline"
              >
                <p className="text-xs text-muted">
                  {m.code ?? 'Customer account'} ·{' '}
                  <span className="num">{m.ledgerAccountId.slice(0, 8)}…</span>
                </p>
                <p className="mt-1 font-medium">{x.likelyCause}</p>
                <p className="mt-1 whitespace-pre-wrap text-muted">{x.summary}</p>
                {x.affectedEntries.length > 0 && (
                  <p className="mt-2 text-xs text-muted">
                    Entries to look at:{' '}
                    {x.affectedEntries.map((id, i) => (
                      <span key={id} className="num" title={id}>
                        {i > 0 && ', '}
                        {id.slice(0, 8)}
                      </span>
                    ))}
                  </p>
                )}
                {x.runId && (
                  <button
                    type="button"
                    onClick={() => setTrace(x.runId)}
                    className="mt-2 text-xs text-muted hover:text-violet"
                  >
                    See how it was written →
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      <p className="text-xs text-muted">
        Fix mismatches with reversing entries. The draft never changes the ledger.
      </p>
      {trace && <TraceDialog runId={trace} admin onClose={() => setTrace(null)} />}
    </div>
  )
}

/**
 * Proof of Balance back office (PRD §12.10): the ledger hash chain is intact, and assets cover
 * customer balances. Both run on demand. A full check walks every seal from genesis; a quick
 * check starts at the last checkpoint, so it can't see an edit made before it (F-POB-4).
 */
function IntegrityChecks() {
  const chain = useMutation({ mutationFn: (from: ChainVerifyFrom) => proofApi.verifyChain(from) })
  const fromPos = chain.data?.fromPosition
  const solvency = useQuery({
    queryKey: ['admin', 'solvency'],
    queryFn: proofApi.solvency,
    retry: false,
  })
  return (
    <section aria-label="Ledger integrity" className="mt-6 grid gap-4 md:grid-cols-2">
      <Card className="flex flex-col">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="font-medium">Hash chain</h2>
            <p className="mt-1 text-sm text-muted">
              Re-hashes sealed journal entries and checks each link. The quick check starts at the
              last checkpoint; only a full check catches an edit made before it.
            </p>
          </div>
          <div className="flex shrink-0 flex-col gap-1.5 sm:flex-row">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => chain.mutate('CHECKPOINT')}
              disabled={chain.isPending}
            >
              Quick check
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => chain.mutate('GENESIS')}
              disabled={chain.isPending}
            >
              {chain.isPending ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Link2 aria-hidden="true" />
              )}
              Full check
            </Button>
          </div>
        </div>
        <div aria-live="polite" className="mt-4 text-sm">
          {chain.isError ? (
            <p className="text-loss">{errorMessage(chain.error)}</p>
          ) : chain.data ? (
            chain.data.intact ? (
              <p className="flex items-center gap-2 text-gain">
                <CircleCheck aria-hidden="true" className="size-4" /> Intact through seal #
                {chain.data.checkedUpTo.toLocaleString('en-IN')}
                {fromPos != null &&
                  (fromPos <= 1
                    ? ' (from genesis)'
                    : fromPos > chain.data.checkedUpTo
                      ? ' (nothing new since the last checkpoint)'
                      : ` (from checkpoint #${fromPos.toLocaleString('en-IN')})`)}
              </p>
            ) : (
              <p role="alert" className="flex items-center gap-2 text-loss">
                <CircleAlert aria-hidden="true" className="size-4" /> Broken at seal #
                {chain.data.firstBrokenPosition?.toLocaleString('en-IN')}. Stop and investigate.
              </p>
            )
          ) : (
            <p className="text-muted">{chain.isPending ? 'Walking the chain…' : 'Not run yet.'}</p>
          )}
        </div>
      </Card>
      <Card>
        <h2 className="font-medium">Solvency</h2>
        <p className="mt-1 text-sm text-muted">Assets held against what customers are owed.</p>
        {solvency.isError ? (
          <p className="mt-4 text-sm text-muted">
            {toApiError(solvency.error).code === 'NOT_FOUND'
              ? 'Not available until Proof of Balance ships.'
              : errorMessage(solvency.error)}
          </p>
        ) : !solvency.data ? (
          <Skeleton className="mt-4 h-16" />
        ) : (
          <dl className="mt-4 grid grid-cols-3 gap-3 text-sm">
            <div>
              <dt className="text-xs text-muted">Owed to customers</dt>
              <dd className="mt-0.5">
                <Money value={solvency.data.totalLiabilities} />
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Assets</dt>
              <dd className="mt-0.5">
                <Money value={solvency.data.totalAssets} />
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted">Ratio</dt>
              <dd
                className={cn(
                  'num mt-0.5',
                  new Decimal(solvency.data.ratio).lt(1) ? 'text-loss' : 'text-gain',
                )}
              >
                {new Decimal(solvency.data.ratio).toFixed(2)}×
              </dd>
            </div>
          </dl>
        )}
      </Card>
    </section>
  )
}
