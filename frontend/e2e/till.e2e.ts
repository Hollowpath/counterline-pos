import { expect, test } from '@playwright/test'
import { CASHIER_PIN, enterPin, MANAGER_PIN, OUTLET, signInToDashboard } from './helpers'

test('a till is set up, signs in, and records a cash sale of a seeded item', async ({ page, browser, baseURL }) => {
  // An outlet has one till. A database that already has one (a Compose volume kept from an earlier
  // run) has it reset from the dashboard first, as head office would; a fresh database has none.
  const office = await browser.newContext({ baseURL })
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

  // Set this iPad up for the outlet with the manager's PIN, then sign in as the cashier.
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Set up this iPad' })).toBeVisible()
  await page.getByRole('button', { name: new RegExp(OUTLET.name) }).click()
  await enterPin(page, MANAGER_PIN)
  await expect(page.getByRole('heading', { name: OUTLET.name })).toBeVisible()
  await enterPin(page, CASHIER_PIN)
  await expect(page.getByText('Welcome, Aisyah Rahman')).toBeVisible()

  // Sales need an open register; open it if an earlier run did not leave it open.
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main menu' }).getByRole('button', { name: 'Manage Register' }).click()
  const open = page.getByRole('button', { name: 'Open Register' })
  await expect(open.or(page.getByText('OPEN', { exact: true }))).toBeVisible()
  if (await open.isVisible()) {
    await open.click()
  }
  await expect(page.getByText('OPEN', { exact: true })).toBeVisible()

  // Back at the point of sale: add a seeded item and pay for it in cash.
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main menu' }).getByRole('button', { name: 'Point of Sale' }).click()
  await page.getByRole('button', { name: /White Stand Pouch/ }).click()
  await page.getByRole('region', { name: 'Products' }).getByRole('button', { name: /WSP Caramel/ }).click()
  const sale = page.getByRole('complementary', { name: 'Current sale' })
  await expect(sale.getByTestId('cart-total')).toHaveText('RM16.00')

  await sale.getByRole('button', { name: 'Pay', exact: true }).click()
  await page.getByRole('button', { name: 'Cash', exact: true }).click()
  await page.getByRole('button', { name: 'Confirm payment' }).click()

  // The UI confirms the sale was recorded.
  await expect(page.getByRole('dialog', { name: 'Sale complete' })).toBeVisible()
})
