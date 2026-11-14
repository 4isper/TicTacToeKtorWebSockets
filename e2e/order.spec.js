const { test, expect } = require('./diagnostics');

const BASE = 'http://localhost:8080';

// On a loaded CI runner the room's broadcast coroutine can deliver StateChanged
// before the websocket handler sends Joined. Force that order so the client has
// to be correct regardless of which frame wins the race.
test('a state arriving before Joined still leaves a playable board', async ({ page }) => {
  await page.addInitScript(() => {
    const Native = window.WebSocket;
    function Reordered(url, protocols) {
      const socket = new Native(url, protocols);
      let heldJoined = null;
      let stateSeen = false;
      socket.addEventListener('message', (event) => {
        let type;
        try { type = JSON.parse(event.data).type; } catch (_) { return; }
        if (type === 'joined' && !stateSeen) {
          heldJoined = event.data;
          event.stopImmediatePropagation();
          return;
        }
        if (type === 'state' && heldJoined) {
          const joinedFrame = heldJoined;
          heldJoined = null;
          stateSeen = true;
          event.stopImmediatePropagation();
          socket.dispatchEvent(new MessageEvent('message', { data: event.data }));
          socket.dispatchEvent(new MessageEvent('message', { data: joinedFrame }));
        }
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
  await expect(page.locator('#hint')).toBeVisible({ timeout: 10_000 });
});
