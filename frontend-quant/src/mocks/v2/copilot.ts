/**
 * MSW handlers: Copilot + intents (§12.6). A scripted stand-in for `agent-quant`: it routes the
 * message to a "specialist" by keyword, streams tool progress and tokens over SSE, and proposes
 * intents whose summaries are rendered here (the server), never by the "model" (D-023).
 * Approving an intent re-validates it and moves money through the same mock ledger as the UI.
 */
import { delay, http, HttpResponse } from 'msw'
import type { AgentName, CitationDto, ConversationDetailDto, IntentDto, IntentKind, TraceDto } from '@/api/types'
import { BASE, db, error, idempotent, inr, last4, notify } from '../handlers'
import { available, Decimal, json, money, move, notFound, requireUser, stepUpGate } from './common'
import { budgetsFor, syntheticSpend } from './savings'
import { DEMO_GOAL_GOA, PEOPLE } from './state'

const enc = new TextEncoder()
const sse = (event: string, data: unknown) => enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)

type Step = { agent: AgentName; tool: string; ms: number }
interface Plan {
  steps: Step[]
  text: string
  intent?: { kind: IntentKind; payload: Record<string, unknown>; rationale: string; summary: IntentDto['summary']; risk: IntentDto['riskLevel']; stepUp: boolean }
  citations?: CitationDto[]
}

function plan(userId: string, text: string): Plan {
  const t = text.toLowerCase()
  const accts = [...db.accounts.values()].filter((a) => a.userId === userId)
  const savings = accts.find((a) => a.type === 'SAVINGS')
  const amountMatch = t.match(/(?:₹|rs\.?\s*|inr\s*)?(\d[\d,]*(?:\.\d{1,2})?)\s*(k|l|lakh)?/)
  const amount = amountMatch
    ? new Decimal(amountMatch[1]!.replace(/,/g, '')).times(amountMatch[2] === 'k' ? 1000 : amountMatch[2] ? 100000 : 1)
    : null

  // Payments: "pay meera 500 for dinner"
  const payTo = Object.values(PEOPLE).find((p) => p.id !== userId && t.includes(p.name.split(' ')[0]!.toLowerCase()))
  if (/\b(pay|send|transfer)\b/.test(t) && payTo && amount && savings) {
    const big = amount.gt(50000)
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_payments', ms: 300 },
        { agent: 'PAYMENTS', tool: 'resolve_payee', ms: 450 },
        { agent: 'PAYMENTS', tool: 'check_limits', ms: 300 },
        { agent: 'PAYMENTS', tool: 'propose_intent', ms: 350 },
      ],
      text: `I’ve prepared a payment of ${inr(amount)} to ${payTo.name} (${payTo.handle}) from your Savings account. Check the card below and approve it if it looks right${big ? ' — it’s over ₹50,000, so you’ll confirm with your passkey or PIN' : ''}. Nothing is sent until you approve.`,
      intent: {
        kind: 'TRANSFER',
        payload: { fromAccountId: savings.id, toHandle: payTo.handle, amount: money(amount), note: text.match(/for (.+)$/i)?.[1]?.slice(0, 60) ?? null },
        rationale: `You asked to pay ${payTo.name.split(' ')[0]} ${inr(amount)}. Your Savings balance covers it and you’re within today’s limit.`,
        summary: {
          title: `Pay ${inr(amount)} to ${payTo.name}`,
          lines: [
            { label: 'To', value: `${payTo.name} · ${payTo.handle}` },
            { label: 'From', value: `Savings ${last4(savings.accountNumber)}` },
            { label: 'Amount', value: inr(amount) },
            { label: 'Balance after', value: inr(available(savings).minus(amount)) },
          ],
        },
        risk: big ? 'HIGH' : amount.gt(10000) ? 'MEDIUM' : 'LOW',
        stepUp: big,
      },
    }
  }

  // Markets: "buy 5 qgen"
  const buy = t.match(/\bbuy\s+(\d{1,4})\s+(?:shares?\s+of\s+)?([a-z]{2,5})\b/)
  if (buy && savings) {
    const sym = buy[2]!.toUpperCase()
    const inst = db.instruments.get(sym)
    if (inst) {
      const qty = Number(buy[1])
      const total = new Decimal(inst.price).times(qty)
      return {
        steps: [
          { agent: 'CONCIERGE', tool: 'ask_markets', ms: 300 },
          { agent: 'MARKETS', tool: 'get_quote', ms: 400 },
          { agent: 'MARKETS', tool: 'propose_intent', ms: 300 },
        ],
        text: `${inst.name} is at ${inr(inst.price)} right now (${inst.changePct24h}% today). Buying ${qty} share${qty === 1 ? '' : 's'} costs about ${inr(total)}; the final price is whatever the market shows when you approve.`,
        intent: {
          kind: 'ORDER',
          payload: { accountId: savings.id, symbol: sym, side: 'BUY', quantity: qty },
          rationale: `Market order for ${qty} ${sym}. Estimated cost uses the latest tick.`,
          summary: { title: `Buy ${qty} × ${sym}`, lines: [{ label: 'Instrument', value: `${inst.name} (${sym})` }, { label: 'Estimated cost', value: inr(total) }, { label: 'Pay from', value: `Savings ${last4(savings.accountNumber)}` }, { label: 'Order type', value: 'Market' }] },
          risk: total.gt(50000) ? 'MEDIUM' : 'LOW',
          stepUp: false,
        },
        citations: [{ type: 'INSTRUMENT', id: sym, label: `${sym} ${inr(inst.price)}` }],
      }
    }
  }

  // Goals: "move 5000 to goa goal"
  if (/\b(save|move|put)\b/.test(t) && /goal|goa/.test(t) && amount && savings) {
    const goal = db.v2.goals.get(DEMO_GOAL_GOA)
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_planner', ms: 300 },
        { agent: 'PLANNER', tool: 'list_goals', ms: 350 },
        { agent: 'PLANNER', tool: 'propose_intent', ms: 300 },
      ],
      text: `Good call. Moving ${inr(amount)} into “${goal?.name ?? 'your goal'}” keeps you on pace for December.`,
      intent: {
        kind: 'GOAL_TRANSFER',
        payload: { fromAccountId: savings.id, toAccountId: DEMO_GOAL_GOA, amount: money(amount) },
        rationale: 'Own-account move: no limits or step-up apply.',
        summary: { title: `Move ${inr(amount)} to ${goal?.name ?? 'goal'}`, lines: [{ label: 'From', value: `Savings ${last4(savings.accountNumber)}` }, { label: 'To', value: goal?.name ?? 'Goal' }, { label: 'Amount', value: inr(amount) }] },
        risk: 'LOW',
        stepUp: false,
      },
    }
  }

  // Spending analysis
  if (/spend|spent|spending|budget|food|groceries|where.*money/.test(t)) {
    const spend = [...syntheticSpend(userId, 0).entries()].sort((a, b) => b[1].cmp(a[1]))
    const total = spend.reduce((s, [, v]) => s.plus(v), new Decimal(0))
    const over = budgetsFor(userId).filter((b) => new Decimal(b.pct).gte(80))
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_ledger_analyst', ms: 300 },
        { agent: 'LEDGER_ANALYST', tool: 'spending_by_category', ms: 500 },
        { agent: 'LEDGER_ANALYST', tool: 'get_budgets', ms: 300 },
      ],
      text: `This month you’ve spent about ${inr(total)}. The biggest pieces are ${spend
        .slice(0, 3)
        .map(([c, v]) => `${c.toLowerCase()} (${inr(v)})`)
        .join(', ')}. ${over.length ? `Heads-up: ${over.map((b) => b.category.toLowerCase()).join(' and ')} ${over.length === 1 ? 'is' : 'are'} past 80% of budget.` : 'All your budgets are comfortably under 80%.'} Want me to set a budget or move the leftover into a goal?`,
      citations: [{ type: 'METRIC', id: 'spending', label: 'Spending by category (this month)' }],
    }
  }

  // Loans / affordability
  if (/loan|afford|emi|borrow/.test(t)) {
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_loan_advisor', ms: 300 },
        { agent: 'LOAN_ADVISOR', tool: 'existing_emis', ms: 350 },
        { agent: 'LOAN_ADVISOR', tool: 'quote', ms: 400 },
      ],
      text: `With your declared income of about ₹82,000 a month and your current EMI of ₹9,414.69, you could take on roughly ₹31,600 more in EMIs (the 50% rule). For example, ₹3,00,000 over 36 months at 12% is an EMI of about ₹9,964 — that fits. Want me to prepare an application? You’d review it before anything is submitted.`,
      citations: [{ type: 'LOAN', id: 'current', label: 'Your active loan' }],
    }
  }

  // Balance
  if (/balance|how much.*(have|left)|net worth/.test(t)) {
    const cash = accts.filter((a) => a.type === 'SAVINGS' || a.type === 'CHECKING')
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_ledger_analyst', ms: 300 },
        { agent: 'LEDGER_ANALYST', tool: 'list_accounts', ms: 350 },
      ],
      text: `You have ${cash.map((a) => `${inr(available(a))} available in ${a.type === 'SAVINGS' ? 'Savings' : 'Checking'} ${last4(a.accountNumber)}`).join(' and ')}. Your goals hold ${inr(accts.filter((a) => a.type === 'GOAL').reduce((s, a) => s.plus(a.balance), new Decimal(0)))} more.`,
      citations: cash.map((a) => ({ type: 'ACCOUNT' as const, id: a.id, label: `${a.type === 'SAVINGS' ? 'Savings' : 'Checking'} ${last4(a.accountNumber)}` })),
    }
  }

  // Autopilot
  if (/when|every month|automat|rule|autopilot|salary/.test(t)) {
    return {
      steps: [
        { agent: 'CONCIERGE', tool: 'ask_planner', ms: 300 },
        { agent: 'PLANNER', tool: 'compile_rule', ms: 600 },
      ],
      text: 'That sounds like an Autopilot rule. Open Autopilot → “New rule”, paste your sentence, and I’ll turn it into a precise rule and replay it over your last 90 days before you switch it on.',
    }
  }

  return {
    steps: [{ agent: 'CONCIERGE', tool: 'route', ms: 250 }],
    text: 'I can explain your spending, check balances, compare loan options, prepare payments, orders and goal transfers for you to approve, and help you write Autopilot rules. Try “How much did I spend on food this month?” or “Pay Meera 500 for dinner”.',
  }
}

function newTrace(steps: Step[]): TraceDto {
  return {
    runId: crypto.randomUUID(),
    startedAt: new Date().toISOString(),
    totalMs: steps.reduce((s, x) => s + x.ms, 0) + 900,
    costUsd: (0.004 + steps.length * 0.0021).toFixed(4),
    steps: [
      ...steps.map((s) => ({ agent: s.agent, kind: (s.tool.startsWith('ask_') || s.tool === 'route' ? 'MODEL' : 'TOOL') as 'MODEL' | 'TOOL', name: s.tool, status: 'OK' as const, latencyMs: s.ms, inputTokens: s.tool.startsWith('ask_') ? 1840 : null, outputTokens: s.tool.startsWith('ask_') ? 120 : null, detail: s.tool === 'resolve_payee' ? 'handle → masked name' : null })),
      { agent: 'CONCIERGE', kind: 'MODEL', name: 'compose_answer', status: 'OK', latencyMs: 900, inputTokens: 2310, outputTokens: 180, detail: null },
    ],
  }
}

function publicIntent({ userId: _u, payload: _p, ...i }: IntentDto & { userId: string; payload: Record<string, unknown> }): IntentDto {
  if (i.status === 'PROPOSED' && Date.parse(i.expiresAt) < Date.now()) return { ...i, status: 'EXPIRED' }
  return i
}

const conv = (userId: string, id: unknown) => db.v2.conversations.find((c) => c.id === id && c.userId === userId)

export const copilotHandlers = [
  http.post(`${BASE}/copilot/conversations`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.ai.enabled) return error(503, 'FEATURE_DISABLED', 'Copilot is switched off', request)
    const now = new Date().toISOString()
    const c: ConversationDetailDto & { userId: string } = { id: crypto.randomUUID(), userId: r.user.id, title: null, createdAt: now, updatedAt: now, messages: [] }
    db.v2.conversations.unshift(c)
    return json({ id: c.id }, 201)
  }),
  http.get(`${BASE}/copilot/conversations`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(db.v2.conversations.filter((c) => c.userId === r.user.id).map(({ userId: _u, messages: _m, ...c }) => c))
  }),
  http.get(`${BASE}/copilot/conversations/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const c = conv(r.user.id, params.id)
    if (!c) return notFound(request, 'Conversation')
    const { userId: _u, ...out } = c
    return json({ ...out, messages: out.messages.map((m) => ({ ...m, intents: m.intents.map((i) => publicIntent(db.v2.intents.find((x) => x.id === i.id) ?? { ...i, userId: '', payload: {} })) })) })
  }),
  http.delete(`${BASE}/copilot/conversations/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.conversations.findIndex((c) => c.id === params.id && c.userId === r.user.id)
    if (i < 0) return notFound(request, 'Conversation')
    db.v2.conversations.splice(i, 1)
    return new Response(null, { status: 204 })
  }),
  http.post(`${BASE}/copilot/conversations/:id/messages`, async ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    if (!db.v2.flags.ai.enabled) return error(503, 'FEATURE_DISABLED', 'Copilot is switched off', request)
    const c = conv(r.user.id, params.id)
    if (!c) return notFound(request, 'Conversation')
    const b = (await request.json()) as { text?: unknown }
    const text = typeof b.text === 'string' ? b.text.trim() : ''
    if (!text || text.length > 2000) return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [{ field: 'text', message: 'must be 1–2000 characters' }])
    const user = r.user
    const p = plan(user.id, text)
    const trace = newTrace(p.steps)
    db.v2.traces.set(trace.runId, trace)
    db.v2.aiCalls += 1
    const now = new Date().toISOString()
    c.messages.push({ id: crypto.randomUUID(), role: 'USER', text, intents: [], citations: [], runId: null, createdAt: now })
    c.title ??= text.slice(0, 48)
    c.updatedAt = now

    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        for (const s of p.steps) {
          controller.enqueue(sse('tool', { agent: s.agent, tool: s.tool, status: 'STARTED' }))
          await delay(Math.min(s.ms, 500))
          controller.enqueue(sse('tool', { agent: s.agent, tool: s.tool, status: 'DONE' }))
        }
        let intent: IntentDto | null = null
        if (p.intent) {
          const full = {
            id: crypto.randomUUID(),
            userId: user.id,
            payload: p.intent.payload,
            kind: p.intent.kind,
            status: 'PROPOSED' as const,
            summary: p.intent.summary,
            rationale: p.intent.rationale,
            riskLevel: p.intent.risk,
            requiresStepUp: p.intent.stepUp,
            proposedBy: { agent: p.steps.at(-1)!.agent },
            conversationId: c.id,
            failureCode: null,
            resultRef: null,
            expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
            createdAt: new Date().toISOString(),
          }
          db.v2.intents.unshift(full)
          intent = publicIntent(full)
          controller.enqueue(sse('intent', intent))
        }
        for (const ci of p.citations ?? []) controller.enqueue(sse('citation', ci))
        for (const word of p.text.split(/(\s+)/)) {
          if (!word) continue
          controller.enqueue(sse('token', { text: word }))
          if (word.trim()) await delay(18)
        }
        c.messages.push({ id: crypto.randomUUID(), role: 'ASSISTANT', text: p.text, intents: intent ? [intent] : [], citations: p.citations ?? [], runId: trace.runId, createdAt: new Date().toISOString() })
        controller.enqueue(sse('done', { runId: trace.runId, usage: { inputTokens: 4150, outputTokens: 300 } }))
        controller.close()
      },
    })
    return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' } })
  }),
  http.get(`${BASE}/copilot/runs/:runId/trace`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const owns = db.v2.conversations.some((c) => c.userId === r.user.id && c.messages.some((m) => m.runId === params.runId))
    const t = db.v2.traces.get(String(params.runId))
    return t && owns ? json(t) : notFound(request, 'Run')
  }),

  // Intents
  http.get(`${BASE}/intents`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const status = new URL(request.url).searchParams.get('status')
    return json(db.v2.intents.filter((i) => i.userId === r.user.id).map(publicIntent).filter((i) => !status || i.status === status))
  }),
  http.post(`${BASE}/intents/:id/reject`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.intents.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!i) return notFound(request, 'Intent')
    if (publicIntent(i).status !== 'PROPOSED') return error(409, 'INVALID_STATE', 'Intent is not open', request)
    i.status = 'REJECTED'
    return json(publicIntent(i))
  }),
  http.post(`${BASE}/intents/:id/approve`, async ({ request, params }) => {
    await delay(500)
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.intents.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!i) return notFound(request, 'Intent')
    const idem = idempotent(request, r.user.id, { id: i.id })
    if (idem.early) return idem.early
    if (i.status === 'PROPOSED' && Date.parse(i.expiresAt) < Date.now()) {
      i.status = 'EXPIRED'
      return error(409, 'INTENT_EXPIRED', 'Intent expired', request)
    }
    if (i.status !== 'PROPOSED') return error(409, 'INVALID_STATE', 'Intent is not open', request)
    if (i.requiresStepUp) {
      const gate = stepUpGate(request, r.user, 'INTENT_APPROVE')
      if (gate) return gate
    }
    // Re-validate and execute through the same rules as a manual action.
    const p = i.payload
    const fail = (code: string) => {
      i.status = 'FAILED'
      i.failureCode = code
      const out = publicIntent(i)
      idem.save(200, out)
      return json(out)
    }
    const from = db.accounts.get(String(p.fromAccountId ?? p.accountId))
    if (!from || from.userId !== r.user.id) return fail('NOT_FOUND')
    if (from.status === 'FROZEN') return fail('ACCOUNT_FROZEN')
    if (i.kind === 'TRANSFER' || i.kind === 'GOAL_TRANSFER') {
      const amount = new Decimal(String(p.amount))
      if (available(from).lt(amount)) return fail('INSUFFICIENT_FUNDS')
      let to
      if (i.kind === 'GOAL_TRANSFER') to = db.accounts.get(String(p.toAccountId))
      else {
        const h = [...db.v2.handles.values()].find((x) => x.handle === p.toHandle)
        to = h ? db.accounts.get(h.accountId) : undefined
      }
      if (!to) return fail('NOT_FOUND')
      const toUser = [...db.users.values()].find((u) => u.user.id === to.userId)?.user
      move(from, to, amount, 'TRANSFER', (p.note as string) ?? `Transfer to ${toUser?.fullName ?? 'goal'}`, (p.note as string) ?? `Transfer from ${r.user.fullName}`, `To ${toUser?.fullName ?? ''} ${last4(to.accountNumber)}`, `From ${r.user.fullName} ${last4(from.accountNumber)}`)
      const id = crypto.randomUUID()
      db.transfers.unshift({ id, userId: r.user.id, fromAccountNumber: from.accountNumber, toAccountNumber: to.accountNumber, toName: toUser?.fullName ?? 'Goal', amount: money(amount), note: (p.note as string) ?? null, status: 'COMPLETED', createdAt: new Date().toISOString(), source: 'INTENT' })
      if (toUser && toUser.id !== r.user.id) notify(toUser.id, 'MONEY_RECEIVED', 'Money received', `${inr(amount)} from ${r.user.fullName}.`)
      i.resultRef = { type: 'TRANSFER', id }
    } else if (i.kind === 'ORDER') {
      const inst = db.instruments.get(String(p.symbol))
      if (!inst) return fail('NOT_FOUND')
      const qty = Number(p.quantity)
      const total = new Decimal(inst.price).times(qty)
      if (available(from).lt(total)) return fail('INSUFFICIENT_FUNDS')
      move(from, null, total, 'TRADE', `Bought ${qty} × ${inst.symbol}`, '')
      const h = db.holdings.find((x) => x.userId === r.user.id && x.symbol === inst.symbol)
      if (h) {
        h.avgCost = new Decimal(h.avgCost).times(h.quantity).plus(total).div(h.quantity + qty).toFixed(4)
        h.quantity += qty
      } else db.holdings.push({ userId: r.user.id, symbol: inst.symbol, quantity: qty, avgCost: new Decimal(inst.price).toFixed(4), realizedPnl: '0' })
      const id = crypto.randomUUID()
      db.orders.unshift({ id, userId: r.user.id, symbol: inst.symbol, side: 'BUY', quantity: qty, price: inst.price, total: money(total), status: 'FILLED', createdAt: new Date().toISOString() })
      i.resultRef = { type: 'ORDER', id }
    } else {
      // Same result types as the backend: the result's own type, not the intent kind.
      const type = i.kind === 'FD_BOOKING' ? 'FIXED_DEPOSIT' : i.kind === 'LOAN_APPLICATION' ? 'LOAN' : i.kind
      i.resultRef = { type, id: crypto.randomUUID() }
    }
    i.status = 'EXECUTED'
    const out = publicIntent(i)
    idem.save(200, out)
    return json(out)
  }),
]
