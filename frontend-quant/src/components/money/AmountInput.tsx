import type { ReactNode } from 'react'
import { forwardRef, useId, type InputHTMLAttributes } from 'react'
import { FieldMessage, Label } from '@/components/ui/input'
import { sanitizeAmount } from '@/lib/amount'
import { cn } from '@/lib/utils'

interface AmountInputProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'onChange' | 'value' | 'type'
> {
  label: string
  value: string
  onValueChange: (value: string) => void
  error?: string
  hint?: ReactNode
}

/**
 * Rupee amount field. The value stays a string end to end (never `Number()`), so what the
 * user types is exactly what is sent.
 */
export const AmountInput = forwardRef<HTMLInputElement, AmountInputProps>(
  ({ label, value, onValueChange, error, hint, id, className, ...props }, ref) => {
    const auto = useId()
    const inputId = id ?? auto
    const errorId = `${inputId}-error`
    const hintId = `${inputId}-hint`
    return (
      <div className="space-y-1.5">
        <Label htmlFor={inputId}>{label}</Label>
        <div
          className={cn(
            'flex h-14 items-center gap-2 rounded-[var(--radius-control)] bg-surface-2 px-4 hairline',
            'focus-within:border-accent focus-within:ring-2 focus-within:ring-accent/30',
            error && 'border-loss focus-within:border-loss focus-within:ring-loss/30',
            className,
          )}
        >
          <span aria-hidden="true" className="font-display text-2xl text-muted">
            ₹
          </span>
          <input
            ref={ref}
            id={inputId}
            inputMode="decimal"
            autoComplete="off"
            placeholder="0.00"
            value={value}
            onChange={(e) => onValueChange(sanitizeAmount(e.target.value))}
            aria-invalid={error ? true : undefined}
            aria-describedby={
              [error ? errorId : '', hint ? hintId : ''].join(' ').trim() || undefined
            }
            className="num h-full w-full bg-transparent text-2xl outline-none placeholder:text-muted/60"
            {...props}
          />
        </div>
        <FieldMessage id={errorId}>{error}</FieldMessage>
        {hint && (
          <div id={hintId} className="text-xs text-muted">
            {hint}
          </div>
        )}
      </div>
    )
  },
)
AmountInput.displayName = 'AmountInput'
