import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end smoke tests against an already-running application (the packaged image started by
 * `docker compose --profile app up`); nothing here starts a server. The *.e2e.ts name keeps
 * Vitest from discovering these files.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  forbidOnly: !!process.env.CI,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'line',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8090',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
