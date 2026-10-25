import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { DEMO_SAVINGS, PEOPLE } from '@/mocks/v2/state'
import { renderApp, signInAs } from '@/test/utils'

const EMPTY = 'a1f3c2d4-0009-4b5e-9c7d-999999999999'

/** A second checking account with ₹0 and nothing depending on it. */
function seedEmptyAccount() {
  const base = db.accounts.get(DEMO_SAVINGS)!
  db.accounts.set(EMPTY, {
    ...base,
    id: EMPTY,
    accountNumber: '4021000000009999',
    type: 'CHECKING',
    status: 'ACTIVE',
    balance: '0.0000',
    userId: PEOPLE.demo.id,
  })
}

function spyOnClose() {
  const calls: Array<{ stepUp: boolean }> = []
  server.events.on('request:start', ({ request }) => {
    if (
      request.method === 'POST' &&
      /\/accounts\/[^/]+\/close$/.test(new URL(request.url).pathname)
    )
      calls.push({ stepUp: !!request.headers.get('X-Step-Up-Token') })
  })
  return calls
}

describe('closing an account', () => {
  it('closes an empty account after the PIN step-up', async () => {
    signInAs('CUSTOMER')
    seedEmptyAccount()
    const calls = spyOnClose()
    const user = userEvent.setup()
    renderApp(`/accounts/${EMPTY}`)
    await user.click(await screen.findByRole('button', { name: 'Close' }))
    await user.click(await screen.findByRole('button', { name: 'Close account' }))
    await screen.findByRole('group', { name: 'Transaction PIN' })
    await user.keyboard('246810')

    await waitFor(() => expect(db.accounts.get(EMPTY)!.status).toBe('CLOSED'))
    expect(calls.map((c) => c.stepUp)).toEqual([false, true])
    // Once closed, there's nothing left to close.
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument(),
    )
  })

  it('won’t offer to close an account that still holds money', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp(`/accounts/${DEMO_SAVINGS}`)
    await user.click(await screen.findByRole('button', { name: 'Close' }))
    expect(await screen.findByText(/Move it to another account first/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close account' })).toBeDisabled()
    expect(db.accounts.get(DEMO_SAVINGS)!.status).toBe('ACTIVE')
  })

  it('shows the server’s list of what still depends on the account', async () => {
    signInAs('CUSTOMER')
    seedEmptyAccount()
    db.v2.roundUps.set(EMPTY, { enabled: true, roundTo: 10, goalAccountId: '' })
    const user = userEvent.setup()
    renderApp(`/accounts/${EMPTY}`)
    await user.click(await screen.findByRole('button', { name: 'Close' }))
    await user.click(await screen.findByRole('button', { name: 'Close account' }))
    await screen.findByRole('group', { name: 'Transaction PIN' })
    await user.keyboard('246810')
    expect(await screen.findByText(/round-ups use it/)).toBeInTheDocument()
    expect(db.accounts.get(EMPTY)!.status).toBe('ACTIVE')
  })
})
