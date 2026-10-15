import { ArrowRight, Plus, Wallet } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { useAccounts } from '@/api/queries'
import type { AccountDto } from '@/api/types'
import { MaskedAccount } from '@/components/MaskedAccount'
import { Money } from '@/components/Money'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { SpotlightCard } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { sumMoney } from '@/lib/money'
import { AccountStatusBadge as StatusBadge } from './components/AccountStatusBadge'
import { DepositDialog } from './components/DepositDialog'
import { OpenAccountDialog } from './components/OpenAccountDialog'
import { ACCOUNT_TYPE_LABEL } from './labels'

export function AccountsPage() {
  const { data, isPending, isError, refetch } = useAccounts()
  const [depositFor, setDepositFor] = useState<AccountDto | null>(null)
  const [opening, setOpening] = useState(false)
  const canOpen = (data?.length ?? 0) < 3

  return (
    <div>
      <PageHeader
        title="Accounts"
        description={
          data ? (
            <>
              Total balance{' '}
              <Money value={sumMoney(data.map((a) => a.balance))} className="text-text" />
            </>
          ) : undefined
        }
        actions={
          <Button
            onClick={() => setOpening(true)}
            disabled={!canOpen}
            title={canOpen ? undefined : 'You can hold up to 3 accounts'}
          >
            <Plus aria-hidden="true" /> Open account
          </Button>
        }
      />

      {isPending ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {[0, 1].map((i) => (
            <Skeleton key={i} className="h-52 rounded-[var(--radius-card)]" />
          ))}
        </div>
      ) : isError ? (
        <CardError what="your accounts" onRetry={() => void refetch()} />
      ) : data.length === 0 ? (
        <EmptyState
          icon={<Wallet />}
          title="No accounts yet"
          action={<Button onClick={() => setOpening(true)}>Open your first account</Button>}
        >
          Open a savings or checking account to start.
        </EmptyState>
      ) : (
        <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {data.map((a) => (
            <li key={a.id}>
              <SpotlightCard className="flex h-full flex-col">
                <div className="flex items-center justify-between gap-2">
                  <h2 className="font-medium">{ACCOUNT_TYPE_LABEL[a.type]}</h2>
                  <StatusBadge status={a.status} />
                </div>
                <MaskedAccount
                  accountNumber={a.accountNumber}
                  className="mt-1 text-sm text-muted"
                />
                <Money value={a.balance} className="mt-8 block text-3xl font-medium" />
                {a.status === 'FROZEN' && (
                  <p className="mt-2 text-xs text-warn">
                    Can receive money, but can’t send or trade.
                  </p>
                )}
                <div className="mt-auto flex gap-2 pt-6">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => setDepositFor(a)}
                    disabled={a.status === 'CLOSED'}
                  >
                    <Plus aria-hidden="true" /> Add money
                  </Button>
                  <Button variant="ghost" size="sm" asChild className="ml-auto">
                    <Link
                      to={`/accounts/${a.id}`}
                      aria-label={`${ACCOUNT_TYPE_LABEL[a.type]} account details`}
                    >
                      Details <ArrowRight aria-hidden="true" />
                    </Link>
                  </Button>
                </div>
              </SpotlightCard>
            </li>
          ))}
        </ul>
      )}

      <DepositDialog
        account={depositFor}
        open={depositFor !== null}
        onOpenChange={(o) => !o && setDepositFor(null)}
      />
      <OpenAccountDialog open={opening} onOpenChange={setOpening} />
    </div>
  )
}
