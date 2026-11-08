import { expect, test } from '@playwright/test';
import { signIn, uniqueSuffix } from './support';

test('admin creates, edits and deletes a patient', async ({ page }) => {
  const lastName = `Playwright${uniqueSuffix()}`;
  const fullName = `Jane ${lastName}`;
  await signIn(page, 'admin');
  await page.goto('/patients');

  // Create
  await page.getByRole('button', { name: 'Add patient' }).click();
  const panel = page.getByRole('dialog', { name: 'New patient' });
  await panel.getByLabel('First name').fill('Jane');
  await panel.getByLabel('Last name').fill(lastName);
  await panel.getByLabel('Date of birth').fill('1985-04-12');
  await panel.getByLabel('Gender').selectOption('FEMALE');
  await panel.getByLabel('Email').fill(`${lastName.toLowerCase()}@example.com`);
  await panel.getByLabel('City').fill('Leeds');
  await panel.getByRole('button', { name: 'Create patient' }).click();
  await expect(page.getByText(`Created ${fullName}.`)).toBeVisible();
  await expect(panel).toBeHidden();

  // Find it
  await page.getByPlaceholder('Search by name…').fill(lastName);
  const row = page.getByRole('row').filter({ hasText: lastName });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('Leeds');

  // Edit
  await row.getByRole('button', { name: `Edit ${fullName}` }).click();
  const editPanel = page.getByRole('dialog', { name: 'Edit patient' });
  await expect(editPanel.getByLabel('Last name')).toHaveValue(lastName);
  await editPanel.getByLabel('City').fill('York');
  await editPanel.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText(`Saved changes to ${fullName}.`)).toBeVisible();
  await expect(row).toContainText('York');

  // Delete (keeps repeated runs tidy)
  await row.getByRole('button', { name: `Delete ${fullName}` }).click();
  await page.getByRole('alertdialog', { name: 'Delete patient?' }).getByRole('button', { name: 'Delete patient' }).click();
  await expect(page.getByText(`Deleted ${fullName}.`)).toBeVisible();
  await expect(row).toHaveCount(0);
});

test('the form explains invalid input before anything is sent', async ({ page }) => {
  await signIn(page, 'admin');
  await page.goto('/patients');
  await page.getByRole('button', { name: 'Add patient' }).click();
  const panel = page.getByRole('dialog', { name: 'New patient' });
  let posted = false;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/patients')) posted = true;
  });

  await panel.getByLabel('Email').fill('not-an-email');
  await panel.getByRole('button', { name: 'Create patient' }).click();

  await expect(panel.getByText('Enter a valid email address, e.g. name@example.com.')).toBeVisible();
  await expect(panel.getByText('This field is required.').first()).toBeVisible();
  await expect(panel).toBeVisible();
  expect(posted).toBe(false);
});
