import { forwardRef, type TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement>
>(({ className, ...props }, ref) => (
  <textarea
    ref={ref}
    className={cn(
      'min-h-24 w-full resize-y rounded-[var(--radius-control)] bg-surface-2 px-3.5 py-2.5 text-[0.9375rem] text-text',
      'hairline placeholder:text-muted/70',
      'transition-[border-color,box-shadow] duration-[var(--dur-micro)]',
      'hover:border-muted/40 focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30',
      'aria-[invalid=true]:border-loss aria-[invalid=true]:focus-visible:ring-loss/30',
      'disabled:opacity-50',
      className,
    )}
    {...props}
  />
))
Textarea.displayName = 'Textarea'
