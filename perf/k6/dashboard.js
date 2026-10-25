// k6 run perf/k6/dashboard.js
// Analytics dashboard is cache-aside (TTL 60s, tenant-keyed): most calls should be cache hits, so latency should be low.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, MANAGER_EMAIL, login, authHeaders } from './lib.js';

export const options = {
  stages: [
    { duration: '15s', target: 10 },
    { duration: '1m', target: 10 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed{name:dashboard}': ['rate<0.01'],
    'http_req_duration{name:dashboard}': ['p(95)<300', 'p(99)<700'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return { token: login(MANAGER_EMAIL) };
}

export default function (data) {
  const res = http.get(`${BASE_URL}/api/v1/analytics/dashboard?days=30`, Object.assign(authHeaders(data.token), { tags: { name: 'dashboard' } }));
  check(res, {
    'dashboard 200': (r) => r.status === 200,
    'has openTickets': (r) => r.json('openTickets') !== undefined,
  });
  sleep(0.2);
}
