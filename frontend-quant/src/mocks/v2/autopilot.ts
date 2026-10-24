/**
 * MSW handlers: Autopilot (§12.7). Includes the *server-side* renderer that turns a rule into the
 * plain sentence the user approves (D-024: the user reads what will actually run, not what the
 * model wrote), a keyword "Planner" compiler, a validator and a 90-day replay simulator.
 */
import { delay, http } from 'msw'
import type { FieldError, RuleAction, RuleAmount, RuleDsl, RuleDto, SimulationDto, TemplateDto } from '@/api/types'
import { BASE, db, error, inr, last4, paginate } from '../handlers'
import { addDays, Decimal, json, money, notFound, requireUser, stepUpGate, todayIst } from './common'
import { categoryOf } from '@/lib/categories'
import { DEMO_CHECKING, DEMO_GOAL_EMERGENCY, DEMO_GOAL_GOA, DEMO_SAVINGS, prng } from './state'

const MONEY_RE = /^\d{1,9}(\.\d{1,2})?$/
const WEEKDAYS = { MON: 'Monday', TUE: 'Tuesday', WED: 'Wednesday', THU: 'Thursday', FRI: 'Friday', SAT: 'Saturday', SUN: 'Sunday' } as const

function accName(id: string | undefined): string {
  const a = id ? db.accounts.get(id) : undefined
  if (!a) return 'an account'
  const goal = db.v2.goals.get(a.id)
  if (goal) return goal.name
  return `${a.type === 'SAVINGS' ? 'Savings' : a.type === 'CHECKING' ? 'Checking' : 'Account'} ${last4(a.accountNumber)}`
}

const ordinal = (n: number) => `${n}${['th', 'st', 'nd', 'rd'][n % 100 > 10 && n % 100 < 14 ? 0 : Math.min(n % 10, 4) % 4] ?? 'th'}`

function amountText(a: RuleAmount): string {
  if (a.mode === 'FIXED') return inr(a.value)
  if (a.mode === 'PERCENT_OF_TRIGGER') return `${new Decimal(a.value).toDecimalPlaces(2).toString()}% of it`
  return `everything above ${inr(a.value)}`
}

function actionText(a: RuleAction): string {
  switch (a.type) {
    case 'TRANSFER': {
      const to = a.toBeneficiaryId ? (db.beneficiaries.find((b) => b.id === a.toBeneficiaryId)?.nickname ?? 'a saved payee') : accName(a.toAccountId)
      return `move ${amountText(a.amount)} to ${to}`
    }
    case 'BUY':
      return `buy ${amountText(a.amount)} of ${a.symbol} (whole shares)`
    case 'PAY_BILL':
      {
        // Backend (c)'s sentence: the nickname, else the masked consumer number.
        const sb = db.v2.savedBillers.find((x) => x.id === a.savedBillerId)
        return `pay the ${sb?.billerName ?? 'saved'} bill (${sb?.nickname ?? sb?.consumerNumberMasked ?? 'removed'}) from ${accName(a.fromAccountId)} (if it isn't paid yet)`
      }
    case 'ROUND_UP_SWEEP':
      // Backend f5ab093's exact sentence.
      return `move the round-ups to the next ₹${a.roundTo} on spends from ${accName(a.fromAccountId)} since the previous scheduled day to ${accName(a.toAccountId)}`
    case 'NOTIFY':
      return `notify me: “${a.message}”`
  }
}

/** The server's plain-language rendering of a rule (shown on the review screen). */
export function renderRule(r: RuleDsl): string {
  const t = r.trigger
  let when: string
  switch (t.type) {
    case 'MONEY_RECEIVED':
      when = `When ${t.minAmount ? `at least ${inr(t.minAmount)}` : 'money'}${t.counterpartyContains ? ` from “${t.counterpartyContains}”` : ''} lands in ${accName(t.accountId)}`
      break
    case 'SCHEDULE':
      when = t.frequency === 'MONTHLY' ? `On the ${ordinal(t.dayOfMonth ?? 1)} of every month at 08:00 IST` : `Every ${WEEKDAYS[t.weekday ?? 'MON']} at 08:00 IST`
      break
    case 'BALANCE_ABOVE':
      when = `Every day, if ${accName(t.accountId)} holds more than ${inr(t.threshold)}`
      break
    case 'PRICE_CROSSES':
      when = `When ${t.symbol} goes ${t.direction === 'ABOVE' ? 'above' : 'below'} ${inr(t.price)}`
      break
    case 'BUDGET_THRESHOLD':
      when = `When your ${categoryOf(t.category).label.toLowerCase()} spending reaches ${t.percent}% of its monthly budget (once a month)`
      break
  }
  const cond = r.conditions.map((c) => `, if ${accName(c.accountId)} holds at least ${inr(c.amount)}`).join('')
  const acts = r.actions.map(actionText)
  const list = acts.length > 1 ? `${acts.slice(0, -1).join(', ')}, then ${acts.at(-1)}` : acts[0]
  return `${when}${cond}: ${list}. At most ${inr(r.caps.perRun)} per run and ${inr(r.caps.perMonth)} a month.`
}

function validate(userId: string, r: unknown): FieldError[] {
  const fe: FieldError[] = []
  const rule = r as Partial<RuleDsl> | null
  if (!rule || rule.dslVersion !== 1) return [{ field: 'rule.dslVersion', message: 'must be 1' }]
  const own = (id: unknown) => typeof id === 'string' && db.accounts.get(id)?.userId === userId
  const t = rule.trigger
  if (!t) fe.push({ field: 'rule.trigger', message: 'is required' })
  else if ((t.type === 'MONEY_RECEIVED' || t.type === 'BALANCE_ABOVE') && !own(t.accountId)) fe.push({ field: 'rule.trigger.accountId', message: 'pick one of your accounts' })
  else if (t.type === 'SCHEDULE' && t.frequency === 'MONTHLY' && !(Number.isInteger(t.dayOfMonth) && t.dayOfMonth! >= 1 && t.dayOfMonth! <= 28)) fe.push({ field: 'rule.trigger.dayOfMonth', message: 'must be 1–28' })
  else if (t.type === 'PRICE_CROSSES' && !db.instruments.has(t.symbol)) fe.push({ field: 'rule.trigger.symbol', message: 'unknown symbol' })
  else if (t.type === 'BUDGET_THRESHOLD' && !(Number.isInteger(t.percent) && t.percent >= 1 && t.percent <= 200)) fe.push({ field: 'rule.trigger.percent', message: 'must be a whole number 1–200' })
  else if (t.type === 'BUDGET_THRESHOLD' && !(db.v2.budgets.get(userId) ?? []).some((b) => b.category === t.category)) fe.push({ field: 'rule.trigger.category', message: 'set a monthly budget for this category first' })
  if (!Array.isArray(rule.actions) || rule.actions.length < 1 || rule.actions.length > 5) fe.push({ field: 'rule.actions', message: 'add 1–5 actions' })
  else
    rule.actions.forEach((a, i) => {
      if (a.type === 'TRANSFER') {
        if (a.toAccountId && !own(a.toAccountId)) fe.push({ field: `rule.actions[${i}].toAccountId`, message: 'pick one of your accounts' })
        if (a.toBeneficiaryId) {
          const b = db.beneficiaries.find((x) => x.id === a.toBeneficiaryId && x.userId === userId)
          if (!b) fe.push({ field: `rule.actions[${i}].toBeneficiaryId`, message: 'pick a saved payee' })
          else if (Date.now() - Date.parse(b.createdAt) < 86_400_000) fe.push({ field: `rule.actions[${i}].toBeneficiaryId`, message: 'payees saved in the last 24 hours can’t be used yet' })
        }
        if (!a.toAccountId && !a.toBeneficiaryId) fe.push({ field: `rule.actions[${i}]`, message: 'choose where the money goes' })
      }
      if (a.type === 'BUY' && !db.instruments.has(a.symbol)) fe.push({ field: `rule.actions[${i}].symbol`, message: 'unknown symbol' })
      if (a.type === 'PAY_BILL') {
        if (!db.v2.savedBillers.some((x) => x.id === a.savedBillerId && x.userId === userId)) fe.push({ field: `rule.actions[${i}].savedBillerId`, message: 'pick one of your saved billers' })
        if (!own(a.fromAccountId)) fe.push({ field: `rule.actions[${i}].fromAccountId`, message: 'pick one of your accounts' })
      }
      if (a.type === 'ROUND_UP_SWEEP') {
        if (rule.trigger?.type !== 'SCHEDULE') fe.push({ field: `rule.actions[${i}].type`, message: 'round-up sweeps run on a schedule trigger' })
        if (!own(a.fromAccountId)) fe.push({ field: `rule.actions[${i}].fromAccountId`, message: 'pick one of your accounts' })
        else if (db.v2.roundUps.get(a.fromAccountId)?.enabled) fe.push({ field: `rule.actions[${i}].fromAccountId`, message: 'this account already has nightly round-ups on' })
        if (!own(a.toAccountId) || a.toAccountId === a.fromAccountId) fe.push({ field: `rule.actions[${i}].toAccountId`, message: 'pick another of your accounts' })
        if (![10, 50, 100].includes(a.roundTo)) fe.push({ field: `rule.actions[${i}].roundTo`, message: 'must be 10, 50 or 100' })
      }
      if ('amount' in a && a.amount) {
        const ok = a.amount.mode === 'PERCENT_OF_TRIGGER' ? /^\d{1,3}(\.\d{1,2})?$/.test(a.amount.value) && new Decimal(a.amount.value).gt(0) && new Decimal(a.amount.value).lte(100) : MONEY_RE.test(a.amount.value)
        if (!ok) fe.push({ field: `rule.actions[${i}].amount`, message: a.amount.mode === 'PERCENT_OF_TRIGGER' ? 'must be 0–100%' : 'must be an amount' })
        if (a.amount.mode === 'PERCENT_OF_TRIGGER' && rule.trigger?.type !== 'MONEY_RECEIVED') fe.push({ field: `rule.actions[${i}].amount`, message: '“% of it” only works with a money-received trigger' })
      }
    })
  const caps = rule.caps
  if (!caps || !MONEY_RE.test(caps.perRun ?? '') || !MONEY_RE.test(caps.perMonth ?? '')) fe.push({ field: 'rule.caps', message: 'set both caps' })
  else if (new Decimal(caps.perRun).gt(caps.perMonth)) fe.push({ field: 'rule.caps.perRun', message: 'can’t be more than the monthly cap' })
  else if (new Decimal(caps.perRun).gt(100000)) fe.push({ field: 'rule.caps.perRun', message: 'Autopilot moves at most ₹1,00,000 a day' })
  return fe
}

/** Keyword "Planner" (the real one is an LLM in agent-quant; the output shape is identical). */
function compile(text: string): { rule: RuleDsl; warnings: string[] } {
  const t = text.toLowerCase()
  const pct = t.match(/(\d{1,2})\s*%/)?.[1]
  const amt = t.match(/₹?\s?(\d[\d,]{2,})/)?.[1]?.replace(/,/g, '')
  const warnings: string[] = []
  const goal = /emergency/.test(t) ? DEMO_GOAL_EMERGENCY : DEMO_GOAL_GOA
  const sym = t.match(/\b(qgen|tcs|infy|hdfc|reli|itc|sbin)\b/)?.[1]?.toUpperCase()
  if (/salary|paycheck|payroll|lands|arrives|receive/.test(t)) {
    const actions: RuleAction[] = [{ type: 'TRANSFER', fromAccountId: DEMO_SAVINGS, toAccountId: goal, amount: pct ? { mode: 'PERCENT_OF_TRIGGER', value: pct } : { mode: 'FIXED', value: `${amt ?? '5000'}.00` } }]
    if (sym) actions.push({ type: 'BUY', accountId: DEMO_SAVINGS, symbol: sym, amount: { mode: 'FIXED', value: '5000.00' } })
    if (!pct && !amt) warnings.push('No amount found, so I used ₹5,000. Change it below.')
    return { rule: { dslVersion: 1, trigger: { type: 'MONEY_RECEIVED', accountId: DEMO_SAVINGS, minAmount: '40000.00' }, conditions: [], actions, caps: { perRun: '30000.00', perMonth: '60000.00' } }, warnings: [...warnings, 'I assumed a salary is any credit of ₹40,000 or more. Add the employer’s name to be precise.'] }
  }
  if (/every month|monthly|sip|on the \d/.test(t)) {
    const day = Number(t.match(/on the (\d{1,2})/)?.[1] ?? 5)
    const action: RuleAction = sym ? { type: 'BUY', accountId: DEMO_SAVINGS, symbol: sym, amount: { mode: 'FIXED', value: `${amt ?? '5000'}.00` } } : { type: 'TRANSFER', fromAccountId: DEMO_SAVINGS, toAccountId: goal, amount: { mode: 'FIXED', value: `${amt ?? '5000'}.00` } }
    if (day > 28) warnings.push('Monthly rules run on day 1–28, so I used the 28th.')
    const cap = `${amt ?? '5000'}.00`
    return { rule: { dslVersion: 1, trigger: { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: Math.min(day, 28) }, conditions: [], actions: [action], caps: { perRun: cap, perMonth: cap } }, warnings }
  }
  if (/more than|above|over|keep|sweep/.test(t)) {
    const threshold = `${amt ?? '50000'}.00`
    return { rule: { dslVersion: 1, trigger: { type: 'BALANCE_ABOVE', accountId: DEMO_CHECKING, threshold }, conditions: [], actions: [{ type: 'TRANSFER', fromAccountId: DEMO_CHECKING, toAccountId: goal, amount: { mode: 'EXCESS_ABOVE', value: threshold } }], caps: { perRun: '50000.00', perMonth: '100000.00' } }, warnings }
  }
  if (sym && /drops?|falls?|below|under/.test(t)) {
    const price = `${amt ?? '1000'}.00`
    return { rule: { dslVersion: 1, trigger: { type: 'PRICE_CROSSES', symbol: sym, direction: 'BELOW', price }, conditions: [], actions: [{ type: 'BUY', accountId: DEMO_SAVINGS, symbol: sym, amount: { mode: 'FIXED', value: '10000.00' } }], caps: { perRun: '10000.00', perMonth: '20000.00' } }, warnings: ['I set a ₹10,000 buy. Adjust it below.'] }
  }
  return {
    rule: { dslVersion: 1, trigger: { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: 1 }, conditions: [], actions: [{ type: 'TRANSFER', fromAccountId: DEMO_SAVINGS, toAccountId: goal, amount: { mode: 'FIXED', value: '5000.00' } }], caps: { perRun: '5000.00', perMonth: '5000.00' } },
    warnings: ['I wasn’t sure what you meant, so here’s a simple monthly saving rule to start from.'],
  }
}

function simulate(userId: string, rule: RuleDsl, days: number): SimulationDto {
  const rnd = prng(days + rule.actions.length * 31)
  const today = todayIst()
  const firings: SimulationDto['firings'] = []
  const events: Array<{ date: string; summary: string; amount: Decimal }> = []
  const t = rule.trigger
  for (let i = days; i >= 0; i--) {
    const date = addDays(today, -i)
    const dom = Number(date.slice(8))
    if (t.type === 'MONEY_RECEIVED' && dom === 1) events.push({ date, summary: `${inr('82000')} from ACME Payroll into ${accName(t.accountId)}`, amount: new Decimal(82000) })
    if (t.type === 'SCHEDULE' && ((t.frequency === 'MONTHLY' && dom === (t.dayOfMonth ?? 1)) || (t.frequency === 'WEEKLY' && new Date(`${date}T00:00:00Z`).getUTCDay() === ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'].indexOf(t.weekday ?? 'MON'))))
      events.push({ date, summary: t.frequency === 'MONTHLY' ? `Scheduled: ${ordinal(t.dayOfMonth ?? 1)} of the month` : `Scheduled: every ${WEEKDAYS[t.weekday ?? 'MON']}`, amount: new Decimal(0) })
    if (t.type === 'BALANCE_ABOVE' && i % 9 === 3) {
      const held = new Decimal(t.threshold).plus(Math.round(1500 + rnd() * 9000))
      events.push({ date, summary: `Daily check: ${accName(t.accountId)} held ${inr(held)}`, amount: held.minus(t.threshold) })
    }
    if (t.type === 'PRICE_CROSSES' && i === 37) events.push({ date, summary: `${t.symbol} crossed ${inr(t.price)}`, amount: new Decimal(0) })
  }
  let moved = new Decimal(0)
  let failures = 0
  const monthly = new Map<string, Decimal>()
  for (const e of events) {
    let runTotal = new Decimal(0)
    const month = e.date.slice(0, 7)
    const acts = rule.actions.map((a, idx) => {
      let amount = new Decimal(0)
      if ('amount' in a) {
        amount =
          a.amount.mode === 'FIXED'
            ? new Decimal(a.amount.value)
            : a.amount.mode === 'PERCENT_OF_TRIGGER'
              ? e.amount.times(a.amount.value).div(100).toDecimalPlaces(2)
              : e.amount
      }
      const monthUsed = monthly.get(month) ?? new Decimal(0)
      let wouldSucceed = true
      let reason: string | undefined
      if (runTotal.plus(amount).gt(rule.caps.perRun)) {
        wouldSucceed = false
        reason = 'Over the per-run cap'
      } else if (monthUsed.plus(runTotal).plus(amount).gt(rule.caps.perMonth)) {
        wouldSucceed = false
        reason = 'Over the monthly cap'
      } else if (idx === 1 && rnd() < 0.15) {
        wouldSucceed = false
        reason = 'Insufficient funds that day'
      }
      if (wouldSucceed) runTotal = runTotal.plus(amount)
      else failures += 1
      return { summary: actionText(a).replace(/^./, (c) => c.toUpperCase()), amount: money(amount), wouldSucceed, ...(reason ? { reason } : {}) }
    })
    monthly.set(month, (monthly.get(month) ?? new Decimal(0)).plus(runTotal))
    moved = moved.plus(runTotal)
    firings.push({ date: e.date, triggerSummary: e.summary, actions: acts })
  }
  void userId
  return { firings: firings.reverse(), totalMoved: money(moved), failures }
}

const TEMPLATES: TemplateDto[] = [
  { id: 'salary-splitter', name: 'Salary splitter', description: 'Split every salary between goals, investments and bills the moment it lands.', prompt: 'When my salary arrives, move 20% to my Goa goal and buy ₹5,000 of QGEN', rule: null },
  { id: 'round-up-saver', name: 'Round-up saver', description: 'Every spend rounds up to the next ₹10; the change goes to a goal.', prompt: 'Every Sunday sweep the round-ups on my savings spends into my Goa goal', rule: { dslVersion: 1, trigger: { type: 'SCHEDULE', frequency: 'WEEKLY', weekday: 'SUN' }, conditions: [], actions: [{ type: 'ROUND_UP_SWEEP', fromAccountId: DEMO_SAVINGS, toAccountId: DEMO_GOAL_GOA, roundTo: 10 }], caps: { perRun: '2000.00', perMonth: '8000.00' } } },
  { id: 'budget-guard', name: 'Budget guard', description: 'Get a heads-up when a budget is nearly spent.', prompt: 'Tell me when my food spending reaches 90% of its budget', rule: null },
  { id: 'sip', name: 'Monthly SIP', description: 'Invest a fixed amount on the same day every month.', prompt: 'Every month on the 5th buy ₹8,000 of TCS', rule: null },
  { id: 'lean-checking', name: 'Keep checking lean', description: 'Anything above a buffer is swept into savings every day.', prompt: 'Keep ₹50,000 in checking and sweep the rest to my emergency fund', rule: null },
  { id: 'rent', name: 'Pay rent on the 1st', description: 'Rent goes out on the 1st, automatically, within your caps.', prompt: 'On the 1st of every month move ₹12,000 to Meera', rule: null },
  { id: 'buy-the-dip', name: 'Buy the dip', description: 'Buy a stock you like when it falls below a price.', prompt: 'When QGEN drops below ₹1,200 buy ₹10,000 of it', rule: null },
]

function publicRule({ userId: _u, ...r }: RuleDto & { userId: string }): RuleDto {
  return r
}

export const autopilotHandlers = [
  http.get(`${BASE}/autopilot/templates`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(TEMPLATES)
  }),
  http.post(`${BASE}/autopilot/compile`, async ({ request }) => {
    await delay(1100)
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.ai.enabled) return error(503, 'FEATURE_DISABLED', 'The AI layer is switched off; use the form instead', request)
    const b = (await request.json()) as { text?: unknown }
    const text = typeof b.text === 'string' ? b.text.trim() : ''
    if (text.length < 8 || text.length > 500) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [{ field: 'text', message: 'describe the rule in 8–500 characters' }])
    const { rule, warnings } = compile(text)
    return json({ rule, rendering: renderRule(rule), warnings })
  }),
  http.post(`${BASE}/autopilot/validate`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { rule?: unknown }
    const fe = validate(r.user.id, b.rule)
    return json({ valid: fe.length === 0, rendering: fe.length ? null : renderRule(b.rule as RuleDsl), fieldErrors: fe })
  }),
  http.post(`${BASE}/autopilot/simulate`, async ({ request }) => {
    await delay(700)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { rule?: unknown; days?: unknown }
    const fe = validate(r.user.id, b.rule)
    if (fe.length) return error(422, 'RULE_INVALID', 'Rule is invalid', request, fe)
    return json(simulate(r.user.id, b.rule as RuleDsl, Math.min(180, Number(b.days ?? 90))))
  }),
  http.get(`${BASE}/autopilot/rules`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.rules.filter((x) => x.userId === r.user.id).map(publicRule))
  }),
  http.get(`${BASE}/autopilot/rules/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const rule = db.v2.rules.find((x) => x.id === params.id && x.userId === r.user.id)
    return rule ? json(publicRule(rule)) : notFound(request, 'Rule')
  }),
  http.post(`${BASE}/autopilot/rules`, async ({ request }) => {
    await delay(400)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { name?: unknown; rule?: unknown }
    const name = typeof b.name === 'string' ? b.name.trim() : ''
    const fe = validate(r.user.id, b.rule)
    if (!name || name.length > 40) fe.unshift({ field: 'name', message: 'must be 1–40 characters' })
    if (fe.length) return error(422, 'RULE_INVALID', 'Rule is invalid', request, fe)
    if (db.v2.rules.filter((x) => x.userId === r.user.id && x.status === 'ACTIVE').length >= 10) return error(422, 'LIMIT_EXCEEDED', 'At most 10 active rules', request)
    const rule = b.rule as RuleDsl
    const gate = stepUpGate(request, r.user, 'RULE_ACTIVATE')
    if (gate) return gate
    const now = new Date().toISOString()
    const dto = { id: crypto.randomUUID(), userId: r.user.id, name, version: 1, status: 'ACTIVE' as const, rule, rendering: renderRule(rule), pausedReason: null, lastRunAt: null, runCount: 0, movedThisMonth: '0.0000', createdAt: now, updatedAt: now }
    db.v2.rules.unshift(dto)
    db.v2.ruleRuns.set(dto.id, [])
    return json(publicRule(dto), 201)
  }),
  http.put(`${BASE}/autopilot/rules/:id`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const rule = db.v2.rules.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!rule) return notFound(request, 'Rule')
    const b = (await request.json()) as { name?: unknown; rule?: unknown }
    const fe = validate(r.user.id, b.rule)
    if (fe.length) return error(422, 'RULE_INVALID', 'Rule is invalid', request, fe)
    const gate = stepUpGate(request, r.user, 'RULE_ACTIVATE')
    if (gate) return gate
    rule.rule = b.rule as RuleDsl
    rule.name = typeof b.name === 'string' && b.name.trim() ? b.name.trim().slice(0, 40) : rule.name
    rule.version += 1
    rule.rendering = renderRule(rule.rule)
    rule.updatedAt = new Date().toISOString()
    return json(publicRule(rule))
  }),
  http.post(`${BASE}/autopilot/rules/:id/:action`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const rule = db.v2.rules.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!rule) return notFound(request, 'Rule')
    if (params.action === 'pause') {
      if (rule.status !== 'ACTIVE') return error(409, 'INVALID_STATE', 'Rule is not active', request)
      rule.status = 'PAUSED'
      rule.pausedReason = 'Paused by you.'
    } else if (params.action === 'resume') {
      if (rule.status !== 'PAUSED') return error(409, 'INVALID_STATE', 'Rule is not paused', request)
      const gate = stepUpGate(request, r.user, 'RULE_ACTIVATE')
      if (gate) return gate
      rule.status = 'ACTIVE'
      rule.pausedReason = null
    } else return notFound(request)
    rule.updatedAt = new Date().toISOString()
    return json(publicRule(rule))
  }),
  http.delete(`${BASE}/autopilot/rules/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.rules.findIndex((x) => x.id === params.id && x.userId === r.user.id)
    if (i < 0) return notFound(request, 'Rule')
    db.v2.rules.splice(i, 1)
    return new Response(null, { status: 204 })
  }),
  http.get(`${BASE}/autopilot/rules/:id/runs`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const rule = db.v2.rules.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!rule) return notFound(request, 'Rule')
    return json(paginate(db.v2.ruleRuns.get(rule.id) ?? [], new URL(request.url)))
  }),
]
