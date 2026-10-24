import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { savingsApi } from '@/api/endpoints.v2'
import type { Category, DateRangeQuery, MoneyString } from '@/api/types'

export const insightKeys = {
  spending: (q: object) => ['insights', 'spending', q] as const,
  budgets: ['insights', 'budgets'] as const,
  forecast: (days: number) => ['insights', 'forecast', days] as const,
  netWorth: (months: number) => ['insights', 'net-worth', months] as const,
}

export const useSpending = (q: DateRangeQuery & { groupBy: 'category' | 'month' }) =>
  useQuery({
    queryKey: insightKeys.spending(q),
    queryFn: () => savingsApi.spending(q),
    staleTime: 60_000,
  })

export const useBudgets = () =>
  useQuery({ queryKey: insightKeys.budgets, queryFn: savingsApi.budgets, staleTime: 60_000 })

export const useForecast = (days = 30) =>
  useQuery({
    queryKey: insightKeys.forecast(days),
    queryFn: () => savingsApi.forecast(days),
    staleTime: 60_000,
  })

export const useNetWorth = (months = 12) =>
  useQuery({
    queryKey: insightKeys.netWorth(months),
    queryFn: () => savingsApi.netWorth(months),
    staleTime: 60_000,
  })

export function useSaveBudgets() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (rows: Array<{ category: Category; monthlyLimit: MoneyString }>) =>
      savingsApi.setBudgets(rows),
    onSuccess: (data) => qc.setQueryData(insightKeys.budgets, data),
  })
}
