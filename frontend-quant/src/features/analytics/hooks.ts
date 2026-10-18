/** TanStack Query hooks for analytics (PRD §12.13). Read-only; cached briefly like the server (60 s). */
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { analyticsApi } from '@/api/endpoints.v2'
import type {
  AnalyticsMetric,
  BreakdownDimension,
  DateRangeQuery,
  Granularity,
  HistoryRange,
  PortfolioRange,
} from '@/api/types'

const STALE = 60_000

export const analyticsKeys = {
  overview: (q: DateRangeQuery) => ['analytics', 'overview', q] as const,
  series: (q: object) => ['analytics', 'series', q] as const,
  breakdown: (q: object) => ['analytics', 'breakdown', q] as const,
  funnel: (q: DateRangeQuery) => ['analytics', 'funnel', q] as const,
  cohorts: (months: number) => ['analytics', 'cohorts', months] as const,
  loanBook: ['analytics', 'loan-book'] as const,
  adoption: (q: DateRangeQuery) => ['analytics', 'adoption', q] as const,
  health: ['analytics', 'health'] as const,
  summary: (q: DateRangeQuery) => ['me-analytics', 'summary', q] as const,
  cashflow: (months: number) => ['me-analytics', 'cashflow', months] as const,
  counterparties: (q: DateRangeQuery) => ['me-analytics', 'counterparties', q] as const,
  balanceHistory: (q: object) => ['me-analytics', 'balance-history', q] as const,
  portfolio: (r: PortfolioRange) => ['me-analytics', 'portfolio', r] as const,
  loans: ['me-analytics', 'loans'] as const,
  goals: ['me-analytics', 'goals'] as const,
}

// ── Business ────────────────────────────────────────────────────────────────────────────
export const useOverview = (q: DateRangeQuery) =>
  useQuery({ queryKey: analyticsKeys.overview(q), queryFn: () => analyticsApi.overview(q), staleTime: STALE, placeholderData: keepPreviousData })

export const useSeries = (q: DateRangeQuery & { metric: AnalyticsMetric; granularity: Granularity; compare?: boolean }, enabled = true) =>
  useQuery({ queryKey: analyticsKeys.series(q), queryFn: () => analyticsApi.series(q), staleTime: STALE, placeholderData: keepPreviousData, enabled })

export const useBreakdown = (q: DateRangeQuery & { dimension: BreakdownDimension; limit?: number }) =>
  useQuery({ queryKey: analyticsKeys.breakdown(q), queryFn: () => analyticsApi.breakdown(q), staleTime: STALE, placeholderData: keepPreviousData })

export const useFunnel = (q: DateRangeQuery) =>
  useQuery({ queryKey: analyticsKeys.funnel(q), queryFn: () => analyticsApi.funnel(q), staleTime: STALE, placeholderData: keepPreviousData })

export const useCohorts = (months: number) =>
  useQuery({ queryKey: analyticsKeys.cohorts(months), queryFn: () => analyticsApi.cohorts(months), staleTime: STALE })

export const useLoanBook = () =>
  useQuery({ queryKey: analyticsKeys.loanBook, queryFn: analyticsApi.loanBook, staleTime: STALE })

export const useAdoption = (q: DateRangeQuery) =>
  useQuery({ queryKey: analyticsKeys.adoption(q), queryFn: () => analyticsApi.adoption(q), staleTime: STALE, placeholderData: keepPreviousData })

export const useHealth = () =>
  useQuery({ queryKey: analyticsKeys.health, queryFn: analyticsApi.health, staleTime: STALE })

// ── Personal ────────────────────────────────────────────────────────────────────────────
export const useMoneySummary = (q: DateRangeQuery) =>
  useQuery({ queryKey: analyticsKeys.summary(q), queryFn: () => analyticsApi.summary(q), staleTime: STALE, placeholderData: keepPreviousData })

export const useCashflow = (months = 12) =>
  useQuery({ queryKey: analyticsKeys.cashflow(months), queryFn: () => analyticsApi.cashflow(months), staleTime: STALE })

export const useCounterparties = (q: DateRangeQuery) =>
  useQuery({ queryKey: analyticsKeys.counterparties(q), queryFn: () => analyticsApi.counterparties({ ...q, limit: 5 }), staleTime: STALE, placeholderData: keepPreviousData })

export const useBalanceHistory = (q: { accountId?: string; range: HistoryRange }) =>
  useQuery({ queryKey: analyticsKeys.balanceHistory(q), queryFn: () => analyticsApi.balanceHistory(q), staleTime: STALE, placeholderData: keepPreviousData })

export const usePortfolioAnalytics = (range: PortfolioRange) =>
  useQuery({ queryKey: analyticsKeys.portfolio(range), queryFn: () => analyticsApi.portfolio(range), staleTime: STALE, placeholderData: keepPreviousData })

export const useLoanAnalytics = () =>
  useQuery({ queryKey: analyticsKeys.loans, queryFn: analyticsApi.loans, staleTime: STALE })

export const useGoalProgress = () =>
  useQuery({ queryKey: analyticsKeys.goals, queryFn: analyticsApi.goals, staleTime: STALE })
