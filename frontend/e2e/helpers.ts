import { expect, type Browser, type Page } from '@playwright/test'

// The `local` profile's synthetic sample accounts (README, "Run locally"); nothing is added here.
export const HEAD_OFFICE = { email: 'admin@counterline.test', password: 'counterline-demo' }
export const OUTLET = { code: 'O1', name: 'Counterline Bukit Bintang' }
export const MANAGER_PIN = '999999'
export const CASHIER_PIN = '111111'

export async function signInToDashboard(page: Page) {
  await page.goto('/dashboard.html')
  await page.getByLabel('Email').fill(HEAD_OFFICE.email)
  await page.getByLabel('Password').fill(HEAD_OFFICE.password)
  await page.getByRole('button', { name: 'Log in' }).click()
  await expect(page.getByRole('navigation', { name: 'Dashboard' })).toBeVisible()
}

/** The till's PIN pad: the sixth digit signs in (or sets up) by itself. */
export async function enterPin(page: Page, pin: string) {
  for (const digit of pin) {
    await page.getByRole('button', { name: digit, exact: true }).click()
  }
}

/**
 * A till, ready to sell: any till left over from an earlier run against the same database is reset
 * from the dashboard first (an outlet has one till), this page is set up with the manager PIN, the
 * cashier signs in, and the register is opened if it is closed. Ends on the point of sale.
 */
export async function startTill(page: Page, browser: Browser, baseURL: string | undefined) {
  // Head office works on a desktop screen whatever the till's size under test.
  const office = await browser.newContext({ baseURL, viewport: { width: 1280, height: 800 } })
  const dashboard = await office.newPage()
  await signInToDashboard(dashboard)
  await dashboard.getByRole('navigation', { name: 'Dashboard' }).getByRole('button', { name: 'iPads' }).click()
  await expect(dashboard.getByRole('heading', { name: 'iPads' })).toBeVisible()
  await expect(dashboard.getByRole('region', { name: 'Till iPads' })).toBeVisible()
  const reset = dashboard.getByRole('button', { name: new RegExp(`^Reset .* at ${OUTLET.name}$`) })
  if (await reset.count()) {
    await reset.click()
    await dashboard.getByRole('button', { name: 'Confirm reset' }).click()
    await expect(reset).toHaveCount(0)
  }
  await office.close()

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Set up this iPad' })).toBeVisible()
  await page.getByRole('button', { name: new RegExp(OUTLET.name) }).click()
  await enterPin(page, MANAGER_PIN)
  await expect(page.getByRole('heading', { name: OUTLET.name })).toBeVisible()
  await enterPin(page, CASHIER_PIN)
  await expect(page.getByText('Welcome, Aisyah Rahman')).toBeVisible()

  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main menu' }).getByRole('button', { name: 'Manage Register' }).click()
  const open = page.getByRole('button', { name: 'Open Register' })
  await expect(open.or(page.getByText('OPEN', { exact: true }))).toBeVisible()
  if (await open.isVisible()) {
    await open.click()
  }
  await expect(page.getByText('OPEN', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main menu' }).getByRole('button', { name: 'Point of Sale' }).click()
}
