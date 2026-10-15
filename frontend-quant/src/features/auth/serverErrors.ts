import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import type { ApiError } from '@/api/errors'

const FRIENDLY: Record<string, string> = {
  'is already registered': 'An account with this email already exists. Sign in instead?',
}

/** Copy §7.1 fieldErrors onto matching form fields. Returns true if any were applied. */
export function applyFieldErrors<T extends FieldValues>(
  error: ApiError,
  setError: UseFormSetError<T>,
  fields: ReadonlyArray<Path<T>>,
): boolean {
  let applied = false
  for (const fe of error.fieldErrors) {
    const field = fields.find((f) => f === fe.field)
    if (!field) continue
    const message = FRIENDLY[fe.message] ?? capitalise(fe.message)
    setError(field, { type: 'server', message }, { shouldFocus: !applied })
    applied = true
  }
  return applied
}

const capitalise = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)
