/**
 * MSW handlers: Proof of Balance (§12.10). Builds a *real* Merkle sum tree over every customer
 * account with the same canonical encoding the browser verifies (lib/merkle.ts), so the
 * in-browser check genuinely passes or fails.
 */
import { delay, http } from 'msw'
import type { BalanceProofDto, ProofRootDto } from '@/api/types'
import { leafNode, parentNode, sha256Hex, type HashedNode } from '@/lib/merkle'
import { BASE, db } from '../handlers'
import { addDays, Decimal, json, notFound, requireAdmin, requireUser, todayIst } from './common'

interface Snapshot {
  date: string
  levels: HashedNode[][]
  index: Map<string, { i: number; balance: string; salt: string }>
  root: ProofRootDto
}

const cache = new Map<string, Snapshot>()

async function build(date: string): Promise<Snapshot> {
  const key = `${date}:${[...db.accounts.values()].map((a) => a.balance).join(',')}`
  const hit = cache.get(key)
  if (hit) return hit
  const customer = [...db.accounts.values()].filter((a) => a.status !== 'CLOSED').sort((a, b) => a.id.localeCompare(b.id))
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
    root: { date, root: top.hash, totalLiabilities: top.sum, leafCount: customer.length, chainHead: { position: 48210 + db.transactions.length, hash: (await sha256Hex(`chain|${db.transactions.length}`)) } },
  }
  cache.set(key, snap)
  return snap
}

export const proofHandlers = [
  http.get(`${BASE}/public/proofs/roots`, async () => {
    await delay(200)
    const today = todayIst()
    const out: ProofRootDto[] = [(await build(today)).root]
    // Earlier days: deterministic placeholder roots (the mock only keeps today's tree).
    for (let d = 1; d < 7; d++) {
      const date = addDays(today, -d)
      out.push({ date, root: await sha256Hex(`root|${date}`), totalLiabilities: new Decimal(out[0]!.totalLiabilities).minus(d * 3125.5).toFixed(4), leafCount: out[0]!.leafCount, chainHead: { position: out[0]!.chainHead.position - d * 140, hash: await sha256Hex(`chain|${date}`) } })
    }
    return json(out)
  }),
  http.get(`${BASE}/proofs/accounts/:accountId`, async ({ request, params }) => {
    await delay(300)
    const r = requireUser(request)
    if (!r.user) return r.res
    const acc = db.accounts.get(String(params.accountId))
    if (!acc || acc.userId !== r.user.id) return notFound(request, 'Account')
    const snap = await build(todayIst())
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
    return json({ date: snap.date, leaf: { accountId: acc.id, balance: entry.balance, salt: entry.salt }, path, root: snap.root.root, totalLiabilities: snap.root.totalLiabilities })
  }),
  http.get(`${BASE}/admin/ledger/verify-chain`, async ({ request }) => {
    await delay(900)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    return json({ intact: true, checkedUpTo: 48210 + db.transactions.length, firstBrokenPosition: null })
  }),
  http.get(`${BASE}/admin/ledger/solvency`, async ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const snap = await build(todayIst())
    const liabilities = new Decimal(snap.root.totalLiabilities)
    const assets = liabilities.times('1.0812').toDecimalPlaces(4)
    return json({ totalLiabilities: liabilities.toFixed(4), totalAssets: assets.toFixed(4), ratio: assets.div(liabilities).toDecimalPlaces(4).toFixed(4) })
  }),
]
