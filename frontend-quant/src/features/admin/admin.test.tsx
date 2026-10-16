import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'

const PRIYA_LOAN = 'c0000000-0000-4000-8000-000000000001'
const KAVYA_LOAN = 'c0000000-0000-4000-8000-000000000002'
const PRIYA_ACC = 'a2000000-0000-4000-8000-000000000001'
const MEERA = '11110000-0000-4000-8000-000000000002'
const MEERA_SAVINGS = 'a2000000-0000-4000-8000-000000000002'

describe('admin access', () => {
  it('customers get a plain 404 for admin URLs and never see admin nav', async () => {
    signInAs('CUSTOMER')
    renderApp('/admin/users')
    expect(await screen.findByRole('heading', { name: 'Nothing here.' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Loan queue' })).not.toBeInTheDocument()
  })

  it('admins land on the overview with live stats', async () => {
    signInAs('ADMIN')
    renderApp('/admin')
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    const figures = await screen.findByRole('region', { name: 'Key figures' })
    // Customers only (backend M5): demo + 4 seeded customers.
    expect(await within(figures).findByText('5')).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: /loan queues*2 pending/i }),
    ).toBeInTheDocument()
  })
})

describe('loan queue', () => {
  it('approves once on double-click and disburses into the applicant account', async () => {
    signInAs('ADMIN')
    let approvals = 0
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith(`/admin/loans/${PRIYA_LOAN}/approve`))
        approvals++
    })
    server.use(
      http.post(`/api/v1/admin/loans/${PRIYA_LOAN}/approve`, async () => {
        await delay(150)
        return undefined
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/loans')
    const card = (await screen.findByRole('link', { name: 'Priya Nair' })).closest('li')!
    await user.click(within(card).getByRole('button', { name: /approve/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve and disburse?' })
    const confirm = within(dialog).getByRole('button', { name: 'Approve and disburse' })
    await user.dblClick(confirm)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(approvals).toBe(1)
    const loan = db.loans.find((l) => l.id === PRIYA_LOAN)!
    expect(loan.status).toBe('ACTIVE')
    expect(loan.schedule).toHaveLength(36)
    expect(db.accounts.get(PRIYA_ACC)?.balance).toBe('618420.5000')
    expect(db.auditLogs[0]).toMatchObject({ action: 'LOAN_APPROVED', targetId: PRIYA_LOAN })
    expect(db.notifications[0]).toMatchObject({ type: 'LOAN_APPROVED' })
    // The pending filter refetches: Priya drops out of the queue.
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'Priya Nair' })).not.toBeInTheDocument(),
    )
  })

  it('requires a reason to reject, then records it', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/loans')
    const card = (await screen.findByRole('link', { name: 'Kavya Reddy' })).closest('li')!
    await user.click(within(card).getByRole('button', { name: /reject/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject this application?' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject application' }))
    expect(within(dialog).getByText('Give a reason of at least 5 characters')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Reason'), 'Income could not be verified')
    await user.click(within(dialog).getByRole('button', { name: 'Reject application' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(db.loans.find((l) => l.id === KAVYA_LOAN)).toMatchObject({
      status: 'REJECTED',
      decisionReason: 'Income could not be verified',
    })
  })

  it('explains a 409 when another admin decided first', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/loans')
    const card = (await screen.findByRole('link', { name: 'Priya Nair' })).closest('li')!
    // Someone else approves it after the queue loaded.
    db.loans.find((l) => l.id === PRIYA_LOAN)!.status = 'REJECTED'
    await user.click(within(card).getByRole('button', { name: /approve/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and disburse' }))
    expect(await within(dialog).findByText('This has already changed.')).toBeInTheDocument()
  })
})

describe('freeze and unfreeze', () => {
  it('freezes an account with an audited reason and notifies the customer', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp(`/admin/users/${MEERA}`)
    expect(await screen.findByRole('heading', { name: 'Meera Iyer' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /freeze savings ••••2290/i }))
    const dialog = await screen.findByRole('dialog', { name: 'Freeze this account?' })
    await user.type(within(dialog).getByLabelText('Reason'), 'Suspicious login pattern')
    await user.click(within(dialog).getByRole('button', { name: 'Freeze account' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(db.accounts.get(MEERA_SAVINGS)?.status).toBe('FROZEN')
    expect(db.auditLogs[0]).toMatchObject({
      action: 'ACCOUNT_FROZEN',
      details: 'Account ••••2290: Suspicious login pattern',
    })
    expect(db.notifications[0]).toMatchObject({ userId: MEERA, type: 'ACCOUNT_FROZEN' })
    expect(
      await screen.findByRole('button', { name: /unfreeze savings ••••2290/i }),
    ).toBeInTheDocument()
  })
})

describe('users and audit', () => {
  it('searches users by email fragment', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/users')
    await screen.findAllByText('Aarav Sharma')
    await user.type(screen.getByRole('searchbox', { name: 'Search users' }), 'rohan')
    await waitFor(() => expect(screen.queryAllByText('Aarav Sharma')).toHaveLength(0))
    expect(screen.getAllByText('Rohan Mehta').length).toBeGreaterThan(0)
  })

  it('reconciles on demand', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/reconcile')
    expect(await screen.findByText('Not run yet')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Run reconciliation' }))
    expect(await screen.findByText('Balanced')).toBeInTheDocument()
  })
})

describe('review fixes', () => {
  it('steps back a page when the last row on the current page is decided', async () => {
    signInAs('ADMIN')
    // 11 pending in total (2 seeded + 9 more): page 2 holds exactly one.
    const template = db.loans.find((l) => l.id === KAVYA_LOAN)!
    for (let i = 0; i < 9; i++)
      db.loans.push({
        ...template,
        id: `c0000000-0000-4000-8000-1000000000${String(i).padStart(2, '0')}`,
        createdAt: '2026-10-16T09:00:00Z',
      })
    const user = userEvent.setup()
    renderApp('/admin/loans')
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    const last = await screen.findByRole('button', { name: /approve priya nair/i })
    await user.click(last)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and disburse' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findAllByRole('button', { name: /approve kavya reddy/i })).not.toHaveLength(
      0,
    )
    expect(screen.queryByText('The queue is empty')).not.toBeInTheDocument()
  })

  it('cannot be dismissed with Escape while the decision is in flight', async () => {
    signInAs('ADMIN')
    server.use(
      http.post(`/api/v1/admin/loans/${PRIYA_LOAN}/approve`, async () => {
        await delay(300)
        return undefined
      }),
    )
    const user = userEvent.setup()
    renderApp('/admin/loans')
    await user.click(await screen.findByRole('button', { name: /approve priya nair/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and disburse' }))
    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('sends an admin to /admin even when the expired session was on a customer page', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/dashboard')
    await user.type(await screen.findByLabelText('Email'), 'admin@quantgen.dev')
    await user.type(screen.getByLabelText('Password', { exact: true }), 'Admin@1234')
    await user.click(screen.getByRole('button', { name: /^sign in/i }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin'))
  })
})
