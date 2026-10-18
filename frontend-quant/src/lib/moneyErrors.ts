import { errorMessage, type ApiError } from '@/api/errors'
import type { MoneyString } from '@/api/types'
import { Decimal, formatINR, toDecimal } from './money'

export interface MoneyErrorCopy {
  title: string
  detail?: string
  /** Suggested next step the screen can render as a link/button. */
  next?: 'add-money' | 'edit' | 'retry'
}

/**
 * Human copy for money-action failures (DESIGN.md §3.3), with a next step.
 * `amount`/`balance` let us say exactly how much is missing.
 */
export function moneyErrorCopy(
  e: ApiError,
  ctx: { amount?: MoneyString; balance?: MoneyString } = {},
): MoneyErrorCopy {
  switch (e.code) {
    case 'INSUFFICIENT_FUNDS': {
      if (ctx.amount && ctx.balance) {
        const short = toDecimal(ctx.amount).minus(toDecimal(ctx.balance))
        if (short.gt(0)) {
          return {
            title: `You need ${formatINR(short)} more.`,
            detail: 'Add money to this account, or send a smaller amount.',
            next: 'add-money',
          }
        }
      }
      return { title: 'Not enough balance for this.', next: 'add-money' }
    }
    case 'LIMIT_EXCEEDED': {
      const m = /remaining[^\d]*(\d+(?:\.\d+)?)/i.exec(e.message)
      if (m?.[1]) {
        const left = new Decimal(m[1])
        return {
          title: left.isZero()
            ? 'You’ve reached today’s transfer limit.'
            : `You can send up to ${formatINR(left)} more today.`,
          detail: 'Transfers to other people are limited to ₹5,00,000 per day.',
          next: 'edit',
        }
      }
      return { title: 'This is over a limit.', detail: e.message, next: 'edit' }
    }
    case 'INSUFFICIENT_HOLDINGS':
      return {
        title: 'You don’t hold enough shares to sell that many.',
        detail: 'Lower the quantity, or check your portfolio.',
        next: 'edit',
      }
    case 'ACCOUNT_FROZEN':
      return {
        title: 'This account is frozen.',
        detail: 'Frozen accounts can receive money but can’t send it. Contact support.',
      }
    case 'NETWORK':
    case 'INTERNAL_ERROR':
      return {
        title: 'We couldn’t confirm this yet.',
        detail:
          'It’s safe to try again: the retry reuses the same request key, so it can’t be applied twice.',
        next: 'retry',
      }
    case 'VALIDATION_FAILED':
      return { title: 'Some details need fixing.', next: 'edit' }
    case 'STEP_UP_CANCELLED':
      return {
        title: 'Verification was cancelled, so nothing was sent.',
        detail: 'Confirm with your passkey or PIN to continue.',
        next: 'retry',
      }
    case 'PIN_LOCKED':
      return {
        title: 'Your PIN is locked for 30 minutes.',
        detail: 'Use a passkey instead, or try again later. Nothing was sent.',
      }
    case 'COOLING_PERIOD':
      return {
        title: 'This payee is new, so there’s a ₹25,000 cap for the first 24 hours.',
        detail: 'It protects you from scams that ask you to add someone and pay them at once.',
        next: 'edit',
      }
    case 'KYC_REQUIRED':
      return { title: 'Complete your KYC first.', detail: 'It takes a minute in Settings → Limits & KYC.' }
    default:
      return { title: errorMessage(e) }
  }
}
