import { MessagesSquare, Sparkles, SquarePen, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from '@/api/errors'
import { useFlag } from '@/api/flags'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { IntentDto } from '@/api/types'
import { cn } from '@/lib/utils'
import { ChatView } from './components/ChatView'
import { IntentCard } from './components/IntentCard'
import { useConversations, useCopilotChat, useDeleteConversation, useIntents } from './hooks'

const when = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })

/** `/copilot`: the full-page assistant (F-AGT-1…5). */
export function CopilotPage() {
  const on = useFlag('ai')
  const chat = useCopilotChat()

  if (!on) {
    return (
      <>
        <PageHeader eyebrow="AI" title="Copilot" />
        <EmptyState icon={<Sparkles />} title="Copilot is switched off right now">
          Everything else works as usual. Check back later.
        </EmptyState>
      </>
    )
  }

  return (
    <>
      <PageHeader
        eyebrow="AI · six specialist agents"
        title="Copilot"
        description="Ask about spending, balances, loans and markets. Copilot can prepare payments, orders and transfers, but nothing happens until you approve it."
        actions={
          <Button variant="violet" onClick={chat.newChat}>
            <SquarePen aria-hidden="true" /> New chat
          </Button>
        }
      />
      <div className="grid gap-4 lg:grid-cols-12">
        <History className="order-2 lg:order-1 lg:col-span-3" active={chat.conversationId} onPick={chat.setConversation} />
        <section
          aria-label="Chat"
          className="order-1 flex h-[min(72dvh,760px)] min-h-[420px] flex-col overflow-hidden rounded-[var(--radius-card)] bg-surface hairline lg:order-2 lg:col-span-6"
        >
          <ChatView chat={chat} autoFocus />
        </section>
        <Waiting className="order-3 lg:col-span-3" />
      </div>
    </>
  )
}

function History({ active, onPick, className }: { active: string | null; onPick: (id: string) => void; className?: string }) {
  const list = useConversations()
  const del = useDeleteConversation()
  return (
    <Panel title="Conversations" eyebrow="History" className={className} bodyClassName="mt-3">
      {list.isPending ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-11" />
          ))}
        </div>
      ) : list.isError ? (
        <PanelError what="conversations" onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <p className="py-4 text-sm text-muted">Your chats will show up here.</p>
      ) : (
        <ul className="-mx-2 space-y-0.5">
          {list.data.map((c) => (
            <li key={c.id} className="group relative">
              <button
                type="button"
                onClick={() => onPick(c.id)}
                aria-current={c.id === active || undefined}
                className={cn(
                  'w-full rounded-[var(--radius-control)] px-2 py-2 pr-9 text-left transition-colors hover:bg-surface-2',
                  c.id === active && 'bg-violet/10',
                )}
              >
                <span className="flex items-center gap-2 truncate text-sm">
                  <MessagesSquare aria-hidden="true" className="size-3.5 shrink-0 text-muted" />
                  <span className="truncate">{c.title ?? 'New conversation'}</span>
                </span>
                <span className="mt-0.5 block pl-5.5 text-xs text-muted">{when.format(new Date(c.updatedAt))}</span>
              </button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Delete “${c.title ?? 'New conversation'}”`}
                disabled={del.isPending}
                onClick={() => del.mutate(c.id, { onError: (e) => toast.error(errorMessage(e)) })}
                className="absolute right-1 top-1.5 text-muted opacity-100 md:opacity-0 md:group-hover:opacity-100 md:focus-visible:opacity-100"
              >
                <Trash2 aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}

/** Every open proposal across chats, so nothing waits unseen (they expire after 15 minutes). */
function Waiting({ className }: { className?: string }) {
  const intents = useIntents('PROPOSED')
  // Keep every card shown during this visit mounted: once approved it leaves the PROPOSED list, and
  // unmounting it would also throw away its "Approved and carried out" confirmation. New ones go on top.
  const [seen, setSeen] = useState<{ from: IntentDto[] | undefined; list: IntentDto[] }>({ from: undefined, list: [] })
  if (intents.data && intents.data !== seen.from) {
    const known = new Set(seen.list.map((i) => i.id))
    setSeen({ from: intents.data, list: [...intents.data.filter((i) => !known.has(i.id)), ...seen.list] })
  }
  const shown = seen.list
  return (
    <Panel title="Waiting for you" eyebrow="Approvals" tone="violet" className={className} bodyClassName="mt-3">
      {intents.isPending ? (
        <Skeleton className="h-40" />
      ) : intents.isError ? (
        <PanelError what="approvals" onRetry={() => void intents.refetch()} />
      ) : shown.length === 0 ? (
        <p className="py-4 text-sm text-muted">Nothing to approve. When Copilot prepares an action, it waits here until you decide.</p>
      ) : (
        <div className="space-y-3">
          {shown.map((i) => (
            <IntentCard key={i.id} intent={i} />
          ))}
        </div>
      )}
    </Panel>
  )
}
