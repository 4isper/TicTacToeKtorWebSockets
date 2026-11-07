const { test, expect } = require('@playwright/test');

// CSS locators keep the tests independent of the UI language; cells carry X/O text.
const cell = (page, x, y) => page.locator('#board .cell').nth(y * 3 + x);

test('two players share one board, the win scores and the round resets', async ({ page, browser }) => {
  const room = 'e2e-win-' + Date.now();
  await page.goto('/?room=' + room);
  const opponent = await browser.newPage();
  await opponent.goto('/?room=' + room);

  await cell(page, 0, 0).click();
  await expect(cell(opponent, 0, 0)).toHaveText('X');
  await cell(opponent, 1, 0).click();
  await expect(cell(page, 1, 0)).toHaveText('O');
  await cell(page, 0, 1).click();
  await cell(opponent, 1, 1).click();
  await cell(page, 0, 2).click();

  await expect(page.locator('#score')).toContainText('X 1 : 0 O');
  await expect(cell(opponent, 0, 2)).toHaveText('X');
  // the round restarts automatically after five seconds
  await expect(cell(page, 0, 0)).toHaveText('', { timeout: 8_000 });
  await opponent.close();
});

test('a reload inside the grace keeps the seat and the turn', async ({ page, browser }) => {
  const room = 'e2e-reload-' + Date.now();
  await page.goto('/?room=' + room);
  const opponent = await browser.newPage();
  await opponent.goto('/?room=' + room);

  await cell(page, 0, 0).click();
  await expect(cell(opponent, 0, 0)).toHaveText('X');
  await cell(opponent, 1, 0).click();
  await expect(cell(page, 1, 0)).toHaveText('O');

  await page.reload();
  await expect(cell(page, 0, 0)).toHaveText('X');
  await cell(page, 0, 1).click(); // still X and still my turn after the reconnect
  await expect(cell(opponent, 0, 1)).toHaveText('X');
  await opponent.close();
});

test('a practice bot answers the opening move', async ({ page }) => {
  await page.goto('/?room=e2e-bot-' + Date.now() + '&bot=easy');
  await cell(page, 0, 0).click();
  // the bot thinks for about 0.4s before it searches
  await expect(page.locator('#board .cell').filter({ hasText: 'O' })).toHaveCount(1, { timeout: 5_000 });
});
