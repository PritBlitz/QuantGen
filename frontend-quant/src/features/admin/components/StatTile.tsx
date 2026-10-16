import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

/** Dense KPI tile for the admin console (DESIGN.md §4: utilitarian, no flourish). */
export function StatTile({
  label,
  icon: Icon,
  value,
  hint,
  loading,
}: {
  label: string
  icon: LucideIcon
  value: ReactNode
  hint?: ReactNode
  loading?: boolean
}) {
  return (
    <Card className="p-4 sm:p-5">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-medium text-muted">{label}</p>
        <Icon aria-hidden="true" className="size-4 text-muted" />
      </div>
      {loading ? (
        <Skeleton className="mt-3 h-8 w-24" />
      ) : (
        <p className="num mt-2 text-2xl font-medium tracking-tight sm:text-3xl">{value}</p>
      )}
      {hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </Card>
  )
}
