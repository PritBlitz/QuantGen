import { motion, useReducedMotion } from 'motion/react'
import { EASE_OUT_EXPO } from '@/lib/motion'

/** Animated progress ring (value 0–1, display only). */
const R = 42
const C = 2 * Math.PI * R

/** `size` in px (default 128). */
export function ProgressRing({
  value,
  label,
  size = 128,
  caption = 'repaid',
}: {
  value: number
  label: string
  size?: number
  caption?: string
}) {
  const reduce = useReducedMotion()
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 100" className="size-full -rotate-90" role="img" aria-label={label}>
        <circle cx="50" cy="50" r={R} fill="none" stroke="var(--surface-2)" strokeWidth="8" />
        <motion.circle
          cx="50"
          cy="50"
          r={R}
          fill="none"
          stroke="var(--accent)"
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={C}
          initial={{ strokeDashoffset: reduce ? C * (1 - value) : C }}
          animate={{ strokeDashoffset: C * (1 - value) }}
          transition={{ duration: reduce ? 0 : 0.9, ease: EASE_OUT_EXPO }}
        />
      </svg>
      <div className="absolute inset-0 grid place-items-center text-center" aria-hidden="true">
        <div>
          <p className={size < 100 ? 'num text-base font-semibold' : 'num text-xl font-semibold'}>{Math.round(value * 100)}%</p>
          <p className="text-[0.6875rem] text-muted">{caption}</p>
        </div>
      </div>
    </div>
  )
}
