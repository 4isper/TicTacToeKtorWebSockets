const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const cell = (page, x, y) => page.locator('#board .cell').nth(y * 3 + x);
const room = (tag) => 'e2e-p0-' + tag + '-' + Date.now();

// Two real players need separate profiles: the reconnect token lives in localStorage.
async function twoPlayers(browser, query) {
  const first = await browser.newPage();
  const second = await browser.newPage();
  await first.goto(BASE + query);
  await first.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  await second.goto(BASE + query);
  await second.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  return [first, second];
}

test('a player sees their side and the rules of the round', async ({ page }) => {
  await page.goto(BASE + '/?room=' + room('header') + '&size=5&win=4&lang=en');
  await expect(page.locator('#identity')).toContainText('You: X', { timeout: 10_000 });
  await expect(page.locator('#rules')).toContainText('5×5');
  await expect(page.locator('#rules')).toContainText('win 4');
});

test('a third visitor is told they are a spectator', async ({ browser }) => {
  const name = room('spec');
  const first = await browser.newPage();
  const second = await browser.newPage();
  const third = await browser.newPage();
  await first.goto(BASE + '/?room=' + name + '&lang=en');
  await second.goto(BASE + '/?room=' + name + '&lang=en');
  await third.goto(BASE + '/?room=' + name + '&lang=en');
  await expect(third.locator('#identity')).toContainText('Spectator', { timeout: 10_000 });
  await expect(third.locator('#board .cell:not([disabled])')).toHaveCount(0);
  await first.close();
  await second.close();
  await third.close();
});

test('quick play opens a named room with an invite link', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  // A previous suite test may have left an anonymous opponent in the grace window,
  // which makes quick play confirm leaving a live round. Accept it: this test is
  // about the destination room, not the guard (covered by the next test).
  page.on('dialog', (d) => d.accept());
  await page.locator('#quick-play').click();
  await expect(page).toHaveURL(/[?&]room=/);
  await expect(page.locator('#copy-link')).toBeVisible();
});

test('leaving a live game asks before abandoning the round', async ({ browser }) => {
  const name = room('leave');
  const [first, second] = await twoPlayers(browser, '/?room=' + name + '&lang=en');
  await expect(first.locator('#draw')).toBeVisible({ timeout: 10_000 });

  // A native confirm blocks click(); handle it as it arrives instead of awaiting the click first.
  const dialogs = [];
  first.on('dialog', async (d) => {
    dialogs.push({ type: d.type(), message: d.message() });
    await d.dismiss();
  });
  await first.locator('#quick-play').click();

  expect(dialogs[0].type).toBe('confirm');
  expect(dialogs[0].message).toContain('Leave');
  await expect(first).toHaveURL(new RegExp('room=' + name));
  await first.close();
  await second.close();
});

test('resigning needs a confirmation and can be cancelled', async ({ browser }) => {
  const name = room('resign');
  const [first, second] = await twoPlayers(browser, '/?room=' + name + '&lang=en');
  await expect(first.locator('#resign')).toBeVisible({ timeout: 10_000 });

  const dialogs = [];
  first.on('dialog', async (d) => {
    dialogs.push({ type: d.type(), message: d.message() });
    await d.dismiss();
  });
  await first.locator('#resign').click();

  expect(dialogs[0].type).toBe('confirm');
  expect(dialogs[0].message).toContain('Resign');
  await expect(first.locator('#score')).toContainText('X 0 : 0 O');

  first.removeAllListeners('dialog');
  first.on('dialog', (d) => d.accept());
  await first.locator('#resign').click();
  await expect(first.locator('#status')).toContainText('won', { timeout: 8_000 });

  await first.close();
  await second.close();
});

test('a finished round leaves no clickable cells', async ({ browser }) => {
  const name = room('win');
  const [first, second] = await twoPlayers(browser, '/?room=' + name + '&lang=en&turn=120');
  await cell(first, 0, 0).click();
  await expect(cell(second, 0, 0)).toHaveText('X');
  await cell(second, 1, 0).click();
  await cell(first, 0, 1).click();
  await cell(second, 1, 1).click();
  await cell(first, 0, 2).click();

  await expect(first.locator('#status')).toContainText('won', { timeout: 5_000 });
  await expect(first.locator('#board .cell:not([disabled])')).toHaveCount(0);
  await expect(second.locator('#board .cell:not([disabled])')).toHaveCount(0);
  await first.close();
  await second.close();
});
