const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const PANEL = 'rgb(31, 31, 51)'; // --panel in the dark palette

test.use({ colorScheme: 'dark' });

test('secondary controls wear the theme, not the browser default', async ({ page }) => {
  await page.goto(BASE + '/?room=e2e-style-' + Date.now() + '&bot=easy&lang=en');
  const bgs = await page.evaluate(() => Object.fromEntries(
    ['sound', 'theme', 'chat-send', 'chat-input', 'set-name'].map(id => [id, getComputedStyle(document.getElementById(id)).backgroundColor]),
  ));
  for (const [id, bg] of Object.entries(bgs)) {
    expect(bg, id + ' background').toBe(PANEL);
  }
});

test('replay controls wear the theme too', async ({ page }) => {
  const frag = 'r=' + Buffer.from(JSON.stringify({ s: 3, w: 3, m: [[0, 0], [1, 0], [0, 1]] })).toString('base64url');
  await page.goto(BASE + '/?lang=en#' + frag);
  const buttons = page.locator('#shared-replay button');
  await expect(buttons.first()).toBeVisible();
  const count = await buttons.count();
  for (let i = 0; i < count; i++) {
    const bg = await buttons.nth(i).evaluate(el => getComputedStyle(el).backgroundColor);
    expect(bg).toBe(PANEL);
  }
});

// No game state ever arrives in the shared-replay view: action buttons that
// only a live state can enable must stay hidden there (and before first state).
test('the undo button stays hidden while no game state calls for it', async ({ page }) => {
  const frag = 'r=' + Buffer.from(JSON.stringify({ s: 3, w: 3, m: [[0, 0], [1, 0], [0, 1]] })).toString('base64url');
  await page.goto(BASE + '/?lang=en#' + frag);
  await expect(page.locator('#shared-replay')).toBeVisible();
  await expect(page.locator('#undo')).toBeHidden();
});