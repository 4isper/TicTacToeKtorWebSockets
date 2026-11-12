const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

test('the primary call to action stands out and reacts to hover', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const quick = page.locator('#quick-play');
  const base = await quick.evaluate((e) => getComputedStyle(e).backgroundColor);
  const secondary = await page.locator('#set-name').evaluate((e) => getComputedStyle(e).backgroundColor);
  expect(base).not.toBe(secondary);
  await quick.hover();
  const hovered = await quick.evaluate((e) => getComputedStyle(e).backgroundColor);
  expect(hovered).not.toBe(base);
});

test('every visible control is at least 44px in both directions', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  await expect(page.locator('#player-search')).toBeVisible();
  await page.waitForTimeout(600);
  const small = await page.evaluate(() => Array.from(
    document.querySelectorAll('button, a, input, select, [role="button"]'),
  ).filter((el) => {
    const cs = getComputedStyle(el);
    const r = el.getBoundingClientRect();
    return cs.display !== 'none' && cs.visibility !== 'hidden' && r.width > 0 && r.height > 0 &&
      (r.width < 44 || r.height < 44);
  }).map((el) => ({
    id: el.id,
    cls: String(el.className),
    w: Math.round(el.getBoundingClientRect().width),
    h: Math.round(el.getBoundingClientRect().height),
  })));
  expect(small).toEqual([]);
});

test('the page has landmarks, a skip link and a description', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  await expect(page.locator('main#main')).toHaveCount(1);
  await expect(page.locator('[role="region"]')).toHaveCount(5);
  const desc = await page.locator('meta[name="description"]').getAttribute('content');
  expect(desc && desc.length).toBeGreaterThan(10);
  await expect(page.locator('#skip-link')).toHaveAttribute('href', '#main');
  await page.keyboard.press('Tab');
  await expect(page.locator('#skip-link')).toBeFocused();
});

test('sound and theme expose their state', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const sound = page.locator('#sound');
  await expect(sound).toHaveAttribute('aria-pressed', 'false');
  await sound.click();
  await expect(sound).toHaveAttribute('aria-pressed', 'true');

  const theme = page.locator('#theme');
  const before = (await theme.textContent()) || '';
  await theme.click();
  const after = (await theme.textContent()) || '';
  expect(after).not.toBe(before);
  expect(after).toMatch(/light|dark|auto/i);
});

test('empty containers do not leave dead space', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  const displays = await page.evaluate(() => ['moves', 'error'].map(
    (id) => getComputedStyle(document.getElementById(id)).display,
  ));
  expect(displays).toEqual(['none', 'none']);
});

test('the leaderboard explains its numbers', async ({ page }) => {
  await page.goto(BASE + '/?lang=en');
  await expect(page.locator('#leaderboard .legend')).toContainText('Wins');
});
