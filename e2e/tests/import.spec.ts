import { expect, test } from '@playwright/test';
import path from 'node:path';
import { signIn } from './support';

const SAMPLE_CSV = path.resolve(__dirname, '../../sample-data/patients_messy.csv');

/**
 * Uploads the ~5,000-row sample file and follows the job to the end. The counts depend on what
 * earlier runs imported (a second run rejects every row as a duplicate), so the test checks that
 * every row was processed and the rejected rows can be downloaded, not exact numbers.
 */
test('admin uploads the sample CSV and the import finishes', async ({ page }) => {
  test.setTimeout(120_000);
  await signIn(page, 'admin');
  await page.goto('/import');

  await page.locator('input[type="file"]').setInputFiles(SAMPLE_CSV);
  await expect(page.getByText('patients_messy.csv')).toBeVisible();
  await page.getByRole('button', { name: 'Start import' }).click();

  await expect(page).toHaveURL(/\/imports\/\d+$/);
  const heading = page.getByRole('heading', { level: 1 });
  await expect(heading).toContainText('patients_messy.csv');
  await expect(heading).toContainText('Completed', { timeout: 90_000 });
  await expect(page.getByRole('progressbar', { name: 'Import progress' })).toHaveAttribute('aria-valuenow', '100');

  // The sample file always has rejected rows; their report downloads under the original name.
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download error report' }).click();
  expect((await download).suggestedFilename()).toBe('patients_messy-errors.csv');

  // And the import is listed in the history.
  const importId = page.url().split('/').pop();
  await page.goto('/imports');
  await expect(page.getByRole('row', { name: new RegExp(`patients_messy\\.csv #${importId} `) })).toBeVisible();
});
