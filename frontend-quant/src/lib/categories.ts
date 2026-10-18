import {
  BookOpen,
  Bus,
  Clapperboard,
  HandCoins,
  HeartPulse,
  Home,
  Landmark,
  LineChart,
  Plane,
  ReceiptText,
  ShoppingBag,
  ShoppingCart,
  Shuffle,
  Utensils,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import type { Category } from '@/api/types'

/** Spending categories (PRD §12.14): label, icon and chart colour. */
export const CATEGORIES: Record<Category, { label: string; icon: LucideIcon; color: string }> = {
  FOOD: { label: 'Food & dining', icon: Utensils, color: 'var(--chart-4)' },
  GROCERIES: { label: 'Groceries', icon: ShoppingCart, color: 'var(--chart-5)' },
  SHOPPING: { label: 'Shopping', icon: ShoppingBag, color: 'var(--chart-6)' },
  TRANSPORT: { label: 'Transport', icon: Bus, color: 'var(--chart-3)' },
  BILLS: { label: 'Bills & utilities', icon: ReceiptText, color: 'var(--chart-2)' },
  RENT: { label: 'Rent', icon: Home, color: 'var(--chart-1)' },
  ENTERTAINMENT: { label: 'Entertainment', icon: Clapperboard, color: 'var(--chart-6)' },
  HEALTH: { label: 'Health', icon: HeartPulse, color: 'var(--chart-5)' },
  EDUCATION: { label: 'Education', icon: BookOpen, color: 'var(--chart-3)' },
  TRAVEL: { label: 'Travel', icon: Plane, color: 'var(--chart-2)' },
  TRANSFERS: { label: 'Transfers', icon: Shuffle, color: 'var(--text-muted)' },
  INVESTMENTS: { label: 'Investments', icon: LineChart, color: 'var(--chart-1)' },
  LOANS: { label: 'Loans', icon: HandCoins, color: 'var(--chart-4)' },
  INCOME: { label: 'Income', icon: Wallet, color: 'var(--gain)' },
  OTHER: { label: 'Other', icon: Landmark, color: 'var(--text-muted)' },
}

export const categoryOf = (key: string) =>
  CATEGORIES[key as Category] ?? { label: key, icon: Landmark, color: 'var(--text-muted)' }

/** Categories a budget can be set on (spending ones only). */
export const BUDGETABLE: Category[] = [
  'FOOD',
  'GROCERIES',
  'SHOPPING',
  'TRANSPORT',
  'BILLS',
  'RENT',
  'ENTERTAINMENT',
  'HEALTH',
  'EDUCATION',
  'TRAVEL',
  'OTHER',
]
