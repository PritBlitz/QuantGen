// Transfers under load (PRD F-OPS-4): a ring of customers paying each other, plus everyone paying
// one "hot" account at once (the row-lock path, ARCHITECTURE §4.3). About 10% of iterations retry with
// the same Idempotency-Key and must get the stored response back, never a second transfer.
//
//   docker run --rm -i -v "$PWD/infra/k6:/k6" grafana/k6:1.0.0 run /k6/transfers.js
//   (-e QG_BASE_URL=http://host.docker.internal:8080 -e USERS=20 -e VUS=10 -e DURATION=60s to tune)
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { BASE, auth, login, newCustomer, uuid, assertReconciled } from './lib.js'

const USERS = Number(__ENV.USERS || 20)
const replays = new Counter('qg_idempotent_replays')
const duplicates = new Counter('qg_duplicate_transfers')

export const options = {
  setupTimeout: '180s',
  scenarios: {
    ring: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '60s' },
  },
  thresholds: {
    // 201 for every transfer (a fraud hold is still 201 PENDING_REVIEW; limits are far away at ₹1–₹9).
    checks: ['rate>0.99'],
    // A setup failure runs zero iterations, and every other threshold would then pass vacuously.
    iterations: ['count>0'],
    'http_req_duration{name:transfer}': ['p(95)<800'],
    'http_req_failed{name:transfer}': ['rate<0.01'],
    qg_duplicate_transfers: ['count==0'],
  },
}

export function setup() {
  const users = []
  for (let i = 0; i < USERS; i++) users.push(newCustomer('t', i, '50000.00'))
  const hot = newCustomer('hot', 0, '1.00')
  return { users, hot }
}

export default function (data) {
  const me = data.users[(__VU - 1) % data.users.length]
  if (!me.token) me.token = login(me.email, me.password)
  // Mostly the next user in the ring; every 4th iteration everyone hits the hot account.
  const to = __ITER % 4 === 3 ? data.hot : data.users[__VU % data.users.length]
  const body = JSON.stringify({ fromAccountId: me.accountId, toAccountNumber: to.accountNumber, amount: `${1 + (__ITER % 9)}.00`, note: 'k6' })
  const key = uuid()
  const first = http.post(`${BASE}/transfers`, body, Object.assign(auth(me.token, { 'Idempotency-Key': key }), { tags: { name: 'transfer' } }))
  if (first.status === 401) {
    me.token = null // access token expired (15 min); log in again next iteration
    return
  }
  check(first, { 'transfer 201': (r) => r.status === 201 })

  if (Math.random() < 0.1 && first.status === 201) {
    const again = http.post(`${BASE}/transfers`, body, Object.assign(auth(me.token, { 'Idempotency-Key': key }), { tags: { name: 'replay' } }))
    const same = again.status === 201 && again.json('id') === first.json('id')
    check(again, { 'replay returns the same transfer': () => same, 'replay is marked': (r) => r.headers['Idempotent-Replayed'] === 'true' })
    if (same) replays.add(1)
    else duplicates.add(1)
  }
}

export function teardown() {
  assertReconciled()
}
