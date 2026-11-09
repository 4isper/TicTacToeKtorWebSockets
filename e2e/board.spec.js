const { test, expect } = require('@playwright/test');

const BASE = 'http://localhost:8080';

// A big board keeps its 44px cells on a phone by scrolling INSIDE the board
// container; the page itself must never overflow sideways.
test('a 10x10 board scrolls inside its container on a narrow screen', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 667 }, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  await page.goto(BASE + '/?room=e2e-big-' + Date.now() + '&size=10&win=5&bot=easy&lang=en');
  await expect(page.locator('#board .cell')).toHaveCount(100);

  const m = await page.evaluate(() => {
    const board = document.getElementById('board');
    board.scrollLeft = 100;
    return {
      innerWidth: window.innerWidth,
      docWidth: document.documentElement.scrollWidth,
      boardClient: board.clientWidth,
      boardScroll: board.scrollWidth,
      scrollLeft: board.scrollLeft,
      turnBarRight: document.getElementById('turn-bar').getBoundingClientRect().right,
    };
  });
  expect(m.docWidth).toBeLessThanOrEqual(m.innerWidth);
  expect(m.innerWidth).toBe(375); // the layout viewport must not expand
  expect(m.boardScroll).toBeGreaterThan(m.boardClient);
  expect(m.scrollLeft).toBeGreaterThan(0);
  expect(m.turnBarRight).toBeLessThanOrEqual(375);

  // the far right cell is reachable: scroll, then tap
  await page.locator('#board .cell').nth(9).tap();
  await expect(page.locator('#board .cell').nth(9)).toHaveText('X');
  await ctx.close();
});