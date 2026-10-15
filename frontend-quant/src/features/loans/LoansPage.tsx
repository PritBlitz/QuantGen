import { ArrowRight, Calculator } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useLoans } from '@/api/queries'
import { Money } from '@/components/Money'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Card, SpotlightCard } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { formatINR } from '@/lib/money'
import { formatDate } from '@/lib/time'
import { ApplyDialog } from './components/ApplyDialog'
import { EmiCalculator, type LoanDraft } from './components/EmiCalculator'
import { LOAN_STATUS } from './labels'

export function LoansPage() {
  const { data, isPending, isError, refetch } = useLoans()
  const [draft, setDraft] = useState<LoanDraft | null>(null)

  return (
    <div className="space-y-8">
      <PageHeader title="Loans" description="Fixed 12% a year. Clear EMIs, no hidden charges." />

      <section aria-labelledby="your-loans">
        <h2 id="your-loans" className="mb-3 text-sm font-medium text-muted">
          Your loans
        </h2>
        {isPending ? (
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <Skeleton className="h-40 rounded-[var(--radius-card)]" />
          </div>
        ) : isError ? (
          <CardError what="your loans" onRetry={() => void refetch()} />
        ) : data.length === 0 ? (
          <Card className="text-sm text-muted">
            No loans yet. Use the calculator below to see what you could borrow.
          </Card>
        ) : (
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {data.map((l) => {
              const st = LOAN_STATUS[l.status]
              return (
                <li key={l.id}>
                  <SpotlightCard className="h-full">
                    <Link to={`/loans/${l.id}`} className="flex h-full flex-col">
                      <div className="flex items-center justify-between">
                        <span className="font-display text-3xl">
                          {formatINR(l.amount).replace('.00', '')}
                        </span>
                        <Badge tone={st.tone}>{st.label}</Badge>
                      </div>
                      <dl className="mt-5 grid grid-cols-2 gap-3 text-sm">
                        <div>
                          <dt className="text-xs text-muted">EMI</dt>
                          <dd>
                            <Money value={l.emi} />
                          </dd>
                        </div>
                        <div>
                          <dt className="text-xs text-muted">
                            {l.status === 'ACTIVE' ? 'Outstanding' : 'Tenure'}
                          </dt>
                          <dd>
                            {l.status === 'ACTIVE' ? (
                              <Money value={l.outstandingPrincipal} />
                            ) : (
                              <span className="num">{l.tenureMonths} months</span>
                            )}
                          </dd>
                        </div>
                        {l.nextDueDate && (
                          <div className="col-span-2">
                            <dt className="text-xs text-muted">Next due</dt>
                            <dd className="num">{formatDate(l.nextDueDate)}</dd>
                          </div>
                        )}
                      </dl>
                      <span className="mt-auto inline-flex items-center gap-1 pt-5 text-sm text-muted">
                        Details <ArrowRight aria-hidden="true" className="size-4" />
                      </span>
                    </Link>
                  </SpotlightCard>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <section aria-labelledby="calc">
        <Card className="p-5 sm:p-8">
          <h2 id="calc" className="flex items-center gap-2 text-lg font-medium">
            <Calculator aria-hidden="true" className="size-5 text-accent" /> EMI calculator
          </h2>
          <p className="mt-1 text-sm text-muted">
            Move the sliders to see your EMI. Your eligibility is checked as you go.
          </p>
          <div className="mt-8">
            <EmiCalculator onApply={setDraft} />
          </div>
        </Card>
      </section>

      <ApplyDialog draft={draft} onOpenChange={(o) => !o && setDraft(null)} />
    </div>
  )
}
