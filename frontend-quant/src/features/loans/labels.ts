import type { InstallmentStatus, LoanStatus } from '@/api/types'

export const LOAN_STATUS: Record<
  LoanStatus,
  { label: string; tone: 'neutral' | 'gain' | 'loss' | 'warn' | 'accent' }
> = {
  PENDING: { label: 'In review', tone: 'warn' },
  APPROVED: { label: 'Approved', tone: 'accent' },
  ACTIVE: { label: 'Active', tone: 'gain' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
  REJECTED: { label: 'Rejected', tone: 'loss' },
}

export const INSTALLMENT_STATUS: Record<
  InstallmentStatus,
  { label: string; tone: 'neutral' | 'gain' | 'warn' }
> = {
  PAID: { label: 'Paid', tone: 'gain' },
  PENDING: { label: 'Upcoming', tone: 'neutral' },
  OVERDUE: { label: 'Overdue', tone: 'warn' },
}

export const inr0 = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 0,
})
