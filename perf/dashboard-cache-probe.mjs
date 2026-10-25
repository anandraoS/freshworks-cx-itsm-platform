#!/usr/bin/env node
// Single-request latency probe: analytics dashboard cache HIT vs cache MISS (Node 18+, no dependencies).
//
//   BASE_URL=http://localhost:8081 node perf/dashboard-cache-probe.mjs
//
// Env: BASE_URL (default http://localhost:8081), TENANT (acme-retail), EMAIL (manager@acme.test), PASSWORD (demo seed password),
//      HITS (30), MISSES (15).
// How it works: the dashboard is cached per tenant for 60 s and evicted after the commit of any ticket write. So
//   - HIT  = repeated GETs inside the TTL;
//   - MISS = create a ticket (evicts the cache after commit), then GET once. This LEAVES `load-test`-style synthetic tickets behind.
// The first call after login is reported separately (it includes JIT warm-up if the JVM is fresh).
// Finally it prints the `cx_cache_total{cache="analytics"}` counters from /actuator/prometheus.
// Run it against a throwaway local instance (profile local, H2); do not point it at anything shared.
const BASE = (process.env.BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
const TENANT = process.env.TENANT || 'acme-retail';
const EMAIL = process.env.EMAIL || 'manager@acme.test';
const PASSWORD = process.env.PASSWORD || 'Passw0rd!demo'; // dummy seed password from docs/API-CONTRACT.md
const HITS = parseInt(process.env.HITS || '30', 10);
const MISSES = parseInt(process.env.MISSES || '15', 10);

async function json(path, opts = {}) {
  const r = await fetch(BASE + path, opts);
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}
const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const stats = (a) => `n=${a.length} median=${median(a).toFixed(2)} min=${Math.min(...a).toFixed(2)} max=${Math.max(...a).toFixed(2)} ms`;

const login = await json('/api/v1/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tenantSlug: TENANT, email: EMAIL, password: PASSWORD }),
});
if (login.status !== 200) { console.error('login failed', login.status); process.exit(2); }
const H = { Authorization: `Bearer ${login.body.accessToken}` };
const categories = (await json('/api/v1/ticket-categories', { headers: H })).body;
const total = (await json('/api/v1/tickets?size=1', { headers: H })).body.totalElements;
console.log(`tenant=${TENANT} tickets in tenant: ${total}`);

async function timed() {
  const t0 = performance.now();
  const r = await fetch(BASE + '/api/v1/analytics/dashboard?days=30', { headers: H });
  await r.arrayBuffer();
  return [performance.now() - t0, r.status];
}
const [first, firstStatus] = await timed();
console.log(`first call after login: ${first.toFixed(1)} ms (HTTP ${firstStatus}; may be a cache miss and/or a cold JVM)`);
const hits = [];
for (let i = 0; i < HITS; i++) hits.push((await timed())[0]);
const misses = [];
for (let i = 0; i < MISSES; i++) {
  await fetch(BASE + '/api/v1/tickets', {
    method: 'POST', headers: { ...H, 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ type: 'SUPPORT', title: 'dashboard probe', description: 'synthetic ticket from perf/dashboard-cache-probe.mjs',
      priority: 'LOW', severity: 'MINOR', categoryId: categories[0]?.id ?? null, tags: [], attachments: [] }),
  });
  misses.push((await timed())[0]);
}
console.log(`HIT : ${stats(hits)}`);
console.log(`MISS: ${stats(misses)}`);
const prom = await (await fetch(BASE + '/actuator/prometheus')).text();
console.log(prom.split('\n').filter((l) => l.startsWith('cx_cache_total') && l.includes('cache="analytics"')).join('\n'));
