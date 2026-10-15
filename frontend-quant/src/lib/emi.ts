/**
 * EMI maths per PRD F-LOAN-3 / ARCHITECTURE §7, in Decimal (never floats).
 * The server's /loans/quote is authoritative; this powers the live calculator preview
 * and the mock backend.
 */
import { Decimal } from './money'

/** Accepted numeric inputs: decimal strings, integer rupees/months, or Decimal. */
type Num = string | number | Decimal

export const ANNUAL_RATE = '12'
export const LOAN_MIN = 10_000
export const LOAN_MAX = 1_000_000
export const TENURE_MIN = 6
export const TENURE_MAX = 60

export interface ScheduleRow {
  installmentNo: number
  emi: Decimal
  principal: Decimal
  interest: Decimal
  /** Outstanding principal after this instalment. */
  balance: Decimal
}

const monthlyRate = (annualPct: Num) => new Decimal(annualPct).div(100).div(12)

/** EMI = P·r·(1+r)^n / ((1+r)^n − 1), rounded to 2 dp (half-even). */
export function emi(principal: Num, months: number, annualPct: Num = ANNUAL_RATE) {
  const p = new Decimal(principal)
  const r = monthlyRate(annualPct)
  if (r.isZero()) return p.div(months).toDecimalPlaces(2)
  const f = r.plus(1).pow(months)
  return p.times(r).times(f).div(f.minus(1)).toDecimalPlaces(2)
}

/** Full schedule; the last instalment absorbs the rounding difference. */
export function schedule(
  principal: Num,
  months: number,
  annualPct: Num = ANNUAL_RATE,
): ScheduleRow[] {
  const r = monthlyRate(annualPct)
  const e = emi(principal, months, annualPct)
  let outstanding = new Decimal(principal)
  const rows: ScheduleRow[] = []
  for (let i = 1; i <= months; i++) {
    const interest = outstanding.times(r).toDecimalPlaces(2)
    let p = e.minus(interest)
    let pay = e
    if (i === months) {
      p = outstanding
      pay = p.plus(interest)
    }
    outstanding = outstanding.minus(p)
    rows.push({ installmentNo: i, emi: pay, principal: p, interest, balance: outstanding })
  }
  return rows
}

export function totals(rows: ScheduleRow[]) {
  const totalPayable = rows.reduce((s, r) => s.plus(r.emi), new Decimal(0))
  const totalInterest = rows.reduce((s, r) => s.plus(r.interest), new Decimal(0))
  return { totalPayable, totalInterest }
}

/**
 * F-LOAN-2 preview: new EMI + existing EMIs ≤ 50% of income, amount ≤ 20× income.
 * Returns a human reason when not eligible.
 */
export function eligibility(
  amount: Num,
  newEmi: Num,
  monthlyIncome: Num,
  existingEmis: Num = 0,
): { eligible: boolean; reason?: string } {
  const income = new Decimal(monthlyIncome)
  if (income.lte(0)) return { eligible: false, reason: 'Enter your monthly income.' }
  if (new Decimal(amount).gt(income.times(20)))
    return { eligible: false, reason: 'The amount can be at most 20× your monthly income.' }
  if (new Decimal(newEmi).plus(existingEmis).gt(income.div(2)))
    return {
      eligible: false,
      reason: 'All your EMIs together must stay within 50% of your monthly income.',
    }
  return { eligible: true }
}
