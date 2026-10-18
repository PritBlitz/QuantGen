import { Check, Inbox, X } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { useAdminLoans } from '@/api/queries'
import type { AdminLoanDto, LoanStatus } from '@/api/types'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { Segmented } from '@/components/Segmented'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { LOAN_STATUS } from '@/features/loans/labels'
import { Decimal } from '@/lib/money'
import { formatRelative } from '@/lib/time'
import { usePageClamp } from '@/lib/usePageClamp'
import { cn } from '@/lib/utils'
import { ReasonDialog } from './components/ReasonDialog'
import { UnderwritingMemo } from './components/UnderwritingMemo'
import { useApproveLoan, useRejectLoan } from './hooks'

type Filter = Extract<LoanStatus, 'PENDING' | 'APPROVED' | 'ACTIVE' | 'REJECTED' | 'CLOSED'> | 'ALL'
const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CLOSED', label: 'Closed' },
  { value: 'ALL', label: 'All' },
]

/** EMI as a % of declared income, one decimal (display only, Decimal maths). */
function emiShare(l: AdminLoanDto): string | null {
  const income = new Decimal(l.monthlyIncome)
  if (income.lte(0)) return null
  return new Decimal(l.emi).div(income).times(100).toDecimalPlaces(1).toFixed(1)
}

/** Amount as a multiple of monthly income (F-LOAN-2 caps it at 20×). */
function incomeMultiple(l: AdminLoanDto): string | null {
  const income = new Decimal(l.monthlyIncome)
  if (income.lte(0)) return null
  return new Decimal(l.amount).div(income).toDecimalPlaces(1).toFixed(1)
}

/** F-ADM-3: approve (disburses) or reject (reason required). */
export function AdminLoansPage() {
  const [filter, setFilter] = useState<Filter>('PENDING')
  const [page, setPage] = useState(0)
  const [decision, setDecision] = useState<{
    loan: AdminLoanDto
    kind: 'approve' | 'reject'
  } | null>(null)
  const { data, isPending, isError, refetch, isPlaceholderData } = useAdminLoans({
    status: filter === 'ALL' ? undefined : filter,
    page,
    size: 10,
  })
  usePageClamp(data, page, setPage)
  const approve = useApproveLoan()
  const reject = useRejectLoan()

  const loan = decision?.loan
  const approving = decision?.kind === 'approve'

  return (
    <div>
      <PageHeader
        title="Loan queue"
        description="Approving disburses the full amount into the applicant’s chosen account and starts the EMI schedule."
      />

      <Segmented<Filter>
        label="Status"
        value={filter}
        onValueChange={(v) => {
          setFilter(v)
          setPage(0)
        }}
        options={FILTERS}
        className="mb-4"
      />

      {isPending ? (
        <div className="space-y-3">
          {[0, 1].map((i) => (
            <Skeleton key={i} className="h-44 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : isError ? (
        <Card>
          <CardError what="loans" onRetry={() => void refetch()} />
        </Card>
      ) : data.content.length === 0 ? (
        <Card>
          <EmptyState
            icon={<Inbox />}
            title={filter === 'PENDING' ? 'The queue is empty' : 'No loans here'}
          >
            {filter === 'PENDING' ? 'New applications appear here for review.' : undefined}
          </EmptyState>
        </Card>
      ) : (
        <div
          className={cn('space-y-3', isPlaceholderData && 'opacity-60')}
          inert={isPlaceholderData}
        >
          <ul className="space-y-3">
            {data.content.map((l) => (
              <LoanCard
                key={l.id}
                l={l}
                onApprove={() => setDecision({ loan: l, kind: 'approve' })}
                onReject={() => setDecision({ loan: l, kind: 'reject' })}
              />
            ))}
          </ul>
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            onPage={setPage}
            label="loans"
          />
        </div>
      )}

      <ReasonDialog
        open={decision !== null}
        onOpenChange={(o) => !o && setDecision(null)}
        title={approving ? 'Approve and disburse?' : 'Reject this application?'}
        description={
          approving
            ? 'The money moves now, from loan receivables into the customer’s account. This can’t be undone from here.'
            : 'The customer is told the reason, so keep it clear and respectful.'
        }
        summary={
          loan && (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
              <dt className="text-muted">Applicant</dt>
              <dd className="truncate text-right">{loan.applicant.fullName}</dd>
              <dt className="text-muted">Amount</dt>
              <dd className="text-right">
                <Money value={loan.amount} className="font-medium" />
              </dd>
              <dt className="text-muted">Tenure</dt>
              <dd className="num text-right">{loan.tenureMonths} months</dd>
              <dt className="text-muted">EMI</dt>
              <dd className="text-right">
                <Money value={loan.emi} />
              </dd>
            </dl>
          )
        }
        reasonLabel={approving ? 'Note' : 'Reason'}
        reasonRequired={!approving}
        placeholder={
          approving
            ? 'e.g. Income verified against payslips'
            : 'e.g. EMI would exceed 50% of income'
        }
        confirmLabel={approving ? 'Approve and disburse' : 'Reject application'}
        destructive={!approving}
        onConfirm={async (reason) => {
          if (!loan) return
          if (approving) {
            await approve.mutateAsync({ id: loan.id, reason: reason || undefined })
            toast.success('Loan approved and disbursed', { description: loan.applicant.fullName })
          } else {
            await reject.mutateAsync({ id: loan.id, reason })
            toast.success('Application rejected', { description: loan.applicant.fullName })
          }
          setDecision(null)
        }}
      />
    </div>
  )
}

function LoanCard({
  l,
  onApprove,
  onReject,
}: {
  l: AdminLoanDto
  onApprove: () => void
  onReject: () => void
}) {
  const share = emiShare(l)
  const multiple = incomeMultiple(l)
  const overShare = share !== null && new Decimal(share).gt(50)
  const overMultiple = multiple !== null && new Decimal(multiple).gt(20)
  const status = LOAN_STATUS[l.status]

  return (
    <li>
      <Card className="p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-2">
              <Link
                to={`/admin/users/${l.applicant.userId}`}
                className="font-medium hover:underline"
              >
                {l.applicant.fullName}
              </Link>
              <Badge tone={status.tone}>{status.label}</Badge>
            </p>
            <p className="truncate text-xs text-muted">
              {l.applicant.email} · applied {formatRelative(l.createdAt)}
            </p>
          </div>
          <Money value={l.amount} className="text-2xl font-medium" />
        </div>

        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-4">
          <Fact label="Tenure">
            <span className="num">{l.tenureMonths}</span> months
          </Fact>
          <Fact label="EMI">
            <Money value={l.emi} />
          </Fact>
          <Fact label="Monthly income">
            <Money value={l.monthlyIncome} />
          </Fact>
          <Fact label="EMI / income">
            {share === null ? (
              '—'
            ) : (
              <span className={cn('num', overShare && 'text-loss')}>
                {share}%
                {overShare && (
                  <>
                    {' '}
                    <span className="sr-only">(over the 50% limit)</span>
                  </>
                )}
              </span>
            )}
          </Fact>
        </dl>

        <p className="mt-4 text-sm">
          <span className="text-muted">Purpose: </span>
          {l.purpose}
        </p>
        {multiple !== null && (
          <p className={cn('mt-1 text-xs', overMultiple ? 'text-loss' : 'text-muted')}>
            Amount is <span className="num">{multiple}×</span> monthly income (limit 20×). EMI share
            excludes the applicant’s other loans; the server re-checks both at decision time.
          </p>
        )}
        {l.decisionReason && l.status !== 'PENDING' && (
          <p className="mt-3 rounded-[var(--radius-control)] bg-surface-2 px-3 py-2 text-sm">
            <span className="text-muted">Decision: </span>
            {l.decisionReason}
          </p>
        )}

        {l.status === 'PENDING' && <UnderwritingMemo loanId={l.id} />}

        {l.status === 'PENDING' && (
          <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button
              variant="outline"
              onClick={onReject}
              aria-label={`Reject ${l.applicant.fullName}’s application`}
            >
              <X aria-hidden="true" /> Reject
            </Button>
            <Button
              onClick={onApprove}
              aria-label={`Approve ${l.applicant.fullName}’s application`}
            >
              <Check aria-hidden="true" /> Approve
            </Button>
          </div>
        )}
      </Card>
    </li>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5">{children}</dd>
    </div>
  )
}
