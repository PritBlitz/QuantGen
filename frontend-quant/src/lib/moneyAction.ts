import { useCallback, useRef, useState } from 'react'
import { toApiError, type ApiError } from '@/api/errors'
import { useIdempotencyKey } from './idempotency'

export type MoneyStep = 'form' | 'review' | 'success'

/**
 * Drives every money action: fill in → review → confirm (DESIGN.md §3.1, CLAUDE.md).
 *
 * - The Idempotency-Key is created when the review step opens and reused for every retry
 *   of that review (timeouts, double clicks), so money can't move twice.
 * - Editing the form (going back) discards the key; success discards it too.
 * - `confirm` ignores re-entry while a request is in flight.
 * - Nothing is shown as done until the server answers (no optimistic state).
 */
export function useMoneyAction<T>() {
  const { ensure, reset } = useIdempotencyKey()
  const [step, setStep] = useState<MoneyStep>('form')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [result, setResult] = useState<T | null>(null)
  const inFlight = useRef(false)

  const review = useCallback(() => {
    ensure()
    setError(null)
    setStep('review')
  }, [ensure])

  const edit = useCallback(() => {
    reset()
    setError(null)
    setStep('form')
  }, [reset])

  const confirm = useCallback(
    async (send: (idempotencyKey: string) => Promise<T>) => {
      if (inFlight.current) return { ok: false as const, error: null }
      inFlight.current = true
      setPending(true)
      setError(null)
      try {
        const res = await send(ensure())
        setResult(res)
        reset()
        setStep('success')
        return { ok: true as const, value: res }
      } catch (e) {
        // Keep the key: a retry of the same review must replay, not re-execute blindly.
        const err = toApiError(e)
        setError(err)
        return { ok: false as const, error: err }
      } finally {
        inFlight.current = false
        setPending(false)
      }
    },
    [ensure, reset],
  )

  const restart = useCallback(() => {
    reset()
    setResult(null)
    setError(null)
    setStep('form')
  }, [reset])

  return { step, pending, error, result, review, edit, confirm, restart }
}
