import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  workers: 1,
  timeout: 60000,
  use: {
    actionTimeout: 10000,
    baseURL: process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080',
    channel: process.env.PLAYWRIGHT_CHANNEL,
    screenshot: 'only-on-failure'
  }
})
