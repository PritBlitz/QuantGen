import { forwardRef, type InputHTMLAttributes, type LabelHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'h-11 w-full rounded-[var(--radius-control)] bg-surface-2 px-3.5 text-[0.9375rem] text-text',
        'hairline placeholder:text-muted/70',
        'transition-[border-color,box-shadow] duration-[var(--dur-micro)]',
        'hover:border-muted/40 focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30',
        'aria-[invalid=true]:border-loss aria-[invalid=true]:focus-visible:ring-loss/30',
        'disabled:opacity-50',
        className,
      )}
      {...props}
    />
  ),
)
Input.displayName = 'Input'

export function Label({ className, ...props }: LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn('text-sm font-medium text-text', className)} {...props} />
}

export function FieldMessage({ id, children }: { id: string; children?: string }) {
  if (!children) return null
  return (
    <p id={id} role="alert" className="text-xs text-loss">
      {children}
    </p>
  )
}
