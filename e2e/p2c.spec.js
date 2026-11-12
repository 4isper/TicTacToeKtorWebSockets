const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const room = (tag) => 'e2e-p2c-' + tag + '-' + Date.now();
const uniqueName = () => 'T' + Date.now().toString().slice(-8);

async function twoPlayers(browser, query) {
  const first = await browser.newPage();
  const second = await browser.newPage();
  await first.goto(BASE + query);
  await first.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  await second.goto(BASE + query);
  await second.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  return [first, second];
}

test('a win line longer than the board is refused before navigation', async ({ page }) => {
  await page.goto(BASE + '/?room=' + room('win') + '&lang=en');
  const before = page.url();
  await page.locator('#create input[name="size"]').fill('3');
  await page.locator('#create input[name="win"]').fill('4');
  await page.locator('#create button').click();
  await expect(page.locator('#error')).toContainText('cannot exceed');
  expect(page.url()).toBe(before);
});

test('advanced settings are folded away but still submitted', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const details = page.locator('#create details');
  await expect(details).toHaveCount(1);
  await expect(page.locator('#create input[name="turn"]')).toBeHidden();
  await details.locator('summary').click();
  await expect(page.locator('#create input[name="turn"]')).toBeVisible();
  await expect(page.locator('#create form')).toHaveAttribute('action', '/');
});

test('submitting the create form from a live game asks before leaving', async ({ browser }) => {
  const [first, second] = await twoPlayers(browser, '/?room=' + room('leave') + '&lang=en');
  await expect(first.locator('#draw')).toBeVisible({ timeout: 10_000 });
  const dialogs = [];
  first.on('dialog', async (d) => { dialogs.push(d.message()); await d.dismiss(); });
  const before = first.url();
  await first.locator('#create button').click();
  expect(dialogs).toHaveLength(1);
  expect(dialogs[0]).toContain('Leave');
  expect(first.url()).toBe(before);
  await first.close();
  await second.close();
});

test('a scrollable board announces that it scrolls', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 667 }, isMobile: true, hasTouch: true, serviceWorkers: 'block' });
  const big = await ctx.newPage();
  await big.goto(BASE + '/?room=' + room('big') + '&size=10&win=5&lang=en');
  await expect(big.locator('#board .cell')).toHaveCount(100);
  await expect(big.locator('#board-hint')).toBeVisible();
  await expect(big.locator('#board-hint')).toBeInViewport();
  await expect(big.locator('#board-hint')).toContainText(/scroll/i);
  await ctx.close();

  const small = await browser.newPage();
  await small.goto(BASE + '/?room=' + room('small') + '&lang=en');
  await expect(small.locator('#board .cell')).toHaveCount(9);
  await expect(small.locator('#board-hint')).toBeHidden();
  await small.close();
});

test('cells carry algebraic coordinates', async ({ page }) => {
  await page.goto(BASE + '/?room=' + room('coords') + '&lang=en');
  await expect(page.locator('#board .cell').first()).toHaveAttribute('title', 'a1');
  await expect(page.locator('#board .cell').last()).toHaveAttribute('title', 'c3');
});

test('chat shows a counter, an Enter hint and highlights my own lines', async ({ page }) => {
  const claimed = uniqueName();
  await page.goto(BASE + '/?room=' + room('chat') + '&lang=en');
  await expect(page.locator('#chat-hint')).toContainText('Enter');
  await page.locator('#chat-input').fill('hello');
  await expect(page.locator('#chat-count')).toHaveText('5/200');

  await page.locator('#name').fill(claimed);
  await page.locator('#set-name').click();
  await expect(page.locator('#identity')).toContainText(claimed, { timeout: 8_000 });
  await page.locator('#chat-send').click();
  await expect(page.locator('#chat-log .mine')).toContainText(claimed);
});
