/**
 * Shared TanStack Query keys and read hooks. Keys are hierarchical so invalidating a root
 * (e.g. ['accounts']) refreshes every balance and transaction list after a money action.
 */
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { accountsApi, loansApi, marketApi, paymentsApi } from './endpoints'
import type { PageQuery, PriceRange, TransactionQuery } from './types'

export const queryKeys = {
  accounts: ['accounts'] as const,
  account: (id: string) => ['accounts', id] as const,
  transactions: (accountId: string, q: TransactionQuery) =>
    ['accounts', accountId, 'transactions', q] as const,
  transfers: (q: PageQuery) => ['transfers', q] as const,
  beneficiaries: ['beneficiaries'] as const,
  portfolio: ['portfolio'] as const,
  orders: (q: PageQuery) => ['orders', q] as const,
  loans: ['loans'] as const,
  loan: (id: string) => ['loans', id] as const,
  instruments: ['market', 'instruments'] as const,
  instrument: (symbol: string) => ['market', 'instruments', symbol] as const,
  history: (symbol: string, range: PriceRange) =>
    ['market', 'instruments', symbol, 'history', range] as const,
}

export const useAccounts = () =>
  useQuery({ queryKey: queryKeys.accounts, queryFn: accountsApi.list })

export const useAccount = (id: string) =>
  useQuery({ queryKey: queryKeys.account(id), queryFn: () => accountsApi.get(id) })

export const useTransactions = (accountId: string, q: TransactionQuery) =>
  useQuery({
    queryKey: queryKeys.transactions(accountId, q),
    queryFn: () => accountsApi.transactions(accountId, q),
    placeholderData: keepPreviousData,
  })

export const useTransfers = (q: PageQuery) =>
  useQuery({
    queryKey: queryKeys.transfers(q),
    queryFn: () => paymentsApi.transfers(q),
    placeholderData: keepPreviousData,
  })

export const useBeneficiaries = () =>
  useQuery({ queryKey: queryKeys.beneficiaries, queryFn: paymentsApi.beneficiaries })

export const usePortfolio = () =>
  useQuery({ queryKey: queryKeys.portfolio, queryFn: marketApi.portfolio })

export const useOrders = (q: PageQuery) =>
  useQuery({
    queryKey: queryKeys.orders(q),
    queryFn: () => marketApi.orders(q),
    placeholderData: keepPreviousData,
  })

export const useLoans = () => useQuery({ queryKey: queryKeys.loans, queryFn: loansApi.list })

export const useLoan = (id: string) =>
  useQuery({ queryKey: queryKeys.loan(id), queryFn: () => loansApi.get(id) })

export const useInstruments = (enabled = true) =>
  useQuery({ queryKey: queryKeys.instruments, queryFn: marketApi.instruments, enabled })

/** After money moves, refetch every affected server truth (never patch caches by hand). */
export function useInvalidateMoney() {
  const qc = useQueryClient()
  return useCallback(
    (...roots: Array<'accounts' | 'transfers' | 'portfolio' | 'orders' | 'loans'>) =>
      Promise.all(roots.map((r) => qc.invalidateQueries({ queryKey: [r] }))),
    [qc],
  )
}
