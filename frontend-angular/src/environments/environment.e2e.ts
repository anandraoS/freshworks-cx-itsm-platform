/** Used by `npm run start:e2e`: talks to core-service directly on :8085 (no gateway) for integration checks. */
export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8085',
  showDemoHints: true,
};
