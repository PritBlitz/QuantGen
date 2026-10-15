import { CandlestickChart, HandCoins, Scale, ShieldCheck } from 'lucide-react'
import { Money } from '@/components/Money'
import { SpotlightCard } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { Reveal } from './SplitReveal'

function Eyebrow({ icon: Icon, children }: { icon: typeof Scale; children: string }) {
  return (
    <p className="flex items-center gap-2 text-xs uppercase tracking-[0.16em] text-muted">
      <Icon aria-hidden="true" className="size-4 text-accent" /> {children}
    </p>
  )
}

export function Bento() {
  return (
    <section id="product" className="mx-auto max-w-[1280px] scroll-mt-24 px-4 sm:px-6 lg:px-10">
      <Reveal className="max-w-2xl">
        <p className="text-xs uppercase tracking-[0.18em] text-muted">One login, four engines</p>
        <h2 className="mt-4 font-display text-4xl sm:text-5xl">
          Bank, trade and borrow on a single ledger.
        </h2>
      </Reveal>

      <div className="mt-14 grid grid-cols-1 gap-4 md:grid-cols-6">
        <Reveal className="md:col-span-4">
          <SpotlightCard className="h-full min-h-72">
            <Eyebrow icon={Scale}>Ledger</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Double-entry, down to the paisa.</h3>
            <p className="mt-2 max-w-md text-sm text-muted">
              Every movement is a balanced journal entry, posted inside one database transaction.
              Money can’t be created, lost or spent twice.
            </p>
            <div className="mt-8 overflow-hidden rounded-[var(--radius-control)] hairline">
              <table className="w-full text-sm">
                <caption className="sr-only">Example journal entry for a transfer</caption>
                <thead className="bg-surface-2 text-xs text-muted">
                  <tr>
                    <th scope="col" className="px-4 py-2 text-left font-normal">
                      Account
                    </th>
                    <th scope="col" className="px-4 py-2 text-right font-normal">
                      Debit
                    </th>
                    <th scope="col" className="px-4 py-2 text-right font-normal">
                      Credit
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr className="border-t border-border">
                    <td className="px-4 py-2.5">Aarav · Savings</td>
                    <td className="px-4 py-2.5 text-right">
                      <Money value="12000.00" />
                    </td>
                    <td className="px-4 py-2.5 text-right text-muted">—</td>
                  </tr>
                  <tr className="border-t border-border">
                    <td className="px-4 py-2.5">Meera · Savings</td>
                    <td className="px-4 py-2.5 text-right text-muted">—</td>
                    <td className="px-4 py-2.5 text-right">
                      <Money value="12000.00" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-2" delay={0.06}>
          <SpotlightCard className="flex h-full min-h-72 flex-col">
            <Eyebrow icon={CandlestickChart}>Trade</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Prices that move every 3 seconds.</h3>
            <p className="mt-2 text-sm text-muted">
              Market orders settle cash and shares atomically.
            </p>
            <div className="mt-auto flex items-end gap-1.5 pt-8" aria-hidden="true">
              {[38, 52, 44, 61, 57, 72, 66, 80, 74, 88].map((h, i) => (
                <span
                  key={i}
                  style={{ height: `${h}px` }}
                  className={cn('flex-1 rounded-sm', i % 3 === 2 ? 'bg-loss/70' : 'bg-gain/70')}
                />
              ))}
            </div>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-3" delay={0.04}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={HandCoins}>Loans</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Know your EMI before you apply.</h3>
            <p className="mt-2 text-sm text-muted">
              A fixed 12% a year, with a full amortization schedule, auto-debit and clear dues.
            </p>
            <dl className="mt-8 grid grid-cols-3 gap-3 text-sm">
              {[
                ['Amount', <Money key="a" value="200000.00" compact />],
                [
                  'Tenure',
                  <span key="t" className="num">
                    24 mo
                  </span>,
                ],
                ['EMI', <Money key="e" value="9414.69" />],
              ].map(([label, value]) => (
                <div
                  key={String(label)}
                  className="rounded-[var(--radius-control)] bg-surface-2 p-3"
                >
                  <dt className="text-xs text-muted">{label}</dt>
                  <dd className="mt-1 font-medium">{value}</dd>
                </div>
              ))}
            </dl>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-3" delay={0.08}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={ShieldCheck}>Security</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Your session never touches storage.</h3>
            <p className="mt-2 text-sm text-muted">
              Short-lived tokens kept in memory, rotating HttpOnly refresh cookies, and an audit
              trail for every sensitive action.
            </p>
            <ul className="mt-8 flex flex-wrap gap-2 text-xs">
              {[
                '15-min access tokens',
                'Refresh rotation',
                'Reuse detection',
                'Rate limits',
                'Audit log',
              ].map((t) => (
                <li key={t} className="rounded-full px-3 py-1.5 hairline">
                  {t}
                </li>
              ))}
            </ul>
          </SpotlightCard>
        </Reveal>
      </div>
    </section>
  )
}
