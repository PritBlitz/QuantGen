import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import { flagsApi, riskApi } from '@/api/endpoints.v2'
import type { FeatureFlag, FraudCaseStatus } from '@/api/types'

/** Admin v2 data (PRD §12.8, §12.14): fraud queue, underwriting, AI usage, feature flags. */
export const riskKeys = {
  cases: (status: FraudCaseStatus | 'ALL', page: number) =>
    ['admin', 'fraud', status, page] as const,
  case: (id: string) => ['admin', 'fraud', 'case', id] as const,
  underwriting: (loanId: string) => ['admin', 'underwriting', loanId] as const,
  documents: (loanId: string) => ['loans', loanId, 'documents'] as const,
  aiUsage: (from: string, to: string) => ['admin', 'ai-usage', from, to] as const,
  flags: ['admin', 'feature-flags'] as const,
}

export const useFraudCases = (status: FraudCaseStatus | 'ALL', page: number) =>
  useQuery({
    queryKey: riskKeys.cases(status, page),
    queryFn: () => riskApi.cases({ ...(status === 'ALL' ? {} : { status }), page, size: 10 }),
    placeholderData: keepPreviousData,
  })
export const useFraudCase = (id: string) =>
  useQuery({ queryKey: riskKeys.case(id), queryFn: () => riskApi.case(id) })

export function useFraudDecision() {
  const qc = useQueryClient()
  // One Idempotency-Key per (case, decision, reason): a retry after a timeout replays instead of
  // deciding twice; a different decision or reason is a new request (and gets a 409 if one landed).
  const keys = useRef(new Map<string, string>())
  const keyFor = (id: string, kind: string, reason: string) => {
    const k = `${id}|${kind}|${reason}`
    let v = keys.current.get(k)
    if (!v) keys.current.set(k, (v = crypto.randomUUID()))
    return v
  }
  return useMutation({
    mutationFn: ({
      id,
      kind,
      reason,
    }: {
      id: string
      kind: 'release' | 'reject'
      reason: string
    }) =>
      kind === 'release'
        ? riskApi.release(id, reason, keyFor(id, kind, reason))
        : riskApi.reject(id, reason, keyFor(id, kind, reason)),
    // A 409 means another admin decided first: refresh either way.
    onSettled: () => qc.invalidateQueries({ queryKey: ['admin'] }),
  })
}

export const useUnderwriting = (loanId: string, enabled: boolean) =>
  useQuery({
    queryKey: riskKeys.underwriting(loanId),
    queryFn: () => riskApi.underwriting(loanId),
    enabled,
    retry: false,
  })
export const useLoanDocuments = (loanId: string) =>
  useQuery({
    queryKey: riskKeys.documents(loanId),
    queryFn: () => riskApi.documents(loanId),
    retry: false,
  })

export const useAiUsage = (from: string, to: string) =>
  useQuery({
    queryKey: riskKeys.aiUsage(from, to),
    queryFn: () => riskApi.aiUsage({ from, to }),
    placeholderData: keepPreviousData,
  })

export const useAdminFlags = () =>
  useQuery({ queryKey: riskKeys.flags, queryFn: flagsApi.adminList })
export function useSetFlag() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ name, enabled }: { name: FeatureFlag; enabled: boolean }) =>
      flagsApi.set(name, enabled),
    onSettled: () =>
      Promise.all([
        qc.invalidateQueries({ queryKey: riskKeys.flags }),
        qc.invalidateQueries({ queryKey: ['feature-flags'] }),
      ]),
  })
}
