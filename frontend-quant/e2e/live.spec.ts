import { expect, test, type APIRequest, type Page } from '@playwright/test'

/**
 * Real UI against the REAL backend (no MSW). Opt-in: needs the API on :8080 and Postgres.
 *   QG_LIVE=1 npx playwright test e2e/live.spec.ts --project=desktop
 * A fresh user each run, so it never depends on seed balances.
 * The whole file registers ~15 users and logs in ~25 times from one IP, so run the backend with
 * QG_RATE_LIMIT_ENABLED=false (dev only); with the limiter on, run tests one at a time (-g "<name>").
 * Tests that need a feature flag turn it on through the admin API and restore it in `finally`.
 */
test.skip(!process.env.QG_LIVE, 'set QG_LIVE=1 with the backend running on :8080')
// Feature flags are global server state: two tests toggling the same flag in parallel undo each other
// (seen 2026-10-24: one test's `finally` turned `autopilot`/`mcp` off mid-way through another). Run this
// file's tests in order in one worker, whatever --workers says.
test.describe.configure({ mode: 'default' })

async function go(page: Page, name: string) {
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name, exact: true })
    .filter({ visible: true })
    .click()
}

test('register → deposit → open account → own transfer → buy, all server-confirmed', async ({
  page,
}) => {
  test.setTimeout(120_000)
  const n = Date.now().toString().slice(-9)
  await page.goto('/register')
  await page.getByLabel('Full name').fill('Live Tester')
  await page.getByLabel('Email').fill(`live${n}@example.com`)
  await page.getByLabel('Phone').fill(`+91${n}0`)
  await page.getByLabel('Password', { exact: true }).fill('Live@12345')
  await page.getByLabel('Confirm password').fill('Live@12345')
  await page.getByRole('button', { name: /create account/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  // F-AUTH-5: one savings account, empty. Deposit ₹5,000 (review → confirm).
  await go(page, 'Accounts')
  await page.getByRole('button', { name: 'Add money' }).click()
  const dep = page.getByRole('dialog', { name: 'Add money' })
  await dep.getByRole('textbox', { name: 'Amount' }).fill('5000')
  await dep.getByRole('button', { name: 'Review' }).click()
  await dep.getByRole('button', { name: 'Add ₹5,000.00' }).click()
  await expect(dep.getByRole('img', { name: 'Deposit confirmed' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByText('₹5,000.00').first()).toBeVisible()

  // Second account (checking).
  await page.getByRole('button', { name: 'Open account' }).click()
  const open = page.getByRole('dialog', { name: 'Open an account' })
  await open.getByRole('radio', { name: /checking/i }).click()
  await open.getByRole('button', { name: 'Open account' }).click()
  await expect(open).toBeHidden()

  // ₹1,500 savings → checking.
  await page.goto('/transfer') // v2 nav groups it under Pay
  await page.getByRole('radio', { name: 'Mine' }).click()
  await page.getByRole('combobox').filter({ hasText: 'Choose one of your accounts' }).click()
  await page.getByRole('option', { name: /^Checking/ }).click()
  await page.getByLabel('Amount').fill('1500')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('button', { name: /send ₹1,500\.00/i }).click()
  await expect(page.getByRole('img', { name: 'Transfer completed' })).toBeVisible()

  await go(page, 'Accounts')
  await expect(page.getByText('₹3,500.00').first()).toBeVisible()
  await expect(page.getByText('₹1,500.00').first()).toBeVisible()

  // Buy 1 share of the cheapest-feeling instrument at the live price.
  await go(page, 'Markets')
  // F-TRD-2: STOMP connected (not the REST polling fallback) and ticks actually arrive.
  await expect(page.getByText('Live', { exact: true }).first()).toBeVisible()
  const table = page.getByRole('table').first()
  const before = await table.innerText()
  await expect.poll(() => table.innerText(), { timeout: 12_000 }).not.toBe(before)
  await page.getByRole('link', { name: 'WIPR' }).filter({ visible: true }).first().click()
  const ticket = page.getByRole('complementary', { name: 'Order ticket' })
  await ticket.getByLabel('Quantity').fill('1')
  await ticket.getByRole('button', { name: 'Review buy' }).click()
  await ticket.getByRole('button', { name: 'Confirm buy' }).click()
  await expect(ticket.getByRole('img', { name: 'Order filled' })).toBeVisible()

  await go(page, 'Portfolio')
  await expect(page.getByText('WIPR').filter({ visible: true }).first()).toBeVisible()
})

async function signOut(page: Page) {
  await page.getByRole('button', { name: /account menu/i }).click()
  await page.getByRole('menuitem', { name: /sign out/i }).click()
  await expect(page).toHaveURL(/\/login/)
}

async function signIn(page: Page, email: string, password: string, home: RegExp) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: /^sign in/i }).click()
  await expect(page).toHaveURL(home)
}

// M4 + M5 end to end: apply → admin approves (disburses) → customer repays the exact
// amountDue → notifications → admin freezes → audit log. Four logins (backend allows 10/min).
test('loan lifecycle, notifications, freeze and audit against the real API', async ({ page }) => {
  test.setTimeout(180_000)
  const n = Date.now().toString().slice(-9)
  const name = `Live ${n}`
  const email = `loan${n}@example.com`
  await page.goto('/register')
  await page.getByLabel('Full name').fill(name)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Phone').fill(`+91${n}1`)
  await page.getByLabel('Password', { exact: true }).fill('Live@12345')
  await page.getByLabel('Confirm password').fill('Live@12345')
  await page.getByRole('button', { name: /create account/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  // Apply: ₹20,000 against ₹50,000/month income.
  await go(page, 'Loans')
  await page.getByRole('button', { name: /apply for/i }).click()
  const apply = page.getByRole('dialog', { name: 'Apply for a loan' })
  await apply.getByRole('textbox', { name: 'How much?' }).fill('20000')
  await apply.getByRole('button', { name: 'Continue' }).click()
  await apply.getByRole('textbox', { name: 'Monthly income' }).fill('50000')
  await apply.getByLabel('Purpose').fill('Live e2e laptop')
  await apply.getByRole('button', { name: 'Continue' }).click()
  await apply.getByRole('button', { name: 'Continue' }).click()
  await apply.getByRole('button', { name: 'Submit application' }).click()
  await expect(apply.getByRole('img', { name: 'Application submitted' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(apply).toBeHidden()
  await signOut(page)

  // Admin approves it from the queue.
  await signIn(page, 'admin@quantgen.dev', 'Admin@1234', /\/admin$/)
  await go(page, 'Loan queue')
  await page.getByRole('button', { name: `Approve ${name}’s application` }).click()
  const approve = page.getByRole('dialog', { name: 'Approve and disburse?' })
  await approve.getByRole('button', { name: 'Approve and disburse' }).click()
  await expect(approve).toBeHidden()
  await signOut(page)

  // Customer: loan is active, money arrived, repay the next EMI at the server's amountDue.
  await signIn(page, email, 'Live@12345', /\/dashboard$/)
  await go(page, 'Accounts')
  await expect(page.getByText('₹20,000.00').first()).toBeVisible()
  await go(page, 'Loans')
  await page.locator('a[href^="/loans/"]').first().click()
  const repay = page.getByRole('complementary', { name: 'Repay' })
  await repay.getByRole('button', { name: 'Review repayment' }).click()
  await repay.getByRole('button', { name: /^pay ₹/i }).click()
  await expect(repay.getByRole('img', { name: 'Repayment confirmed' })).toBeVisible()

  await page.getByRole('link', { name: /^notifications/i }).click()
  await expect(page.getByText('Loan approved').first()).toBeVisible()
  await expect(page.getByText('EMI paid').first()).toBeVisible()
  await page.getByRole('button', { name: 'Mark all as read' }).click()
  await expect(page.getByText('You’re all caught up.')).toBeVisible()
  await signOut(page)

  // Admin freezes the savings account; the audit log records it.
  await signIn(page, 'admin@quantgen.dev', 'Admin@1234', /\/admin$/)
  await go(page, 'Users')
  await page.getByRole('searchbox', { name: 'Search users' }).fill(email)
  await page.getByRole('link', { name }).filter({ visible: true }).first().click()
  await page.getByRole('button', { name: /^Freeze Savings/ }).click()
  const freeze = page.getByRole('dialog', { name: 'Freeze this account?' })
  await freeze.getByLabel('Reason').fill('Live e2e freeze check')
  await freeze.getByRole('button', { name: 'Freeze account' }).click()
  await expect(page.getByRole('button', { name: /^Unfreeze Savings/ })).toBeVisible()
  await go(page, 'Audit log')
  await expect(
    page
      .getByText(/: Live e2e freeze check$/)
      .filter({ visible: true })
      .first(),
  ).toBeVisible()
})

// V2-M3 against the real API: PIN → goal → FD booked → closed early with step-up (FD_CLOSE),
// paying 0 interest under 7 days and returning the principal.
test('savings: set PIN, create a goal, book an FD and close it early with step-up', async ({
  page,
}) => {
  test.setTimeout(120_000)
  const n = Date.now().toString().slice(-9)
  await page.goto('/register')
  await page.getByLabel('Full name').fill('Saver Tester')
  await page.getByLabel('Email').fill(`saver${n}@example.com`)
  await page.getByLabel('Phone').fill(`+91${n}1`)
  await page.getByLabel('Password', { exact: true }).fill('Live@12345')
  await page.getByLabel('Confirm password').fill('Live@12345')
  await page.getByRole('button', { name: /create account/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  await go(page, 'Accounts')
  await page.getByRole('button', { name: 'Add money' }).click()
  const dep = page.getByRole('dialog', { name: 'Add money' })
  await dep.getByRole('textbox', { name: 'Amount' }).fill('5000')
  await dep.getByRole('button', { name: 'Review' }).click()
  await dep.getByRole('button', { name: 'Add ₹5,000.00' }).click()
  await expect(dep.getByRole('img', { name: 'Deposit confirmed' })).toBeVisible()
  await page.keyboard.press('Escape')

  // Transaction PIN (first time also asks for the account password).
  await page.goto('/settings/security')
  await page.getByRole('group', { name: 'Choose a PIN' }).locator('input').first().click()
  await page.keyboard.type('246810')
  await page.getByRole('group', { name: 'Confirm PIN' }).locator('input').first().click()
  await page.keyboard.type('246810')
  const pw = page.getByLabel('Account password')
  if (await pw.isVisible()) await pw.fill('Live@12345')
  await page.getByRole('button', { name: 'Set PIN' }).click()
  await expect(page.getByText('Set', { exact: true }).first()).toBeVisible()

  await go(page, 'Savings')
  await page.getByRole('button', { name: 'New goal' }).click()
  const goal = page.getByRole('dialog', { name: 'New goal' })
  await goal.getByLabel('Name').fill('Laptop')
  await goal.getByRole('textbox', { name: 'Target', exact: true }).fill('80000')
  await goal.getByRole('button', { name: 'Create goal' }).click()
  await expect(goal).toBeHidden()
  await expect(page.getByText('Laptop').first()).toBeVisible()

  await page.getByRole('button', { name: 'Book an FD' }).click()
  const fd = page.getByRole('dialog', { name: 'Book a fixed deposit' })
  await fd.getByRole('textbox', { name: 'Amount' }).fill('1000')
  await fd.getByRole('button', { name: /1 month/ }).click()
  await fd.getByRole('button', { name: 'Review' }).click()
  await fd.getByRole('button', { name: /Book deposit/ }).click()
  await expect(fd.getByRole('img', { name: 'Deposit booked' })).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('button', { name: 'Close early' }).click()
  const close = page.getByRole('dialog', { name: 'Close this deposit early?' })
  await close.getByRole('button', { name: /Close deposit/ }).click()
  await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
  await page.keyboard.type('246810')
  await expect(close.getByRole('img', { name: 'Deposit closed' })).toBeVisible()
  await page.keyboard.press('Escape')

  // Principal back, no interest (< 7 days): the savings account shows ₹5,000.00 again.
  await go(page, 'Accounts')
  await expect(page.getByText('₹5,000.00').first()).toBeVisible()
})

test('cards: issue, reveal with step-up, pay the demo store → hold → capture → refund', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  // Cards sit behind a feature flag (off on a fresh :8080). Turn it on as admin, restore after.
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json())
    .cards as boolean
  await admin.put('/api/v1/admin/feature-flags/cards', { headers: auth, data: { enabled: true } })
  try {
    const n = Date.now().toString().slice(-9)
    await page.goto('/register')
    await page.getByLabel('Full name').fill('Card Tester')
    await page.getByLabel('Email').fill(`card${n}@example.com`)
    await page.getByLabel('Phone').fill(`+91${n}2`)
    await page.getByLabel('Password', { exact: true }).fill('Live@12345')
    await page.getByLabel('Confirm password').fill('Live@12345')
    await page.getByRole('button', { name: /create account/i }).click()
    await expect(page).toHaveURL(/\/dashboard$/)

    await go(page, 'Accounts')
    await page.getByRole('button', { name: 'Add money' }).click()
    const dep = page.getByRole('dialog', { name: 'Add money' })
    await dep.getByRole('textbox', { name: 'Amount' }).fill('5000')
    await dep.getByRole('button', { name: 'Review' }).click()
    await dep.getByRole('button', { name: 'Add ₹5,000.00' }).click()
    await expect(dep.getByRole('img', { name: 'Deposit confirmed' })).toBeVisible()
    await page.keyboard.press('Escape')

    await page.goto('/settings/security')
    await page.getByRole('group', { name: 'Choose a PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await page.getByRole('group', { name: 'Confirm PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    const pw = page.getByLabel('Account password')
    if (await pw.isVisible()) await pw.fill('Live@12345')
    await page.getByRole('button', { name: 'Set PIN' }).click()
    await expect(page.getByText('Set', { exact: true }).first()).toBeVisible()

    await go(page, 'Cards')
    await page.getByRole('button', { name: 'Issue card' }).first().click()
    const face = page.getByRole('group', { name: /^Virtual card ending \d{4}$/ })
    await expect(face).toBeVisible()
    await page.getByRole('button', { name: 'Show card details' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await expect(page.getByRole('button', { name: 'Hide now' })).toBeVisible()
    const pan = (await face.locator('p.num').innerText()).replace(/\s/g, '')
    const nums = await face.locator('div.num > span').allInnerTexts()
    const expiry = nums[0]!.match(/\d{2}\/\d{2}/)![0]
    const cvv = nums[1]!.match(/\d{3}/)![0]
    expect(pan).toMatch(/^\d{16}$/)

    await page.getByRole('link', { name: 'Try the demo store' }).click()
    await page.getByRole('radio', { name: /Filter coffee beans/ }).click()
    await page.getByLabel('Card number').fill(pan)
    await page.getByLabel('Expiry').fill(expiry.replace('/', ''))
    await page.getByLabel('CVV').fill(cvv)
    await page.getByRole('button', { name: /^Pay/ }).click()
    await expect(page.getByText('Authorized · on hold')).toBeVisible()
    await expect(page.getByText('Paid', { exact: true })).toBeVisible({ timeout: 15_000 })
    await page.getByRole('button', { name: 'Refund' }).click()
    await expect(page.getByText('Refunded', { exact: true })).toBeVisible()

    // Hold captured, then refunded: the balance is whole again.
    await go(page, 'Accounts')
    await expect(page.getByText('₹5,000.00').first()).toBeVisible()
  } finally {
    await admin.put('/api/v1/admin/feature-flags/cards', {
      headers: auth,
      data: { enabled: wasOn },
    })
    await admin.dispose()
  }
})

/** A fresh customer created through the API, with its own cookie jar. */
async function apiCustomer(
  playwright: { request: APIRequest },
  baseURL: string | undefined,
  tag: string,
) {
  const ctx = await playwright.request.newContext({ baseURL })
  const n = `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 10)
  const email = `${tag}${n}@example.com`
  const password = 'Live@12345'
  expect(
    (
      await ctx.post('/api/v1/auth/register', {
        data: { fullName: `${tag} Tester`, email, phone: `+91${n}9`, password },
      })
    ).ok(),
  ).toBe(true)
  const token = (await (await ctx.post('/api/v1/auth/login', { data: { email, password } })).json())
    .accessToken as string
  const headers = { Authorization: `Bearer ${token}` }
  const accounts = (await (await ctx.get('/api/v1/accounts', { headers })).json()) as Array<{
    id: string
    accountNumber: string
    balance: string
  }>
  return { ctx, email, password, headers, account: accounts[0]! }
}

test('fraud: an unusual transfer is held for review, then an admin releases it', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const sender = await apiCustomer(playwright, baseURL, 'sender')
  const friend = await apiCustomer(playwright, baseURL, 'friend')
  const stranger = await apiCustomer(playwright, baseURL, 'stranger')
  try {
    const idem = () => ({ ...sender.headers, 'Idempotency-Key': crypto.randomUUID() })
    expect(
      (
        await sender.ctx.post(`/api/v1/accounts/${sender.account.id}/deposit`, {
          headers: idem(),
          data: { amount: '40000.00' },
        })
      ).ok(),
    ).toBe(true)
    // Some history with a known payee, so the next one stands out.
    for (let i = 0; i < 3; i++) {
      const r = await sender.ctx.post('/api/v1/transfers', {
        headers: idem(),
        data: {
          fromAccountId: sender.account.id,
          toAccountNumber: friend.account.accountNumber,
          amount: '1000.00',
        },
      })
      expect(r.status()).toBe(201)
    }

    // ₹30,000 to someone never paid before → held (201 PENDING_REVIEW), money not moved.
    await signIn(page, sender.email, sender.password, /\/dashboard$/)
    await page.goto('/transfer')
    await page.getByRole('radio', { name: 'Account no.' }).click()
    await page.getByLabel('Account number').fill(stranger.account.accountNumber)
    await page.getByLabel('Amount').fill('30000')
    await page.getByRole('button', { name: 'Review transfer' }).click()
    await page.getByRole('button', { name: /send ₹30,000\.00/i }).click()
    await expect(page.getByText('being checked')).toBeVisible()
    const held = await (
      await stranger.ctx.get('/api/v1/accounts', { headers: stranger.headers })
    ).json()
    expect(held[0].balance).toMatch(/^0(\.0+)?$/)
    await signOut(page)

    // Admin releases it with a reason; the stranger is credited.
    await signIn(page, 'admin@quantgen.dev', 'Admin@1234', /\/admin$/)
    await page.goto('/admin/fraud')
    await page
      .getByRole('link', { name: /₹30,000\.00/ })
      .first()
      .click()
    await expect(page.getByText('Large amount to someone never paid before').first()).toBeVisible()
    await page.getByRole('button', { name: /Release transfer/ }).click()
    const dialog = page.getByRole('dialog', { name: 'Release this transfer?' })
    await dialog.getByRole('textbox').fill('Called the customer; payee confirmed.')
    await dialog.getByRole('button', { name: 'Release' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText('Called the customer; payee confirmed.')).toBeVisible()
    await expect
      .poll(
        async () =>
          (
            await (await stranger.ctx.get('/api/v1/accounts', { headers: stranger.headers })).json()
          )[0].balance,
      )
      .toMatch(/^30000(\.0+)?$/)
  } finally {
    await Promise.all([sender.ctx.dispose(), friend.ctx.dispose(), stranger.ctx.dispose()])
  }
})

/** Registers through the UI and sets the transaction PIN 246810 (first time asks for the password). */
async function registerWithPin(page: Page, tag: string) {
  const n = Date.now().toString().slice(-9)
  await page.goto('/register')
  await page.getByLabel('Full name').fill(`${tag} Tester`)
  await page.getByLabel('Email').fill(`${tag.toLowerCase()}${n}@example.com`)
  await page.getByLabel('Phone').fill(`+91${n}3`)
  await page.getByLabel('Password', { exact: true }).fill('Live@12345')
  await page.getByLabel('Confirm password').fill('Live@12345')
  await page.getByRole('button', { name: /create account/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  await page.goto('/settings/security')
  await page.getByRole('group', { name: 'Choose a PIN' }).locator('input').first().click()
  await page.keyboard.type('246810')
  await page.getByRole('group', { name: 'Confirm PIN' }).locator('input').first().click()
  await page.keyboard.type('246810')
  const pw = page.getByLabel('Account password')
  if (await pw.isVisible()) await pw.fill('Live@12345')
  await page.getByRole('button', { name: 'Set PIN' }).click()
  await expect(page.getByText('Set', { exact: true }).first()).toBeVisible()
}

test('access tokens: create with step-up, read-only reach, revoke kills it', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(120_000)
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json())
    .mcp as boolean
  await admin.put('/api/v1/admin/feature-flags/mcp', { headers: auth, data: { enabled: true } })
  const client = await playwright.request.newContext({ baseURL })
  try {
    await registerWithPin(page, 'Token')
    await page.goto('/settings/developer')
    await page.getByLabel('Token name').fill('Live e2e')
    await page.getByRole('button', { name: 'Create token' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    const shown = page.getByRole('dialog', { name: 'Copy your token now' })
    const token = (await shown.locator('.num').innerText()).trim()
    expect(token).toMatch(/^qg_pat_[A-Za-z0-9_-]{43}$/)
    await shown.getByRole('button', { name: 'Done' }).click()
    await expect(page.getByText('Live e2e')).toBeVisible()

    const bearer = { Authorization: `Bearer ${token}` }
    expect((await client.get('/api/v1/accounts', { headers: bearer })).status()).toBe(200)
    const write = await client.post('/api/v1/transfers', {
      headers: { ...bearer, 'Idempotency-Key': crypto.randomUUID() },
      data: { fromAccountId: crypto.randomUUID(), toAccountNumber: '000000000000', amount: '1.00' },
    })
    expect(write.status()).toBe(403)

    await page.getByRole('button', { name: 'Revoke' }).click()
    await expect(page.getByText('No tokens')).toBeVisible()
    expect((await client.get('/api/v1/accounts', { headers: bearer })).status()).toBe(401)
  } finally {
    await admin.put('/api/v1/admin/feature-flags/mcp', { headers: auth, data: { enabled: wasOn } })
    await Promise.all([admin.dispose(), client.dispose()])
  }
})

// V2-M4 core side: an intent proposed through the API (as the agent service will) is approved in the
// "Waiting for you" panel with PIN step-up, and the money moves exactly once.
test('intents: proposed via API, approved in the UI with step-up, executed', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json())
    .ai as boolean
  await admin.put('/api/v1/admin/feature-flags/ai', { headers: auth, data: { enabled: true } })
  const me = await apiCustomer(playwright, baseURL, 'intent')
  const friend = await apiCustomer(playwright, baseURL, 'payee')
  try {
    expect(
      (
        await me.ctx.post(`/api/v1/accounts/${me.account.id}/deposit`, {
          headers: { ...me.headers, 'Idempotency-Key': crypto.randomUUID() },
          data: { amount: '2000.00' },
        })
      ).ok(),
    ).toBe(true)
    expect(
      (
        await me.ctx.put('/api/v1/users/me/pin', {
          headers: me.headers,
          data: { pin: '246810', password: me.password },
        })
      ).ok(),
    ).toBe(true)
    const proposed = await me.ctx.post('/api/v1/intents', {
      headers: me.headers,
      data: {
        kind: 'TRANSFER',
        payload: {
          fromAccountId: me.account.id,
          toAccountNumber: friend.account.accountNumber,
          amount: '750.00',
        },
        rationale: 'You asked to pay your friend back for dinner.',
        agent: 'PAYMENTS',
      },
    })
    expect(proposed.status()).toBe(201)
    const intent = await proposed.json()
    expect(intent).toMatchObject({ status: 'PROPOSED', requiresStepUp: true })

    await signIn(page, me.email, me.password, /\/dashboard$/)
    await page.goto('/copilot')
    const waiting = page
      .getByRole('region', { name: 'Waiting for you' })
      .or(page.locator('section', { hasText: 'Waiting for you' }))
      .first()
    const card = waiting.getByRole('article', { name: /Proposed action: Send ₹750\.00/ })
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: 'Approve' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await expect(card.getByText('Approved and carried out.')).toBeVisible()

    const list = await (await me.ctx.get('/api/v1/intents', { headers: me.headers })).json()
    expect(list.find((i: { id: string }) => i.id === intent.id)).toMatchObject({
      status: 'EXECUTED',
      resultRef: { type: 'TRANSFER' },
    })
    const paid = await (
      await friend.ctx.get('/api/v1/accounts', { headers: friend.headers })
    ).json()
    expect(paid[0].balance).toMatch(/^750(\.0+)?$/)
  } finally {
    await admin.put('/api/v1/admin/feature-flags/ai', { headers: auth, data: { enabled: wasOn } })
    await Promise.all([admin.dispose(), me.ctx.dispose(), friend.ctx.dispose()])
  }
})

// A connected app (read+propose token) proposes; with the ai flag OFF the user still approves it, on
// Settings → Developer.
test('intents from a connected app are approvable on the Developer tab with ai off', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const flags = (await (
    await admin.get('/api/v1/feature-flags', { headers: auth })
  ).json()) as Record<string, boolean>
  await admin.put('/api/v1/admin/feature-flags/mcp', { headers: auth, data: { enabled: true } })
  await admin.put('/api/v1/admin/feature-flags/ai', { headers: auth, data: { enabled: false } })
  const me = await apiCustomer(playwright, baseURL, 'mcp')
  const friend = await apiCustomer(playwright, baseURL, 'mcppayee')
  try {
    expect(
      (
        await me.ctx.post(`/api/v1/accounts/${me.account.id}/deposit`, {
          headers: { ...me.headers, 'Idempotency-Key': crypto.randomUUID() },
          data: { amount: '1000.00' },
        })
      ).ok(),
    ).toBe(true)
    expect(
      (
        await me.ctx.put('/api/v1/users/me/pin', {
          headers: me.headers,
          data: { pin: '246810', password: me.password },
        })
      ).ok(),
    ).toBe(true)
    const stepUp = (
      await (
        await me.ctx.post('/api/v1/auth/step-up/verify', {
          headers: me.headers,
          data: { action: 'TOKEN_CREATE', pin: '246810' },
        })
      ).json()
    ).stepUpToken
    const created = await me.ctx.post('/api/v1/users/me/tokens', {
      headers: { ...me.headers, 'X-Step-Up-Token': stepUp },
      data: { name: 'Claude Desktop', scope: 'read+propose', expiresInDays: 7 },
    })
    expect(created.status()).toBe(201)
    const pat = (await created.json()).token as string
    const proposed = await me.ctx.post('/api/v1/intents', {
      headers: { Authorization: `Bearer ${pat}` },
      data: {
        kind: 'TRANSFER',
        payload: {
          fromAccountId: me.account.id,
          toAccountNumber: friend.account.accountNumber,
          amount: '120.00',
        },
        rationale: 'Splitting the cab fare.',
      },
    })
    expect(proposed.status()).toBe(201)
    expect((await proposed.json()).proposedBy.agent).toBe('MCP_CLIENT')

    await signIn(page, me.email, me.password, /\/dashboard$/)
    await page.goto('/settings/developer')
    const card = page.getByRole('article', { name: /Proposed action: Send ₹120\.00/ })
    await expect(card).toBeVisible()
    await expect(card.getByText('Connected app')).toBeVisible()
    await card.getByRole('button', { name: 'Approve' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await expect(card.getByText('Approved and carried out.')).toBeVisible()
    const paid = await (
      await friend.ctx.get('/api/v1/accounts', { headers: friend.headers })
    ).json()
    expect(paid[0].balance).toMatch(/^120(\.0+)?$/)
  } finally {
    await admin.put('/api/v1/admin/feature-flags/mcp', {
      headers: auth,
      data: { enabled: flags.mcp },
    })
    await admin.put('/api/v1/admin/feature-flags/ai', {
      headers: auth,
      data: { enabled: flags.ai },
    })
    await Promise.all([admin.dispose(), me.ctx.dispose(), friend.ctx.dispose()])
  }
})

// V2-M5 core: a salary-splitter rule built with the form (no AI), replayed, switched on with PIN
// step-up; money arriving fires it exactly once and moves exactly 20%.
test('autopilot: build a rule by hand, replay, switch on with step-up, it fires once', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json())
    .autopilot as boolean
  await admin.put('/api/v1/admin/feature-flags/autopilot', {
    headers: auth,
    data: { enabled: true },
  })
  const me = await apiCustomer(playwright, baseURL, 'autopilot')
  try {
    const opened = await me.ctx.post('/api/v1/accounts', {
      headers: me.headers,
      data: { type: 'CHECKING' },
    })
    expect(opened.status()).toBe(201)
    const checking = (await opened.json()) as { id: string }
    expect(
      (
        await me.ctx.put('/api/v1/users/me/pin', {
          headers: me.headers,
          data: { pin: '246810', password: me.password },
        })
      ).ok(),
    ).toBe(true)

    await signIn(page, me.email, me.password, /\/dashboard$/)
    await page.goto('/autopilot/new')
    // "Start from a simple rule" with ai off, "Build it myself" with ai on; both open the form.
    await page.getByRole('button', { name: /Start from a simple rule|Build it myself/ }).click()
    await page.getByRole('combobox', { name: 'Trigger' }).click()
    await page.getByRole('option', { name: 'Money arrives' }).click()
    await page.getByLabel('Of at least (optional)').fill('1000')
    await page.getByRole('combobox', { name: 'How much' }).click()
    await page.getByRole('option', { name: '% of the money received' }).click()
    await page.getByLabel('Percent').fill('20')

    await page.getByRole('button', { name: 'Replay the last 90 days' }).click()
    await expect(page.getByText('Times it ran')).toBeVisible()
    await page.getByLabel('Rule name').fill('Salary splitter e2e')
    await page.getByRole('button', { name: 'Switch on' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await expect(page.getByRole('heading', { level: 1, name: 'Salary splitter e2e' })).toBeVisible()
    // The sentence is the server's rendering of what will run.
    await expect(page.getByText(/move 20% of it from Savings/)).toBeVisible()

    // ₹10,000 arrives → one run moves ₹2,000 to checking. Never twice.
    expect(
      (
        await me.ctx.post(`/api/v1/accounts/${me.account.id}/deposit`, {
          headers: { ...me.headers, 'Idempotency-Key': crypto.randomUUID() },
          data: { amount: '10000.00' },
        })
      ).ok(),
    ).toBe(true)
    const rules = (await (
      await me.ctx.get('/api/v1/autopilot/rules', { headers: me.headers })
    ).json()) as Array<{ id: string }>
    expect(rules).toHaveLength(1)
    await expect
      .poll(
        async () =>
          (
            await (
              await me.ctx.get(`/api/v1/autopilot/rules/${rules[0]!.id}/runs`, {
                headers: me.headers,
              })
            ).json()
          ).content.map((r: { outcome: string | null }) => r.outcome),
        { timeout: 30_000 },
      )
      .toEqual(['SUCCEEDED'])
    const balances = (await (
      await me.ctx.get('/api/v1/accounts', { headers: me.headers })
    ).json()) as Array<{ id: string; balance: string }>
    expect(balances.find((a) => a.id === checking.id)!.balance).toMatch(/^2000(\.0+)?$/)
    expect(balances.find((a) => a.id === me.account.id)!.balance).toMatch(/^8000(\.0+)?$/)

    await page.reload()
    await expect(page.getByText('Done', { exact: true }).first()).toBeVisible()
  } finally {
    await admin.put('/api/v1/admin/feature-flags/autopilot', {
      headers: auth,
      data: { enabled: wasOn },
    })
    await Promise.all([admin.dispose(), me.ctx.dispose()])
  }
})

// V2-M5-bills: save a biller after a fetch, pay it from the saved list (the UI never sees the full
// number), and an Autopilot PAY_BILL rule can point at it.
test('saved billers: save, pay from the saved list once, use in an Autopilot rule, remove', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', {
    data: { email: 'admin@quantgen.dev', password: 'Admin@1234' },
  })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json())
    .autopilot as boolean
  await admin.put('/api/v1/admin/feature-flags/autopilot', {
    headers: auth,
    data: { enabled: true },
  })
  const me = await apiCustomer(playwright, baseURL, 'biller')
  try {
    expect(
      (
        await me.ctx.post(`/api/v1/accounts/${me.account.id}/deposit`, {
          headers: { ...me.headers, 'Idempotency-Key': crypto.randomUUID() },
          data: { amount: '20000.00' },
        })
      ).ok(),
    ).toBe(true)
    const consumer = `${Date.now()}`.slice(-9)
    await signIn(page, me.email, me.password, /\/dashboard$/)
    await page.goto('/pay/bills')
    await page.getByRole('button', { name: /Meshnet Broadband/ }).click()
    await page.getByLabel('Customer ID').fill(consumer)
    await page.getByRole('button', { name: /Fetch bill/ }).click()
    await page.getByLabel('Save as (optional)').fill('Home wifi')
    await page.getByRole('button', { name: /Save biller/ }).click()
    await expect(page.getByText(/Saved\. It’s under “Your billers”/)).toBeVisible()

    const saved = page.locator('section', { hasText: 'Your billers' })
    await expect(saved.getByText(`••••${consumer.slice(-4)}`)).toBeVisible()
    await expect(saved.getByText(consumer)).toHaveCount(0)
    await saved.getByRole('button', { name: 'Pay Home wifi' }).click()
    await page.getByRole('button', { name: /Fetch this month’s bill/ }).click()
    await page.getByRole('button', { name: 'Review' }).click()
    await page.getByRole('button', { name: /^Pay ₹/ }).click()
    await expect(page.getByRole('img', { name: 'Bill paid' })).toBeVisible()
    const paid = (await (await me.ctx.get('/api/v1/bill-payments', { headers: me.headers })).json())
      .content
    expect(paid).toHaveLength(1)

    // This month's bill is paid: fetching it again is refused, and the rule still validates.
    const list = (await (
      await me.ctx.get('/api/v1/saved-billers', { headers: me.headers })
    ).json()) as Array<{ id: string; consumerNumberMasked: string }>
    expect(list).toHaveLength(1)
    expect(JSON.stringify(list)).not.toContain(consumer)
    expect(
      (
        await me.ctx.post(`/api/v1/saved-billers/${list[0]!.id}/fetch`, { headers: me.headers })
      ).status(),
    ).toBe(409)
    const rule = {
      dslVersion: 1,
      trigger: { type: 'SCHEDULE', frequency: 'MONTHLY', dayOfMonth: 5 },
      conditions: [],
      actions: [{ type: 'PAY_BILL', savedBillerId: list[0]!.id, fromAccountId: me.account.id }],
      caps: { perRun: '5000.00', perMonth: '5000.00' },
    }
    const v = await (
      await me.ctx.post('/api/v1/autopilot/validate', { headers: me.headers, data: { rule } })
    ).json()
    expect(v).toMatchObject({ valid: true })
    expect(v.rendering).toContain('(Home wifi)')

    await saved.getByRole('button', { name: 'Remove Home wifi' }).click()
    await expect(saved).toHaveCount(0)
    expect(
      await (await me.ctx.get('/api/v1/saved-billers', { headers: me.headers })).json(),
    ).toEqual([])
  } finally {
    await admin.put('/api/v1/admin/feature-flags/autopilot', {
      headers: auth,
      data: { enabled: wasOn },
    })
    await Promise.all([admin.dispose(), me.ctx.dispose()])
  }
})

// Backend bbb2f66 (2026-10-25): block → unblock a requester, leave a settled split group, and close an
// empty account with step-up. No money moves in any of them.
test('gap closes: unblock a requester, leave a split group, close an empty account', async ({
  page,
  playwright,
  baseURL,
}) => {
  test.setTimeout(150_000)
  const me = await apiCustomer(playwright, baseURL, 'closer')
  const asker = await apiCustomer(playwright, baseURL, 'asker')
  try {
    const tag = `${Date.now()}`.slice(-7)
    const myHandle = `closer${tag}@qg`
    const askerHandle = `asker${tag}@qg`
    for (const [who, handle] of [
      [me, myHandle],
      [asker, askerHandle],
    ] as const) {
      const r = await who.ctx.put('/api/v1/users/me/handle', {
        headers: who.headers,
        data: { handle, accountId: who.account.id },
      })
      expect(r.status()).toBe(200)
    }
    const ask = () =>
      asker.ctx.post('/api/v1/payment-requests', {
        headers: asker.headers,
        data: { toHandle: myHandle, amount: '250.00', note: 'Live e2e' },
      })
    expect((await ask()).status()).toBe(201)
    const group = await (
      await me.ctx.post('/api/v1/split-groups', {
        headers: me.headers,
        data: { name: 'Live e2e group', memberHandles: [askerHandle] },
      })
    ).json()
    const empty = await (
      await me.ctx.post('/api/v1/accounts', { headers: me.headers, data: { type: 'CHECKING' } })
    ).json()

    await signIn(page, me.email, me.password, /\/dashboard$/)
    await page.goto('/settings/security')
    await page.getByRole('group', { name: 'Choose a PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await page.getByRole('group', { name: 'Confirm PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    const pw = page.getByLabel('Account password')
    if (await pw.isVisible()) await pw.fill(me.password)
    await page.getByRole('button', { name: 'Set PIN' }).click()
    await expect(page.getByText('Set', { exact: true }).first()).toBeVisible()

    // Decline and block, then unblock from the Blocked panel: they can ask again.
    await page.goto('/pay/requests')
    await page.getByRole('button', { name: `Decline and block ${askerHandle}` }).click()
    const unblock = page.getByRole('button', { name: `Unblock ${askerHandle}` })
    await expect(unblock).toBeVisible()
    await unblock.click()
    await expect(unblock).toBeHidden()
    expect((await ask()).status()).toBe(201)

    // Leave the (settled) group: it drops off the list and the API answers 404.
    await page.goto(`/pay/splits/${group.id}`)
    await page.getByRole('button', { name: 'Leave', exact: true }).click()
    await page.getByRole('button', { name: 'Leave group' }).click()
    await expect(page).toHaveURL(/\/pay\/splits$/)
    await expect(page.getByText('Live e2e group')).toBeHidden()
    expect(
      (await me.ctx.get(`/api/v1/split-groups/${group.id}`, { headers: me.headers })).status(),
    ).toBe(404)

    // Close the empty checking account with the PIN.
    await page.goto(`/accounts/${empty.id}`)
    await page.getByRole('button', { name: 'Close', exact: true }).click()
    await page.getByRole('button', { name: 'Close account' }).click()
    await page.getByRole('group', { name: 'Transaction PIN' }).locator('input').first().click()
    await page.keyboard.type('246810')
    await expect
      .poll(
        async () =>
          (await (await me.ctx.get(`/api/v1/accounts/${empty.id}`, { headers: me.headers })).json())
            .status,
      )
      .toBe('CLOSED')
    await expect(page.getByRole('button', { name: 'Close', exact: true })).toBeHidden()
  } finally {
    await Promise.all([me.ctx.dispose(), asker.ctx.dispose()])
  }
})
