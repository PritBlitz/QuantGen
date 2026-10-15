import { describe, expect, it } from 'vitest'
import { eligibility, emi, schedule, totals } from './emi'
import { Decimal } from './money'

describe('EMI (F-LOAN-3)', () => {
  it('matches the standard formula at 12% p.a.', () => {
    expect(emi('200000', 24).toFixed(2)).toBe('9414.69')
    expect(emi('100000', 12).toFixed(2)).toBe('8884.88')
    expect(emi('10000', 6).toFixed(2)).toBe('1725.48')
  })

  it.each([
    ['200000', 24],
    ['10000', 6],
    ['1000000', 60],
    ['123457', 37],
  ])('schedule for %s over %i months repays exactly the principal', (p, n) => {
    const rows = schedule(p, n)
    expect(rows).toHaveLength(n)
    const principal = rows.reduce((s, r) => s.plus(r.principal), new Decimal(0))
    expect(principal.toFixed(2)).toBe(new Decimal(p).toFixed(2))
    expect(rows.at(-1)!.balance.isZero()).toBe(true)
    // Every row: emi = principal + interest, all 2-dp.
    for (const r of rows) {
      expect(r.principal.plus(r.interest).toFixed(2)).toBe(r.emi.toFixed(2))
      expect(r.interest.decimalPlaces()).toBeLessThanOrEqual(2)
    }
    const { totalPayable, totalInterest } = totals(rows)
    expect(totalPayable.minus(totalInterest).toFixed(2)).toBe(new Decimal(p).toFixed(2))
  })

  it('applies F-LOAN-2 eligibility', () => {
    expect(eligibility('200000', '9414.69', '50000').eligible).toBe(true)
    expect(eligibility('200000', '9414.69', '9000').reason).toMatch(/20×/)
    expect(eligibility('100000', '8884.88', '17000').reason).toMatch(/50%/)
    expect(eligibility('100000', '4000', '20000', '7000').eligible).toBe(false)
    expect(eligibility('100000', '4000', '0').eligible).toBe(false)
  })
})
