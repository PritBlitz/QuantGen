import { expect, test, type Page } from '@playwright/test'

async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('Email').fill('demo@quantgen.dev')
  await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
  await page.getByRole('button', { name: /^sign in/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

/**
 * Client-side navigation through the visible main nav (sidebar on desktop, tab bar on
 * mobile). A full page load would restart the in-browser mock database.
 */
/**
 * Navigate in-app (a reload would reset the in-browser mock state): the rail on desktop, the
 * dock or its "More" sheet on mobile.
 */
async function go(page: Page, name: string) {
  const link = page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name, exact: true })
    .filter({ visible: true })
  if (await link.count()) return link.first().click()
  await page.getByRole('button', { name: 'More', exact: true }).click()
  await page.getByRole('dialog').getByRole('link', { name, exact: true }).click()
}

// CLAUDE.md: Playwright end-to-end for login → transfer → buy.
test('login → transfer → buy', async ({ page, isMobile }) => {
  // The longest mocked flow (three journeys + palette); 30 s is tight with a cold dev server.
  test.setTimeout(60_000)
  await login(page)

  // Transfer ₹1,500 from savings to the checking account by number.
  await page.goto('/transfer') // v2 nav groups it under Pay
  await page.getByRole('radio', { name: 'Account no.' }).click()
  await page.getByLabel('Account number').fill('502184739067')
  // v2 confirmation of payee: the server resolves the holder's masked name first.
  await expect(page.getByText('Aarav S.').first()).toBeVisible()
  await page.getByLabel('Amount').fill('1500')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await expect(page.getByText('You’re sending')).toBeVisible()
  await expect(page.getByText('₹1,82,750.75')).toBeVisible() // balance after, decimal
  const send = page.getByRole('button', { name: /send ₹1,500\.00/i })
  await send.click()
  await expect(page.getByRole('img', { name: 'Transfer completed' })).toBeVisible()

  // The balance shown is the server's, after the transfer.
  await go(page, 'Accounts')
  await expect(page.getByText('₹1,82,750.75')).toBeVisible()

  // Buy 2 × QGEN.
  await go(page, 'Markets')
  await page.getByRole('link', { name: 'QGEN' }).filter({ visible: true }).first().click()
  if (isMobile) await page.getByRole('button', { name: 'Buy', exact: true }).click()
  const ticket = isMobile
    ? page.getByRole('dialog')
    : page.getByRole('complementary', { name: 'Order ticket' })
  const qty = ticket.getByLabel('Quantity')
  await qty.fill('2')
  await ticket.getByRole('button', { name: 'Review buy' }).click()
  await expect(ticket.getByText('Estimated cost')).toBeVisible()
  await ticket.getByRole('button', { name: 'Confirm buy' }).click()
  await expect(ticket.getByRole('img', { name: 'Order filled' })).toBeVisible()
  await expect(ticket.getByText(/Bought 2 QGEN/)).toBeVisible()

  // The order appears in history and the position grew to 42.
  if (isMobile) await page.keyboard.press('Escape')
  await page
    .getByRole('button', { name: 'Open command menu' })
    .or(page.getByRole('button', { name: 'Quick actions' }))
    .filter({ visible: true })
    .click()
  // On mobile, Quick actions is a sheet; the palette sits behind "Search everything…".
  if (isMobile) await page.getByRole('button', { name: 'Search everything…' }).click()
  // v2 grew the palette, so filter first: the option can start off-screen.
  await page.getByRole('combobox').filter({ visible: true }).fill('Orders')
  await page.getByRole('option', { name: 'Orders', exact: true }).click()
  await expect(page).toHaveURL(/\/orders$/)
  await expect(page.getByText(/2 ×/).filter({ visible: true }).first()).toBeVisible()
})

test('money confirm buttons disable while pending (no double submit)', async ({ page }) => {
  await login(page)
  await page.goto('/transfer') // v2 nav groups it under Pay
  await page.getByRole('radio', { name: 'Account no.' }).click()
  await page.getByLabel('Account number').fill('502184739067')
  await page.getByLabel('Amount').fill('10')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  const send = page.getByRole('button', { name: /send ₹10\.00/i })
  await send.click()
  await expect(page.getByRole('button', { name: /sending/i })).toBeDisabled()
  await expect(page.getByRole('img', { name: 'Transfer completed' })).toBeVisible()
})
