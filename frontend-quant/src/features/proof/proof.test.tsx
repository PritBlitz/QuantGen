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
    expect(
      await screen.findByText(
        'Verified. Your balance is in the published total.',
        {},
        { timeout: 8000 },
      ),
    ).toBeInTheDocument()
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
    expect(
      await screen.findByText(
        'Verified. Your balance is in the published total.',
        {},
        { timeout: 8000 },
      ),
    ).toBeInTheDocument()
  })

  it('fails when the server’s proof doesn’t add up (tampered balance)', async () => {
    signInAs('CUSTOMER')
    // Pass the real proof through, but inflate the leaf balance by ₹1.
    server.use(
      http.get(
        '/api/v1/proofs/accounts/:id',
        async ({ request }) => {
          const real = (await (
            await fetch(new Request(request.url, { headers: request.headers }))
          ).json()) as BalanceProofDto
          return HttpResponse.json({
            ...real,
            leaf: { ...real.leaf, balance: new Decimal(real.leaf.balance).plus(1).toFixed(4) },
          })
        },
        { once: true },
      ), // once: the inner fetch then reaches the regular mock handler
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
    await user.click(screen.getByRole('button', { name: 'Full check' }))
    expect(
      await screen.findByText(/Intact through seal.*\(from genesis\)/, {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    // The quick check starts at the last checkpoint and says so.
    await user.click(screen.getByRole('button', { name: 'Quick check' }))
    expect(
      await screen.findByText(/\(from checkpoint #[\d,]+\)/, {}, { timeout: 5000 }),
    ).toBeInTheDocument()
  })
})

describe('external anchoring (F-POB-6)', () => {
  const root = (anchor: unknown) => [
    {
      date: '2026-10-25',
      root: 'ab'.repeat(32),
      totalLiabilities: '1000.00',
      leafCount: 3,
      chainHead: { position: 10, hash: 'cd'.repeat(32) },
      anchor,
    },
  ]

  it('links today’s root to its git commit', async () => {
    const commit = 'e'.repeat(40)
    server.use(
      http.get('/api/v1/public/proofs/roots', () =>
        HttpResponse.json(
          root({
            repo: 'https://github.com/qg/roots.git',
            commit,
            committedAt: '2026-10-25T19:10:00Z',
          }),
        ),
      ),
    )
    signInAs('CUSTOMER')
    renderApp('/proof')
    const link = await screen.findByRole('link', { name: 'commit eeeeeee' })
    expect(link).toHaveAttribute('href', `https://github.com/qg/roots/commit/${commit}`)
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(screen.getByText('roots/2026-10-25.json')).toBeInTheDocument()
  })

  it('never turns a non-https repo into a link', async () => {
    server.use(
      http.get('/api/v1/public/proofs/roots', () =>
        HttpResponse.json(
          root({
            repo: 'javascript:alert(1)',
            commit: 'f'.repeat(40),
            committedAt: '2026-10-25T19:10:00Z',
          }),
        ),
      ),
    )
    signInAs('CUSTOMER')
    renderApp('/proof')
    expect(await screen.findByText('commit fffffff')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /commit fffffff/ })).not.toBeInTheDocument()
  })
})
