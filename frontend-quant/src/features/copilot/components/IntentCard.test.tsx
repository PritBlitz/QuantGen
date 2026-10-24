import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { IntentDto } from '@/api/types'
import { IntentCard } from './IntentCard'

const executed = (kind: IntentDto['kind'], resultType: string): IntentDto => ({
  id: crypto.randomUUID(),
  kind,
  status: 'EXECUTED',
  summary: { title: 'Done thing', lines: [] },
  rationale: 'Because you asked.',
  riskLevel: 'MEDIUM',
  requiresStepUp: true,
  proposedBy: { agent: 'PLANNER' },
  conversationId: crypto.randomUUID(),
  failureCode: null,
  resultRef: { type: resultType, id: crypto.randomUUID() },
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  createdAt: new Date().toISOString(),
})

function show(intent: IntentDto) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <IntentCard intent={intent} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('IntentCard result link', () => {
  // The backend's resultRef.type is the result's own type, not the intent kind (PRD §12.6 notes).
  it.each([
    ['FD_BOOKING', 'FIXED_DEPOSIT', 'See savings', '/savings'],
    ['LOAN_APPLICATION', 'LOAN', 'See loans', '/loans'],
    ['BILL_PAYMENT', 'BILL_PAYMENT', 'See bills', '/pay/bills'],
    ['SCHEDULED_TRANSFER', 'SCHEDULED_TRANSFER', 'See scheduled', '/pay/scheduled'],
    ['PAYMENT_REQUEST', 'PAYMENT_REQUEST', 'See requests', '/pay/requests'],
  ] as const)('%s → %s links to %s', (kind, type, label, href) => {
    show(executed(kind, type))
    expect(screen.getByRole('link', { name: label })).toHaveAttribute('href', href)
  })

  it('explains a loan the server refused at approval', () => {
    show({ ...executed('LOAN_APPLICATION', 'LOAN'), status: 'FAILED', resultRef: null, failureCode: 'LOAN_NOT_ELIGIBLE' })
    expect(screen.getByRole('alert')).toHaveTextContent('not eligible for this loan')
  })
})
