import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'

const PRIYA_CASE = 'fc000000-0000-4000-8000-000000000001'
const KAVYA_CASE = 'fc000000-0000-4000-8000-000000000002'
const PRIYA_LOAN = 'c0000000-0000-4000-8000-000000000001'
const DEMO_LOAN = 'b0a11111-2222-4333-8444-555566667777'

describe('fraud queue (Admin v2)', () => {
  it('lists open cases highest risk first and links to the case', async () => {
    signInAs('ADMIN')
    renderApp('/admin/fraud')
    const scores = await screen.findAllByLabelText(/Risk score \d+ of 100/)
    expect(scores.map((s) => s.textContent)).toEqual(['86', '74'])
    expect(screen.getByRole('link', { name: /Priya Nair/ })).toHaveAttribute('href', `/admin/fraud/${PRIYA_CASE}`)
  })

  it('releases a held transfer only with a reason, and audits it', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp(`/admin/fraud/${PRIYA_CASE}`)
    expect(await screen.findByText(/matches a common "urgent help" scam pattern/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Release transfer/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Release this transfer?' })
    await user.click(within(dialog).getByRole('button', { name: 'Release' }))
    expect(within(dialog).getByText('Give a reason of at least 5 characters')).toBeInTheDocument()
    expect(db.v2.fraudCases.find((c) => c.id === PRIYA_CASE)?.status).toBe('OPEN')

    await user.type(within(dialog).getByRole('textbox'), 'Called the customer; payee confirmed.')
    await user.click(within(dialog).getByRole('button', { name: 'Release' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(db.v2.fraudCases.find((c) => c.id === PRIYA_CASE)).toMatchObject({
      status: 'RELEASED',
      decisionReason: 'Called the customer; payee confirmed.',
    })
    expect(db.auditLogs[0]).toMatchObject({ action: 'FRAUD_RELEASED', targetId: PRIYA_CASE })
    // The page refetches: no more decision buttons, the reason is shown.
    expect(await screen.findByText('Called the customer; payee confirmed.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Release transfer/ })).not.toBeInTheDocument()
  })

  it('explains a 409 when another admin decided first, and refreshes the case', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp(`/admin/fraud/${KAVYA_CASE}`)
    await user.click(await screen.findByRole('button', { name: /Reject and return money/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject this transfer?' })

    // Someone else releases it while this dialog is open.
    const kase = db.v2.fraudCases.find((c) => c.id === KAVYA_CASE)!
    kase.status = 'RELEASED'
    kase.decisionReason = 'Released by a colleague.'

    await user.type(within(dialog).getByRole('textbox'), 'Payee is a reported mule account.')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(await within(dialog).findByText('This has already changed.')).toBeInTheDocument()
    expect(kase.status).toBe('RELEASED')

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(await screen.findByText('Released by a colleague.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Reject and return money/ })).not.toBeInTheDocument()
  })
})

describe('feature flags (Admin v2)', () => {
  it('confirms a toggle, saves it, and refreshes the flags every screen reads', async () => {
    signInAs('ADMIN')
    let customerFlagReads = 0
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'GET' && new URL(request.url).pathname === '/api/v1/feature-flags') customerFlagReads++
    })
    const user = userEvent.setup()
    renderApp('/admin/flags')
    await user.click(await screen.findByRole('button', { name: 'Turn off Virtual cards' }))
    const dialog = await screen.findByRole('dialog', { name: 'Turn off Virtual cards?' })
    const before = customerFlagReads
    await user.click(within(dialog).getByRole('button', { name: 'Turn off' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    expect(db.v2.flags.cards.enabled).toBe(false)
    expect(await screen.findByRole('button', { name: 'Turn on Virtual cards' })).toBeInTheDocument()
    await waitFor(() => expect(customerFlagReads).toBeGreaterThan(before))
  })
})

describe('loan documents and underwriting (Admin v2)', () => {
  it('checks type and consent before uploading, then lists the document', async () => {
    signInAs('CUSTOMER')
    db.loans.find((l) => l.id === DEMO_LOAN)!.status = 'PENDING'
    // jsdom's FormData can't be parsed by undici's multipart parser (browsers are fine), so this
    // handler checks the raw multipart body and stores the document the way the mock would.
    const bodies: string[] = []
    server.use(
      http.post(`/api/v1/loans/${DEMO_LOAN}/documents`, async ({ request }) => {
        bodies.push(await request.text())
        const doc = { id: crypto.randomUUID(), fileName: 'salary-aug.pdf', contentType: 'application/pdf', sizeBytes: 9, uploadedAt: new Date().toISOString() }
        db.v2.loanDocs.set(DEMO_LOAN, [doc])
        return HttpResponse.json(doc, { status: 201 })
      }),
    )
    const user = userEvent.setup({ applyAccept: false })
    renderApp(`/loans/${DEMO_LOAN}`)
    const input = await screen.findByLabelText('Document to upload')
    const form = input.closest('form')!

    await user.upload(input, new File(['hello'], 'notes.txt', { type: 'text/plain' }))
    await user.click(within(form).getByRole('button', { name: 'Upload' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('Use a PDF, PNG or JPEG.')

    const pdf = new File(['%PDF-1.7\n'], 'salary-aug.pdf', { type: 'application/pdf' })
    await user.upload(input, pdf)
    await user.click(within(form).getByRole('button', { name: 'Upload' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('Tick the consent box to upload.')

    await user.click(within(form).getByRole('checkbox'))
    await user.click(within(form).getByRole('button', { name: 'Upload' }))
    expect(await screen.findByText('salary-aug.pdf')).toBeInTheDocument()
    expect(bodies).toHaveLength(1)
    expect(bodies[0]).toMatch(/name="file"; filename="salary-aug\.pdf"/)
    expect(bodies[0]).toMatch(/name="consent"\r\n\r\ntrue/)
  })

  it('shows the underwriting memo on demand, with cited figures, as advisory', async () => {
    signInAs('ADMIN')
    const user = userEvent.setup()
    renderApp('/admin/loans')
    const card = (await screen.findByRole('link', { name: 'Priya Nair' })).closest('li')!
    const toggle = within(card).getByRole('button', { name: /Underwriting memo/ })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(await within(card).findByText(/Salary slips show a net monthly income/)).toBeInTheDocument()
    expect(within(card).getByText(/AI-written, advisory/)).toBeInTheDocument()
    expect(within(card).getByText('Employer')).toBeInTheDocument()
    expect(within(card).getAllByText(/^p\. \d$/).length).toBeGreaterThan(0)
    expect(db.loans.find((l) => l.id === PRIYA_LOAN)?.status).toBe('PENDING')
  })
})
