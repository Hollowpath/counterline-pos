import { expect, test as base } from '@playwright/test'
import { watchCsp } from './helpers'

/**
 * Every test runs with a Content-Security-Policy watch on its browser context, registered before any
 * page loads: the test fails if the app breaks its own policy (a blocked script, style, font, image
 * or request), however well the flow itself went.
 */
export const test = base.extend<{ cspViolations: string[] }>({
  cspViolations: [
    async ({ context }, use) => {
      const violations = await watchCsp(context)
      await use(violations)
      expect(violations, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
