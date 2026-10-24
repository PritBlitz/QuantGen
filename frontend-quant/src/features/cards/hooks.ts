import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { backendHas } from '@/api/capabilities'
import { cardsApi } from '@/api/endpoints.v2'
import type { CardControls, CardDto, DisputeReason, DisputeStatus, MoneyString } from '@/api/types'

/** Virtual cards (PRD §12.11). The revealed PAN/CVV never goes through the query cache. */
export const cardKeys = {
  list: ['cards'] as const,
  payments: (cardId: string) => ['cards', cardId, 'payments'] as const,
  disputes: ['card-disputes'] as const,
  admin: (status: DisputeStatus | 'ALL', page: number) =>
    ['admin', 'card-disputes', status, page] as const,
}

/** F-CARD-4 disputes ship with the backend's 'F-CARD-4'; an older backend has none of these routes. */
export const DISPUTES = backendHas('F-CARD-4')

export const useCardPayments = (cardId: string, enabled: boolean) =>
  useQuery({
    queryKey: cardKeys.payments(cardId),
    queryFn: () => cardsApi.payments(cardId, { page: 0, size: 10 }),
    enabled: enabled && DISPUTES,
  })
export const useMyDisputes = (enabled: boolean) =>
  useQuery({
    queryKey: cardKeys.disputes,
    queryFn: cardsApi.disputes,
    enabled: enabled && DISPUTES,
  })

export function useOpenDispute(cardId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      authId,
      ...body
    }: {
      authId: string
      reason: DisputeReason
      amount?: MoneyString
      note?: string
    }) => cardsApi.openDispute(cardId, authId, body),
    onSettled: () =>
      Promise.all([
        qc.invalidateQueries({ queryKey: cardKeys.payments(cardId) }),
        qc.invalidateQueries({ queryKey: cardKeys.disputes }),
      ]),
  })
}

export const useAdminDisputes = (status: DisputeStatus | 'ALL', page: number) =>
  useQuery({
    queryKey: cardKeys.admin(status, page),
    queryFn: () =>
      cardsApi.adminDisputes({ ...(status === 'ALL' ? {} : { status }), page, size: 10 }),
    placeholderData: keepPreviousData,
  })

export function useResolveDispute() {
  const qc = useQueryClient()
  // One Idempotency-Key per (dispute, outcome), as the contract says: a retry after a timeout replays
  // instead of refunding twice; the other outcome is a new request (409 if the first one landed).
  const keys = useRef(new Map<string, string>())
  const keyFor = (id: string, outcome: string) => {
    const k = `${id}|${outcome}`
    let v = keys.current.get(k)
    if (!v) keys.current.set(k, (v = crypto.randomUUID()))
    return v
  }
  return useMutation({
    mutationFn: ({
      id,
      outcome,
      reason,
    }: {
      id: string
      outcome: 'REFUNDED' | 'REJECTED'
      reason: string
    }) => cardsApi.resolveDispute(id, { outcome, reason }, keyFor(id, outcome)),
    onSettled: () => qc.invalidateQueries({ queryKey: ['admin', 'card-disputes'] }),
  })
}

export const useCards = (enabled: boolean) =>
  useQuery({ queryKey: cardKeys.list, queryFn: cardsApi.list, enabled, retry: false })

export function useIssueCard() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (accountId: string) => cardsApi.create(accountId),
    onSettled: () => qc.invalidateQueries({ queryKey: cardKeys.list }),
  })
}

export function useCardControls(card: CardDto) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Partial<CardControls>) => cardsApi.controls(card.id, body),
    // Server-confirmed state only (no optimistic toggles): write the returned card into the list.
    onSuccess: (next) =>
      qc.setQueryData<CardDto[]>(cardKeys.list, (list) =>
        list?.map((c) => (c.id === next.id ? next : c)),
      ),
    onError: () => qc.invalidateQueries({ queryKey: cardKeys.list }),
  })
}
