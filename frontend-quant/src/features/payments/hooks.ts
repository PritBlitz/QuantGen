import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { payApi } from '@/api/endpoints.v2'
import type { BillerCategory, PaymentRequestStatus } from '@/api/types'

export const payKeys = {
  requests: (direction: 'INCOMING' | 'OUTGOING', status?: PaymentRequestStatus) =>
    ['pay', 'requests', direction, status ?? 'ALL'] as const,
  scheduled: ['pay', 'scheduled'] as const,
  billers: (category?: BillerCategory) => ['pay', 'billers', category ?? 'ALL'] as const,
  billPayments: ['pay', 'bill-payments'] as const,
  savedBillers: ['pay', 'saved-billers'] as const,
  blocked: ['pay', 'blocked'] as const,
  groups: ['pay', 'groups'] as const,
  group: (id: string) => ['pay', 'groups', id] as const,
}

export const useRequests = (direction: 'INCOMING' | 'OUTGOING', status?: PaymentRequestStatus) =>
  useQuery({
    queryKey: payKeys.requests(direction, status),
    queryFn: () => payApi.requests({ direction, status, page: 0, size: 50 }),
    placeholderData: keepPreviousData,
  })

export const useBlockedRequesters = () =>
  useQuery({ queryKey: payKeys.blocked, queryFn: payApi.blockedRequesters })

export const useScheduled = () =>
  useQuery({ queryKey: payKeys.scheduled, queryFn: payApi.scheduled })
export const useBillers = (category?: BillerCategory) =>
  useQuery({
    queryKey: payKeys.billers(category),
    queryFn: () => payApi.billers(category),
    staleTime: 5 * 60_000,
  })
export const useSavedBillers = (enabled = true) =>
  useQuery({ queryKey: payKeys.savedBillers, queryFn: payApi.savedBillers, enabled })
export const useBillPayments = () =>
  useQuery({
    queryKey: payKeys.billPayments,
    queryFn: () => payApi.billPayments({ page: 0, size: 20 }),
  })
export const useGroups = () => useQuery({ queryKey: payKeys.groups, queryFn: payApi.groups })
export const useGroup = (id: string) =>
  useQuery({ queryKey: payKeys.group(id), queryFn: () => payApi.group(id) })

/** Invalidate everything a payment could change (balances, lists). */
export function useInvalidatePayments() {
  const qc = useQueryClient()
  return () =>
    Promise.all(
      [['accounts'], ['transfers'], ['pay'], ['notifications'], ['insights'], ['me-analytics']].map(
        (k) => qc.invalidateQueries({ queryKey: k }),
      ),
    )
}

export function useScheduledAction() {
  const invalidate = useInvalidatePayments()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'pause' | 'resume' | 'cancel' }) =>
      action === 'pause'
        ? payApi.pauseScheduled(id)
        : action === 'resume'
          ? payApi.resumeScheduled(id)
          : payApi.cancelScheduled(id),
    onSuccess: invalidate,
  })
}
