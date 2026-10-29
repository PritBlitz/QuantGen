import { ArrowLeft } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { EASE_OUT_EXPO } from '@/lib/motion'

export function PageHeader({
  title,
  description,
  actions,
  back,
  eyebrow,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  back?: { to: string; label: string }
  /** Small uppercase label above the title (section name, date…). */
  eyebrow?: ReactNode
}) {
  const reduce = useReducedMotion()
  return (
    <header className="mb-6 sm:mb-8">
      {back && (
        <Link
          to={back.to}
          className="mb-5 inline-flex items-center gap-1.5 rounded-full py-1 pr-3 text-sm text-muted transition-colors hover:text-text"
        >
          <ArrowLeft aria-hidden="true" className="size-4" /> {back.label}
        </Link>
      )}
      <div className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          {eyebrow && <p className="eyebrow mb-2">{eyebrow}</p>}
          <motion.h1
            initial={reduce ? false : { opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.3, ease: EASE_OUT_EXPO }}
            className="font-display text-2xl sm:text-[1.75rem]"
          >
            {title}
          </motion.h1>
          {description && (
            <p className="mt-1.5 max-w-2xl text-sm text-muted">{description}</p>
          )}
        </div>
        {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
      </div>
    </header>
  )
}

export function EmptyState({
  icon,
  title,
  children,
  action,
}: {
  icon: ReactNode
  title: string
  children?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="relative flex flex-col items-center overflow-hidden px-4 py-12 text-center">
      <div
        aria-hidden="true"
        className="dot-grid pointer-events-none absolute inset-0 opacity-40 [mask-image:radial-gradient(circle_at_center,black,transparent_65%)]"
      />
      <span className="relative grid size-14 place-items-center rounded-2xl bg-surface-2 text-muted hairline [&_svg]:size-5">
        {icon}
      </span>
      <p className="relative mt-4 font-medium">{title}</p>
      {children && <div className="relative mt-1 max-w-sm text-sm text-muted">{children}</div>}
      {action && <div className="relative mt-5">{action}</div>}
    </div>
  )
}
