import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useAccounts, useBeneficiaries } from '@/api/queries'
import type { BeneficiaryDto } from '@/api/types'
import { PageHeader } from '@/components/PageHeader'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { BeneficiariesCard } from './components/BeneficiariesCard'
import { RecentTransfers } from './components/RecentTransfers'
import { TransferFlow } from './components/TransferFlow'

export function TransferPage() {
  const [params] = useSearchParams()
  const accounts = useAccounts()
  const beneficiaries = useBeneficiaries()
  const [picked, setPicked] = useState<BeneficiaryDto | null>(null)

  return (
    <div>
      <PageHeader
        title="Transfer"
        description="Between your accounts or to anyone at QuantGen. Instant and free."
      />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Card className="p-5 sm:p-8">
          {accounts.isPending || beneficiaries.isPending ? (
            <div className="space-y-4">
              <Skeleton className="h-11" />
              <Skeleton className="h-24" />
              <Skeleton className="h-14" />
              <Skeleton className="h-12" />
            </div>
          ) : accounts.isError || beneficiaries.isError ? (
            <CardError
              what="your accounts"
              onRetry={() => {
                void accounts.refetch()
                void beneficiaries.refetch()
              }}
            />
          ) : (
            <TransferFlow
              accounts={accounts.data}
              beneficiaries={beneficiaries.data}
              initialFromId={params.get('from')}
              picked={picked}
            />
          )}
        </Card>
        <div className="space-y-4">
          <BeneficiariesCard onPick={(b) => setPicked({ ...b })} />
          <RecentTransfers />
        </div>
      </div>
    </div>
  )
}
