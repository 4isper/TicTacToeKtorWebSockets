const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

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

test('room details read in the page language', async ({ page, browser }) => {
  await page.goto(BASE + '/?lang=ru');
  const room = 'e2e-i18n-' + Date.now();
  const guest = await browser.newPage();
  await guest.goto(BASE + '/?room=' + room + '&size=5&win=4&bot=easy');

  const row = page.locator('#rooms-rows li', { hasText: room });
  await expect(row).toContainText('линия 4', { timeout: 10_000 });
  await expect(row).toContainText('бот: лёгкий');
  await guest.close();
});