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
  await page.getByRole('link', { name: 'WIPR' }).filter({ visible: true }).first().click()
  const ticket = page.getByRole('complementary', { name: 'Order ticket' })
  await ticket.getByLabel('Quantity').fill('1')
  await ticket.getByRole('button', { name: 'Review buy' }).click()
  await ticket.getByRole('button', { name: 'Confirm buy' }).click()
  await expect(ticket.getByRole('img', { name: 'Order filled' })).toBeVisible()

  await go(page, 'Portfolio')
  await expect(page.getByText('WIPR').filter({ visible: true }).first()).toBeVisible()
})
