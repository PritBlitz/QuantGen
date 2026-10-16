import { ScrollText, Search } from 'lucide-react'
import { useState } from 'react'
import { useAuditLogs } from '@/api/queries'
import type { AuditLogDto } from '@/api/types'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Input, Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatAbsolute } from '@/lib/time'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePageClamp } from '@/lib/usePageClamp'
import { AUDIT_ACTIONS, auditAction } from './labels'

const ALL = '__all'
const ACTION_OPTIONS = [
  { value: ALL, label: 'All actions', text: 'All actions' },
  ...Object.entries(AUDIT_ACTIONS).map(([value, m]) => ({ value, label: m.label, text: m.label })),
]

/** F-ADM-5: who did what, when and from which IP. Newest first. */
export function AdminAuditPage() {
  const [actor, setActor] = useState('')
  const [action, setAction] = useState(ALL)
  const [page, setPage] = useState(0)
  const actorQ = useDebouncedValue(actor.trim(), 300)
  const { data, isPending, isError, refetch, isPlaceholderData } = useAuditLogs({
    actor: actorQ || undefined,
    action: action === ALL ? undefined : action,
    page,
    size: 25,
  })
  usePageClamp(data, page, setPage)

  return (
    <div>
      <PageHeader
        title="Audit log"
        description="Security-relevant actions, as recorded by the server."
      />

      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,20rem)_minmax(0,14rem)]">
        <div className="space-y-1.5">
          <Label htmlFor="audit-actor">Actor email</Label>
          <div className="relative">
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted"
            />
            <Input
              id="audit-actor"
              type="search"
              value={actor}
              onChange={(e) => {
                setActor(e.target.value)
                setPage(0)
              }}
              placeholder="e.g. admin@"
              className="pl-10"
              maxLength={254}
            />
          </div>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Select
            id="audit-action"
            value={action}
            onValueChange={(v) => {
              setAction(v)
              setPage(0)
            }}
            options={ACTION_OPTIONS}
          />
        </div>
      </div>

      <Card className="p-0 sm:p-0">
        {isPending ? (
          <div className="space-y-2 p-5">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-10" />
            ))}
          </div>
        ) : isError ? (
          <div className="p-5">
            <CardError what="the audit log" onRetry={() => void refetch()} />
          </div>
        ) : data.content.length === 0 ? (
          <EmptyState icon={<ScrollText />} title="No entries match">
            Clear the filters to see everything.
          </EmptyState>
        ) : (
          <div className={isPlaceholderData ? 'opacity-60' : undefined} inert={isPlaceholderData}>
            <div className="hidden overflow-x-auto lg:block">
              <table className="w-full text-sm">
                <caption className="sr-only">Audit log</caption>
                <thead>
                  <tr className="border-b border-border text-left text-xs text-muted">
                    <th scope="col" className="px-5 py-3 font-normal">
                      When
                    </th>
                    <th scope="col" className="py-3 pr-3 font-normal">
                      Action
                    </th>
                    <th scope="col" className="py-3 pr-3 font-normal">
                      Actor
                    </th>
                    <th scope="col" className="py-3 pr-3 font-normal">
                      Target
                    </th>
                    <th scope="col" className="py-3 pr-3 font-normal">
                      Details
                    </th>
                    <th scope="col" className="py-3 pr-5 font-normal">
                      IP · request
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((a) => (
                    <tr key={a.id} className="border-b border-border align-top last:border-0">
                      <td className="whitespace-nowrap px-5 py-3 text-muted">
                        {formatAbsolute(a.createdAt)}
                      </td>
                      <td className="py-3 pr-3">
                        <ActionBadge action={a.action} />
                      </td>
                      <td className="max-w-48 truncate py-3 pr-3">
                        {a.actorEmail ?? <span className="text-muted">system</span>}
                      </td>
                      <td className="py-3 pr-3 text-muted">
                        <Target a={a} />
                      </td>
                      <td className="max-w-80 py-3 pr-3 [overflow-wrap:anywhere]">
                        {a.details ?? <span className="text-muted">—</span>}
                      </td>
                      <td className="num whitespace-nowrap py-3 pr-5 text-xs text-muted">
                        {a.ip ?? '—'}
                        {a.requestId && <span className="block">{shortId(a.requestId)}</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <ul className="divide-y divide-border lg:hidden">
              {data.content.map((a) => (
                <li key={a.id} className="space-y-1 px-4 py-3 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <ActionBadge action={a.action} />
                    <time dateTime={a.createdAt} className="text-xs text-muted">
                      {formatAbsolute(a.createdAt)}
                    </time>
                  </div>
                  <p className="truncate">{a.actorEmail ?? 'system'}</p>
                  {a.details && <p className="text-muted [overflow-wrap:anywhere]">{a.details}</p>}
                  <p className="num text-xs text-muted">
                    {a.ip ?? '—'}
                    {a.targetType && (
                      <>
                        {' '}
                        · <Target a={a} />
                      </>
                    )}
                  </p>
                </li>
              ))}
            </ul>
            <div className="px-5 pb-4">
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPage={setPage}
                label="entries"
              />
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}

function ActionBadge({ action }: { action: string }) {
  const m = auditAction(action)
  return <Badge tone={m.tone}>{m.label}</Badge>
}

/** Ids are shortened for scanning; the full value is in the title tooltip. */
const shortId = (id: string) => (id.length > 12 ? `${id.slice(0, 8)}…` : id)

function Target({ a }: { a: AuditLogDto }) {
  if (!a.targetType) return <>—</>
  return (
    <span title={a.targetId ?? undefined}>
      {a.targetType.toLowerCase()}
      {a.targetId && <span className="num"> {shortId(a.targetId)}</span>}
    </span>
  )
}
