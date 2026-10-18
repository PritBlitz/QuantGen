import { useQuery } from '@tanstack/react-query'
import { Check, Fingerprint, ShieldCheck, Sparkles, Workflow } from 'lucide-react'
import type { ReactNode } from 'react'
import { proofApi } from '@/api/endpoints.v2'
import { Money } from '@/components/Money'
import { SpotlightCard } from '@/components/ui/card'
import { formatDate } from '@/lib/time'
import { cn } from '@/lib/utils'
import { Reveal } from './SplitReveal'

function Eyebrow({ icon, tone, children }: { icon: ReactNode; tone: 'violet' | 'cyan'; children: string }) {
  return (
    <p
      className={cn(
        'flex items-center gap-2 text-xs uppercase tracking-[0.16em] [&_svg]:size-4',
        tone === 'violet' ? 'text-violet' : 'text-cyan',
      )}
    >
      {icon} {children}
    </p>
  )
}

/** A token of the rule sentence, styled like the in-app renderer. */
const T = ({ children }: { children: ReactNode }) => (
  <span className="rounded-md bg-violet/14 px-1.5 py-0.5 font-medium text-violet">{children}</span>
)

function AutopilotDemo() {
  return (
    <SpotlightCard tone="violet" className="flex h-full flex-col">
      <Eyebrow icon={<Workflow aria-hidden="true" />} tone="violet">
        Autopilot
      </Eyebrow>
      <h3 className="mt-4 text-2xl font-medium">Money rules, in plain English.</h3>
      <p className="mt-2 max-w-md text-sm text-muted">
        Describe a rule; QuantGen turns it into a strict, checked program and reads it back to you.
        Replay it on the last 90 days before you switch it on.
      </p>
      <div className="mt-8 space-y-4 rounded-[var(--radius-control)] bg-surface-2/70 p-4 hairline">
        <p className="text-[0.9375rem] leading-8">
          When <T>salary</T> lands in <T>Checking</T>, move <T>20%</T> to <T>Emergency fund</T> and{' '}
          <T>₹5,000</T> to <T>Holiday</T>. At most <T>₹50,000</T> a month.
        </p>
        <dl className="grid grid-cols-3 gap-2 border-t border-border pt-4 text-center text-sm">
          <div>
            <dt className="text-xs text-muted">Would have run</dt>
            <dd className="num mt-1 text-lg">3×</dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Moved</dt>
            <dd className="mt-1 text-lg">
              <Money value="54600.00" compact />
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Blocked</dt>
            <dd className="num mt-1 text-lg">0</dd>
          </div>
        </dl>
      </div>
      <p className="mt-auto pt-6 text-xs text-muted">Runs exactly once per trigger, even on retries.</p>
    </SpotlightCard>
  )
}

function CopilotDemo() {
  return (
    <SpotlightCard tone="violet" className="flex h-full flex-col">
      <Eyebrow icon={<Sparkles aria-hidden="true" />} tone="violet">
        Copilot
      </Eyebrow>
      <h3 className="mt-4 text-2xl font-medium">It proposes. You approve.</h3>
      <p className="mt-2 text-sm text-muted">
        Ask about your money in your own words. Anything that moves money comes back as a card you
        approve with your PIN.
      </p>
      <div className="mt-8 space-y-2.5 text-sm" aria-label="Example conversation" role="group">
        <p className="ml-auto w-fit max-w-[85%] rounded-2xl rounded-br-md bg-surface-3 px-3.5 py-2">
          Pay Meera back for dinner
        </p>
        <p className="w-fit max-w-[85%] rounded-2xl rounded-bl-md bg-violet/10 px-3.5 py-2">
          You split ₹2,400 with Meera on Friday. Send her your half?
        </p>
        <div className="rounded-[var(--radius-control)] bg-surface-2/80 p-3 hairline">
          <p className="flex items-center justify-between gap-3">
            <span>
              <span className="block text-xs text-muted">Transfer to meera@qg</span>
              <Money value="1200.00" className="text-lg" />
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-full bg-accent px-3 py-1.5 text-xs font-medium text-accent-ink">
              <Fingerprint aria-hidden="true" className="size-3.5" /> Approve
            </span>
          </p>
        </div>
      </div>
    </SpotlightCard>
  )
}

const short = (h: string) => `${h.slice(0, 6)}…${h.slice(-4)}`

function ProofDemo() {
  // Public, unauthenticated endpoint. If it isn't live (or fails), the sample stays.
  const roots = useQuery({ queryKey: ['public', 'proof-roots'], queryFn: proofApi.roots, retry: false, staleTime: 5 * 60_000 })
  const latest = roots.data?.[0]
  return (
    <SpotlightCard tone="cyan" className="flex h-full flex-col">
      <Eyebrow icon={<ShieldCheck aria-hidden="true" />} tone="cyan">
        Proof of Balance
      </Eyebrow>
      <h3 className="mt-4 text-2xl font-medium">Don’t trust us. Check.</h3>
      <p className="mt-2 text-sm text-muted">
        Every night we publish one fingerprint of all customer balances. Your browser checks that
        your balance is inside it, without seeing anyone else’s.
      </p>
      <svg viewBox="0 0 240 110" className="mt-8 w-full max-w-xs self-center" aria-hidden="true">
        <g stroke="var(--border-strong)" strokeWidth="1.5" fill="none">
          <path d="M120 20 L60 55 M120 20 L180 55 M60 55 L30 90 M60 55 L90 90 M180 55 L150 90 M180 55 L210 90" />
        </g>
        <path d="M120 20 L60 55 L90 90" stroke="var(--cyan)" strokeWidth="2.5" fill="none" strokeLinecap="round" />
        {[
          [120, 20, true],
          [60, 55, true],
          [180, 55, false],
          [30, 90, false],
          [90, 90, true],
          [150, 90, false],
          [210, 90, false],
        ].map(([x, y, on]) => (
          <circle key={`${x}-${y}`} cx={x as number} cy={y as number} r={on ? 7 : 5.5} fill={on ? 'var(--cyan)' : 'var(--surface-3)'} />
        ))}
      </svg>
      <div className="mt-6 flex items-center gap-3 rounded-[var(--radius-control)] bg-surface-2/70 p-3 text-sm hairline">
        <span className="grid size-7 shrink-0 place-items-center rounded-full bg-gain text-bg">
          <Check aria-hidden="true" className="size-4" />
        </span>
        <span className="min-w-0">
          <span className="block font-medium">Your balance is included</span>
          <span className="num block truncate text-xs text-muted">root {latest ? short(latest.root) : '9f2c4e…a71e'}</span>
          <span className="block truncate text-xs text-muted">
            {latest ? `${formatDate(latest.date)} · ${latest.leafCount} accounts` : 'Sample'}
          </span>
        </span>
      </div>
    </SpotlightCard>
  )
}

/** The v2 signature features, up front (DESIGN.md v2 §4 Landing). */
export function Signature() {
  return (
    <section id="signature" className="mx-auto max-w-[1280px] scroll-mt-24 px-4 sm:px-6 lg:px-10">
      <Reveal className="max-w-2xl">
        <p className="eyebrow">New in QuantGen 2</p>
        <h2 className="mt-4 font-display text-4xl sm:text-5xl">
          A bank that does the busywork, <span className="italic text-muted">and shows its working.</span>
        </h2>
      </Reveal>
      <div className="mt-14 grid grid-cols-1 gap-4 lg:grid-cols-12">
        <Reveal className="lg:col-span-6">
          <AutopilotDemo />
        </Reveal>
        <Reveal className="lg:col-span-6" delay={0.05}>
          <div className="grid h-full gap-4 md:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
            <CopilotDemo />
            <ProofDemo />
          </div>
        </Reveal>
      </div>
    </section>
  )
}
