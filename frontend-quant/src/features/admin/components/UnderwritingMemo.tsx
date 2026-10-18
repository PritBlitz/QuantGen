import { ChevronDown, FileText, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { errorMessage } from '@/api/errors'
import type { UnderwritingDto } from '@/api/types'
import { Badge, type BadgeTone } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { formatRelative } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useLoanDocuments, useUnderwriting } from '../riskHooks'

const REC: Record<NonNullable<UnderwritingDto['recommendation']>, { label: string; tone: BadgeTone }> = {
  APPROVE: { label: 'Suggests approve', tone: 'gain' },
  REJECT: { label: 'Suggests reject', tone: 'loss' },
  REVIEW: { label: 'Suggests a closer look', tone: 'warn' },
}

/**
 * The Underwriting Analyst's memo for a pending loan (F-RISK-4), loaded on demand. Advisory
 * only: the admin decides, and the memo's figures cite the document page they came from.
 */
export function UnderwritingMemo({ loanId }: { loanId: string }) {
  const [open, setOpen] = useState(false)
  const memo = useUnderwriting(loanId, open)
  const docs = useLoanDocuments(loanId)
  const u = memo.data
  return (
    <div className="mt-4 rounded-[var(--radius-control)] bg-violet/[0.06] hairline">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm"
      >
        <Sparkles aria-hidden="true" className="size-4 text-violet" />
        <span className="flex-1 font-medium">Underwriting memo</span>
        <span className="text-xs text-muted">
          {docs.data ? `${docs.data.length} document${docs.data.length === 1 ? '' : 's'}` : ''}
        </span>
        <ChevronDown aria-hidden="true" className={cn('size-4 text-muted transition-transform', open && 'rotate-180')} />
      </button>
      {open && (
        <div className="space-y-3 border-t border-border px-3 py-3 text-sm">
          {memo.isError ? (
            <p className="text-muted">{errorMessage(memo.error)}</p>
          ) : !u ? (
            <Skeleton className="h-20" />
          ) : u.status === 'PENDING' ? (
            <p className="text-muted">{u.flags[0] ?? 'The memo is being prepared. Check back in a minute.'}</p>
          ) : u.status === 'FAILED' ? (
            <p className="text-muted">The AI couldn’t read these documents. Review them yourself; the memo is optional.</p>
          ) : (
            <>
              <p className="flex flex-wrap items-center gap-2">
                {u.recommendation && <Badge tone={REC[u.recommendation].tone}>{REC[u.recommendation].label}</Badge>}
                <span className="text-xs text-muted">AI-written, advisory{u.generatedAt ? ` · ${formatRelative(u.generatedAt)}` : ''}</span>
              </p>
              {u.memo && <p className="whitespace-pre-wrap">{u.memo}</p>}
              {u.flags.length > 0 && (
                <ul className="list-disc space-y-0.5 pl-5 text-warn">
                  {u.flags.map((f) => (
                    <li key={f}>{f}</li>
                  ))}
                </ul>
              )}
              {u.extracted.length > 0 && (
                <table className="w-full text-xs">
                  <caption className="sr-only">Figures read from the documents</caption>
                  <tbody>
                    {u.extracted.map((x) => (
                      <tr key={`${x.field}-${x.page}`} className="border-t border-border">
                        <th scope="row" className="py-1.5 pr-3 text-left font-normal text-muted">
                          {x.field}
                        </th>
                        <td className="py-1.5 pr-3">{x.value}</td>
                        <td className="py-1.5 text-right text-muted">p. {x.page}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
          {docs.data && docs.data.length > 0 && (
            <ul className="flex flex-wrap gap-1.5">
              {docs.data.map((d) => (
                <li key={d.id} className="inline-flex items-center gap-1 rounded-full bg-surface-2 px-2.5 py-1 text-xs text-muted">
                  <FileText aria-hidden="true" className="size-3" /> {d.fileName}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
