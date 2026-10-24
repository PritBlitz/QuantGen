/// <reference lib="dom" />
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/** WCAG 2.1 AA audit of every built screen, in both themes (CLAUDE.md / DESIGN.md §3.8). */
const PUBLIC = ['/', '/login', '/register']
/** Every customer screen, v1 and v2, by path (most aren't in the mobile dock). */
const APP = [
  '/dashboard',
  '/insights',
  '/accounts',
  '/transfer',
  '/pay',
  '/pay/requests',
  '/pay/scheduled',
  '/pay/bills',
  '/savings',
  '/cards',
  '/cards/sandbox',
  '/markets',
  '/portfolio',
  '/portfolio/tax',
  '/orders',
  '/lab',
  '/loans',
  '/autopilot',
  '/copilot',
  '/proof',
  '/settings',
  '/settings/security',
] as const

async function audit(page: Page, label: string) {
  // Measure contrast on final colours, not mid-transition (a fixed delay was flaky under
  // parallel load). motion tweens the page wrapper's opacity via inline styles, which
  // getAnimations() can't see, so also wait until exactly one fully opaque page is mounted,
  // on two samples 250 ms apart (so a page that's about to exit doesn't count as settled).
  await page.waitForLoadState('networkidle')
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))
  const settled = () =>
    page.evaluate(() => {
      const main = document.getElementById('main')
      if (!main) return true
      const pages = [...main.children] as HTMLElement[]
      return pages.length === 1 && getComputedStyle(pages[0]!).opacity === '1'
    })
  await expect
    .poll(async () => (await settled()) && (await page.waitForTimeout(250), await settled()), {
      timeout: 15_000,
    })
    .toBe(true)
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    // Decorative grain overlay and chart canvases aren't content.
    .exclude('canvas')
    .analyze()
  // Include the offending HTML and contrast numbers so a failure is diagnosable from CI logs.
  const summary = results.violations.map(
    (v) =>
      `${label}: [${v.impact}] ${v.id} — ${v.help} (${v.nodes.length}×)\n` +
      v.nodes
        .slice(0, 3)
        .map((n) => `    ${n.html.slice(0, 160)}\n      ${n.failureSummary ?? ''}`)
        .join('\n'),
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

    async function loginAsDemo(page: Page) {
      await page.goto('/login')
      await page.getByLabel('Email').fill('demo@quantgen.dev')
      await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
      await page.getByRole('button', { name: /^sign in/i }).click()
      await expect(page).toHaveURL(/\/dashboard$/)
      await expect(
        page.getByRole('navigation', { name: 'Main' }).filter({ visible: true }),
      ).toBeVisible()
    }

    // Chunked so one test never audits all 22 screens (that blew the timeout under parallel load).
    const CHUNK = 6
    for (let c = 0; c < APP.length; c += CHUNK) {
      const paths = APP.slice(c, c + CHUNK)
      test(`signed-in screens ${paths[0]}…`, async ({ page }) => {
        test.setTimeout(120_000)
        await loginAsDemo(page)
        for (const path of paths) {
          // Loaded by URL: the refresh cookie restores the session on each load.
          await page.goto(path)
          await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
          await audit(page, `${theme} ${path}`)
        }
      })
    }

    test('instrument and notifications', async ({ page }) => {
      test.setTimeout(120_000)
      await loginAsDemo(page)
      await page
        .getByRole('navigation', { name: 'Main' })
        .getByRole('link', { name: 'Markets', exact: true })
        .filter({ visible: true })
        .click()
      await page.getByRole('link', { name: 'QGEN' }).filter({ visible: true }).first().click()
      await expect(page).toHaveURL(/\/markets\/QGEN$/)
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
      const ADMIN_PATHS = [
        'users',
        'loans',
        'fraud',
        'analytics',
        'ai',
        'flags',
        'audit',
        'reconcile',
      ]
      for (const path of ADMIN_PATHS) {
        await page.goto(`/admin/${path}`)
        await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
        const name = path
        if (name === 'reconcile') {
          await page.getByRole('button', { name: 'Run reconciliation' }).click()
          await expect(page.getByText('Balanced', { exact: true })).toBeVisible()
        }
        await audit(page, `${theme} admin ${name}`)
      }
      await page.goto('/admin/users')
      await page
        .getByRole('link', { name: /meera iyer/i })
        .filter({ visible: true })
        .first()
        .click()
      await expect(page.getByRole('heading', { name: 'Meera Iyer' })).toBeVisible()
      await audit(page, `${theme} admin user`)
      await page.getByRole('button', { name: 'Freeze Savings ••••2290' }).click()
      await audit(page, `${theme} admin freeze dialog`)
    })
  })
}
