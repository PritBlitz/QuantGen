import { CircleAlert, CircleCheck, Loader2, Play, Scale } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useReconcile } from '@/api/queries'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
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
              </Card>
            )}
          </motion.div>
        )}
      </div>
    </div>
  )
}
