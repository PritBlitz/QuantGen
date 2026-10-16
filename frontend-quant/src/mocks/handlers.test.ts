import { describe, expect, it } from 'vitest'
import { api } from '@/api/client'
import { ApiError } from '@/api/errors'
import { useAuthStore } from '@/features/auth/store'
import { db } from './handlers'
import { signInAs } from '@/test/utils'
import { DEMO_USER_ID } from './db'

// The M2 screens are built against these mocks, so they must follow the backend's
// documented money rules (backend session, M2 notes).
const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'

function signIn() {
  useAuthStore.setState({
    accessToken: `mock.${DEMO_USER_ID}.00000000-0000-4000-8000-000000000000`,
    status: 'authenticated',
    expiresAt: Date.now() + 900_000,
    user: null,
  })
}

function must<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new Error(`mock seed missing: ${what}`)
  return value
}
const savings = () => must(db.accounts.get(SAVINGS), 'savings account')

const transfer = (body: object, key: string) =>
  api.post('/transfers', body, { headers: { 'Idempotency-Key': key } })

function addOtherUserAccount(accountNumber: string) {
  const userId = '11111111-2222-4333-8444-555555555555'
  db.users.set('meera@example.com', {
    password: 'x',
    user: {
      id: userId,
      fullName: 'Meera Iyer',
      email: 'meera@example.com',
      phone: '+919800000002',
      role: 'CUSTOMER',
      createdAt: '2026-01-01T00:00:00Z',
    },
  })
  db.accounts.set('acc-meera', {
    id: 'acc-meera',
    userId,
    accountNumber,
    type: 'SAVINGS',
    status: 'ACTIVE',
    balance: '0.00',
    currency: 'INR',
    createdAt: '2026-01-01T00:00:00Z',
  })
}

describe('mock M2 rules', () => {
  it('replays the same key + body with Idempotent-Replayed, 409s on a different body', async () => {
    signIn()
    const key = crypto.randomUUID()
    const body = { fromAccountId: SAVINGS, toAccountNumber: '502184739067', amount: '10.00' }
    const first = await transfer(body, key)
    const again = await transfer(body, key)
    expect(again.data).toEqual(first.data)
    expect(again.headers['idempotent-replayed']).toBe('true')
    // Balance moved once only.
    expect(db.accounts.get(SAVINGS)?.balance).toBe('184240.7500')

    const err = await transfer({ ...body, amount: '11.00' }, key).catch((e: unknown) => e)
    expect(err).toMatchObject({ code: 'IDEMPOTENCY_CONFLICT', status: 409 })
  })

  it('does not consume the key on a failed request', async () => {
    signIn()
    const key = crypto.randomUUID()
    const body = { fromAccountId: SAVINGS, toAccountNumber: '502184739067', amount: '200000.00' }
    savings().balance = '5.00'
    await expect(transfer(body, key)).rejects.toMatchObject({ code: 'INSUFFICIENT_FUNDS' })
    savings().balance = '300000.00'
    const ok = await transfer(body, key)
    expect(ok.status).toBe(201)
  })

  it('enforces the ₹5,00,000 daily limit to others only, with the remaining amount', async () => {
    signIn()
    addOtherUserAccount('777788889999')
    savings().balance = '900000.00'
    const send = (amount: string, to = '777788889999') =>
      transfer({ fromAccountId: SAVINGS, toAccountNumber: to, amount }, crypto.randomUUID())
    await send('200000.00')
    await send('200000.00')
    // Own-account moves don't count toward the limit.
    await send('150000.00', '502184739067')
    const err = await send('100000.01').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({
      code: 'LIMIT_EXCEEDED',
      message: expect.stringContaining('100000.00'),
    })
    await expect(send('100000.00')).resolves.toMatchObject({ status: 201 })
  })

  it('rejects amounts with more than 2 decimals', async () => {
    signIn()
    const err = await transfer(
      { fromAccountId: SAVINGS, toAccountNumber: '502184739067', amount: '1.001' },
      crypto.randomUUID(),
    ).catch((e: unknown) => e)
    expect(err).toMatchObject({
      code: 'VALIDATION_FAILED',
      fieldErrors: [expect.objectContaining({ field: 'amount' })],
    })
  })

  it('filters transactions by inclusive IST dates', async () => {
    signIn()
    const tx = must(
      db.transactions.find((t) => t.accountId === SAVINGS),
      'savings transaction',
    )
    tx.createdAt = '2026-10-15T19:00:00Z' // 16 Oct 00:30 IST
    const day = await api.get(`/accounts/${SAVINGS}/transactions`, {
      params: { from: '2026-10-16', to: '2026-10-16' },
    })
    expect(day.data.content.map((t: { id: string }) => t.id)).toContain(tx.id)
    const before = await api.get(`/accounts/${SAVINGS}/transactions`, {
      params: { to: '2026-10-15' },
    })
    expect(before.data.content.map((t: { id: string }) => t.id)).not.toContain(tx.id)
    await expect(
      api.get(`/accounts/${SAVINGS}/transactions`, { params: { from: '16-10-2026' } }),
    ).rejects.toMatchObject({ code: 'VALIDATION_FAILED' })
  })

  it('validates beneficiaries: unknown and own → 400, duplicate → 409', async () => {
    signIn()
    addOtherUserAccount('777788889999')
    const add = (accountNumber: string) =>
      api.post('/beneficiaries', { nickname: 'Meera', accountNumber })
    await expect(add('000000000000')).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
    })
    await expect(add('502184734821')).rejects.toMatchObject({ status: 400 })
    const ok = await add('777788889999')
    expect(ok.data).toMatchObject({ holderName: 'Meera Iyer', accountNumber: '777788889999' })
    await expect(add('777788889999')).rejects.toMatchObject({
      status: 409,
      code: 'VALIDATION_FAILED',
    })
  })
})

describe('mock M5 rules (PRD §7.7/§7.8)', () => {
  const PRIYA_LOAN = 'c0000000-0000-4000-8000-000000000001'

  it('denies every admin endpoint to customers with 403', async () => {
    signInAs('CUSTOMER')
    for (const call of [
      () => api.get('/admin/stats'),
      () => api.get('/admin/users'),
      () => api.get('/admin/loans'),
      () => api.get('/admin/audit-logs'),
      () => api.get('/admin/ledger/reconcile'),
      () => api.post(`/admin/loans/${PRIYA_LOAN}/approve`, {}),
      () => api.patch(`/admin/accounts/${SAVINGS}/status`, { status: 'FROZEN', reason: 'testing' }),
    ]) {
      await expect(call()).rejects.toMatchObject({ status: 403, code: 'FORBIDDEN' })
    }
    expect(db.loans.find((l) => l.id === PRIYA_LOAN)?.status).toBe('PENDING')
  })

  it('approves a pending loan exactly once; a second decision is 409 INVALID_STATE', async () => {
    signInAs('ADMIN')
    const ok = await api.post(`/admin/loans/${PRIYA_LOAN}/approve`, {})
    expect(ok.data).toMatchObject({ status: 'ACTIVE', outstandingPrincipal: '300000.00' })
    await expect(api.post(`/admin/loans/${PRIYA_LOAN}/approve`, {})).rejects.toMatchObject({
      status: 409,
      code: 'INVALID_STATE',
    })
    await expect(
      api.post(`/admin/loans/${PRIYA_LOAN}/reject`, { reason: 'too late now' }),
    ).rejects.toMatchObject({ status: 409 })
  })

  it('rejects a freeze without a reason, and a no-op status change', async () => {
    signInAs('ADMIN')
    await expect(
      api.patch(`/admin/accounts/${SAVINGS}/status`, { status: 'FROZEN', reason: ' ' }),
    ).rejects.toMatchObject({ status: 400, fieldErrors: [{ field: 'reason' }] })
    await expect(
      api.patch(`/admin/accounts/${SAVINGS}/status`, { status: 'ACTIVE', reason: 'already on' }),
    ).rejects.toMatchObject({ status: 409, code: 'INVALID_STATE' })
  })

  it('returns 404, not 403, for another user’s notification', async () => {
    signInAs('ADMIN')
    const demoNotification = db.notifications.find((n) => n.userId === DEMO_USER_ID)!
    await expect(api.patch(`/notifications/${demoNotification.id}/read`)).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
    })
    expect(demoNotification.read).toBe(false)
  })

  it('notifies the recipient of a transfer (MONEY_RECEIVED), not the sender', async () => {
    signIn()
    addOtherUserAccount('777788889999')
    await transfer(
      { fromAccountId: SAVINGS, toAccountNumber: '777788889999', amount: '500.00' },
      crypto.randomUUID(),
    )
    expect(db.notifications[0]).toMatchObject({
      userId: '11111111-2222-4333-8444-555555555555',
      type: 'MONEY_RECEIVED',
      body: '₹500.00 from Aarav Sharma into ••••9999.',
    })
  })
})
