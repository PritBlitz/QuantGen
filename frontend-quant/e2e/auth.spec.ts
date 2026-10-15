import { expect, test } from '@playwright/test'

test('login → dashboard → reload restores the session without storing the token', async ({
  page,
}) => {
  await page.goto('/login')
  await page.getByLabel('Email').fill('demo@quantgen.dev')
  await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
  await page.getByRole('button', { name: /^sign in/i }).click()

  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByRole('heading', { name: /aarav/i })).toBeVisible()
  await expect(page.getByText('₹1,84,250.75')).toBeVisible()

  await page.reload()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByRole('heading', { name: /aarav/i })).toBeVisible()

  // The access token must never be persisted (CLAUDE.md).
  const storage = await page.evaluate(() =>
    JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]),
  )
  expect(storage).not.toMatch(/mock\.[0-9a-f-]{36}|eyJ[\w-]+\.[\w-]+/)
})

test('protected routes send anonymous users to login', async ({ page }) => {
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
})

test('sign out ends the session', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Email').fill('demo@quantgen.dev')
  await page.getByLabel('Password', { exact: true }).fill('Demo@1234')
  await page.getByRole('button', { name: /^sign in/i }).click()
  await expect(page).toHaveURL(/\/dashboard$/)

  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('menuitem', { name: /sign out/i }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
})
