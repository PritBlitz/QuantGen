import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import {
  ArrowLeft,
  CircleAlert,
  History,
  Loader2,
  PenLine,
  Power,
  Sparkles,
  Wand2,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { autopilotApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import { useAccounts } from '@/api/queries'
import type { RuleDsl, TemplateDto } from '@/api/types'
import { FormField } from '@/components/FormField'
import { PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { RuleForm } from './components/RuleForm'
import { SimulationView } from './components/SimulationView'
import { useRule, useRuleMutation, useTemplates } from './hooks'
import { blankRule, fieldErrorIndex, normalizeRule } from './rules'

const DAYS = 90

/** `/autopilot/new` and `/autopilot/:id/edit` (F-AUTO-1…4). Loads what the builder needs, then mounts it. */
export function RuleBuilderPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const accounts = useAccounts()
  const templates = useTemplates()
  const existing = useRule(id ?? '', !!id)
  const templateId = params.get('template')

  const ready =
    accounts.data && (!id || existing.data) && (!templateId || templates.data || templates.isError)
  const failed = accounts.isError || (id && existing.isError)

  return (
    <>
      <PageHeader
        back={{
          to: id ? `/autopilot/${id}` : '/autopilot',
          label: id ? 'Back to rule' : 'Autopilot',
        }}
        eyebrow={id ? 'Edit rule' : 'New rule'}
        title={id ? (existing.data?.name ?? 'Edit rule') : 'Teach Autopilot a rule'}
        description="Describe it in your own words or build it step by step. You’ll see exactly what it will do, and what it would have done over the last 90 days, before it’s switched on."
      />
      {failed ? (
        <PanelError
          what="this rule"
          onRetry={() => void (accounts.isError ? accounts.refetch() : existing.refetch())}
        />
      ) : !ready ? (
        <Skeleton className="h-96" />
      ) : (
        <Builder
          key={id ?? templateId ?? 'new'}
          ruleId={id ?? null}
          initialRule={existing.data?.rule ?? null}
          initialName={existing.data?.name ?? ''}
          template={templates.data?.find((t) => t.id === templateId) ?? null}
          blank={blankRule(accounts.data ?? [])}
        />
      )}
    </>
  )
}

function Builder({
  ruleId,
  initialRule,
  initialName,
  template,
  blank,
}: {
  ruleId: string | null
  initialRule: RuleDsl | null
  initialName: string
  template: TemplateDto | null
  blank: RuleDsl
}) {
  const navigate = useNavigate()
  const aiOn = useFlag('ai')
  const templates = useTemplates()
  const { create, update } = useRuleMutation()

  const startRule = initialRule ?? template?.rule ?? null
  const [draft, setDraft] = useState<RuleDsl | null>(startRule)
  const [name, setName] = useState(initialName || template?.name || '')
  const [text, setText] = useState(startRule ? '' : (template?.prompt ?? ''))
  const [warnings, setWarnings] = useState<string[]>([])

  const compile = useMutation({
    mutationFn: autopilotApi.compile,
    onSuccess: (res) => {
      setDraft(res.rule)
      setWarnings(res.warnings)
      if (!name) setName(text.trim().slice(0, 40))
    },
    // No Planner (503 FEATURE_DISABLED, or AI_UNAVAILABLE when agent-quant is down or not set up):
    // fall back to the form, keeping the idea in the text box. AI_BUSY stays inline: worth a retry.
    onError: (err) => {
      const code = toApiError(err).code
      if (code === 'FEATURE_DISABLED' || code === 'AI_UNAVAILABLE') {
        setDraft(blank)
        toast.info('Writing rules from a sentence isn’t available yet. Fill in the form instead.')
      }
    },
  })

  // The server checks the draft and renders the sentence the user approves (D-023). Debounced
  // so typing an amount doesn't send a request per keystroke.
  const canonical = useMemo(() => (draft ? normalizeRule(draft) : null), [draft])
  const key = canonical ? JSON.stringify(canonical) : ''
  const debouncedKey = useDebouncedValue(key, 350)
  const validation = useQuery({
    queryKey: ['autopilot', 'validate', debouncedKey],
    queryFn: () => autopilotApi.validate(JSON.parse(debouncedKey) as RuleDsl),
    enabled: !!debouncedKey,
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  })

  const simulate = useMutation({ mutationFn: (r: RuleDsl) => autopilotApi.simulate(r, DAYS) })
  const [simFor, setSimFor] = useState<string | null>(null)
  const simFresh = simulate.data && simFor === key

  const save = ruleId ? update : create
  const saveErr = save.error ? toApiError(save.error) : null
  const errs = fieldErrorIndex(
    saveErr?.code === 'RULE_INVALID' ? saveErr.fieldErrors : (validation.data?.fieldErrors ?? []),
  )
  const checking = validation.isFetching || debouncedKey !== key
  const valid = !checking && validation.data?.valid === true
  const nameErr =
    errs.all.find((e) => e.path === 'name')?.message ??
    (name.trim().length > 40 ? 'Keep it under 40 characters' : undefined)

  const activate = () => {
    if (!canonical || !valid || !simFresh || save.isPending) return
    const body = { name: name.trim() || 'My rule', rule: canonical }
    const onSuccess = (r: { id: string }) => {
      toast.success(
        ruleId ? 'Rule updated. The new version is live.' : 'Autopilot rule switched on.',
      )
      void navigate(`/autopilot/${r.id}`)
    }
    if (ruleId) update.mutate({ id: ruleId, ...body }, { onSuccess })
    else create.mutate(body, { onSuccess })
  }

  // ── Step 1: describe it ────────────────────────────────────────────────────────────
  if (!draft) {
    const compileErr = compile.error ? toApiError(compile.error) : null
    return (
      <div className="grid gap-4 lg:grid-cols-12">
        <Panel
          className="lg:col-span-7"
          eyebrow={aiOn ? 'Plain English' : 'Build it'}
          title={aiOn ? 'What should happen, and when?' : 'Build your rule'}
          tone="violet"
        >
          {aiOn ? (
            <form
              className="space-y-3"
              onSubmit={(e) => {
                e.preventDefault()
                if (text.trim().length >= 8 && !compile.isPending) compile.mutate(text.trim())
              }}
            >
              <label htmlFor="rule-text" className="sr-only">
                Describe your rule
              </label>
              <Textarea
                id="rule-text"
                value={text}
                maxLength={500}
                onChange={(e) => setText(e.target.value)}
                placeholder="When my salary arrives, move 20% to my Goa goal and buy ₹5,000 of QGEN."
                aria-invalid={compileErr?.code === 'VALIDATION_FAILED' || undefined}
              />
              {compileErr && (
                <p role="alert" className="text-sm text-loss">
                  {compileErr.fieldErrors[0]?.message
                    ? `Describe the rule in 8–500 characters.`
                    : errorMessage(compileErr)}
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  type="submit"
                  variant="violet"
                  disabled={text.trim().length < 8 || compile.isPending}
                >
                  {compile.isPending ? (
                    <Loader2 className="animate-spin" aria-hidden="true" />
                  ) : (
                    <Wand2 aria-hidden="true" />
                  )}
                  {compile.isPending ? 'Working it out…' : 'Turn into a rule'}
                </Button>
                <Button type="button" variant="ghost" onClick={() => setDraft(blank)}>
                  <PenLine aria-hidden="true" /> Build it myself
                </Button>
              </div>
              <p className="text-xs text-muted">
                The AI only drafts the rule. You check it, replay it, and switch it on yourself.
              </p>
            </form>
          ) : (
            <div className="space-y-3 text-sm text-muted">
              <p>
                The AI helper is switched off right now, but you can build a rule with the form.
              </p>
              <Button onClick={() => setDraft(blank)}>
                <PenLine aria-hidden="true" /> Start from a simple rule
              </Button>
            </div>
          )}
        </Panel>
        <Panel className="lg:col-span-5" eyebrow="Templates" title="Start from an idea">
          {templates.isError ? (
            <PanelError what="templates" onRetry={() => void templates.refetch()} />
          ) : (
            <ul className="space-y-2">
              {(templates.data ?? []).map((t) => (
                <li key={t.id}>
                  <button
                    type="button"
                    disabled={compile.isPending}
                    onClick={() => {
                      setName(t.name)
                      setText(t.prompt)
                      if (t.rule) setDraft(t.rule)
                      // The server's templates carry no rule (accounts are the user's): compile the
                      // idea when AI is on, else start the form with the idea as the name and prompt.
                      else if (aiOn) compile.mutate(t.prompt)
                      else setDraft(blank)
                    }}
                    className="w-full rounded-[var(--radius-control)] bg-surface-2/50 p-3 text-left hairline transition-colors hover:border-violet/50 hover:bg-violet/10 disabled:opacity-50"
                  >
                    <span className="block text-sm font-medium">{t.name}</span>
                    <span className="mt-0.5 block text-xs text-muted">{t.description}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </div>
    )
  }

  // ── Step 2: check, replay, switch on ──────────────────────────────────────────────
  return (
    <div className="grid gap-4 lg:grid-cols-12">
      <Panel
        className="lg:col-span-7"
        eyebrow="The rule"
        title="Fine-tune it"
        action={
          !ruleId && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setDraft(null)
                setWarnings([])
                simulate.reset()
              }}
            >
              <ArrowLeft aria-hidden="true" /> Start over
            </Button>
          )
        }
      >
        <RuleForm rule={draft} onChange={setDraft} errors={errs} />
      </Panel>

      <div className="space-y-4 lg:sticky lg:top-24 lg:col-span-5 lg:self-start">
        <Panel eyebrow="Checked by QuantGen" title="What it will do" tone="violet">
          <div aria-live="polite" className="space-y-3">
            {validation.isError ? (
              <PanelError what="the check" onRetry={() => void validation.refetch()} />
            ) : validation.data?.rendering ? (
              <p className={`font-display text-xl leading-snug ${checking ? 'opacity-60' : ''}`}>
                “{validation.data.rendering}”
              </p>
            ) : checking ? (
              <Skeleton className="h-16" />
            ) : (
              <p className="text-sm text-muted">
                Fix the highlighted fields and the sentence will appear here.
              </p>
            )}
            {!checking && errs.all.length > 0 && (
              <ul className="space-y-1 text-sm text-loss" role="alert">
                {errs.all.slice(0, 4).map((e) => (
                  <li key={e.path} className="flex gap-1.5">
                    <CircleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{' '}
                    {e.message}
                  </li>
                ))}
              </ul>
            )}
            {warnings.length > 0 && (
              <div className="rounded-[var(--radius-control)] bg-violet/8 p-3 text-sm">
                <p className="mb-1 flex items-center gap-1.5 text-xs font-medium text-violet">
                  <Sparkles aria-hidden="true" className="size-3.5" /> Notes from the AI draft
                </p>
                <ul className="list-disc space-y-0.5 pl-4 text-muted">
                  {warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </Panel>

        <Panel eyebrow={`Replay · last ${DAYS} days`} title="What would have happened">
          {simFresh && simulate.data ? (
            <SimulationView sim={simulate.data} days={DAYS} />
          ) : (
            <div className="space-y-3">
              <p className="text-sm text-muted">
                {simulate.data
                  ? 'You changed the rule, so replay it again.'
                  : 'Run the rule against your own history. Nothing moves; it’s a dry run.'}
              </p>
              {simulate.error && (
                <p role="alert" className="text-sm text-loss">
                  {errorMessage(simulate.error)}
                </p>
              )}
              <Button
                variant="secondary"
                disabled={!valid || simulate.isPending}
                onClick={() => {
                  if (!canonical) return
                  setSimFor(key)
                  simulate.mutate(canonical)
                }}
              >
                {simulate.isPending ? (
                  <Loader2 className="animate-spin" aria-hidden="true" />
                ) : (
                  <History aria-hidden="true" />
                )}
                {simulate.isPending ? 'Replaying…' : `Replay the last ${DAYS} days`}
              </Button>
            </div>
          )}
        </Panel>

        <Panel eyebrow="Switch on" title="Name it and go">
          <div className="space-y-3">
            <FormField
              label="Rule name"
              value={name}
              maxLength={41}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Salary splitter"
              error={nameErr}
            />
            {saveErr && saveErr.code !== 'RULE_INVALID' && (
              <p role="alert" className="text-sm text-loss">
                {saveErr.code === 'STEP_UP_CANCELLED'
                  ? 'Verification was cancelled, so the rule wasn’t switched on.'
                  : errorMessage(saveErr)}
              </p>
            )}
            <Button
              size="lg"
              className="w-full"
              disabled={!valid || !simFresh || !!nameErr || save.isPending}
              onClick={activate}
            >
              {save.isPending ? (
                <Loader2 className="animate-spin" aria-hidden="true" />
              ) : (
                <Power aria-hidden="true" />
              )}
              {save.isPending ? 'Switching on…' : ruleId ? 'Save new version' : 'Switch on'}
            </Button>
            <p className="text-xs text-muted">
              {!simFresh ? 'Replay the rule first. ' : ''}You’ll confirm with your passkey or PIN.
              From then on it runs without AI, only within your caps, and you can pause it any time.
            </p>
          </div>
        </Panel>
      </div>
    </div>
  )
}
