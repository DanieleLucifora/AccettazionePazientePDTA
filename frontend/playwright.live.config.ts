import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e-live',
  workers: 1,
  timeout: 90000,
  expect: { timeout: 30000 },
  use: {
    baseURL: process.env.PDTA_LIVE_URL || 'http://127.0.0.1:8081',
    headless: true,
  },
});
