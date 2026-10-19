import { expect, test, type APIRequest, type Page } from '@playwright/test'

/**
 * Real UI against the REAL backend (no MSW). Opt-in: needs the API on :8080 and Postgres.
 *   QG_LIVE=1 npx playwright test e2e/live.spec.ts --project=desktop
 * A fresh user each run, so it never depends on seed balances.
 */
test.skip(!process.env.QG_LIVE, 'set QG_LIVE=1 with the backend running on :8080')

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
test('savings: set PIN, create a goal, book an FD and close it early with step-up', async ({ page }) => {
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

test('cards: issue, reveal with step-up, pay the demo store → hold → capture → refund', async ({ page, playwright, baseURL }) => {
  test.setTimeout(150_000)
  // Cards sit behind a feature flag (off on a fresh :8080). Turn it on as admin, restore after.
  const admin = await playwright.request.newContext({ baseURL })
  const login = await admin.post('/api/v1/auth/login', { data: { email: 'admin@quantgen.dev', password: 'Admin@1234' } })
  const auth = { Authorization: `Bearer ${(await login.json()).accessToken}` }
  const wasOn = (await (await admin.get('/api/v1/feature-flags', { headers: auth })).json()).cards as boolean
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
    await admin.put('/api/v1/admin/feature-flags/cards', { headers: auth, data: { enabled: wasOn } })
    await admin.dispose()
  }
})

/** A fresh customer created through the API, with its own cookie jar. */
async function apiCustomer(playwright: { request: APIRequest }, baseURL: string | undefined, tag: string) {
  const ctx = await playwright.request.newContext({ baseURL })
  const n = `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 10)
  const email = `${tag}${n}@example.com`
  const password = 'Live@12345'
  expect((await ctx.post('/api/v1/auth/register', { data: { fullName: `${tag} Tester`, email, phone: `+91${n}9`, password } })).ok()).toBe(true)
  const token = (await (await ctx.post('/api/v1/auth/login', { data: { email, password } })).json()).accessToken as string
  const headers = { Authorization: `Bearer ${token}` }
  const accounts = (await (await ctx.get('/api/v1/accounts', { headers })).json()) as Array<{ id: string; accountNumber: string; balance: string }>
  return { ctx, email, password, headers, account: accounts[0]! }
}

test('fraud: an unusual transfer is held for review, then an admin releases it', async ({ page, playwright, baseURL }) => {
  test.setTimeout(150_000)
  const sender = await apiCustomer(playwright, baseURL, 'sender')
  const friend = await apiCustomer(playwright, baseURL, 'friend')
  const stranger = await apiCustomer(playwright, baseURL, 'stranger')
  try {
    const idem = () => ({ ...sender.headers, 'Idempotency-Key': crypto.randomUUID() })
    expect((await sender.ctx.post(`/api/v1/accounts/${sender.account.id}/deposit`, { headers: idem(), data: { amount: '40000.00' } })).ok()).toBe(true)
    // Some history with a known payee, so the next one stands out.
    for (let i = 0; i < 3; i++) {
      const r = await sender.ctx.post('/api/v1/transfers', { headers: idem(), data: { fromAccountId: sender.account.id, toAccountNumber: friend.account.accountNumber, amount: '1000.00' } })
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
    const held = await (await stranger.ctx.get('/api/v1/accounts', { headers: stranger.headers })).json()
    expect(held[0].balance).toMatch(/^0(\.0+)?$/)
    await signOut(page)

    // Admin releases it with a reason; the stranger is credited.
    await signIn(page, 'admin@quantgen.dev', 'Admin@1234', /\/admin$/)
    await page.goto('/admin/fraud')
    await page.getByRole('link', { name: /₹30,000\.00/ }).first().click()
    await expect(page.getByText('Large amount to someone never paid before').first()).toBeVisible()
    await page.getByRole('button', { name: /Release transfer/ }).click()
    const dialog = page.getByRole('dialog', { name: 'Release this transfer?' })
    await dialog.getByRole('textbox').fill('Called the customer; payee confirmed.')
    await dialog.getByRole('button', { name: 'Release' }).click()
    await expect(dialog).toBeHidden()
    await expect(page.getByText('Called the customer; payee confirmed.')).toBeVisible()
    await expect
      .poll(async () => (await (await stranger.ctx.get('/api/v1/accounts', { headers: stranger.headers })).json())[0].balance)
      .toMatch(/^30000(\.0+)?$/)
  } finally {
    await Promise.all([sender.ctx.dispose(), friend.ctx.dispose(), stranger.ctx.dispose()])
  }
})
