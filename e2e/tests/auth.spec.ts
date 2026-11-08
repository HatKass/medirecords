import { expect, test } from '@playwright/test';
import { navigation, signIn } from './support';

test.describe('sign-in and roles', () => {
  test('admin signs in, sees every section and signs out', async ({ page }) => {
    await signIn(page, 'admin');

    const nav = navigation(page);
    for (const section of ['Dashboard', 'Patients', 'Import', 'Import history', 'Audit log']) {
      await expect(nav.getByRole('link', { name: section, exact: true })).toBeVisible();
    }
    await expect(page.getByRole('banner').getByText('ADMIN', { exact: true })).toBeVisible();

    await nav.getByRole('link', { name: 'Audit log' }).click();
    await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible();

    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
    // The session is gone server-side too: protected pages send us back to the login form.
    await page.goto('/patients');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fpatients/);
  });

  test('viewer signs in read-only and cannot open admin pages', async ({ page }) => {
    await signIn(page, 'viewer');

    const nav = navigation(page);
    await expect(nav.getByRole('link', { name: 'Patients' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Import history' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Import', exact: true })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Audit log' })).toHaveCount(0);
    await expect(page.getByRole('banner').getByText('VIEWER', { exact: true })).toBeVisible();

    // Typing the URL of an admin page lands on the dashboard instead.
    for (const adminPage of ['/audit-log', '/import']) {
      await page.goto(adminPage);
      await expect(page.getByRole('heading', { name: 'Welcome, Sam Taylor' })).toBeVisible();
      await expect(page).toHaveURL(/\/$/);
    }

    // The API refuses as well, not only the UI (page.request sends the page's session cookies).
    const auditLog = await page.request.get('/api/audit-log');
    expect(auditLog.status()).toBe(403);

    await page.goto('/patients');
    await expect(page.getByRole('heading', { name: 'Patients' })).toBeVisible();
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add patient' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Edit / })).toHaveCount(0);
  });

  test('wrong password shows an error and a deep link survives sign-in', async ({ page }) => {
    await page.goto('/imports');
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fimports/);

    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid username or password.');

    await page.getByLabel('Password').fill('demo123');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/imports$/);
  });
});
