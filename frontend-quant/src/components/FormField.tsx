import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { FieldMessage, Input, Label } from './ui/input'

interface FormFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: ReactNode
  /** Rendered at the right of the label row (e.g. "Forgot?"). */
  aside?: ReactNode
  /** Rendered inside the input on the right (e.g. a show-password button). */
  trailing?: ReactNode
}

/** Label + input + inline error, wired with aria-invalid/aria-describedby. */
export const FormField = forwardRef<HTMLInputElement, FormFieldProps>(
  (
    { label, error, hint, aside, trailing, id, className, 'aria-describedby': extra, ...props },
    ref,
  ) => {
    const auto = useId()
    const inputId = id ?? auto
    const errorId = `${inputId}-error`
    const hintId = `${inputId}-hint`
    const describedBy = [error ? errorId : null, hint ? hintId : null, extra ?? null]
      .filter(Boolean)
      .join(' ')
    return (
      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <Label htmlFor={inputId}>{label}</Label>
          {aside}
        </div>
        <div className="relative">
          <Input
            ref={ref}
            id={inputId}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy || undefined}
            className={trailing ? `pr-11 ${className ?? ''}` : className}
            {...props}
          />
          {trailing && (
            <div className="absolute inset-y-0 right-1 flex items-center">{trailing}</div>
          )}
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
FormField.displayName = 'FormField'
