import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import { copilotApi } from '@/api/endpoints.v2'
import { ApiError, toApiError } from '@/api/errors'
import type { AgentName, CitationDto, ConversationDetailDto, IntentDto, IntentStatus } from '@/api/types'
import { streamMessage } from './stream'
import { useCopilotUi } from './uiStore'

export const copilotKeys = {
  conversations: ['copilot', 'conversations'] as const,
  conversation: (id: string) => ['copilot', 'conversation', id] as const,
  trace: (runId: string) => ['copilot', 'trace', runId] as const,
  intents: (status?: IntentStatus) => ['copilot', 'intents', status ?? 'ALL'] as const,
}

export const useConversations = () =>
  useQuery({ queryKey: copilotKeys.conversations, queryFn: copilotApi.conversations })
export const useConversation = (id: string | null) =>
  useQuery({
    queryKey: copilotKeys.conversation(id ?? ''),
    queryFn: () => copilotApi.conversation(id ?? ''),
    enabled: !!id,
  })
export const useTrace = (runId: string | null) =>
  useQuery({
    queryKey: copilotKeys.trace(runId ?? ''),
    queryFn: () => copilotApi.trace(runId ?? ''),
    enabled: !!runId,
    staleTime: Infinity,
  })
export const useIntents = (status?: IntentStatus) =>
  useQuery({ queryKey: copilotKeys.intents(status), queryFn: () => copilotApi.intents(status) })

export function useDeleteConversation() {
  const qc = useQueryClient()
  const { conversationId, setConversation } = useCopilotUi()
  return useMutation({
    mutationFn: copilotApi.deleteConversation,
    onSuccess: (_, id) => {
      if (id === conversationId) setConversation(null)
      qc.removeQueries({ queryKey: copilotKeys.conversation(id) })
      void qc.invalidateQueries({ queryKey: copilotKeys.conversations })
    },
  })
}

export interface ToolProgress {
  agent: AgentName
  tool: string
  status: 'STARTED' | 'DONE' | 'FAILED'
}

/** The turn being streamed right now, shown until the server's stored copy replaces it. */
export interface LiveTurn {
  userText: string
  text: string
  tools: ToolProgress[]
  intents: IntentDto[]
  citations: CitationDto[]
  runId: string | null
  status: 'streaming' | 'done' | 'error'
  error: ApiError | null
  /** Stored messages in the thread when this turn was sent; more means the server's copy is in. */
  baseCount: number
}

/**
 * One Copilot thread: send a message, stream the answer, then refetch the stored conversation.
 * Creates the conversation on first send. Nothing here moves money: intents arrive as proposals
 * and are approved separately (IntentCard).
 */
export function useCopilotChat() {
  const qc = useQueryClient()
  const conversationId = useCopilotUi((s) => s.conversationId)
  const setConversation = useCopilotUi((s) => s.setConversation)
  const conversation = useConversation(conversationId)
  const [live, setLive] = useState<LiveTurn | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  /** Set when `send` itself created the thread, so the switch below doesn't cancel it. */
  const createdRef = useRef<string | null>(null)

  // Switching threads cancels any stream for the old one.
  useEffect(() => {
    if (conversationId && conversationId === createdRef.current) return
    abortRef.current?.abort()
    abortRef.current = null
    setLive(null)
  }, [conversationId])
  useEffect(() => () => abortRef.current?.abort(), [])

  const busy = live?.status === 'streaming'

  const send = useCallback(
    async (raw: string) => {
      const text = raw.trim()
      if (!text || text.length > 2000 || abortRef.current) return
      const ctrl = new AbortController()
      abortRef.current = ctrl
      const patch = (fn: (t: LiveTurn) => LiveTurn) => setLive((t) => (t ? fn(t) : t))
      let id = useCopilotUi.getState().conversationId
      const baseCount = id ? (qc.getQueryData<ConversationDetailDto>(copilotKeys.conversation(id))?.messages.length ?? 0) : 0
      setLive({ userText: text, text: '', tools: [], intents: [], citations: [], runId: null, status: 'streaming', error: null, baseCount })
      try {
        if (!id) {
          id = (await copilotApi.createConversation()).id
          if (ctrl.signal.aborted) return
          createdRef.current = id
          setConversation(id)
          void qc.invalidateQueries({ queryKey: copilotKeys.conversations })
        }
        let failed: ApiError | null = null
        await streamMessage(
          id,
          text,
          (e) => {
            switch (e.type) {
              case 'token':
                patch((t) => ({ ...t, text: t.text + e.text }))
                break
              case 'tool':
                patch((t) => {
                  const i = t.tools.findIndex((x) => x.agent === e.agent && x.tool === e.tool)
                  const next = { agent: e.agent, tool: e.tool, status: e.status }
                  return { ...t, tools: i < 0 ? [...t.tools, next] : t.tools.map((x, j) => (j === i ? next : x)) }
                })
                break
              case 'intent':
                patch((t) => ({ ...t, intents: [...t.intents, e.intent] }))
                break
              case 'citation':
                patch((t) => ({ ...t, citations: [...t.citations, e.citation] }))
                break
              case 'done':
                patch((t) => ({ ...t, runId: e.runId }))
                break
              case 'error':
                // Mid-stream failure (e.g. BUDGET_EXHAUSTED); the HTTP status was already 200.
                failed = new ApiError({ status: 200, code: e.code as ApiError['code'], message: e.message })
                break
            }
          },
          ctrl.signal,
        )
        if (failed) throw failed
        patch((t) => ({ ...t, status: 'done' }))
        await Promise.all([
          qc.refetchQueries({ queryKey: copilotKeys.conversation(id) }),
          qc.invalidateQueries({ queryKey: copilotKeys.conversations }),
          qc.invalidateQueries({ queryKey: ['copilot', 'intents'] }),
        ])
        if (!ctrl.signal.aborted) setLive(null)
      } catch (e) {
        if (ctrl.signal.aborted) return
        patch((t) => ({ ...t, status: 'error', error: toApiError(e) }))
        if (id) void qc.invalidateQueries({ queryKey: copilotKeys.conversation(id) })
      } finally {
        if (abortRef.current === ctrl) abortRef.current = null
      }
    },
    [qc, setConversation],
  )

  const stop = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setLive((t) => (t ? { ...t, status: 'done' } : t))
    const id = useCopilotUi.getState().conversationId
    if (id) void qc.invalidateQueries({ queryKey: copilotKeys.conversation(id) })
  }, [qc])

  const newChat = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setConversation(null)
  }, [setConversation])

  return { conversationId, conversation, live, busy, send, stop, newChat, setConversation }
}
