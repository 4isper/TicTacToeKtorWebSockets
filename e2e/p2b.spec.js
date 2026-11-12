const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const room = (tag) => 'e2e-p2b-' + tag + '-' + Date.now();

test('wide screens put the panels beside the board', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  await page.goto(BASE + '/?room=' + room('cols') + '&lang=en');
  await expect(page.locator('#board .cell').first()).toBeVisible({ timeout: 10_000 });
  const m = await page.evaluate(() => {
    const board = document.querySelector('#board').getBoundingClientRect();
    const chat = document.querySelector('#chat').getBoundingClientRect();
    return {
      boardRight: board.right,
      chatX: chat.x,
      roomsX: document.querySelector('#rooms').getBoundingClientRect().x,
      leaderX: document.querySelector('#leaderboard').getBoundingClientRect().x,
    };
  });
  expect(m.chatX).toBeGreaterThan(m.boardRight);
  expect(m.roomsX).toBe(m.leaderX);
  await ctx.close();
});

test('narrow screens stack the panels without horizontal overflow', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  await page.goto(BASE + '/?room=' + room('stack') + '&lang=en');
  await expect(page.locator('#board .cell').first()).toBeVisible({ timeout: 10_000 });
  const m = await page.evaluate(() => ({
    innerW: innerWidth,
    docW: document.documentElement.scrollWidth,
    chatY: document.querySelector('#chat').getBoundingClientRect().y,
    boardBottom: document.querySelector('#board').getBoundingClientRect().bottom,
  }));
  expect(m.docW).toBeLessThanOrEqual(m.innerW);
  expect(m.chatY).toBeGreaterThan(m.boardBottom);
  await ctx.close();
});

test('the board keeps its desktop size in the new layout', async ({ page }) => {
  await page.goto(BASE + '/?room=' + room('size') + '&lang=en');
  await expect(page.locator('#board .cell').first()).toBeVisible({ timeout: 10_000 });
  const w = await page.locator('#board .cell').first().evaluate((e) => e.getBoundingClientRect().width);
  expect(w).toBe(96);
});

test('the language switch keeps the room and follows the page', async ({ page }) => {
  const name = room('lang');
  await page.goto(BASE + '/?room=' + name + '&lang=ru');
  const ru = page.locator('.lang-switch a', { hasText: 'RU' });
  const en = page.locator('.lang-switch a', { hasText: 'EN' });
  await expect(en).toHaveAttribute('href', /lang=en/);
  expect(await en.getAttribute('href')).toContain('room=' + name);
  await expect(ru).toHaveAttribute('aria-current', 'page');
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', /lang=ru/);

  await en.click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.locator('#quick-play')).toHaveText('Quick play');
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', /lang=en/);
});
