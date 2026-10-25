import { test, expect } from '@playwright/test';
import { login } from './helpers';

// Needs a SECOND stack whose core-service runs with -Dcx.ai.fault-mode=FAIL (test hook, refused in docker profile),
// its own gateway and a frontend pointing at it. Set E2E_FAULT_BASE_URL (e.g. http://localhost:5174). Skipped otherwise.
const faultUrl = process.env.E2E_FAULT_BASE_URL;
test.skip(!faultUrl, 'E2E_FAULT_BASE_URL not set (fault-injected stack not running)');
test.use({ baseURL: faultUrl });

test('AI provider failure shows a degraded notice and the ticket stays usable manually', async ({ page }) => {
  const title = `E2E AI degraded ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await page.goto('/tickets/new');
  await page.getByLabel(/^Title/).fill(title);
  await page.getByLabel(/^Description/).fill('Dummy description for the degraded AI e2e ticket.');
  await page.getByRole('button', { name: 'Create ticket' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
  await page.getByRole('button', { name: 'Analyze ticket' }).click();
  await expect(page.getByTestId('ai-degraded')).toBeVisible();
  await expect(page.getByRole('button', { name: /Approve & post reply/ })).toHaveCount(0);
  // manual work still possible
  await page.getByRole('tab', { name: /Internal/ }).click();
  await page.getByRole('textbox', { name: 'Add internal note' }).fill('Handled manually');
  await page.getByRole('button', { name: 'Add internal note' }).click();
  await expect(page.getByText('Handled manually')).toBeVisible();
});
