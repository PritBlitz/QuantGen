import { ArrowLeftRight, Download, FileSearch, Plus, X } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useAccount, useTransactions } from '@/api/queries'
import type { TransactionQuery, TransactionType } from '@/api/types'
import { FormField } from '@/components/FormField'
import { MaskedAccount } from '@/components/MaskedAccount'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Pagination } from '@/components/Pagination'
import { RollingMoney } from '@/components/RollingMoney'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { CardError } from '@/features/dashboard/components/CardShell'
import { todayIST } from '@/lib/time'
import { AccountStatusBadge as StatusBadge } from './components/AccountStatusBadge'
import { DepositDialog } from './components/DepositDialog'
import { StatementDialog } from './components/StatementDialog'
import { TransactionList } from './components/TransactionList'
import { ACCOUNT_TYPE_LABEL, TX_TYPE_LABEL } from './labels'

const PAGE_SIZE = 20
const ALL = 'ALL'

export function AccountDetailPage() {
  const { id = '' } = useParams()
  const account = useAccount(id)
  const [page, setPage] = useState(0)
  const [type, setType] = useState<TransactionType | typeof ALL>(ALL)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [depositOpen, setDepositOpen] = useState(false)
  const [statementOpen, setStatementOpen] = useState(false)

  const rangeError = from && to && from > to ? 'Start date is after end date' : undefined
  const query: TransactionQuery = {
    page,
    size: PAGE_SIZE,
    ...(type !== ALL ? { type } : {}),
    ...(from && !rangeError ? { from } : {}),
    ...(to && !rangeError ? { to } : {}),
  }
  const txs = useTransactions(id, query)
  const filtered = type !== ALL || !!from || !!to

  if (account.isError && account.error.status === 404) {
    return (
      <EmptyState
        icon={<FileSearch />}
        title="Account not found"
        action={
          <Button asChild variant="secondary">
            <Link to="/accounts">Back to accounts</Link>
          </Button>
        }
      >
        It may have been closed, or the link is wrong.
      </EmptyState>
    )
  }

  const a = account.data
  const resetPage =
    <T,>(set: (v: T) => void) =>
    (v: T) => {
      set(v)
      setPage(0)
    }

  return (
    <div>
      <PageHeader
        back={{ to: '/accounts', label: 'Accounts' }}
        title={a ? `${ACCOUNT_TYPE_LABEL[a.type]} account` : <Skeleton className="h-12 w-72" />}
        description={a ? <MaskedAccount accountNumber={a.accountNumber} /> : undefined}
        actions={
          a && (
            <>
              <Button variant="secondary" onClick={() => setStatementOpen(true)}>
                <Download aria-hidden="true" /> Statement
              </Button>
              {a.status === 'ACTIVE' && (
                <Button variant="secondary" asChild>
                  <Link to={`/transfer?from=${encodeURIComponent(a.id)}`}>
                    <ArrowLeftRight aria-hidden="true" /> Transfer
                  </Link>
                </Button>
              )}
              <Button onClick={() => setDepositOpen(true)} disabled={a.status === 'CLOSED'}>
                <Plus aria-hidden="true" /> Add money
              </Button>
            </>
          )
        }
      />

      <Card className="mb-6">
        {account.isPending ? (
          <Skeleton className="h-16 w-1/2" />
        ) : account.isError ? (
          <CardError what="this account" onRetry={() => void account.refetch()} />
        ) : (
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="text-sm text-muted">Available balance</p>
              <div aria-live="polite" aria-atomic="true">
                <RollingMoney
                  value={account.data.balance}
                  className="font-display text-[clamp(2.5rem,8vw,4rem)]"
                />
              </div>
            </div>
            <StatusBadge status={account.data.status} />
          </div>
        )}
      </Card>

      <Card>
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <h2 className="text-lg font-medium">Transactions</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-[repeat(3,minmax(0,11rem))_auto] sm:items-end">
            <div className="col-span-2 space-y-1.5 sm:col-span-1">
              <Label htmlFor="tx-type">Type</Label>
              <Select
                id="tx-type"
                value={type}
                onValueChange={(v) => resetPage(setType)(v as TransactionType | typeof ALL)}
                options={[
                  { value: ALL, label: 'All types' },
                  ...Object.entries(TX_TYPE_LABEL).map(([value, label]) => ({ value, label })),
                ]}
              />
            </div>
            <FormField
              label="From"
              type="date"
              value={from}
              max={to || todayIST()}
              onChange={(e) => resetPage(setFrom)(e.target.value)}
            />
            <FormField
              label="To"
              type="date"
              value={to}
              min={from || undefined}
              max={todayIST()}
              onChange={(e) => resetPage(setTo)(e.target.value)}
              error={rangeError}
            />
            {filtered && (
              <Button
                variant="ghost"
                size="sm"
                className="col-span-2 mb-1 sm:col-span-1"
                onClick={() => {
                  setType(ALL)
                  setFrom('')
                  setTo('')
                  setPage(0)
                }}
              >
                <X aria-hidden="true" /> Clear
              </Button>
            )}
          </div>
        </div>

        <div className="mt-4" aria-busy={txs.isFetching}>
          {txs.isPending ? (
            <div className="space-y-2">
              {[0, 1, 2, 3, 4].map((i) => (
                <Skeleton key={i} className="h-12" />
              ))}
            </div>
          ) : txs.isError ? (
            <CardError what="transactions" onRetry={() => void txs.refetch()} />
          ) : txs.data.content.length === 0 ? (
            <EmptyState
              icon={<FileSearch />}
              title={filtered ? 'No matching transactions' : 'No transactions yet'}
            >
              {filtered ? 'Try a different type or date range.' : 'Add money to get started.'}
            </EmptyState>
          ) : (
            <div className={txs.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
              <TransactionList items={txs.data.content} />
              <Pagination
                page={txs.data.page}
                totalPages={txs.data.totalPages}
                totalElements={txs.data.totalElements}
                onPage={setPage}
                label="transactions"
              />
            </div>
          )}
        </div>
      </Card>

      {a && (
        <>
          <DepositDialog account={a} open={depositOpen} onOpenChange={setDepositOpen} />
          <StatementDialog account={a} open={statementOpen} onOpenChange={setStatementOpen} />
        </>
      )}
    </div>
  )
}
