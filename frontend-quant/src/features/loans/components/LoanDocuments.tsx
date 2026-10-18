import { useMutation, useQueryClient } from '@tanstack/react-query'
import { FileText, Loader2, Upload } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { toast } from 'sonner'
import { riskApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { Button } from '@/components/ui/button'
import { useLoanDocuments, riskKeys } from '@/features/admin/riskHooks'
import { formatRelative } from '@/lib/time'

const MAX_BYTES = 5 * 1024 * 1024
const MAX_DOCS = 3
const TYPES = ['application/pdf', 'image/png', 'image/jpeg']

const size = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1024 / 1024).toFixed(1)} MB`)

/**
 * Supporting documents for a loan application (F-RISK-3): salary slips or statements, read by
 * the Underwriting Analyst to help the reviewer. Type and size are checked here for UX; the
 * server sniffs the real type and enforces the limits. Explicit consent is required.
 */
export function LoanDocuments({ loanId, canUpload }: { loanId: string; canUpload: boolean }) {
  const qc = useQueryClient()
  const docs = useLoanDocuments(loanId)
  const inputRef = useRef<HTMLInputElement>(null)
  const consentId = useId()
  const [consent, setConsent] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const upload = useMutation({
    mutationFn: (f: File) => riskApi.uploadDocument(loanId, f),
    onSuccess: () => {
      toast.success('Document uploaded.')
      setFile(null)
      if (inputRef.current) inputRef.current.value = ''
      void qc.invalidateQueries({ queryKey: riskKeys.documents(loanId) })
    },
    onError: (e) => {
      const ae = toApiError(e)
      setError(ae.code === 'LIMIT_EXCEEDED' ? `You can upload at most ${MAX_DOCS} documents.` : (ae.fieldErrors[0]?.message ? `The file ${ae.fieldErrors[0].message}.` : errorMessage(ae)))
    },
  })

  // A 404 means the endpoint isn't live yet (or not your loan): hide the section quietly.
  if (docs.isError) return null
  const list = docs.data ?? []
  const full = list.length >= MAX_DOCS

  return (
    <section aria-labelledby={`${consentId}-title`} className="space-y-3">
      <h2 id={`${consentId}-title`} className="font-medium">
        Supporting documents
      </h2>
      {list.length > 0 ? (
        <ul className="space-y-1.5 text-sm">
          {list.map((d) => (
            <li key={d.id} className="flex items-center gap-2">
              <FileText aria-hidden="true" className="size-4 text-muted" />
              <span className="min-w-0 flex-1 truncate">{d.fileName}</span>
              <span className="text-xs text-muted">
                {size(d.sizeBytes)} · {formatRelative(d.uploadedAt)}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted">Optional: salary slips or a bank statement can speed up the review.</p>
      )}
      {canUpload && !full && (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            setError(null)
            if (!file) return setError('Choose a file first.')
            if (!TYPES.includes(file.type)) return setError('Use a PDF, PNG or JPEG.')
            if (file.size > MAX_BYTES) return setError('The file must be 5 MB or smaller.')
            if (!consent) return setError('Tick the consent box to upload.')
            upload.mutate(file)
          }}
        >
          <input
            ref={inputRef}
            type="file"
            accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
            aria-label="Document to upload"
            onChange={(e) => {
              setError(null)
              setFile(e.target.files?.[0] ?? null)
            }}
            className="block w-full text-sm text-muted file:mr-3 file:rounded-[var(--radius-control)] file:border-0 file:bg-surface-2 file:px-3 file:py-2 file:text-sm file:text-text"
          />
          <label htmlFor={consentId} className="flex items-start gap-2 text-xs text-muted">
            <input id={consentId} type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} className="mt-0.5" />
            I agree that QuantGen’s AI assistant may read this document to help review my application. A person makes the decision.
          </label>
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <Button type="submit" variant="secondary" size="sm" disabled={upload.isPending}>
            {upload.isPending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Upload aria-hidden="true" />}
            {upload.isPending ? 'Uploading…' : 'Upload'}
          </Button>
        </form>
      )}
    </section>
  )
}
