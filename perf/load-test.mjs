#!/usr/bin/env node
// Dependency-free load test (Node 18+ fetch). Runs against a RUNNING core-service (or the gateway).
//
//   node perf/load-test.mjs                                   # defaults below
//   BASE_URL=http://localhost:8081 CONCURRENCY=20 DURATION_S=20 SCENARIO=ticket-list node perf/load-test.mjs
//
// Env: BASE_URL (default http://localhost:8081), TENANT (acme-retail), EMAIL (agent1@acme.test), PASSWORD (demo seed password),
//      SCENARIO (ticket-list | dashboard | ticket-create | mixed), CONCURRENCY (10), DURATION_S (15), WARMUP_S (2)
// Prints: total, success, errors by status, RPS, and latency p50/p95/p99/max (ms). Exit code 1 if any non-2xx/network error.

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
const TENANT = process.env.TENANT || 'acme-retail';
const EMAIL = process.env.EMAIL || 'agent1@acme.test';
const PASSWORD = process.env.PASSWORD || 'Passw0rd!demo'; // dummy seed password from docs/API-CONTRACT.md
const SCENARIO = process.env.SCENARIO || 'ticket-list';
const CONCURRENCY = Math.max(1, parseInt(process.env.CONCURRENCY || '10', 10));
const DURATION_S = Math.max(1, parseFloat(process.env.DURATION_S || '15'));
const WARMUP_S = Math.max(0, parseFloat(process.env.WARMUP_S || '2'));

async function login(email) {
  const res = await fetch(`${BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tenantSlug: TENANT, email, password: PASSWORD }),
  });
  if (!res.ok) throw new Error(`login failed: HTTP ${res.status}`);
  return (await res.json()).accessToken;
}

export function percentile(sortedAsc, p) {
  if (sortedAsc.length === 0) return NaN;
  const rank = Math.ceil((p / 100) * sortedAsc.length);
  return sortedAsc[Math.min(sortedAsc.length, Math.max(1, rank)) - 1];
}

function buildScenarios(token, categoryId) {
  const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const list = () => fetch(`${BASE_URL}/api/v1/tickets?page=0&size=20`, { headers: auth });
  const dashboard = () => fetch(`${BASE_URL}/api/v1/analytics/dashboard?days=30`, { headers: auth });
  const create = () =>
    fetch(`${BASE_URL}/api/v1/tickets`, {
      method: 'POST',
      headers: { ...auth, 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        type: 'SUPPORT', title: 'load-test ticket', description: 'Synthetic ticket from perf/load-test.mjs (safe to delete).',
        priority: 'MEDIUM', severity: 'MINOR', categoryId, tags: ['load-test'], attachments: [],
      }),
    });
  const mixed = () => { const r = Math.random(); return r < 0.7 ? list() : r < 0.9 ? dashboard() : create(); };
  const table = { 'ticket-list': list, dashboard, 'ticket-create': create, mixed };
  if (!table[SCENARIO]) throw new Error(`unknown SCENARIO '${SCENARIO}' (use ${Object.keys(table).join(', ')})`);
  return table[SCENARIO];
}

async function main() {
  console.log(`target=${BASE_URL} scenario=${SCENARIO} concurrency=${CONCURRENCY} duration=${DURATION_S}s warmup=${WARMUP_S}s`);
  const token = await login(EMAIL);
  let categoryId = null;
  try {
    const cats = await fetch(`${BASE_URL}/api/v1/ticket-categories`, { headers: { Authorization: `Bearer ${token}` } });
    if (cats.ok) { const arr = await cats.json(); categoryId = arr[0]?.id ?? null; }
  } catch { /* categories are optional for the non-create scenarios */ }
  const op = buildScenarios(token, categoryId);

  const latencies = [];
  const statuses = new Map();
  let errors = 0;
  const startedAt = performance.now();
  const measureFrom = startedAt + WARMUP_S * 1000;
  const endAt = measureFrom + DURATION_S * 1000;

  async function worker() {
    while (performance.now() < endAt) {
      const t0 = performance.now();
      let status = 'ERR';
      try {
        const res = await op();
        await res.arrayBuffer();               // drain the body so the connection is reused
        status = res.status;
      } catch { /* network error counted below */ }
      const t1 = performance.now();
      if (t0 >= measureFrom) {
        latencies.push(t1 - t0);
        statuses.set(status, (statuses.get(status) || 0) + 1);
        if (typeof status !== 'number' || status >= 400) errors++;
      }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const elapsed = (performance.now() - measureFrom) / 1000;

  latencies.sort((a, b) => a - b);
  const total = latencies.length;
  const f = (n) => (Number.isFinite(n) ? n.toFixed(1) : 'n/a');
  console.log('--- results (measured window, warmup excluded) ---');
  console.log(`requests: ${total}   errors: ${errors}   window: ${elapsed.toFixed(1)}s   RPS: ${(total / elapsed).toFixed(1)}`);
  console.log(`status counts: ${[...statuses.entries()].map(([k, v]) => `${k}=${v}`).join(' ')}`);
  console.log(`latency ms  p50=${f(percentile(latencies, 50))}  p95=${f(percentile(latencies, 95))}  p99=${f(percentile(latencies, 99))}  max=${f(latencies[total - 1])}`);
  if (statuses.get(429)) console.log('note: 429 responses mean a rate limiter engaged; raise limits or target core directly for raw throughput.');
  process.exitCode = errors > 0 ? 1 : 0;
}

import { fileURLToPath } from 'node:url';
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((e) => { console.error(`load test aborted: ${e.message}`); process.exit(2); });
}
