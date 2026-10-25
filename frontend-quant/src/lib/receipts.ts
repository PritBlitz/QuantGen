import type { BillPaymentDto, TransferDto } from '@/api/types'
import type { Receipt } from '@/components/money/ReceiptButton'
import { maskShort } from '@/lib/mask'

/** Receipt for a completed transfer (F-PAY-13). Account numbers are masked here, never later. */
export function transferReceipt(t: TransferDto): Receipt {
  return {
    title: 'Transfer receipt',
    amount: t.amount,
    at: t.createdAt,
    reference: t.id,
    rows: [
      { label: 'From', value: `Account ${maskShort(t.fromAccountNumber)}` },
      { label: 'To', value: `${t.toName} · ${maskShort(t.toAccountNumber)}` },
      ...(t.note ? [{ label: 'Note', value: t.note }] : []),
      { label: 'Status', value: t.status === 'COMPLETED' ? 'Completed' : t.status },
    ],
  }
}

/** Receipt for a bill payment. `fromAccountNumber` is the paying account's number, if known. */
export function billReceipt(b: BillPaymentDto, fromAccountNumber?: string): Receipt {
  return {
    title: 'Bill payment receipt',
    amount: b.amount,
    at: b.createdAt,
    reference: b.id,
    rows: [
      { label: 'Biller', value: b.billerName },
      { label: 'Consumer number', value: b.consumerNumberMasked },
      ...(fromAccountNumber
        ? [{ label: 'Paid from', value: `Account ${maskShort(fromAccountNumber)}` }]
        : []),
      { label: 'Status', value: 'Paid' },
    ],
  }
}
