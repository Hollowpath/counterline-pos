import { expect, test } from './fixtures'
import { startTill } from './helpers'

// One till per outlet: each test sets it up afresh, so the tests of this file run one after another.
test.describe.configure({ mode: 'serial' })

test('a till is set up, signs in, and records a cash sale of a seeded item', async ({ page, browser, baseURL }) => {
  await startTill(page, browser, baseURL)

  // Add a seeded item and pay for it in cash.
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

// On a phone the cart is a short sheet under the product grid. Its lines once collapsed to nothing
// there: they stay in view, scroll, and can be removed, with the total and Pay still reachable.
for (const [width, height] of [[390, 844], [360, 640]]) {
  test.describe(`phone width ${width}×${height}`, () => {
    test.use({ viewport: { width, height } })

    test('the cart shows its lines, which can be removed, above the total and Pay', async ({ page, browser, baseURL }) => {
      await startTill(page, browser, baseURL)

      await page.getByRole('button', { name: /White Stand Pouch/ }).click()
      const products = page.getByRole('region', { name: 'Products' })
      for (const item of [/WSP Caramel/, /WSP BBQ/, /WSP Cheese/, /WSP Tomato/]) {
        await products.getByRole('button', { name: item }).click()
      }
      const sale = page.getByRole('complementary', { name: 'Current sale' })
      await expect(sale.getByTestId('cart-total')).toHaveText('RM64.00')

      // Not just present in the page: actually inside the screen, not clipped by the sheet.
      const remove = sale.getByRole('button', { name: 'Remove WSP Caramel' })
      await remove.scrollIntoViewIfNeeded()
      await expect(remove).toBeInViewport({ ratio: 1 })
      await expect(sale.getByTestId('cart-total')).toBeInViewport({ ratio: 1 })
      await expect(sale.getByRole('button', { name: 'Pay', exact: true })).toBeInViewport({ ratio: 1 })

      await remove.click()
      await expect(sale.getByRole('button', { name: 'Remove WSP Caramel' })).toHaveCount(0)
      await expect(sale.getByTestId('cart-total')).toHaveText('RM48.00')
    })
  })
}
