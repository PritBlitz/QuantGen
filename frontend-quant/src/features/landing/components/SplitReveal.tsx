import { motion, useReducedMotion } from 'motion/react'
import type { ReactNode } from 'react'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { cn } from '@/lib/utils'

/**
 * Split-line text reveal (DESIGN.md §2, landing only): each line rises out of a mask.
 * Reduced motion → a plain opacity fade.
 */
export function SplitReveal({
  lines,
  className,
  lineClassName,
  delay = 0,
  as: Tag = 'h1',
}: {
  lines: ReactNode[]
  className?: string
  lineClassName?: string
  delay?: number
  as?: 'h1' | 'h2' | 'p'
}) {
  const reduce = useReducedMotion()
  return (
    <Tag className={className}>
      {lines.map((line, i) => (
        <span key={i} className="block overflow-hidden pb-[0.08em]">
          <motion.span
            className={cn('block', lineClassName)}
            initial={reduce ? { opacity: 0 } : { y: '105%' }}
            whileInView={reduce ? { opacity: 1 } : { y: '0%' }}
            viewport={{ once: true, margin: '-10% 0px' }}
            transition={{
              duration: reduce ? 0.2 : 0.9,
              ease: EASE_OUT_EXPO,
              delay: delay + i * 0.08,
            }}
          >
            {line}
          </motion.span>
        </span>
      ))}
    </Tag>
  )
}

/** Fade + rise on scroll into view. */
export function Reveal({
  children,
  className,
  delay = 0,
}: {
  children: ReactNode
  className?: string
  delay?: number
}) {
  const reduce = useReducedMotion()
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: reduce ? 0 : 24 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-10% 0px' }}
      transition={{ duration: reduce ? 0.2 : 0.7, ease: EASE_OUT_EXPO, delay }}
    >
      {children}
    </motion.div>
  )
}
