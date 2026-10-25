import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { db } from '@/mocks/handlers'
import { server } from '@/mocks/node'
import { renderApp, signInAs } from '@/test/utils'

const SALARY_RULE = 'fa000000-0000-4000-8000-000000000001'

/** Records the X-Step-Up-Token header of every request to an Autopilot write endpoint. */
function spyOnRuleWrites() {
  const calls: Array<{ method: string; path: string; stepUp: string | null }> = []
  server.events.on('request:start', ({ request }) => {
    const path = new URL(request.url).pathname
    if (request.method !== 'GET' && /\/autopilot\/rules/.test(path))
      calls.push({ method: request.method, path, stepUp: request.headers.get('X-Step-Up-Token') })
  })
  return calls
}

async function enterPin(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('group', { name: 'Transaction PIN' })
  await user.keyboard('246810')
}

describe('Autopilot', () => {
  it('compiles plain English, shows the server’s sentence, replays 90 days, then switches on with step-up', async () => {
    signInAs('CUSTOMER')
    const calls = spyOnRuleWrites()
    const user = userEvent.setup()
    const { router } = renderApp('/autopilot/new')

    await user.type(
      await screen.findByLabelText('Describe your rule'),
      'Every month on the 5th buy ₹8000 of TCS',
    )
    await user.click(screen.getByRole('button', { name: 'Turn into a rule' }))

    // The sentence comes from /autopilot/validate (the server), not from the AI.
    expect(
      await screen.findByText(/On the 5th of every month/i, {}, { timeout: 8000 }),
    ).toBeInTheDocument()
    const switchOn = screen.getByRole('button', { name: 'Switch on' })
    expect(switchOn).toBeDisabled()

    await user.click(await screen.findByRole('button', { name: 'Replay the last 90 days' }))
    expect(await screen.findByText('Times it ran')).toBeInTheDocument()

    const name = screen.getByLabelText('Rule name')
    await user.clear(name)
    await user.type(name, 'TCS SIP')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Switch on' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Switch on' }))
    await enterPin(user)

    await waitFor(() =>
      expect(router.state.location.pathname).toMatch(/^\/autopilot\/[0-9a-f-]{36}$/),
    )
    expect(await screen.findByRole('heading', { level: 1, name: 'TCS SIP' })).toBeInTheDocument()
    // First try refused with STEP_UP_REQUIRED, the retry carries the token.
    const creates = calls.filter((c) => c.method === 'POST' && c.path.endsWith('/autopilot/rules'))
    expect(creates.map((c) => !!c.stepUp)).toEqual([false, true])
    expect(db.v2.rules.some((r) => r.name === 'TCS SIP' && r.status === 'ACTIVE')).toBe(true)
  })

  it('needs a fresh replay after the rule changes, and shows server field errors inline', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/autopilot/new')
    await user.click(await screen.findByRole('button', { name: /Build it myself/ }))
    // The button renders disabled until the debounced /validate answers; a click before that is lost.
    const replay = await screen.findByRole(
      'button',
      { name: 'Replay the last 90 days' },
      { timeout: 8000 },
    )
    await waitFor(() => expect(replay).toBeEnabled())
    await user.click(replay)
    expect(await screen.findByText('Times it ran')).toBeInTheDocument()

    // Per-run cap above the monthly cap: the server's validator rejects it, inline.
    const perRun = screen.getByLabelText('Per run')
    await user.clear(perRun)
    await user.type(perRun, '9000')
    // Inline under the field and in the summary next to the sentence.
    expect(
      await screen.findAllByText('can’t be more than the monthly cap', {}, { timeout: 5000 }),
    ).toHaveLength(2)
    expect(screen.getByLabelText('Per run')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('You changed the rule, so replay it again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Switch on' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Replay the last 90 days' })).toBeDisabled()
  })

  it('sweeps round-ups on a schedule, and explains why it can’t run on another trigger', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    renderApp('/autopilot/new')
    await user.click(await screen.findByRole('button', { name: /Build it myself/ }))
    await user.click(
      await screen.findByRole('combobox', { name: 'Action type' }, { timeout: 8000 }),
    )
    await user.click(await screen.findByRole('option', { name: 'Sweep round-ups' }))
    // The blank rule runs on a schedule, so the server renders the sweep in its own words.
    expect(
      await screen.findByText(
        /move the round-ups to the next ₹10 on spends from/,
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Trigger' }))
    await user.click(await screen.findByRole('option', { name: 'Money arrives' }))
    expect(
      await screen.findAllByText(
        'round-up sweeps run on a schedule trigger',
        {},
        { timeout: 5000 },
      ),
    ).not.toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Replay the last 90 days' })).toBeDisabled()
  })

  it('lists rules; pausing is instant but resuming asks for the PIN', async () => {
    signInAs('CUSTOMER')
    const calls = spyOnRuleWrites()
    const user = userEvent.setup()
    renderApp('/autopilot')
    const card = (await screen.findByRole('link', { name: 'Salary splitter' })).closest(
      'li',
    ) as HTMLElement

    await user.click(within(card).getByRole('button', { name: /Pause/ }))
    expect(await within(card).findByText('Paused')).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /Resume/ }))
    await enterPin(user)
    expect(await within(card).findByText('Running')).toBeInTheDocument()
    const resumes = calls.filter((c) => c.path.endsWith('/resume'))
    expect(resumes.map((c) => !!c.stepUp)).toEqual([false, true])
  })

  it('shows a rule’s detail and deletes it after confirmation', async () => {
    signInAs('CUSTOMER')
    const user = userEvent.setup()
    const { router } = renderApp(`/autopilot/${SALARY_RULE}`)
    expect(await screen.findByText(/lands in Savings/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Delete/ }))
    await user.click(await screen.findByRole('button', { name: /Delete rule/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/autopilot'))
    expect(db.v2.rules.some((r) => r.id === SALARY_RULE)).toBe(false)
  })

  it('“Explain this run” asks Copilot with the run attached as context', async () => {
    signInAs('CUSTOMER')
    db.v2.flags.ai.enabled = true
    const run = db.v2.ruleRuns.get(SALARY_RULE)![0]!
    const bodies: Array<Record<string, unknown>> = []
    server.events.on('request:start', async ({ request }) => {
      if (
        request.method === 'POST' &&
        /\/copilot\/conversations\/[^/]+\/messages$/.test(request.url)
      )
        bodies.push((await request.clone().json()) as Record<string, unknown>)
    })
    const user = userEvent.setup()
    renderApp(`/autopilot/${SALARY_RULE}`)
    await user.click((await screen.findAllByRole('button', { name: /Explain this run/ }))[0]!)
    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({ context: { type: 'AUTOPILOT_RUN', id: run.id } })
    expect(await screen.findByText(/ran because/)).toBeInTheDocument()
  })

  it('another user’s rule reads as not found', async () => {
    signInAs('ADMIN')
    renderApp(`/autopilot/${SALARY_RULE}`)
    expect(await screen.findByText(/doesn’t exist/)).toBeInTheDocument()
  })

  it('falls back to the form when writing rules from a sentence is unavailable (503)', async () => {
    signInAs('CUSTOMER')
    server.use(
      http.post('/api/v1/autopilot/compile', () =>
        HttpResponse.json(
          { status: 503, code: 'FEATURE_DISABLED', message: 'Use the form', fieldErrors: [] },
          { status: 503 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderApp('/autopilot/new')
    await user.type(
      await screen.findByLabelText('Describe your rule'),
      'Every month on the 5th buy ₹8000 of TCS',
    )
    await user.click(screen.getByRole('button', { name: 'Turn into a rule' }))
    expect(await screen.findByLabelText('Rule name', {}, { timeout: 8000 })).toBeInTheDocument()
  })

  it('a template without a rule starts the form with its name when AI is off', async () => {
    signInAs('CUSTOMER')
    db.v2.flags.ai.enabled = false
    const user = userEvent.setup()
    renderApp('/autopilot/new')
    await user.click(await screen.findByRole('button', { name: /Monthly SIP/ }))
    expect(await screen.findByLabelText('Rule name')).toHaveValue('Monthly SIP')
  })

  it('shows a run that is still in progress as running', async () => {
    signInAs('CUSTOMER')
    server.use(
      http.get('/api/v1/autopilot/rules/:id/runs', () =>
        HttpResponse.json({
          content: [
            {
              id: 'r1',
              ruleVersion: 1,
              triggerEventId: 'e1',
              triggerSummary: '₹85,000 arrived in Checking',
              startedAt: new Date().toISOString(),
              outcome: null,
              actions: [],
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        }),
      ),
    )
    renderApp(`/autopilot/${SALARY_RULE}`)
    const row = (await screen.findByText('₹85,000 arrived in Checking')).closest(
      'li',
    ) as HTMLElement
    expect(within(row).getByText('Running')).toBeInTheDocument()
  })

  it('shows a friendly page when the autopilot flag is off', async () => {
    signInAs('CUSTOMER')
    db.v2.flags.autopilot.enabled = false
    renderApp('/autopilot')
    expect(await screen.findByText('Autopilot is switched off right now')).toBeInTheDocument()
  })
})
