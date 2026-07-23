import { defineConfig, devices } from "@playwright/test";

const webPort = process.env.OPSMIND_E2E_PRODUCTION_WEB_PORT ?? "3001";

export default defineConfig({
  testDir: "./tests/e2e-production",
  outputDir: "../../artifacts/verification/phase-07/operator-browser-production",
  fullyParallel: false,
  forbidOnly: true,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["line"]],
  timeout: 30_000,
  expect: {
    timeout: 7_500,
  },
  use: {
    ...devices["Desktop Chrome"],
    baseURL: `http://127.0.0.1:${webPort}`,
    browserName: "chromium",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  webServer: {
    command: "node tests/support/start-production-smoke-stack.mjs",
    url: `http://127.0.0.1:${webPort}/api/health`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
