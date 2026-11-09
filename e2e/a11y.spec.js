const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

// List rows that act on click must also act from the keyboard: focusable, button role, Enter/Space.
test('a room row activates from the keyboard', async ({ page, browser }) => {
  await page.goto(BASE + '/?lang=en');
  const room = 'e2e-kbd-' + Date.now();
  const guest = await browser.newPage();
  await guest.goto(BASE + '/?room=' + room);

  const row = page.locator('#rooms-rows li', { hasText: room });
  await expect(row).toBeVisible({ timeout: 10_000 });
  await expect(row).toHaveAttribute('role', 'button');
  await row.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp('room=' + room));
  await guest.close();
});

// The notification prompt belongs to a deliberate game gesture, not any click on the page.
test('notification permission is asked only on a board gesture', async ({ page }) => {
  await page.addInitScript(() => {
    window.__permAsked = 0;
    if (window.Notification) {
      window.Notification.requestPermission = () => { window.__permAsked++; return Promise.resolve('denied'); };
    }
  });
  await page.goto(BASE + '/?room=e2e-perm-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });

  await page.locator('#chat-input').click();
  expect(await page.evaluate(() => window.__permAsked)).toBe(0);

  await page.locator('#board .cell').nth(4).click();
  expect(await page.evaluate(() => window.__permAsked)).toBe(1);
});