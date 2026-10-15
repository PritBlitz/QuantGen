import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { DEMO_USER_ID } from '@/mocks/db'
import { server } from '@/mocks/node'
import { renderApp } from '@/test/utils'

function signInAsDemo() {
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

const card = (name: string) => screen.getByRole('region', { name })

describe('dashboard', () => {
  it('computes net worth as cash + investments − loans, in decimal', async () => {
    server.use(
      http.get('/api/v1/accounts', () =>
        HttpResponse.json([
          {
            id: 'a',
            accountNumber: '111122223333',
            type: 'SAVINGS',
            status: 'ACTIVE',
            balance: '0.10',
            currency: 'INR',
            createdAt: '2026-01-01T00:00:00Z',
          },
          {
            id: 'b',
            accountNumber: '444455556666',
            type: 'CHECKING',
            status: 'FROZEN',
            balance: '0.20',
            currency: 'INR',
            createdAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
      http.get('/api/v1/accounts/:id/transactions', () =>
        HttpResponse.json({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0 }),
      ),
      http.get('/api/v1/portfolio', () =>
        HttpResponse.json({
          holdings: [],
          totalMarketValue: '1000.00',
          totalCost: '0.00',
          totalUnrealizedPnl: '0.00',
          totalRealizedPnl: '0.00',
        }),
      ),
      http.get('/api/v1/loans', () =>
        HttpResponse.json([
          {
            id: 'l',
            amount: '500.00',
            tenureMonths: 6,
            annualRate: '12.00',
            emi: '86.27',
            status: 'ACTIVE',
            outstandingPrincipal: '400.00',
            nextDueDate: '2026-11-05',
            createdAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
    )
    signInAsDemo()
    renderApp('/dashboard')
    // 0.10 + 0.20 + 1000.00 − 400.00 = 600.30 exactly (no 0.30000000000000004).
    const net = await screen.findByText('₹600.30')
    expect(within(card('Net worth')).getByText('₹0.30')).toBeInTheDocument()
    expect(net).toHaveClass('sr-only')
    expect(within(card('Accounts')).getByText('Frozen')).toBeInTheDocument()
    expect(
      within(card('Loan')).getByRole('img', { name: '20% of principal repaid' }),
    ).toBeInTheDocument()
  })

  it('teaches with trending instruments when there are no holdings', async () => {
    server.use(
      http.get('/api/v1/portfolio', () =>
        HttpResponse.json({
          holdings: [],
          totalMarketValue: '0.00',
          totalCost: '0.00',
          totalUnrealizedPnl: '0.00',
          totalRealizedPnl: '0.00',
        }),
      ),
    )
    signInAsDemo()
    renderApp('/dashboard')
    expect(await screen.findByRole('link', { name: /buy your first share/i })).toBeInTheDocument()
  })

  it('shows a retryable error per card instead of failing the page', async () => {
    let fail = true
    server.use(
      http.get('/api/v1/loans', () =>
        fail
          ? HttpResponse.json({ code: 'NOT_FOUND', status: 404 }, { status: 404 })
          : HttpResponse.json([]),
      ),
    )
    signInAsDemo()
    renderApp('/dashboard')
    const loan = await screen.findByText('Couldn’t load your loans.')
    expect(loan).toBeInTheDocument()
    // Other cards still render server data.
    expect(await screen.findByText('₹1,84,250.75')).toBeInTheDocument()

    fail = false
    const retry = within(card('Loan')).getByRole('button', { name: /try again/i })
    await userEvent.setup().click(retry)
    expect(await screen.findByRole('link', { name: /emi calculator/i })).toBeInTheDocument()
  })
})
