import { ArrowRight, CalendarClock, ShieldCheck, Sparkles, Workflow } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useMemo } from 'react'
import { Link } from 'react-router'
import { isUnavailable } from '@/api/availability'
import { useFlag } from '@/api/flags'
import { useLoans } from '@/api/queries'
import { Money } from '@/components/Money'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useMoneySummary } from '@/features/analytics/hooks'
import { useRules } from '@/features/autopilot/hooks'
import { useCopilotUi } from '@/features/copilot/uiStore'
import { useBudgets } from '@/features/insights/hooks'
import { useRequests, useScheduled } from '@/features/payments/hooks'
import { useProofRoots } from '@/features/proof/hooks'
import { categoryOf } from '@/lib/categories'
import { formatINR, ratio, sumMoney, toDecimal } from '@/lib/money'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { rangeFor } from '@/lib/range'
import { formatDate, formatRelative } from '@/lib/time'
import { cn } from '@/lib/utils'
import { DashCard } from './CardShell'

const PROMPTS = [
  'How much did I spend on food this month?',
  'Can I afford a ₹3L loan?',
  'Pay Meera 500 for dinner',
  'What’s my balance?',
]

/** Copilot entry strip (V2-M4). Hidden when the `ai` flag is off. */
export function CopilotStrip({ className }: { className?: string }) {
  const on = useFlag('ai')
  const open = useCopilotUi((s) => s.open)
  const reduce = useReducedMotion()
  if (!on) return null
  return (
    <section
      aria-label="Ask Copilot"
      className={cn(
        'relative isolate overflow-hidden rounded-[var(--radius-card)] p-5 hairline sm:p-6',
        'bg-[radial-gradient(120%_140%_at_0%_0%,color-mix(in_oklab,var(--violet)_22%,transparent),transparent_55%),radial-gradient(100%_120%_at_100%_100%,color-mix(in_oklab,var(--cyan)_14%,transparent),transparent_60%)] bg-surface',
        className,
      )}
    >
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
        <div className="flex items-center gap-3 lg:w-72 lg:shrink-0">
          <span className="relative grid size-11 place-items-center rounded-2xl bg-violet text-violet-ink">
            {!reduce && <span className="absolute inset-0 rounded-2xl border border-violet animate-pulse-ring" />}
            <Sparkles aria-hidden="true" className="size-5" />
          </span>
          <div>
            <p className="font-medium">Ask Copilot</p>
            <p className="text-xs text-muted">Six AI agents, one rule: you approve every rupee.</p>
          </div>
        </div>
        <ul className="flex flex-1 flex-wrap gap-2">
          {PROMPTS.map((p, i) => (
            <motion.li key={p} initial={reduce ? false : { opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: 0.06 * i, duration: 0.4, ease: EASE_OUT_EXPO }}>
              <button
                type="button"
                onClick={() => open(p)}
                className="rounded-full bg-surface-solid/70 px-3.5 py-2 text-sm text-text hairline transition-colors hover:border-violet/50 hover:bg-violet/10"
              >
                {p}
              </button>
            </motion.li>
          ))}
        </ul>
      </div>
    </section>
  )
}

/** This month's spending vs budgets (V2-MA / V2-M3). Hidden if the endpoints don't exist yet. */
export function SpendingCard({ className }: { className?: string }) {
  const range = useMemo(() => rangeFor('30D'), [])
  const summary = useMoneySummary(range)
  const budgets = useBudgets()
  if (isUnavailable(summary.error)) return null
  const s = summary.data
  return (
    <DashCard
      title="Spending · 30 days"
      labelledBy="spend-title"
      className={className}
      action={
        <Link to="/insights?tab=spending" className="text-sm text-muted hover:text-text">
          Insights
        </Link>
      }
    >
      {!s ? (
        <Skeleton className="h-40 w-full" />
      ) : (
        <div className="flex flex-1 flex-col">
          <p className="num text-3xl font-medium">{formatINR(s.spending, { compact: true })}</p>
          <p className="mt-1 text-xs text-muted">
            Saved <span className="text-text">{s.savingsRatePct ? `${toDecimal(s.savingsRatePct).toDecimalPlaces(0).toString()}%` : '—'}</span> of what came in
          </p>
          <ul className="mt-5 space-y-3">
            {(budgets.data ?? []).slice(0, 3).map((b) => {
              const c = categoryOf(b.category)
              const over = toDecimal(b.pct).gte(100)
              const warn = toDecimal(b.pct).gte(80)
              return (
                <li key={b.category}>
                  <div className="mb-1 flex justify-between text-xs">
                    <span className="flex items-center gap-1.5 text-muted">
                      <c.icon aria-hidden="true" className="size-3.5" /> {c.label}
                    </span>
                    <span className={cn('num', over ? 'text-loss' : warn ? 'text-warn' : 'text-muted')}>
                      {formatINR(b.spent, { compact: true })} / {formatINR(b.monthlyLimit, { compact: true })}
                    </span>
                  </div>
                  <div className="h-1.5 overflow-hidden rounded-full bg-surface-2">
                    <div className={cn('h-full rounded-full', over ? 'bg-loss' : warn ? 'bg-warn' : 'bg-gain')} style={{ width: `${Math.min(100, ratio(b.spent, b.monthlyLimit) * 100)}%` }} />
                  </div>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </DashCard>
  )
}

/** Next scheduled transfers and the next EMI (F-PAY-9). */
export function UpcomingCard({ className }: { className?: string }) {
  const scheduled = useScheduled()
  const loans = useLoans()
  const incoming = useRequests('INCOMING', 'OPEN')
  if (isUnavailable(scheduled.error) && !loans.data?.some((l) => l.status === 'ACTIVE')) return null
  const items = [
    ...(scheduled.data ?? [])
      .filter((s) => s.status === 'ACTIVE' && s.nextRunDate)
      .map((s) => ({ key: s.id, date: s.nextRunDate!, label: s.note ?? `To ${s.toName}`, sub: `${s.frequency.toLowerCase()} · ${s.toAccountNumberMasked}`, amount: s.amount })),
    ...(loans.data ?? [])
      .filter((l) => l.status === 'ACTIVE' && l.nextDueDate)
      .map((l) => ({ key: l.id, date: l.nextDueDate!, label: 'Loan EMI', sub: 'auto-debit at 06:00', amount: l.emi })),
    ...(incoming.data?.content ?? []).map((r) => ({ key: r.id, date: r.expiresAt.slice(0, 10), label: `${r.fromHandle} asked you`, sub: r.note ? `“${r.note}” · pay by` : 'pay by', amount: r.amount })),
  ]
    .sort((a, b) => a.date.localeCompare(b.date))
    .slice(0, 5)
  return (
    <DashCard
      title="Coming up"
      labelledBy="upcoming-title"
      className={className}
      action={
        <Link to="/pay/scheduled" className="text-sm text-muted hover:text-text">
          Schedules
        </Link>
      }
    >
      {scheduled.isPending && loans.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : items.length === 0 ? (
        <p className="py-6 text-sm text-muted">Nothing scheduled. Set up rent or a SIP so you never forget.</p>
      ) : (
        <ol className="relative space-y-4 pl-5 before:absolute before:inset-y-1 before:left-[5px] before:w-px before:bg-border">
          {items.map((it) => (
            <li key={it.key} className="relative">
              <span className="absolute -left-5 top-1 size-[11px] rounded-full border-2 border-accent bg-bg" aria-hidden="true" />
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{it.label}</p>
                  <p className="text-xs text-muted">
                    {formatDate(it.date)} · {it.sub}
                  </p>
                </div>
                <Money value={it.amount} className="text-sm" />
              </div>
            </li>
          ))}
        </ol>
      )}
    </DashCard>
  )
}

/** Autopilot status (V2-M5). Hidden when the flag is off or the endpoint isn't there. */
export function AutopilotCard({ className }: { className?: string }) {
  const on = useFlag('autopilot')
  const rules = useRules(on)
  if (!on || isUnavailable(rules.error)) return null
  const active = rules.data?.filter((r) => r.status === 'ACTIVE') ?? []
  const paused = rules.data?.filter((r) => r.status === 'PAUSED') ?? []
  const moved = sumMoney(active.map((r) => r.movedThisMonth))
  return (
    <DashCard title="Autopilot" labelledBy="autopilot-title" className={cn(className)} >
      {!rules.data ? (
        <Skeleton className="h-36 w-full" />
      ) : (
        <div className="flex flex-1 flex-col">
          <div className="flex items-center gap-3">
            <span className="grid size-11 place-items-center rounded-2xl bg-violet/14 text-violet">
              <Workflow aria-hidden="true" className="size-5" />
            </span>
            <div>
              <p className="text-2xl font-medium">
                {active.length} <span className="text-sm font-normal text-muted">active rule{active.length === 1 ? '' : 's'}</span>
              </p>
              <p className="text-xs text-muted">
                Moved <Money value={moved} className="text-text" /> this month
              </p>
            </div>
          </div>
          {paused.length > 0 && (
            <p className="mt-4 rounded-xl border border-warn/30 bg-warn/[0.07] px-3 py-2 text-xs">
              <Badge tone="warn">{paused.length} paused</Badge> <span className="ml-1 text-muted">{paused[0]!.pausedReason}</span>
            </p>
          )}
          {active[0]?.lastRunAt && <p className="mt-3 text-xs text-muted">Last run {formatRelative(active[0].lastRunAt)} · “{active[0].name}”</p>}
          <Button asChild variant="secondary" size="sm" className="mt-auto self-start">
            <Link to="/autopilot">
              Open Autopilot <ArrowRight aria-hidden="true" />
            </Link>
          </Button>
        </div>
      )}
    </DashCard>
  )
}

/** Proof of Balance teaser (V2-M8). */
export function ProofCard({ className }: { className?: string }) {
  const roots = useProofRoots()
  const latest = roots.data?.[0]
  return (
    <DashCard title="Proof of Balance" labelledBy="proof-title" className={className}>
      <div className="flex flex-1 flex-col">
        <div className="flex items-start gap-3">
          <span className="relative grid size-11 shrink-0 place-items-center rounded-2xl bg-gain/12 text-gain">
            <ShieldCheck aria-hidden="true" className="size-5" />
          </span>
          <p className="text-sm text-muted">
            Every night we publish one fingerprint of all balances. Check, in your own browser, that yours is in it and history wasn’t rewritten.
          </p>
        </div>
        {latest ? (
          <p className="num mt-4 truncate rounded-xl bg-surface-2/70 px-3 py-2 text-xs text-muted hairline" title={latest.root}>
            <CalendarClock aria-hidden="true" className="mr-1.5 inline size-3.5" />
            {formatDate(latest.date)} · {latest.root.slice(0, 20)}…
          </p>
        ) : roots.isPending ? (
          <Skeleton className="mt-4 h-8 w-full" />
        ) : null}
        <Button asChild size="sm" className="mt-auto self-start pt-0">
          <Link to="/proof">Verify my balance</Link>
        </Button>
      </div>
    </DashCard>
  )
}
