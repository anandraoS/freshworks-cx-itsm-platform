import { test, expect } from '@playwright/test';
import { createTicket, login } from './helpers';

test('a ticket created in one tenant is not reachable from another tenant', async ({ browser }) => {
  const title = `E2E isolation ${Date.now()}`;
  const a = await (await browser.newContext()).newPage();
  await login(a, 'acme-retail', 'agent1@acme.test');
  await createTicket(a, title);
  const path = new URL(a.url()).pathname;

  const b = await (await browser.newContext()).newPage();
  await login(b, 'globex-it', 'agent1@globex.test');
  await b.goto(path); // same id, other tenant: backend answers 404
  await expect(b.getByText('Ticket not found')).toBeVisible();
  await b.goto(`/tickets?q=${encodeURIComponent(title)}`);
  await expect(b.getByRole('link', { name: title })).toHaveCount(0);
  await expect(b.locator('header').first()).toContainText(/globex/i);
});
