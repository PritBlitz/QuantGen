import { motion, useReducedMotion } from 'motion/react'
import { EASE_OUT_EXPO } from '@/lib/motion'

const PARTICLES = Array.from({ length: 14 }, (_, i) => {
  const angle = (i / 14) * Math.PI * 2
  return { x: Math.cos(angle) * 70, y: Math.sin(angle) * 70, d: (i % 3) * 0.03, c: i % 3 }
})
const COLORS = ['var(--accent)', 'var(--gain)', 'var(--text)']

/**
 * Check-mark path draw plus a subtle confetti burst, once per action (DESIGN.md §2).
 * Reduced motion: a static check, no confetti.
 */
export function SuccessCheck({ label }: { label: string }) {
  const reduce = useReducedMotion()
  return (
    <div className="relative mx-auto grid size-24 place-items-center" role="img" aria-label={label}>
      {!reduce &&
        PARTICLES.map((p, i) => (
          <motion.span
            key={i}
            aria-hidden="true"
            className="absolute size-1.5 rounded-full"
            style={{ background: COLORS[p.c] }}
            initial={{ x: 0, y: 0, opacity: 0, scale: 0.6 }}
            animate={{ x: p.x, y: p.y, opacity: [0, 1, 0], scale: 1 }}
            transition={{ duration: 0.9, delay: 0.25 + p.d, ease: EASE_OUT_EXPO }}
          />
        ))}
      <svg viewBox="0 0 52 52" className="size-24" aria-hidden="true">
        <motion.circle
          cx="26"
          cy="26"
          r="24"
          fill="none"
          stroke="var(--gain)"
          strokeWidth="2.5"
          initial={{ pathLength: reduce ? 1 : 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: 0.5, ease: EASE_OUT_EXPO }}
        />
        <motion.path
          d="M15 27 l7.5 7.5 L37.5 19"
          fill="none"
          stroke="var(--gain)"
          strokeWidth="3.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          initial={{ pathLength: reduce ? 1 : 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: 0.4, delay: reduce ? 0 : 0.35, ease: EASE_OUT_EXPO }}
        />
      </svg>
    </div>
  )
}
