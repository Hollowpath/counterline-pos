import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures'

// The customer display's posters, with the display's own API answered by the test (route
// interception): the packaged display app is real, and so is its clock handling, but the clock is
// the page's own (Playwright's), moved by the test; the machine's clock is never touched. The
// server's side of the same answers is covered by the backend tests.

const OUTLET = 'Counterline Bukit Bintang'
const IDLE_STATE = { version: 1, outletName: OUTLET, cart: null, member: { status: 'NONE', firstName: null } }

type Answer = { posters: string[]; validUntilInMs: number } | 'fail'

/** A paired display (a token the test makes up) whose polls and poster asks the test answers. */
async function openDisplay(page: Page, answer: (asked: number) => Answer) {
  let asked = 0
  await page.clock.install()
  await page.addInitScript(({ outlet }) => {
    localStorage.setItem('counterline.display', JSON.stringify({
      token: 'test-display-token', expiresAt: '2999-01-01T00:00:00Z', displayId: 1, outletName: outlet,
    }))
  }, { outlet: OUTLET })
  await page.route(/\/api\/v1\/display\/state/, (route: Route) => {
    const since = new URL(route.request().url()).searchParams.get('since')
    return since === String(IDLE_STATE.version)
      ? route.fulfill({ status: 204 })
      : route.fulfill({ json: IDLE_STATE })
  })
  await page.route(/\/api\/v1\/display\/posters/, (route: Route) => {
    asked += 1
    const next = answer(asked)
    return next === 'fail'
      ? route.fulfill({ status: 503, json: { title: 'Unavailable' } })
      : route.fulfill({
        json: { posters: next.posters, validUntil: new Date(Date.now() + next.validUntilInMs).toISOString() },
        headers: { 'Cache-Control': 'no-store' },
      })
  })
  await page.goto('/display.html')
  return { asked: () => asked }
}

const FIVE_MINUTES = 5 * 60_000
const heading = (page: Page) => page.getByLabel('Posters').getByRole('heading')
const PROMOTIONAL = /Buy 3, get 1 free|Seasonal flavours are here|Birthday month treat|Free gift for members|Free gift with your spend/

test('only the posters of the promotions the server names are shown, with accurate terms', async ({ page }) => {
  await openDisplay(page, () => ({ posters: ['BUY_THREE', 'FUTURE_KEY', 'SPEND_ANYONE'], validUntilInMs: FIVE_MINUTES }))

  const poster = page.getByLabel('Posters')
  await expect(heading(page)).toHaveText('Buy 3, get 1 free')
  await expect(poster).toContainText('Members get a free item for every 3 qualifying items')
  await expect(poster).toContainText('Not with vouchers or discounts. Terms apply.')
  await expect(poster).toContainText(OUTLET)

  // The next one slides in after 10 seconds: the second named poster, not an unnamed one.
  await page.clock.runFor(10_000)
  await expect(heading(page)).toHaveText('Free gift with your spend')
  await expect(poster).toContainText('subject to limits')
  await page.clock.runFor(10_000)
  await expect(heading(page)).toHaveText('Buy 3, get 1 free')

  for (const title of ['Seasonal flavours are here', 'Birthday month treat', 'Free gift for members', 'Become a member', 'Welcome']) {
    await expect(page.getByRole('heading', { name: title, includeHidden: true })).toHaveCount(0)
  }
})

test('before the first answer, and when asking fails, only neutral posters show', async ({ page }) => {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  await page.clock.install()
  await page.addInitScript(({ outlet }) => {
    localStorage.setItem('counterline.display', JSON.stringify({
      token: 'test-display-token', expiresAt: '2999-01-01T00:00:00Z', displayId: 1, outletName: outlet,
    }))
  }, { outlet: OUTLET })
  await page.route(/\/api\/v1\/display\/state/, (route) => route.fulfill({ json: IDLE_STATE }))
  await page.route(/\/api\/v1\/display\/posters/, async (route) => {
    await gate
    await route.fulfill({ status: 503, json: { title: 'Unavailable' } })
  })
  await page.goto('/display.html')

  // Nothing answered yet.
  await expect(heading(page)).toHaveText('Become a member')
  await expect(page.getByLabel('Posters')).toContainText('Ask the cashier about membership and today’s rewards.')
  release()
  // Failed: still neutral, and it stays so as the posters rotate.
  await page.clock.runFor(10_000)
  await expect(heading(page)).toHaveText('Welcome')
  await page.clock.runFor(10_000)
  await expect(heading(page)).toHaveText('Become a member')
  await expect(page.getByText(PROMOTIONAL)).toHaveCount(0)
  await expect(page.getByLabel('Posters')).not.toContainText(/free|RM\d|points/i)
})

test('an answer is shown only until it expires, then the display falls back to neutral posters', async ({ page }) => {
  // The first answer is good for 20 seconds; every later ask fails.
  const display = await openDisplay(page, (asked) => (asked === 1 ? { posters: ['SPEND_MEMBERS'], validUntilInMs: 20_000 } : 'fail'))

  await expect(heading(page)).toHaveText('Free gift for members')
  await expect(page.getByLabel('Posters')).toContainText('Members get a free gift')

  // It asks again shortly before the answer expires (and is refused), but still shows it until then.
  await page.clock.runFor(16_000)
  await expect.poll(display.asked).toBeGreaterThanOrEqual(2)
  await expect(heading(page)).toHaveText('Free gift for members')

  await page.clock.runFor(5_000)
  await expect(heading(page)).toHaveText('Become a member')
  await expect(page.getByText(PROMOTIONAL)).toHaveCount(0)
})

test('an answer that has already expired is not shown at all', async ({ page }) => {
  await openDisplay(page, () => ({ posters: ['BUY_THREE', 'SEASONAL'], validUntilInMs: -1_000 }))
  await expect(heading(page)).toHaveText('Become a member')
  await expect(page.getByText(PROMOTIONAL)).toHaveCount(0)
})
