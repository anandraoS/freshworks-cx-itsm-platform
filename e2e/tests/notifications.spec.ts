import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('assigning a ticket notifies the assignee in the bell', async ({ browser }) => {
  const title = `E2E notify ${Date.now()}`;
  const a = await (await browser.newContext()).newPage();
  await login(a, 'acme-retail', 'agent1@acme.test');
  await createTicket(a, title);
  await a.getByLabel('Assignee').selectOption({ label: 'Ben Agent' });
  await a.getByRole('button', { name: 'Save assignment' }).click();
  await expect(a.getByText('Currently:')).toContainText('Ben Agent');

  const b = await (await browser.newContext()).newPage();
  await login(b, 'acme-retail', 'agent2@acme.test');
  // notifications are delivered asynchronously (outbox relay); reload until it arrives
  await expect(async () => {
    await b.reload();
    await b.getByRole('button', { name: /^Notifications/ }).click();
    await expect(b.getByRole('region', { name: 'Recent notifications' }).getByText(new RegExp(title)).first()).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 20_000 });
  await b.getByRole('region', { name: 'Recent notifications' }).getByRole('button', { name: new RegExp(title) }).first().click();
  await expect(b.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
});
