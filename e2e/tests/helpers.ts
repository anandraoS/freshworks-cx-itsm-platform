import { expect, type Page } from '@playwright/test';

export const PASSWORD = 'Passw0rd!demo'; // dummy seed password, local only

export async function login(page: Page, tenant: string, email: string) {
  await page.goto('/login');
  await page.getByLabel(/Organization/).fill(tenant);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
}

export async function createTicket(page: Page, title: string) {
  await page.goto('/tickets/new');
  await page.getByLabel(/^Title/).fill(title);
  await page.getByLabel(/^Description/).fill('Dummy description for an end-to-end test ticket.');
  await page.getByRole('button', { name: 'Create ticket' }).click();
  await expect(page.getByRole('heading', { name: new RegExp(title) })).toBeVisible();
}
