import type { MoneyString } from '@/api/types'
import { AMOUNT_INPUT_RE, formatINR, toDecimal } from '@/lib/money'

export const TRANSFER_MIN = '1'
export const TRANSFER_MAX = '200000'
export const NOTE_MAX = 140

/** F-ACC-2: 12 digits. Users may type spaces; we strip them. */
export const normaliseAccountNumber = (v: string) => v.replace(/\s+/g, '')
export const isAccountNumber = (v: string) => /^\d{12}$/.test(v)

export interface TransferDraft {
  fromAccountId: string
  toAccountNumber: string
  amount: string
  note: string
}

export type TransferErrors = Partial<Record<keyof TransferDraft, string>>

/** UX validation mirroring F-PAY-3. The server re-checks everything (incl. the daily limit). */
export function validateTransfer(d: TransferDraft, balance?: MoneyString): TransferErrors {
  const e: TransferErrors = {}
  if (!d.fromAccountId) e.fromAccountId = 'Choose an account to send from'
  const to = normaliseAccountNumber(d.toAccountNumber)
  if (!to) e.toAccountNumber = 'Choose who to send to'
  else if (!isAccountNumber(to)) e.toAccountNumber = 'Account numbers have 12 digits'
  if (!d.amount) e.amount = 'Enter an amount'
  else if (!AMOUNT_INPUT_RE.test(d.amount)) e.amount = 'Use a number with up to 2 decimals'
  else {
    const a = toDecimal(d.amount)
    if (a.lt(TRANSFER_MIN)) e.amount = `The minimum is ${formatINR(TRANSFER_MIN)}`
    else if (a.gt(TRANSFER_MAX))
      e.amount = `The most you can send at once is ${formatINR(TRANSFER_MAX)}`
    else if (balance && a.gt(toDecimal(balance)))
      e.amount = `You need ${formatINR(a.minus(toDecimal(balance)))} more in this account`
  }
  if (d.note.length > NOTE_MAX) e.note = `Keep the note under ${NOTE_MAX} characters`
  return e
}
