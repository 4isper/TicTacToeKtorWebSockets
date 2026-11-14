const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: 'e2e',
  workers: 1,
  timeout: 30_000,
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
