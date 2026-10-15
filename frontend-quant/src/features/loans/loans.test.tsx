import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { DEMO_USER_ID } from '@/mocks/db'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp } from '@/test/utils'

const LOAN = 'b0a11111-2222-4333-8444-555566667777'
const SAVINGS = 'a1f3c2d4-0001-4b5e-9c7d-111111111111'

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

describe('loan detail', () => {
  it('shows the schedule and repays the next EMI idempotently', async () => {
    signIn()
    const user = userEvent.setup()
    renderApp(`/loans/${LOAN}`)
    // 7 of 24 paid; EMI 8 is next.
    const repay = await screen.findByRole('complementary', { name: 'Repay' })
    expect(await within(repay).findByText(/EMI 8 of 24/)).toBeInTheDocument()
    expect(screen.getAllByText('Paid')).toHaveLength(7)

    await user.click(within(repay).getByRole('button', { name: 'Review repayment' }))
    await user.click(await within(repay).findByRole('button', { name: /pay ₹9,414\.69/i }))
    expect(
      await within(repay).findByRole('img', { name: 'Repayment confirmed' }),
    ).toBeInTheDocument()

    const loan = db.loans.find((l) => l.id === LOAN)!
    expect(loan.schedule[7]!.status).toBe('PAID')
    expect(db.accounts.get(SAVINGS)?.balance).toBe('174836.0600')
    await waitFor(() => expect(screen.getAllByText('Paid')).toHaveLength(8))
  })

  it('caps a custom repayment at everything still due', async () => {
    signIn()
    const user = userEvent.setup()
    renderApp(`/loans/${LOAN}`)
    const repay = await screen.findByRole('complementary', { name: 'Repay' })
    await user.click(await within(repay).findByRole('radio', { name: 'Custom amount' }))
    await user.type(within(repay).getByLabelText('Amount'), '9999999')
    await user.click(within(repay).getByRole('button', { name: 'Review repayment' }))
    expect(within(repay).getByText(/The most you can pay is/)).toBeInTheDocument()
  })
})

describe('loan application', () => {
  it('walks the stepper and submits exactly one application on double-click', async () => {
    signIn()
    let posts = 0
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && new URL(request.url).pathname === '/api/v1/loans') posts++
    })
    server.use(
      http.post('/api/v1/loans', async () => {
        await delay(200)
        return undefined
      }),
    )
    const user = userEvent.setup()
    renderApp('/loans')
    const apply = await screen.findByRole('button', { name: /apply for ₹2,00,000/i })
    await waitFor(() => expect(apply).toBeEnabled())
    await user.click(apply)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    await user.type(within(dialog).getByLabelText('Purpose'), 'Laptop for work')
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    await user.click(within(dialog).getByRole('button', { name: 'Continue' }))
    const submit = await within(dialog).findByRole('button', { name: 'Submit application' })
    await waitFor(() => expect(submit).toBeEnabled())
    await user.dblClick(submit)
    expect(
      await within(dialog).findByRole('img', { name: 'Application submitted' }),
    ).toBeInTheDocument()
    expect(posts).toBe(1)
    expect(db.loans[0]).toMatchObject({ status: 'PENDING', purpose: 'Laptop for work' })
  })

  it('blocks ineligible quotes and shows why', async () => {
    signIn()
    const user = userEvent.setup()
    renderApp('/loans')
    const income = await screen.findByLabelText('Your monthly income')
    await user.clear(income)
    await user.type(income, '5000')
    expect(await screen.findByText(/at most 20× your monthly income/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /apply for/i })).toBeDisabled()
  })
})
