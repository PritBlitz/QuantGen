import type { OrderSide } from '@/api/types'

export const QTY_MAX = 10_000

/** F-TRD-4: whole shares 1–10,000; can't sell more than held. */
export function validateQuantity(raw: string, side: OrderSide, held: number): string | undefined {
  if (!raw) return 'Enter a quantity'
  if (!/^\d{1,5}$/.test(raw)) return 'Whole shares only'
  const q = Number.parseInt(raw, 10) // share count, not money
  if (q < 1) return 'At least 1 share'
  if (q > QTY_MAX) return `At most ${QTY_MAX.toLocaleString('en-IN')} shares per order`
  if (side === 'SELL' && q > held)
    return held ? `You hold ${held} share${held === 1 ? '' : 's'}` : 'You don’t hold any shares'
  return undefined
}

/** F-TRD-7: a limit or trigger price in rupees, up to 2 decimals, above 0. String in, no floats. */
export function validateOrderPrice(raw: string): string | undefined {
  if (!raw) return 'Enter a price'
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(raw)) return 'Use rupees and up to 2 decimals'
  if (/^0+(\.0{0,2})?$/.test(raw)) return 'Must be more than ₹0'
  return undefined
}
