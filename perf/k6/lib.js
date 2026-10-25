// Shared helpers for the k6 scripts. NOT EXECUTED in the authoring environment (k6 is not installed there).
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'; // gateway; use http://localhost:8081 to hit core directly
export const TENANT = __ENV.TENANT || 'acme-retail';
// Local seed users from docs/API-CONTRACT.md (dummy demo data). Override via env for other setups.
export const PASSWORD = __ENV.PASSWORD || 'Passw0rd!demo';
export const AGENT_EMAIL = __ENV.AGENT_EMAIL || 'agent1@acme.test';
export const MANAGER_EMAIL = __ENV.MANAGER_EMAIL || 'manager@acme.test';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function login(email) {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ tenantSlug: TENANT, email, password: PASSWORD }),
    { headers: JSON_HEADERS, tags: { name: 'login' } },
  );
  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'login has accessToken': (r) => !!r.json('accessToken'),
  });
  if (!ok) fail(`login failed for ${email}: HTTP ${res.status}`);
  return res.json('accessToken');
}

export function authHeaders(token, extra = {}) {
  return { headers: Object.assign({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, extra) };
}

export function uuid() {
  // good enough for idempotency keys in a load test
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
