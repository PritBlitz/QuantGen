import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { marketApi } from '@/api/endpoints'
import { queryKeys } from '@/api/queries'
import type { MoneyString, PriceRange } from '@/api/types'
import { toDecimal } from '@/lib/money'
import { acquireFeed } from './livePrices'

/** Keep the shared price feed open while the calling component is mounted. */
export function useLivePriceFeed() {
  const qc = useQueryClient()
  useEffect(() => acquireFeed(qc), [qc])
}

export const useInstrument = (symbol: string) =>
  useQuery({
    queryKey: queryKeys.instrument(symbol),
    queryFn: () => marketApi.instrument(symbol),
  })

export const usePriceHistory = (symbol: string, range: PriceRange, enabled = true) =>
  useQuery({
    queryKey: queryKeys.history(symbol, range),
    queryFn: () => marketApi.history(symbol, range),
    staleTime: range === '1H' ? 15_000 : 60_000,
    enabled,
  })

/**
 * Returns 'up' | 'down' for ~600 ms after the price changes (DESIGN.md §2 tick flash),
 * comparing decimals, never floats.
 */
export function usePriceFlash(price: MoneyString | undefined) {
  const prev = useRef(price)
  const [flash, setFlash] = useState<'up' | 'down' | null>(null)
  useEffect(() => {
    const before = prev.current
    prev.current = price
    if (!price || !before || before === price) return
    const dir = toDecimal(price).gt(toDecimal(before)) ? 'up' : 'down'
    const start = window.setTimeout(() => setFlash(dir), 0)
    const end = window.setTimeout(() => setFlash(null), 600)
    return () => {
      window.clearTimeout(start)
      window.clearTimeout(end)
    }
  }, [price])
  return flash
}
