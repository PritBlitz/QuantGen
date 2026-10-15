import { describe, expect, it } from 'vitest'
import { AMOUNT_INPUT_RE, formatINR, formatPct, ratio, signOf, sumMoney, toDecimal } from './money'

describe('money', () => {
  it('formats INR with Indian digit grouping', () => {
    expect(formatINR('125000')).toBe('₹1,25,000.00')
    expect(formatINR('184250.7500')).toBe('₹1,84,250.75')
    expect(formatINR('0')).toBe('₹0.00')
  })

  it('uses a true minus sign and optional plus', () => {
    expect(formatINR('-2340.5')).toBe('−₹2,340.50')
    expect(formatINR('2340.5', { signed: true })).toBe('+₹2,340.50')
    expect(formatINR('0', { signed: true })).toBe('₹0.00')
  })

  it('rounds half-even at 2 dp without float error', () => {
    expect(formatINR('0.125')).toBe('₹0.12')
    expect(formatINR('0.135')).toBe('₹0.14')
    // 0.1 + 0.2 is exact in decimal
    expect(sumMoney(['0.1', '0.2']).toFixed(2)).toBe('0.30')
    expect(formatINR(sumMoney(['0.1', '0.2']))).toBe('₹0.30')
  })

  it('keeps precision on very large amounts', () => {
    expect(formatINR('999999999999999.99')).toBe('₹99,99,99,99,99,99,999.99')
  })

  it('rejects non-decimal input', () => {
    for (const bad of ['', 'abc', '1e5', 'NaN', 'Infinity', '1,000', ' 1']) {
      expect(() => toDecimal(bad)).toThrow()
    }
  })

  it('formats percentages and signs', () => {
    expect(formatPct('-1.42')).toBe('−1.42%')
    expect(formatPct('1.4')).toBe('+1.40%')
    expect(signOf('-0.01')).toBe('negative')
    expect(signOf('0.0000')).toBe('zero')
  })

  it('validates typed amounts', () => {
    for (const ok of ['1', '1.5', '100000.00', '0.99']) expect(AMOUNT_INPUT_RE.test(ok)).toBe(true)
    for (const bad of ['-1', '1.234', '01', '1e3', '', '.5', '1,000']) {
      expect(AMOUNT_INPUT_RE.test(bad)).toBe(false)
    }
  })

  it('clamps display ratios', () => {
    expect(ratio('57681.54', '200000')).toBeCloseTo(0.2884, 4)
    expect(ratio('5', '0')).toBe(0)
    expect(ratio('300', '200')).toBe(1)
  })
})
