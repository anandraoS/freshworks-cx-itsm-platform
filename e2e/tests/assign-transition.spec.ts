import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('agent assigns a ticket and moves it NEW to OPEN with a reason', async ({ page }) => {
  const title = `E2E assign ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await createTicket(page, title);

  await page.getByLabel('Assignee').selectOption({ index: 1 });
  await page.getByRole('button', { name: 'Save assignment' }).click();

  await page.getByRole('group', { name: 'Change status' }).getByRole('button', { name: 'Open' }).click();
  await page.getByLabel(/^Reason/).fill('Picking this up');
  await page.getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByRole('group', { name: 'Change status' }).getByRole('button', { name: 'Pending' })).toBeVisible();
});

test('internal notes are shown in a clearly separate tab', async ({ page }) => {
  const title = `E2E notes ${Date.now()}`;
  await login(page, 'acme-retail', 'agent1@acme.test');
  await createTicket(page, title);
  await page.getByRole('tab', { name: /Internal/ }).click();
  await page.getByRole('textbox', { name: 'Add internal note' }).fill('Internal only: checked logs');
  await page.getByRole('button', { name: 'Add internal note' }).click();
  await expect(page.getByText('Internal only: checked logs')).toBeVisible();
  await page.getByRole('tab', { name: /Public/ }).click();
  await expect(page.getByText('Internal only: checked logs')).toHaveCount(0);
});
