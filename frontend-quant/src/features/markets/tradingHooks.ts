import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { tradeApi } from '@/api/endpoints.v2'
import type { BacktestRequest, MoneyString } from '@/api/types'

/** Trading 2.0 data (PRD §12.9): watchlists, price alerts, backtests, tax P&L. */
export const tradingKeys = {
  watchlists: ['watchlists'] as const,
  alerts: ['price-alerts'] as const,
  backtests: ['backtests'] as const,
  taxPnl: (fy: string) => ['reports', 'tax-pnl', fy] as const,
}

// Not-yet-built endpoints answer 404: no retries, the UI hides the section.
export const useWatchlists = () =>
  useQuery({ queryKey: tradingKeys.watchlists, queryFn: tradeApi.watchlists, retry: false })
export const usePriceAlerts = () =>
  useQuery({ queryKey: tradingKeys.alerts, queryFn: tradeApi.alerts, retry: false })
export const useBacktests = () =>
  useQuery({ queryKey: tradingKeys.backtests, queryFn: tradeApi.backtests, retry: false })
export const useTaxPnl = (fy: string) =>
  useQuery({ queryKey: tradingKeys.taxPnl(fy), queryFn: () => tradeApi.taxPnl(fy), retry: false })

function useInvalidating<A>(fn: (a: A) => Promise<unknown>, key: readonly string[]) {
  const qc = useQueryClient()
  return useMutation({ mutationFn: fn, onSettled: () => qc.invalidateQueries({ queryKey: key }) })
}

export const useCreateWatchlist = () =>
  useInvalidating((name: string) => tradeApi.createWatchlist(name), tradingKeys.watchlists)
export const useDeleteWatchlist = () =>
  useInvalidating((id: string) => tradeApi.deleteWatchlist(id), tradingKeys.watchlists)
export const useToggleWatch = () =>
  useInvalidating(
    ({ id, symbol, on }: { id: string; symbol: string; on: boolean }) =>
      on ? tradeApi.addToWatchlist(id, symbol) : tradeApi.removeFromWatchlist(id, symbol),
    tradingKeys.watchlists,
  )
export const useCreateAlert = () =>
  useInvalidating(
    (body: { symbol: string; direction: 'ABOVE' | 'BELOW'; price: MoneyString }) =>
      tradeApi.createAlert(body),
    tradingKeys.alerts,
  )
export const useDeleteAlert = () =>
  useInvalidating((id: string) => tradeApi.deleteAlert(id), tradingKeys.alerts)
export const useRunBacktest = () =>
  useInvalidating((body: BacktestRequest) => tradeApi.backtest(body), tradingKeys.backtests)
