import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'

const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'
const MEERA_REQUEST = 'f1000000-0000-4000-8000-000000000001'

/** Every POST /transfers: its Idempotency-Key and whether it carried a step-up token. */
function spyOnTransfers() {
  const calls: Array<{ key: string | null; stepUp: boolean; body: Record<string, unknown> }> = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && new URL(request.url).pathname === '/api/v1/transfers')
      calls.push({
        key: request.headers.get('Idempotency-Key'),
        stepUp: !!request.headers.get('X-Step-Up-Token'),
        body: (await request.clone().json()) as Record<string, unknown>,
      })
  })
  return calls
}

async function payByHandle(user: ReturnType<typeof userEvent.setup>, handle: string, amount: string) {
  renderApp(`/transfer?from=${SAVINGS}`)
  await user.click(await screen.findByRole('radio', { name: 'Handle' }))
  await user.type(screen.getByLabelText('QuantGen handle'), handle)
  // Confirmation of payee: the server's masked name appears before review.
  expect(await screen.findByText(/Priya N\./)).toBeInTheDocument()
  await user.type(screen.getByLabelText('Amount'), amount)
  await user.click(screen.getByRole('button', { name: 'Review transfer' }))
}

describe('pay by handle with step-up (F-PAY-5, F-SEC-2)', () => {
  it('asks for the PIN above ₹50,000 and retries once with the same key', async () => {
    signInAs('CUSTOMER')
    const calls = spyOnTransfers()
    const user = userEvent.setup()
    await payByHandle(user, 'priya@qg', '60000')
    await user.click(await screen.findByRole('button', { name: /send ₹60,000\.00/i }))

    await screen.findByRole('group', { name: 'Transaction PIN' })
    await user.keyboard('246810')
    expect(await screen.findByRole('img', { name: 'Transfer completed' })).toBeInTheDocument()

    expect(calls.map((c) => c.stepUp)).toEqual([false, true])
    expect(new Set(calls.map((c) => c.key)).size).toBe(1)
    expect(calls[0]!.body).toMatchObject({ toHandle: 'priya@qg' })
    expect(String(calls[0]!.body.amount)).toMatch(/^60000(\.0+)?$/)
    expect(db.transfers[0]).toMatchObject({ amount: '60000.0000', status: 'COMPLETED' })
  })

  it('moves no money when the PIN prompt is cancelled, and explains it', async () => {
    signInAs('CUSTOMER')
    const before = db.accounts.get(SAVINGS)!.balance
    const calls = spyOnTransfers()
    const user = userEvent.setup()
    await payByHandle(user, 'priya@qg', '60000')
    await user.click(await screen.findByRole('button', { name: /send ₹60,000\.00/i }))
    await screen.findByRole('group', { name: 'Transaction PIN' })
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('group', { name: 'Transaction PIN' })).not.toBeInTheDocument())
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(calls).toHaveLength(1)
    expect(db.accounts.get(SAVINGS)!.balance).toBe(before)
  })
})

describe('payment requests (F-PAY-8)', () => {
  it('pays an incoming request after review, once', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/pay/requests')
    const row = (await screen.findByText(/Dinner at Toit/)).closest('li') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Pay' }))
    const dialog = await screen.findByRole('dialog', { name: 'Pay meera@qg' })
    await user.click(within(dialog).getByRole('button', { name: 'Review' }))
    await user.dblClick(await within(dialog).findByRole('button', { name: 'Pay ₹1,850.00' }))
    expect(await within(dialog).findByRole('img', { name: 'Request paid' })).toBeInTheDocument()
    expect(db.v2.requests.find((r) => r.id === MEERA_REQUEST)?.status).toBe('PAID')
    expect(db.transfers.filter((t) => t.note === 'Dinner at Toit')).toHaveLength(1)
  })

  it('declines and blocks the requester', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/pay/requests')
    await user.click(await screen.findByRole('button', { name: 'Decline and block meera@qg' }))
    await waitFor(() => expect(db.v2.requests.find((r) => r.id === MEERA_REQUEST)?.status).toBe('DECLINED'))
    expect(db.v2.blocked).toContainEqual(expect.objectContaining({ blockedHandle: 'meera@qg' }))
  })
})

describe('saved billers (F-PAY-11)', () => {
  it('saves a biller after fetching, pays it from the saved list, and removes it', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/pay/bills')
    await user.click(await screen.findByRole('button', { name: /BESCOM/ }))
    await user.type(screen.getByLabelText('Account ID'), '1234567890')
    await user.click(screen.getByRole('button', { name: /Fetch bill/ }))
    await user.type(await screen.findByLabelText('Save as (optional)', {}, { timeout: 5000 }), 'Home power')
    await user.click(screen.getByRole('button', { name: /Save biller/ }))
    expect(await screen.findByText(/Saved\. It’s under “Your billers”/)).toBeInTheDocument()

    // The list shows only the masked number; paying fetches this month's bill without it.
    const saved = (await screen.findByText('Your billers')).closest('section') as HTMLElement
    expect(within(saved).getByText('••••7890')).toBeInTheDocument()
    expect(within(saved).queryByText('1234567890')).not.toBeInTheDocument()
    await user.click(within(saved).getByRole('button', { name: 'Pay Home power' }))
    await user.click(await screen.findByRole('button', { name: /Fetch this month’s bill/ }))
    expect(await screen.findByText('Amount due', {}, { timeout: 5000 })).toBeInTheDocument()

    await user.click(within(saved).getByRole('button', { name: 'Remove Home power' }))
    await waitFor(() => expect(screen.queryByText('Your billers')).not.toBeInTheDocument())
    expect(db.v2.savedBillers).toHaveLength(0)
  })
})
