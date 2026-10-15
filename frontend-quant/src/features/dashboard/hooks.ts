import { useQueries } from '@tanstack/react-query'
import { accountsApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queries'
import type { AccountDto, TransactionDto } from '@/api/types'

export { useAccounts, useInstruments, useLoans, usePortfolio } from '@/api/queries'

/** Latest activity across all of the user's accounts (max 3 per F-ACC-1), newest first. */
export function useRecentActivity(accounts: AccountDto[] | undefined, limit = 6) {
  return useQueries({
    queries: (accounts ?? []).map((a) => ({
      queryKey: queryKeys.transactions(a.id, { page: 0, size: limit }),
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
