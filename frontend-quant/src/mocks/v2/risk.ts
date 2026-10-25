/** MSW handlers: risk and back office (§12.8): fraud cases, loan documents, underwriting, AI usage. */
import { delay, http } from 'msw'
import type { FraudCaseDetailDto, FraudCaseDto, UnderwritingDto } from '@/api/types'
import { audit, BASE, db, error, idempotent, inr, notify, paginate } from '../handlers'
import {
  addDays,
  Decimal,
  json,
  move,
  notFound,
  requireAdmin,
  requireUser,
  todayIst,
} from './common'
import { prng } from './state'

const toCase = ({
  memo: _m,
  signals: _s,
  customerBaseline: _c,
  ...c
}: FraudCaseDetailDto): FraudCaseDto => c
const ALLOWED = ['application/pdf', 'image/png', 'image/jpeg']
const MAX_BYTES = 5 * 1024 * 1024

async function sniff(file: File): Promise<string | null> {
  const b = new Uint8Array(await file.slice(0, 8).arrayBuffer())
  if (b[0] === 0x25 && b[1] === 0x50 && b[2] === 0x44 && b[3] === 0x46) return 'application/pdf'
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  return null
}

export const riskHandlers = [
  http.get(`${BASE}/admin/fraud-cases`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    return json(
      paginate(db.v2.fraudCases.filter((c) => !status || c.status === status).map(toCase), url),
    )
  }),
  http.get(`${BASE}/admin/fraud-cases/:id`, async ({ request, params }) => {
    await delay(200)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const c = db.v2.fraudCases.find((x) => x.id === params.id)
    if (!c) return notFound(request, 'Case')
    // A freshly opened case gets its investigator memo a moment later (async agent).
    if (!c.memo && Date.now() - Date.parse(c.createdAt) > 4000) {
      c.memo = {
        summary: `${c.customer.fullName} sent ${inr(c.amount)} to ${c.toName}. That is far above their usual transfer of ${inr(c.customerBaseline.avgTransfer)} and they have made ${c.customerBaseline.transfers30d} transfers in the last 30 days. No device change was seen. Worth a quick call before releasing.`,
        suggestedAction: 'CONTACT_CUSTOMER',
        confidence: 'MEDIUM',
        generatedAt: new Date().toISOString(),
        runId: crypto.randomUUID(),
      }
    }
    return json(c)
  }),
  http.post(`${BASE}/admin/fraud-cases/:id/:decision`, async ({ request, params }) => {
    await delay(400)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const c = db.v2.fraudCases.find((x) => x.id === params.id)
    if (!c) return notFound(request, 'Case')
    const b = (await request.json()) as { reason?: unknown }
    const idem = idempotent(request, r.user.id, { id: params.id, decision: params.decision, ...b })
    if (idem.early) return idem.early
    const reason = typeof b.reason === 'string' ? b.reason.trim() : ''
    if (reason.length < 5 || reason.length > 500)
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'reason', message: 'must be 5–500 characters' },
      ])
    if (c.status !== 'OPEN') return error(409, 'INVALID_STATE', 'Case already decided', request)
    const transfer = db.transfers.find((t) => t.id === c.transferId)
    const hold = transfer
      ? db.v2.holds.find(
          (h) =>
            h.reason === 'FRAUD_REVIEW' && h.status === 'ACTIVE' && h.amount === transfer.amount,
        )
      : undefined
    if (params.decision === 'release') {
      c.status = 'RELEASED'
      if (transfer && hold) {
        const from = db.accounts.get(hold.accountId)
        const to = [...db.accounts.values()].find(
          (a) => a.accountNumber === transfer.toAccountNumber,
        )
        hold.status = 'CAPTURED'
        if (from && to)
          move(
            from,
            to,
            new Decimal(transfer.amount),
            'TRANSFER',
            `Transfer to ${transfer.toName}`,
            'Transfer received',
            `To ${transfer.toName}`,
            null,
          )
        transfer.status = 'COMPLETED'
        notify(
          transfer.userId,
          'MONEY_RECEIVED',
          'Transfer released',
          `Your ${inr(transfer.amount)} transfer to ${transfer.toName} was checked and sent.`,
        )
      }
    } else if (params.decision === 'reject') {
      c.status = 'REJECTED'
      if (hold) hold.status = 'RELEASED'
      if (transfer) {
        transfer.status = 'REJECTED'
        notify(
          transfer.userId,
          'TRANSFER_UNDER_REVIEW',
          'Transfer stopped',
          `We stopped your ${inr(transfer.amount)} transfer to ${transfer.toName}. The money is back in your account.`,
        )
      }
    } else return notFound(request)
    c.decidedAt = new Date().toISOString()
    c.decisionReason = reason
    audit(
      r.user,
      params.decision === 'release' ? 'FRAUD_RELEASED' : 'FRAUD_REJECTED',
      'FRAUD_CASE',
      c.id,
      reason,
    )
    idem.save(200, toCase(c))
    return json(toCase(c))
  }),

  // Loan documents (customer upload) + underwriting (admin)
  http.post(`${BASE}/loans/:id/documents`, async ({ request, params }) => {
    await delay(600)
    const r = requireUser(request)
    if (!r.user) return r.res
    const loan = db.loans.find((l) => l.id === params.id && l.userId === r.user.id)
    if (!loan) return notFound(request, 'Loan')
    const form = await request.formData()
    if (form.get('consent') !== 'true')
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'consent', message: 'consent to AI processing is required' },
      ])
    const file = form.get('file')
    if (!(file instanceof File))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'file', message: 'attach a file' },
      ])
    if (file.size > MAX_BYTES)
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'file', message: 'must be 5 MB or smaller' },
      ])
    const type = await sniff(file)
    if (!type || !ALLOWED.includes(type))
      return error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [
        { field: 'file', message: 'must be a PDF, PNG or JPEG' },
      ])
    const docs = db.v2.loanDocs.get(loan.id) ?? []
    if (docs.length >= 3) return error(422, 'LIMIT_EXCEEDED', 'At most 3 documents', request)
    const doc = {
      id: crypto.randomUUID(),
      fileName: file.name.replace(/[^\w.\- ]+/g, '_').slice(0, 80),
      contentType: type,
      sizeBytes: file.size,
      uploadedAt: new Date().toISOString(),
    }
    db.v2.loanDocs.set(loan.id, [...docs, doc])
    return json(doc, 201)
  }),
  http.get(`${BASE}/loans/:id/documents`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const loan = db.loans.find(
      (l) => l.id === params.id && (l.userId === r.user.id || r.user.role === 'ADMIN'),
    )
    if (!loan) return notFound(request, 'Loan')
    return json(db.v2.loanDocs.get(loan.id) ?? [])
  }),
  http.get(`${BASE}/admin/loans/:id/underwriting`, async ({ request, params }) => {
    await delay(300)
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const loan = db.loans.find((l) => l.id === params.id)
    if (!loan) return notFound(request, 'Loan')
    const docs = db.v2.loanDocs.get(loan.id) ?? []
    const income = new Decimal(loan.monthlyIncome)
    const seeded = loan.status === 'PENDING'
    if (!docs.length && !seeded)
      return json<UnderwritingDto>({
        status: 'PENDING',
        memo: null,
        extracted: [],
        flags: ['No documents uploaded yet.'],
        recommendation: null,
        generatedAt: null,
      })
    const doc = docs[0]?.id
    const runId = `ua-${loan.id}`
    if (!db.v2.traces.has(runId))
      db.v2.traces.set(runId, {
        runId,
        startedAt: new Date(Date.now() - 3 * 60_000).toISOString(),
        totalMs: 2140,
        costUsd: '0.00',
        steps: [
          {
            agent: 'UNDERWRITING_ANALYST',
            kind: 'MODEL',
            name: 'write_memo',
            status: 'OK',
            latencyMs: 2140,
            inputTokens: 6200,
            outputTokens: 540,
            detail: 'Read 2 pages; 4 figures, each checked against its page.',
          },
        ],
      })
    const rnd = prng(loan.amount.length * 977)
    const stated = income.times(0.93 + rnd() * 0.05).toDecimalPlaces(0)
    const gap = income.minus(stated).div(income).times(100).toDecimalPlaces(1)
    const emiRatio = new Decimal(loan.emi).div(stated).times(100).toDecimalPlaces(1)
    return json<UnderwritingDto>({
      status: 'READY',
      memo: `Salary slips show a net monthly income of about ${inr(stated)}, ${gap.toString()}% below the declared ${inr(income)} (within normal deductions). Existing obligations: one consumer EMI of ₹4,200. The requested EMI of ${inr(loan.emi)} is ${emiRatio.toString()}% of verified income, inside the 50% rule. Employment appears stable (same employer for 3 slips). Recommend approval; consider asking for one more month of statements if the amount is above ₹5,00,000.`,
      extracted: [
        { field: 'EMPLOYER', value: 'ACME Technologies Pvt Ltd', page: 1, documentId: doc },
        { field: 'MONTHLY_NET_INCOME', value: inr(stated), page: 1, documentId: doc },
        { field: 'MONTHLY_NET_INCOME', value: inr(stated.plus(310)), page: 2, documentId: doc },
        { field: 'EXISTING_EMI', value: '₹4,200.00', page: 2, documentId: doc },
      ],
      flags: gap.gt(10) ? ['Verified income is more than 10% below the declared income.'] : [],
      recommendation: emiRatio.gt(50) ? 'REJECT' : gap.gt(10) ? 'REVIEW' : 'APPROVE',
      generatedAt: new Date(Date.now() - 3 * 60_000).toISOString(),
      runId,
    })
  }),

  // AI usage (admin)
  http.get(`${BASE}/admin/ai/usage`, ({ request }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const rnd = prng(31)
    const byDay = Array.from({ length: 30 }, (_, i) => {
      const turns = Math.round(240 + rnd() * 160 + i * 4)
      return { date: addDays(todayIst(), i - 29), costUsd: (turns * 0.0123).toFixed(2), turns }
    })
    const cost = byDay.reduce((s, d) => s.plus(d.costUsd), new Decimal(0))
    return json({
      tokens: 19_630_000,
      costUsd: cost.toFixed(2),
      byAgent: [
        { agent: 'CONCIERGE', runs: 9120, costUsd: cost.times(0.46).toFixed(2) },
        { agent: 'LEDGER_ANALYST', runs: 5210, costUsd: cost.times(0.14).toFixed(2) },
        { agent: 'PAYMENTS', runs: 2140, costUsd: cost.times(0.09).toFixed(2) },
        { agent: 'MARKETS', runs: 1730, costUsd: cost.times(0.08).toFixed(2) },
        { agent: 'PLANNER', runs: 980, costUsd: cost.times(0.12).toFixed(2) },
        { agent: 'LOAN_ADVISOR', runs: 610, costUsd: cost.times(0.04).toFixed(2) },
        { agent: 'FRAUD_INVESTIGATOR', runs: 41, costUsd: cost.times(0.04).toFixed(2) },
        { agent: 'UNDERWRITING_ANALYST', runs: 23, costUsd: cost.times(0.03).toFixed(2) },
      ],
      byDay,
      budget: {
        monthlyCapUsd: '250.00',
        usedPct: cost.div(250).times(100).toDecimalPlaces(2).toFixed(2),
      },
    })
  }),
  http.get(`${BASE}/admin/ai/runs/:runId`, ({ request, params }) => {
    const r = requireAdmin(request)
    if (!r.user) return r.res
    const t = db.v2.traces.get(String(params.runId))
    return t ? json(t) : notFound(request, 'Run')
  }),
]
