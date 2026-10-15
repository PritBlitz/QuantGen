import type { Transition, Variants } from 'motion/react'

/** DESIGN.md §2 motion tokens. */
export const EASE_OUT_EXPO = [0.22, 1, 0.36, 1] as const

export const DURATION = {
  micro: 0.12,
  ui: 0.24,
  page: 0.32,
  reveal: 0.8,
} as const

export const SPRING_LAYOUT: Transition = { type: 'spring', stiffness: 300, damping: 30 }

export const STAGGER = 0.04
export const STAGGER_CAP = 8

/** Page transition: fade + 8px rise; old page leaves fast (120ms). */
export const pageVariants: Variants = {
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0, transition: { duration: DURATION.page, ease: EASE_OUT_EXPO } },
  exit: { opacity: 0, transition: { duration: DURATION.micro } },
}

/** Opacity-only version for prefers-reduced-motion. */
export const pageVariantsReduced: Variants = {
  initial: { opacity: 0 },
  animate: { opacity: 1, transition: { duration: DURATION.micro } },
  exit: { opacity: 0, transition: { duration: DURATION.micro } },
}

/** List item delay with the 8-item cap. */
export const staggerDelay = (index: number) => Math.min(index, STAGGER_CAP) * STAGGER
