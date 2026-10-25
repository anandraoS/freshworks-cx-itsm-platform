/** Default (production build) configuration. Override per build via angular.json fileReplacements. */
export const environment = {
  production: true,
  /** Gateway origin (contract base). The app appends /api/v1. */
  apiBaseUrl: 'http://localhost:8080',
  /** Show the seed-login hints panel on the login page (always on in development). */
  showDemoHints: false,
};
