import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { cvvFor } from '@/mocks/v2/cards'
import { DEMO_CHECKING } from '@/mocks/v2/state'
import { renderApp, signInAs } from '@/test/utils'

const CARD = 'f8000000-0000-4000-8000-000000000001'

async function enterPin(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('group', { name: 'Transaction PIN' })
  await user.keyboard('246810')
}

describe('virtual cards', () => {
  it('reveals the full number only after the PIN, then hides it on its own', async () => {
    signInAs('CUSTOMER')
    let reveals = 0
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith(`/cards/${CARD}/reveal`)) reveals++
    })
    const user = userEvent.setup()
    renderApp('/cards')
    const card = await screen.findByRole('group', { name: 'Virtual card ending 4417' })
    expect(within(card).getByText('•••• •••• •••• 4417')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Show card details' }))
    await enterPin(user)
    expect(await within(card).findByText('9100 4521 7783 4417')).toBeInTheDocument()
    expect(within(card).getByText(/hides in 30s/)).toBeInTheDocument()
    expect(reveals).toBe(2) // first answered STEP_UP_REQUIRED, the retry carried the token
    expect(db.auditLogs[0]).toMatchObject({ action: 'CARD_REVEALED', targetId: CARD })

    await user.click(screen.getByRole('button', { name: 'Hide now' }))
    expect(within(card).getByText('•••• •••• •••• 4417')).toBeInTheDocument()
  })

  it('wipes the details when the server’s hide-after time runs out', async () => {
    signInAs('CUSTOMER')
    server.use(
      http.post(`/api/v1/cards/${CARD}/reveal`, () =>
        HttpResponse.json({ pan: '9100 4521 7783 4417', cvv: '123', expiry: '09/30', hideAfterSeconds: 1 }),
      ),
    )
    const user = userEvent.setup()
    renderApp('/cards')
    const card = await screen.findByRole('group', { name: 'Virtual card ending 4417' })
    await user.click(screen.getByRole('button', { name: 'Show card details' }))
    expect(await within(card).findByText('123')).toBeInTheDocument()
    await waitFor(() => expect(within(card).queryByText('123')).not.toBeInTheDocument(), { timeout: 3000 })
    expect(within(card).getByText('•••')).toBeInTheDocument()
  })

  it('freezes the card from the server’s answer and blocks revealing while frozen', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/cards')
    await user.click(await screen.findByRole('switch', { name: 'Freeze card' }))
    expect(await screen.findByRole('group', { name: 'Virtual card ending 4417, frozen' })).toBeInTheDocument()
    expect(db.v2.cards[0]).toMatchObject({ status: 'FROZEN', controls: { frozen: true } })
    expect(screen.getByRole('button', { name: 'Show card details' })).toBeDisabled()
  })

  it('shows a friendly page when cards are switched off', async () => {
    signInAs('CUSTOMER')
    db.v2.flags.cards.enabled = false
    renderApp('/cards')
    expect(await screen.findByText('Cards are switched off right now')).toBeInTheDocument()
  })
})

describe('merchant sandbox', () => {
  async function fillCard(user: ReturnType<typeof userEvent.setup>) {
    const card = db.v2.cards[0]!
    await user.type(await screen.findByLabelText('Card number'), card.pan.replace(/\s/g, ''))
    await user.type(screen.getByLabelText('Expiry'), card.expiry.replace('/', ''))
    await user.type(screen.getByLabelText('CVV'), cvvFor(card.pan))
  }

  it('authorizes into a hold, captures, and refunds back to the balance', async () => {
    signInAs('CUSTOMER')
    const before = db.accounts.get(DEMO_CHECKING)!.balance
    const user = userEvent.setup()
    renderApp('/cards/sandbox')
    await user.click(await screen.findByRole('radio', { name: /Filter coffee beans/ }))
    await fillCard(user)
    await user.click(screen.getByRole('button', { name: /^Pay/ }))

    expect(await screen.findByText('Authorized · on hold')).toBeInTheDocument()
    expect(db.v2.holds.some((h) => h.reason === 'CARD_AUTH' && h.amount === '649.0000' && h.status === 'ACTIVE')).toBe(true)
    expect(screen.getByLabelText('CVV')).toHaveValue('')

    expect(await screen.findByText('Paid', {}, { timeout: 8000 })).toBeInTheDocument()
    expect(db.accounts.get(DEMO_CHECKING)!.balance).not.toBe(before)
    await user.click(screen.getByRole('button', { name: 'Refund' }))
    expect(await screen.findByText('Refunded')).toBeInTheDocument()
    expect(db.accounts.get(DEMO_CHECKING)!.balance).toBe(before)
  }, 20_000)

  it('declines a blocked merchant category with the reason', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/cards/sandbox')
    await user.click(await screen.findByRole('radio', { name: /Casino chips/ }))
    await fillCard(user)
    await user.click(screen.getByRole('button', { name: /^Pay/ }))
    expect(await screen.findByText('Declined')).toBeInTheDocument()
    expect(screen.getByText('Merchant category is blocked')).toBeInTheDocument()
    expect(db.v2.holds.filter((h) => h.reason === 'CARD_AUTH')).toHaveLength(1) // only the seeded one
  })

  it('reuses the Idempotency-Key when a checkout is retried, and gets a new one after an edit', async () => {
    signInAs('CUSTOMER')
    const keys: Array<string | null> = []
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && new URL(request.url).pathname.endsWith('/sandbox/merchant/authorize'))
        keys.push(request.headers.get('Idempotency-Key'))
    })
    const user = userEvent.setup()
    renderApp('/cards/sandbox')
    await user.click(await screen.findByRole('radio', { name: /Filter coffee beans/ }))
    await fillCard(user)

    server.use(http.post('/api/v1/sandbox/merchant/authorize', () => HttpResponse.error(), { once: true }))
    await user.click(screen.getByRole('button', { name: /^Pay/ }))
    await waitFor(() => expect(keys).toHaveLength(1))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Pay/ })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: /^Pay/ }))
    expect(await screen.findByText('Authorized · on hold')).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
    expect(keys[1]).toBe(keys[0])

    // The answer cleared the CVV; the next attempt is a new payment with a new key.
    await user.type(screen.getByLabelText('CVV'), cvvFor(db.v2.cards[0]!.pan))
    await user.click(screen.getByRole('button', { name: /^Pay/ }))
    await waitFor(() => expect(keys).toHaveLength(3))
    expect(keys[2]).not.toBe(keys[0])
  }, 20_000)
})

describe('card disputes (F-CARD-4)', () => {
  const STORE_AUTH = 'f8100000-0000-4000-8000-000000000001'

  it('raises a dispute on a captured payment; it shows as disputed and moves no money', async () => {
    signInAs('CUSTOMER')
    const before = db.accounts.get(DEMO_CHECKING)!.balance
    const user = userEvent.setup()
    renderApp('/cards')
    await user.click(await screen.findByRole('button', { name: 'Dispute QuantGen Store payment' }))
    const dialog = await screen.findByRole('dialog', { name: 'Dispute this payment?' })
    // The amount is prefilled with what can still be disputed; the reason is required.
    expect(within(dialog).getByLabelText('Amount to dispute')).toHaveValue('2499.00')
    await user.click(within(dialog).getByRole('button', { name: 'Raise dispute' }))
    expect(await within(dialog).findByText('Choose what went wrong')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('combobox', { name: 'What went wrong?' }))
    await user.click(await screen.findByRole('option', { name: 'I didn’t receive it' }))
    await user.type(within(dialog).getByLabelText(/Details/), '<b>never</b> arrived')
    await user.click(within(dialog).getByRole('button', { name: 'Raise dispute' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Disputed')).toBeInTheDocument()
    const mine = (await screen.findByText('Your disputes')).closest('section') as HTMLElement
    expect(within(mine).getByText('Under review')).toBeInTheDocument()
    expect(db.v2.cardDisputes[0]).toMatchObject({ authId: STORE_AUTH, amount: expect.stringMatching(/^2499\.00(00)?$/), reason: 'NOT_RECEIVED', note: '<b>never</b> arrived' })
    expect(db.accounts.get(DEMO_CHECKING)!.balance).toBe(before)
  })

  it('an admin refunds it once, with one Idempotency-Key per dispute and outcome', async () => {
    db.v2.cardDisputes.unshift({ id: 'd1000000-0000-4000-8000-000000000001', userId: db.v2.cards[0]!.userId, cardId: CARD, authId: STORE_AUTH, last4: '4417', merchant: 'QuantGen Store', amount: '2499.00', reason: 'NOT_RECEIVED', note: '<img src=x onerror=alert(1)>', status: 'OPEN', resolutionReason: null, createdAt: new Date().toISOString(), resolvedAt: null })
    const keys: Array<string | null> = []
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.includes('/admin/card-disputes/')) keys.push(request.headers.get('Idempotency-Key'))
    })
    const before = db.accounts.get(DEMO_CHECKING)!.balance
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/disputes')
    // The customer's note is shown as text, never as markup.
    expect(await screen.findByText('“<img src=x onerror=alert(1)>”')).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Refund QuantGen Store dispute' }))
    const dialog = await screen.findByRole('dialog', { name: 'Refund this dispute?' })
    await user.type(within(dialog).getByRole('textbox'), 'Merchant confirmed it never shipped.')
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    await waitFor(() => expect(db.v2.cardDisputes[0]!.status).toBe('REFUNDED'))
    expect(keys).toHaveLength(1)
    expect(keys[0]).toBeTruthy()
    expect(db.accounts.get(DEMO_CHECKING)!.balance).not.toBe(before)
    expect(db.transactions[0]).toMatchObject({ type: 'REFUND', amount: expect.stringMatching(/^2499\.00(00)?$/), description: 'Dispute refund QuantGen Store' })
  })
})
