import { test, expect } from '@playwright/test';
import { login } from './helpers';

test('agent creates a draft article, publishes it, and the customer then sees it', async ({ browser }) => {
  const title = `E2E KB reset guide ${Date.now()}`;
  const agent = await (await browser.newContext()).newPage();
  await login(agent, 'acme-retail', 'agent1@acme.test');
  await agent.goto('/kb/new');
  await agent.getByLabel(/^Title/).fill(title);
  await agent.getByLabel('Summary').fill('How to reset a password (dummy e2e article).');
  await agent.getByLabel(/^Body/).fill('Step 1: open the portal. Step 2: choose reset. Dummy content.');
  await agent.getByRole('button', { name: 'Save as draft' }).click();
  await expect(agent.getByRole('heading', { name: title })).toBeVisible();
  await expect(agent.getByText('DRAFT').first()).toBeVisible();

  // a draft must not be visible to a customer
  const customer = await (await browser.newContext()).newPage();
  await login(customer, 'acme-retail', 'customer1@acme.test');
  await customer.goto(`/kb?q=${encodeURIComponent(title)}`);
  await expect(customer.getByRole('heading', { name: 'Help articles' })).toBeVisible();
  await expect(customer.locator('.article-list li').first()).toBeVisible();
  await expect(customer.getByRole('list').getByRole('link', { name: title })).toHaveCount(0);

  await agent.getByRole('button', { name: 'Publish' }).click();
  await expect(agent.getByText('PUBLISHED').first()).toBeVisible();

  await customer.goto(`/kb?q=${encodeURIComponent(title)}`);
  await expect(customer.getByRole('link', { name: title })).toBeVisible();
  await customer.getByRole('link', { name: title }).click();
  await expect(customer.getByRole('heading', { name: title })).toBeVisible();
  await expect(customer.getByText('Step 2: choose reset')).toBeVisible();
});
