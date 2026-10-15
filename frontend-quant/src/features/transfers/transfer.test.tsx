import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { DEMO_USER_ID } from '@/mocks/db'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp } from '@/test/utils'

const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'
const CHECKING_NO = '502184739067'

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

async function fillTransfer(amount: string) {
  const user = userEvent.setup()
  renderApp(`/transfer?from=${SAVINGS}`)
  await user.click(await screen.findByRole('radio', { name: 'New' }))
  await user.type(screen.getByLabelText('Account number'), CHECKING_NO)
  await user.type(screen.getByLabelText('Amount'), amount)
  await user.click(screen.getByRole('button', { name: 'Review transfer' }))
  return user
}

/** Records the Idempotency-Key of every POST /transfers, then defers to the mock. */
function spyOnTransfers() {
  const keys: Array<string | null> = []
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'POST' && new URL(request.url).pathname === '/api/v1/transfers')
      keys.push(request.headers.get('Idempotency-Key'))
  })
  return keys
}

describe('transfer flow', () => {
  it('fills in → reviews → confirms, then shows the server’s result', async () => {
    signIn()
    const user = await fillTransfer('1500')

    // Review: big amount, unverified-name warning, balance after (decimal math).
    expect(await screen.findByText('₹1,500.00')).toBeInTheDocument()
    expect(screen.getByText(/haven’t verified this recipient’s name/)).toBeInTheDocument()
    expect(screen.getByText('₹1,82,750.75')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /send ₹1,500\.00/i }))
    expect(await screen.findByRole('img', { name: 'Transfer completed' })).toBeInTheDocument()
    expect(screen.getByText('Aarav Sharma')).toBeInTheDocument()
    expect(db.accounts.get(SAVINGS)?.balance).toBe('182750.7500')
  })

  it('sends one request for a double-click', async () => {
    signIn()
    const keys = spyOnTransfers()
    server.use(
      http.post('/api/v1/transfers', async () => {
        await delay(150)
        return undefined // fall through to the default mock
      }),
    )
    const user = await fillTransfer('10')
    const send = await screen.findByRole('button', { name: /send ₹10\.00/i })
    await user.dblClick(send)
    await screen.findByRole('img', { name: 'Transfer completed' })
    expect(keys).toHaveLength(1)
  })

  it('retries a failed confirm with the same Idempotency-Key, moving money once', async () => {
    signIn()
    const keys = spyOnTransfers()
    let fail = true
    server.use(
      http.post('/api/v1/transfers', () => {
        if (fail) {
          fail = false
          return HttpResponse.error()
        }
        return undefined
      }),
    )
    const user = await fillTransfer('250')
    await user.click(await screen.findByRole('button', { name: /send ₹250\.00/i }))
    expect(await screen.findByText('We couldn’t confirm this yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByRole('img', { name: 'Transfer completed' })

    expect(keys).toHaveLength(2)
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys[1]).toBe(keys[0])
    expect(db.accounts.get(SAVINGS)?.balance).toBe('184000.7500')
  })

  it('uses a new key after the user edits the transfer', async () => {
    signIn()
    const keys = spyOnTransfers()
    server.use(http.post('/api/v1/transfers', () => HttpResponse.error(), { once: true }))
    const user = await fillTransfer('250')
    await user.click(await screen.findByRole('button', { name: /send ₹250\.00/i }))
    await screen.findByText('We couldn’t confirm this yet.')

    await user.click(screen.getByRole('button', { name: /edit/i }))
    const amount = screen.getByLabelText('Amount')
    await user.clear(amount)
    await user.type(amount, '300')
    await user.click(screen.getByRole('button', { name: 'Review transfer' }))
    await user.click(await screen.findByRole('button', { name: /send ₹300\.00/i }))
    await screen.findByRole('img', { name: 'Transfer completed' })

    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('returns to the form with the server’s field error inline', async () => {
    signIn()
    server.use(
      http.post('/api/v1/transfers', () =>
        HttpResponse.json(
          {
            timestamp: '',
            status: 400,
            code: 'VALIDATION_FAILED',
            message: 'Request validation failed',
            path: '/api/v1/transfers',
            fieldErrors: [{ field: 'toAccountNumber', message: 'account not found' }],
            requestId: 'r',
          },
          { status: 400 },
        ),
      ),
    )
    const user = await fillTransfer('10')
    await user.click(await screen.findByRole('button', { name: /send ₹10\.00/i }))
    expect(await screen.findByText(/couldn’t find an open account/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review transfer' })).toBeInTheDocument()
  })

  it('explains insufficient funds with a next step', async () => {
    signIn()
    server.use(
      http.post('/api/v1/transfers', () =>
        HttpResponse.json(
          {
            timestamp: '',
            status: 422,
            code: 'INSUFFICIENT_FUNDS',
            message: 'Insufficient funds',
            path: '',
            fieldErrors: [],
            requestId: 'r',
          },
          { status: 422 },
        ),
      ),
    )
    const user = await fillTransfer('10')
    await user.click(await screen.findByRole('button', { name: /send ₹10\.00/i }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Not enough balance')
    expect(within(alert).getByRole('button', { name: 'Add money' })).toBeInTheDocument()
  })

  it('validates amounts client-side, including the balance and per-transfer max', async () => {
    signIn()
    const user = await fillTransfer('250000')
    expect(
      await screen.findByText(/most you can send at once is ₹2,00,000\.00/),
    ).toBeInTheDocument()
    const amount = screen.getByLabelText('Amount')
    await user.clear(amount)
    await user.type(amount, '0.5')
    await user.click(screen.getByRole('button', { name: 'Review transfer' }))
    expect(await screen.findByText(/minimum is ₹1\.00/)).toBeInTheDocument()
  })
})

describe('deposit dialog', () => {
  it('enforces ₹1,00,000 per deposit and confirms with server truth', async () => {
    signIn()
    const user = userEvent.setup()
    renderApp(`/accounts/${SAVINGS}`)
    await user.click(await screen.findByRole('button', { name: /add money/i }))
    const dialog = await screen.findByRole('dialog')
    const amount = within(dialog).getByLabelText('Amount')
    await user.type(amount, '100000.01')
    await user.click(within(dialog).getByRole('button', { name: 'Review' }))
    expect(within(dialog).getByText(/add up to ₹1,00,000\.00/)).toBeInTheDocument()

    await user.clear(amount)
    await user.type(amount, '2500')
    await user.click(within(dialog).getByRole('button', { name: 'Review' }))
    await user.click(within(dialog).getByRole('button', { name: /add ₹2,500\.00/i }))
    expect(
      await within(dialog).findByRole('img', { name: 'Deposit confirmed' }),
    ).toBeInTheDocument()
    await waitFor(() => expect(within(dialog).getByText('₹1,86,750.75')).toBeInTheDocument())
  })
})
