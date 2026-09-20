import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  fullyParallel: false,
  use: {
    baseURL: 'http://localhost:8081',
    viewport: { width: 390, height: 844 },
    launchOptions: { ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}) },
    screenshot: 'only-on-failure',
  },
  webServer: { command: 'npm run web', url: 'http://localhost:8081', reuseExistingServer: !process.env.CI, timeout: 120_000 },
});
