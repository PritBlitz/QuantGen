import { useMutation } from '@tanstack/react-query'
import { Check, CircleCheck, CircleX, Copy, Fingerprint, Loader2, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { proofApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useAccounts } from '@/api/queries'
import type { BalanceProofDto, ProofRootDto } from '@/api/types'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { accountLabel } from '@/features/accounts/labels'
import { verifyBalanceProof, type VerifyResult } from '@/lib/merkle'
import { formatDate } from '@/lib/time'
import { cn } from '@/lib/utils'
import { useProofRoots } from './hooks'

const short = (h: string) => `${h.slice(0, 10)}…${h.slice(-6)}`

/**
 * `/proof` (F-POB-1…4): check, in this browser, that your balance is inside the total the bank
 * published. The maths runs locally with WebCrypto (lib/merkle.ts); the server only supplies
 * your leaf and the sibling hashes.
 */
export function ProofPage() {
  const roots = useProofRoots()
  const latest = roots.data?.[0]
  return (
    <>
      <PageHeader
        eyebrow="Proof of Balance"
        title={
          <>
            Don’t trust. <em className="text-gradient">Verify.</em>
          </>
        }
        description="Every night QuantGen publishes one fingerprint of all customer balances. Check that your balance is included, using maths that runs in your own browser."
      />
      <div className="grid gap-4 lg:grid-cols-12">
        <div className="space-y-4 lg:col-span-7">
          {roots.isError ? (
            <PanelError what="published roots" onRetry={() => void roots.refetch()} />
          ) : !roots.data ? (
            <Skeleton className="h-80" />
          ) : roots.data.length === 0 ? (
            <Panel title="No snapshot yet" eyebrow="Verify">
              <p className="text-sm text-muted">
                The first snapshot is published at midnight (IST). Come back tomorrow to verify.
              </p>
            </Panel>
          ) : (
            <Verifier roots={roots.data} />
          )}
        </div>
        <div className="space-y-4 lg:col-span-5">
          {latest && <LatestRoot root={latest} />}
          <Panel eyebrow="Be clear-eyed" title="What a green tick means">
            <ul className="space-y-2 text-sm">
              <Point ok>Your balance on that date was counted in the published total.</Point>
              <Point ok>No account in the tree has a negative balance hiding liabilities.</Point>
              <Point ok>
                The published fingerprint is tied to the ledger’s hash chain, so history can’t be
                quietly rewritten.
              </Point>
              <Point>
                It doesn’t prove the bank holds matching assets outside the system (this is a
                sandbox).
              </Point>
            </ul>
          </Panel>
          {roots.data && roots.data.length > 1 && <History roots={roots.data} />}
        </div>
      </div>
    </>
  )
}

function Point({ ok, children }: { ok?: boolean; children: React.ReactNode }) {
  return (
    <li className="flex gap-2">
      {ok ? (
        <Check aria-label="Proves" className="mt-0.5 size-4 shrink-0 text-gain" />
      ) : (
        <CircleX aria-label="Doesn’t prove" className="mt-0.5 size-4 shrink-0 text-muted" />
      )}
      <span className={ok ? undefined : 'text-muted'}>{children}</span>
    </li>
  )
}

function Verifier({ roots }: { roots: ProofRootDto[] }) {
  const accounts = useAccounts()
  const mine = (accounts.data ?? []).filter((a) => a.status !== 'CLOSED')
  const [accountId, setAccountId] = useState('')
  const [date, setDate] = useState(roots[0]?.date ?? '')
  const acct = accountId || mine[0]?.id || ''
  const published = roots.find((r) => r.date === date)

  const verify = useMutation({
    mutationFn: async (): Promise<{ proof: BalanceProofDto; result: VerifyResult }> => {
      if (!published) throw new Error('no root')
      const proof = await proofApi.proof(acct, date)
      return { proof, result: await verifyBalanceProof(proof, published) }
    },
  })
  const res = verify.data
  const fresh = res && res.proof.leaf.accountId === acct && res.proof.date === date

  return (
    <Panel eyebrow="Verify" title="Check my balance" tone="cyan">
      <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto] sm:items-end">
        <div className="space-y-1.5">
          <Label htmlFor="proof-account">Account</Label>
          <Select
            id="proof-account"
            value={acct}
            onValueChange={(v) => {
              setAccountId(v)
              verify.reset()
            }}
            options={mine.map((a) => ({
              value: a.id,
              label: accountLabel(a),
              text: accountLabel(a),
            }))}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="proof-date">Snapshot</Label>
          <Select
            id="proof-date"
            value={date}
            onValueChange={(v) => {
              setDate(v)
              verify.reset()
            }}
            options={roots.map((r) => ({
              value: r.date,
              label: formatDate(r.date),
              text: formatDate(r.date),
            }))}
            className="sm:w-40"
          />
        </div>
        <Button
          size="lg"
          disabled={!acct || !published || verify.isPending}
          onClick={() => verify.mutate()}
        >
          {verify.isPending ? (
            <Loader2 className="animate-spin" aria-hidden="true" />
          ) : (
            <Fingerprint aria-hidden="true" />
          )}
          {verify.isPending ? 'Verifying…' : 'Verify'}
        </Button>
      </div>

      <div aria-live="polite" className="mt-5">
        {verify.isError && (
          <p role="alert" className="text-sm text-loss">
            {toApiError(verify.error).code === 'NOT_FOUND'
              ? 'This account wasn’t in that snapshot (it may have been opened later). Pick a newer date.'
              : errorMessage(verify.error)}
          </p>
        )}
        {fresh && published && (
          <Result proof={res.proof} result={res.result} published={published} />
        )}
      </div>
    </Panel>
  )
}

function Result({
  proof,
  result,
  published,
}: {
  proof: BalanceProofDto
  result: VerifyResult
  published: ProofRootDto
}) {
  return (
    <div className="space-y-4">
      <div
        role="status"
        className={cn(
          'flex items-start gap-3 rounded-[var(--radius-control)] p-4',
          result.ok ? 'border border-gain/40 bg-gain/10' : 'border border-loss/40 bg-loss/10',
        )}
      >
        {result.ok ? (
          <CircleCheck aria-hidden="true" className="size-6 shrink-0 text-gain" />
        ) : (
          <CircleX aria-hidden="true" className="size-6 shrink-0 text-loss" />
        )}
        <div>
          <p className="font-medium">
            {result.ok
              ? 'Verified. Your balance is in the published total.'
              : 'Verification failed.'}
          </p>
          <p className="mt-0.5 text-sm text-muted">
            {result.ok ? (
              <>
                On {formatDate(proof.date)} this account held{' '}
                <Money value={proof.leaf.balance} className="text-text" />, and it’s one of{' '}
                {published.leafCount.toLocaleString('en-IN')} balances adding up to{' '}
                <Money value={published.totalLiabilities} className="text-text" />.
              </>
            ) : (
              `${result.reason} Please tell support; don’t rely on this snapshot.`
            )}
          </p>
        </div>
      </div>

      <details className="group rounded-[var(--radius-control)] bg-surface-2/50 hairline">
        <summary className="cursor-pointer select-none px-4 py-3 text-sm font-medium">
          Show the maths, step by step
        </summary>
        <ol className="space-y-2 px-4 pb-4 text-sm">
          <Step
            n={0}
            title="Your leaf"
            hash={result.steps[0]?.node.hash ?? ''}
            sum={result.steps[0]?.node.sum ?? '0'}
          >
            SHA-256 of “account id | balance | salt”. The salt hides your balance from anyone who
            sees the tree.
          </Step>
          {proof.path.map((sib, i) => {
            const step = result.steps[i + 1]
            return (
              <Step
                key={i}
                n={i + 1}
                title={`Combine with the ${sib.side === 'LEFT' ? 'left' : 'right'} neighbour`}
                hash={step?.node.hash ?? '—'}
                sum={step?.node.sum ?? '0'}
              >
                Neighbour {short(sib.hash)} holds <Money value={sib.sum} />.
              </Step>
            )
          })}
          <li className="rounded-[var(--radius-control)] bg-surface-solid p-3 hairline">
            <p className="font-medium">Compare with what was published</p>
            <dl className="mt-1.5 space-y-1 text-xs">
              <Compare label="Root" computed={result.computedRoot} published={published.root} />
              <Compare
                label="Total"
                computed={result.computedTotal}
                published={published.totalLiabilities}
                money
              />
            </dl>
          </li>
        </ol>
      </details>
    </div>
  )
}

function Step({
  n,
  title,
  hash,
  sum,
  children,
}: {
  n: number
  title: string
  hash: string
  sum: string
  children: React.ReactNode
}) {
  return (
    <li className="rounded-[var(--radius-control)] bg-surface-solid p-3 hairline">
      <p className="flex items-baseline justify-between gap-3">
        <span className="font-medium">
          <span className="eyebrow mr-1.5">{n}</span>
          {title}
        </span>
        <Money value={sum} className="text-xs text-muted" />
      </p>
      <p className="mt-0.5 text-xs text-muted">{children}</p>
      <p className="num mt-1 break-all text-[0.6875rem] text-muted">{hash}</p>
    </li>
  )
}

function Compare({
  label,
  computed,
  published,
  money,
}: {
  label: string
  computed: string
  published: string
  money?: boolean
}) {
  const ok = money
    ? computed.replace(/0+$/, '') === published.replace(/0+$/, '')
    : computed === published.toLowerCase()
  return (
    <div className="flex flex-wrap items-baseline gap-x-2">
      <dt className="w-10 text-muted">{label}</dt>
      <dd className="num min-w-0 flex-1 break-all">
        {money ? <Money value={computed} /> : short(computed)}{' '}
        <span className="text-muted">vs</span>{' '}
        {money ? <Money value={published} /> : short(published)}
      </dd>
      <dd className={ok ? 'text-gain' : 'text-loss'}>{ok ? 'match' : 'mismatch'}</dd>
    </div>
  )
}

function LatestRoot({ root }: { root: ProofRootDto }) {
  return (
    <Panel eyebrow={`Published ${formatDate(root.date)}`} title="Today’s fingerprint" tone="cyan">
      <div className="flex items-center gap-2 rounded-[var(--radius-control)] bg-surface-2/60 p-3 hairline">
        <ShieldCheck aria-hidden="true" className="size-4 shrink-0 text-gain" />
        <code className="num min-w-0 flex-1 break-all text-xs">{root.root}</code>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="Copy root hash"
          onClick={() =>
            void navigator.clipboard?.writeText(root.root).then(
              () => toast.success('Root hash copied'),
              () => toast.error('Couldn’t copy'),
            )
          }
        >
          <Copy aria-hidden="true" />
        </Button>
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div>
          <dt className="text-xs text-muted">Total customer balances</dt>
          <dd className="mt-0.5 font-medium">
            <Money value={root.totalLiabilities} />
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted">Accounts included</dt>
          <dd className="mt-0.5 font-medium tabular-nums">
            {root.leafCount.toLocaleString('en-IN')}
          </dd>
        </div>
        <div className="col-span-2">
          <dt className="text-xs text-muted">Ledger hash chain at</dt>
          <dd className="num mt-0.5 text-xs">
            #{root.chainHead.position.toLocaleString('en-IN')} · {short(root.chainHead.hash)}
          </dd>
        </div>
        {root.anchor && (
          <div className="col-span-2">
            <dt className="text-xs text-muted">Also recorded in git</dt>
            <dd className="mt-0.5 text-xs">
              <AnchorLink anchor={root.anchor} /> ·{' '}
              <code className="num">roots/{root.date}.json</code>
              <span className="block text-muted">
                Anyone can check that file holds this same root, so it can’t be changed later
                without it showing.
              </span>
            </dd>
          </div>
        )}
      </dl>
    </Panel>
  )
}

const COMMIT_RE = /^[0-9a-f]{40}$/

/**
 * The anchor commit. A link only for an https repo and a 40-hex SHA; anything else is shown as
 * text, so a misconfigured repo URL can't become a `javascript:` link.
 */
function AnchorLink({ anchor }: { anchor: NonNullable<ProofRootDto['anchor']> }) {
  const sha = anchor.commit.slice(0, 7)
  let href: string | null = null
  try {
    const u = new URL(anchor.repo)
    if (u.protocol === 'https:' && COMMIT_RE.test(anchor.commit))
      href = `${u.origin}${u.pathname.replace(/\.git$|\/$/g, '')}/commit/${anchor.commit}`
  } catch {
    href = null
  }
  return href ? (
    <a href={href} target="_blank" rel="noreferrer noopener" className="num underline">
      commit {sha}
    </a>
  ) : (
    <span className="num">commit {sha}</span>
  )
}

function History({ roots }: { roots: ProofRootDto[] }) {
  return (
    <Panel eyebrow="History" title="Published roots">
      <ul className="divide-y divide-border text-sm">
        {roots.map((r) => (
          <li key={r.date} className="flex items-baseline justify-between gap-3 py-2">
            <span>{formatDate(r.date)}</span>
            <code className="num truncate text-xs text-muted">
              {short(r.root)}
              {r.anchor && <span className="ml-1.5 text-gain">· in git</span>}
            </code>
            <Money value={r.totalLiabilities} className="shrink-0 text-xs" />
          </li>
        ))}
      </ul>
    </Panel>
  )
}
