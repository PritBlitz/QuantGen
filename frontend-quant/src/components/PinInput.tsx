import { useRef, type ClipboardEvent, type KeyboardEvent } from 'react'
import { cn } from '@/lib/utils'

/**
 * A 6-digit PIN entry: six boxes, one digit each, paste-aware, numeric keyboard on mobile.
 * Values are never logged or stored; the parent clears them after use.
 */
export function PinInput({
  value,
  onChange,
  length = 6,
  label,
  invalid,
  autoFocus,
  disabled,
  onComplete,
  className,
}: {
  value: string
  onChange: (v: string) => void
  length?: number
  label: string
  invalid?: boolean
  autoFocus?: boolean
  disabled?: boolean
  onComplete?: (v: string) => void
  className?: string
}) {
  const refs = useRef<Array<HTMLInputElement | null>>([])

  const set = (next: string) => {
    const clean = next.replace(/\D/g, '').slice(0, length)
    onChange(clean)
    if (clean.length === length) onComplete?.(clean)
    refs.current[Math.min(clean.length, length - 1)]?.focus()
  }

  function onKeyDown(i: number, e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace') {
      e.preventDefault()
      const at = value.length > i ? i : i - 1
      if (at < 0) return
      onChange(value.slice(0, at))
      refs.current[at]?.focus()
    } else if (e.key === 'ArrowLeft') refs.current[i - 1]?.focus()
    else if (e.key === 'ArrowRight') refs.current[i + 1]?.focus()
  }

  function onPaste(e: ClipboardEvent<HTMLInputElement>) {
    e.preventDefault()
    set(e.clipboardData.getData('text'))
  }

  return (
    <div role="group" aria-label={label} className={cn('flex justify-center gap-2', className)}>
      {Array.from({ length }).map((_, i) => (
        <input
          key={i}
          ref={(el) => {
            refs.current[i] = el
          }}
          type="password"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={1}
          disabled={disabled}
          autoFocus={autoFocus && i === 0}
          aria-label={`Digit ${i + 1} of ${length}`}
          aria-invalid={invalid || undefined}
          value={value[i] ?? ''}
          onKeyDown={(e) => onKeyDown(i, e)}
          onPaste={onPaste}
          onChange={(e) => {
            const d = e.target.value.replace(/\D/g, '').slice(-1)
            if (!d) return
            set(value.slice(0, i) + d)
          }}
          className={cn(
            // Shrinks below 48 px on narrow cards (6 boxes + gaps don't fit at 360 px) but never grows past it.
            'num h-12 w-12 min-w-0 shrink rounded-[12px] bg-surface-2 text-center text-xl hairline outline-none transition-colors',
            'focus:border-accent',
            invalid && 'border-loss',
          )}
        />
      ))}
    </div>
  )
}
