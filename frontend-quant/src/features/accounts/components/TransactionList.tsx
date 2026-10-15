import type { TransactionDto } from '@/api/types'
import { Money } from '@/components/Money'
import { Badge } from '@/components/ui/badge'
import { Tooltip } from '@/components/ui/tooltip'
import { formatAbsolute, formatRelative } from '@/lib/time'
import { TX_TYPE_LABEL } from '../labels'

function When({ iso }: { iso: string }) {
  return (
    <Tooltip content={formatAbsolute(iso)}>
      <time dateTime={iso} tabIndex={0} className="whitespace-nowrap rounded-sm">
        {formatRelative(iso)}
      </time>
    </Tooltip>
  )
}

/** Table on md+, stacked cards on phones (DESIGN.md §3.7). */
export function TransactionList({ items }: { items: TransactionDto[] }) {
  return (
    <>
      <table className="hidden w-full text-sm md:table">
        <caption className="sr-only">Transactions</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted">
            <th scope="col" className="py-2.5 pr-4 font-normal">
              When
            </th>
            <th scope="col" className="py-2.5 pr-4 font-normal">
              Description
            </th>
            <th scope="col" className="py-2.5 pr-4 font-normal">
              Type
            </th>
            <th scope="col" className="py-2.5 pr-4 text-right font-normal">
              Amount
            </th>
            <th scope="col" className="py-2.5 text-right font-normal">
              Balance
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((t) => (
            <tr key={t.id} className="border-b border-border last:border-0">
              <td className="py-3.5 pr-4 text-muted">
                <When iso={t.createdAt} />
              </td>
              <td className="max-w-80 py-3.5 pr-4">
                <p className="truncate font-medium">{t.description}</p>
                {t.counterparty && (
                  <p className="num truncate text-xs text-muted">{t.counterparty}</p>
                )}
              </td>
              <td className="py-3.5 pr-4">
                <Badge>{TX_TYPE_LABEL[t.type]}</Badge>
              </td>
              <td className="py-3.5 pr-4 text-right">
                <Money value={t.amount} tone={t.direction === 'CREDIT' ? 'credit' : 'debit'} />
              </td>
              <td className="py-3.5 text-right text-muted">
                <Money value={t.balanceAfter} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <ul className="divide-y divide-border md:hidden">
        {items.map((t) => (
          <li key={t.id} className="flex items-start justify-between gap-3 py-3.5">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium">{t.description}</p>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
                <When iso={t.createdAt} />
                <Badge>{TX_TYPE_LABEL[t.type]}</Badge>
              </p>
              {t.counterparty && (
                <p className="num mt-1 truncate text-xs text-muted">{t.counterparty}</p>
              )}
            </div>
            <div className="shrink-0 text-right">
              <Money
                value={t.amount}
                tone={t.direction === 'CREDIT' ? 'credit' : 'debit'}
                className="text-sm"
              />
              <p className="mt-0.5 text-xs text-muted">
                <Money value={t.balanceAfter} />
              </p>
            </div>
          </li>
        ))}
      </ul>
    </>
  )
}
