import { test, expect } from '@playwright/test';
import { login } from './helpers';

test('manager dashboard renders real, non-empty data', async ({ page }) => {
  await login(page, 'acme-retail', 'manager@acme.test');
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Dashboard' }).click();
  await expect(page.getByRole('heading', { name: 'Organization dashboard' })).toBeVisible();
  const open = page.locator('.kpi-row').getByText('Open tickets').locator('..');
  await expect(open).toContainText(/\d+/);
  await expect(page.locator('.kpi-row')).not.toContainText(/NaN|undefined/);
  const m = (await open.innerText()).match(/\d+/);
  expect(Number(m![0])).toBeGreaterThan(0);
  await expect(page.getByText('Tenant activity (last 7 days)')).toBeVisible();
  await expect(page.locator('svg').first()).toBeVisible();
});

test('admin can open the workflow page and see the seeded workflow version', async ({ page }) => {
  await login(page, 'acme-retail', 'admin@acme.test');
  await page.goto('/admin/workflows');
  await expect(page.getByRole('heading', { name: /Workflow/i }).first()).toBeVisible();
  await expect(page.getByRole('form', { name: 'Workflow editor' })).toBeVisible();
  await expect(page.getByRole('button', { name: '1. Validate' })).toBeVisible();
});
