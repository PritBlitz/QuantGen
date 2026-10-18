/**
 * Live prices over STOMP (PRD §7.5): one shared connection while signed in.
 *
 * - CONNECT carries `Authorization: Bearer <token>`; the backend checks it only at
 *   CONNECT, so each (re)connect fetches a fresh token first.
 * - Ticks are validated (untrusted input) and written into the instruments query cache.
 * - While the socket is down, or in mock-only/test mode, we poll the REST endpoint every 3 s.
 */
import { Client } from '@stomp/stompjs'
import type { QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { create } from 'zustand'
import { refreshSession } from '@/api/client'
import { queryKeys } from '@/api/queries'
import type { InstrumentDto, NotificationDto } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'

export type FeedStatus = 'idle' | 'connecting' | 'live' | 'polling'

export const useFeedStatus = create<{ status: FeedStatus; lastTickAt: number | null }>()(() => ({
  status: 'idle',
  lastTickAt: null,
}))

const POLL_MS = 3000
const DEC = /^-?\d{1,15}(\.\d{1,8})?$/

/** Accept only well-formed InstrumentDto[]; drop anything else. */
export function parseTick(body: string): InstrumentDto[] | null {
  let data: unknown
  try {
    data = JSON.parse(body)
  } catch {
    return null
  }
  if (!Array.isArray(data)) return null
  const out: InstrumentDto[] = []
  for (const x of data) {
    if (
      x &&
      typeof x === 'object' &&
      typeof x.symbol === 'string' &&
      /^[A-Z0-9]{1,12}$/.test(x.symbol) &&
      typeof x.name === 'string' &&
      typeof x.sector === 'string' &&
      typeof x.price === 'string' &&
      DEC.test(x.price) &&
      typeof x.changePct24h === 'string' &&
      DEC.test(x.changePct24h) &&
      typeof x.updatedAt === 'string'
    ) {
      out.push({
        symbol: x.symbol,
        name: x.name,
        sector: x.sector,
        price: x.price,
        changePct24h: x.changePct24h,
        updatedAt: x.updatedAt,
      })
    }
  }
  return out.length ? out : null
}

/**
 * Accept only a well-formed NotificationDto pushed on /user/queue/notifications (V2-M1, F-RT-1).
 * The type is not checked against a list: the server may add types, the UI renders them generically.
 */
export function parseNotification(body: string): NotificationDto | null {
  let x: unknown
  try {
    x = JSON.parse(body)
  } catch {
    return null
  }
  if (!x || typeof x !== 'object') return null
  const n = x as Record<string, unknown>
  if (
    typeof n.id !== 'string' ||
    typeof n.type !== 'string' ||
    !/^[A-Z_]{1,40}$/.test(n.type) ||
    typeof n.title !== 'string' ||
    typeof n.body !== 'string' ||
    typeof n.read !== 'boolean' ||
    typeof n.createdAt !== 'string'
  )
    return null
  return {
    id: n.id,
    type: n.type as NotificationDto['type'],
    title: n.title.slice(0, 200),
    body: n.body.slice(0, 1000),
    read: n.read,
    createdAt: n.createdAt,
  }
}

const socketDisabled =
  import.meta.env.MODE === 'test' ||
  (import.meta.env.VITE_USE_MOCKS === 'true' && import.meta.env.VITE_MOCKS_HYBRID !== 'true')

let client: Client | null = null
let pollTimer: number | null = null
let refs = 0

function startPolling(qc: QueryClient) {
  if (pollTimer !== null) return
  useFeedStatus.setState({ status: 'polling' })
  pollTimer = window.setInterval(() => {
    void qc.invalidateQueries({ queryKey: queryKeys.instruments, exact: true })
  }, POLL_MS)
}

function stopPolling() {
  if (pollTimer !== null) window.clearInterval(pollTimer)
  pollTimer = null
}

function wsUrl() {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws`
}

function start(qc: QueryClient) {
  if (socketDisabled) {
    startPolling(qc)
    return
  }
  useFeedStatus.setState({ status: 'connecting' })
  client = new Client({
    brokerURL: wsUrl(),
    reconnectDelay: 5000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async (c) => {
      const { accessToken, expiresAt } = useAuthStore.getState()
      let token = accessToken
      if (!token || !expiresAt || expiresAt - Date.now() < 60_000) {
        token = (await refreshSession())?.accessToken ?? null
      }
      if (!token) {
        // Signed out: stop trying.
        await c.deactivate()
        return
      }
      c.connectHeaders = { Authorization: `Bearer ${token}` }
    },
    onConnect: () => {
      stopPolling()
      useFeedStatus.setState({ status: 'live' })
      client?.subscribe('/topic/prices', (msg) => {
        const tick = parseTick(msg.body)
        if (!tick) return
        qc.setQueryData<InstrumentDto[]>(queryKeys.instruments, tick)
        for (const i of tick) qc.setQueryData(queryKeys.instrument(i.symbol), i)
        useFeedStatus.setState({ lastTickAt: Date.now() })
      })
      // Realtime notifications (F-RT-1). Push is best-effort: it only triggers a refetch of the
      // REST list and count, which stay the source of truth, plus a toast. Text is rendered as
      // plain text (React escapes it), never as HTML.
      client?.subscribe('/user/queue/notifications', (msg) => {
        const n = parseNotification(msg.body)
        if (!n) return
        void qc.invalidateQueries({ queryKey: ['notifications'] })
        // Balances may have changed (money received, transfer reviewed…).
        void qc.invalidateQueries({ queryKey: queryKeys.accounts })
        toast(n.title, { description: n.body })
      })
    },
    onWebSocketClose: () => {
      if (refs > 0) startPolling(qc)
    },
    onStompError: () => {
      if (refs > 0) startPolling(qc)
    },
  })
  client.activate()
  // Until the first CONNECTED frame, keep data fresh by polling.
  startPolling(qc)
  useFeedStatus.setState({ status: 'connecting' })
}

function stop() {
  stopPolling()
  void client?.deactivate()
  client = null
  useFeedStatus.setState({ status: 'idle', lastTickAt: null })
}

/** Ref-counted: the first caller opens the feed, the last one closes it. */
export function acquireFeed(qc: QueryClient) {
  refs += 1
  if (refs === 1) start(qc)
  return () => {
    refs -= 1
    if (refs === 0) stop()
  }
}
