import { ArrowRight, Check, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useFlag } from '@/api/flags'
import { ProgressRing } from '@/components/ProgressRing'
import { Button } from '@/components/ui/button'
import { useRules } from '@/features/autopilot/hooks'
import { useMe } from '@/features/security/hooks'
import { readOnboardingHidden, writeOnboardingHidden } from '@/lib/prefs'
import { cn } from '@/lib/utils'
import { useAccounts } from '../hooks'
import { DashCard } from './CardShell'

interface Step {
  key: string
  label: string
  hint: string
  to: string
  done: boolean
}

/**
 * "Get set up" (F-UX-3): passkey, handle, first goal, first Autopilot rule. Every step is read
 * from the server, so it ticks itself off; the card goes away once all are done or dismissed.
 */
export function OnboardingCard({ className }: { className?: string }) {
  const me = useMe()
  const accounts = useAccounts()
  const autopilot = useFlag('autopilot')
  const rules = useRules(autopilot)
  const [hidden, setHidden] = useState(readOnboardingHidden)

  if (hidden || !me.data || !accounts.data) return null
  const steps: Step[] = [
    { key: 'passkey', label: 'Add a passkey', hint: 'Sign in with your fingerprint or face, no password.', to: '/settings/security', done: !!me.data.hasPasskey },
    { key: 'handle', label: 'Claim your handle', hint: 'Get paid at name@qg instead of an account number.', to: '/pay', done: !!me.data.handle },
    { key: 'goal', label: 'Start a savings goal', hint: 'Put money aside for something specific.', to: '/savings', done: accounts.data.some((a) => a.type === 'GOAL') },
  ]
  if (autopilot && !rules.isError)
    steps.push({ key: 'rule', label: 'Switch on your first rule', hint: 'Let Autopilot move money for you, on your terms.', to: '/autopilot/new', done: (rules.data ?? []).length > 0 })
  const done = steps.filter((s) => s.done).length
  if (done === steps.length) return null

  return (
    <DashCard
      title="Get set up"
      labelledBy="onboarding-title"
      className={className}
      action={
        <Button
          variant="ghost"
          size="icon"
          aria-label="Hide the setup checklist"
          onClick={() => {
            writeOnboardingHidden(true)
            setHidden(true)
          }}
        >
          <X />
        </Button>
      }
    >
      <div className="flex flex-col gap-5 sm:flex-row sm:items-center">
        <div className="flex items-center gap-3 sm:w-40 sm:flex-col sm:items-start">
          <ProgressRing value={done / steps.length} size={64} caption="set up" label={`${done} of ${steps.length} done`} />
          <p className="text-sm text-muted">
            <span className="num text-text">{done}</span> of <span className="num">{steps.length}</span> done
          </p>
        </div>
        <ol className="grid flex-1 gap-2 sm:grid-cols-2">
          {steps.map((s) => (
            <li key={s.key}>
              <Link
                to={s.to}
                className={cn(
                  'group flex h-full items-start gap-3 rounded-[var(--radius-control)] p-3 hairline transition-colors hover:bg-surface-2',
                  s.done && 'opacity-60',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'mt-0.5 grid size-5 shrink-0 place-items-center rounded-full hairline',
                    s.done && 'border-gain bg-gain text-bg',
                  )}
                >
                  {s.done && <Check className="size-3" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className={cn('block text-sm font-medium', s.done && 'line-through')}>
                    {s.label}
                    {s.done && <span className="sr-only"> (done)</span>}
                  </span>
                  <span className="block text-xs text-muted">{s.hint}</span>
                </span>
                {!s.done && <ArrowRight aria-hidden="true" className="mt-0.5 size-4 text-muted transition-transform group-hover:translate-x-0.5" />}
              </Link>
            </li>
          ))}
        </ol>
      </div>
    </DashCard>
  )
}
