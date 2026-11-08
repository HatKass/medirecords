import { Page, expect } from '@playwright/test';

export const USERS = {
  admin: { username: 'admin', password: 'demo123', fullName: 'Dr. Alex Morgan' },
  viewer: { username: 'viewer', password: 'demo123', fullName: 'Sam Taylor' },
} as const;

/** Signs in through the login form and waits for the dashboard. */
export async function signIn(page: Page, user: keyof typeof USERS): Promise<void> {
  const { username, password, fullName } = USERS[user];
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: `Welcome, ${fullName}` })).toBeVisible();
}

export function navigation(page: Page) {
  return page.getByRole('complementary', { name: 'Main navigation' });
}

/** Letters only, so the value also works as part of a name and as a search term. */
export function uniqueSuffix(): string {
  return Array.from({ length: 8 }, () => String.fromCharCode(97 + Math.floor(Math.random() * 26))).join('');
}
