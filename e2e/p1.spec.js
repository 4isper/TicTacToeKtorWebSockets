const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';
const room = (tag) => 'e2e-p1-' + tag + '-' + Date.now();
const cell = (page, x, y) => page.locator('#board .cell').nth(y * 3 + x);

// Separate profiles: the reconnect token is per tab after the P1 fix.
async function twoPlayers(browser, query) {
  const first = await browser.newPage();
  const second = await browser.newPage();
  await first.goto(BASE + query);
  await first.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  await second.goto(BASE + query);
  await second.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  return [first, second];
}

test('the turn countdown does not rewrite the polite status region', async ({ browser }) => {
  const [first, second] = await twoPlayers(browser, '/?room=' + room('live') + '&lang=en&turn=120');
  await expect(first.locator('#draw')).toBeVisible({ timeout: 10_000 });

  await first.evaluate(() => {
    window.__mut = 0;
    window.__obs = new MutationObserver(() => { window.__mut += 1; });
    window.__obs.observe(document.querySelector('#status'), { childList: true, characterData: true, subtree: true });
  });
  await first.waitForTimeout(3_000);
  const m = await first.evaluate(() => {
    window.__obs.disconnect();
    return {
      mutations: window.__mut,
      timer: (document.querySelector('#timer') || {}).textContent || '',
    };
  });
  expect(m.mutations).toBeLessThanOrEqual(1);
  expect(m.timer).toMatch(/\d/);
  await first.close();
  await second.close();
});

test('keyboard focus stays usable in the grid after a move', async ({ browser }) => {
  const [first, second] = await twoPlayers(browser, '/?room=' + room('focus') + '&lang=en&turn=120');
  await expect(first.locator('#draw')).toBeVisible({ timeout: 10_000 });

  await cell(first, 1, 1).focus();
  await first.keyboard.press('Enter');
  await expect(cell(second, 1, 1)).toHaveText('X');
  // the opponent answers, so the board is interactive again for the keyboard player
  await cell(second, 0, 0).click();
  await expect(first.locator('#status')).toContainText('Your move');

  await first.keyboard.press('ArrowRight');
  const focused = await first.evaluate(() => ({
    cls: document.activeElement?.className || '',
    id: document.activeElement?.id || '',
  }));
  expect(focused.cls).toContain('cell');
  await first.close();
  await second.close();
});

test('the board is a labelled grid and the move list is named', async ({ page }) => {
  await page.goto(BASE + '/?room=' + room('grid') + '&lang=en');
  await expect(page.locator('#board .cell').first()).toBeVisible({ timeout: 10_000 });
  await expect(page.locator('#board')).toHaveAttribute('role', 'grid');
  await expect(page.locator('#board')).toHaveAttribute('tabindex', '0');
  const label = await page.locator('#board').getAttribute('aria-label');
  expect(label && label.length).toBeGreaterThan(3);
  await expect(page.locator('#moves')).toHaveAttribute('aria-label', /Move list/);
});

test('replay controls have readable accessible names', async ({ page }) => {
  const frag = 'r=' + Buffer.from(JSON.stringify({ s: 3, w: 3, m: [[0, 0], [1, 0], [0, 1]] })).toString('base64url');
  await page.goto(BASE + '/?lang=en#' + frag);
  await expect(page.locator('.replay-controls button').first()).toBeVisible();
  const labels = await page.locator('.replay-controls button').evaluateAll(
    (buttons) => buttons.map((b) => b.getAttribute('aria-label') || ''),
  );
  expect(labels.length).toBe(4);
  for (const label of labels) expect(label).toMatch(/[A-Za-z]/);
});

test('undo waits for a move and a hint waits for a bot', async ({ browser }) => {
  const [first, second] = await twoPlayers(browser, '/?room=' + room('actions') + '&lang=en');
  await expect(first.locator('#draw')).toBeVisible({ timeout: 10_000 });
  await expect(second.locator('#undo')).toBeHidden();
  await expect(first.locator('#hint')).toBeHidden();
  await first.close();
  await second.close();

  const bot = await browser.newPage();
  await bot.goto(BASE + '/?room=' + room('hint') + '&bot=easy&lang=en');
  await expect(bot.locator('#hint')).toBeVisible({ timeout: 10_000 });
  await bot.close();
});

test('two tabs of one browser profile are two different players', async ({ browser }) => {
  const ctx = await browser.newContext();
  const name = room('tabs');
  const first = await ctx.newPage();
  await first.goto(BASE + '/?room=' + name + '&lang=en');
  await first.locator('#board .cell').first().waitFor({ timeout: 10_000 });
  const second = await ctx.newPage();
  await second.goto(BASE + '/?room=' + name + '&lang=en');
  await expect(second.locator('#identity')).toContainText('You: O', { timeout: 10_000 });
  await ctx.close();
});

test('placeholders and cell borders meet WCAG contrast in both themes', async ({ browser }) => {
  for (const scheme of ['dark', 'light']) {
    const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width: 1280, height: 900 } });
    const page = await ctx.newPage();
    await page.goto(BASE + '/?room=' + room('c' + scheme.charAt(0)) + '&bot=easy&lang=en');
    await expect(page.locator('#board .cell').first()).toBeVisible({ timeout: 10_000 });
    const m = await page.evaluate(() => {
      const lum = (rgb) => {
        const [r, g, b] = rgb.map((v) => {
          const x = v / 255;
          return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const parse = (s) => (s.match(/[\d.]+/g) || []).slice(0, 3).map(Number);
      const bgOf = (el) => {
        let node = el;
        while (node) {
          const c = getComputedStyle(node).backgroundColor;
          if (c && c !== 'transparent' && !/rgba\(.*,\s*0\)/.test(c)) return parse(c);
          node = node.parentElement;
        }
        return [255, 255, 255];
      };
      const ratio = (a, b) => (Math.max(lum(a), lum(b)) + 0.05) / (Math.min(lum(a), lum(b)) + 0.05);
      const input = document.querySelector('#name');
      const cellEl = document.querySelector('#board .cell');
      return {
        placeholder: ratio(parse(getComputedStyle(input, '::placeholder').color), bgOf(input)),
        cellLine: ratio(parse(getComputedStyle(cellEl).borderTopColor), bgOf(document.body)),
        borderPx: parseFloat(getComputedStyle(cellEl).borderTopWidth),
      };
    });
    expect(m.placeholder, scheme + ' placeholder').toBeGreaterThanOrEqual(4.5);
    expect(m.borderPx, scheme + ' visible cell border').toBeGreaterThanOrEqual(1);
    expect(m.cellLine, scheme + ' cell border').toBeGreaterThanOrEqual(3);
    await ctx.close();
  }
});
