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
import { create } from 'zustand'
import { refreshSession } from '@/api/client'
import { queryKeys } from '@/api/queries'
import type { InstrumentDto } from '@/api/types'
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
