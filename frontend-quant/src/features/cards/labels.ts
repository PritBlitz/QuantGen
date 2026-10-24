import type { DisputeReason, DisputeStatus, MerchantCategory } from '@/api/types'
import type { BadgeTone } from '@/components/ui/badge'

export const CATEGORY_LABEL: Record<MerchantCategory, string> = {
  GROCERY: 'Groceries',
  DINING: 'Dining',
  TRAVEL: 'Travel',
  ELECTRONICS: 'Electronics',
  GAMBLING: 'Gambling and betting',
  FUEL: 'Fuel',
  ENTERTAINMENT: 'Entertainment',
  OTHER: 'Everything else',
}
export const CATEGORIES = Object.keys(CATEGORY_LABEL) as MerchantCategory[]

/** "1234 5678 9012 3456" from any spacing; digits only, max 16. */
export const formatPan = (raw: string) =>
  raw
    .replace(/\D/g, '')
    .slice(0, 16)
    .replace(/(\d{4})(?=\d)/g, '$1 ')

export const DISPUTE_REASON: Record<DisputeReason, string> = {
  NOT_RECEIVED: 'I didn’t receive it',
  NOT_AS_DESCRIBED: 'Not as described',
  DUPLICATE: 'I was charged twice',
  NOT_RECOGNISED: 'I don’t recognise this payment',
  OTHER: 'Something else',
}
export const DISPUTE_STATUS: Record<DisputeStatus, { label: string; tone: BadgeTone }> = {
  OPEN: { label: 'Under review', tone: 'warn' },
  REFUNDED: { label: 'Refunded', tone: 'gain' },
  REJECTED: { label: 'Not upheld', tone: 'neutral' },
}
