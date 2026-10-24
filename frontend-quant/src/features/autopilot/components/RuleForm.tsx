import { Plus, Trash2 } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useAccounts, useBeneficiaries, useInstruments } from '@/api/queries'
import type {
  AccountDto,
  Category,
  RuleAction,
  RuleAmount,
  RuleCondition,
  RuleDsl,
  RuleTrigger,
  Weekday,
} from '@/api/types'
import { Button } from '@/components/ui/button'
import { FieldMessage, Input, Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { accountLabel } from '@/features/accounts/labels'
import { useSavedBillers } from '@/features/payments/hooks'
import { sanitizeAmount } from '@/lib/amount'
import { BUDGETABLE, categoryOf } from '@/lib/categories'
import { maskShort } from '@/lib/mask'
import {
  ACTION_LABEL,
  ACTION_OPTIONS,
  amountModes,
  defaultAction,
  defaultTrigger,
  spendable,
  TRIGGER_LABEL,
  TRIGGER_OPTIONS,
  WEEKDAYS,
  type FieldErrorIndex,
} from '../rules'

const MAX_ACTIONS = 5
const MAX_CONDITIONS = 3

/**
 * The structured rule editor (F-AUTO-2): every DSL v1 trigger, condition and action as a form.
 * It only edits a draft; the server validates it and renders the sentence the user approves.
 */
export function RuleForm({
  rule,
  onChange,
  errors,
}: {
  rule: RuleDsl
  onChange: (rule: RuleDsl) => void
  errors: FieldErrorIndex
}) {
  const accounts = useAccounts().data ?? []
  const set = (patch: Partial<RuleDsl>) => onChange({ ...rule, ...patch })
  const setAction = (i: number, a: RuleAction) =>
    set({ actions: rule.actions.map((x, j) => (j === i ? a : x)) })
  const setCondition = (i: number, c: RuleCondition) =>
    set({ conditions: rule.conditions.map((x, j) => (j === i ? c : x)) })

  return (
    <div className="space-y-6">
      <Section
        step="1"
        title="When"
        error={
          errors.at('trigger') && !hasSub(errors, 'trigger') ? errors.at('trigger') : undefined
        }
      >
        <TriggerEditor
          trigger={rule.trigger}
          accounts={accounts}
          errors={errors}
          onChange={(trigger) => {
            // Amount modes depend on the trigger; fall back to a fixed amount when one no longer fits.
            const modes = amountModes(trigger).map((m) => m.value)
            const fix = (a: RuleAction): RuleAction =>
              'amount' in a && !modes.includes(a.amount.mode)
                ? { ...a, amount: { mode: 'FIXED', value: '' } }
                : a
            set({ trigger, actions: rule.actions.map(fix) })
          }}
        />
      </Section>

      <Section step="2" title="Only if" optional>
        <div className="space-y-3">
          {rule.conditions.map((c, i) => (
            <div key={i} className="flex items-end gap-2">
              <div className="grid flex-1 gap-3 sm:grid-cols-2">
                <AccountField
                  label="Balance of"
                  value={c.accountId}
                  accounts={accounts.filter(
                    (a) => a.status === 'ACTIVE' && a.type !== 'FIXED_DEPOSIT',
                  )}
                  onChange={(accountId) => setCondition(i, { ...c, accountId })}
                  error={errors.at(`conditions[${i}].accountId`)}
                />
                <MoneyField
                  label="is at least"
                  value={c.amount}
                  onChange={(amount) => setCondition(i, { ...c, amount })}
                  error={errors.at(`conditions[${i}].amount`)}
                />
              </div>
              <RemoveButton
                label="Remove condition"
                onClick={() => set({ conditions: rule.conditions.filter((_, j) => j !== i) })}
              />
            </div>
          ))}
          {rule.conditions.length < MAX_CONDITIONS && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                set({
                  conditions: [
                    ...rule.conditions,
                    {
                      type: 'BALANCE_AT_LEAST',
                      accountId: spendable(accounts)[0]?.id ?? '',
                      amount: '5000.00',
                    },
                  ],
                })
              }
            >
              <Plus aria-hidden="true" /> Add a balance check
            </Button>
          )}
        </div>
      </Section>

      <Section
        step="3"
        title="Then"
        error={
          errors.at('actions') && !hasSub(errors, 'actions') ? errors.at('actions') : undefined
        }
      >
        <ol className="space-y-3">
          {rule.actions.map((a, i) => (
            <li key={i} className="rounded-[var(--radius-control)] bg-surface-2/50 p-3.5 hairline">
              <div className="mb-3 flex items-center gap-2">
                <span className="grid size-6 shrink-0 place-items-center rounded-full bg-violet/15 text-xs font-medium text-violet">
                  {i + 1}
                </span>
                <Select
                  aria-label="Action type"
                  value={a.type}
                  onValueChange={(t) =>
                    setAction(i, defaultAction(t as RuleAction['type'], accounts))
                  }
                  options={ACTION_OPTIONS.map((value) => ({
                    value,
                    label: ACTION_LABEL[value],
                    text: ACTION_LABEL[value],
                  }))}
                  className="h-9"
                />
                {rule.actions.length > 1 && (
                  <RemoveButton
                    label={`Remove action ${i + 1}`}
                    onClick={() => set({ actions: rule.actions.filter((_, j) => j !== i) })}
                  />
                )}
              </div>
              <ActionEditor
                action={a}
                index={i}
                trigger={rule.trigger}
                accounts={accounts}
                errors={errors}
                onChange={(x) => setAction(i, x)}
              />
            </li>
          ))}
        </ol>
        {rule.actions.length < MAX_ACTIONS && (
          <Button
            variant="ghost"
            size="sm"
            className="mt-2"
            onClick={() => set({ actions: [...rule.actions, defaultAction('TRANSFER', accounts)] })}
          >
            <Plus aria-hidden="true" /> Add another action
          </Button>
        )}
      </Section>

      <Section
        step="4"
        title="Never more than"
        error={errors.at('caps') && !hasSub(errors, 'caps') ? errors.at('caps') : undefined}
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <MoneyField
            label="Per run"
            value={rule.caps.perRun}
            onChange={(perRun) => set({ caps: { ...rule.caps, perRun } })}
            error={errors.at('caps.perRun')}
          />
          <MoneyField
            label="Per month"
            value={rule.caps.perMonth}
            onChange={(perMonth) => set({ caps: { ...rule.caps, perMonth } })}
            error={errors.at('caps.perMonth')}
          />
        </div>
        <p className="mt-2 text-xs text-muted">
          Autopilot stops for the run or the month once a cap is reached, and tells you.
        </p>
      </Section>
    </div>
  )
}

const hasSub = (errors: FieldErrorIndex, path: string) =>
  errors.all.some((e) => e.path.startsWith(`${path}.`) || e.path.startsWith(`${path}[`))

function TriggerEditor({
  trigger: t,
  accounts,
  errors,
  onChange,
}: {
  trigger: RuleTrigger
  accounts: AccountDto[]
  errors: FieldErrorIndex
  onChange: (t: RuleTrigger) => void
}) {
  const instruments = useInstruments().data ?? []
  const symbols = instruments.map((i) => ({
    value: i.symbol,
    label: `${i.symbol} · ${i.name}`,
    text: i.symbol,
  }))
  const e = (k: string) => errors.at(`trigger.${k}`)
  return (
    <div className="space-y-3">
      <Field label="Trigger">
        {(id) => (
          <Select
            id={id}
            value={t.type}
            onValueChange={(type) =>
              onChange(defaultTrigger(type as RuleTrigger['type'], accounts))
            }
            options={TRIGGER_OPTIONS.map((value) => ({
              value,
              label: TRIGGER_LABEL[value],
              text: TRIGGER_LABEL[value],
            }))}
          />
        )}
      </Field>
      {t.type === 'MONEY_RECEIVED' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="Into"
            value={t.accountId}
            accounts={accounts.filter((a) => a.status === 'ACTIVE' && a.type !== 'FIXED_DEPOSIT')}
            onChange={(accountId) => onChange({ ...t, accountId })}
            error={e('accountId')}
          />
          <MoneyField
            label="Of at least (optional)"
            value={t.minAmount ?? ''}
            onChange={(minAmount) => onChange({ ...t, minAmount: minAmount || undefined })}
            error={e('minAmount')}
          />
          <TextField
            label="From someone whose name contains (optional)"
            value={t.counterpartyContains ?? ''}
            maxLength={40}
            placeholder="e.g. ACME"
            onChange={(v) => onChange({ ...t, counterpartyContains: v || undefined })}
            error={e('counterpartyContains')}
            className="sm:col-span-2"
          />
        </div>
      )}
      {t.type === 'SCHEDULE' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Every">
            {(id) => (
              <Select
                id={id}
                value={t.frequency}
                onValueChange={(f) =>
                  onChange(
                    f === 'MONTHLY'
                      ? { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: 1 }
                      : { type: 'SCHEDULE', frequency: 'WEEKLY', weekday: 'MON' },
                  )
                }
                options={[
                  { value: 'MONTHLY', label: 'Month', text: 'Month' },
                  { value: 'WEEKLY', label: 'Week', text: 'Week' },
                ]}
              />
            )}
          </Field>
          {t.frequency === 'MONTHLY' ? (
            <Field label="On day" error={e('dayOfMonth')}>
              {(id) => (
                <Select
                  id={id}
                  value={String(t.dayOfMonth ?? 1)}
                  onValueChange={(d) => onChange({ ...t, dayOfMonth: Number.parseInt(d, 10) })}
                  options={Array.from({ length: 28 }, (_, i) => ({
                    value: String(i + 1),
                    label: String(i + 1),
                    text: String(i + 1),
                  }))}
                />
              )}
            </Field>
          ) : (
            <Field label="On" error={e('weekday')}>
              {(id) => (
                <Select
                  id={id}
                  value={t.weekday ?? 'MON'}
                  onValueChange={(w) => onChange({ ...t, weekday: w as Weekday })}
                  options={WEEKDAYS.map((w) => ({ value: w.value, label: w.label, text: w.label }))}
                />
              )}
            </Field>
          )}
        </div>
      )}
      {t.type === 'BALANCE_ABOVE' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="Balance of"
            value={t.accountId}
            accounts={accounts.filter((a) => a.status === 'ACTIVE' && a.type !== 'FIXED_DEPOSIT')}
            onChange={(accountId) => onChange({ ...t, accountId })}
            error={e('accountId')}
          />
          <MoneyField
            label="Goes above"
            value={t.threshold}
            onChange={(threshold) => onChange({ ...t, threshold })}
            error={e('threshold')}
          />
        </div>
      )}
      {t.type === 'PRICE_CROSSES' && (
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Stock" error={e('symbol')}>
            {(id) => (
              <Select
                id={id}
                value={t.symbol}
                onValueChange={(symbol) => onChange({ ...t, symbol })}
                options={symbols}
              />
            )}
          </Field>
          <Field label="Goes">
            {(id) => (
              <Select
                id={id}
                value={t.direction}
                onValueChange={(d) => onChange({ ...t, direction: d as 'ABOVE' | 'BELOW' })}
                options={[
                  { value: 'BELOW', label: 'Below', text: 'Below' },
                  { value: 'ABOVE', label: 'Above', text: 'Above' },
                ]}
              />
            )}
          </Field>
          <MoneyField
            label="Price"
            value={t.price}
            onChange={(price) => onChange({ ...t, price })}
            error={e('price')}
          />
        </div>
      )}
      {t.type === 'BUDGET_THRESHOLD' && (
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Budget" error={e('category')}>
            {(id) => (
              <Select
                id={id}
                value={t.category}
                onValueChange={(c) => onChange({ ...t, category: c as Category })}
                options={BUDGETABLE.map((c) => ({
                  value: c,
                  label: categoryOf(c).label,
                  text: categoryOf(c).label,
                }))}
              />
            )}
          </Field>
          <Field label="Reaches" error={e('percent')}>
            {(id) => (
              <Select
                id={id}
                value={String(t.percent)}
                onValueChange={(p) => onChange({ ...t, percent: Number.parseInt(p, 10) })}
                options={[50, 75, 80, 90, 100, 120, 150, 200].map((p) => ({
                  value: String(p),
                  label: `${p}%`,
                  text: `${p}%`,
                }))}
              />
            )}
          </Field>
        </div>
      )}
    </div>
  )
}

function ActionEditor({
  action: a,
  index,
  trigger,
  accounts,
  errors,
  onChange,
}: {
  action: RuleAction
  index: number
  trigger: RuleTrigger
  accounts: AccountDto[]
  errors: FieldErrorIndex
  onChange: (a: RuleAction) => void
}) {
  const payees = useBeneficiaries().data ?? []
  const instruments = useInstruments().data ?? []
  const savedBillers = useSavedBillers(a.type === 'PAY_BILL').data ?? []
  const e = (k: string) => errors.at(`actions[${index}].${k}`)
  const own = accounts.filter((x) => x.status === 'ACTIVE' && x.type !== 'FIXED_DEPOSIT')

  switch (a.type) {
    case 'TRANSFER': {
      const dest = a.toBeneficiaryId
        ? `payee:${a.toBeneficiaryId}`
        : a.toAccountId
          ? `acct:${a.toAccountId}`
          : ''
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="From"
            value={a.fromAccountId ?? ''}
            accounts={spendable(accounts)}
            onChange={(fromAccountId) => onChange({ ...a, fromAccountId })}
            error={e('fromAccountId')}
          />
          <Field
            label="To"
            error={
              e('toAccountId') ??
              e('toBeneficiaryId') ??
              errors.all.find((x) => x.path === `actions[${index}]`)?.message
            }
          >
            {(id) => (
              <Select
                id={id}
                value={dest}
                placeholder="Choose where it goes"
                onValueChange={(v) => {
                  const [kind, val] = v.split(':') as ['acct' | 'payee', string]
                  onChange({
                    ...a,
                    toAccountId: kind === 'acct' ? val : undefined,
                    toBeneficiaryId: kind === 'payee' ? val : undefined,
                  })
                }}
                options={[
                  ...own
                    .filter((x) => x.id !== a.fromAccountId)
                    .map((x) => ({
                      value: `acct:${x.id}`,
                      label: accountLabel(x),
                      text: accountLabel(x),
                    })),
                  ...payees.map((p) => ({
                    value: `payee:${p.id}`,
                    label: `${p.nickname} · ${maskShort(p.accountNumber)}`,
                    text: p.nickname,
                  })),
                ]}
              />
            )}
          </Field>
          <AmountEditor
            amount={a.amount}
            trigger={trigger}
            onChange={(amount) => onChange({ ...a, amount })}
            error={e('amount')}
          />
        </div>
      )
    }
    case 'BUY':
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="Pay from"
            value={a.accountId}
            accounts={spendable(accounts)}
            onChange={(accountId) => onChange({ ...a, accountId })}
            error={e('accountId')}
          />
          <Field label="Stock" error={e('symbol')}>
            {(id) => (
              <Select
                id={id}
                value={a.symbol}
                onValueChange={(symbol) => onChange({ ...a, symbol })}
                options={instruments.map((i) => ({
                  value: i.symbol,
                  label: `${i.symbol} · ${i.name}`,
                  text: i.symbol,
                }))}
              />
            )}
          </Field>
          <AmountEditor
            amount={a.amount}
            trigger={trigger}
            onChange={(amount) => onChange({ ...a, amount })}
            error={e('amount')}
          />
        </div>
      )
    case 'PAY_BILL':
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="Pay from"
            value={a.fromAccountId}
            accounts={spendable(accounts)}
            onChange={(fromAccountId) => onChange({ ...a, fromAccountId })}
            error={e('fromAccountId')}
          />
          <Field label="Saved biller" error={e('savedBillerId')}>
            {(id) => (
              <Select
                id={id}
                value={a.savedBillerId}
                placeholder={savedBillers.length ? 'Choose a saved biller' : 'No saved billers yet'}
                onValueChange={(savedBillerId) => onChange({ ...a, savedBillerId })}
                options={savedBillers.map((b) => {
                  const text = `${b.nickname ?? b.billerName} · ${b.consumerNumberMasked}`
                  return { value: b.id, label: text, text }
                })}
              />
            )}
          </Field>
          <p className="text-xs text-muted sm:col-span-2">
            Pays this month’s bill if it isn’t paid yet. Save a biller on{' '}
            <Link to="/pay/bills" className="underline underline-offset-2">
              Bills
            </Link>{' '}
            first.
          </p>
        </div>
      )
    case 'ROUND_UP_SWEEP':
      return (
        <div className="grid gap-3 sm:grid-cols-2">
          <AccountField
            label="Round up spends from"
            value={a.fromAccountId}
            accounts={spendable(accounts)}
            onChange={(fromAccountId) => onChange({ ...a, fromAccountId })}
            error={e('fromAccountId')}
          />
          <AccountField
            label="Move the change to"
            value={a.toAccountId}
            accounts={own.filter((x) => x.id !== a.fromAccountId)}
            onChange={(toAccountId) => onChange({ ...a, toAccountId })}
            error={e('toAccountId')}
          />
          <Field label="Round to the next" error={e('roundTo')}>
            {(id) => (
              <Select
                id={id}
                value={String(a.roundTo)}
                onValueChange={(v) => onChange({ ...a, roundTo: Number(v) as 10 | 50 | 100 })}
                options={[10, 50, 100].map((n) => ({
                  value: String(n),
                  label: `₹${n}`,
                  text: `₹${n}`,
                }))}
              />
            )}
          </Field>
          {/* The server refuses this action on any trigger but a schedule (path rule.actions[i].type). */}
          <p className="self-end text-xs text-muted sm:pb-2">
            Runs on a schedule trigger only. Sweeps spends since the previous run.
          </p>
          <div className="sm:col-span-2">
            <FieldMessage id={`round-up-${index}-error`}>{e('type')}</FieldMessage>
          </div>
        </div>
      )
    case 'NOTIFY':
      return (
        <TextField
          label="Message"
          value={a.message}
          maxLength={140}
          placeholder="e.g. Salary’s in, budgets reset"
          onChange={(message) => onChange({ ...a, message })}
          error={e('message')}
        />
      )
    default:
      return null
  }
}

function AmountEditor({
  amount,
  trigger,
  onChange,
  error,
}: {
  amount: RuleAmount
  trigger: RuleTrigger
  onChange: (a: RuleAmount) => void
  error?: string
}) {
  const modes = amountModes(trigger)
  return (
    <>
      <Field label="How much">
        {(id) => (
          <Select
            id={id}
            value={amount.mode}
            onValueChange={(mode) =>
              onChange(
                mode === 'EXCESS_ABOVE' && trigger.type === 'BALANCE_ABOVE'
                  ? { mode, value: trigger.threshold }
                  : ({ mode, value: '' } as RuleAmount),
              )
            }
            options={modes.map((m) => ({ value: m.value, label: m.text, text: m.text }))}
          />
        )}
      </Field>
      {amount.mode === 'PERCENT_OF_TRIGGER' ? (
        <TextField
          label="Percent"
          value={amount.value}
          inputMode="decimal"
          suffix="%"
          onChange={(v) => onChange({ ...amount, value: v.replace(/[^\d.]/g, '').slice(0, 5) })}
          error={error}
        />
      ) : amount.mode === 'EXCESS_ABOVE' ? (
        <MoneyField
          label="Keep at least"
          value={amount.value}
          onChange={(value) => onChange({ ...amount, value })}
          error={error}
        />
      ) : (
        <MoneyField
          label="Amount"
          value={amount.value}
          onChange={(value) => onChange({ ...amount, value })}
          error={error}
        />
      )}
    </>
  )
}

// ── small form primitives ────────────────────────────────────────────────────────────

function Section({
  step,
  title,
  optional,
  error,
  children,
}: {
  step: string
  title: string
  optional?: boolean
  error?: string
  children: ReactNode
}) {
  const id = useId()
  return (
    <section aria-labelledby={id}>
      <h3 id={id} className="mb-3 flex items-center gap-2 text-sm font-medium">
        <span className="eyebrow">{step}</span> {title}
        {optional && <span className="text-xs font-normal text-muted">(optional)</span>}
      </h3>
      {children}
      {error && (
        <p role="alert" className="mt-2 text-sm text-loss">
          {error}
        </p>
      )}
    </section>
  )
}

function Field({
  label,
  error,
  className,
  children,
}: {
  label: string
  error?: string
  className?: string
  children: (id: string) => ReactNode
}) {
  const id = useId()
  return (
    <div className={className ? `space-y-1.5 ${className}` : 'space-y-1.5'}>
      <Label htmlFor={id}>{label}</Label>
      {children(id)}
      <FieldMessage id={`${id}-error`}>{error}</FieldMessage>
    </div>
  )
}

function AccountField({
  label,
  value,
  accounts,
  onChange,
  error,
}: {
  label: string
  value: string
  accounts: AccountDto[]
  onChange: (id: string) => void
  error?: string
}) {
  return (
    <Field label={label} error={error}>
      {(id) => (
        <Select
          id={id}
          value={value}
          placeholder="Choose an account"
          invalid={!!error}
          onValueChange={onChange}
          options={accounts.map((a) => ({
            value: a.id,
            label: accountLabel(a),
            text: accountLabel(a),
          }))}
        />
      )}
    </Field>
  )
}

function MoneyField({
  label,
  value,
  onChange,
  error,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  error?: string
}) {
  return (
    <TextField
      label={label}
      value={value}
      inputMode="decimal"
      prefix="₹"
      onChange={(v) => onChange(sanitizeAmount(v))}
      error={error}
    />
  )
}

function TextField({
  label,
  value,
  onChange,
  error,
  prefix,
  suffix,
  className,
  ...rest
}: {
  label: string
  value: string
  onChange: (v: string) => void
  error?: string
  prefix?: string
  suffix?: string
  className?: string
  maxLength?: number
  placeholder?: string
  inputMode?: 'decimal' | 'text'
}) {
  return (
    <Field label={label} error={error} className={className}>
      {(id) => (
        <div className="relative">
          {prefix && (
            <span
              aria-hidden="true"
              className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted"
            >
              {prefix}
            </span>
          )}
          <Input
            id={id}
            value={value}
            onChange={(ev) => onChange(ev.target.value)}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-error` : undefined}
            className={`${prefix ? 'pl-8' : ''} ${suffix ? 'pr-8' : ''}`}
            {...rest}
          />
          {suffix && (
            <span
              aria-hidden="true"
              className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-muted"
            >
              {suffix}
            </span>
          )}
        </div>
      )}
    </Field>
  )
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label={label}
      onClick={onClick}
      className="ml-auto shrink-0 text-muted"
    >
      <Trash2 aria-hidden="true" />
    </Button>
  )
}
