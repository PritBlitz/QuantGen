import { Check, Loader2, Plus, Star, Trash2 } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { isUnavailable } from '@/api/availability'
import { errorMessage, toApiError } from '@/api/errors'
import type { WatchlistDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { FieldMessage, Input, Label } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import {
  useCreateWatchlist,
  useDeleteWatchlist,
  useToggleWatch,
  useWatchlists,
} from '../tradingHooks'

export const MAX_WATCHLISTS = 5
export const MAX_SYMBOLS = 50
export const ALL_INSTRUMENTS = 'all'

function NewWatchlistForm({ onCreated }: { onCreated?: (w: WatchlistDto) => void }) {
  const id = useId()
  const create = useCreateWatchlist()
  const [name, setName] = useState('')
  const [error, setError] = useState<string>()
  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    if (!n) return setError('Give the list a name')
    if (n.length > 30) return setError('Keep it to 30 characters')
    setError(undefined)
    create.mutate(n, {
      onSuccess: (w) => {
        setName('')
        onCreated?.(w as WatchlistDto)
      },
      onError: (err) => {
        const ae = toApiError(err)
        setError(ae.code === 'LIMIT_EXCEEDED' ? `You can have up to ${MAX_WATCHLISTS} lists.` : errorMessage(ae))
      },
    })
  }
  return (
    <form onSubmit={submit} className="space-y-1.5" noValidate>
      <Label htmlFor={id}>New list</Label>
      <div className="flex gap-2">
        <Input
          id={id}
          value={name}
          maxLength={40}
          placeholder="e.g. Dividend picks"
          onChange={(e) => setName(e.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : undefined}
        />
        <Button type="submit" variant="secondary" disabled={create.isPending}>
          {create.isPending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Plus aria-hidden="true" />}
          Create
        </Button>
      </div>
      <FieldMessage id={`${id}-error`}>{error}</FieldMessage>
    </form>
  )
}

/**
 * Star button on an instrument page (F-TRD-8): add or remove the symbol from any of your lists.
 * Hidden when the backend doesn't have watchlists yet.
 */
export function WatchButton({ symbol }: { symbol: string }) {
  const lists = useWatchlists()
  const toggle = useToggleWatch()
  const [open, setOpen] = useState(false)
  if (lists.isError && isUnavailable(lists.error)) return null
  const data = lists.data ?? []
  const watched = data.some((w) => w.symbols.includes(symbol))
  return (
    <>
      <Button
        variant="secondary"
        size="sm"
        onClick={() => setOpen(true)}
        disabled={!lists.data}
      >
        <Star aria-hidden="true" className={cn(watched && 'fill-current text-accent')} />
        {watched ? 'Watching' : 'Watch'}
        <span className="sr-only"> {symbol}</span>
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title={`Watch ${symbol}`} description="Pick the lists it belongs on.">
          <ul className="mb-5 space-y-1.5">
            {data.map((w) => {
              const on = w.symbols.includes(symbol)
              const full = !on && w.symbols.length >= MAX_SYMBOLS
              return (
                <li key={w.id}>
                  <button
                    type="button"
                    role="checkbox"
                    aria-checked={on}
                    disabled={full || toggle.isPending}
                    onClick={() =>
                      toggle.mutate(
                        { id: w.id, symbol, on: !on },
                        { onError: (e) => toast.error(errorMessage(e)) },
                      )
                    }
                    className="flex w-full items-center gap-3 rounded-[var(--radius-control)] px-3 py-2.5 text-left text-sm hairline transition-colors hover:bg-surface-2 disabled:opacity-50"
                  >
                    <span
                      aria-hidden="true"
                      className={cn(
                        'grid size-5 place-items-center rounded-[6px] hairline',
                        on && 'border-accent bg-accent text-accent-ink',
                      )}
                    >
                      {on && <Check className="size-3.5" />}
                    </span>
                    <span className="flex-1">{w.name}</span>
                    <span className="num text-xs text-muted">
                      {full ? 'Full' : `${w.symbols.length}/${MAX_SYMBOLS}`}
                    </span>
                  </button>
                </li>
              )
            })}
            {data.length === 0 && <li className="text-sm text-muted">No lists yet. Create one below.</li>}
          </ul>
          {data.length < MAX_WATCHLISTS && (
            <NewWatchlistForm
              onCreated={(w) => toggle.mutate({ id: w.id, symbol, on: true })}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Manage lists from the Markets page: create and delete. */
export function ManageWatchlistsDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const lists = useWatchlists()
  const del = useDeleteWatchlist()
  const [confirming, setConfirming] = useState<string | null>(null)
  const data = lists.data ?? []
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title="Watchlists"
        description={`Up to ${MAX_WATCHLISTS} lists of ${MAX_SYMBOLS} symbols each. Add symbols from an instrument’s page.`}
      >
        <ul className="mb-5 divide-y divide-border">
          {data.map((w) => (
            <li key={w.id} className="flex items-center gap-3 py-2.5 text-sm">
              <span className="min-w-0 flex-1">
                <span className="font-medium">{w.name}</span>
                <span className="block truncate text-xs text-muted">
                  {w.symbols.length ? w.symbols.join(', ') : 'Empty'}
                </span>
              </span>
              {confirming === w.id ? (
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={del.isPending}
                  onClick={() => del.mutate(w.id, { onSettled: () => setConfirming(null) })}
                >
                  Delete “{w.name}”
                </Button>
              ) : (
                <Button
                  size="icon"
                  variant="ghost"
                  aria-label={`Delete list ${w.name}`}
                  onClick={() => setConfirming(w.id)}
                >
                  <Trash2 />
                </Button>
              )}
            </li>
          ))}
          {data.length === 0 && <li className="py-2 text-sm text-muted">No lists yet.</li>}
        </ul>
        {data.length < MAX_WATCHLISTS ? (
          <NewWatchlistForm />
        ) : (
          <p className="text-xs text-muted">You have the maximum of {MAX_WATCHLISTS} lists.</p>
        )}
      </DialogContent>
    </Dialog>
  )
}
