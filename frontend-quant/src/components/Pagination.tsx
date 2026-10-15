import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from './ui/button'

/** Prev/next pager for the §7.1 page envelope (0-based pages). */
export function Pagination({
  page,
  totalPages,
  totalElements,
  onPage,
  label = 'items',
}: {
  page: number
  totalPages: number
  totalElements: number
  onPage: (page: number) => void
  label?: string
}) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-3 pt-4 text-sm">
      <p className="text-muted">
        Page <span className="num text-text">{page + 1}</span> of{' '}
        <span className="num">{totalPages}</span>
        <span className="hidden sm:inline">
          {' '}
          · <span className="num">{totalElements}</span> {label}
        </span>
      </p>
      <div className="flex gap-2">
        <Button
          variant="secondary"
          size="sm"
          disabled={page <= 0}
          onClick={() => onPage(page - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft aria-hidden="true" /> Prev
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
          aria-label="Next page"
        >
          Next <ChevronRight aria-hidden="true" />
        </Button>
      </div>
    </nav>
  )
}
