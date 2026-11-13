const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: 'e2e',
  workers: 1,
  timeout: 30_000,
  // Shared CI runners occasionally start a bot room in a transient state (the
  // failure moves between bot tests from run to run). Retry the whole test with a
  // fresh context/room; a deterministic failure still fails after three attempts.
  retries: process.env.CI ? 2 : 0,
  use: {
    baseURL: 'http://localhost:8080',
  },
  webServer: {
    // the fat jar is a direct child process, so the runner can stop it cleanly
    command: './gradlew fatJar jsBrowserDistribution && java -jar build/libs/*-all.jar',
    url: 'http://localhost:8080/health',
    reuseExistingServer: !process.env.CI,
    timeout: 300_000,
  },
});
