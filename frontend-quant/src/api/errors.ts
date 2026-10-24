import { isAxiosError } from 'axios'
import type { ErrorBody, ErrorCode, ErrorCodeV2, FieldError, StepUpChallenge } from './types'

/**
 * `NETWORK` is client-side only: the request never got a §7.1 body back.
 * `STEP_UP_CANCELLED` is client-side only: the user closed the step-up dialog.
 */
export type ClientErrorCode = ErrorCode | ErrorCodeV2 | 'NETWORK' | 'STEP_UP_CANCELLED'

export class ApiError extends Error {
  readonly status: number
  readonly code: ClientErrorCode
  readonly fieldErrors: FieldError[]
  readonly requestId: string | null
  /** Seconds, from a `Retry-After` header on 429. Display only; may be absent. */
  readonly retryAfter: number | null
  /** Present on `401 STEP_UP_REQUIRED` (PRD §12.1). */
  readonly stepUp: StepUpChallenge | null

  constructor(init: {
    status: number
    code: ClientErrorCode
    message: string
    fieldErrors?: FieldError[]
    requestId?: string | null
    retryAfter?: number | null
    stepUp?: StepUpChallenge | null
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.requestId = init.requestId ?? null
    this.retryAfter = init.retryAfter ?? null
    this.stepUp = init.stepUp ?? null
  }
}

function isErrorBody(data: unknown): data is ErrorBody & { stepUp?: StepUpChallenge } {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as Record<string, unknown>).code === 'string' &&
    typeof (data as Record<string, unknown>).status === 'number'
  )
}

function parseRetryAfter(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d{1,6}$/.test(value)) return null
  return Number.parseInt(value, 10)
}

/** Normalise anything thrown by the API layer into an ApiError. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (isAxiosError(error)) {
    const res = error.response
    if (res && isErrorBody(res.data)) {
      return new ApiError({
        status: res.status,
        code: res.data.code,
        message: res.data.message,
        fieldErrors: res.data.fieldErrors ?? [],
        requestId: res.data.requestId,
        retryAfter: parseRetryAfter(res.headers['retry-after']),
        stepUp: res.data.stepUp ?? null,
      })
    }
    if (res) {
      return new ApiError({
        status: res.status,
        code: res.status === 401 ? 'UNAUTHORIZED' : 'INTERNAL_ERROR',
        message: 'Unexpected response from the server',
      })
    }
    return new ApiError({ status: 0, code: 'NETWORK', message: 'Network error' })
  }
  return new ApiError({ status: 0, code: 'INTERNAL_ERROR', message: 'Unexpected error' })
}

const COPY: Record<ClientErrorCode, string> = {
  VALIDATION_FAILED: 'Some details need fixing. Check the highlighted fields.',
  UNAUTHORIZED: 'Your session has ended. Please sign in again.',
  FORBIDDEN: "You don't have access to that.",
  NOT_FOUND: "We couldn't find that. It may have been removed.",
  INSUFFICIENT_FUNDS: "There isn't enough balance for this. Add money and try again.",
  ACCOUNT_FROZEN: 'This account is frozen, so it can’t send money. Contact support.',
  LIMIT_EXCEEDED: 'This is over your transfer limit. Try a smaller amount.',
  IDEMPOTENCY_CONFLICT: 'This request changed after it was sent. Review it and try again.',
  INSUFFICIENT_HOLDINGS: "You don't hold enough shares to sell that many.",
  LOAN_NOT_ELIGIBLE: "You're not eligible for this loan amount right now.",
  INVALID_STATE: "That action isn't available any more. Refresh and try again.",
  RATE_LIMITED: 'Too many attempts. Please wait a moment and try again.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again.',
  NETWORK: "Can't reach QuantGen. Check your connection and try again.",
  STEP_UP_REQUIRED: 'This needs a quick identity check. Confirm with your passkey or PIN.',
  STEP_UP_CANCELLED: 'Verification was cancelled, so nothing happened.',
  PIN_LOCKED: 'Too many wrong PINs. Your PIN is locked for 30 minutes; use a passkey instead.',
  COOLING_PERIOD:
    'This payee was added less than 24 hours ago, so only ₹25,000 can go to them for now.',
  INTENT_EXPIRED: 'That suggestion expired. Ask the assistant again.',
  RULE_INVALID: 'This rule has a problem. Check the highlighted parts.',
  BUDGET_EXHAUSTED: "You've used today's assistant allowance. It resets at midnight.",
  FEATURE_DISABLED: 'This feature is switched off right now. Everything else works as usual.',
  KYC_REQUIRED: 'Complete your KYC profile first. It takes a minute in Settings.',
  PAYMENT_VERIFICATION_FAILED: "We couldn't verify that payment, so nothing was credited.",
  GATEWAY_UNAVAILABLE: "The payment gateway isn't available right now. Try the demo deposit.",
  AI_UNAVAILABLE: "The assistant isn't available right now. Everything else in QuantGen works as usual.",
  AI_BUSY: 'The assistant is busy right now. Try again in a minute.',
  TOOL_LIMIT: 'That question needed too many steps. Try asking something narrower.',
}

/** Human copy for a toast. Field errors are shown inline by forms instead. */
export function errorMessage(error: unknown): string {
  const e = toApiError(error)
  if (e.code === 'RATE_LIMITED' && e.retryAfter) {
    const mins = Math.ceil(e.retryAfter / 60)
    return `Too many attempts. Try again in ${mins} minute${mins === 1 ? '' : 's'}.`
  }
  // Codes added after this build (PRD §12.2: treat unknown values gracefully).
  return COPY[e.code] ?? COPY.INTERNAL_ERROR
}
