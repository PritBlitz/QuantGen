/**
 * Proof of Balance verification (PRD F-POB-3, ARCHITECTURE §12.10). Runs entirely in the browser
 * with WebCrypto, so the customer doesn't have to trust the server's maths.
 *
 * Canonical encoding (PRD §12.10, shared with the backend):
 *   leaf.hash = hex(SHA-256(UTF-8("<accountId>|<balance>|<salt>")))
 *   node.hash = hex(SHA-256(UTF-8("<left.hash>|<left.sum>|<right.hash>|<right.sum>")))
 *   node.sum  = left.sum + right.sum
 * where every amount is a plain decimal string with exactly 4 fraction digits ("1250.5000"),
 * hashes are lowercase hex, and `side` on a path node says which side the *sibling* is on.
 */
import type { BalanceProofDto, MoneyString } from '@/api/types'
import { Decimal, toDecimal } from './money'

const fmt4 = (v: MoneyString | Decimal) => toDecimal(v).toDecimalPlaces(4).toFixed(4)

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export interface HashedNode {
  hash: string
  sum: string
}

export async function leafNode(
  accountId: string,
  balance: MoneyString,
  salt: string,
): Promise<HashedNode> {
  const sum = fmt4(balance)
  return { hash: await sha256Hex(`${accountId}|${sum}|${salt}`), sum }
}

export async function parentNode(left: HashedNode, right: HashedNode): Promise<HashedNode> {
  const sum = toDecimal(left.sum).plus(right.sum).toFixed(4)
  return {
    hash: await sha256Hex(`${left.hash}|${fmt4(left.sum)}|${right.hash}|${fmt4(right.sum)}`),
    sum,
  }
}

export interface VerifyStep {
  level: number
  node: HashedNode
}

export interface VerifyResult {
  ok: boolean
  /** Why it failed, in plain language (null when ok). */
  reason: string | null
  computedRoot: string
  computedTotal: string
  steps: VerifyStep[]
}

/**
 * Recompute the path from the leaf to the root. Passes only if the root hash matches *and* the
 * summed total equals the published total, and no sibling sum is negative (a negative sum would
 * let a dishonest bank hide liabilities).
 */
export async function verifyBalanceProof(
  proof: BalanceProofDto,
  published: { root: string; totalLiabilities: MoneyString },
): Promise<VerifyResult> {
  let node = await leafNode(proof.leaf.accountId, proof.leaf.balance, proof.leaf.salt)
  const steps: VerifyStep[] = [{ level: 0, node }]
  for (const [i, sib] of proof.path.entries()) {
    if (toDecimal(sib.sum).isNegative()) {
      return {
        ok: false,
        reason: 'A node in the proof has a negative sum, which isn’t allowed.',
        computedRoot: node.hash,
        computedTotal: node.sum,
        steps,
      }
    }
    const sibling = { hash: sib.hash, sum: fmt4(sib.sum) }
    node = sib.side === 'LEFT' ? await parentNode(sibling, node) : await parentNode(node, sibling)
    steps.push({ level: i + 1, node })
  }
  const rootOk =
    node.hash === published.root.toLowerCase() && node.hash === proof.root.toLowerCase()
  const totalOk = toDecimal(node.sum).eq(published.totalLiabilities)
  return {
    ok: rootOk && totalOk,
    reason: !rootOk
      ? 'The recomputed root doesn’t match the published root.'
      : !totalOk
        ? 'The recomputed total doesn’t match the published total.'
        : null,
    computedRoot: node.hash,
    computedTotal: node.sum,
    steps,
  }
}
