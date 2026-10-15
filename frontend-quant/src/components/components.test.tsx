import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MaskedAccount } from './MaskedAccount'
import { Money } from './Money'
import { RollingMoney } from './RollingMoney'

describe('<Money>', () => {
  it('never relies on colour alone for P&L', () => {
    const { container } = render(<Money value="-1250.5" tone="pnl" />)
    expect(screen.getByText('−₹1,250.50')).toBeInTheDocument()
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('prefixes credits with + and debits with −', () => {
    render(
      <>
        <Money value="500" tone="credit" />
        <Money value="500" tone="debit" />
      </>,
    )
    expect(screen.getByText('+₹500.00')).toBeInTheDocument()
    expect(screen.getByText('−₹500.00')).toBeInTheDocument()
  })
})

describe('<RollingMoney>', () => {
  it('exposes the exact formatted value to assistive tech', () => {
    render(<RollingMoney value="482615.2" />)
    expect(screen.getByText('₹4,82,615.20')).toHaveClass('sr-only')
  })
})

describe('<MaskedAccount>', () => {
  it('masks by default and re-masks 10 s after reveal', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<MaskedAccount accountNumber="502184734821" />)
    expect(screen.getByText('•••• •••• 4821')).toBeInTheDocument()
    expect(screen.queryByText('5021 8473 4821')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Show account number' }))
    expect(screen.getByText('5021 8473 4821')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(10_000))
    expect(screen.getByText('•••• •••• 4821')).toBeInTheDocument()
    vi.useRealTimers()
  })
})
