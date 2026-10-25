// Captures docs screenshots against the running local stack (frontend :5173 -> gateway :8080). Dummy seed data only.
import { chromium } from '@playwright/test';
const base = 'http://localhost:5173', out = '../docs/screenshots';
const browser = await chromium.launch();
async function ctx(tenant, email) {
  const page = await (await browser.newContext({ viewport: { width: 1360, height: 900 } })).newPage();
  await page.goto(base + '/login');
  await page.getByLabel(/Organization/).fill(tenant);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('Passw0rd!demo');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('navigation', { name: 'Main' }).waitFor();
  return page;
}
let p = await ctx('acme-retail', 'manager@acme.test');
await p.goto(base + '/dashboard');
await p.getByText('Tenant activity (last 7 days)').waitFor();
await p.waitForTimeout(800);
await p.screenshot({ path: `${out}/dashboard.png`, fullPage: true });

p = await ctx('acme-retail', 'agent1@acme.test');
await p.goto(base + '/tickets/new');
await p.getByLabel(/^Title/).fill('Cannot reset my password for the customer portal');
await p.getByLabel(/^Description/).fill('Customer says the password reset email never arrives and the account looks locked.');
await p.getByRole('button', { name: 'Create ticket' }).click();
await p.getByRole('button', { name: 'Analyze ticket' }).click();
await p.getByTestId('ai-result').waitFor();
await p.waitForTimeout(500);
await p.screenshot({ path: `${out}/ticket-detail-ai-panel.png`, fullPage: true });

p = await ctx('acme-retail', 'admin@acme.test');
await p.goto(base + '/admin/workflows');
await p.getByRole('form', { name: 'Workflow editor' }).waitFor();
await p.waitForTimeout(800);
await p.screenshot({ path: `${out}/admin-workflows.png`, fullPage: true });
await browser.close();
