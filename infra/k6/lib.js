// Shared helpers for the QuantGen k6 scripts (PRD F-OPS-4). Throwaway users only; never point
// these at a deployment with real customers. Registration from one IP needs the backend's auth
// rate limiter off (QG_RATE_LIMIT_ENABLED=false), like the live e2e suite.
import http from 'k6/http'
import { check, fail } from 'k6'

export const BASE = (__ENV.QG_BASE_URL || 'http://host.docker.internal:8080') + '/api/v1'
const JSON_HEADERS = { 'Content-Type': 'application/json' }

export function uuid() {
  // RFC 4122 v4 from Math.random: fine for idempotency keys in a load test.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

export function auth(token, extra) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS, extra || {}) }
}

export function login(email, password) {
  const r = http.post(`${BASE}/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS })
  if (r.status !== 200) fail(`login ${email}: ${r.status}`)
  return r.json('accessToken')
}

/** Registers a fresh customer, deposits `deposit` rupees and returns {token, accountId, accountNumber}. */
export function newCustomer(tag, i, deposit) {
  const n = `${Date.now()}`.slice(-7) + `${i}`.padStart(3, '0')
  const email = `k6${tag}${n}@example.com`
  const password = 'K6load@12345'
  const reg = http.post(
    `${BASE}/auth/register`,
    JSON.stringify({ fullName: `k6 ${tag} ${i}`, email, phone: `+91${n}`, password }),
    { headers: JSON_HEADERS },
  )
  if (reg.status !== 201) fail(`register ${email}: ${reg.status} ${reg.body}`)
  const token = login(email, password)
  const account = http.get(`${BASE}/accounts`, auth(token)).json()[0]
  const dep = http.post(`${BASE}/accounts/${account.id}/deposit`, JSON.stringify({ amount: deposit }), auth(token, { 'Idempotency-Key': uuid() }))
  if (dep.status !== 201 && dep.status !== 200) fail(`deposit: ${dep.status} ${dep.body}`)
  return { email, password, accountId: account.id, accountNumber: account.accountNumber }
}

/** The pass condition after every run: the ledger still reconciles (PRD §11.6). */
export function assertReconciled() {
  const admin = login(__ENV.QG_ADMIN_EMAIL || 'admin@quantgen.dev', __ENV.QG_ADMIN_PASSWORD || 'Admin@1234')
  const r = http.get(`${BASE}/admin/ledger/reconcile`, auth(admin))
  const ok = check(r, {
    'reconcile 200': (x) => x.status === 200,
    'ledger balanced': (x) => x.json('balanced') === true,
    'no mismatches': (x) => (x.json('mismatches') || []).length === 0,
  })
  if (!ok) fail(`ledger did not reconcile: ${r.body}`)
}
