// k6 run perf/k6/login.js            (env: BASE_URL, TENANT, AGENT_EMAIL, PASSWORD)
// Note: login is rate limited (core LoginRateLimiter + gateway). Expect 429s above the configured rate; this script
// keeps the load modest and treats 429 as a measured outcome rather than a crash.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, TENANT, PASSWORD, AGENT_EMAIL } from './lib.js';

const limited = new Rate('login_rate_limited');

export const options = {
  scenarios: {
    logins: { executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: '1m', preAllocatedVUs: 10, maxVUs: 20 },
  },
  thresholds: {
    'http_req_failed{name:login}': ['rate<0.05'],
    'http_req_duration{name:login}': ['p(95)<800', 'p(99)<1500'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ tenantSlug: TENANT, email: AGENT_EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' }, responseCallback: http.expectedStatuses(200, 429) },
  );
  limited.add(res.status === 429);
  check(res, { 'login 200 or 429': (r) => r.status === 200 || r.status === 429 });
  sleep(0.1);
}
