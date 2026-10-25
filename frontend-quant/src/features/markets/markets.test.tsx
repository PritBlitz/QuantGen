import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { DEMO_USER_ID } from '@/mocks/db'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp } from '@/test/utils'
import { parseTick } from './livePrices'
import { validateQuantity } from './validation'

function signIn() {
  useAuthStore.setState({
    accessToken: `mock.${DEMO_USER_ID}.00000000-0000-4000-8000-000000000000`,
    expiresAt: Date.now() + 900_000,
    status: 'authenticated',
    user: {
      id: DEMO_USER_ID,
      fullName: 'Aarav Sharma',
      email: 'demo@quantgen.dev',
      phone: '+919876543210',
      role: 'CUSTOMER',
      createdAt: '2026-07-02T09:12:00Z',
    },
  })
}

const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'

/** jsdom reports a narrow viewport, so the ticket opens as the mobile sheet. */
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

describe('price feed parsing', () => {
  const good = {
    symbol: 'QGEN',
    name: 'QuantGen',
    sector: 'Financials',
    price: '1284.50',
    changePct24h: '-1.20',
    updatedAt: '2026-10-16T10:00:00Z',
  }
  it('accepts InstrumentDto[] and drops malformed items', () => {
    const out = parseTick(
      JSON.stringify([
        good,
        { ...good, symbol: '<img src=x>' },
        { ...good, price: 1284.5 },
        { ...good, price: '1e9' },
      ]),
    )
    expect(out).toEqual([good])
  })
  it('rejects non-arrays and bad JSON', () => {
    expect(parseTick('{"symbol":"QGEN"}')).toBeNull()
    expect(parseTick('not json')).toBeNull()
    expect(parseTick('[]')).toBeNull()
  })
})

describe('quantity validation', () => {
  it('allows whole shares 1–10,000 and caps sells at holdings', () => {
    expect(validateQuantity('1', 'BUY', 0)).toBeUndefined()
    expect(validateQuantity('10000', 'BUY', 0)).toBeUndefined()
    expect(validateQuantity('10001', 'BUY', 0)).toMatch(/At most/)
    expect(validateQuantity('0', 'BUY', 0)).toMatch(/At least 1/)
    expect(validateQuantity('1.5', 'BUY', 0)).toMatch(/Whole shares/)
    expect(validateQuantity('5', 'SELL', 4)).toBe('You hold 4 shares')
    expect(validateQuantity('1', 'SELL', 0)).toMatch(/don’t hold/)
  })
})

describe('order ticket', () => {
  it('buys: review → confirm → filled, settling cash and shares together', async () => {
    signIn()
    const user = userEvent.setup()
    renderApp('/markets/qgen')
    const sheet = await openTicket(user, 'Buy')
    const qty = within(sheet).getByLabelText('Quantity')
    await user.clear(qty)
    await user.type(qty, '2')
    await user.click(within(sheet).getByRole('button', { name: 'Review buy' }))
    expect(await screen.findByText('Estimated cost')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Confirm buy' }))
    expect(await screen.findByRole('img', { name: 'Order filled' })).toBeInTheDocument()

    const holding = db.holdings.find((h) => h.userId === DEMO_USER_ID && h.symbol === 'QGEN')
    expect(holding?.quantity).toBe(42)
    // Cash went down by exactly the order total (decimal, from the server's fill).
    const order = db.orders[0]!
    const before = '184250.7500'
    const { Decimal } = await import('@/lib/money')
    expect(db.accounts.get(SAVINGS)?.balance).toBe(
      new Decimal(before).minus(order.total).toFixed(4),
    )
  })

  it('shows the holdings error from the server and offers to edit', async () => {
    signIn()
    server.use(
      http.post('/api/v1/orders', () =>
        HttpResponse.json(
          {
            timestamp: '',
            status: 422,
            code: 'INSUFFICIENT_HOLDINGS',
            message: 'x',
            path: '',
            fieldErrors: [],
            requestId: 'r',
          },
          { status: 422 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderApp('/markets/QGEN')
    const sheet = await openTicket(user, 'Sell')
    await user.click(within(sheet).getByRole('button', { name: 'Review sell' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm sell' }))
    expect(await screen.findByText(/don’t hold enough shares/)).toBeInTheDocument()
  })

  it('replays the same key on retry, so a timeout never doubles an order', async () => {
    signIn()
    const keys: Array<string | null> = []
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/api/v1/orders'))
        keys.push(request.headers.get('Idempotency-Key'))
    })
    server.use(http.post('/api/v1/orders', () => HttpResponse.error(), { once: true }))
    const user = userEvent.setup()
    renderApp('/markets/QGEN')
    const sheet = await openTicket(user, 'Buy')
    await user.click(within(sheet).getByRole('button', { name: 'Review buy' }))
    await user.click(await screen.findByRole('button', { name: 'Confirm buy' }))
    await user.click(await screen.findByRole('button', { name: 'Try again' }))
    await screen.findByRole('img', { name: 'Order filled' })
    await waitFor(() => expect(keys).toHaveLength(2))
    expect(keys[1]).toBe(keys[0])
    expect(db.orders).toHaveLength(1)
  })
})

describe('live crypto prices (F-TRD-12)', () => {
  it('shows read-only prices when the flag is on, and nothing when it is off', async () => {
    signIn()
    db.v2.flags.crypto.enabled = true
    const { unmount } = renderApp('/markets')
    const panel = await screen.findByRole('heading', { name: 'Crypto' })
    const card = panel.closest('div.mt-6, section, [aria-labelledby]') as HTMLElement
    expect(await within(card).findByText('BTC')).toBeInTheDocument()
    expect(within(card).getByText('24h n/a')).toBeInTheDocument() // XRP comes without a change
    expect(within(card).queryByRole('button', { name: /buy|trade/i })).not.toBeInTheDocument()
    unmount()

    db.v2.flags.crypto.enabled = false
    renderApp('/markets')
    expect(await screen.findByRole('heading', { name: 'Markets' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Crypto' })).not.toBeInTheDocument()
  })
})
