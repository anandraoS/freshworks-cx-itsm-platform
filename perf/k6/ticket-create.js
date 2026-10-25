// k6 run perf/k6/ticket-create.js
// Creates tickets with a unique Idempotency-Key each, then replays one key to verify idempotent replay under load.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, AGENT_EMAIL, login, authHeaders, uuid } from './lib.js';

export const options = {
  stages: [
    { duration: '15s', target: 5 },
    { duration: '45s', target: 5 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed{name:ticket_create}': ['rate<0.01'],
    'http_req_duration{name:ticket_create}': ['p(95)<600', 'p(99)<1200'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const token = login(AGENT_EMAIL);
  const cats = http.get(`${BASE_URL}/api/v1/ticket-categories`, authHeaders(token));
  const categoryId = cats.status === 200 && cats.json().length > 0 ? cats.json()[0].id : null;
  return { token, categoryId };
}

export default function (data) {
  const key = uuid();
  const body = JSON.stringify({
    type: 'SUPPORT',
    title: `k6 load ticket ${key.slice(0, 8)}`,
    description: 'Synthetic ticket created by the k6 load test (safe to delete).',
    priority: 'MEDIUM',
    severity: 'MINOR',
    categoryId: data.categoryId,
    tags: ['k6'],
    attachments: [],
  });
  const params = Object.assign(authHeaders(data.token, { 'Idempotency-Key': key }), { tags: { name: 'ticket_create' } });
  const res = http.post(`${BASE_URL}/api/v1/tickets`, body, params);
  check(res, { 'create 201': (r) => r.status === 201, 'has id': (r) => !!r.json('id') });

  if (__ITER % 10 === 0 && res.status === 201) {
    const replay = http.post(`${BASE_URL}/api/v1/tickets`, body, Object.assign(authHeaders(data.token, { 'Idempotency-Key': key }), { tags: { name: 'ticket_replay' } }));
    check(replay, { 'replay returns same id': (r) => r.json('id') === res.json('id') });
  }
  sleep(0.3);
}
