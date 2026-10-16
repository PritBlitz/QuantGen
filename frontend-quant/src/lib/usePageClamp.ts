import { useEffect } from 'react'

/**
 * If the current page comes back empty after a refetch (e.g. its last row was approved or
 * deleted), step back to the last page that still has rows instead of showing "empty".
 */
export function usePageClamp(
  data: { content: unknown[]; totalPages: number } | undefined,
  page: number,
  setPage: (page: number) => void,
) {
  useEffect(() => {
    if (data && data.content.length === 0 && page > 0) setPage(Math.max(0, data.totalPages - 1))
  }, [data, page, setPage])
}
