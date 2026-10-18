import type { AccountDto, TransactionDto } from '@/api/types'
import { maskShort } from '@/lib/mask'

export const ACCOUNT_TYPE_LABEL: Record<AccountDto['type'], string> = {
  SAVINGS: 'Savings',
  CHECKING: 'Checking',
  GOAL: 'Goal',
  FIXED_DEPOSIT: 'Fixed deposit',
}

export const TX_TYPE_LABEL: Record<TransactionDto['type'], string> = {
  DEPOSIT: 'Deposit',
  TRANSFER: 'Transfer',
  TRADE: 'Trade',
  LOAN_DISBURSAL: 'Loan disbursal',
  LOAN_REPAYMENT: 'Loan repayment',
  REVERSAL: 'Reversal',
  BILL_PAYMENT: 'Bill payment',
  INTEREST: 'Interest',
  CARD: 'Card',
  REFUND: 'Refund',
  TOPUP: 'Top-up',
}

/** "Savings ••••4821" — safe to show anywhere (masked). */
export const accountLabel = (a: Pick<AccountDto, 'type' | 'accountNumber' | 'goal'>) =>
  a.goal?.name
    ? `${a.goal.name} ${maskShort(a.accountNumber)}`
    : `${ACCOUNT_TYPE_LABEL[a.type] ?? 'Account'} ${maskShort(a.accountNumber)}`
