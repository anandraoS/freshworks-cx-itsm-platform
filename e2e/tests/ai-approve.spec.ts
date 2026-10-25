import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('AI suggestion is labelled, editable, and only posted after explicit approval', async ({ page }) => {
  const title = `E2E AI password reset ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await createTicket(page, title);
  await page.getByRole('button', { name: 'Analyze ticket' }).click();
  await expect(page.getByText('AI-generated (mock rule-based provider)').first()).toBeVisible();

  await page.getByLabel('Edit before approving').fill('Hello, access was reset. Edited by agent E2E.');
  await page.getByRole('button', { name: /Approve/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: /Approve|Confirm|Post/ }).click();
  await expect(page.getByRole('tabpanel').getByText(/Edited by agent E2E/)).toBeVisible();
});
