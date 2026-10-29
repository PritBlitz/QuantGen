import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react'
import { useRef } from 'react'
import { Money } from '@/components/Money'
import { cn } from '@/lib/utils'

/** A composed "screenshot" of the dashboard, built from real components, with
 *  scroll-linked parallax on its layers (disabled under reduced motion). */
export function ProductShot({ className }: { className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const reduce = useReducedMotion()
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'end start'] })
  const yBack = useTransform(scrollYProgress, [0, 1], [40, -40])
  const yFront = useTransform(scrollYProgress, [0, 1], [90, -90])
  const rotate = useTransform(scrollYProgress, [0, 1], [-2, 2])

  return (
    <div
      ref={ref}
      aria-hidden="true"
      className={cn('relative mx-auto w-full max-w-5xl', className)}
    >
      <motion.div
        style={reduce ? undefined : { y: yBack, rotate }}
        className="rounded-[12px] bg-surface p-3 hairline shadow-[var(--shadow-pop)] sm:p-4"
      >
        <div className="grid grid-cols-1 gap-3 rounded-[10px] bg-bg p-4 sm:grid-cols-[1.4fr_1fr] sm:p-6">
          <div className="rounded-[var(--radius-card)] bg-surface p-5 hairline">
            <p className="text-xs uppercase tracking-[0.16em] text-muted">Net worth</p>
            <p className="mt-3 font-display text-[clamp(1.875rem,5vw,2.75rem)] leading-none">
              ₹4,82,615.20
            </p>
            <p className="mt-3 text-sm">
              <Money value="18420.55" tone="pnl" /> <span className="text-muted">this month</span>
            </p>
            <svg viewBox="0 0 300 70" className="mt-6 h-16 w-full" preserveAspectRatio="none">
              <defs>
                <linearGradient id="ps-fill" x1="0" x2="0" y1="0" y2="1">
                  <stop offset="0%" stopColor="var(--gain)" stopOpacity="0.35" />
                  <stop offset="100%" stopColor="var(--gain)" stopOpacity="0" />
                </linearGradient>
              </defs>
              <path
                d="M0 55 C30 50 40 58 70 46 S120 30 150 36 210 18 240 22 280 8 300 6 L300 70 L0 70 Z"
                fill="url(#ps-fill)"
              />
              <path
                d="M0 55 C30 50 40 58 70 46 S120 30 150 36 210 18 240 22 280 8 300 6"
                fill="none"
                stroke="var(--gain)"
                strokeWidth="2"
              />
            </svg>
          </div>
          <div className="grid grid-cols-1 gap-3">
            {(
              [
                ['Savings', '•••• 4821', '184250.75'],
                ['Checking', '•••• 9067', '42310.20'],
              ] as const
            ).map(([label, num, bal]) => (
              <div key={label} className="rounded-[var(--radius-card)] bg-surface p-4 hairline">
                <div className="flex items-center justify-between text-xs text-muted">
                  <span>{label}</span>
                  <span className="num">{num}</span>
                </div>
                <Money value={bal} className="mt-2 block text-xl" />
              </div>
            ))}
          </div>
        </div>
      </motion.div>

      <motion.div
        style={reduce ? undefined : { y: yFront }}
        className="absolute -bottom-10 -right-2 hidden w-64 rounded-[var(--radius-card)] bg-surface p-4 hairline shadow-[var(--shadow-pop)] sm:block lg:-right-10"
      >
        <div className="flex items-center gap-3">
          <span className="grid size-9 place-items-center rounded-full bg-gain/15 text-gain">
            <svg
              viewBox="0 0 24 24"
              className="size-4"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
            >
              <path d="M5 12.5 10 17 19 7" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
          <div>
            <p className="text-sm font-medium">Transfer settled</p>
            <p className="text-xs text-muted">2 ledger lines · balanced</p>
          </div>
        </div>
        <Money value="25000.00" tone="credit" className="mt-3 block text-lg" />
      </motion.div>

      <motion.div
        style={reduce ? undefined : { y: yFront }}
        className="absolute -left-2 -top-8 hidden rounded-full bg-surface px-4 py-2 text-sm hairline shadow-[var(--shadow-pop)] md:block lg:-left-10"
      >
        <span className="font-semibold">QGEN</span> <Money value="1284.50" className="text-muted" />{' '}
        <Money value="22.40" tone="pnl" className="text-xs" />
      </motion.div>
    </div>
  )
}
