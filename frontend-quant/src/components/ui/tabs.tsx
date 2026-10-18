import { motion } from 'motion/react'
import { Tabs as T } from 'radix-ui'
import { useId, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * Page-level tabs (switch panels; for filtering one list use `Segmented`). The active pill glides
 * between triggers. Radix supplies roving focus and the tab/tabpanel ARIA wiring.
 */
export function Tabs<V extends string>({
  value,
  onValueChange,
  tabs,
  label,
  className,
  children,
}: {
  value: V
  onValueChange: (v: V) => void
  tabs: Array<{ value: V; label: ReactNode; badge?: ReactNode }>
  label: string
  className?: string
  children: ReactNode
}) {
  const pill = useId()
  return (
    <T.Root value={value} onValueChange={(v) => onValueChange(v as V)} className={className}>
      <T.List
        aria-label={label}
        className="scroll-thin mb-6 inline-flex max-w-full gap-1 overflow-x-auto rounded-full bg-surface-2/80 p-1 hairline"
      >
        {tabs.map((t) => (
          <T.Trigger
            key={t.value}
            value={t.value}
            className={cn(
              'relative shrink-0 whitespace-nowrap rounded-full px-4 py-1.5 text-sm transition-colors',
              value === t.value ? 'text-text' : 'text-muted hover:text-text',
            )}
          >
            {value === t.value && (
              <motion.span
                layoutId={pill}
                transition={{ type: 'spring', stiffness: 420, damping: 34 }}
                className="absolute inset-0 -z-0 rounded-full bg-surface-solid shadow-sm hairline"
              />
            )}
            <span className="relative z-10 flex items-center gap-1.5">
              {t.label}
              {t.badge}
            </span>
          </T.Trigger>
        ))}
      </T.List>
      {children}
    </T.Root>
  )
}

export const TabPanel = ({ value, children, className }: { value: string; children: ReactNode; className?: string }) => (
  <T.Content value={value} className={cn('focus-visible:outline-none', className)}>
    {children}
  </T.Content>
)
