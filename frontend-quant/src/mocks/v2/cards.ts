/** MSW handlers: virtual cards + merchant sandbox (§12.11) and personal access tokens (§12.12). */
import { delay, http } from 'msw'
import type { CardControls, CardDisputeDto, MerchantCategory } from '@/api/types'
import { audit, BASE, db, error, idempotent, inr, isMoney, notify, paginate } from '../handlers'
import {
  available,
  Decimal,
  invalid,
  json,
  money,
  move,
  notFound,
  ownAccount,
  requireAdmin,
  requireUser,
  stepUpGate,
} from './common'

const CATS: MerchantCategory[] = [
  'GROCERY',
  'DINING',
  'TRAVEL',
  'ELECTRONICS',
  'GAMBLING',
  'FUEL',
  'ENTERTAINMENT',
  'OTHER',
]
const luhn = (digits: string) => {
  let sum = 0
  for (let i = 0; i < digits.length; i++) {
    let d = Number(digits[digits.length - 1 - i])
    if (i % 2 === 1) {
      d *= 2
      if (d > 9) d -= 9
    }
    sum += d
  }
  return sum % 10 === 0
}
function newPan() {
  for (;;) {
    const body = `9100${String(Math.floor(Math.random() * 1e11)).padStart(11, '0')}`
    for (let c = 0; c <= 9; c++) if (luhn(body + c)) return body + c
  }
}
/** Time-based demo CVV (exported for tests). */
/** Checks card details and controls; on approval places a hold (the auth). */
async function authorize(
  userId: string,
  b: Record<string, unknown>,
  request: Request,
): Promise<Response> {
  const pan = typeof b.cardNumber === 'string' ? b.cardNumber.replace(/\s/g, '') : ''
  const card = db.v2.cards.find((c) => c.pan.replace(/\s/g, '') === pan)
  const decline = (reason: string) => {
    if (card)
      notify(
        card.userId,
        'CARD_DECLINED',
        'Card payment declined',
        `${inr(String(b.amount ?? '0'))} at ${String(b.merchant ?? 'merchant')}: ${reason}.`,
      )
    return json({ authId: crypto.randomUUID(), status: 'DECLINED', reason })
  }
  if (
    !card ||
    b.expiry !== card.expiry ||
    (b.cvv !== cvvFor(card.pan) && b.cvv !== cvvFor(card.pan, Date.now() - 30_000))
  )
    return decline("Card details don't match")
  if (!isMoney(b.amount) || new Decimal(b.amount).lt(1))
    return invalid(request, 'amount', 'must be at least 1.00')
  const amount = new Decimal(b.amount)
  if (card.controls.frozen) return decline('Card is frozen')
  if (!card.controls.online) return decline('Online payments are off')
  if (card.controls.blockedCategories.includes(b.category as MerchantCategory))
    return decline('Merchant category is blocked')
  if (amount.gt(card.controls.perTxnLimit)) return decline('Over the per-transaction limit')
  const acc = db.accounts.get(card.accountId)!
  if (acc.status === 'FROZEN') return decline('Account is frozen')
  if (available(acc).lt(amount)) return decline('Insufficient funds')
  const holdId = crypto.randomUUID()
  db.v2.holds.push({
    id: holdId,
    accountId: acc.id,
    amount: money(amount),
    reason: 'CARD_AUTH',
    status: 'ACTIVE',
  })
  const authId = crypto.randomUUID()
  db.v2.cardAuths.set(authId, {
    cardId: card.id,
    userId,
    amount: money(amount),
    holdId,
    merchant: String(b.merchant ?? 'Merchant').slice(0, 40),
    status: 'AUTHORIZED',
    category: b.category as MerchantCategory,
    refunded: '0',
  })
  return json({ authId, status: 'APPROVED' })
}

export const cvvFor = (pan: string, at: number = Date.now()) => {
  const window = Math.floor(at / 30_000)
  let h = window
  for (const ch of pan) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return String(h % 1000).padStart(3, '0')
}
const pub = ({ userId: _u, pan: _p, ...c }: (typeof db.v2.cards)[number]) => c

const openDisputed = (authId: string) =>
  db.v2.cardDisputes
    .filter((d) => d.authId === authId && d.status === 'OPEN')
    .reduce((s, d) => s.plus(d.amount), new Decimal(0))

/** F-CARD-4 view of a captured auth, as the backend's CardPaymentDto. */
function paymentDto(authId: string, a: NonNullable<ReturnType<typeof db.v2.cardAuths.get>>) {
  const refunded = new Decimal(a.status === 'REFUNDED' ? a.amount : (a.refunded ?? '0'))
  const open = db.v2.cardDisputes.find((d) => d.authId === authId && d.status === 'OPEN')
  const left = new Decimal(a.amount).minus(refunded).minus(openDisputed(authId))
  return {
    authId,
    merchant: a.merchant,
    category: a.category ?? 'OTHER',
    amount: money(a.amount),
    capturedAmount: money(a.amount),
    refundedAmount: money(refunded),
    disputableAmount: money(left.lt(0) ? new Decimal(0) : left),
    status: a.status === 'REFUNDED' ? 'REFUNDED' : 'CAPTURED',
    capturedAt: a.capturedAt ?? new Date().toISOString(),
    openDisputeId: open?.id ?? null,
  }
}
const disputePub = ({ userId: _u, ...d }: (typeof db.v2.cardDisputes)[number]) => d
const DISPUTE_REASONS = ['NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'DUPLICATE', 'NOT_RECOGNISED', 'OTHER']

export const disputeHandlers = [
  http.get(`${BASE}/cards/:id/payments`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const card = db.v2.cards.find((c) => c.id === params.id && c.userId === r.user.id)
    if (!card) return notFound(request, 'Card')
    const rows = [...db.v2.cardAuths.entries()]
      .filter(([, a]) => a.cardId === card.id && a.status !== 'AUTHORIZED')
      .map(([id, a]) => paymentDto(id, a))
      .sort((x, y) => y.capturedAt.localeCompare(x.capturedAt))
    return json(paginate(rows, new URL(request.url)))
  }),
  http.post(`${BASE}/cards/:id/payments/:authId/dispute`, async ({ request, params }) => {
    await delay(400)
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.cards.enabled)
      return error(503, 'FEATURE_DISABLED', 'Cards are switched off', request)
    // Cardholder only: the card must be the caller's, and the auth on that card (else 404).
    const card = db.v2.cards.find((c) => c.id === params.id && c.userId === r.user.id)
    const a = db.v2.cardAuths.get(String(params.authId))
    if (!card || !a || a.cardId !== card.id || a.status === 'AUTHORIZED')
      return notFound(request, 'Payment')
    const b = (await request.json()) as { reason?: unknown; amount?: unknown; note?: unknown }
    if (!DISPUTE_REASONS.includes(String(b.reason)))
      return invalid(request, 'reason', 'pick a reason')
    const p = paymentDto(String(params.authId), a)
    if (p.openDisputeId)
      return error(409, 'INVALID_STATE', 'This payment already has an open dispute', request)
    if (Date.now() - Date.parse(p.capturedAt) > 60 * 86_400_000)
      return error(409, 'INVALID_STATE', 'Disputes must be raised within 60 days', request)
    const amount =
      b.amount === undefined
        ? new Decimal(p.disputableAmount)
        : isMoney(b.amount)
          ? new Decimal(b.amount)
          : null
    if (!amount || amount.lte(0) || amount.gt(p.disputableAmount))
      return invalid(request, 'amount', `must be between 0.01 and ${p.disputableAmount}`)
    const note = typeof b.note === 'string' ? b.note.trim() : ''
    if (note.length > 280) return invalid(request, 'note', 'must be at most 280 characters')
    if (
      db.v2.cardDisputes.filter((d) => d.userId === r.user.id && d.status === 'OPEN').length >= 20
    )
      return error(422, 'LIMIT_EXCEEDED', 'Too many open disputes', request)
    const d = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      cardId: card.id,
      authId: String(params.authId),
      last4: card.last4,
      merchant: a.merchant,
      amount: money(amount),
      reason: b.reason as CardDisputeDto['reason'],
      note: note || null,
      status: 'OPEN' as const,
      resolutionReason: null,
      createdAt: new Date().toISOString(),
      resolvedAt: null,
    }
    db.v2.cardDisputes.unshift(d)
    audit(
      r.user,
      'CARD_DISPUTE_OPENED',
      'CARD_DISPUTE',
      d.id,
      `card ${card.last4} ${inr(d.amount)} ${d.reason}`,
    )
    return json(disputePub(d), 201)
  }),
  http.get(`${BASE}/card-disputes`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.cardDisputes.filter((d) => d.userId === r.user.id).map(disputePub))
  }),
  http.get(`${BASE}/admin/card-disputes`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const status = new URL(request.url).searchParams.get('status')
    // Oldest first, like the backend's queue.
    const rows = db.v2.cardDisputes
      .filter((d) => !status || d.status === status)
      .slice()
      .sort((x, y) => x.createdAt.localeCompare(y.createdAt))
      .map(({ userId, ...d }) => ({ ...d, customerId: userId }))
    return json(paginate(rows, new URL(request.url)))
  }),
  http.post(`${BASE}/admin/card-disputes/:id/resolve`, async ({ request, params }) => {
    await delay(400)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { outcome?: unknown; reason?: unknown }
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    const d = db.v2.cardDisputes.find((x) => x.id === params.id)
    if (!d) return notFound(request, 'Dispute')
    if (b.outcome !== 'REFUNDED' && b.outcome !== 'REJECTED')
      return invalid(request, 'outcome', 'must be REFUNDED or REJECTED')
    const reason = typeof b.reason === 'string' ? b.reason.trim() : ''
    if (reason.length < 1 || reason.length > 280)
      return invalid(request, 'reason', 'must be 1–280 characters')
    if (d.status !== 'OPEN')
      return error(409, 'INVALID_STATE', 'This dispute is already resolved', request)
    if (b.outcome === 'REFUNDED') {
      const a = db.v2.cardAuths.get(d.authId)!
      const card = db.v2.cards.find((c) => c.id === d.cardId)!
      const acc = db.accounts.get(card.accountId)!
      if (acc.status === 'CLOSED')
        return error(409, 'INVALID_STATE', 'The account is closed', request)
      acc.balance = new Decimal(acc.balance).plus(d.amount).toFixed(4)
      db.transactions.unshift({
        id: crypto.randomUUID(),
        journalEntryId: crypto.randomUUID(),
        accountId: acc.id,
        direction: 'CREDIT',
        amount: d.amount,
        balanceAfter: acc.balance,
        type: 'REFUND',
        description: `Dispute refund ${d.merchant}`,
        counterparty: d.merchant,
        createdAt: new Date().toISOString(),
      })
      a.refunded = money(new Decimal(a.refunded ?? '0').plus(d.amount))
      if (new Decimal(a.refunded).gte(a.amount)) a.status = 'REFUNDED'
    }
    d.status = b.outcome
    d.resolutionReason = reason
    d.resolvedAt = new Date().toISOString()
    audit(
      r.user,
      'CARD_DISPUTE_RESOLVED',
      'CARD_DISPUTE',
      d.id,
      `card ${d.last4} ${inr(d.amount)} ${b.outcome}: ${reason}`,
    )
    notify(
      d.userId,
      'CARD_DISPUTE_RESOLVED',
      b.outcome === 'REFUNDED' ? 'Dispute refunded' : 'Dispute not upheld',
      `${d.merchant}: ${inr(d.amount)}${b.outcome === 'REFUNDED' ? ' is back in your account' : ''}.`,
    )
    const body = disputePub(d)
    idem.save(200, body)
    return json(body)
  }),
]

export const cardHandlers = [
  http.get(`${BASE}/cards`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.cards.filter((c) => c.userId === r.user.id).map(pub))
  }),
  http.post(`${BASE}/accounts/:id/cards`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.cards.enabled)
      return error(503, 'FEATURE_DISABLED', 'Cards are switched off', request)
    const acc = ownAccount(r.user.id, params.id)
    if (!acc || (acc.type !== 'SAVINGS' && acc.type !== 'CHECKING'))
      return notFound(request, 'Account')
    if (db.v2.cards.some((c) => c.accountId === acc.id))
      return error(409, 'INVALID_STATE', 'This account already has a card', request)
    const pan = newPan()
    const exp = new Date()
    const card = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      accountId: acc.id,
      last4: pan.slice(-4),
      expiry: `${String(exp.getUTCMonth() + 1).padStart(2, '0')}/${String((exp.getUTCFullYear() + 4) % 100).padStart(2, '0')}`,
      status: 'ACTIVE' as const,
      pan: pan.replace(/(\d{4})(?=\d)/g, '$1 '),
      controls: {
        frozen: false,
        online: true,
        perTxnLimit: '25000.00',
        blockedCategories: ['GAMBLING'] as MerchantCategory[],
      },
    }
    db.v2.cards.push(card)
    audit(r.user, 'CARD_CREATED', 'CARD', card.id, `•••• ${card.last4}`)
    return json(pub(card), 201)
  }),
  http.post(`${BASE}/cards/:id/reveal`, async ({ request, params }) => {
    await delay(300)
    const r = requireUser(request)
    if (!r.user) return r.res
    const c = db.v2.cards.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!c) return notFound(request, 'Card')
    const gate = stepUpGate(request, r.user, 'CARD_REVEAL')
    if (gate) return gate
    audit(r.user, 'CARD_REVEALED', 'CARD', c.id, `•••• ${c.last4}`)
    return json({ pan: c.pan, cvv: cvvFor(c.pan), expiry: c.expiry, hideAfterSeconds: 30 })
  }),
  http.patch(`${BASE}/cards/:id/controls`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const c = db.v2.cards.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!c) return notFound(request, 'Card')
    const b = (await request.json()) as Partial<CardControls>
    if (b.frozen !== undefined && typeof b.frozen !== 'boolean')
      return invalid(request, 'frozen', 'must be true or false')
    if (b.online !== undefined && typeof b.online !== 'boolean')
      return invalid(request, 'online', 'must be true or false')
    if (
      b.perTxnLimit !== undefined &&
      (!isMoney(b.perTxnLimit) ||
        new Decimal(b.perTxnLimit).lt(1) ||
        new Decimal(b.perTxnLimit).gt(200000))
    )
      return invalid(request, 'perTxnLimit', 'must be ₹1–₹2,00,000')
    if (
      b.blockedCategories !== undefined &&
      (!Array.isArray(b.blockedCategories) || b.blockedCategories.some((x) => !CATS.includes(x)))
    )
      return invalid(request, 'blockedCategories', 'unknown category')
    c.controls = {
      ...c.controls,
      ...b,
      ...(b.perTxnLimit ? { perTxnLimit: new Decimal(b.perTxnLimit).toFixed(2) } : {}),
    }
    c.status = c.controls.frozen ? 'FROZEN' : 'ACTIVE'
    return json(pub(c))
  }),

  // Merchant sandbox
  http.post(`${BASE}/sandbox/merchant/authorize`, async ({ request }) => {
    await delay(700)
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.cards.enabled)
      return error(503, 'FEATURE_DISABLED', 'Cards are switched off', request)
    const b = (await request.json()) as Record<string, unknown>
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    const res = await authorize(r.user.id, b, request)
    idem.save(res.status, await res.clone().json())
    return res
  }),
  http.post(`${BASE}/sandbox/merchant/:authId/:action`, async ({ request, params }) => {
    await delay(400)
    const r = requireUser(request)
    if (!r.user) return r.res
    const idem = idempotent(request, r.user.id, { authId: params.authId, action: params.action })
    if (idem.early) return idem.early
    // Like the backend: only the user who ran the authorize can capture or refund it.
    const a = db.v2.cardAuths.get(String(params.authId))
    if (!a || a.userId !== r.user.id) return notFound(request, 'Authorization')
    const card = db.v2.cards.find((c) => c.id === a.cardId)!
    const acc = db.accounts.get(card.accountId)!
    const hold = db.v2.holds.find((h) => h.id === a.holdId)
    if (params.action === 'capture') {
      if (a.status !== 'AUTHORIZED') return error(409, 'INVALID_STATE', 'Not capturable', request)
      if (hold) hold.status = 'CAPTURED'
      move(acc, null, new Decimal(a.amount), 'CARD', a.merchant, '', a.merchant)
      a.status = 'CAPTURED'
      a.capturedAt = new Date().toISOString()
    } else if (params.action === 'refund') {
      if (a.status !== 'CAPTURED')
        return error(409, 'INVALID_STATE', 'Only captured payments can be refunded', request)
      // Like the backend: money under an open dispute can't also be refunded by the merchant.
      if (openDisputed(params.authId as string).gt(0))
        return error(409, 'INVALID_STATE', 'Part of this payment is under dispute', request)
      acc.balance = new Decimal(acc.balance).plus(a.amount).toFixed(4)
      db.transactions.unshift({
        id: crypto.randomUUID(),
        journalEntryId: crypto.randomUUID(),
        accountId: acc.id,
        direction: 'CREDIT',
        amount: a.amount,
        balanceAfter: acc.balance,
        type: 'REFUND',
        description: `Refund · ${a.merchant}`,
        counterparty: a.merchant,
        createdAt: new Date().toISOString(),
      })
      a.status = 'REFUNDED'
    } else return notFound(request)
    const body = { authId: params.authId, status: a.status }
    idem.save(200, body)
    return json(body)
  }),

  // Personal access tokens
  http.get(`${BASE}/users/me/tokens`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(
      db.v2.tokens
        .filter((t) => t.userId === r.user.id)
        .map(({ userId: _u, tokenHash: _h, ...t }) => t),
    )
  }),
  http.post(`${BASE}/users/me/tokens`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { name?: unknown; scope?: unknown; expiresInDays?: unknown }
    const name = typeof b.name === 'string' ? b.name.trim() : ''
    if (!name || name.length > 40) return invalid(request, 'name', 'must be 1–40 characters')
    if (b.scope !== 'read' && b.scope !== 'read+propose')
      return invalid(request, 'scope', 'must be read or read+propose')
    const days = Number(b.expiresInDays)
    if (!Number.isInteger(days) || days < 1 || days > 90)
      return invalid(request, 'expiresInDays', 'must be 1–90')
    const gate = stepUpGate(request, r.user, 'TOKEN_CREATE')
    if (gate) return gate
    const token = `qg_pat_${crypto.randomUUID().replace(/-/g, '')}`
    const t = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      name,
      scope: b.scope as 'read' | 'read+propose',
      lastUsedAt: null,
      expiresAt: new Date(Date.now() + days * 86_400_000).toISOString(),
      tokenHash: 'hash',
    }
    db.v2.tokens.unshift(t)
    audit(r.user, 'TOKEN_CREATED', 'TOKEN', t.id, `${name} (${b.scope})`)
    const { userId: _u, tokenHash: _h, ...out } = t
    return json({ ...out, token }, 201)
  }),
  http.delete(`${BASE}/users/me/tokens/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.tokens.findIndex((t) => t.id === params.id && t.userId === r.user.id)
    if (i < 0) return notFound(request, 'Token')
    db.v2.tokens.splice(i, 1)
    audit(r.user, 'TOKEN_REVOKED', 'TOKEN', String(params.id), null)
    return new Response(null, { status: 204 })
  }),
]
