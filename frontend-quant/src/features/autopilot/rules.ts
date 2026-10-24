import type { AccountDto, FieldError, RuleAction, RuleAmount, RuleDsl, RuleRunOutcome, RuleTrigger, Weekday } from '@/api/types'
import { backendHas } from '@/api/capabilities'
import type { BadgeTone } from '@/components/ui/badge'
import { AMOUNT_INPUT_RE, toDecimal } from '@/lib/money'

export const TRIGGER_LABEL: Record<RuleTrigger['type'], string> = {
  MONEY_RECEIVED: 'Money arrives',
  SCHEDULE: 'On a schedule',
  BALANCE_ABOVE: 'Balance goes above',
  PRICE_CROSSES: 'A stock price crosses',
  BUDGET_THRESHOLD: 'A budget reaches',
}

export const ACTION_LABEL: Record<RuleAction['type'], string> = {
  TRANSFER: 'Move money',
  BUY: 'Buy a stock',
  PAY_BILL: 'Pay a bill',
  ROUND_UP_SWEEP: 'Sweep round-ups',
  NOTIFY: 'Notify me',
}

/**
 * What the live engine runs (backend V2-M5 core): an older backend answers 422 RULE_INVALID for the
 * rest, so the form offers them only once their milestone ships (or when every request is mocked).
 * 'V2-M5-extended' (backend f5ab093) = BUDGET_THRESHOLD + ROUND_UP_SWEEP; 'V2-M5-bills' = PAY_BILL
 * on saved billers.
 */
const EXTENDED = backendHas('V2-M5-extended')
const BILLS = backendHas('V2-M5-bills')
export const TRIGGER_OPTIONS = (Object.keys(TRIGGER_LABEL) as Array<RuleTrigger['type']>).filter((t) => EXTENDED || t !== 'BUDGET_THRESHOLD')
export const ACTION_OPTIONS = (Object.keys(ACTION_LABEL) as Array<RuleAction['type']>).filter(
  (a) => (EXTENDED || a !== 'ROUND_UP_SWEEP') && (BILLS || a !== 'PAY_BILL'),
)

export const WEEKDAYS: Array<{ value: Weekday; label: string }> = [
  { value: 'MON', label: 'Monday' },
  { value: 'TUE', label: 'Tuesday' },
  { value: 'WED', label: 'Wednesday' },
  { value: 'THU', label: 'Thursday' },
  { value: 'FRI', label: 'Friday' },
  { value: 'SAT', label: 'Saturday' },
  { value: 'SUN', label: 'Sunday' },
]

export const OUTCOME: Record<RuleRunOutcome, { label: string; tone: BadgeTone }> = {
  SUCCEEDED: { label: 'Done', tone: 'gain' },
  PARTIAL: { label: 'Partly done', tone: 'warn' },
  FAILED: { label: 'Failed', tone: 'loss' },
  SKIPPED_CAP: { label: 'Skipped: cap reached', tone: 'neutral' },
}

/** Accounts money can leave from (an Autopilot source). */
export const spendable = (accounts: AccountDto[]) =>
  accounts.filter((a) => a.status === 'ACTIVE' && (a.type === 'SAVINGS' || a.type === 'CHECKING'))
export const goals = (accounts: AccountDto[]) => accounts.filter((a) => a.status === 'ACTIVE' && a.type === 'GOAL')

/** A starting rule for "build it myself": ₹5,000 into a goal on the 1st of each month. */
export function blankRule(accounts: AccountDto[]): RuleDsl {
  const from = spendable(accounts)[0]?.id
  const to = goals(accounts)[0]?.id ?? spendable(accounts)[1]?.id
  return {
    dslVersion: 1,
    trigger: { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: 1 },
    conditions: [],
    actions: [{ type: 'TRANSFER', fromAccountId: from, toAccountId: to, amount: { mode: 'FIXED', value: '5000.00' } }],
    caps: { perRun: '5000.00', perMonth: '5000.00' },
  }
}

export function defaultTrigger(type: RuleTrigger['type'], accounts: AccountDto[]): RuleTrigger {
  const acc = spendable(accounts)[0]?.id ?? ''
  switch (type) {
    case 'MONEY_RECEIVED':
      return { type, accountId: acc, minAmount: '40000.00' }
    case 'SCHEDULE':
      return { type, frequency: 'MONTHLY', dayOfMonth: 1 }
    case 'BALANCE_ABOVE':
      return { type, accountId: acc, threshold: '50000.00' }
    case 'PRICE_CROSSES':
      return { type, symbol: 'QGEN', direction: 'BELOW', price: '1000.00' }
    case 'BUDGET_THRESHOLD':
      return { type, category: 'FOOD', percent: 80 }
  }
}

export function defaultAction(type: RuleAction['type'], accounts: AccountDto[]): RuleAction {
  const from = spendable(accounts)[0]?.id ?? ''
  const goal = goals(accounts)[0]?.id ?? ''
  switch (type) {
    case 'TRANSFER':
      return { type, fromAccountId: from, toAccountId: goal || undefined, amount: { mode: 'FIXED', value: '1000.00' } }
    case 'BUY':
      return { type, accountId: from, symbol: 'QGEN', amount: { mode: 'FIXED', value: '5000.00' } }
    case 'PAY_BILL':
      return { type, savedBillerId: '', fromAccountId: from }
    case 'ROUND_UP_SWEEP':
      return { type, fromAccountId: from, toAccountId: goal || (spendable(accounts)[1]?.id ?? ''), roundTo: 10 }
    case 'NOTIFY':
      return { type, message: '' }
  }
}

/** Which amount modes make sense for a trigger (the server re-checks). */
export function amountModes(trigger: RuleTrigger): Array<{ value: RuleAmount['mode']; text: string }> {
  return [
    { value: 'FIXED', text: 'A fixed amount' },
    ...(trigger.type === 'MONEY_RECEIVED' ? [{ value: 'PERCENT_OF_TRIGGER' as const, text: '% of the money received' }] : []),
    ...(trigger.type === 'BALANCE_ABOVE' ? [{ value: 'EXCESS_ABOVE' as const, text: 'Everything above the threshold' }] : []),
  ]
}

/** "5000" → "5000.00" for money fields the user typed; anything invalid is left for the server to reject. */
const money2 = (v: string | undefined) => (v !== undefined && AMOUNT_INPUT_RE.test(v) ? toDecimal(v).toFixed(2) : v)

/** Canonical form before sending: money with 2 decimals, empty optionals removed. Pure. */
export function normalizeRule(r: RuleDsl): RuleDsl {
  const t = r.trigger
  const trigger: RuleTrigger =
    t.type === 'MONEY_RECEIVED'
      ? {
          type: t.type,
          accountId: t.accountId,
          ...(t.minAmount ? { minAmount: money2(t.minAmount) } : {}),
          ...(t.counterpartyContains?.trim() ? { counterpartyContains: t.counterpartyContains.trim() } : {}),
          ...(t.transactionType ? { transactionType: t.transactionType } : {}),
        }
      : t.type === 'BALANCE_ABOVE'
        ? { ...t, threshold: money2(t.threshold) ?? '' }
        : t.type === 'PRICE_CROSSES'
          ? { ...t, price: money2(t.price) ?? '' }
          : t.type === 'SCHEDULE'
            ? t.frequency === 'MONTHLY'
              ? { type: t.type, frequency: t.frequency, dayOfMonth: t.dayOfMonth ?? 1 }
              : { type: t.type, frequency: t.frequency, weekday: t.weekday ?? 'MON' }
            : t
  const amount = (a: RuleAmount): RuleAmount => (a.mode === 'PERCENT_OF_TRIGGER' ? a : { ...a, value: money2(a.value) ?? '' })
  const actions = r.actions.map((a): RuleAction => {
    if (a.type === 'TRANSFER') {
      const { toAccountId, toBeneficiaryId, ...rest } = a
      return { ...rest, ...(toBeneficiaryId ? { toBeneficiaryId } : { toAccountId }), amount: amount(a.amount) }
    }
    if (a.type === 'BUY') return { ...a, amount: amount(a.amount) }
    if (a.type === 'NOTIFY') return { ...a, message: a.message.trim() }
    return a
  })
  return {
    dslVersion: 1,
    trigger,
    conditions: r.conditions.map((c) => ({ ...c, amount: money2(c.amount) ?? '' })),
    actions,
    caps: { perRun: money2(r.caps.perRun) ?? '', perMonth: money2(r.caps.perMonth) ?? '' },
  }
}

/**
 * Server field errors point into the DSL (`rule.actions[0].amount`, PRD §12.1 RULE_INVALID).
 * `at(path)` returns the message for that field or anything under it.
 */
export function fieldErrorIndex(errors: FieldError[]) {
  const norm = (f: string) => f.replace(/^rule\./, '')
  const list = errors.map((e) => ({ path: norm(e.field), message: e.message }))
  return {
    at: (path: string) => list.find((e) => e.path === path || e.path.startsWith(`${path}.`))?.message,
    all: list,
  }
}
export type FieldErrorIndex = ReturnType<typeof fieldErrorIndex>
