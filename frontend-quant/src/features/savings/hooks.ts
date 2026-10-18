import { useQuery } from '@tanstack/react-query'
import { savingsApi } from '@/api/endpoints.v2'

export const savingsKeys = {
  fds: ['savings', 'fds'] as const,
  rates: ['savings', 'fd-rates'] as const,
  roundUp: (accountId: string) => ['savings', 'round-up', accountId] as const,
}

export const useFixedDeposits = () => useQuery({ queryKey: savingsKeys.fds, queryFn: savingsApi.fds })
export const useFdRates = () => useQuery({ queryKey: savingsKeys.rates, queryFn: savingsApi.fdRates, staleTime: 10 * 60_000 })
export const useRoundUp = (accountId: string) =>
  useQuery({ queryKey: savingsKeys.roundUp(accountId), queryFn: () => savingsApi.roundUp(accountId), enabled: !!accountId })
