const { test, expect } = require('@playwright/test');

// The lobby is live data: once the service worker controls the page, its polls
// must still reach the network, or new rooms never appear in the list.
test('the room list keeps refreshing once the service worker controls the page', async ({ page, browser }) => {
  await page.goto('/?lang=en');
  await page.evaluate(() => navigator.serviceWorker.ready);
  await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller), { timeout: 10_000 }).toBe(true);
  await page.waitForTimeout(6_000); // let a /rooms poll run through the worker

  const room = 'e2e-sw-' + Date.now();
  const guest = await browser.newPage();
  await guest.goto('/?room=' + room);
  await expect(page.locator('#rooms-rows')).toContainText(room, { timeout: 12_000 });
  await guest.close();
});