/** MSW handlers: feature flags (§12.14) and Security 2.0 (§12.3). */
import { delay, http } from 'msw'
import type { FeatureFlag, KycDto, LimitsDto, StepUpAction } from '@/api/types'
import { audit, BASE, db, error, issue, notify } from '../handlers'
import { Decimal, invalid, json, money, notFound, requireAdmin, requireUser, stepUpGate } from './common'

const ACTIONS: StepUpAction[] = [
  'TRANSFER',
  'INTENT_APPROVE',
  'RULE_ACTIVATE',
  'CARD_REVEAL',
  'SECURITY_SETTINGS',
  'FD_CLOSE',
  'TOKEN_CREATE',
]
const PER_TRANSFER_MAX = '200000.0000'
const DAILY_MAX = '500000.0000'
const PAN_RE = /^[A-Z]{5}\d{4}[A-Z]$/

function methodsFor(userId: string) {
  const m: Array<'PASSKEY' | 'PIN'> = []
  if (db.v2.passkeys.some((p) => p.userId === userId)) m.push('PASSKEY')
  if (db.v2.pins.has(userId)) m.push('PIN')
  return m
}

export const securityHandlers = [
  // Feature flags
  http.get(`${BASE}/feature-flags`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const f = db.v2.flags
    return json({ ai: f.ai.enabled, autopilot: f.autopilot.enabled, razorpay: f.razorpay.enabled, cards: f.cards.enabled, mcp: f.mcp.enabled })
  }),
  http.get(`${BASE}/admin/feature-flags`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    return json(Object.values(db.v2.flags))
  }),
  http.put(`${BASE}/admin/feature-flags/:name`, async ({ request, params }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const flag = db.v2.flags[String(params.name) as FeatureFlag]
    if (!flag) return notFound(request, 'Flag')
    const b = (await request.json()) as { enabled?: unknown }
    if (typeof b.enabled !== 'boolean') return invalid(request, 'enabled', 'must be true or false')
    flag.enabled = b.enabled
    flag.updatedAt = new Date().toISOString()
    flag.updatedBy = r.user.email
    audit(r.user, 'FEATURE_FLAG_CHANGED', 'FLAG', flag.name, `${flag.name} → ${b.enabled ? 'on' : 'off'}`)
    return json(flag)
  }),

  // Passkeys
  http.post(`${BASE}/auth/passkeys/register/options`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json({
      challenge: btoa(crypto.randomUUID()).replace(/=/g, ''),
      rp: { name: 'QuantGen', id: location.hostname },
      user: { id: btoa(r.user.id).replace(/=/g, ''), name: r.user.email, displayName: r.user.fullName },
      pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
      authenticatorSelection: { residentKey: 'preferred', userVerification: 'required' },
      timeout: 60000,
    })
  }),
  http.post(`${BASE}/auth/passkeys/register`, async ({ request }) => {
    await delay(300)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { name?: unknown; credential?: { id?: unknown } }
    const name = typeof b.name === 'string' ? b.name.trim() : ''
    if (!name || name.length > 40) return invalid(request, 'name', 'must be 1–40 characters')
    if (!b.credential || typeof b.credential.id !== 'string') return invalid(request, 'credential', 'invalid credential')
    if (db.v2.passkeys.filter((p) => p.userId === r.user.id).length >= 5)
      return error(422, 'LIMIT_EXCEEDED', 'At most 5 passkeys', request)
    const pk = { id: crypto.randomUUID(), userId: r.user.id, name, createdAt: new Date().toISOString(), lastUsedAt: null, credentialId: b.credential.id }
    db.v2.passkeys.push(pk)
    audit(r.user, 'PASSKEY_ADDED', 'USER', r.user.id, name)
    const { userId: _u, credentialId: _c, ...dto } = pk
    return json(dto, 201)
  }),
  http.get(`${BASE}/auth/passkeys`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.passkeys.filter((p) => p.userId === r.user.id).map(({ userId: _u, credentialId: _c, ...p }) => p))
  }),
  http.delete(`${BASE}/auth/passkeys/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.passkeys.findIndex((p) => p.id === params.id && p.userId === r.user.id)
    if (i < 0) return notFound(request, 'Passkey')
    const gate = stepUpGate(request, r.user, 'SECURITY_SETTINGS')
    if (gate) return gate
    db.v2.passkeys.splice(i, 1)
    audit(r.user, 'PASSKEY_REMOVED', 'USER', r.user.id, null)
    return new Response(null, { status: 204 })
  }),
  http.post(`${BASE}/auth/passkeys/login/options`, () =>
    json({ challenge: btoa(crypto.randomUUID()).replace(/=/g, ''), rpId: location.hostname, userVerification: 'required', timeout: 60000 }),
  ),
  http.post(`${BASE}/auth/passkeys/login`, async ({ request }) => {
    await delay(300)
    const b = (await request.json()) as { credential?: { id?: unknown } }
    const pk = db.v2.passkeys.find((p) => p.credentialId === b.credential?.id)
    const user = pk ? [...db.users.values()].find((u) => u.user.id === pk.userId)?.user : undefined
    if (!pk || !user) return error(401, 'UNAUTHORIZED', 'Passkey not recognised', request)
    pk.lastUsedAt = new Date().toISOString()
    return issue(user)
  }),

  // Step-up
  http.post(`${BASE}/auth/step-up/options`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { action?: unknown }
    if (!ACTIONS.includes(b.action as StepUpAction)) return invalid(request, 'action', 'unknown action')
    const methods = methodsFor(r.user.id)
    const challenge = btoa(crypto.randomUUID()).replace(/=/g, '')
    db.v2.stepUpChallenges.set(r.user.id, challenge)
    const creds = db.v2.passkeys.filter((p) => p.userId === r.user.id)
    return json({
      methods,
      ...(creds.length
        ? { passkeyOptions: { challenge, rpId: location.hostname, userVerification: 'required', timeout: 60000, allowCredentials: [] } }
        : {}),
    })
  }),
  http.post(`${BASE}/auth/step-up/verify`, async ({ request }) => {
    await delay(250)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { action?: unknown; pin?: unknown; credential?: unknown }
    const action = b.action as StepUpAction
    if (!ACTIONS.includes(action)) return invalid(request, 'action', 'unknown action')
    if (typeof b.pin === 'string') {
      const rec = db.v2.pins.get(r.user.id)
      if (!rec) return invalid(request, 'pin', 'no PIN is set')
      if (rec.lockedUntil > Date.now()) return error(423, 'PIN_LOCKED', 'PIN locked', request)
      if (rec.pin !== b.pin) {
        rec.fails += 1
        if (rec.fails >= 5) {
          rec.lockedUntil = Date.now() + 30 * 60_000
          rec.fails = 0
          audit(r.user, 'PIN_LOCKED', 'USER', r.user.id, null)
          return error(423, 'PIN_LOCKED', 'PIN locked', request)
        }
        return invalid(request, 'pin', `incorrect PIN, ${5 - rec.fails} attempts left`)
      }
      rec.fails = 0
    } else if (b.credential && typeof b.credential === 'object') {
      // Mock: any assertion for an issued challenge passes (the real server verifies the signature).
      if (!db.v2.stepUpChallenges.has(r.user.id)) return invalid(request, 'credential', 'no challenge issued')
      db.v2.stepUpChallenges.delete(r.user.id)
    } else {
      return invalid(request, 'pin', 'provide a PIN or a passkey credential')
    }
    const token = `su.${crypto.randomUUID()}`
    db.v2.stepUpTokens.set(token, { userId: r.user.id, action, exp: Date.now() + 5 * 60_000 })
    return json({ stepUpToken: token, expiresIn: 300 })
  }),

  // PIN
  http.put(`${BASE}/users/me/pin`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { pin?: unknown; password?: unknown }
    if (typeof b.pin !== 'string' || !/^\d{6}$/.test(b.pin)) return invalid(request, 'pin', 'must be exactly 6 digits')
    if (/^(\d)\1{5}$/.test(b.pin) || '0123456789'.includes(b.pin) || '9876543210'.includes(b.pin))
      return invalid(request, 'pin', 'too easy to guess')
    // Changing an existing PIN needs step-up; setting the first one doesn't (there's nothing to step up with).
    if (methodsFor(r.user.id).length) {
      const gate = stepUpGate(request, r.user, 'SECURITY_SETTINGS')
      if (gate) return gate
    } else {
      // Backend f29bb64: the first PIN needs the account password.
      const pw = [...db.users.values()].find((u) => u.user.id === r.user.id)?.password
      if (typeof b.password !== 'string' || b.password !== pw) return invalid(request, 'password', 'incorrect password')
    }
    db.v2.pins.set(r.user.id, { pin: b.pin, fails: 0, lockedUntil: 0 })
    audit(r.user, 'PIN_SET', 'USER', r.user.id, null)
    return new Response(null, { status: 204 })
  }),

  // Sessions
  http.get(`${BASE}/users/me/sessions`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.sessions.filter((x) => x.userId === r.user.id).map(({ userId: _u, ...x }) => x))
  }),
  http.delete(`${BASE}/users/me/sessions/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.sessions.findIndex((x) => x.id === params.id && x.userId === r.user.id)
    if (i < 0) return notFound(request, 'Session')
    if (db.v2.sessions[i]!.current) return error(409, 'INVALID_STATE', 'Use sign out for this device', request)
    db.v2.sessions.splice(i, 1)
    audit(r.user, 'SESSION_REVOKED', 'USER', r.user.id, null)
    return new Response(null, { status: 204 })
  }),
  http.delete(`${BASE}/users/me/sessions`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    db.v2.sessions = db.v2.sessions.filter((x) => x.userId !== r.user.id || x.current)
    audit(r.user, 'SESSIONS_REVOKED', 'USER', r.user.id, 'all other devices')
    return new Response(null, { status: 204 })
  }),

  // Limits
  http.get(`${BASE}/users/me/limits`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(limitsFor(r.user.id))
  }),
  http.put(`${BASE}/users/me/limits`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { perTransfer?: unknown; daily?: unknown }
    const cur = db.v2.limits.get(r.user.id) ?? { perTransfer: PER_TRANSFER_MAX, daily: DAILY_MAX }
    for (const [field, max] of [['perTransfer', PER_TRANSFER_MAX], ['daily', DAILY_MAX]] as const) {
      const v = b[field]
      if (v === undefined) continue
      if (typeof v !== 'string' || !/^\d{1,9}(\.\d{1,2})?$/.test(v) || new Decimal(v).lt(1))
        return invalid(request, field, 'must be an amount of at least 1.00')
      if (new Decimal(v).gt(max)) return invalid(request, field, `can't be raised above ${max.replace('.0000', '.00')}`)
    }
    const gate = stepUpGate(request, r.user, 'SECURITY_SETTINGS')
    if (gate) return gate
    db.v2.limits.set(r.user.id, {
      perTransfer: typeof b.perTransfer === 'string' ? money(b.perTransfer) : cur.perTransfer,
      daily: typeof b.daily === 'string' ? money(b.daily) : cur.daily,
    })
    audit(r.user, 'LIMITS_CHANGED', 'USER', r.user.id, null)
    return json(limitsFor(r.user.id))
  }),

  // KYC
  http.get(`${BASE}/users/me/kyc`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json<KycDto>(db.v2.kyc.get(r.user.id) ?? { status: 'NONE', panMasked: null, dateOfBirth: null, address: null, verifiedAt: null })
  }),
  http.put(`${BASE}/users/me/kyc`, async ({ request }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { pan?: unknown; dateOfBirth?: unknown; address?: unknown }
    const fe = []
    const pan = typeof b.pan === 'string' ? b.pan.toUpperCase() : ''
    if (!PAN_RE.test(pan)) fe.push({ field: 'pan', message: 'must look like ABCDE1234F' })
    const dob = typeof b.dateOfBirth === 'string' ? b.dateOfBirth : ''
    const age = dob ? (Date.now() - Date.parse(dob)) / (365.25 * 86_400_000) : 0
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dob) || age < 18 || age > 120) fe.push({ field: 'dateOfBirth', message: 'you must be 18 or older' })
    const address = typeof b.address === 'string' ? b.address.trim() : ''
    if (address.length < 10 || address.length > 200) fe.push({ field: 'address', message: 'must be 10–200 characters' })
    if (fe.length) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, fe)
    const dto: KycDto = { status: 'VERIFIED', panMasked: `${pan.slice(0, 5)}••••${pan.slice(-1)}`, dateOfBirth: dob, address, verifiedAt: new Date().toISOString() }
    db.v2.kyc.set(r.user.id, dto)
    audit(r.user, 'KYC_SUBMITTED', 'USER', r.user.id, null)
    notify(r.user.id, 'ACCOUNT_UNFROZEN', 'KYC verified', 'Your KYC profile is verified. Fixed deposits and Autopilot investing are unlocked.')
    return json(dto)
  }),
]

export function limitsFor(userId: string): LimitsDto {
  const l = db.v2.limits.get(userId) ?? { perTransfer: PER_TRANSFER_MAX, daily: DAILY_MAX }
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())
  const own = new Set([...db.accounts.values()].filter((a) => a.userId === userId).map((a) => a.accountNumber))
  const used = db.transfers
    .filter((t) => t.userId === userId && !own.has(t.toAccountNumber) && t.createdAt.slice(0, 10) === today)
    .reduce((s, t) => s.plus(t.amount), new Decimal(0))
  return { perTransfer: l.perTransfer, daily: l.daily, perTransferMax: PER_TRANSFER_MAX, dailyMax: DAILY_MAX, usedToday: money(used) }
}
