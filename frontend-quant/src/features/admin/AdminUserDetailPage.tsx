import { FileSearch, LockKeyhole, LockKeyholeOpen, Mail, Phone } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { toApiError } from '@/api/errors'
import { useAdminUser } from '@/api/queries'
import type { AccountDto } from '@/api/types'
import { MaskedAccount } from '@/components/MaskedAccount'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { AccountStatusBadge } from '@/features/accounts/components/AccountStatusBadge'
import { ACCOUNT_TYPE_LABEL, accountLabel } from '@/features/accounts/labels'
import { CardError } from '@/features/dashboard/components/CardShell'
import { LOAN_STATUS } from '@/features/loans/labels'
import { formatDate } from '@/lib/time'
import { RoleBadge } from './AdminUsersPage'
import { ReasonDialog } from './components/ReasonDialog'
import { useSetAccountStatus } from './hooks'

/** F-ADM-1 detail + F-ADM-2 freeze/unfreeze (reason required, audited). */
export function AdminUserDetailPage() {
  const { id = '' } = useParams()
  const { data: u, isPending, isError, error, refetch } = useAdminUser(id)
  const [target, setTarget] = useState<AccountDto | null>(null)
  const setStatus = useSetAccountStatus()

  if (isPending) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-16 w-2/3" />
        <Skeleton className="h-40" />
        <Skeleton className="h-40" />
      </div>
    )
  }
  if (isError) {
    return toApiError(error).code === 'NOT_FOUND' ? (
      <EmptyState
        icon={<FileSearch />}
        title="User not found"
        action={
          <Button asChild>
            <Link to="/admin/users">All users</Link>
          </Button>
        }
      />
    ) : (
      <CardError what="this user" onRetry={() => void refetch()} />
    )
  }

  const freezing = target?.status === 'ACTIVE'

  return (
    <div>
      <PageHeader
        back={{ to: '/admin/users', label: 'Users' }}
        title={u.fullName}
        description={
          <span className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <RoleBadge role={u.role} />
            <span className="inline-flex items-center gap-1.5">
              <Mail aria-hidden="true" className="size-3.5" /> {u.email}
            </span>
            <span className="num inline-flex items-center gap-1.5">
              <Phone aria-hidden="true" className="size-3.5" /> {u.phone}
            </span>
            <span>Joined {formatDate(u.createdAt)}</span>
          </span>
        }
      />

      <section aria-labelledby="acc-h">
        <h2 id="acc-h" className="mb-3 text-sm font-medium text-muted">
          Accounts <span className="num">({u.accounts.length})</span>
        </h2>
        {u.accounts.length === 0 ? (
          <Card>
            <p className="text-sm text-muted">No bank accounts.</p>
          </Card>
        ) : (
          <ul className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            {u.accounts.map((a) => (
              <li key={a.id}>
                <Card className="flex h-full flex-col p-4 sm:p-5">
                  <div className="flex items-center justify-between gap-2">
                    <p className="font-medium">{ACCOUNT_TYPE_LABEL[a.type]}</p>
                    <AccountStatusBadge status={a.status} />
                  </div>
                  <MaskedAccount
                    accountNumber={a.accountNumber}
                    className="mt-1 text-sm text-muted"
                  />
                  <Money value={a.balance} className="mt-5 block text-2xl font-medium" />
                  <div className="mt-auto pt-5">
                    {a.status === 'CLOSED' ? null : (
                      <Button
                        size="sm"
                        variant={a.status === 'ACTIVE' ? 'outline' : 'secondary'}
                        onClick={() => setTarget(a)}
                        aria-label={`${a.status === 'ACTIVE' ? 'Freeze' : 'Unfreeze'} ${accountLabel(a)}`}
                      >
                        {a.status === 'ACTIVE' ? (
                          <>
                            <LockKeyhole aria-hidden="true" /> Freeze
                          </>
                        ) : (
                          <>
                            <LockKeyholeOpen aria-hidden="true" /> Unfreeze
                          </>
                        )}
                      </Button>
                    )}
                  </div>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="loan-h" className="mt-8">
        <h2 id="loan-h" className="mb-3 text-sm font-medium text-muted">
          Loans <span className="num">({u.loans.length})</span>
        </h2>
        <Card className="p-0 sm:p-0">
          {u.loans.length === 0 ? (
            <p className="p-5 text-sm text-muted">No loans.</p>
          ) : (
            <ul className="divide-y divide-border">
              {u.loans.map((l) => (
                <li
                  key={l.id}
                  className="flex flex-wrap items-center justify-between gap-3 px-5 py-3 text-sm"
                >
                  <div>
                    <p>
                      <Money value={l.amount} className="font-medium" />{' '}
                      <span className="text-muted">
                        over <span className="num">{l.tenureMonths}</span> months
                      </span>
                    </p>
                    <p className="text-xs text-muted">
                      Applied {formatDate(l.createdAt)} · EMI <Money value={l.emi} />
                    </p>
                  </div>
                  <div className="flex items-center gap-3">
                    {l.status === 'ACTIVE' && (
                      <span className="text-xs text-muted">
                        Outstanding <Money value={l.outstandingPrincipal} className="text-text" />
                      </span>
                    )}
                    <Badge tone={LOAN_STATUS[l.status].tone}>{LOAN_STATUS[l.status].label}</Badge>
                    {l.status === 'PENDING' && (
                      <Button size="sm" variant="secondary" asChild>
                        <Link to="/admin/loans">Review</Link>
                      </Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </section>

      <ReasonDialog
        open={target !== null}
        onOpenChange={(o) => !o && setTarget(null)}
        title={freezing ? 'Freeze this account?' : 'Unfreeze this account?'}
        description={
          freezing
            ? 'A frozen account can still receive money, but can’t send money, trade or repay loans.'
            : 'The customer will be able to send money, trade and repay loans again.'
        }
        summary={
          target && (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
              <dt className="text-muted">Customer</dt>
              <dd className="text-right">{u.fullName}</dd>
              <dt className="text-muted">Account</dt>
              <dd className="text-right">{accountLabel(target)}</dd>
              <dt className="text-muted">Balance</dt>
              <dd className="text-right">
                <Money value={target.balance} />
              </dd>
            </dl>
          )
        }
        reasonRequired
        placeholder={
          freezing ? 'e.g. Customer reported a lost card' : 'e.g. Identity re-verified by phone'
        }
        confirmLabel={freezing ? 'Freeze account' : 'Unfreeze account'}
        destructive={freezing}
        onConfirm={async (reason) => {
          if (!target) return
          // On failure this throws into the dialog; a 409 means it changed under us, and
          // the refetch on settle already shows the real status.
          await setStatus.mutateAsync({
            id: target.id,
            status: freezing ? 'FROZEN' : 'ACTIVE',
            reason,
          })
          toast.success(freezing ? 'Account frozen' : 'Account unfrozen', {
            description: accountLabel(target),
          })
          setTarget(null)
        }}
      />
    </div>
  )
}
