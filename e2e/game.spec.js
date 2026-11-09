const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

// A refused action answers with an error; the next game state means the world
// moved on, so the error must not hang around for the rest of the round.
test('a transient error clears when the next game state arrives', async ({ page }) => {
  await page.goto(BASE + '/?room=e2e-err-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });

  // an overlong chat line: maxLength only guards typing, the server refuses it
  await page.evaluate(() => { document.getElementById('chat-input').value = 'x'.repeat(201); });
  await page.locator('#chat-send').click();
  await expect(page.locator('#error')).toHaveText('Empty or overly long message');

  await page.locator('#board .cell').nth(4).click();
  await expect(page.locator('#error')).toHaveText('', { timeout: 5_000 });
});