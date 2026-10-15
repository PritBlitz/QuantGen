import { Check, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { PASSWORD_RULES, passwordStrength } from '../schemas'

const LABELS = ['', 'Weak', 'Fair', 'Strong', 'Very strong'] as const
const BAR = ['', 'bg-loss', 'bg-warn', 'bg-gain', 'bg-gain'] as const

export function PasswordStrength({ value, id }: { value: string; id: string }) {
  const score = passwordStrength(value)
  return (
    <div id={id} className="space-y-2.5">
      <div className="flex items-center gap-3">
        <div className="grid flex-1 grid-cols-4 gap-1.5" aria-hidden="true">
          {[1, 2, 3, 4].map((i) => (
            <span
              key={i}
              className={cn(
                'h-1 rounded-full bg-surface-2 transition-colors duration-[var(--dur-ui)]',
                score >= i && BAR[score],
              )}
            />
          ))}
        </div>
        <span className="w-20 text-right text-xs text-muted" aria-live="polite">
          {score ? LABELS[score] : ''}
        </span>
      </div>
      <ul className="grid grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
        {PASSWORD_RULES.map((rule) => {
          const ok = rule.test(value)
          return (
            <li
              key={rule.id}
              className={cn('flex items-center gap-1.5', ok ? 'text-text' : 'text-muted')}
            >
              {ok ? (
                <Check aria-hidden="true" className="size-3.5 text-gain" />
              ) : (
                <X aria-hidden="true" className="size-3.5" />
              )}
              {rule.label}
              <span className="sr-only">{ok ? '(met)' : '(not met)'}</span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
