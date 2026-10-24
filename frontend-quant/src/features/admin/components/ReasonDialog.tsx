import { Loader2 } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { z } from 'zod'
import { errorMessage, toApiError, type ApiError } from '@/api/errors'
import { MoneyErrorAlert } from '@/components/money/MoneyErrorAlert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { FieldMessage, Label } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'

const REASON_MAX = 500

/** Copy for a failed admin action. A 409 here almost always means someone else acted first. */
function adminErrorCopy(e: ApiError) {
  if (e.code === 'INVALID_STATE')
    return {
      title: 'This has already changed.',
      detail: 'Another admin may have acted on it first. The list has been refreshed.',
    }
  if (e.code === 'NOT_FOUND') return { title: 'It no longer exists.', detail: errorMessage(e) }
  return { title: 'That didn’t go through.', detail: errorMessage(e) }
}

/** Client-side check for UX only; the server validates the same bounds (5–max, 500 unless a contract says less). */
const requiredReason = (max: number) =>
  z
    .string()
    .trim()
    .min(5, 'Give a reason of at least 5 characters')
    .max(max, `Keep it under ${max} characters`)
const optionalReason = (max: number) =>
  z.string().trim().max(max, `Keep it under ${max} characters`)

/**
 * Review-then-confirm dialog for a sensitive admin action. The reason is audited server-side,
 * so the copy says so. The confirm button is disabled while in flight (no double submit).
 */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  summary,
  reasonLabel = 'Reason',
  reasonRequired,
  maxLength = REASON_MAX,
  placeholder,
  confirmLabel,
  destructive,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  summary?: ReactNode
  reasonLabel?: string
  reasonRequired: boolean
  /** The contract's limit for this reason (default 500). */
  maxLength?: number
  placeholder?: string
  confirmLabel: string
  destructive?: boolean
  /** Resolve on success; throw the API error on failure (it's shown inline). */
  onConfirm: (reason: string) => Promise<unknown>
}) {
  // While the request is in flight the dialog can't be dismissed (Esc / overlay), so a late
  // success can never close a different dialog opened in the meantime.
  const [busy, setBusy] = useState(false)
  return (
    <Dialog open={open} onOpenChange={(o) => (o || !busy) && onOpenChange(o)}>
      <DialogContent title={title} description={description}>
        {/* Keyed by `open` so each opening starts empty. */}
        {open && (
          <ReasonForm
            summary={summary}
            reasonLabel={reasonLabel}
            reasonRequired={reasonRequired}
            maxLength={maxLength}
            placeholder={placeholder}
            confirmLabel={confirmLabel}
            destructive={destructive}
            onConfirm={onConfirm}
            onCancel={() => onOpenChange(false)}
            onBusyChange={setBusy}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}

function ReasonForm({
  summary,
  reasonLabel,
  reasonRequired,
  maxLength,
  placeholder,
  confirmLabel,
  destructive,
  onConfirm,
  onCancel,
  onBusyChange,
}: {
  summary?: ReactNode
  reasonLabel: string
  reasonRequired: boolean
  maxLength: number
  placeholder?: string
  confirmLabel: string
  destructive?: boolean
  onConfirm: (reason: string) => Promise<unknown>
  onCancel: () => void
  onBusyChange: (busy: boolean) => void
}) {
  const id = useId()
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const [serverField, setServerField] = useState<string | null>(null)
  const [failure, setFailure] = useState<ApiError | null>(null)
  const [pending, setPending] = useState(false)

  const parsed = (reasonRequired ? requiredReason : optionalReason)(maxLength).safeParse(reason)
  const clientError = parsed.success ? undefined : parsed.error.issues[0]?.message
  const fieldError = serverField ?? (touched ? clientError : undefined)

  async function submit() {
    setTouched(true)
    if (!parsed.success || pending) return
    setPending(true)
    onBusyChange(true)
    setFailure(null)
    setServerField(null)
    try {
      await onConfirm(parsed.data)
    } catch (e) {
      const err = toApiError(e)
      const fe = err.fieldErrors.find((f) => f.field === 'reason')
      if (fe) setServerField(`Reason ${fe.message}`)
      else setFailure(err)
    } finally {
      setPending(false)
      onBusyChange(false)
    }
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        void submit()
      }}
      className="space-y-5"
    >
      {summary && (
        <div className="rounded-[var(--radius-control)] bg-surface-2 p-4 text-sm">{summary}</div>
      )}
      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <Label htmlFor={id}>
            {reasonLabel}
            {!reasonRequired && <span className="font-normal text-muted"> (optional)</span>}
          </Label>
          <span className="num text-xs text-muted" aria-hidden="true">
            {reason.trim().length}/{maxLength}
          </span>
        </div>
        <Textarea
          id={id}
          value={reason}
          placeholder={placeholder}
          maxLength={maxLength + 50}
          onChange={(e) => {
            setReason(e.target.value)
            setServerField(null)
          }}
          onBlur={() => setTouched(true)}
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={`${id}-hint${fieldError ? ` ${id}-error` : ''}`}
          autoFocus
        />
        <FieldMessage id={`${id}-error`}>{fieldError}</FieldMessage>
        <p id={`${id}-hint`} className="text-xs text-muted">
          Recorded in the audit log with your name and IP. The customer is notified.
        </p>
      </div>
      {failure && <MoneyErrorAlert copy={adminErrorCopy(failure)} />}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="ghost" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
        <Button type="submit" variant={destructive ? 'destructive' : 'primary'} disabled={pending}>
          {pending && <Loader2 className="animate-spin" aria-hidden="true" />}
          {confirmLabel}
        </Button>
      </div>
    </form>
  )
}
