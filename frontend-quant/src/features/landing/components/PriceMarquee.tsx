import { ArrowDownRight, ArrowUpRight } from 'lucide-react'
import { formatINR, formatPct, signOf } from '@/lib/money'
import { cn } from '@/lib/utils'

/**
 * Illustrative snapshot of the simulated market. Market endpoints require auth (PRD §7.5),
 * so the public landing page can't stream /topic/prices; this is labelled as a sample.
 */
const SAMPLE: Array<[string, string, string]> = [
  ['QGEN', '1284.50', '1.77'],
  ['INFY', '1876.25', '-0.73'],
  ['TCS', '4125.80', '0.67'],
  ['RELI', '2944.10', '-0.60'],
  ['HDFC', '1712.40', '0.79'],
  ['ICIC', '1265.75', '-0.35'],
  ['WIPR', '548.30', '1.34'],
  ['SBIN', '842.15', '0.66'],
  ['BHAR', '1689.00', '-0.79'],
  ['ITC', '486.90', '0.96'],
  ['LT', '3712.60', '0.66'],
  ['MARU', '12480.00', '-0.60'],
]

function Row() {
  return (
    <ul className="flex shrink-0 items-center gap-10 pr-10">
      {SAMPLE.map(([symbol, price, pct]) => {
        const sign = signOf(pct)
        const Icon = sign === 'negative' ? ArrowDownRight : ArrowUpRight
        return (
          <li key={symbol} className="flex items-center gap-3 whitespace-nowrap">
            <span className="text-sm font-semibold tracking-wide">{symbol}</span>
            <span className="num text-sm text-muted">{formatINR(price)}</span>
            <span
              className={cn(
                'num inline-flex items-center gap-0.5 text-xs',
                sign === 'negative' ? 'text-loss' : 'text-gain',
              )}
            >
              <Icon aria-hidden="true" className="size-3.5" />
              {formatPct(pct)}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

export function PriceMarquee() {
  return (
    <section aria-label="Sample of simulated market prices" className="border-y border-border py-5">
      <div className="group relative flex overflow-hidden [mask-image:linear-gradient(90deg,transparent,#000_8%,#000_92%,transparent)]">
        <div className="flex animate-marquee group-hover:[animation-play-state:paused]">
          <Row />
          <div aria-hidden="true">
            <Row />
          </div>
        </div>
      </div>
      <p className="mt-3 text-center text-[0.6875rem] uppercase tracking-[0.18em] text-muted">
        Simulated market · sample prices
      </p>
    </section>
  )
}
