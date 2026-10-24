import { Check, CircleAlert, History } from 'lucide-react'
import type { SimulationDto } from '@/api/types'
import { Money } from '@/components/Money'
import { formatDate } from '@/lib/time'
import { cn } from '@/lib/utils'

/**
 * The 90-day replay (F-AUTO-3): what the rule would have done against the user's own history.
 * Computed by the server's simulator, which never posts to the ledger.
 */
export function SimulationView({ sim, days = 90 }: { sim: SimulationDto; days?: number }) {
  const actions = sim.firings.reduce((n, f) => n + f.actions.length, 0)
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-2 text-center">
        <Stat label="Times it ran" value={String(sim.firings.length)} />
        <Stat label="Would have moved" value={<Money value={sim.totalMoved} />} />
        <Stat
          label="Blocked"
          value={`${sim.failures} of ${actions}`}
          tone={sim.failures ? 'warn' : undefined}
        />
      </div>
      {sim.firings.length === 0 ? (
        <p className="flex items-start gap-2 rounded-[var(--radius-control)] bg-surface-2/60 p-3.5 text-sm text-muted">
          <History aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          In the last {days} days this rule wouldn’t have run at all. That can be fine (for example,
          a price that was never reached), but check the trigger.
        </p>
      ) : (
        <ol
          aria-label={`What would have happened in the last ${days} days`}
          className="scroll-thin max-h-[420px] space-y-2 overflow-y-auto pr-1"
        >
          {sim.firings.map((f, i) => (
            <li
              key={`${f.date}-${i}`}
              className="rounded-[var(--radius-control)] bg-surface-2/50 p-3 hairline"
            >
              <p className="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
                <span className="font-medium">{f.triggerSummary}</span>
                <span className="text-xs text-muted">{formatDate(f.date)}</span>
              </p>
              <ul className="mt-2 space-y-1">
                {f.actions.map((a, j) => (
                  <li
                    key={j}
                    className={cn(
                      'flex items-start gap-2 text-sm',
                      !a.wouldSucceed && 'text-muted',
                    )}
                  >
                    {a.wouldSucceed ? (
                      <Check
                        aria-label="Would succeed"
                        className="mt-0.5 size-3.5 shrink-0 text-gain"
                      />
                    ) : (
                      <CircleAlert
                        aria-label="Would be blocked"
                        className="mt-0.5 size-3.5 shrink-0 text-warn"
                      />
                    )}
                    <span className="min-w-0 flex-1">
                      {a.summary}
                      {a.reason && <span className="text-warn"> · {a.reason}</span>}
                    </span>
                    <Money
                      value={a.amount}
                      className={cn('shrink-0', !a.wouldSucceed && 'line-through')}
                    />
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

function Stat({ label, value, tone }: { label: string; value: React.ReactNode; tone?: 'warn' }) {
  return (
    <div className="rounded-[var(--radius-control)] bg-surface-2/60 px-2 py-3 hairline">
      <p className="text-xs text-muted">{label}</p>
      <p className={cn('mt-0.5 font-medium tabular-nums', tone === 'warn' && 'text-warn')}>
        {value}
      </p>
    </div>
  )
}
