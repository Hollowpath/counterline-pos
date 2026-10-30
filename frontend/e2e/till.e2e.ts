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

test('the register opens with a declared float and refuses a pay-out above its expected cash', async ({ page, browser, baseURL }) => {
  await startTill(page, browser, baseURL)
  // An open sale in the cart keeps the register from closing: clear it first.
  const clear = page.getByRole('button', { name: 'Clear sale' })
  if (await clear.isVisible()) await clear.click()
  await page.getByRole('button', { name: 'Open menu' }).click()
  await page.getByRole('navigation', { name: 'Main menu' }).getByRole('button', { name: 'Manage Register' }).click()

  // Where the register can be closed (no open orders left on this database), reopen it with a float of RM20.00.
  const figures = page.getByText('Opening float:')
  await expect(figures).toBeVisible()
  const close = page.getByRole('button', { name: 'Close Register' })
  if (await close.isEnabled()) {
    await close.click()
    await page.getByRole('dialog', { name: 'Close Register' }).getByRole('button', { name: 'Close Register' }).click()
    await page.getByRole('button', { name: 'Open Register' }).click()
    const opening = page.getByRole('dialog', { name: 'Open Register' })
    await expect(opening.getByLabel('Opening float (RM)')).toHaveValue('0.00')
    await opening.getByLabel('Opening float (RM)').fill('20.00')
    await opening.getByRole('button', { name: 'Open Register' }).click()
    await expect(figures).toContainText('Opening float: RM20.00')
    await expect(figures).toContainText('Expected cash: RM20.00')
  }
  const expected = /Expected cash: RM(\d+\.\d{2})/.exec(await figures.innerText())![1]
  const over = (Number(expected) + 5).toFixed(2)

  // More than the drawer holds: refused, with what was typed still there.
  await page.getByRole('button', { name: 'Pay In / Out' }).click()
  const movement = page.getByRole('dialog', { name: 'Pay In / Out' })
  await movement.getByRole('tab', { name: 'Pay Out' }).click()
  await movement.getByLabel('Amount (RM)').fill(over)
  await movement.getByLabel('Reason').fill('Supplies')
  await movement.getByRole('button', { name: 'Save' }).click()
  await expect(movement.getByRole('alert')).toContainText(`Pay-out of RM${over} is more than the register's expected cash of RM${expected}`)
  await expect(movement.getByLabel('Amount (RM)')).toHaveValue(over)

  // Exactly what it holds: allowed.
  if (Number(expected) > 0) {
    await movement.getByLabel('Amount (RM)').fill(expected)
    await movement.getByRole('button', { name: 'Save' }).click()
    await expect(movement).toBeHidden()
    await expect(figures).toContainText('Expected cash: RM0.00')
  }
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
