/**
 * Money helpers. The API sends money as decimal strings (PRD §7, F-LED-6). All arithmetic
 * goes through Decimal; JS `number` never touches an amount (CLAUDE.md money rules).
 */
import Decimal from 'decimal.js-light'
import type { MoneyString } from '@/api/types'

Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_EVEN })

export { Decimal }

const DECIMAL_RE = /^-?\d{1,19}(\.\d{1,8})?$/

/** Strictly parse an API decimal string. Throws on anything else, including '' and 'NaN'. */
export function toDecimal(value: MoneyString | Decimal): Decimal {
  if (value instanceof Decimal) return value
  if (typeof value !== 'string' || !DECIMAL_RE.test(value)) {
    throw new TypeError('Invalid money value')
  }
  return new Decimal(value)
}

export function sumMoney(values: Array<MoneyString | Decimal>): Decimal {
  return values.reduce<Decimal>((acc, v) => acc.plus(toDecimal(v)), new Decimal(0))
}

export type Sign = 'positive' | 'negative' | 'zero'

export function signOf(value: MoneyString | Decimal): Sign {
  const d = toDecimal(value)
  if (d.isZero()) return 'zero'
  return d.isNegative() ? 'negative' : 'positive'
}

const MINUS = '−'

const inrFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const inrCompactFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  notation: 'compact',
  maximumFractionDigits: 2,
})

export interface FormatMoneyOptions {
  /** Prefix `+` for positive values (P&L, credits). Negative always gets `−`. */
  signed?: boolean
  /** `₹1.25L`-style compact notation for tight spaces. */
  compact?: boolean
}

/**
 * Format as INR (`₹1,25,000.00`). Rounds to 2 dp with half-even on the decimal value, then
 * hands Intl a **string**, so formatting never goes through a binary float.
 */
export function formatINR(value: MoneyString | Decimal, opts: FormatMoneyOptions = {}): string {
  const d = toDecimal(value).toDecimalPlaces(2)
  const abs = d.abs().toFixed(2) as `${number}`
  const body = (opts.compact ? inrCompactFormatter : inrFormatter).format(abs)
  if (d.isNegative() && !d.isZero()) return `${MINUS}${body}`
  if (opts.signed && !d.isZero()) return `+${body}`
  return body
}

/** Percent string from the API (e.g. "-1.42") → "−1.42%" / "+1.42%". */
export function formatPct(value: string, opts: { signed?: boolean } = { signed: true }): string {
  const d = toDecimal(value).toDecimalPlaces(2)
  const abs = d.abs().toFixed(2)
  if (d.isNegative() && !d.isZero()) return `${MINUS}${abs}%`
  return `${opts.signed && !d.isZero() ? '+' : ''}${abs}%`
}

/** Validates user-typed rupee amounts: digits with up to 2 decimals, no sign, no exponent. */
export const AMOUNT_INPUT_RE = /^(0|[1-9]\d{0,11})(\.\d{1,2})?$/

/** Clamp a ratio between two amounts to [0, 1] for display (rings, bars). Not money. */
export function ratio(part: MoneyString | Decimal, whole: MoneyString | Decimal): number {
  const w = toDecimal(whole)
  if (w.lte(0)) return 0
  const r = toDecimal(part).div(w)
  if (r.lte(0)) return 0
  if (r.gte(1)) return 1
  return r.toDecimalPlaces(4).toNumber()
}
