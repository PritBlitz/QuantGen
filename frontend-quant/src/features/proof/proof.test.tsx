import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { BalanceProofDto } from '@/api/types'
import { Decimal } from '@/lib/money'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'

/** Enabled once the user's accounts have loaded. */
async function verifyButton() {
  const b = await screen.findByRole('button', { name: 'Verify' })
  await waitFor(() => expect(b).toBeEnabled())
  return b
}

describe('Proof of Balance', () => {
  it('verifies the signed-in user’s balance in the browser against the published root', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/proof')
    await user.click(await verifyButton())
    expect(await screen.findByText('Verified. Your balance is in the published total.', {}, { timeout: 8000 })).toBeInTheDocument()
    await user.click(screen.getByText('Show the maths, step by step'))
    expect(screen.getAllByText('match')).toHaveLength(2)
  })

  it('verifies an older snapshot too', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/proof')
    const date = await screen.findByLabelText('Snapshot')
    await user.click(date)
    const options = await screen.findAllByRole('option')
    await user.click(options[3] as HTMLElement)
    await user.click(await verifyButton())
    expect(await screen.findByText('Verified. Your balance is in the published total.', {}, { timeout: 8000 })).toBeInTheDocument()
  })

  it('fails when the server’s proof doesn’t add up (tampered balance)', async () => {
    signInAs('CUSTOMER')
    // Pass the real proof through, but inflate the leaf balance by ₹1.
    server.use(
      http.get('/api/v1/proofs/accounts/:id', async ({ request }) => {
        const real = (await (await fetch(new Request(request.url, { headers: request.headers }))).json()) as BalanceProofDto
        return HttpResponse.json({ ...real, leaf: { ...real.leaf, balance: new Decimal(real.leaf.balance).plus(1).toFixed(4) } })
      }, { once: true }), // once: the inner fetch then reaches the regular mock handler
    )
    const user = userEvent.setup()
    renderApp('/proof')
    await user.click(await verifyButton())
    const status = await screen.findByRole('status', {}, { timeout: 8000 })
    expect(within(status).getByText('Verification failed.')).toBeInTheDocument()
    expect(within(status).getByText(/doesn’t match the published root/)).toBeInTheDocument()
  })

  it('lets an admin verify the hash chain and see solvency', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/reconcile')
    expect(await screen.findByText('Solvency')).toBeInTheDocument()
    expect(await screen.findByText(/×$/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Verify chain' }))
    expect(await screen.findByText(/Intact through seal/, {}, { timeout: 5000 })).toBeInTheDocument()
  })
})
