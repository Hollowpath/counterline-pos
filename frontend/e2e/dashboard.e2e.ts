import { expect, test } from '@playwright/test'
import { signInToDashboard } from './helpers'

test('head office signs in and opens the overview and the seeded catalogue', async ({ page }) => {
  await signInToDashboard(page)

  await expect(page.getByRole('region', { name: 'Sales overview' })).toBeVisible()

  await page.getByRole('navigation', { name: 'Dashboard' }).getByRole('button', { name: 'Items & Prices' }).click()
  await expect(page.getByRole('heading', { name: 'Items & Prices' })).toBeVisible()
  // The local profile's demo range: a folder, and an item in it with its price.
  await expect(page.getByRole('region', { name: 'White Stand Pouch', exact: true })).toBeVisible()
  await expect(page.getByLabel('Price of WSP Caramel', { exact: true })).toHaveValue('16.00')
})
