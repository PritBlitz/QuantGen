import { useMutation, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/api/endpoints'
import type { AccountStatusRequest } from '@/api/types'

/**
 * Admin writes. No optimistic updates: after the server confirms, every admin view and any
 * cached customer data are refetched (a freeze or disbursal changes balances and statuses).
 */
function useRefetchAdmin() {
  const qc = useQueryClient()
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ['admin'] }),
      qc.invalidateQueries({ queryKey: ['accounts'] }),
      qc.invalidateQueries({ queryKey: ['loans'] }),
    ])
}

export function useSetAccountStatus() {
  const refetch = useRefetchAdmin()
  return useMutation({
    mutationFn: ({ id, ...body }: AccountStatusRequest & { id: string }) =>
      adminApi.setAccountStatus(id, body),
    onSettled: refetch,
  })
}

export function useApproveLoan() {
  const refetch = useRefetchAdmin()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      adminApi.approveLoan(id, reason ? { reason } : {}),
    onSettled: refetch,
  })
}

export function useRejectLoan() {
  const refetch = useRefetchAdmin()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      adminApi.rejectLoan(id, { reason }),
    onSettled: refetch,
  })
}
