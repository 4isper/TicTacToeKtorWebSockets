const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const room = (tag) => 'e2e-fb-' + tag + '-' + Date.now();
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

// One page has several error sources in different scroll regions. Anchoring the
// message to the board makes it easy to miss; a viewport-anchored alert does not.
test('errors stay visible wherever the page is scrolled and clear themselves', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  await expect(page.locator('#chat-input')).toBeVisible();

  await page.locator('#chat-input').scrollIntoViewIfNeeded();
  await page.evaluate(() => { document.getElementById('chat-input').value = 'x'.repeat(201); });
  await page.locator('#chat-send').click();
  await expect(page.locator('#error')).toHaveText('Empty or overly long message', { timeout: 5_000 });
  await expect(page.locator('#error')).toBeInViewport();
  await expect(page.locator('#error')).toHaveText('', { timeout: 8_000 });
});

test('the name field keeps the accepted name', async ({ page }) => {
  const claimed = uniqueName();
  await page.goto(BASE + '/?room=' + room('name') + '&lang=en&turn=120');
  await page.locator('#name').fill(claimed);
  await page.locator('#set-name').click();
  await expect(page.locator('#identity')).toContainText(claimed, { timeout: 8_000 });
  await expect(page.locator('#name')).toHaveValue(claimed);
});

test('a refused name stays in the field so it can be edited', async ({ browser }) => {
  const claimed = uniqueName();
  const [first, second] = await twoPlayers(browser, '/?room=' + room('taken') + '&lang=en&turn=120');
  await first.locator('#name').fill(claimed);
  await first.locator('#set-name').click();
  await expect(first.locator('#identity')).toContainText(claimed, { timeout: 8_000 });

  await second.locator('#name').fill(claimed);
  await second.locator('#set-name').click();
  await expect(second.locator('#error')).toContainText('Name already taken', { timeout: 8_000 });
  await expect(second.locator('#name')).toHaveValue(claimed);
  await first.close();
  await second.close();
});

test('opening a profile brings it into view and moves focus there', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 900, height: 700 }, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  const profile = {
    name: 'Tester', rank: 1, page: 0, pages: 1,
    stats: {
      rating: 1000, wins: 1, losses: 0, draws: 0, opponents: {},
      history: [{ epochMs: Date.now(), opponent: 'Other', result: 'WIN', moves: [{ x: 0, y: 0 }], boardSize: 3, ratingAfter: 1000 }],
    },
  };
  await page.route((url) => url.pathname === '/leaderboard', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ entries: [{ name: 'Tester', stats: profile.stats }] }),
  }));
  await page.route((url) => url.pathname === '/player', (route) => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(profile),
  }));

  await page.goto(BASE + '/?lang=en');
  const row = page.locator('#leaderboard-rows li[role=button]').first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.click();
  await expect(page.locator('#profile h3').first()).toBeVisible({ timeout: 8_000 });
  const m = await page.evaluate(() => ({
    top: document.querySelector('#profile').getBoundingClientRect().top,
    viewportH: innerHeight,
    focusInside: document.querySelector('#profile').contains(document.activeElement),
  }));
  expect(m.top).toBeLessThan(m.viewportH / 2);
  expect(m.focusInside).toBe(true);
  await ctx.close();
});
