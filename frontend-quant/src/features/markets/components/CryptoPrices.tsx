import { useQuery } from '@tanstack/react-query'
import { Bitcoin } from 'lucide-react'
import { marketApi } from '@/api/endpoints'
import { toApiError } from '@/api/errors'
import { useFlag } from '@/api/flags'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatINR } from '@/lib/money'
import { formatRelative } from '@/lib/time'
import { ChangePct } from './PriceBits'

/**
 * Live crypto prices (F-TRD-12), read-only. The API fetches a free public feed and serves its
 * cache, so the browser never talks to a third party. Hidden when the `crypto` flag is off.
 */
export function CryptoPrices() {
  const on = useFlag('crypto')
  const q = useQuery({
    queryKey: ['market', 'crypto'],
    queryFn: marketApi.crypto,
    enabled: on,
    // The server refreshes once a minute; asking more often only returns the same cache.
    refetchInterval: 60_000,
    retry: false,
  })
  if (!on) return null
  const unavailable = q.isError && toApiError(q.error).code === 'GATEWAY_UNAVAILABLE'
  // A failing feed keeps serving the last good prices with their own `asOf`; show the oldest.
  const oldest = q.data?.reduce<string | null>((m, c) => (!m || c.asOf < m ? c.asOf : m), null)

  return (
    <Card className="mt-6" aria-labelledby="crypto-title">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="crypto-title" className="flex items-center gap-2 font-medium">
          <Bitcoin aria-hidden="true" className="size-4 text-muted" /> Crypto
        </h2>
        <p className="text-xs text-muted">
          Read-only · from a public feed{oldest ? ` · updated ${formatRelative(oldest)}` : ''}
        </p>
      </div>
      <div className="mt-4">
        {q.isPending ? (
          <Skeleton className="h-24" />
        ) : q.isError ? (
          <p className="text-sm text-muted">
            {unavailable
              ? 'Prices aren’t in yet. They usually arrive within a minute.'
              : 'Crypto prices aren’t available right now.'}
          </p>
        ) : q.data.length === 0 ? (
          <p className="text-sm text-muted">No prices from the feed right now.</p>
        ) : (
          <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {q.data.map((c) => (
              <li key={c.symbol} className="min-w-0 rounded-xl bg-surface-2/50 p-3 hairline">
                <p className="text-xs text-muted">
                  <span className="font-semibold text-text">{c.symbol}</span> · {c.name}
                </p>
                <p className="num mt-1 truncate text-sm">{formatINR(c.priceInr)}</p>
                {c.change24hPct != null ? (
                  <ChangePct value={c.change24hPct} className="text-xs" />
                ) : (
                  <span className="text-xs text-muted">24h n/a</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      <p className="mt-3 text-xs text-muted">
        For information only. QuantGen doesn’t trade crypto, and these prices aren’t an offer.
      </p>
    </Card>
  )
}
