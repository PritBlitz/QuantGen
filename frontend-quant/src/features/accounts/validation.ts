import { AMOUNT_INPUT_RE, formatINR, toDecimal } from '@/lib/money'

export const DEPOSIT_MIN = '1'
export const DEPOSIT_MAX = '100000'

/** F-ACC-4: ₹1 to ₹1,00,000 per deposit, at most 2 decimals. */
export function validateDeposit(amount: string): string | undefined {
  if (!amount) return 'Enter an amount'
  if (!AMOUNT_INPUT_RE.test(amount)) return 'Use a number with up to 2 decimals'
  const d = toDecimal(amount)
  if (d.lt(DEPOSIT_MIN)) return `The minimum is ${formatINR(DEPOSIT_MIN)}`
  if (d.gt(DEPOSIT_MAX)) return `You can add up to ${formatINR(DEPOSIT_MAX)} at a time`
  return undefined
}
