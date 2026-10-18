import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { cardsApi } from '@/api/endpoints.v2'
import type { CardControls, CardDto } from '@/api/types'

/** Virtual cards (PRD §12.11). The revealed PAN/CVV never goes through the query cache. */
export const cardKeys = { list: ['cards'] as const }

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
      qc.setQueryData<CardDto[]>(cardKeys.list, (list) => list?.map((c) => (c.id === next.id ? next : c))),
    onError: () => qc.invalidateQueries({ queryKey: cardKeys.list }),
  })
}
