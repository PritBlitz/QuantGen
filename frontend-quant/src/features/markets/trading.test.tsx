import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { DEMO_USER_ID } from '@/mocks/db'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'
import { financialYear } from '@/lib/time'
import { validateOrderPrice } from './validation'

type Captured = { body: Record<string, unknown>; key: string | null }

function captureOrders() {
  const seen: Captured[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && new URL(request.url).pathname === '/api/v1/orders')
      seen.push({
        body: (await request.clone().json()) as Record<string, unknown>,
        key: request.headers.get('Idempotency-Key'),
      })
  })
  return seen
}

/** jsdom is narrow, so the ticket opens as the mobile sheet. */
async function openTicket(user: ReturnType<typeof userEvent.setup>, side: 'Buy' | 'Sell') {
  const open = await screen.findByRole('button', { name: new RegExp(`^${side}$`) })
  await waitFor(() => expect(open).toBeEnabled())
  await user.click(open)
  const sheet = await screen.findByRole('dialog')
  // The sheet opens before the ticket's form renders (it waits for accounts and holdings); on a slow
  // CI runner a getBy straight after found only the heading. Return once the form is there.
  await within(sheet).findByRole('button', { name: /^Review / }, { timeout: 10_000 })
  return sheet
}

describe('order price validation', () => {
  it('takes rupees with up to 2 decimals, above zero', () => {
    expect(validateOrderPrice('1250.5')).toBeUndefined()
    expect(validateOrderPrice('')).toBe('Enter a price')
    expect(validateOrderPrice('0.00')).toMatch(/more than/)
    expect(validateOrderPrice('12.345')).toMatch(/2 decimals/)
  })
  it('works out the Indian financial year', () => {
    expect(financialYear('2026-10-18')).toBe('2026-27')
    expect(financialYear('2027-03-31')).toBe('2026-27')
    expect(financialYear('2027-04-01')).toBe('2027-28')
  })
})

describe('limit and stop-loss orders', () => {
  it('places a limit buy that rests as OPEN with a hold, with one idempotency key', async () => {
    signInAs('CUSTOMER')
    const seen = captureOrders()
    const user = userEvent.setup()
    renderApp('/markets/QGEN')
    const sheet = await openTicket(user, 'Buy')
    // Stop-loss is a sell-only order type.
    expect(within(sheet).queryByRole('radio', { name: 'Stop-loss' })).not.toBeInTheDocument()
    await user.click(within(sheet).getByRole('radio', { name: 'Limit' }))
    const price = within(sheet).getByLabelText('Limit price')
    await user.clear(price)
    await user.type(price, '1000')
    await user.click(within(sheet).getByRole('radio', { name: /Until cancelled/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Review limit buy' }))

    expect(await within(sheet).findByText('Held until it fills')).toBeInTheDocument()
    expect(within(sheet).getByText('90 days')).toBeInTheDocument()
    await user.dblClick(within(sheet).getByRole('button', { name: 'Place limit buy' }))
    expect(await within(sheet).findByText('Order placed')).toBeInTheDocument()
    expect(within(sheet).getByText(/is held in your account until it fills/)).toBeInTheDocument()

    expect(seen).toHaveLength(1)
    expect(seen[0]!.body).toMatchObject({
      symbol: 'QGEN',
      side: 'BUY',
      quantity: 1,
      orderType: 'LIMIT',
      limitPrice: '1000',
      validity: 'GTC',
    })
    expect(seen[0]!.key).toMatch(/^[0-9a-f-]{36}$/)
    const order = db.orders[0]!
    expect(order).toMatchObject({ status: 'OPEN', orderType: 'LIMIT', limitPrice: '1000.0000' })
    expect(
      db.v2.holds.some(
        (h) => h.reason === 'LIMIT_ORDER' && h.status === 'ACTIVE' && h.amount === '1000.0000',
      ),
    ).toBe(true)
  })

  it('offers stop-loss on the sell side and sends the trigger price', async () => {
    signInAs('CUSTOMER')
    const seen = captureOrders()
    const user = userEvent.setup()
    renderApp('/markets/QGEN')
    const sheet = await openTicket(user, 'Sell')
    await user.click(within(sheet).getByRole('radio', { name: 'Stop-loss' }))
    const price = within(sheet).getByLabelText('Trigger price')
    await user.clear(price)
    await user.type(price, '1100.50')
    await user.click(within(sheet).getByRole('button', { name: 'Review stop-loss sell' }))
    await user.click(await within(sheet).findByRole('button', { name: 'Place stop-loss sell' }))
    expect(await within(sheet).findByText(/at market if the price falls to/)).toBeInTheDocument()
    expect(seen[0]!.body).toMatchObject({
      side: 'SELL',
      orderType: 'STOP_LOSS',
      triggerPrice: '1100.50',
      validity: 'DAY',
    })
  })

  it('cancels an open order after confirmation and releases its hold', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    // Rest a limit buy first, through the API like the ticket does.
    const { tradeApi } = await import('@/api/endpoints.v2')
    const accountId = [...db.accounts.values()].find(
      (a) => a.userId === DEMO_USER_ID && a.status === 'ACTIVE',
    )!.id
    const placed = await tradeApi.placeOrder(
      {
        accountId,
        symbol: 'HDFC',
        side: 'BUY',
        quantity: 2,
        orderType: 'LIMIT',
        limitPrice: '1500.00',
        validity: 'DAY',
      },
      crypto.randomUUID(),
    )
    expect(placed.status).toBe('OPEN')

    renderApp('/orders')
    const cancel = (
      await screen.findAllByRole('button', { name: 'Cancel buy order for 2 HDFC' })
    )[0]!
    await user.click(cancel)
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this order?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(db.orders.find((o) => o.id === placed.id)?.status).toBe('CANCELLED')
    expect(db.v2.holds.some((h) => h.reason === 'LIMIT_ORDER' && h.status === 'ACTIVE')).toBe(false)
    expect(await screen.findAllByText('Cancelled')).not.toHaveLength(0)
  })
})

describe('watchlists and price alerts', () => {
  it('filters markets by a watchlist and adds a symbol from its page', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    const { unmount } = renderApp('/markets')
    await user.click(await screen.findByRole('radio', { name: 'Tech' }))
    const table = (await screen.findAllByRole('table', { name: 'Instruments and live prices' }))[0]!
    await waitFor(() =>
      expect(within(table).queryByRole('link', { name: 'QGEN' })).not.toBeInTheDocument(),
    )
    expect(within(table).getByRole('link', { name: 'INFY' })).toBeInTheDocument()
    unmount()

    renderApp('/markets/QGEN')
    await user.click(await screen.findByRole('button', { name: /^Watching\s*QGEN$/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Watch QGEN' })
    const tech = within(dialog).getByRole('checkbox', { name: /Tech/ })
    expect(tech).toHaveAttribute('aria-checked', 'false')
    await user.click(tech)
    await waitFor(() =>
      expect(within(dialog).getByRole('checkbox', { name: /Tech/ })).toHaveAttribute(
        'aria-checked',
        'true',
      ),
    )
    expect(db.v2.watchlists.find((w) => w.name === 'Tech')?.symbols).toContain('QGEN')
  })

  it('sets a price alert only on the right side of the current price', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/markets/INFY')
    const price = await screen.findByLabelText('Price')
    const card = price.closest('form')!
    await user.type(price, '1')
    await user.click(within(card).getByRole('button', { name: 'Set alert' }))
    expect(await within(card).findByText('The price is already above that')).toBeInTheDocument()

    await user.clear(price)
    await user.type(price, '99999')
    await user.click(within(card).getByRole('button', { name: 'Set alert' }))
    await waitFor(() =>
      expect(db.v2.alerts[0]).toMatchObject({
        symbol: 'INFY',
        direction: 'ABOVE',
        price: '99999.0000',
        status: 'ACTIVE',
      }),
    )
    expect(
      await screen.findByRole('button', { name: /Delete alert: INFY rises above/ }),
    ).toBeInTheDocument()
  })
})

describe('strategy lab and tax report', () => {
  it('runs a backtest and shows the result as simulated', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/lab')
    // First visit cold-compiles the lab and its chart; allow for that under a parallel run.
    expect(await screen.findByText('Simulated data.', {}, { timeout: 25_000 })).toBeInTheDocument()
    const slow = screen.getByLabelText('Slow (days)')
    await user.clear(slow)
    await user.type(slow, '5')
    await user.click(screen.getByRole('button', { name: 'Run backtest' }))
    expect(screen.getByText('Slow must be longer than fast')).toBeInTheDocument()
    expect(db.v2.backtests).toHaveLength(0)

    // Period bounds match the server's (PRD §12.9): from ≥ 2021-01-01, 30 days to 5 years.
    const from = screen.getByLabelText('From')
    const to = screen.getByLabelText('To')
    const period = async (f: string, t: string) => {
      fireEvent.change(from, { target: { value: f } })
      fireEvent.change(to, { target: { value: t } })
      await user.click(screen.getByRole('button', { name: 'Run backtest' }))
    }
    await period('2020-12-31', '2022-01-01')
    expect(screen.getByText('Price history starts on 1 Jan 2021')).toBeInTheDocument()
    await period('2024-01-01', '2024-01-30')
    expect(screen.getByText('Pick a period of at least 30 days')).toBeInTheDocument()
    await period('2021-01-01', '2026-01-02')
    expect(screen.getByText('Pick a period of at most 5 years')).toBeInTheDocument()
    await period('2021-01-02', '2026-01-02')
    expect(screen.queryByText(/Pick a period|Price history starts/)).not.toBeInTheDocument()
    expect(db.v2.backtests).toHaveLength(0)

    await user.clear(slow)
    await user.type(slow, '30')
    await user.click(screen.getByRole('button', { name: 'Run backtest' }))
    expect(await screen.findByText('SMA 10/30 crossover')).toBeInTheDocument()
    expect(screen.getByText('Final value')).toBeInTheDocument()
    expect(screen.getByText('Simulated')).toBeInTheDocument()
    expect(db.v2.backtests).toHaveLength(1)
  })

  it('shows realized gains by financial year with a CSV download', async () => {
    signInAs('CUSTOMER')
    renderApp('/portfolio/tax')
    expect(await screen.findByText('Short-term gains')).toBeInTheDocument()
    expect(
      screen.getByRole('radio', {
        name: `FY ${financialYear(new Date().toISOString().slice(0, 10))}`,
      }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Download CSV' })).toBeInTheDocument()
    expect(screen.getAllByText('TCS').length).toBeGreaterThan(0)
  })
})
