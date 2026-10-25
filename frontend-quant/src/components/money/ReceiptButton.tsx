import { FileDown } from 'lucide-react'
import { useEffect, useState, type CSSProperties, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { MoneyString } from '@/api/types'
import { Button } from '@/components/ui/button'
import { formatINR } from '@/lib/money'

/**
 * A payment receipt (F-PAY-13), built only from what the server returned for that payment.
 * Account numbers must arrive already masked (`••••1234`); this component never sees a full one.
 */
export interface Receipt {
  title: string
  amount: MoneyString
  /** Server timestamp of the payment. */
  at: string
  /** The full server id: it's what support asks for. */
  reference: string
  rows: Array<{ label: string; value: string }>
}

const istFmt = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: 'Asia/Kolkata',
})

const S: Record<string, CSSProperties> = {
  page: { fontFamily: 'system-ui, sans-serif', fontSize: 12, lineHeight: 1.5, maxWidth: 520 },
  brand: { fontSize: 14, fontWeight: 600, letterSpacing: 0.4 },
  title: { fontSize: 18, fontWeight: 600, margin: '20px 0 4px' },
  amount: { fontSize: 32, fontWeight: 600, margin: '8px 0 20px' },
  table: { width: '100%', borderCollapse: 'collapse' },
  th: { textAlign: 'left', fontWeight: 400, color: '#555', padding: '6px 12px 6px 0' },
  td: { textAlign: 'right', padding: '6px 0', borderBottom: '1px solid #ddd' },
  foot: { marginTop: 24, color: '#555', fontSize: 10 },
}

function ReceiptSheet({ r }: { r: Receipt }) {
  const rows = [
    { label: 'Date and time', value: `${istFmt.format(new Date(r.at))} IST` },
    ...r.rows,
    { label: 'Reference', value: r.reference },
  ]
  return (
    <div style={S.page}>
      <div style={S.brand}>QuantGen</div>
      <h1 style={S.title}>{r.title}</h1>
      <div style={S.amount}>{formatINR(r.amount)}</div>
      <table style={S.table}>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label}>
              <th scope="row" style={S.th}>
                {row.label}
              </th>
              <td style={S.td}>{row.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p style={S.foot}>
        Generated on {istFmt.format(new Date())} IST from your QuantGen records. Account numbers are
        masked. This is not a tax invoice.
      </p>
    </div>
  )
}

/**
 * "Download receipt": prints a receipt-only page, so the browser's "Save as PDF" makes the
 * file. No PDF library and no HTML strings: the sheet is React-rendered into `#receipt-root`.
 */
export function ReceiptButton({
  receipt,
  children = 'Download receipt',
  ...props
}: {
  receipt: Receipt
  children?: ReactNode
} & Omit<React.ComponentProps<typeof Button>, 'onClick' | 'children'>) {
  const [printing, setPrinting] = useState(false)

  useEffect(() => {
    if (!printing) return
    const root = document.documentElement
    const done = () => {
      root.classList.remove('printing-receipt')
      setPrinting(false)
    }
    root.classList.add('printing-receipt')
    window.addEventListener('afterprint', done, { once: true })
    // Let the portal paint before the print dialog snapshots the page.
    const t = window.setTimeout(() => {
      try {
        window.print()
      } catch {
        done()
      }
    }, 50)
    return () => {
      window.clearTimeout(t)
      window.removeEventListener('afterprint', done)
      root.classList.remove('printing-receipt')
    }
  }, [printing])

  return (
    <>
      <Button variant="secondary" {...props} onClick={() => setPrinting(true)}>
        <FileDown aria-hidden="true" /> {children}
      </Button>
      {printing &&
        createPortal(
          <div id="receipt-root" aria-hidden="true">
            <ReceiptSheet r={receipt} />
          </div>,
          document.body,
        )}
    </>
  )
}
