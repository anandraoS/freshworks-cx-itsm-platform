import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('agent can reject an AI suggestion with a reason; nothing is posted publicly', async ({ page }) => {
  const title = `E2E AI reject ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await createTicket(page, title);
  await page.getByRole('button', { name: 'Analyze ticket' }).click();
  await expect(page.getByTestId('ai-result')).toBeVisible();
  await page.getByRole('button', { name: 'Reject', exact: true }).click();
  await page.getByLabel('Reason for rejecting').fill('Not relevant for this ticket');
  await page.getByRole('button', { name: 'Confirm reject' }).click();
  await expect(page.getByText('This suggestion was rejected.')).toBeVisible();
  await expect(page.getByRole('tabpanel').getByText(/AI-generated/)).toHaveCount(0);
});
