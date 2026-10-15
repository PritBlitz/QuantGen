import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { loansApi } from '@/api/endpoints'
import type { LoanQuoteRequest } from '@/api/types'
import { useDebouncedValue } from '@/lib/useDebouncedValue'

/** Server quote (authoritative EMI + eligibility), debounced while sliders move. */
export function useLoanQuote(input: LoanQuoteRequest | null) {
  const debounced = useDebouncedValue(input, 350)
  return useQuery({
    queryKey: ['loans', 'quote', debounced],
    queryFn: () => loansApi.quote(debounced as LoanQuoteRequest),
    enabled: debounced !== null,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  })
}
