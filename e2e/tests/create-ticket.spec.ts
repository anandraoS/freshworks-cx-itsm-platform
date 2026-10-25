import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('shows client-side validation before calling the API', async ({ page }) => {
  await login(page, 'acme-retail', 'agent1@acme.test');
  await page.goto('/tickets/new');
  await page.getByRole('button', { name: 'Create ticket' }).click();
  await expect(page.getByText(/at least 5 characters/)).toBeVisible();
});

test('creates a ticket and finds it in the list via search', async ({ page }) => {
  const title = `E2E printer jam ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await createTicket(page, title);
  await page.goto(`/tickets?q=${encodeURIComponent(title)}`);
  await expect(page.getByRole('link', { name: title })).toBeVisible();
});
