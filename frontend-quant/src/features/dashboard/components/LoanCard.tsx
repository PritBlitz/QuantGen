import { Calculator } from 'lucide-react'
import { Link } from 'react-router'
import { Money } from '@/components/Money'
import { ProgressRing } from '@/components/ProgressRing'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Decimal, ratio } from '@/lib/money'
import { formatDate } from '@/lib/time'
import { useLoans } from '../hooks'
import { CardError, DashCard } from './CardShell'

export function LoanCard({ className }: { className?: string }) {
  const { data, isPending, isError, refetch } = useLoans()
  const active = data?.find((l) => l.status === 'ACTIVE')
  const pendingApp = data?.find((l) => l.status === 'PENDING')

  let body
  if (isPending) {
    body = (
      <div className="flex items-center gap-6">
        <Skeleton className="size-32 rounded-full" />
        <div className="flex-1 space-y-3">
          <Skeleton className="h-5" />
          <Skeleton className="h-5 w-2/3" />
        </div>
      </div>
    )
  } else if (isError) {
    body = <CardError what="your loans" onRetry={() => void refetch()} />
  } else if (active) {
    const repaid = new Decimal(active.amount).minus(active.outstandingPrincipal)
    const progress = ratio(repaid, active.amount)
    body = (
      <div className="flex flex-col gap-6 min-[420px]:flex-row min-[420px]:items-center">
        <ProgressRing
          value={progress}
          label={`${Math.round(progress * 100)}% of principal repaid`}
        />
        <dl className="grid flex-1 grid-cols-2 gap-x-4 gap-y-3 text-sm">
          <div className="col-span-2">
            <dt className="text-xs text-muted">Outstanding</dt>
            <dd className="mt-0.5 text-lg font-medium">
              <Money value={active.outstandingPrincipal} />
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Next EMI</dt>
            <dd className="mt-0.5">
              <Money value={active.emi} />
            </dd>
          </div>
          <div>
            <dt className="text-xs text-muted">Due</dt>
            <dd className="num mt-0.5">
              {active.nextDueDate ? formatDate(active.nextDueDate) : '—'}
            </dd>
          </div>
        </dl>
      </div>
    )
  } else {
    body = (
      <div className="flex flex-1 flex-col items-start justify-center gap-3">
        <p className="text-sm text-muted">
          {pendingApp
            ? 'Your loan application is being reviewed. We’ll notify you when there’s a decision.'
            : 'No active loans. See what you could borrow at 12% a year.'}
        </p>
        <Button asChild size="sm" variant="secondary">
          <Link to="/loans">
            <Calculator aria-hidden="true" /> {pendingApp ? 'View application' : 'EMI calculator'}
          </Link>
        </Button>
      </div>
    )
  }

  return (
    <DashCard
      title="Loan"
      labelledBy="loan-title"
      className={className}
      action={
        active ? (
          <Button asChild variant="ghost" size="sm" className="-mr-2 text-muted">
            <Link to="/loans">Repay</Link>
          </Button>
        ) : undefined
      }
    >
      {body}
    </DashCard>
  )
}
