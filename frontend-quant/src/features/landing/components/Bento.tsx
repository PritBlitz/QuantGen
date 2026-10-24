import {
  CandlestickChart,
  CreditCard,
  HandCoins,
  LineChart,
  PiggyBank,
  Scale,
  Send,
} from 'lucide-react'
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
        <p className="text-xs uppercase tracking-[0.18em] text-muted">One login, one ledger</p>
        <h2 className="mt-4 font-display text-4xl sm:text-5xl">
          Pay, save, trade and borrow, all on a single ledger.
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
              Market, limit and stop-loss orders, watchlists and alerts. Test a strategy in the lab
              first.
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

        <Reveal className="md:col-span-2" delay={0.02}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={Send}>Pay</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Pay a name, not a number.</h3>
            <p className="mt-2 text-sm text-muted">
              Send to <span className="num text-text">meera@qg</span>, scan a QR, request money,
              split a bill or schedule rent. The payee’s name is confirmed before you send.
            </p>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-2" delay={0.04}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={PiggyBank}>Save</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Goals that fill themselves.</h3>
            <p className="mt-2 text-sm text-muted">
              Round up every payment into a goal, lock money in a fixed deposit, and watch interest
              accrue to the paisa.
            </p>
            <div className="mt-6 h-2 overflow-hidden rounded-full bg-surface-2" aria-hidden="true">
              <div className="h-full w-[64%] rounded-full bg-gain" />
            </div>
            <p className="mt-2 flex justify-between text-xs text-muted">
              <span>Holiday</span>
              <Money value="38400.00" compact />
            </p>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-2" delay={0.06}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={HandCoins}>Loans</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Know your EMI before you apply.</h3>
            <dl className="mt-6 grid grid-cols-2 gap-2 text-sm">
              <div className="rounded-[var(--radius-control)] bg-surface-2 p-3">
                <dt className="text-xs text-muted">₹2,00,000 · 24 mo</dt>
                <dd className="mt-1 font-medium">
                  <Money value="9414.69" />
                </dd>
              </div>
              <div className="rounded-[var(--radius-control)] bg-surface-2 p-3">
                <dt className="text-xs text-muted">Rate</dt>
                <dd className="num mt-1 font-medium">12% p.a.</dd>
              </div>
            </dl>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-3" delay={0.04}>
          <SpotlightCard className="h-full">
            <Eyebrow icon={CreditCard}>Cards</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">A virtual card you control.</h3>
            <p className="mt-2 text-sm text-muted">
              Freeze it, cap each payment, block whole merchant types. The number and a CVV that
              changes every 30 seconds show only after your PIN.
            </p>
            <p className="num mt-6 text-lg tracking-[0.12em] text-muted" aria-hidden="true">
              •••• •••• •••• 4417
            </p>
          </SpotlightCard>
        </Reveal>

        <Reveal className="md:col-span-3" delay={0.08}>
          <SpotlightCard tone="cyan" className="h-full">
            <Eyebrow icon={LineChart}>Insights</Eyebrow>
            <h3 className="mt-4 text-2xl font-medium">Where it went, where it’s going.</h3>
            <p className="mt-2 text-sm text-muted">
              Spending by category, budgets that warn early, cash flow and a net-worth forecast,
              computed from the same ledger as your balance.
            </p>
            <div className="mt-6 flex items-end gap-1.5" aria-hidden="true">
              {[30, 46, 28, 54, 40, 62, 48, 36, 58, 44, 50, 66].map((h, i) => (
                <span
                  key={i}
                  style={{ height: `${h}px` }}
                  className="flex-1 rounded-sm bg-cyan/60"
                />
              ))}
            </div>
          </SpotlightCard>
        </Reveal>
      </div>
    </section>
  )
}
