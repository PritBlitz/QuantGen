import { describe, expect, it } from 'vitest'
import { proofApi } from '@/api/endpoints.v2'
import { useAuthStore } from '@/features/auth/store'
import { DEMO_USER_ID } from '@/mocks/db'
import { verifyBalanceProof } from './merkle'

const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'

function signIn() {
  useAuthStore.setState({
    accessToken: `mock.${DEMO_USER_ID}.00000000-0000-4000-8000-000000000000`,
    status: 'authenticated',
    expiresAt: Date.now() + 900_000,
    user: null,
  })
}

describe('Proof of Balance verification (F-POB-3)', () => {
  it('accepts a genuine inclusion proof against the published root', async () => {
    signIn()
    const [root] = await proofApi.roots()
    const proof = await proofApi.proof(SAVINGS)
    const res = await verifyBalanceProof(proof, root!)
    expect(res.ok).toBe(true)
    expect(res.computedRoot).toBe(root!.root)
    expect(res.steps.length).toBe(proof.path.length + 1)
  })

  it('rejects a proof whose balance was altered', async () => {
    signIn()
    const [root] = await proofApi.roots()
    const proof = await proofApi.proof(SAVINGS)
    const forged = { ...proof, leaf: { ...proof.leaf, balance: '999999999.0000' } }
    const res = await verifyBalanceProof(forged, root!)
    expect(res.ok).toBe(false)
    expect(res.reason).toMatch(/root/)
  })

  it('rejects a path that smuggles in a negative sum', async () => {
    signIn()
    const [root] = await proofApi.roots()
    const proof = await proofApi.proof(SAVINGS)
    const path = proof.path.map((n, i) => (i === 0 ? { ...n, sum: '-100.0000' } : n))
    const res = await verifyBalanceProof({ ...proof, path }, root!)
    expect(res.ok).toBe(false)
    expect(res.reason).toMatch(/negative/)
  })

  it('rejects when the published total differs', async () => {
    signIn()
    const [root] = await proofApi.roots()
    const proof = await proofApi.proof(SAVINGS)
    const res = await verifyBalanceProof(proof, { root: root!.root, totalLiabilities: '1.0000' })
    expect(res.ok).toBe(false)
    expect(res.reason).toMatch(/total/)
  })
})
