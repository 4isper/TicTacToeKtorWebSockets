const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

// A refused action answers with an error; the next game state means the world
// moved on, so the error must not hang around for the rest of the round.
test('a transient error clears when the next game state arrives', async ({ page, browser }) => {
  // Two humans, not a bot room: a bot game can end before the assertions run,
  // which used to make this check flaky in a long suite.
  const name = 'e2e-err-' + Date.now();
  await page.goto(BASE + '/?room=' + name + '&lang=en');
  const guest = await browser.newPage();
  await guest.goto(BASE + '/?room=' + name + '&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });

  // an overlong chat line: maxLength only guards typing, the server refuses it
  await page.evaluate(() => { document.getElementById('chat-input').value = 'x'.repeat(201); });
  await page.locator('#chat-send').click();
  await expect(page.locator('#error')).toHaveText('Empty or overly long message');

  await page.locator('#board .cell').nth(4).click();
  await expect(page.locator('#error')).toHaveText('', { timeout: 5_000 });
  await guest.close();
});

// The move list is the server's round record, not a client-side guess:
// a reload (or a spectator joining mid-round) must still show every move.
test('the move log survives a reload', async ({ page }) => {
  await page.goto(BASE + '/?room=e2e-log-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });
  await page.locator('#board .cell').nth(4).click();
  await expect(page.locator('#moves li')).toHaveCount(2, { timeout: 5_000 }); // my move and the bot's answer

  await page.reload();
  await expect(page.locator('#board .cell').nth(4)).toHaveText('X');
  await expect(page.locator('#moves li')).toHaveCount(2);
});

// A draw needs two humans to agree; a bot never votes, so the offer is a dead end.
test('the draw button only shows against a human', async ({ page, browser }) => {
  await page.goto(BASE + '/?room=e2e-draw-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });
  await expect(page.locator('#draw')).toBeHidden();

  const room = 'e2e-draw2-' + Date.now();
  await page.goto(BASE + '/?room=' + room + '&lang=en');
  const guest = await browser.newPage();
  await guest.goto(BASE + '/?room=' + room + '&lang=en');
  await expect(page.locator('#draw')).toBeVisible();
  await guest.close();
});