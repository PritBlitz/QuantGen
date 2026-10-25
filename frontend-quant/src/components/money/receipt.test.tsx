import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TransferDto } from '@/api/types'
import { billReceipt, transferReceipt } from '@/lib/receipts'
import { ReceiptButton } from './ReceiptButton'

const transfer: TransferDto = {
  id: 'b7000000-0000-4000-8000-000000000042',
  fromAccountNumber: '402100004821',
  toAccountNumber: '402100009067',
  toName: 'Priya Nair',
  amount: '1250.5000',
  note: 'Dinner',
  status: 'COMPLETED',
  createdAt: '2026-10-25T06:30:00Z',
}

afterEach(() => vi.restoreAllMocks())

describe('payment receipts (F-PAY-13)', () => {
  it('prints only the receipt, with masked numbers and the full reference', async () => {
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    const user = userEvent.setup()
    render(<ReceiptButton receipt={transferReceipt(transfer)} />)
    await user.click(screen.getByRole('button', { name: /Download receipt/ }))

    await waitFor(() => expect(print).toHaveBeenCalledTimes(1))
    const root = document.getElementById('receipt-root')!
    expect(document.documentElement).toHaveClass('printing-receipt')
    expect(root).toHaveTextContent('Transfer receipt')
    expect(root).toHaveTextContent('₹1,250.50')
    expect(root).toHaveTextContent('Priya Nair · ••••9067')
    expect(root).toHaveTextContent('••••4821')
    expect(root).toHaveTextContent(transfer.id)
    expect(root).toHaveTextContent('25 Oct 2026, 12:00 IST')
    // Full account numbers never reach the page.
    expect(root.textContent).not.toContain('402100004821')
    expect(root.textContent).not.toContain('402100009067')

    fireEvent(window, new Event('afterprint'))
    await waitFor(() => expect(document.getElementById('receipt-root')).toBeNull())
    expect(document.documentElement).not.toHaveClass('printing-receipt')
  })

  it('builds a bill receipt with the masked consumer and paying account', () => {
    const r = billReceipt(
      {
        id: 'b8000000-0000-4000-8000-000000000001',
        billerId: 'x',
        billerName: 'BESCOM',
        category: 'ELECTRICITY',
        consumerNumberMasked: '••••7781',
        amount: '1840.0000',
        fromAccountId: 'y',
        transactionId: 'z',
        createdAt: '2026-10-25T06:30:00Z',
      },
      '402100004821',
    )
    expect(r.rows).toEqual([
      { label: 'Biller', value: 'BESCOM' },
      { label: 'Consumer number', value: '••••7781' },
      { label: 'Paid from', value: 'Account ••••4821' },
      { label: 'Status', value: 'Paid' },
    ])
  })
})
