import { isAxiosError } from 'axios'
import type { ErrorBody, ErrorCode, FieldError } from './types'

/** `NETWORK` is client-side only: the request never got a §7.1 body back. */
export type ClientErrorCode = ErrorCode | 'NETWORK'

export class ApiError extends Error {
  readonly status: number
  readonly code: ClientErrorCode
  readonly fieldErrors: FieldError[]
  readonly requestId: string | null
  /** Seconds, from a `Retry-After` header on 429. Display only; may be absent. */
  readonly retryAfter: number | null

  constructor(init: {
    status: number
    code: ClientErrorCode
    message: string
    fieldErrors?: FieldError[]
    requestId?: string | null
    retryAfter?: number | null
  }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.requestId = init.requestId ?? null
    this.retryAfter = init.retryAfter ?? null
  }
}

function isErrorBody(data: unknown): data is ErrorBody {
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
}

/** Human copy for a toast. Field errors are shown inline by forms instead. */
export function errorMessage(error: unknown): string {
  const e = toApiError(error)
  if (e.code === 'RATE_LIMITED' && e.retryAfter) {
    const mins = Math.ceil(e.retryAfter / 60)
    return `Too many attempts. Try again in ${mins} minute${mins === 1 ? '' : 's'}.`
  }
  return COPY[e.code]
}
