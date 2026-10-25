/**
 * MSW handlers: Proof of Balance (§12.10). Builds a *real* Merkle sum tree over every customer
 * account with the same canonical encoding the browser verifies (lib/merkle.ts), so the
 * in-browser check genuinely passes or fails.
 */
import { delay, http } from 'msw'
import type { BalanceProofDto, ProofRootDto } from '@/api/types'
import { leafNode, parentNode, sha256Hex, type HashedNode } from '@/lib/merkle'
import { BASE, db, error } from '../handlers'
import { addDays, Decimal, json, notFound, requireAdmin, requireUser, todayIst } from './common'

interface Snapshot {
  date: string
  levels: HashedNode[][]
  index: Map<string, { i: number; balance: string; salt: string }>
  root: ProofRootDto
}

const cache = new Map<string, Snapshot>()
const DAYS_KEPT = 7

/** Tests reset the mock DB between cases; drop frozen snapshots with it. */
export function resetProofSnapshots() {
  cache.clear()
}

/** A snapshot is frozen once taken (like the nightly job), so later payments don't change it. */
async function build(date: string): Promise<Snapshot> {
  const key = date
  const hit = cache.get(key)
  if (hit) return hit
  // Like the backend: every customer account incl. closed ones, in ASCII order of the lowercase id.
  const customer = [...db.accounts.values()].sort((a, b) =>
    a.id.toLowerCase() < b.id.toLowerCase() ? -1 : 1,
  )
  const index = new Map<string, { i: number; balance: string; salt: string }>()
  const leaves: HashedNode[] = []
  for (const [i, a] of customer.entries()) {
    const salt = (await sha256Hex(`snapshot-secret|${a.id}|${date}`)).slice(0, 32)
    const balance = new Decimal(a.balance).toFixed(4)
    index.set(a.id, { i, balance, salt })
    leaves.push(await leafNode(a.id, balance, salt))
  }
  // Pad to a power of two with zero-sum leaves.
  let size = 1
  while (size < leaves.length) size *= 2
  for (let i = leaves.length; i < size; i++) leaves.push(await leafNode(`pad-${i}`, '0', 'pad'))
  const levels: HashedNode[][] = [leaves]
  while (levels.at(-1)!.length > 1) {
    const prev = levels.at(-1)!
    const next: HashedNode[] = []
    for (let i = 0; i < prev.length; i += 2) next.push(await parentNode(prev[i]!, prev[i + 1]!))
    levels.push(next)
  }
  const top = levels.at(-1)![0]!
  const snap: Snapshot = {
    date,
    levels,
    index,
    root: {
      date,
      root: top.hash,
      totalLiabilities: top.sum,
      leafCount: customer.length,
      chainHead: {
        position: 48210 + db.transactions.length,
        hash: await sha256Hex(`chain|${db.transactions.length}`),
      },
    },
  }
  cache.set(key, snap)
  return snap
}

export const proofHandlers = [
  http.get(`${BASE}/public/proofs/roots`, async () => {
    await delay(200)
    const today = todayIst()
    const out: ProofRootDto[] = []
    for (let d = 0; d < DAYS_KEPT; d++) {
      const root = (await build(addDays(today, -d))).root
      // Today's root isn't committed yet (the API anchors every 10 minutes; F-POB-6).
      const anchor =
        d === 0
          ? null
          : {
              repo: 'https://github.com/quantgen-demo/proof-roots',
              commit: (await sha256Hex(`anchor|${root.date}`)).slice(0, 40),
              committedAt: `${root.date}T19:10:00Z`,
            }
      out.push({ ...root, anchor })
    }
    return json(out)
  }),
  http.get(`${BASE}/proofs/accounts/:accountId`, async ({ request, params }) => {
    await delay(300)
    const r = requireUser(request)
    if (!r.user) return r.res
    const acc = db.accounts.get(String(params.accountId))
    if (!acc || acc.userId !== r.user.id) return notFound(request, 'Account')
    const today = todayIst()
    const date = new URL(request.url).searchParams.get('date') ?? today
    const oldest = addDays(today, -(DAYS_KEPT - 1))
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || date > today || date < oldest)
      return notFound(request, 'Snapshot')
    const snap = await build(date)
    const entry = snap.index.get(acc.id)
    if (!entry) return notFound(request, 'Account')
    const path: BalanceProofDto['path'] = []
    let i = entry.i
    for (let level = 0; level < snap.levels.length - 1; level++) {
      const isRight = i % 2 === 1
      const sib = snap.levels[level]![isRight ? i - 1 : i + 1]!
      path.push({ hash: sib.hash, sum: sib.sum, side: isRight ? 'LEFT' : 'RIGHT' })
      i = Math.floor(i / 2)
    }
    return json({
      date: snap.date,
      leaf: { accountId: acc.id, balance: entry.balance, salt: entry.salt },
      path,
      root: snap.root.root,
      totalLiabilities: snap.root.totalLiabilities,
    })
  }),
  http.get(`${BASE}/admin/ledger/verify-chain`, async ({ request }) => {
    await delay(900)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const from = new URL(request.url).searchParams.get('from') ?? 'GENESIS'
    if (from !== 'GENESIS' && from !== 'CHECKPOINT')
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'from', message: 'must be GENESIS or CHECKPOINT' },
      ])
    const head = 48210 + db.transactions.length
    return json({
      intact: true,
      checkedUpTo: head,
      firstBrokenPosition: null,
      fromPosition: from === 'CHECKPOINT' ? head - 37 : 1,
    })
  }),
  http.get(`${BASE}/admin/ledger/solvency`, async ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const snap = await build(todayIst())
    const liabilities = new Decimal(snap.root.totalLiabilities)
    const assets = liabilities.times('1.0812').toDecimalPlaces(4)
    return json({
      totalLiabilities: liabilities.toFixed(4),
      totalAssets: assets.toFixed(4),
      ratio: assets.div(liabilities).toDecimalPlaces(4).toFixed(4),
    })
  }),
]
