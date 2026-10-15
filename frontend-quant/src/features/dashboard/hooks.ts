import { useQueries, useQuery } from '@tanstack/react-query'
import { accountsApi, loansApi, marketApi } from '@/api/endpoints'
import type { AccountDto, TransactionDto } from '@/api/types'

export const queryKeys = {
  accounts: ['accounts'] as const,
  transactions: (accountId: string, size: number) =>
    ['accounts', accountId, 'transactions', { size }] as const,
  portfolio: ['portfolio'] as const,
  loans: ['loans'] as const,
  instruments: ['market', 'instruments'] as const,
}

export const useAccounts = () =>
  useQuery({ queryKey: queryKeys.accounts, queryFn: accountsApi.list })

export const usePortfolio = () =>
  useQuery({ queryKey: queryKeys.portfolio, queryFn: marketApi.portfolio })

export const useLoans = () => useQuery({ queryKey: queryKeys.loans, queryFn: loansApi.list })

export const useInstruments = (enabled = true) =>
  useQuery({ queryKey: queryKeys.instruments, queryFn: marketApi.instruments, enabled })

/** Latest activity across all of the user's accounts (max 3 per F-ACC-1), newest first. */
export function useRecentActivity(accounts: AccountDto[] | undefined, limit = 6) {
  return useQueries({
    queries: (accounts ?? []).map((a) => ({
      queryKey: queryKeys.transactions(a.id, limit),
      queryFn: () => accountsApi.transactions(a.id, { page: 0, size: limit }),
    })),
    combine: (results) => {
      const items: TransactionDto[] = results.flatMap((r) => r.data?.content ?? [])
      items.sort((x, y) => y.createdAt.localeCompare(x.createdAt))
      return {
        items: items.slice(0, limit),
        isPending: results.some((r) => r.isPending),
        isError: results.some((r) => r.isError),
        refetch: () => results.forEach((r) => void r.refetch()),
      }
    },
  })
}
