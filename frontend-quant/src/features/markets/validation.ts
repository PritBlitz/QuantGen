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
