// k6 run perf/k6/ticket-list.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, AGENT_EMAIL, login, authHeaders } from './lib.js';

export const options = {
  stages: [
    { duration: '20s', target: 10 },
    { duration: '1m', target: 10 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'http_req_failed{name:ticket_list}': ['rate<0.01'],
    'http_req_duration{name:ticket_list}': ['p(95)<400', 'p(99)<800'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return { token: login(AGENT_EMAIL) };
}

export default function (data) {
  const q = ['', '&status=OPEN', '&priority=HIGH', '&sort=createdAt,desc'][Math.floor(Math.random() * 4)];
  const res = http.get(`${BASE_URL}/api/v1/tickets?page=0&size=20${q}`, Object.assign(authHeaders(data.token), { tags: { name: 'ticket_list' } }));
  check(res, {
    'list 200': (r) => r.status === 200,
    'has content[]': (r) => Array.isArray(r.json('content')),
  });
  sleep(0.2);
}
