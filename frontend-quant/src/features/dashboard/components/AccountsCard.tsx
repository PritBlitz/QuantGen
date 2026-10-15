import { Plus } from 'lucide-react'
import { Link } from 'react-router'
import type { AccountDto } from '@/api/types'
import { MaskedAccount } from '@/components/MaskedAccount'
import { Money } from '@/components/Money'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { useAccounts } from '../hooks'
import { CardError, DashCard } from './CardShell'

const TYPE_LABEL: Record<AccountDto['type'], string> = { SAVINGS: 'Savings', CHECKING: 'Checking' }

function StatusBadge({ status }: { status: AccountDto['status'] }) {
  if (status === 'ACTIVE') return null
  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-[0.6875rem] font-medium',
        status === 'FROZEN' ? 'bg-warn/15 text-warn' : 'bg-surface-2 text-muted',
      )}
    >
      {status === 'FROZEN' ? 'Frozen' : 'Closed'}
    </span>
  )
}

export function AccountsCard({ className }: { className?: string }) {
  const { data, isPending, isError, refetch } = useAccounts()

  return (
    <DashCard
      title="Accounts"
      labelledBy="accounts-title"
      className={className}
      action={
        <Button asChild variant="ghost" size="sm" className="-mr-2 text-muted">
          <Link to="/accounts">View all</Link>
        </Button>
      }
    >
      {isPending ? (
        <div className="space-y-2">
          <Skeleton className="h-[72px]" />
          <Skeleton className="h-[72px]" />
        </div>
      ) : isError ? (
        <CardError what="your accounts" onRetry={() => void refetch()} />
      ) : (
        <>
          <ul className="space-y-2">
            {data.map((a) => (
              <li
                key={a.id}
                className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] bg-surface-2 px-4 py-3.5"
              >
                <div className="min-w-0">
                  <p className="flex items-center gap-2 text-sm font-medium">
                    {TYPE_LABEL[a.type]} <StatusBadge status={a.status} />
                  </p>
                  <MaskedAccount
                    accountNumber={a.accountNumber}
                    className="mt-0.5 text-xs text-muted"
                  />
                </div>
                <Money value={a.balance} className="text-base font-medium sm:text-lg" />
              </li>
            ))}
          </ul>
          {data.length < 3 && (
            <Button asChild variant="outline" size="sm" className="mt-3 self-start">
              <Link to="/accounts">
                <Plus aria-hidden="true" /> Open another account
              </Link>
            </Button>
          )}
        </>
      )}
    </DashCard>
  )
}
