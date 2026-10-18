import { API_BASE, refreshSession } from '@/api/client'
import { ApiError } from '@/api/errors'
import type { CopilotEvent, ErrorBody } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'

/**
 * Client for `POST /copilot/conversations/{id}/messages` (PRD §12.6), which answers with
 * `text/event-stream`. axios can't stream a POST body back in the browser, so this uses `fetch`
 * with the same rules as the API client: bearer token from memory, one refresh on a plain 401.
 */

const KNOWN = new Set(['token', 'tool', 'intent', 'citation', 'done', 'error'])

/** Turn one SSE frame (`event:`/`data:` lines) into a typed event. Unknown events are skipped. */
function toEvent(type: string, data: string): CopilotEvent | null {
  if (!KNOWN.has(type) || !data) return null
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }
  if (typeof payload !== 'object' || payload === null) return null
  const p = payload as Record<string, unknown>
  switch (type) {
    case 'token':
      return typeof p.text === 'string' ? { type: 'token', text: p.text } : null
    case 'intent':
      return { type: 'intent', intent: payload as Extract<CopilotEvent, { type: 'intent' }>['intent'] }
    case 'citation':
      return { type: 'citation', citation: payload as Extract<CopilotEvent, { type: 'citation' }>['citation'] }
    default:
      return { type, ...p } as CopilotEvent
  }
}

/**
 * Incremental SSE parser: feed it decoded text chunks (which may split a frame anywhere) and it
 * returns the complete events so far. Follows the WHATWG framing rules we rely on: frames end
 * with a blank line, `data:` lines join with `\n`, lines starting with `:` are comments.
 */
export function createSseParser() {
  let buffer = ''
  return (chunk: string): CopilotEvent[] => {
    buffer += chunk.replace(/\r\n?/g, '\n')
    const out: CopilotEvent[] = []
    let end: number
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      let type = 'message'
      const data: string[] = []
      for (const line of frame.split('\n')) {
        if (!line || line.startsWith(':')) continue
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        let value = colon < 0 ? '' : line.slice(colon + 1)
        if (value.startsWith(' ')) value = value.slice(1)
        if (field === 'event') type = value
        else if (field === 'data') data.push(value)
      }
      const ev = toEvent(type, data.join('\n'))
      if (ev) out.push(ev)
    }
    return out
  }
}

async function errorFrom(res: Response): Promise<ApiError> {
  let body: Partial<ErrorBody> = {}
  try {
    body = (await res.json()) as Partial<ErrorBody>
  } catch {
    // Not a §7.1 body (proxy error page, etc.).
  }
  return new ApiError({
    status: res.status,
    code: (typeof body.code === 'string' ? body.code : res.status === 401 ? 'UNAUTHORIZED' : 'INTERNAL_ERROR') as ApiError['code'],
    message: typeof body.message === 'string' ? body.message : 'Unexpected response from the server',
    fieldErrors: body.fieldErrors ?? [],
    requestId: body.requestId ?? null,
  })
}

function post(conversationId: string, text: string, signal: AbortSignal): Promise<Response> {
  const token = useAuthStore.getState().accessToken
  return fetch(`${API_BASE}/copilot/conversations/${encodeURIComponent(conversationId)}/messages`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ text }),
    signal,
  })
}

/**
 * Send a message and call `onEvent` for each event as it arrives. Resolves when the stream
 * ends; rejects with an `ApiError` if the request is refused before streaming starts.
 * Aborting the signal stops reading (the server sees the connection close).
 */
export async function streamMessage(
  conversationId: string,
  text: string,
  onEvent: (e: CopilotEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await post(conversationId, text, signal)
    if (res.status === 401) {
      // An expired access token: refresh once (shared single-flight) and retry.
      if (!(await refreshSession())) throw new ApiError({ status: 401, code: 'UNAUTHORIZED', message: 'Session ended' })
      res = await post(conversationId, text, signal)
    }
  } catch (e) {
    if (e instanceof ApiError || signal.aborted) throw e
    throw new ApiError({ status: 0, code: 'NETWORK', message: 'Network error' })
  }
  if (!res.ok || !res.body) throw await errorFrom(res)

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  const parse = createSseParser()
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      for (const ev of parse(decoder.decode(value, { stream: true }))) onEvent(ev)
    }
    for (const ev of parse(decoder.decode() + '\n\n')) onEvent(ev)
  } finally {
    reader.releaseLock()
  }
}
