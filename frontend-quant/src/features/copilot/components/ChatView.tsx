import { ArrowUp, Check, Loader2, Route, Sparkles, Square, TriangleAlert, X } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router'
import { errorMessage } from '@/api/errors'
import type { CitationDto, IntentDto } from '@/api/types'
import { PanelError } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import type { LiveTurn, ToolProgress, useCopilotChat } from '../hooks'
import { AGENT_LABEL, citationHref, SUGGESTIONS, toolLabel } from '../labels'
import { IntentCard } from './IntentCard'
import { TraceDialog } from './TraceDialog'

const MAX = 2000

/**
 * The Copilot thread + composer, shared by the sheet and the `/copilot` page. Answers are plain
 * text (no HTML/markdown rendering), so model output can never inject markup.
 */
export function ChatView({
  chat,
  autoFocus,
  className,
}: {
  chat: ReturnType<typeof useCopilotChat>
  autoFocus?: boolean
  className?: string
}) {
  const { conversationId, conversation, live, busy, send, stop } = chat
  const [traceRun, setTraceRun] = useState<string | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const messages = conversationId ? (conversation.data?.messages ?? []) : []
  const empty = !live && messages.length === 0
  // While a turn is live, show the thread as it was when it was sent (the server may store the
  // question mid-stream); once the stored copy is in, it takes over the same keys.
  const showLive =
    !!live &&
    (live.status === 'streaming' ||
      (live.status === 'done' && messages.length < live.baseCount + 2) ||
      (live.status === 'error' && messages.length <= live.baseCount))

  // Keep the newest content in view while streaming, unless the user scrolled up to read.
  const pinned = useRef(true)
  useLayoutEffect(() => {
    const el = scrollRef.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [messages.length, live?.text, live?.tools.length, live?.intents.length, live?.status])

  return (
    <div className={cn('flex min-h-0 flex-1 flex-col', className)}>
      <div
        ref={scrollRef}
        onScroll={(e) => {
          const el = e.currentTarget
          pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48
        }}
        role="log"
        aria-label="Conversation with Copilot"
        aria-busy={busy}
        className="scroll-thin min-h-0 flex-1 space-y-5 overflow-y-auto px-5 py-4"
      >
        {conversationId && conversation.isPending && !live ? (
          <div className="space-y-3">
            <Skeleton className="ml-auto h-10 w-2/3" />
            <Skeleton className="h-24 w-5/6" />
          </div>
        ) : conversationId && conversation.isError && !live ? (
          <PanelError what="this conversation" onRetry={() => void conversation.refetch()} />
        ) : empty ? (
          <Welcome onPick={(p) => void send(p)} />
        ) : (
          <>
            {/* One flat keyed list, so when the stored copy replaces the live turn the same
                components (and any approval in progress on an intent card) stay mounted. */}
            {[
              ...(showLive ? messages.slice(0, live.baseCount) : messages).map((m, i) =>
                m.role === 'USER' ? (
                  <UserBubble key={`t${i}`} text={m.text} />
                ) : (
                  <AssistantTurn
                    key={`t${i}`}
                    text={m.text}
                    intents={m.intents}
                    citations={m.citations}
                    runId={m.runId}
                    onTrace={setTraceRun}
                  />
                ),
              ),
              ...(showLive
                ? [
                    <UserBubble key={`t${live.baseCount}`} text={live.userText} />,
                    <AssistantTurn
                      key={`t${live.baseCount + 1}`}
                      text={live.text}
                      intents={live.intents}
                      citations={live.citations}
                      runId={live.runId}
                      onTrace={setTraceRun}
                      live={live}
                    />,
                  ]
                : []),
              // Failed after the server stored the question: keep the error visible.
              ...(live?.error && live.status === 'error' && messages.length > live.baseCount
                ? [
                    <p
                      key="live-error"
                      role="alert"
                      className="rounded-[var(--radius-control)] border border-warn/40 bg-warn/10 p-3 text-sm"
                    >
                      {errorMessage(live.error)}
                    </p>,
                  ]
                : []),
            ]}
          </>
        )}
      </div>
      <Composer busy={busy} onSend={send} onStop={stop} autoFocus={autoFocus} />
      {traceRun && <TraceDialog runId={traceRun} onClose={() => setTraceRun(null)} />}
    </div>
  )
}

function Welcome({ onPick }: { onPick: (prompt: string) => void }) {
  return (
    <div className="flex flex-col items-center px-2 pb-4 pt-8 text-center">
      <span className="grid size-12 place-items-center rounded-2xl bg-violet text-violet-ink">
        <Sparkles aria-hidden="true" className="size-5" />
      </span>
      <p className="mt-4 font-medium">Ask about your money</p>
      <p className="mt-1 max-w-xs text-sm text-muted">
        Copilot can read your accounts and prepare payments, orders and transfers. It never moves
        money by itself: you approve every action.
      </p>
      <ul className="mt-5 flex w-full flex-col gap-2">
        {SUGGESTIONS.map((s) => (
          <li key={s}>
            <button
              type="button"
              onClick={() => onPick(s)}
              className="w-full rounded-[var(--radius-control)] bg-surface-2/60 px-3.5 py-2.5 text-left text-sm hairline transition-colors hover:border-violet/50 hover:bg-violet/10"
            >
              {s}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function UserBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-accent/15 px-3.5 py-2.5 text-[0.9375rem]">
        {text}
      </p>
    </div>
  )
}

function Avatar() {
  return (
    <span
      aria-hidden="true"
      className="mt-0.5 grid size-7 shrink-0 place-items-center rounded-xl bg-violet/15 text-violet"
    >
      <Sparkles className="size-3.5" />
    </span>
  )
}

function AssistantBody({
  text,
  intents,
  citations,
  runId,
  onTrace,
  streaming,
}: {
  text: string
  intents: IntentDto[]
  citations: CitationDto[]
  runId: string | null
  onTrace: (runId: string) => void
  streaming?: boolean
}) {
  return (
    <div className="min-w-0 flex-1 space-y-3">
      {text && (
        <p className="whitespace-pre-wrap break-words text-[0.9375rem] leading-relaxed">
          {text}
          {streaming && (
            <span
              aria-hidden="true"
              className="ml-0.5 inline-block h-4 w-1.5 animate-pulse rounded-sm bg-violet align-middle"
            />
          )}
        </p>
      )}
      {intents.map((i) => (
        <IntentCard key={i.id} intent={i} />
      ))}
      {(citations.length > 0 || runId) && (
        <div className="flex flex-wrap items-center gap-1.5">
          {citations.map((c, i) => (
            <Citation key={`${c.type}-${c.id}-${i}`} c={c} />
          ))}
          {runId && (
            <button
              type="button"
              onClick={() => onTrace(runId)}
              className="inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs text-muted hover:bg-surface-2 hover:text-text"
            >
              <Route aria-hidden="true" className="size-3.5" /> How I got this
            </button>
          )}
        </div>
      )}
    </div>
  )
}

function Citation({ c }: { c: CitationDto }) {
  const href = citationHref(c)
  const cls =
    'inline-flex max-w-full items-center truncate rounded-full bg-surface-2 px-2.5 py-1 text-xs text-muted hairline'
  return href ? (
    <Link to={href} className={cn(cls, 'hover:border-violet/50 hover:text-text')}>
      {c.label}
    </Link>
  ) : (
    <span className={cls}>{c.label}</span>
  )
}

function AssistantTurn({
  text,
  intents,
  citations,
  runId,
  onTrace,
  live,
}: {
  text: string
  intents: IntentDto[]
  citations: CitationDto[]
  runId: string | null
  onTrace: (runId: string) => void
  /** Set while this is the turn being streamed. */
  live?: LiveTurn
}) {
  const streaming = live?.status === 'streaming'
  return (
    <div className="flex gap-3">
      <Avatar />
      <div className="min-w-0 flex-1 space-y-3">
        {live && live.tools.length > 0 && <ToolTrail tools={live.tools} done={!streaming} />}
        {streaming && !text && live.tools.length === 0 && (
          <p className="flex items-center gap-2 text-sm text-muted">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" /> Thinking…
          </p>
        )}
        <AssistantBody
          text={text}
          intents={intents}
          citations={citations}
          runId={runId}
          onTrace={onTrace}
          streaming={streaming}
        />
        {live?.error && (
          <p
            role="alert"
            className="flex items-start gap-2 rounded-[var(--radius-control)] border border-warn/40 bg-warn/10 p-3 text-sm"
          >
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-warn" />
            {errorMessage(live.error)}
          </p>
        )}
      </div>
    </div>
  )
}

/** Progress only (`tool` events carry no data, PRD §12.6): which specialist is doing what. */
function ToolTrail({ tools, done }: { tools: ToolProgress[]; done: boolean }) {
  return (
    <ol aria-label="Copilot progress" className="flex flex-wrap gap-1.5">
      {tools.map((t) => (
        <li
          key={`${t.agent}-${t.tool}`}
          className={cn(
            'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs hairline',
            t.status === 'FAILED' ? 'text-loss' : 'text-muted',
          )}
        >
          {t.status === 'STARTED' && !done ? (
            <Loader2 aria-hidden="true" className="size-3 animate-spin" />
          ) : t.status === 'FAILED' ? (
            <X aria-hidden="true" className="size-3" />
          ) : (
            <Check aria-hidden="true" className="size-3 text-gain" />
          )}
          <span className="font-medium text-text">{AGENT_LABEL[t.agent] ?? t.agent}</span>{' '}
          {toolLabel(t.tool)}
        </li>
      ))}
    </ol>
  )
}

function Composer({
  busy,
  onSend,
  onStop,
  autoFocus,
}: {
  busy: boolean
  onSend: (text: string) => Promise<void>
  onStop: () => void
  autoFocus?: boolean
}) {
  const [text, setText] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)
  useEffect(() => {
    if (autoFocus) ref.current?.focus()
  }, [autoFocus])

  const submit = () => {
    const t = text.trim()
    if (!t || busy || t.length > MAX) return
    setText('')
    void onSend(t)
  }
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      submit()
    }
  }
  const over = text.length > MAX

  return (
    <form
      className="border-t border-border p-3"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <div className="flex items-end gap-2 rounded-[var(--radius-control)] bg-surface-2 p-1.5 pl-3 hairline focus-within:border-violet/60">
        <label htmlFor="copilot-input" className="sr-only">
          Message Copilot
        </label>
        <textarea
          id="copilot-input"
          ref={ref}
          rows={1}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={onKey}
          placeholder="Ask anything about your money…"
          aria-invalid={over || undefined}
          className="max-h-36 min-h-9 flex-1 resize-none bg-transparent py-1.5 text-[0.9375rem] outline-none [field-sizing:content] placeholder:text-muted/70"
        />
        {busy ? (
          <Button
            type="button"
            size="icon-sm"
            variant="secondary"
            aria-label="Stop"
            onClick={onStop}
          >
            <Square aria-hidden="true" className="fill-current" />
          </Button>
        ) : (
          <Button
            type="submit"
            size="icon-sm"
            variant="violet"
            aria-label="Send"
            disabled={!text.trim() || over}
          >
            <ArrowUp aria-hidden="true" />
          </Button>
        )}
      </div>
      <p className="mt-1.5 px-1 text-[0.6875rem] text-muted">
        {over ? (
          <span className="text-loss">Keep it under {MAX} characters.</span>
        ) : (
          'AI can be wrong. Check every action card before you approve it.'
        )}
      </p>
      {/* PRD §11.5 AI privacy + D-044: the provider's data use is stated where people type. */}
      <p className="mt-1 px-1 text-[0.6875rem] text-muted">
        Runs on Google’s Gemini free tier, which may use messages to improve Google’s products.
        Account numbers are masked before anything is sent. Conversations are deleted after 30 days.
      </p>
    </form>
  )
}
