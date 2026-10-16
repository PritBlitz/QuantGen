import { expect, test, type Page } from '@playwright/test'

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
  await go(page, 'Transfer')
  await page.getByRole('radio', { name: 'My accounts' }).click()
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
