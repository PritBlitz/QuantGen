import type { MoneyString } from '@/api/types'
import { formatINR, type Decimal } from '@/lib/money'
import { cn } from '@/lib/utils'

const DIGITS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'] as const

/**
 * Odometer-style money (DESIGN.md §2 "Numbers"): each digit is a 0–9 column that slides to
 * its new position (~400ms). It animates the *formatted string*, character by character, so
 * no money value is ever interpolated through a JS number. Under reduced motion the global
 * CSS rule removes the transition and digits change instantly.
 */
export function RollingMoney({
  value,
  className,
}: {
  value: MoneyString | Decimal
  className?: string
}) {
  const text = formatINR(value)
  const chars = [...text]

  return (
    <span className={cn('relative inline-flex whitespace-nowrap tabular-nums', className)}>
      <span className="sr-only">{text}</span>
      <span aria-hidden="true" className="inline-flex leading-none">
        {chars.map((ch, i) => {
          // Key from the right so units stay aligned when the length changes.
          const key = chars.length - i
          const d = DIGITS.indexOf(ch as (typeof DIGITS)[number])
          if (d === -1) {
            return (
              <span key={`s${key}`} className="inline-block">
                {ch}
              </span>
            )
          }
          return (
            <span
              key={`d${key}`}
              className="relative inline-block h-[1.1em] overflow-hidden text-center"
            >
              <span
                className="flex flex-col transition-transform duration-[400ms] ease-[var(--ease-out-expo)]"
                style={{ transform: `translateY(-${d * 10}%)` }}
              >
                {DIGITS.map((n) => (
                  <span key={n} className="block h-[1.1em] leading-[1.1em]">
                    {n}
                  </span>
                ))}
              </span>
            </span>
          )
        })}
      </span>
    </span>
  )
}
