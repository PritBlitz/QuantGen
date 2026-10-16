import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/** WCAG 2.1 AA audit of every built screen, in both themes (CLAUDE.md / DESIGN.md §3.8). */
const PUBLIC = ['/', '/login', '/register']
const APP = [
  'Dashboard',
  'Accounts',
  'Transfer',
  'Markets',
  'Portfolio',
  'Orders',
  'Loans',
  'Profile',
] as const

async function audit(page: Page, label: string) {
  // Let entrance animations finish so contrast is measured on final colours.
  await page.waitForTimeout(900)
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    // Decorative grain overlay and chart canvases aren't content.
    .exclude('canvas')
    .analyze()
  const summary = results.violations.map(
    (v) =>
      `${label}: [${v.impact}] ${v.id} — ${v.help} (${v.nodes.length}× e.g. ${v.nodes[0]?.target.join(' ')})`,
  )
  expect(summary, summary.join('\n')).toEqual([])
}

for (const theme of ['dark', 'light'] as const) {
  test.describe(`${theme} theme`, () => {
    test.use({ colorScheme: theme, reducedMotion: 'reduce' })
    test.beforeEach(async ({ page }) => {
      await page.addInitScript((t) => localStorage.setItem('qg.theme', t), theme)
    })

    for (const path of PUBLIC) {
      test(`public ${path}`, async ({ page }) => {
        await page.goto(path)
        await audit(page, `${theme} ${path}`)
      })
    }

    test('signed-in screens', async ({ page }) => {
      test.setTimeout(120_000)
      await page.goto('/login')
      await page.getByLabel('Email').fill('demo@quantgen.dev')
      await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
      await page.getByRole('button', { name: /^sign in/i }).click()
      await expect(page).toHaveURL(/\/dashboard$/)
      const main = page.getByRole('navigation', { name: 'Main' }).filter({ visible: true })
      await expect(main).toBeVisible()
      for (const name of APP) {
        const link = main.getByRole('link', { name, exact: true })
        // Screens outside the mobile tab bar: load by URL (the refresh cookie restores the session).
        if (await link.count()) await link.click()
        else await page.goto(`/${name.toLowerCase()}`)
        await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
        await audit(page, `${theme} ${name}`)
      }
      await page
        .getByRole('navigation', { name: 'Main' })
        .getByRole('link', { name: 'Markets', exact: true })
        .filter({ visible: true })
        .click()
      await page.getByRole('link', { name: 'QGEN' }).filter({ visible: true }).first().click()
      await audit(page, `${theme} instrument`)
      await page.getByRole('link', { name: /^notifications/i }).click()
      await expect(page.getByRole('heading', { name: 'Notifications' })).toBeVisible()
      await audit(page, `${theme} notifications`)
    })

    test('admin screens', async ({ page }) => {
      test.setTimeout(120_000)
      await page.goto('/login')
      await page.getByLabel('Email').fill('admin@quantgen.dev')
      await page.getByLabel('Password', { exact: true }).fill('Admin@1234')
      await page.getByRole('button', { name: /^sign in/i }).click()
      await expect(page).toHaveURL(/\/admin$/)
      await audit(page, `${theme} admin overview`)
      for (const name of ['Users', 'Loan queue', 'Audit log', 'Reconcile']) {
        await page
          .getByRole('navigation', { name: 'Main' })
          .getByRole('link', { name, exact: true })
          .filter({ visible: true })
          .click()
        if (name === 'Reconcile') {
          await page.getByRole('button', { name: 'Run reconciliation' }).click()
          await expect(page.getByText('Balanced', { exact: true })).toBeVisible()
        }
        await audit(page, `${theme} admin ${name}`)
      }
      await page
        .getByRole('navigation', { name: 'Main' })
        .getByRole('link', { name: 'Users', exact: true })
        .filter({ visible: true })
        .click()
      await page
        .getByRole('link', { name: /meera iyer/i })
        .filter({ visible: true })
        .first()
        .click()
      await audit(page, `${theme} admin user`)
      await page.getByRole('button', { name: 'Freeze Savings ••••2290' }).click()
      await audit(page, `${theme} admin freeze dialog`)
    })
  })
}
