import type { AccountDto, TransactionType } from '@/api/types'
import { maskShort } from '@/lib/mask'

export const ACCOUNT_TYPE_LABEL: Record<AccountDto['type'], string> = {
  SAVINGS: 'Savings',
  CHECKING: 'Checking',
}

export const TX_TYPE_LABEL: Record<TransactionType, string> = {
  DEPOSIT: 'Deposit',
  TRANSFER: 'Transfer',
  TRADE: 'Trade',
  LOAN_DISBURSAL: 'Loan disbursal',
  LOAN_REPAYMENT: 'Loan repayment',
  REVERSAL: 'Reversal',
}

/** "Savings ••••4821" — safe to show anywhere (masked). */
export const accountLabel = (a: Pick<AccountDto, 'type' | 'accountNumber'>) =>
  `${ACCOUNT_TYPE_LABEL[a.type]} ${maskShort(a.accountNumber)}`
