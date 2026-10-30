import { expect, test } from './fixtures'
import { watchCsp } from './helpers'

// These tests run in contexts of their own, so that a violation they provoke or expect is theirs to
// judge and does not fail the automatic watch of the default context.

test('the apps and the API send the security headers, and the page load breaks none of the policy', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL })
  const violations = await watchCsp(context)
  const page = await context.newPage()

  for (const path of ['/', '/dashboard.html', '/display.html']) {
    const response = await page.goto(path)
    const headers = response!.headers()
    const policy = headers['content-security-policy']
    expect(policy, path).toContain("script-src 'self';")
    expect(policy, path).not.toContain('unsafe-eval')
    expect(policy, path).not.toContain('unsafe-inline')
    expect(headers['referrer-policy'], path).toBe('no-referrer')
    expect(headers['x-content-type-options'], path).toBe('nosniff')
    expect(headers['x-frame-options'], path).toBe('DENY')
    expect(headers['strict-transport-security'], path).toBeUndefined()
    await expect(page.locator('#root')).not.toBeEmpty()
  }

  const api = await page.request.get('/api/v1/outlets')
  expect(api.status()).toBe(200)
  expect(api.headers()['content-security-policy']).toContain("default-src 'self'")
  expect(api.headers()['referrer-policy']).toBe('no-referrer')

  expect(violations).toEqual([])
  await context.close()
})

test('the policy is really enforced: an injected inline script is blocked and reported', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL })
  const violations = await watchCsp(context)
  const page = await context.newPage()
  await page.goto('/')

  const ran = await page.evaluate(`(() => {
    const script = document.createElement('script')
    script.textContent = 'window.injectedScriptRan = true'
    document.head.append(script)
    return window.injectedScriptRan === true
  })()`)

  expect(ran).toBe(false)
  await expect.poll(() => violations.length).toBe(1)
  expect(violations[0]).toContain('script-src')
  await context.close()
})

test('Swagger UI works locally under a policy of its own, and the apps keep the strict one', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ baseURL })
  const violations = await watchCsp(context)
  const page = await context.newPage()

  const app = await page.goto('/')
  const docs = await page.goto('/swagger-ui/index.html')
  const appPolicy = app!.headers()['content-security-policy']
  const docsPolicy = docs!.headers()['content-security-policy']
  expect(docsPolicy).not.toEqual(appPolicy)
  expect(docsPolicy).toContain("script-src 'self';")
  expect(docsPolicy).not.toContain('unsafe-eval')

  // Rendered, with the API's operations listed and one opened.
  await expect(page.locator('.swagger-ui .info .title')).toBeVisible()
  const operations = page.locator('.swagger-ui .opblock-tag').first()
  await expect(operations).toBeVisible()
  await operations.click()
  await expect(page.locator('.swagger-ui .opblock').first()).toBeVisible()

  const document = await page.request.get('/v3/api-docs')
  expect(document.status()).toBe(200)
  expect(await document.json()).toHaveProperty('paths')

  expect(violations).toEqual([])
  await context.close()
})
