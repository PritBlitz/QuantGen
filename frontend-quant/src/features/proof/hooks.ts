import { useQuery } from '@tanstack/react-query'
import { proofApi } from '@/api/endpoints.v2'

export const proofKeys = {
  roots: ['proofs', 'roots'] as const,
  proof: (accountId: string) => ['proofs', 'account', accountId] as const,
}

export const useProofRoots = () =>
  useQuery({
    queryKey: proofKeys.roots,
    queryFn: proofApi.roots,
    staleTime: 5 * 60_000,
    retry: false,
  })
