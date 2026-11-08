const { test, expect } = require('@playwright/test');

// The user-visible contract: the tab keeps painting while the hint search runs.
// A main-thread search starves rAF for ~0.9s (one huge wall-clock frame gap); a worker does not.
test('the hint search keeps the tab responsive', async ({ page }) => {
  await page.goto('/?room=e2e-hint-' + Date.now() + '&size=7&win=4&bot=hard');
  const hint = page.locator('#hint');
  await expect(hint).toBeVisible({ timeout: 10_000 });

  // the meter runs before the click so it cannot miss the search window
  const gapPromise = page.evaluate(() => new Promise((resolve) => {
    // wall clock, not the rAF timestamp: headless vsync times hide main-thread stalls
    let last = performance.now();
    let max = 0;
    const start = last;
    function tick() {
      const now = performance.now();
      max = Math.max(max, now - last);
      last = now;
      if (now - start < 2500) requestAnimationFrame(tick); else resolve(max);
    }
    requestAnimationFrame(tick);
  }));
  await hint.click();
  const maxGapMs = await gapPromise;
  expect(maxGapMs).toBeLessThan(200);
  await expect(page.locator('#board .cell.hint')).toHaveCount(1);
});
