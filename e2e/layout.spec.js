const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

test.use({ colorScheme: 'dark', viewport: { width: 1280, height: 800 } });

test('the board has presence on a desktop screen', async ({ page }) => {
  await page.goto(BASE + '/?room=e2e-layout-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell')).toHaveCount(9);
  const w = await page.locator('#board .cell').first().evaluate(el => el.getBoundingClientRect().width);
  expect(w).toBe(96);
});

test('action buttons share one row', async ({ page, browser }) => {
  const room = 'e2e-row-' + Date.now();
  await page.goto(BASE + '/?room=' + room + '&lang=en');
  const guest = await browser.newPage();
  await guest.goto(BASE + '/?room=' + room + '&lang=en');
  await expect(page.locator('#draw')).toBeVisible();
  await expect(page.locator('#resign')).toBeVisible();
  const drawY = await page.locator('#draw').evaluate(el => el.getBoundingClientRect().y);
  const resignY = await page.locator('#resign').evaluate(el => el.getBoundingClientRect().y);
  expect(drawY).toBe(resignY);
  await guest.close();
});

test('section headings align on one left edge', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const xs = await page.evaluate(() =>
    ['create', 'chat', 'rooms', 'leaderboard'].map(id => document.querySelector('#' + id + ' h2').getBoundingClientRect().x),
  );
  for (const x of xs) expect(x).toBe(xs[0]);
});

test('the shared replay hides controls that need a connection', async ({ page }) => {
  const frag = 'r=' + Buffer.from(JSON.stringify({ s: 3, w: 3, m: [[0, 0], [1, 0], [0, 1]] })).toString('base64url');
  await page.goto(BASE + '/?lang=en#' + frag);
  await expect(page.locator('#shared-replay')).toBeVisible();
  await expect(page.locator('#chat')).toBeHidden();
  await expect(page.locator('#name')).toBeHidden();
  const mini = page.locator('.mini-cell').first();
  expect(await mini.evaluate(el => getComputedStyle(el).borderRadius)).toBe('4px');
  expect(await mini.evaluate(el => getComputedStyle(el.parentElement).gap)).toBe('2px');
});

test('scroll areas wear the palette scrollbar', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const sw = await page.locator('#chat-log').evaluate(el => getComputedStyle(el).scrollbarWidth);
  expect(sw).toBe('thin');
});