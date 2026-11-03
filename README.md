# TicTacToe (Ktor WebSockets)

A minimal tic-tac-toe game server: two players connect over WebSocket, every state change is broadcast to both as JSON. Built with Kotlin and Ktor.

## Run

```bash
./gradlew jvmRun       # builds the JS client, listens on :8080, PORT env overrides
```

Then open http://localhost:8080/ to play in the browser (the page is rendered with the Ktor HTML DSL, the client is a Kotlin/JS bundle). Invite friends with a room link: http://localhost:8080/?room=alpha.

## Protocol

- Endpoint: `ws://localhost:8080/play`
- **Rooms / invite links:** connect to `ws://localhost:8080/play?room=<name>` to play with a specific opponent — the first two connections to a room play each other, a third gets `{"type":"error","reason":"room is full"}`. Room names are 1–32 characters of letters, digits, `-` and `_` (anything else answers `invalid room name`). A room ceases to exist once both players have left (after the reconnect grace), so the name can be reused. Connections without a `room` parameter are matched automatically, as before.
- **Board size:** a game's board is square and is picked by the first connection that creates it — add `&size=<n>` (an integer `3..10`) to the room URL, e.g. `http://localhost:8080/?room=alpha&size=5`; players joining later inherit the existing board whatever `size` they pass. Anything outside `3..10` (or not a number) answers `invalid board size` before joining. Clients should read the board dimensions from the `field` array rather than assuming 3×3.
- **Win length:** the winning line does not have to span the whole board — add `&win=<n>` (`3..boardSize`, default: the full board dimension) for games like 4-in-a-row on a 5×5 board, e.g. `http://localhost:8080/?room=alpha&size=5&win=4`. Anything outside the range answers `invalid win length` before joining. The `state` event reports the winning run as `winningCells` (`[{"x":..,"y":..}, …]`) so clients can highlight it.
- Players are matched pairwise: the first two connections play one game, the next two get their own game, and so on. In each game the first connection plays `X`, the second `O`. An emptied game (both players left) is retired.
- Client → server: a text frame holding one JSON message with a `type` discriminator. Currently only:

  ```json
  {"type":"make_turn","x":<col>,"y":<row>}
  ```

  `x` is the column and `y` is the row, both `0..2`. A malformed or unknown message is answered with an `error` event and does not close the session.
- Server → client: a text frame per event, also discriminated by `type`:

  ```json
  {"type":"state","state":{"field":[["X",null,null],["O",null,null],[null,null,null]],"connectionPlayers":["X","O"],"playerAtTurn":"O"}}
  {"type":"error","reason":"cell occupied"}
  ```

  A `state` event is broadcast to both players on every accepted move, connection change and round restart. An `error` event (`reason`: `cell occupied`, `not your turn`, `round is over`, `cell out of bounds`, `malformed message`, `unknown token`, `stale session`) is sent only to the player whose action caused it, and a rejected move does not close the session.
  Every field of `state` is always present in the JSON, including defaults (`winningPlayer: null`, `isBoardFull: false`, `playerAtTurn: "X"`) — no omitted-fields special cases.
- Reconnect: right after connecting every player receives `{"type":"joined","token":"<uuid>","symbol":"X"}`. If the connection drops, the player's slot is held for 10 seconds (`reconnectGraceMs`); reconnecting with `{"type":"reconnect","token":"<uuid>"}` rebinds the session to the same game and symbol and answers with the current `state`. After the grace expires the slot is freed (the next joiner takes the symbol) and the token stops working. A client that reconnects with an unknown token stays in the game it was handed on connect.
- A won or full round resets the board automatically 5 seconds later; the `score` object (`{"X":<wins>,"O":<wins>}`) accumulates across rounds for as long as the same two players stay connected. While the round is over, every `state` carries `roundRestartInMs` (remaining milliseconds until the automatic reset) and, once a player asks, `rematchPlayers` — the client may send `{"type":"request_rematch"}` to reset early, and the board resets as soon as every connected player has asked. Asking while a round is still running answers `round is not over`.
## Development

The build is multiplatform: `commonMain` holds the domain rules and the wire protocol shared by both sides, `jvmMain` the server, `jsMain` the browser client.

```bash
./gradlew jvmTest          # integration (websocket flow) + engine tests
./gradlew spotlessApply    # format with ktlint
./gradlew build            # tests + spotlessCheck + detekt (fails on violations)
```

CI runs `./gradlew build` on every push and pull request (GitHub Actions).

## Roadmap

- Spectators: let a third connection watch a full room instead of refusing it
