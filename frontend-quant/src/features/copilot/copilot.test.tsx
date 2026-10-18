import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { DEMO_SAVINGS, PEOPLE } from '@/mocks/v2/state'
import { renderApp, signInAs } from '@/test/utils'
import { createSseParser } from './stream'

/** Records the Idempotency-Key of every POST /intents/{id}/approve. */
function spyOnApprovals() {
  const calls: Array<{ key: string | null; stepUp: string | null }> = []
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'POST' && /\/intents\/[^/]+\/approve$/.test(new URL(request.url).pathname))
      calls.push({ key: request.headers.get('Idempotency-Key'), stepUp: request.headers.get('X-Step-Up-Token') })
  })
  return calls
}

async function ask(text: string) {
  const user = userEvent.setup()
  renderApp('/copilot')
  await user.type(await screen.findByLabelText('Message Copilot'), `${text}{Enter}`)
  return user
}

const balance = (id: string) => db.accounts.get(id)?.balance

describe('SSE parser', () => {
  it('handles frames split across chunks, CRLF, comments, multi-line data and unknown events', () => {
    const parse = createSseParser()
    expect(parse('event: tok')).toEqual([])
    expect(parse('en\r\ndata: {"text":"Hel')).toEqual([])
    expect(parse('lo"}\r\n\r\n: keep-alive\n\nevent: ping\ndata: {}\n\n')).toEqual([{ type: 'token', text: 'Hello' }])
    expect(parse('event: error\ndata: {"code":"BUDGET_EXHAUSTED",\ndata: "message":"x"}\n\n')).toEqual([
      { type: 'error', code: 'BUDGET_EXHAUSTED', message: 'x' },
    ])
    expect(parse('event: token\ndata: not json\n\n')).toEqual([])
  })
})

describe('Copilot', () => {
  it('streams an answer with agent progress, then the stored message and a trace', async () => {
    signInAs('CUSTOMER')
    const user = await ask('What’s my balance?')

    expect(await screen.findByText('Ledger analyst', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(await screen.findByText(/available in Savings/)).toBeInTheDocument()
    // The conversation appears in history.
    expect(await screen.findByRole('button', { name: /^What’s my balance\?/ })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /How I got this/ }))
    const dialog = await screen.findByRole('dialog', { name: 'How Copilot answered' })
    expect(await within(dialog).findByText('Compose answer')).toBeInTheDocument()
  })

  it('proposes a payment that moves money only after approval, with an Idempotency-Key', async () => {
    signInAs('CUSTOMER')
    const before = balance(DEMO_SAVINGS)
    const calls = spyOnApprovals()
    const user = await ask('Pay Meera 500 for dinner')

    const card = await within(screen.getByRole('log')).findByRole('article', { name: /Proposed action: Pay ₹500.00 to Meera Iyer/ }, { timeout: 8000 })
    expect(within(card).getByText(/Copilot’s reasoning/)).toBeInTheDocument()
    expect(within(card).getByText(`Meera Iyer · ${PEOPLE.meera.handle}`)).toBeInTheDocument()
    // Proposed, not executed: no money has moved.
    expect(balance(DEMO_SAVINGS)).toBe(before)

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    expect(await within(card).findByText('Approved and carried out.')).toBeInTheDocument()
    expect(balance(DEMO_SAVINGS)).not.toBe(before)
    expect(calls).toHaveLength(1)
    expect(calls[0]?.key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('reuses the same Idempotency-Key when an approval is retried after a failure', async () => {
    signInAs('CUSTOMER')
    const calls = spyOnApprovals()
    const user = await ask('Move 2000 to my goa goal')
    const card = await within(screen.getByRole('log')).findByRole('article', { name: /Proposed action: Move/ }, { timeout: 8000 })

    server.use(http.post('/api/v1/intents/:id/approve', () => HttpResponse.error(), { once: true }))
    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    expect(await within(card).findByText('We couldn’t confirm this yet.')).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Try again' }))
    expect(await within(card).findByText('Approved and carried out.')).toBeInTheDocument()
    expect(calls).toHaveLength(2)
    expect(calls[1]?.key).toBe(calls[0]?.key)
  })

  it('asks for the PIN before approving a payment over ₹50,000', async () => {
    signInAs('CUSTOMER')
    const calls = spyOnApprovals()
    const user = await ask('Pay Meera 60000')
    const card = await within(screen.getByRole('log')).findByRole('article', { name: /Proposed action: Pay ₹60,000.00/ }, { timeout: 8000 })
    expect(within(card).getByText(/confirm with your passkey or PIN/)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    await screen.findByRole('group', { name: 'Transaction PIN' })
    await user.keyboard('246810')
    expect(await within(card).findByText('Approved and carried out.')).toBeInTheDocument()
    // First try is refused with STEP_UP_REQUIRED, the retry carries the token and the same key.
    await waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[0]?.stepUp).toBeNull()
    expect(calls[1]?.stepUp).toBeTruthy()
    expect(calls[1]?.key).toBe(calls[0]?.key)
  })

  it('rejecting a proposal leaves balances alone', async () => {
    signInAs('CUSTOMER')
    const before = balance(DEMO_SAVINGS)
    const user = await ask('Pay Meera 700')
    const card = await within(screen.getByRole('log')).findByRole('article', { name: /Proposed action: Pay ₹700.00/ }, { timeout: 8000 })
    await user.click(within(card).getByRole('button', { name: /Not now/ }))
    expect(await within(card).findByText(/You declined this/)).toBeInTheDocument()
    expect(balance(DEMO_SAVINGS)).toBe(before)
  })

  it('shows a friendly page when the ai flag is off', async () => {
    signInAs('CUSTOMER')
    db.v2.flags.ai.enabled = false
    renderApp('/copilot')
    expect(await screen.findByText('Copilot is switched off right now')).toBeInTheDocument()
  })
})
