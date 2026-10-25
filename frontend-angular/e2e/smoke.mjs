// Real-backend smoke test for the Angular UI (headless Chrome through puppeteer-core).
//
//   prerequisites (see frontend-angular/README.md):
//     1. core-service running with profile local on :8085
//     2. `npm run start:e2e` (ng serve --configuration e2e, apiBaseUrl = http://localhost:8085) on :4200
//   run:  CHROME_BIN="C:/Program Files/Google/Chrome/Application/chrome.exe" node e2e/smoke.mjs
//
// puppeteer-core is resolved from the repo's tools/render/node_modules (no new dependency is added to the app).
// Credentials are the documented dummy seed logins (docs/API-CONTRACT.md).
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(path.resolve(here, '../../tools/render/package.json'));
const puppeteer = require('puppeteer-core');

const APP = process.env.APP_URL ?? 'http://localhost:4200';
const API = process.env.API_URL ?? 'http://localhost:8085';
const CHROME = process.env.CHROME_BIN ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const TENANT = 'acme-retail';
const PASSWORD = 'Passw0rd!demo'; // dummy seed password

const results = [];
function record(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`);
}
async function step(name, fn) {
  try { const detail = await fn(); record(name, true, detail ?? ''); }
  catch (e) { record(name, false, e instanceof Error ? e.message : String(e)); throw e; }
}
function assert(cond, msg) { if (!cond) throw new Error(msg); }

const apiCalls = []; // { method, url, status, auth, corr, idem }

async function login(page, email) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle0' });
  await page.type('input[formcontrolname=tenantSlug]', TENANT);
  await page.type('input[formcontrolname=email]', email);
  await page.type('input[formcontrolname=password]', PASSWORD);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => undefined), page.click('button[type=submit]')]);
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-gpu'] });
let failed = false;
try {
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
  page.on('request', (r) => {
    if (r.url().startsWith(API) && r.method() !== 'OPTIONS') { // preflights are checked separately
      const h = r.headers();
      apiCalls.push({ method: r.method(), url: r.url().replace(API, ''), auth: !!h['authorization'], corr: h['x-correlation-id'], idem: h['idempotency-key'], status: undefined });
    }
  });
  page.on('response', (r) => {
    if (r.url().startsWith(API) && r.request().method() !== 'OPTIONS') {
      const c = [...apiCalls].reverse().find((x) => x.url === r.url().replace(API, '') && x.status === undefined);
      if (c) c.status = r.status();
    }
  });

  await step('backend reachable and healthy on :8085', async () => {
    const res = await fetch(`${API}/actuator/health`);
    assert(res.ok, `health HTTP ${res.status}`);
    return (await res.json()).status;
  });

  await step('CORS preflight allows the Angular origin with the custom headers', async () => {
    const res = await fetch(`${API}/api/v1/tickets`, {
      method: 'OPTIONS',
      headers: { Origin: 'http://localhost:4200', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,idempotency-key,x-correlation-id,content-type' },
    });
    assert(res.status === 200, `preflight HTTP ${res.status}`);
    assert(res.headers.get('access-control-allow-origin') === 'http://localhost:4200', 'allow-origin mismatch');
    return `allow-headers=${res.headers.get('access-control-allow-headers')}`;
  });

  // ---- 1. login (manager) -> dashboard
  await step('login as manager@acme.test lands on /dashboard', async () => {
    await login(page, 'manager@acme.test');
    await page.waitForSelector('h1');
    assert(page.url().endsWith('/dashboard'), `url was ${page.url()}`);
    const tenant = await page.$eval('[data-testid=tenant-name]', (e) => e.textContent?.trim());
    assert(tenant && tenant.length > 0, 'tenant chip missing');
    return `url=${page.url()} tenant="${tenant}"`;
  });

  await step('dashboard loads real analytics data (KPIs + SVG charts)', async () => {
    await page.waitForSelector('.kpi-row .kpi', { timeout: 15000 });
    const kpis = await page.$$eval('.kpi', (els) => els.map((e) => e.querySelector('.kpi-label')?.textContent + '=' + e.querySelector('.kpi-value')?.textContent));
    assert(kpis.length === 6, `expected 6 KPIs, got ${kpis.length}`);
    const charts = await page.$$eval('svg[role=img]', (els) => els.length);
    assert(charts >= 2, `expected inline-SVG charts, got ${charts}`);
    const call = apiCalls.find((c) => c.url.startsWith('/api/v1/analytics/dashboard'));
    assert(call && call.status === 200, 'analytics call missing or not 200');
    assert(call.auth && call.corr, 'bearer token / correlation id missing on the request');
    return kpis.join('; ');
  });

  await step('days selector re-queries the API with days=7', async () => {
    await page.select('#days', '7');
    await page.waitForFunction(() => true);
    await new Promise((r) => setTimeout(r, 800));
    assert(apiCalls.some((c) => c.url.includes('analytics/dashboard?days=7') && c.status === 200), 'no days=7 call');
  });

  await step('X-Correlation-Id is exposed to browser JS (CORS expose-headers)', async () => {
    const corr = await page.evaluate(async (api) => {
      const t = JSON.parse(sessionStorage.getItem('cx.ng.session.v1')).accessToken;
      const r = await fetch(`${api}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${t}`, 'X-Correlation-Id': 'smoke-corr-1' } });
      return r.headers.get('x-correlation-id');
    }, API);
    assert(corr === 'smoke-corr-1', `echoed correlation id was ${corr}`);
  });

  // ---- 2. ticket list -> open ticket
  await step('ticket list renders server-side data with URL-synced filters', async () => {
    await page.goto(`${APP}/tickets?status=OPEN&sort=title,asc`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('table.table tbody tr', { timeout: 15000 });
    const rows = await page.$$eval('table.table tbody tr', (r) => r.length);
    assert(rows > 0, 'no ticket rows');
    const statusSel = await page.$eval('#f-status', (e) => e.value);
    assert(statusSel === 'OPEN', `status filter not restored from URL (${statusSel})`);
    const call = apiCalls.filter((c) => c.url.startsWith('/api/v1/tickets?')).pop();
    assert(call && call.url.includes('status=OPEN') && /sort=title(,|%2C)asc/.test(call.url), `list call was ${call?.url}`);
    await page.select('#f-priority', 'HIGH');
    await page.waitForFunction(() => location.search.includes('priority=HIGH'));
    return `${rows} rows; url=${page.url().replace(APP, '')}`;
  });

  let openedTitle = '';
  await step('open a ticket from the list (detail, comments, activity, SLA, AI panel)', async () => {
    await page.goto(`${APP}/tickets`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('table.table tbody tr td a');
    openedTitle = await page.$eval('table.table tbody tr td a', (a) => a.textContent.trim());
    await Promise.all([page.waitForSelector('.detail-grid', { timeout: 15000 }), page.click('table.table tbody tr td a')]);
    await page.waitForSelector('[role=tablist]');
    const h1 = await page.$eval('h1', (e) => e.textContent.trim());
    assert(h1.includes(openedTitle), `h1 "${h1}" does not contain "${openedTitle}"`);
    const panels = await page.$$eval('.card-title', (e) => e.map((x) => x.textContent.trim()));
    for (const need of ['Details', 'Comments', 'Activity', 'SLA']) assert(panels.some((p) => p.startsWith(need)), `missing card ${need}`);
    assert(await page.$('.ai-panel'), 'AI panel missing for staff');
    return h1;
  });

  await step('AI analyze returns a labelled mock rule-based suggestion (never auto-sent)', async () => {
    const [btn] = await page.$$('xpath/.//button[contains(., "Analyze ticket")]');
    await btn.click();
    await page.waitForSelector('[data-testid=ai-result], [data-testid=ai-degraded]', { timeout: 20000 });
    const degraded = await page.$('[data-testid=ai-degraded]');
    const text = await page.$eval('.ai-panel', (e) => e.textContent);
    assert(text.includes('AI-generated (mock rule-based provider)'), 'AI label missing');
    return degraded ? 'degraded fallback shown' : 'result with confidence meter: ' + !!(await page.$('[role=meter]'));
  });

  // ---- 3. create ticket
  const unique = `Angular smoke ${Date.now()}`;
  await step('create ticket: validation then real POST with Idempotency-Key, redirects to the new ticket', async () => {
    await page.goto(`${APP}/tickets/new`, { waitUntil: 'networkidle0' });
    await page.click('button[type=submit]');
    await page.waitForSelector('.field-msg');
    const msgs = await page.$$eval('.field-msg', (e) => e.map((x) => x.textContent));
    assert(msgs.some((m) => m.includes('Title must be at least 5')), 'client validation message missing');
    await page.type('input[formcontrolname=title]', unique);
    await page.type('textarea[formcontrolname=description]', 'Created by the Angular smoke test against the real core-service.');
    await page.select('select[formcontrolname=priority]', 'HIGH');
    await page.type('input[formcontrolname=tags]', 'smoke, angular');
    // double click must still produce exactly one POST
    const before = apiCalls.filter((c) => c.method === 'POST' && c.url === '/api/v1/tickets').length;
    await page.$eval('button[type=submit]', (b) => { b.click(); b.click(); });
    await page.waitForFunction(() => /\/tickets\/[0-9a-f-]{36}$/.test(location.pathname), { timeout: 20000 });
    const posts = apiCalls.filter((c) => c.method === 'POST' && c.url === '/api/v1/tickets');
    assert(posts.length - before === 1, `expected 1 POST, saw ${posts.length - before}`);
    const post = posts.pop();
    assert(post.status === 201, `create status ${post.status}`);
    assert(post.idem && post.idem.length >= 16, 'Idempotency-Key header missing');
    await page.waitForSelector('h1');
    const h1 = await page.$eval('h1', (e) => e.textContent.trim());
    assert(h1.includes(unique), `new ticket h1 was "${h1}"`);
    return `${h1}; Idempotency-Key=${post.idem}`;
  });

  await step('new ticket is visible through server-side search', async () => {
    await page.goto(`${APP}/tickets?q=${encodeURIComponent(unique)}`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('table.table tbody tr a', { timeout: 15000 });
    const t = await page.$eval('table.table tbody tr a', (a) => a.textContent.trim());
    assert(t === unique, `found "${t}"`);
  });

  // ---- 3b. agent workflow on the new ticket: comments (internal vs public), assignment, transition
  const clickText = async (selector, text) => {
    const handle = await page.evaluateHandle((sel, t) => [...document.querySelectorAll(sel)].reverse().find((e) => e.textContent.trim() === t), selector, text);
    const el = handle.asElement();
    if (!el) throw new Error(`no ${selector} with text "${text}"`);
    await el.click();
  };
  await step('ticket detail: internal note stays on the Internal tab; public reply on the Public tab', async () => {
    await page.click('table.table tbody tr a');
    await page.waitForSelector('[role=tablist]');
    const tabs = await page.$$('[role=tab]');
    await tabs[1].click();
    await page.waitForSelector('.banner-internal');
    await page.type('textarea[formcontrolname=body]', 'Internal smoke note');
    await clickText('button', 'Add internal note');
    await page.waitForFunction(() => document.querySelector('.comment-list')?.textContent.includes('Internal smoke note'), { timeout: 15000 });
    await tabs[0].click();
    await page.waitForSelector('.banner-public');
    const publicText = await page.$eval('.comment-list', (e) => e.textContent);
    assert(!publicText.includes('Internal smoke note'), 'internal note leaked into the public tab');
    await page.type('textarea[formcontrolname=body]', 'Public smoke reply');
    await clickText('button', 'Post reply');
    await page.waitForFunction(() => document.querySelector('.comment-list')?.textContent.includes('Public smoke reply'), { timeout: 15000 });
    const posts = apiCalls.filter((c) => c.method === 'POST' && c.url.endsWith('/comments'));
    assert(posts.length === 2 && posts.every((c) => c.status === 201), `comment POSTs: ${posts.map((c) => c.status)}`);
  });

  await step('assign the ticket to an agent and move NEW -> OPEN with a reason (optimistic-lock version sent)', async () => {
    const options = await page.$$eval('#assignee option', (o) => o.map((x) => x.value).filter(Boolean));
    assert(options.length > 0, 'no assignable users');
    await page.select('#assignee', options[0]);
    await clickText('button', 'Save assignment');
    await page.waitForFunction(() => document.body.textContent.includes('Assignment updated'), { timeout: 15000 });
    const assign = apiCalls.filter((c) => c.url.endsWith('/assign')).pop();
    assert(assign && assign.status === 200, 'assign call');
    await clickText('button', 'Open');
    await page.waitForSelector('[role=dialog] textarea');
    await page.type('[role=dialog] textarea', 'Picking this up');
    await clickText('[role=dialog] button', 'Confirm');
    await page.waitForFunction(() => document.body.textContent.includes('Status changed to Open'), { timeout: 15000 });
    const tr = apiCalls.filter((c) => c.url.endsWith('/transition')).pop();
    assert(tr && tr.status === 200, 'transition call');
  });

  // ---- 4. guards / 401 handling
  await step('MANAGER cannot open the ADMIN-only settings page (role guard -> /forbidden)', async () => {
    await page.goto(`${APP}/admin/users`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('.status-code');
    assert(page.url().endsWith('/forbidden'), `url was ${page.url()}`);
    assert((await page.$eval('h1', (e) => e.textContent)) === 'Forbidden', 'heading');
  });

  // ---- admin + knowledge base (real API)
  await step('sign out, then ADMIN login (admin@acme.test)', async () => {
    await page.goto(`${APP}/tickets`, { waitUntil: 'networkidle0' });
    await clickText('button', 'Sign out');
    await page.waitForSelector('input[formcontrolname=tenantSlug]');
    await login(page, 'admin@acme.test');
    assert(page.url().endsWith('/dashboard'), `url was ${page.url()}`);
    const nav = await page.$$eval('.main-nav a', (a) => a.map((x) => x.textContent.trim()));
    assert(nav.includes('Administration'), `nav: ${nav}`);
  });

  for (const path of ['/admin/users', '/admin/sla', '/admin/categories', '/admin/audit']) {
    await step(`admin page ${path} loads real data`, async () => {
      await page.goto(`${APP}${path}`, { waitUntil: 'networkidle0' });
      await page.waitForSelector('table.table tbody tr', { timeout: 15000 });
      return `${await page.$$eval('table.table tbody tr', (r) => r.length)} rows`;
    });
  }

  await step('admin settings form loads and a no-op save round-trips', async () => {
    await page.goto(`${APP}/admin/settings`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('form[aria-label="Organization settings"]');
    const name = await page.$eval('input[formcontrolname=name]', (e) => e.value);
    assert(name.length > 1, 'organization name empty');
    await page.type('input[formcontrolname=emailProvider]', 'X');
    await page.$eval('input[formcontrolname=emailProvider]', (e) => { e.value = e.value.slice(0, -1); e.dispatchEvent(new Event('input')); });
    await clickText('button', 'Save settings');
    await page.waitForFunction(() => document.body.textContent.includes('Settings saved') || document.querySelector('.notice-error'), { timeout: 15000 });
    const put = apiCalls.filter((c) => c.method === 'PUT' && c.url === '/api/v1/tenant/settings').pop();
    assert(put && put.status === 200, `settings PUT status ${put?.status}`);
    return `org="${name}"`;
  });

  await step('workflow editor: validate (server) then the save button unlocks', async () => {
    await page.goto(`${APP}/admin/workflows`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('form[aria-label="Workflow editor"]');
    const saveBefore = await page.evaluate(() => [...document.querySelectorAll('button')].find((b) => b.textContent.includes('2. Save')).disabled);
    assert(saveBefore === true, 'save should be disabled before validating');
    await clickText('button', '1. Validate');
    try {
      await page.waitForSelector('.notice-success', { timeout: 15000 });
    } catch {
      const notices = await page.$$eval('.notice', (n) => n.map((x) => x.className + ': ' + x.textContent.trim()));
      throw new Error('validation did not pass; notices: ' + JSON.stringify(notices));
    }
    const saveAfter = await page.evaluate(() => [...document.querySelectorAll('button')].find((b) => b.textContent.includes('2. Save')).disabled);
    assert(saveAfter === false, 'save should unlock after a passing validation');
  });

  const articleTitle = `Angular smoke article ${Date.now()}`;
  await step('knowledge base: create + publish an article, then find it by search', async () => {
    await page.goto(`${APP}/kb/new`, { waitUntil: 'networkidle0' });
    await page.type('input[formcontrolname=title]', articleTitle);
    await page.type('input[formcontrolname=summary]', 'Smoke summary');
    await page.type('textarea[formcontrolname=body]', 'Step one: press the button. Step two: release it.');
    await clickText('button', 'Save & publish');
    await page.waitForFunction(() => /\/kb\/[0-9a-f-]{36}$/.test(location.pathname), { timeout: 20000 });
    await page.waitForSelector('article h1');
    const badges = await page.$$eval('.btn-row .badge', (b) => b.map((x) => x.textContent.trim()));
    assert(badges.includes('PUBLISHED'), `badges: ${badges}`);
    await page.goto(`${APP}/kb?q=${encodeURIComponent(articleTitle)}`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('.article-list li a', { timeout: 15000 });
    assert((await page.$eval('.article-list li a', (a) => a.textContent.trim())) === articleTitle, 'article not found by search');
  });

  await step('an invalid/expired token gets 401 from the API -> session cleared -> login with "session expired"', async () => {
    await page.evaluate(() => {
      const s = JSON.parse(sessionStorage.getItem('cx.ng.session.v1'));
      s.accessToken = 'not-a-valid.jwt.token';
      sessionStorage.setItem('cx.ng.session.v1', JSON.stringify(s));
    });
    await page.goto(`${APP}/tickets`, { waitUntil: 'networkidle0' });
    await page.waitForSelector('input[formcontrolname=tenantSlug]', { timeout: 15000 });
    assert(page.url().includes('/login'), `url was ${page.url()}`);
    const expired = await page.$('.notice-warn');
    assert(expired, 'session-expired notice missing');
    const stored = await page.evaluate(() => sessionStorage.getItem('cx.ng.session.v1'));
    assert(stored === null, 'session not cleared');
  });

  await step('customer portal: customer1@acme.test sees only the portal (no staff nav) and own tickets', async () => {
    await login(page, 'customer1@acme.test');
    await page.waitForSelector('.dash-grid', { timeout: 15000 });
    assert(page.url().endsWith('/portal'), `url was ${page.url()}`);
    const nav = await page.$$eval('.main-nav a', (a) => a.map((x) => x.textContent.trim()));
    assert(!nav.includes('Dashboard') && !nav.includes('Administration'), `staff nav leaked: ${nav}`);
    const call = apiCalls.filter((c) => c.url.startsWith('/api/v1/tickets?') && c.url.includes('mine=true')).pop();
    assert(call && call.status === 200, 'mine=true list call missing');
    return `nav=${nav.join(', ')}`;
  });

  await step('no unexpected console errors during the run', async () => {
    const real = consoleErrors.filter((e) => !/401|Unauthorized|403|Forbidden|Failed to load resource/.test(e));
    assert(real.length === 0, real.join(' | '));
    return `${consoleErrors.length} expected network-level console errors (401/403 by design)`;
  });

  console.log('\nAPI calls observed (' + apiCalls.length + '):');
  const summary = {};
  for (const c of apiCalls) { const k = `${c.method} ${c.url.split('?')[0]} -> ${c.status}`; summary[k] = (summary[k] ?? 0) + 1; }
  for (const [k, v] of Object.entries(summary)) console.log(`  ${v}x ${k}`);
} catch {
  failed = true;
} finally {
  await browser.close();
}
const bad = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - bad}/${results.length} steps passed${failed || bad ? ' (FAILED)' : ''}`);
process.exit(failed || bad ? 1 : 0);
