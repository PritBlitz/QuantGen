import { FileSearch } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { useAccounts, useLoan } from '@/api/queries'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { ProgressRing } from '@/components/ProgressRing'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatINR, ratio, toDecimal } from '@/lib/money'
import { cn } from '@/lib/utils'
import { formatAbsolute, formatDate } from '@/lib/time'
import { RepayPanel } from './components/RepayPanel'
import { INSTALLMENT_STATUS, LOAN_STATUS } from './labels'

export function LoanDetailPage() {
  const { id = '' } = useParams()
  const loan = useLoan(id)
  const accounts = useAccounts()

  if (loan.isError && loan.error.status === 404) {
    return (
      <EmptyState
        icon={<FileSearch />}
        title="Loan not found"
        action={
          <Button asChild variant="secondary">
            <Link to="/loans">Back to loans</Link>
          </Button>
        }
      />
    )
  }

  const l = loan.data
  const repaid = l ? toDecimal(l.amount).minus(l.outstandingPrincipal) : null
  const status = l ? LOAN_STATUS[l.status] : null
  const nextNo = l?.schedule.find((r) => r.status !== 'PAID')?.installmentNo

  return (
    <div>
      <PageHeader
        back={{ to: '/loans', label: 'Loans' }}
        title={
          l ? `${formatINR(l.amount).replace('.00', '')} loan` : <Skeleton className="h-12 w-64" />
        }
        description={l ? `${l.purpose} · applied ${formatAbsolute(l.createdAt)}` : undefined}
        actions={status && <Badge tone={status.tone}>{status.label}</Badge>}
      />

      {loan.isPending ? (
        <Skeleton className="h-72 rounded-[var(--radius-card)]" />
      ) : loan.isError ? (
        <CardError what="this loan" onRetry={() => void loan.refetch()} />
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
          <div className="space-y-4">
            <Card>
              <div className="flex flex-col gap-6 sm:flex-row sm:items-center">
                {loan.data.status === 'ACTIVE' || loan.data.status === 'CLOSED' ? (
                  <ProgressRing
                    value={
                      loan.data.status === 'CLOSED' ? 1 : ratio(repaid ?? '0', loan.data.amount)
                    }
                    label={`${Math.round((loan.data.status === 'CLOSED' ? 1 : ratio(repaid ?? '0', loan.data.amount)) * 100)}% of principal repaid`}
                  />
                ) : null}
                <dl className="grid flex-1 grid-cols-2 gap-x-4 gap-y-4 text-sm">
                  <div>
                    <dt className="text-xs text-muted">Outstanding</dt>
                    <dd className="mt-0.5 text-lg font-medium">
                      <Money value={loan.data.outstandingPrincipal} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted">Monthly EMI</dt>
                    <dd className="mt-0.5 text-lg font-medium">
                      <Money value={loan.data.emi} />
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted">Rate</dt>
                    <dd className="num mt-0.5">{loan.data.annualRate}% p.a.</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted">Tenure</dt>
                    <dd className="num mt-0.5">{loan.data.tenureMonths} months</dd>
                  </div>
                  {loan.data.nextDueDate && (
                    <div>
                      <dt className="text-xs text-muted">Next due</dt>
                      <dd className="num mt-0.5">{formatDate(loan.data.nextDueDate)}</dd>
                    </div>
                  )}
                  {loan.data.decisionReason && (
                    <div className="col-span-2">
                      <dt className="text-xs text-muted">Decision</dt>
                      <dd className="mt-0.5">{loan.data.decisionReason}</dd>
                    </div>
                  )}
                </dl>
              </div>
              {loan.data.status === 'PENDING' && (
                <p className="mt-5 rounded-[var(--radius-control)] bg-warn/10 p-3.5 text-sm">
                  Your application is with our team. Once approved, the money is paid into your
                  account and the EMI schedule starts.
                </p>
              )}
            </Card>

            {loan.data.schedule.length > 0 && (
              <Card>
                <h2 className="font-medium">Schedule</h2>
                <ol className="mt-4">
                  {loan.data.schedule.map((r) => {
                    const st = INSTALLMENT_STATUS[r.status]
                    const isNext = r.installmentNo === nextNo
                    return (
                      <li
                        key={r.installmentNo}
                        aria-current={isNext ? 'step' : undefined}
                        className={cn(
                          'relative flex items-center gap-4 border-l-2 py-3 pl-5',
                          r.status === 'PAID' ? 'border-accent' : 'border-border',
                          isNext && 'rounded-r-[var(--radius-control)] bg-surface-2',
                        )}
                      >
                        <span
                          aria-hidden="true"
                          className={cn(
                            'absolute -left-[7px] size-3 rounded-full border-2',
                            r.status === 'PAID'
                              ? 'border-accent bg-accent'
                              : r.status === 'OVERDUE'
                                ? 'border-warn bg-bg'
                                : 'border-border bg-bg',
                          )}
                        />
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-medium">
                            EMI {r.installmentNo}
                            <span className="num ml-2 text-xs font-normal text-muted">
                              {formatDate(r.dueDate)}
                            </span>
                          </p>
                          <p className="num text-xs text-muted">
                            {formatINR(r.principal)} principal · {formatINR(r.interest)} interest
                          </p>
                        </div>
                        <div className="text-right">
                          <Money value={r.emi} className="text-sm" />
                          {r.status !== 'PAID' && toDecimal(r.amountDue).lt(r.emi) && (
                            <p className="text-xs text-muted">
                              <Money value={r.amountDue} /> left
                            </p>
                          )}
                          <div className="mt-0.5">
                            <Badge tone={st.tone}>
                              {isNext && r.status === 'PENDING' ? 'Next' : st.label}
                            </Badge>
                          </div>
                        </div>
                      </li>
                    )
                  })}
                </ol>
              </Card>
            )}
          </div>

          {loan.data.status === 'ACTIVE' && (
            <aside aria-label="Repay">
              <Card className="lg:sticky lg:top-24">
                <h2 className="mb-4 font-medium">Repay</h2>
                {accounts.data ? (
                  <RepayPanel loan={loan.data} accounts={accounts.data} />
                ) : (
                  <Skeleton className="h-48" />
                )}
              </Card>
            </aside>
          )}
        </div>
      )}
    </div>
  )
}
