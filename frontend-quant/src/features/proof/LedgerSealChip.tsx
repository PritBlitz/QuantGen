import { useQuery } from '@tanstack/react-query'
import { ShieldCheck } from 'lucide-react'
import { Link } from 'react-router'
import { proofApi } from '@/api/endpoints.v2'

/**
 * Rail footer chip (DESIGN.md v2 §4): the latest published Proof-of-Balance root, linking to the
 * verification page. Falls back to a plain link when no root is published yet (pre V2-M8).
 */
export function LedgerSealChip() {
  const roots = useQuery({
    queryKey: ['proofs', 'roots'],
    queryFn: proofApi.roots,
    staleTime: 5 * 60_000,
    retry: false,
  })
  const latest = roots.data?.[0]
  return (
    <Link
      to="/proof"
      className="group flex items-center gap-2.5 rounded-[12px] bg-gain/[0.06] px-3 py-2 text-xs transition-colors hover:bg-gain/10"
    >
      <span className="relative grid size-6 place-items-center rounded-full bg-gain/15 text-gain">
        {latest && <span className="absolute inset-0 rounded-full border border-gain/50 animate-pulse-ring" />}
        <ShieldCheck aria-hidden="true" className="size-3.5" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-medium text-text">Proof of Balance</span>
        <span className="num block truncate text-[0.6875rem] text-muted">
          {latest ? `root ${latest.root.slice(0, 10)}…` : 'Verify your balance'}
        </span>
      </span>
    </Link>
  )
}
