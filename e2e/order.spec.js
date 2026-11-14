const { test, expect } = require('./diagnostics');

const BASE = 'http://localhost:8080';

// On a loaded CI runner the room's broadcast coroutine can deliver StateChanged
// before the websocket handler sends Joined. Force that order so the client has
// to be correct regardless of which frame wins the race.
test('a state arriving before Joined still leaves a playable board', async ({ page }) => {
  await page.addInitScript(() => {
    const Native = window.WebSocket;
    window.__delivered = [];
    function Reordered(url, protocols) {
      const socket = new Native(url, protocols);
      let pendingState = null;
      let pendingJoined = null;
      let swapped = false;
      let replaying = false;
      socket.addEventListener('message', (event) => {
        if (swapped || replaying) return;
        let type;
        try { type = JSON.parse(event.data).type; } catch (_) { return; }
        if (type !== 'joined' && type !== 'state') return;
        if (type === 'joined') pendingJoined = event.data;
        else pendingState = event.data;
        event.stopImmediatePropagation();
        if (pendingState && pendingJoined) {
          const stateFrame = pendingState;
          const joinedFrame = pendingJoined;
          swapped = true;
          replaying = true;
          socket.dispatchEvent(new MessageEvent('message', { data: stateFrame }));
          socket.dispatchEvent(new MessageEvent('message', { data: joinedFrame }));
          replaying = false;
        }
      });
      // only the replayed frames reach this listener: the originals were stopped
      socket.addEventListener('message', (event) => {
        try { window.__delivered.push(JSON.parse(event.data).type); } catch (_) { /* ignore */ }
      });
      return socket;
    }
    Reordered.prototype = Native.prototype;
    for (const state of ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED']) {
      Object.defineProperty(Reordered, state, { value: Native[state] });
    }
    window.WebSocket = Reordered;
  });

  await page.goto(BASE + '/?room=e2e-order-' + Date.now() + '&bot=easy&lang=en');
  await expect(page.locator('#board .cell').first()).toBeEnabled({ timeout: 10_000 });
  // prove the race was actually forced, whatever order the server used
  expect(await page.evaluate(() => window.__delivered.slice(0, 2))).toEqual(['state', 'joined']);
  await expect(page.locator('#hint')).toBeVisible({ timeout: 10_000 });
});
