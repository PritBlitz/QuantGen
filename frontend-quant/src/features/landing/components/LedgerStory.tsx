import { AnimatePresence, motion, useInView } from 'motion/react'
import { Lock } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Money } from '@/components/Money'
import { DURATION, EASE_OUT_EXPO } from '@/lib/motion'
import { cn } from '@/lib/utils'

const STEPS = [
  {
    title: 'Validate',
    body: 'Two or more lines, every amount positive, debits equal to credits. Unbalanced entries never reach the database.',
  },
  {
    title: 'Lock in order',
    body: 'The accounts involved are locked in ascending id order, so two transfers can’t deadlock or race each other.',
  },
  {
    title: 'Post, all or nothing',
    body: 'The journal entry, its lines and the new balances commit in one transaction. A failure halfway rolls back everything.',
  },
  {
    title: 'Never rewrite history',
    body: 'Ledger rows are insert-only. A mistake is fixed with a reversing entry, so the audit trail stays complete.',
  },
] as const

function Step({ index, onActive }: { index: number; onActive: (i: number) => void }) {
  const ref = useRef<HTMLLIElement>(null)
  const inView = useInView(ref, { margin: '-45% 0px -45% 0px' })
  useEffect(() => {
    if (inView) onActive(index)
  }, [inView, index, onActive])
  const step = STEPS[index]
  if (!step) return null
  return (
    <li ref={ref} className="flex flex-col justify-center py-12 lg:min-h-[70vh] lg:py-0">
      <p className="num text-sm text-accent">0{index + 1}</p>
      <h3 className="mt-3 font-display text-4xl">{step.title}</h3>
      <p className="mt-4 max-w-md text-muted">{step.body}</p>
      {/* Mobile: each step carries its own visual instead of a sticky one. */}
      <div className="mt-8 lg:hidden">
        <Visual active={index} />
      </div>
    </li>
  )
}

function Visual({ active }: { active: number }) {
  const balanced = active >= 0
  const locked = active >= 1
  const posted = active >= 2
  const reversed = active >= 3
  const rows: Array<[string, string, 'DEBIT' | 'CREDIT']> = [
    ['acct 0192 · Savings', '12000.00', 'DEBIT'],
    ['acct 0457 · Savings', '12000.00', 'CREDIT'],
  ]
  return (
    <div className="rounded-[20px] bg-surface p-5 hairline shadow-[var(--shadow-pop)] sm:p-6">
      <div className="flex items-center justify-between text-xs text-muted">
        <span className="num">journal_entry #8f3a…</span>
        <span
          className={cn(
            'rounded-full px-2.5 py-1 transition-colors duration-[var(--dur-ui)]',
            posted ? 'bg-gain/15 text-gain' : 'bg-surface-2',
          )}
        >
          {posted ? 'COMMITTED' : 'PENDING'}
        </span>
      </div>
      <ul className="mt-5 space-y-2">
        {rows.map(([acct, amt, dir]) => (
          <li
            key={acct}
            className="flex items-center gap-3 rounded-[var(--radius-control)] bg-surface-2 px-4 py-3 text-sm"
          >
            <span
              aria-hidden="true"
              className={cn(
                'grid size-6 place-items-center rounded-full text-[0.625rem] transition-colors',
                locked ? 'bg-accent text-accent-ink' : 'bg-bg text-muted',
              )}
            >
              {locked ? <Lock className="size-3" /> : '·'}
            </span>
            <span className="flex-1 truncate">{acct}</span>
            <span className="num w-14 text-xs text-muted">{dir}</span>
            <Money value={amt} />
          </li>
        ))}
        <AnimatePresence>
          {reversed && (
            <motion.li
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: DURATION.ui, ease: EASE_OUT_EXPO }}
              className="rounded-[var(--radius-control)] border border-dashed border-border px-4 py-3 text-xs text-muted"
            >
              Reversal #9b1c… mirrors these lines. The original row is untouched.
            </motion.li>
          )}
        </AnimatePresence>
      </ul>
      <div className="mt-5 flex items-center justify-between border-t border-border pt-4 text-sm">
        <span className="text-muted">Σ debits − Σ credits</span>
        <span className={cn('num font-semibold', balanced ? 'text-gain' : 'text-loss')}>₹0.00</span>
      </div>
    </div>
  )
}

/** Sticky "how the ledger works" scroll story (DESIGN.md §2). */
export function LedgerStory() {
  const [active, setActive] = useState(0)
  return (
    <section id="ledger" className="mx-auto max-w-[1280px] scroll-mt-24 px-4 sm:px-6 lg:px-10">
      <div className="max-w-2xl">
        <p className="text-xs uppercase tracking-[0.18em] text-muted">How the ledger works</p>
        <h2 className="mt-4 font-display text-4xl sm:text-5xl">Four steps behind every rupee.</h2>
      </div>
      <div className="mt-8 grid grid-cols-1 lg:grid-cols-2 lg:gap-20">
        <div className="sticky top-[20vh] hidden self-start lg:block">
          <Visual active={active} />
          <ol aria-hidden="true" className="mt-4 flex gap-1.5">
            {STEPS.map((s, i) => (
              <li
                key={s.title}
                className={cn(
                  'h-1 flex-1 rounded-full transition-colors duration-[var(--dur-ui)]',
                  i <= active ? 'bg-accent' : 'bg-surface-2',
                )}
              />
            ))}
          </ol>
        </div>
        <ol>
          {STEPS.map((s, i) => (
            <Step key={s.title} index={i} onActive={setActive} />
          ))}
        </ol>
      </div>
    </section>
  )
}
