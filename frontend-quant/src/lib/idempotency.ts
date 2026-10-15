import { useCallback, useRef, useState } from 'react'

/**
 * One Idempotency-Key per money action (CLAUDE.md, DESIGN.md §3.1).
 *
 * - `ensure()` when the review step opens: creates a key if there isn't one, else reuses it,
 *   so a retry after a timeout or a double-click replays instead of moving money twice.
 * - `reset()` only after the server confirms success, or when the user edits the form.
 */
export function useIdempotencyKey() {
  const ref = useRef<string | null>(null)
  const [key, setKey] = useState<string | null>(null)

  const ensure = useCallback(() => {
    if (!ref.current) {
      ref.current = crypto.randomUUID()
      setKey(ref.current)
    }
    return ref.current
  }, [])

  const reset = useCallback(() => {
    ref.current = null
    setKey(null)
  }, [])

  return { key, ensure, reset }
}
