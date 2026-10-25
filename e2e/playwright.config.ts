import { defineConfig, devices } from '@playwright/test';

// These specs need the backend running (gateway :8080 -> core :8081, `local` profile with seed data).
// The webServer below only starts the Vite dev server for the frontend.
export default defineConfig({
  testDir: './tests',
  timeout: 60_000, // first tests pay the Vite dev-server cold compile (observed 25-35 s once)
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: 'http://localhost:5173', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host localhost --port 5173 --strictPort',
    cwd: '../frontend',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
