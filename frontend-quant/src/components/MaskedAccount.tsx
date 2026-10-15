import { Eye, EyeOff } from 'lucide-react'
import { useEffect, useState } from 'react'
import { groupAccountNumber, maskAccountNumber } from '@/lib/mask'
import { cn } from '@/lib/utils'

const AUTO_HIDE_MS = 10_000

/** Masked by default; "reveal" shows the full number and re-masks after 10 s (DESIGN.md §1). */
export function MaskedAccount({
  accountNumber,
  className,
  revealable = true,
}: {
  accountNumber: string
  className?: string
  revealable?: boolean
}) {
  const [revealed, setRevealed] = useState(false)

  useEffect(() => {
    if (!revealed) return
    const t = window.setTimeout(() => setRevealed(false), AUTO_HIDE_MS)
    return () => window.clearTimeout(t)
  }, [revealed])

  return (
    <span className={cn('inline-flex items-center gap-1.5', className)}>
      <span className="num tracking-wide">
        {revealed ? groupAccountNumber(accountNumber) : maskAccountNumber(accountNumber)}
      </span>
      {revealable && (
        <button
          type="button"
          onClick={() => setRevealed((r) => !r)}
          aria-label={revealed ? 'Hide account number' : 'Show account number'}
          aria-pressed={revealed}
          className="rounded p-1 text-muted transition-colors hover:text-text"
        >
          {revealed ? <EyeOff className="size-3.5" /> : <Eye className="size-3.5" />}
        </button>
      )}
    </span>
  )
}
