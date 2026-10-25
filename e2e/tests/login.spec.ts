import { test, expect } from '@playwright/test';
import { login } from './helpers';

test('rejects bad credentials with a generic message', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel(/Organization/).fill('acme-retail');
  await page.getByLabel('Email').fill('agent1@acme.test');
  await page.getByLabel('Password').fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('alert')).toContainText('Invalid organization, email or password');
});

test('agent signs in, sees tenant in header, and can sign out', async ({ page }) => {
  await login(page, 'acme-retail', 'agent1@acme.test');
  await expect(page.locator('header').first()).toContainText(/acme/i);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
});

test('unauthenticated access to a protected route redirects to login', async ({ page }) => {
  await page.goto('/tickets');
  await expect(page).toHaveURL(/\/login/);
});
