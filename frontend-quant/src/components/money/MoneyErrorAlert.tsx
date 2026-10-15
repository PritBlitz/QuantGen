import { AlertCircle } from 'lucide-react'
import type { ReactNode } from 'react'
import type { MoneyErrorCopy } from '@/lib/moneyErrors'

export function MoneyErrorAlert({
  copy,
  children,
}: {
  copy: MoneyErrorCopy
  children?: ReactNode
}) {
  return (
    <div
      role="alert"
      className="flex items-start gap-3 rounded-[var(--radius-control)] border border-loss/40 bg-loss/10 p-3.5 text-sm"
    >
      <AlertCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-loss" />
      <div className="min-w-0 flex-1">
        <p className="font-medium">{copy.title}</p>
        {copy.detail && <p className="mt-0.5 text-muted">{copy.detail}</p>}
        {children && <div className="mt-2.5 flex flex-wrap gap-2">{children}</div>}
      </div>
    </div>
  )
}
