import { expect, type Page } from '@playwright/test'

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
