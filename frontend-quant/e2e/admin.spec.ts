import { expect, test, type Page } from '@playwright/test'

async function loginAsAdmin(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email').fill('admin@quantgen.dev')
  await page.getByLabel('Password', { exact: true }).fill('Admin@1234')
  await page.getByRole('button', { name: /^sign in/i }).click()
  await expect(page).toHaveURL(/\/admin$/)
}

/** Client-side nav keeps the in-browser mock database (a reload would reset it). */
async function go(page: Page, name: string) {
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name, exact: true })
    .filter({ visible: true })
    .click()
}

// PRD M4/M5: apply → admin approve → disburse; freeze is audited.
test('admin approves a loan, freezes an account, and both are audited', async ({ page }) => {
  await loginAsAdmin(page)
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
  await expect(page.getByRole('heading', { name: /loan queue\s*2 pending/i })).toBeVisible()

  await go(page, 'Loan queue')
  await page.getByRole('button', { name: 'Approve Priya Nair’s application' }).click()
  const dialog = page.getByRole('dialog', { name: 'Approve and disburse?' })
  await dialog.getByLabel(/note/i).fill('Income verified against bank statements')
  await dialog.getByRole('button', { name: 'Approve and disburse' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('link', { name: 'Priya Nair' })).toBeHidden()

  await go(page, 'Users')
  await page.getByRole('searchbox', { name: 'Search users' }).fill('meera')
  await page
    .getByRole('link', { name: /meera iyer/i })
    .filter({ visible: true })
    .first()
    .click()
  await page.getByRole('button', { name: 'Freeze Checking ••••7731' }).click()
  const freeze = page.getByRole('dialog', { name: 'Freeze this account?' })
  await freeze.getByLabel('Reason').fill('Customer reported a lost phone')
  await freeze.getByRole('button', { name: 'Freeze account' }).click()
  await expect(page.getByRole('button', { name: 'Unfreeze Checking ••••7731' })).toBeVisible()

  await go(page, 'Audit log')
  await expect(
    page
      .getByText('Account ••••7731: Customer reported a lost phone')
      .filter({ visible: true })
      .first(),
  ).toBeVisible()
  await expect(
    page.getByText('Income verified against bank statements').filter({ visible: true }).first(),
  ).toBeVisible()

  await go(page, 'Reconcile')
  await page.getByRole('button', { name: 'Run reconciliation' }).click()
  await expect(page.getByText('Balanced', { exact: true })).toBeVisible()
})

test('a customer cannot reach the admin console', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Email').fill('demo@quantgen.dev')
  await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
  await page.getByRole('button', { name: /^sign in/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  await page.getByRole('link', { name: /notifications, 3 unread/i }).click()
  await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible()
  await page.getByRole('button', { name: 'Mark all as read' }).click()
  await expect(page.getByText('You’re all caught up.')).toBeVisible()
  await expect(
    page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Loan queue' }),
  ).toHaveCount(0)
})
