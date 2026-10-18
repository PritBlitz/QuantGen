import type { MerchantCategory } from '@/api/types'

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
