# TicTacToe (Ktor WebSockets)

A two-player tic-tac-toe game over WebSockets, built with Kotlin Multiplatform and Ktor. The Ktor server and the browser client share the same domain rules and JSON wire protocol, so every state change is broadcast to both players and to spectators from one source of truth. Beyond a bare board there are rooms and invite links, configurable boards and clocks, practice bots, Elo-rated profiles, shareable replays and an installable PWA.

## Run

```bash
./gradlew jvmRun       # builds the JS client, listens on :8080, PORT env overrides
```

Then open http://localhost:8080/ to play in the browser (the page is rendered with the Ktor HTML DSL, the client is a Kotlin/JS bundle). Invite friends with a room link: http://localhost:8080/?room=alpha. Append `&lang=en` to any link for the English UI — the default is Russian. Round results persist to `data/scoreboard.json` (override the path with the `SCOREBOARD_FILE` env var; point it at a `.db`/`.sqlite` path to switch to the SQLite store).

## Features

- **One codebase, two targets:** `commonMain` holds the rules, the bot brain, Elo and the wire protocol; `jvmMain` is the Ktor server, `jsMain` the browser client. Both sides compile from the same types.
- **Installable PWA:** `/manifest.webmanifest` with an SVG icon and generated 192/512 PNG icons, a `/sw.js` service worker and an `/offline` fallback page. Offline support is best-effort — playing still needs the server.
- **Themes:** dark by default, automatic light theme via `prefers-color-scheme`, with matching `theme-color` meta tags.
- **Sound and accessibility:** synthesized Web Audio cues distinguish your own move from the opponent's and mark wins and draws; a mute toggle persists in `localStorage`. Cells carry `aria-label`s, the last move stays marked, and the turn timer shows a progress bar.
- **Resilient scoreboard:** JSON writes are atomic (temp file + rename). If the file is unreadable, the original is saved as `<name>.corrupt` and a warning is logged before the store starts empty, so the history is not silently lost.
- **Fair play and bounds:** a name belongs to the reconnect token that claimed it, so another live session cannot take it and change its Elo rating. The lobby caps active games (100) and spectators per room (20); a refused join answers `room is full`. These are light-weight identity guards, not user accounts — there are no passwords or registration.
- **Checks:** `./gradlew build` runs the full test suite, ktlint via spotless, detekt (including the JVM type-resolution tasks) and the JS production bundle; GitHub Actions runs the same on every push and pull request.

## Protocol

- Endpoint: `ws://localhost:8080/play`
- **Rooms / invite links:** connect to `ws://localhost:8080/play?room=<name>` to play with a specific opponent — the first two connections to a room play each other; every further connection becomes a **spectator**: it receives `{"type":"joined","token":…,"symbol":null}` plus the current `state`, sees every later `state`, but any `make_turn`, `request_rematch` or `request_draw` from it answers `not your turn` (reactions are open to spectators). Spectators do not hold the room: it still ceases to exist once both players have left (after the reconnect grace), so the name can be reused. Room names are 1–32 characters of letters, digits, `-` and `_` (anything else answers `invalid room name`). Connections without a `room` parameter are matched automatically and stay players-only — they are paired only with a game that has the same `size`, `win`, `bot` and `turn` settings (a joiner whose settings match no waiting game gets a fresh one with its own settings), and a full anonymous pair rejects extra connections with `room is full`.
- **Replays:** every finished round's move list is recorded into the named players' history — `/player` responses carry `moves` and `boardSize` per record, and clicking a match in a profile replays it move by move on a mini board. Every replay view also has a share button: the link carries the whole game in its `#r=…` fragment, so it opens the replay on any machine with no server state involved.
- **Room list:** `GET /rooms` answers `{"rooms":[…]}` — every named room that still holds players, with `name`, `players`, `spectators`, `boardSize`, `winLength`, `botLevel` and `turnSeconds`. The page polls it every 5 seconds, shows the list above the leaderboard and joins a room on click; anonymous games are never listed.
- **Board size:** a game's board is square and is picked by the first connection that creates it — add `&size=<n>` (an integer `3..10`) to the room URL, e.g. `http://localhost:8080/?room=alpha&size=5`; players joining later inherit the existing board whatever `size` they pass. Anything outside `3..10` (or not a number) answers `invalid board size` before joining. Clients should read the board dimensions from the `field` array rather than assuming 3×3.
- **Win length:** the winning line does not have to span the whole board — add `&win=<n>` (`3..boardSize`, default: the full board dimension) for games like 4-in-a-row on a 5×5 board, e.g. `http://localhost:8080/?room=alpha&size=5&win=4`. Anything outside the range answers `invalid win length` before joining. Every row, column and diagonal of either direction counts, including diagonals that start away from a corner. The `state` event reports the winning run as `winningCells` (`[{"x":..,"y":..}, …]`) so clients can highlight it.
- **Turn timer:** a player must move within a time budget or forfeits the round to the opponent — add `&turn=<sec>` (an integer `5..600`, default `30`) to the room URL, e.g. `http://localhost:8080/?room=alpha&turn=60`. Anything outside the range (or not a number) answers `invalid turn time` before joining; players joining later inherit the running timer whatever `turn` they pass.
- **Game clock:** for a whole-game budget instead of a per-turn one, add `&clock=<sec>` (`30..3600`, off by default) — each player gets their own chess clock that only drains while it is their turn; the clocks ride every `state` as `clocks` (`{"X":ms,"O":ms}`) and an exhausted clock forfeits the round. A bad value answers `invalid clock` before joining; like the other settings, the budget belongs to the game its creator starts.
- **Practice bot:** add `&bot=<level>` to the room URL (`easy`, `normal` or `hard`; `&bot=1` stays a synonym of `normal`) and the game fills its empty second slot with a bot. `easy` takes an immediate win and otherwise plays random cells; `normal` is an alpha-beta negamax — perfect on 3×3, depth-limited with an open-window heuristic on larger boards; `hard` searches deeper. The bot thinks for ~0.4 s. Humans have priority — a joining human takes the bot's slot and the bot steps aside; when a human leaves, the bot returns. The bot never asks for a rematch, so a human rematch request alone resets the round. An unknown level answers `invalid bot level` before joining. Like `size`, `win` and `turn`, the level belongs to the connection that creates the game. `&bot=2` turns the room into an **exhibition**: both slots are normal-level bots playing each other round after round, every joiner is a spectator and nobody ever displaces them.
- In every game the first connection plays `X`, the second `O`. An emptied game (both players left) is retired.
- Client → server: a text frame holding one JSON message with a `type` discriminator:

  ```json
  {"type":"make_turn","x":<col>,"y":<row>}
  {"type":"reconnect","token":"<uuid>"}
  {"type":"request_rematch"}
  {"type":"request_draw"}
  {"type":"request_undo"}
  {"type":"react","emoji":"👍"}
  {"type":"set_name","name":"Дима"}
  ```

  `x` is the column and `y` is the row (`0`-based, `0..size-1`). A malformed or unknown message is answered with an `error` event and does not close the session. `set_name` trims the name and accepts 1–20 characters (`invalid name` otherwise); the name must also be unique among the connected players of the game, case-insensitively, and cannot be claimed by another live session (`name is taken` otherwise). Names travel in every `state` as `playerNames` (`{"X":"Дима"}`), survive round resets and are dropped when a player disconnects. The browser client remembers the last accepted name in `localStorage` and re-applies it automatically after a reload.
- Server → client: a text frame per event, also discriminated by `type`:

  ```json
  {"type":"state","state":{"field":[["X",null,null],["O",null,null],[null,null,null]],"connectionPlayers":["X","O"],"playerAtTurn":"O"}}
  {"type":"error","reason":"cell occupied"}
  ```

  A `state` event is broadcast to both players on every accepted move, connection change and round restart. An `error` event (`reason`: `cell occupied`, `not your turn`, `round is over`, `cell out of bounds`, `malformed message`, `unknown token`, `stale session`, `name is taken`, `invalid turn time`, `invalid bot level`, `invalid reaction`, `nothing to undo`, `invalid clock`) is sent only to the player whose action caused it, and a rejected move does not close the session.
  Every field of `state` is always present in the JSON, including defaults (`winningPlayer: null`, `isBoardFull: false`, `playerAtTurn: "X"`) — no omitted-fields special cases.
- Reconnect: right after connecting every player receives `{"type":"joined","token":"<uuid>","symbol":"X"}`. If the connection drops, the player's slot is held for 10 seconds (`reconnectGraceMs`); reconnecting with `{"type":"reconnect","token":"<uuid>"}` rebinds the session to the same game and symbol and answers with the current `state`. After the grace expires the slot is freed (the next joiner takes the symbol) and the token stops working. A client that reconnects with an unknown token stays in the game it was handed on connect.
- A won or full round resets the board automatically 5 seconds later; the `score` object (`{"X":<wins>,"O":<wins>}`) accumulates across rounds for as long as the same two players stay connected. While the round is over, every `state` carries `roundRestartInMs` (remaining milliseconds until the automatic reset) and, once a player asks, `rematchPlayers` — the client may send `{"type":"request_rematch"}` to reset early, and the board resets as soon as every connected player has asked. Asking while a round is still running answers `round is not over`.
- **Takeback:** the player who made the last move may ask for it back with `{"type":"request_undo"}` — the offer rides `state` as `undoOfferBy`, asking again withdraws it, and any new move kills it. The opponent (the player on turn) approves with the same message: the stone is removed, the turn returns to its maker, and the move leaves the replay. Asking with no moves on the board answers `nothing to undo`, after the round ends — `round is over`.
- **Reactions:** a player or spectator may send `{"type":"react","emoji":"👍"}` — one of 👍 ❤️ 😂 😮 (`invalid reaction` otherwise, and a repeat within a second is dropped quietly). The server broadcasts `{"type":"reaction","from":<symbol|null>,"emoji":…}` to everyone in the game; the page floats the emoji over the board.
- **Draw by agreement:** while the round is still running, a player may send `{"type":"request_draw"}` — the offer rides every later `state` as `drawPlayers`, and asking again withdraws it. The round ends in a draw (the board restarts like after any other round end, no `score` bump) as soon as both connected humans have offered; with a bot in the game a draw cannot be agreed. Every `state` marks such a round with `isDrawAgreed: true`. Asking while the round is over answers `round is over`.
- **Leaderboard and profiles:** every named player carries an Elo rating (start 1000, K=32), but a round against an unnamed side — a practice bot — is unrated: it lands in the history and replays, yet leaves the rating untouched. `GET /leaderboard` answers the top ten sorted by rating (then wins, then name); `GET /player?name=…` answers one profile whose `history` keeps up to 20 records (`epochMs`, `opponent`, `result`, `moves`, `boardSize`, `ratingAfter`). Unnamed players are invisible to both.
## Development

The build is multiplatform: `commonMain` holds the domain rules and the wire protocol shared by both sides, `jvmMain` the server, `jsMain` the browser client.

```bash
./gradlew jvmTest          # integration (websocket flow) + engine tests
./gradlew spotlessApply    # format with ktlint
./gradlew build            # tests + spotlessCheck + detekt (fails on violations)
```

detekt checks every Kotlin source in `src/`, and `check` also runs the JVM type-resolution analysis (`detektMainJvm` / `detektTestJvm`) for rules that need type information. Every violation fails the build — there is no baseline to hide behind.

The `commonTest` suite runs on the JVM. Browser tests (`jsBrowserTest`) are deliberately disabled in `build.gradle.kts`: they need a Karma/Chrome runner and npm packages from the Kotlin karma fork, which an offline build cannot resolve. Re-enable them only with network access and a headless browser available.

CI runs `./gradlew build` on every push and pull request (GitHub Actions).
