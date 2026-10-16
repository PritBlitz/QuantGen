import { ChevronRight, Search, UserX } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useAdminUsers } from '@/api/queries'
import type { AdminUserDto } from '@/api/types'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatDate } from '@/lib/time'
import { useDebouncedValue } from '@/lib/useDebouncedValue'

const PAGE_SIZE = 20

/** F-ADM-1: search by name, email or phone; paged. */
export function AdminUsersPage() {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const q = useDebouncedValue(search.trim(), 300)
  const { data, isPending, isError, refetch, isPlaceholderData } = useAdminUsers({
    q: q || undefined,
    page,
    size: PAGE_SIZE,
  })

  return (
    <div>
      <PageHeader
        title="Users"
        description={
          data ? (
            <>
              <span className="num text-text">{data.totalElements}</span> {q ? 'matching' : 'total'}
            </>
          ) : undefined
        }
      />

      <div className="relative mb-4 max-w-md">
        <Search
          aria-hidden="true"
          className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted"
        />
        <Input
          type="search"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }}
          placeholder="Search name, email or phone"
          aria-label="Search users"
          className="pl-10"
          maxLength={100}
        />
      </div>

      <Card className="p-0 sm:p-0">
        {isPending ? (
          <div className="space-y-2 p-5">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-12" />
            ))}
          </div>
        ) : isError ? (
          <div className="p-5">
            <CardError what="users" onRetry={() => void refetch()} />
          </div>
        ) : data.content.length === 0 ? (
          <EmptyState icon={<UserX />} title="No users match">
            Try part of a name, an email address or a phone number.
          </EmptyState>
        ) : (
          <div className={isPlaceholderData ? 'opacity-60' : undefined}>
            <table className="hidden w-full text-sm md:table">
              <caption className="sr-only">Users</caption>
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted">
                  <th scope="col" className="px-5 py-3 font-normal">
                    Name
                  </th>
                  <th scope="col" className="py-3 pr-3 font-normal">
                    Phone
                  </th>
                  <th scope="col" className="py-3 pr-3 font-normal">
                    Role
                  </th>
                  <th scope="col" className="py-3 pr-3 text-right font-normal">
                    Accounts
                  </th>
                  <th scope="col" className="py-3 pr-5 text-right font-normal">
                    Joined
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((u) => (
                  <tr
                    key={u.id}
                    className="group relative border-b border-border last:border-0 hover:bg-surface-2/60"
                  >
                    <td className="px-5 py-3">
                      <Link
                        to={`/admin/users/${u.id}`}
                        className="font-medium after:absolute after:inset-0 after:content-[''] hover:underline"
                      >
                        {u.fullName}
                      </Link>
                      <p className="text-xs text-muted">{u.email}</p>
                    </td>
                    <td className="num py-3 pr-3 text-muted">{u.phone}</td>
                    <td className="py-3 pr-3">
                      <RoleBadge role={u.role} />
                    </td>
                    <td className="num py-3 pr-3 text-right">{u.accountCount}</td>
                    <td className="py-3 pr-5 text-right text-muted">{formatDate(u.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <ul className="divide-y divide-border md:hidden">
              {data.content.map((u) => (
                <UserCard key={u.id} u={u} />
              ))}
            </ul>
            <div className="px-5 pb-4">
              <Pagination
                page={data.page}
                totalPages={data.totalPages}
                totalElements={data.totalElements}
                onPage={setPage}
                label="users"
              />
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}

export function RoleBadge({ role }: { role: AdminUserDto['role'] }) {
  return role === 'ADMIN' ? <Badge tone="accent">Admin</Badge> : <Badge>Customer</Badge>
}

function UserCard({ u }: { u: AdminUserDto }) {
  return (
    <li>
      <Link to={`/admin/users/${u.id}`} className="flex items-center gap-3 px-4 py-3">
        <div className="min-w-0 flex-1">
          <p className="flex items-center gap-2 text-sm font-medium">
            <span className="truncate">{u.fullName}</span> <RoleBadge role={u.role} />
          </p>
          <p className="truncate text-xs text-muted">{u.email}</p>
          <p className="mt-0.5 text-xs text-muted">
            <span className="num">{u.accountCount}</span> account{u.accountCount === 1 ? '' : 's'} ·
            joined {formatDate(u.createdAt)}
          </p>
        </div>
        <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted" />
      </Link>
    </li>
  )
}
