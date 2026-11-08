import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against a running stack (frontend + backend + database). They do not start it
 * themselves; see README.md. Point them at another URL with E2E_BASE_URL.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // One browser at a time: the tests share one database, and the import test is heavy.
  workers: 1,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Default (and CI): Playwright's bundled Chromium (`npx playwright install chromium`).
        // E2E_BROWSER_CHANNEL=chrome (or msedge) uses an installed browser instead, for machines
        // where the bundled one cannot be installed.
        channel: process.env.E2E_BROWSER_CHANNEL || undefined,
      },
    },
  ],
});
