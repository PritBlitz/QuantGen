// Market orders under load (PRD F-OPS-4) while the price simulator ticks every 3 s: buys and sells of
// one share across a few symbols, so holdings rows and the brokerage clearing account are contended.
//
//   docker run --rm -i -v "$PWD/infra/k6:/k6" grafana/k6:1.0.0 run /k6/orders.js
import http from 'k6/http'
import { check } from 'k6'
import { BASE, auth, login, newCustomer, uuid, assertReconciled } from './lib.js'

const USERS = Number(__ENV.USERS || 10)

export const options = {
  setupTimeout: '180s',
  scenarios: {
    traders: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '60s' },
  },
  thresholds: {
    checks: ['rate>0.99'],
    // A setup failure runs zero iterations, and every other threshold would then pass vacuously.
    iterations: ['count>0'],
    'http_req_duration{name:order}': ['p(95)<800'],
  },
}

export function setup() {
  const users = []
  for (let i = 0; i < USERS; i++) users.push(newCustomer('o', i, '100000.00'))
  const admin = login(__ENV.QG_ADMIN_EMAIL || 'admin@quantgen.dev', __ENV.QG_ADMIN_PASSWORD || 'Admin@1234')
  const symbols = http.get(`${BASE}/market/instruments`, auth(admin)).json().slice(0, 4).map((i) => i.symbol)
  return { users, symbols }
}

export default function (data) {
  const me = data.users[(__VU - 1) % data.users.length]
  if (!me.token) me.token = login(me.email, me.password)
  // Buy one share, then sell exactly what the last filled buy got, so a failed buy never makes the
  // next sell fail too.
  const side = me.held ? 'SELL' : 'BUY'
  const symbol = me.held || data.symbols[__ITER % data.symbols.length]
  const r = http.post(
    `${BASE}/orders`,
    JSON.stringify({ accountId: me.accountId, symbol, side, quantity: 1 }),
    Object.assign(auth(me.token, { 'Idempotency-Key': uuid() }), { tags: { name: 'order', side } }),
  )
  if (r.status === 401) {
    me.token = null
    return
  }
  const filled = r.status === 201 && r.json('status') === 'FILLED'
  if (filled) me.held = side === 'BUY' ? symbol : null
  check(r, { 'order 201 FILLED': () => filled })
}

export function teardown() {
  assertReconciled()
}
