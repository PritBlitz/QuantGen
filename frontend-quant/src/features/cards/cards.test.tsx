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
})
