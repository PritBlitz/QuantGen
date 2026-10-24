import { ArrowRight, Plus, Split, Users } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router'
import { useMutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { payApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import type { GroupDto, SplitType } from '@/api/types'
import { FormField } from '@/components/FormField'
import { Money } from '@/components/Money'
import { AmountInput } from '@/components/money/AmountInput'
import { EmptyState, PageHeader } from '@/components/PageHeader'
import { Panel, PanelError } from '@/components/Panel'
import { Segmented } from '@/components/Segmented'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Label } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { useMe } from '@/features/security/hooks'
import { AMOUNT_INPUT_RE, sumMoney, toDecimal } from '@/lib/money'
import { formatRelative } from '@/lib/time'
import { cn } from '@/lib/utils'
import { Avatar, PayTabs } from './components/PayBits'
import { useGroup, useGroups, useInvalidatePayments } from './hooks'

/** Split groups (F-PAY-12): bookkeeping only until someone settles up. */
export function SplitsPage() {
  const groups = useGroups()
  const me = useMe()
  const [creating, setCreating] = useState(false)
  return (
    <div>
      <PageHeader
        eyebrow="Pay"
        title="Split"
        description="Track shared costs with friends. Nothing moves until someone settles up, and then it’s a normal payment request."
        actions={
          <Button onClick={() => setCreating(true)} disabled={!me.data?.handle}>
            <Plus aria-hidden="true" /> New group
          </Button>
        }
      />
      <PayTabs />
      {groups.isError ? (
        <PanelError what="groups" onRetry={() => void groups.refetch()} />
      ) : !groups.data ? (
        <Skeleton className="h-48 w-full" />
      ) : groups.data.length === 0 ? (
        <Panel title="No groups yet">
          <EmptyState icon={<Users />} title="Start a group">
            Trips, flatmates, dinners: add people by handle and log who paid.
          </EmptyState>
        </Panel>
      ) : (
        <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
          {groups.data.map((g) => (
            <GroupCard key={g.id} g={g} myHandle={me.data?.handle ?? null} />
          ))}
        </ul>
      )}
      {creating && <CreateGroup onClose={() => setCreating(false)} />}
    </div>
  )
}

function GroupCard({ g, myHandle }: { g: GroupDto; myHandle: string | null }) {
  const mine = g.members.find((m) => m.handle === myHandle)
  const bal = toDecimal(mine?.balance ?? '0')
  const total = sumMoney(g.expenses.map((e) => e.amount))
  return (
    <li>
      <Link
        to={`/pay/splits/${g.id}`}
        className="group flex h-full flex-col rounded-[var(--radius-card)] bg-surface p-5 hairline transition-[transform,border-color] hover:-translate-y-0.5 hover:border-border-strong"
      >
        <div className="flex items-start justify-between gap-3">
          <p className="font-medium">{g.name}</p>
          <ArrowRight
            aria-hidden="true"
            className="size-4 text-muted transition-transform group-hover:translate-x-0.5"
          />
        </div>
        <div className="mt-3 flex -space-x-2">
          {g.members.slice(0, 5).map((m) => (
            <Avatar key={m.handle} name={m.displayName} className="ring-2 ring-surface-solid" />
          ))}
        </div>
        <p className="mt-4 text-xs text-muted">
          {g.expenses.length} expenses · <Money value={total} className="text-text" /> total
        </p>
        <p
          className={cn(
            'mt-1 text-sm font-medium',
            bal.gt(0) ? 'text-gain' : bal.lt(0) ? 'text-warn' : 'text-muted',
          )}
        >
          {bal.gt(0) ? (
            <>
              You’re owed <Money value={bal} />
            </>
          ) : bal.lt(0) ? (
            <>
              You owe <Money value={bal.neg()} />
            </>
          ) : (
            'All settled'
          )}
        </p>
      </Link>
    </li>
  )
}

function CreateGroup({ onClose }: { onClose: () => void }) {
  const invalidate = useInvalidatePayments()
  const [name, setName] = useState('')
  const [members, setMembers] = useState('')
  const [error, setError] = useState<string | null>(null)
  const create = useMutation({ mutationFn: payApi.createGroup })
  function submit(e: FormEvent) {
    e.preventDefault()
    const handles = members
      .split(/[\s,]+/)
      .map((h) => h.trim().toLowerCase())
      .filter(Boolean)
      .map((h) => (h.includes('@') ? h : `${h}@qg`))
    if (!name.trim()) return setError('Give the group a name.')
    if (!handles.length) return setError('Add at least one person.')
    create.mutate(
      { name: name.trim(), memberHandles: handles },
      {
        onSuccess: () => {
          toast.success('Group created')
          void invalidate()
          onClose()
        },
        onError: (err) => {
          const ae = toApiError(err)
          setError(
            ae.code === 'INVALID_STATE'
              ? 'Set your handle first (Pay → Your handle).'
              : (ae.fieldErrors[0]?.message ?? errorMessage(ae)),
          )
        },
      },
    )
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="New group" description="You’re added automatically.">
        <form onSubmit={submit} className="space-y-4" noValidate>
          <FormField
            label="Name"
            placeholder="Goa trip"
            maxLength={40}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <FormField
            label="People (handles)"
            placeholder="priya@qg, meera@qg"
            value={members}
            onChange={(e) => setMembers(e.target.value)}
            hint="Separate with commas or spaces."
          />
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              Create
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}

export function SplitGroupPage() {
  const { id = '' } = useParams()
  const group = useGroup(id)
  const me = useMe()
  const invalidate = useInvalidatePayments()
  const [adding, setAdding] = useState(false)
  const settle = useMutation({
    mutationFn: () => payApi.settleGroup(id),
    onSuccess: (reqs) => {
      toast.success(
        reqs.length
          ? `Sent ${reqs.length} payment request${reqs.length === 1 ? '' : 's'}`
          : 'Nothing to settle for you',
      )
      void invalidate()
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const g = group.data
  const mine = g?.members.find((m) => m.handle === me.data?.handle)

  return (
    <div>
      <PageHeader
        back={{ to: '/pay/splits', label: 'All groups' }}
        eyebrow="Split group"
        title={g?.name ?? '…'}
        actions={
          <>
            <Button variant="secondary" onClick={() => setAdding(true)} disabled={!g}>
              <Plus aria-hidden="true" /> Add expense
            </Button>
            <Button
              onClick={() => settle.mutate()}
              disabled={!mine || !toDecimal(mine.balance).gt(0) || settle.isPending}
            >
              <Split aria-hidden="true" /> Settle up
            </Button>
          </>
        }
      />
      {group.isError ? (
        <PanelError what="this group" onRetry={() => void group.refetch()} />
      ) : !g ? (
        <Skeleton className="h-64 w-full" />
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
          <Panel className="lg:col-span-5" eyebrow="Balances" title="Who owes whom">
            <ul className="space-y-2">
              {g.members.map((m) => {
                const b = toDecimal(m.balance)
                return (
                  <li
                    key={m.handle}
                    className="flex items-center gap-3 rounded-xl bg-surface-2/50 p-3 hairline"
                  >
                    <Avatar name={m.displayName} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium">
                        {m.displayName}{' '}
                        {m.handle === me.data?.handle && (
                          <span className="text-xs text-muted">(you)</span>
                        )}
                      </p>
                      <p className="num text-xs text-muted">{m.handle}</p>
                    </div>
                    <span
                      className={cn(
                        'text-right text-sm',
                        b.gt(0) ? 'text-gain' : b.lt(0) ? 'text-warn' : 'text-muted',
                      )}
                    >
                      <span className="block text-[0.6875rem]">
                        {b.gt(0) ? 'is owed' : b.lt(0) ? 'owes' : 'settled'}
                      </span>
                      <Money value={b.abs()} />
                    </span>
                  </li>
                )
              })}
            </ul>
          </Panel>
          <Panel className="lg:col-span-7" eyebrow="Expenses" title={`${g.expenses.length} logged`}>
            {g.expenses.length === 0 ? (
              <EmptyState icon={<Split />} title="No expenses yet" />
            ) : (
              <ul className="divide-y divide-border">
                {g.expenses.map((e) => (
                  <li key={e.id} className="flex items-center justify-between gap-3 py-3">
                    <div>
                      <p className="text-sm font-medium">{e.description}</p>
                      <p className="text-xs text-muted">
                        {e.paidByHandle} paid · {e.splitType.toLowerCase()} split ·{' '}
                        {formatRelative(e.createdAt)}
                      </p>
                    </div>
                    <Money value={e.amount} />
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      )}
      {adding && g && (
        <AddExpense group={g} myHandle={me.data?.handle ?? ''} onClose={() => setAdding(false)} />
      )}
    </div>
  )
}

function AddExpense({
  group,
  myHandle,
  onClose,
}: {
  group: GroupDto
  myHandle: string
  onClose: () => void
}) {
  const invalidate = useInvalidatePayments()
  const [paidBy, setPaidBy] = useState(myHandle)
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<SplitType>('EQUAL')
  const [shares, setShares] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const add = useMutation({
    mutationFn: (b: Parameters<typeof payApi.addExpense>[1]) => payApi.addExpense(group.id, b),
  })

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!description.trim()) return setError('What was it for?')
    if (!AMOUNT_INPUT_RE.test(amount) || toDecimal(amount).lt(1))
      return setError('Enter at least ₹1.')
    let split: Parameters<typeof payApi.addExpense>[1]['split'] = { type }
    if (type !== 'EQUAL') {
      const list = group.members.map((m) => ({ handle: m.handle, value: shares[m.handle] || '0' }))
      if (list.some((s) => !/^\d{1,9}(\.\d{1,2})?$/.test(s.value)))
        return setError('Shares must be numbers.')
      const total = sumMoney(list.map((s) => s.value))
      if (type === 'EXACT' && !total.eq(amount))
        return setError('Exact shares must add up to the amount.')
      if (type === 'PERCENT' && !total.eq(100)) return setError('Percentages must add up to 100.')
      split = { type, shares: list }
    }
    add.mutate(
      { paidByHandle: paidBy, amount, description: description.trim(), split },
      {
        onSuccess: () => {
          toast.success('Expense added')
          void invalidate()
          onClose()
        },
        onError: (err) => setError(toApiError(err).fieldErrors[0]?.message ?? errorMessage(err)),
      },
    )
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent title="Add an expense" className="max-w-lg">
        <form onSubmit={submit} className="space-y-4" noValidate>
          <FormField
            label="What for"
            maxLength={60}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
          <AmountInput label="Amount" value={amount} onValueChange={setAmount} />
          <div className="space-y-1.5">
            <Label>Paid by</Label>
            <Select
              aria-label="Paid by"
              value={paidBy}
              onValueChange={setPaidBy}
              options={group.members.map((m) => ({
                value: m.handle,
                label: m.displayName,
                text: m.displayName,
              }))}
            />
          </div>
          <div className="space-y-1.5">
            <Label>Split</Label>
            <Segmented
              label="Split type"
              value={type}
              onValueChange={setType}
              options={[
                { value: 'EQUAL', label: 'Equally' },
                { value: 'EXACT', label: 'Exact ₹' },
                { value: 'PERCENT', label: 'By %' },
              ]}
            />
          </div>
          {type !== 'EQUAL' && (
            <ul className="space-y-2">
              {group.members.map((m) => (
                <li key={m.handle} className="grid grid-cols-[1fr_8rem] items-center gap-2 text-sm">
                  <span>{m.displayName}</span>
                  <FormField
                    label={`${m.displayName} share`}
                    className="h-9"
                    inputMode="decimal"
                    value={shares[m.handle] ?? ''}
                    onChange={(e) =>
                      setShares((s) => ({
                        ...s,
                        [m.handle]: e.target.value.replace(/[^\d.]/g, ''),
                      }))
                    }
                    aria-label={`${m.displayName} share`}
                  />
                </li>
              ))}
            </ul>
          )}
          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={add.isPending}>
              Add expense
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
