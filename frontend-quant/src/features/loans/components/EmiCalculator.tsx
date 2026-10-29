import { CheckCircle2, Loader2, XCircle } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Bar, BarChart, ResponsiveContainer, Tooltip as ChartTooltip, XAxis } from 'recharts'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Slider } from '@/components/ui/slider'
import { emi, LOAN_MAX, LOAN_MIN, schedule, TENURE_MAX, TENURE_MIN, totals } from '@/lib/emi'
import { AMOUNT_INPUT_RE, formatINR, toDecimal } from '@/lib/money'
import { useLoanQuote } from '../hooks'
import { inr0 } from '../labels'

export interface LoanDraft {
  amount: number // whole rupees from the slider
  tenureMonths: number
  monthlyIncome: string // decimal string
}

const INCOME_DEFAULT = '75000'

/**
 * Interactive EMI calculator (DESIGN.md §4): sliders update a client-side Decimal estimate
 * instantly; the server's /loans/quote confirms the EMI and eligibility underneath.
 */
export function EmiCalculator({ onApply }: { onApply: (draft: LoanDraft) => void }) {
  const [amount, setAmount] = useState(200_000)
  const [tenure, setTenure] = useState(24)
  const [income, setIncome] = useState(INCOME_DEFAULT)
  const incomeOk = AMOUNT_INPUT_RE.test(income) && toDecimal(income).gt(0)

  const preview = useMemo(() => {
    const rows = schedule(String(amount), tenure)
    return { emi: emi(String(amount), tenure), ...totals(rows), rows }
  }, [amount, tenure])

  const quote = useLoanQuote(
    incomeOk
      ? {
          amount: String(amount),
          tenureMonths: tenure,
          monthlyIncome: toDecimal(income).toFixed(2),
        }
      : null,
  )

  // Chart: yearly buckets keep 60-month schedules readable. Numbers are chart geometry only.
  const chart = useMemo(() => {
    const buckets: Array<{ label: string; principal: number; interest: number }> = []
    preview.rows.forEach((r, i) => {
      const y = Math.floor(i / 12)
      const b = (buckets[y] ??= { label: `Y${y + 1}`, principal: 0, interest: 0 })
      b.principal = toDecimal(r.principal).plus(b.principal).toNumber()
      b.interest = toDecimal(r.interest).plus(b.interest).toNumber()
    })
    return buckets
  }, [preview.rows])

  const q = quote.data
  return (
    <div className="grid grid-cols-1 gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <div className="space-y-7">
        <div className="space-y-3">
          <div className="flex items-baseline justify-between">
            <Label>Loan amount</Label>
            <span className="num text-lg font-medium">
              {inr0.format(String(amount) as `${number}`)}
            </span>
          </div>
          <Slider
            label="Loan amount"
            value={amount}
            onValueChange={setAmount}
            min={LOAN_MIN}
            max={LOAN_MAX}
            step={5_000}
            valueText={inr0.format(String(amount) as `${number}`)}
          />
          <div className="num flex justify-between text-xs text-muted">
            <span>₹10K</span>
            <span>₹10L</span>
          </div>
        </div>

        <div className="space-y-3">
          <div className="flex items-baseline justify-between">
            <Label>Tenure</Label>
            <span className="num text-lg font-medium">{tenure} months</span>
          </div>
          <Slider
            label="Tenure in months"
            value={tenure}
            onValueChange={setTenure}
            min={TENURE_MIN}
            max={TENURE_MAX}
            step={1}
            valueText={`${tenure} months`}
          />
          <div className="num flex justify-between text-xs text-muted">
            <span>6 mo</span>
            <span>60 mo</span>
          </div>
        </div>

        <AmountInput
          label="Your monthly income"
          value={income}
          onValueChange={setIncome}
          error={income && !incomeOk ? 'Enter your monthly income' : undefined}
          hint="Used for eligibility: all EMIs within 50% of income, amount within 20× income."
        />
      </div>

      <div className="rounded-[var(--radius-card)] bg-surface-2 p-5 sm:p-6">
        <p className="text-xs uppercase tracking-[0.16em] text-muted">Monthly EMI</p>
        <p className="mt-2 font-display text-5xl tabular-nums sm:text-5xl">
          {formatINR(q?.emi ?? preview.emi)}
        </p>
        <p className="mt-1 text-xs text-muted">at a fixed 12% a year</p>

        <dl className="mt-6 grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-xs text-muted">Total interest</dt>
            <dd className="mt-0.5 font-medium">
              <Money value={q?.totalInterest ?? preview.totalInterest} />
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Total payable</dt>
            <dd className="mt-0.5 font-medium">
              <Money value={q?.totalPayable ?? preview.totalPayable} />
            </dd>
          </div>
        </dl>

        <div className="mt-6 h-36" role="img" aria-label="Principal and interest paid per year">
          <ResponsiveContainer>
            <BarChart data={chart} margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
              <XAxis
                dataKey="label"
                tickLine={false}
                axisLine={false}
                tick={{ fill: 'var(--text-muted)', fontSize: 11 }}
              />
              <ChartTooltip
                cursor={{ fill: 'color-mix(in oklab, var(--text) 6%, transparent)' }}
                contentStyle={{
                  background: 'var(--surface)',
                  border: '1px solid var(--border)',
                  borderRadius: 10,
                  fontSize: 12,
                }}
                formatter={(v, name) => [
                  inr0.format(typeof v === 'number' ? v : 0),
                  name === 'principal' ? 'Principal' : 'Interest',
                ]}
              />
              <Bar
                dataKey="principal"
                stackId="a"
                fill="var(--accent)"
                radius={[0, 0, 0, 0]}
                isAnimationActive={false}
              />
              <Bar
                dataKey="interest"
                stackId="a"
                fill="var(--text-muted)"
                radius={[4, 4, 0, 0]}
                isAnimationActive={false}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="mt-2 flex gap-4 text-xs text-muted">
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="size-2 rounded-full bg-accent" /> Principal
          </span>
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="size-2 rounded-full bg-muted" /> Interest
          </span>
        </div>

        <div className="mt-6 min-h-12" aria-live="polite">
          {!incomeOk ? null : quote.isFetching && !q ? (
            <p className="flex items-center gap-2 text-sm text-muted">
              <Loader2 className="size-4 animate-spin" aria-hidden="true" /> Checking eligibility…
            </p>
          ) : q?.eligible ? (
            <p className="flex items-center gap-2 text-sm">
              <CheckCircle2 aria-hidden="true" className="size-4 text-gain" /> You’re likely
              eligible.
            </p>
          ) : q ? (
            <p className="flex items-start gap-2 text-sm">
              <XCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-loss" />{' '}
              {q.reason ?? 'Not eligible for this amount.'}
            </p>
          ) : null}
        </div>

        <Button
          size="lg"
          className="mt-2 w-full"
          disabled={!incomeOk || (q ? !q.eligible : false)}
          onClick={() =>
            onApply({ amount, tenureMonths: tenure, monthlyIncome: toDecimal(income).toFixed(2) })
          }
        >
          Apply for {inr0.format(String(amount) as `${number}`)}
        </Button>
      </div>
    </div>
  )
}
