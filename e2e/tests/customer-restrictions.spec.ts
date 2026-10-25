import { test, expect } from '@playwright/test';
import { login } from './helpers';

test('customer has no staff nav and is blocked from admin and dashboard', async ({ page }) => {
  await login(page, 'acme-retail', 'customer1@acme.test');
  const nav = page.getByRole('navigation', { name: 'Main' });
  await expect(nav.getByRole('link', { name: 'Administration' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Dashboard' })).toHaveCount(0);
  await page.goto('/admin/users');
  await expect(page.getByRole('heading', { name: 'Forbidden' })).toBeVisible();
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Forbidden' })).toBeVisible();
});

test('customer ticket detail has no AI panel and no internal tab', async ({ page }) => {
  await login(page, 'acme-retail', 'customer1@acme.test');
  await page.goto('/tickets/new');
  const title = `E2E customer ${Date.now()}`;
  await page.getByLabel(/^Title/).fill(title);
  await page.getByLabel(/^Description/).fill('Dummy customer description for e2e.');
  await page.getByRole('button', { name: 'Create ticket' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Analyze ticket' })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: /Internal/ })).toHaveCount(0);
});
