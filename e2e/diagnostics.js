const { test: base, expect } = require('@playwright/test');

// Bot rooms occasionally start in a non-live state on shared CI runners. When an
// attempt fails, print the exact wire states and the client's DOM so the next run
// can be debugged from the log instead of guessed at. Nothing is printed on success.
const captures = new WeakMap();

const test = base.extend({
  page: async ({ page }, use) => {
    const frames = [];
    captures.set(page, frames);
    page.on('websocket', (socket) => {
      socket.on('framereceived', (frame) => {
        try {
          const event = JSON.parse(frame.payload);
          frames.push({
            type: event.type,
            cp: event.state?.connectionPlayers,
            bots: event.state?.botPlayers,
            turn: event.state?.playerAtTurn,
            over: event.state?.isRoundOver,
            winner: event.state?.winningPlayer,
            restartMs: event.state?.roundRestartInMs,
            turnMs: event.state?.turnRemainingMs,
            reason: event.reason,
            symbol: event.symbol,
          });
        } catch (_) {
          // a non-JSON frame is not a game event
        }
      });
    });
    await use(page);
  },
});

test.afterEach(async ({ page }, testInfo) => {
  if (testInfo.status === testInfo.expectedStatus) return;
  let dom;
  try {
    dom = await page.evaluate(() => {
      const text = (id) => document.getElementById(id)?.textContent ?? null;
      return {
        url: location.href,
        innerW: innerWidth,
        status: text('status'),
        timer: text('timer'),
        identity: text('identity'),
        error: text('error'),
        score: text('score'),
        rules: text('rules'),
        firstCellDisabled: document.querySelector('#board .cell')?.disabled ?? null,
        hint: document.getElementById('hint')
          ? getComputedStyle(document.getElementById('hint')).display
          : null,
      };
    });
  } catch (error) {
    dom = { evaluateFailed: String(error) };
  }
  // eslint-disable-next-line no-console
  console.log('CI_DIAG ' + JSON.stringify({
    test: testInfo.title,
    retry: testInfo.retry,
    dom,
    frames: (captures.get(page) || []).slice(-20),
  }));
});

module.exports = { test, expect };
