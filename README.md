# TicTacToe (Ktor WebSockets)

A minimal tic-tac-toe game server: two players connect over WebSocket, every state change is broadcast to both as JSON. Built with Kotlin and Ktor.

## Run

```bash
./gradlew run          # listens on :8080, override with the PORT env var
```

## Protocol

- Endpoint: `ws://localhost:8080/play`
- **Rooms / invite links:** connect to `ws://localhost:8080/play?room=<name>` to play with a specific opponent — the first two connections to a room play each other, a third gets `{"type":"error","reason":"room is full"}`. Room names are 1–32 characters of letters, digits, `-` and `_` (anything else answers `invalid room name`). A room ceases to exist once both players have left (after the reconnect grace), so the name can be reused. Connections without a `room` parameter are matched automatically, as before.
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

  A `state` event is broadcast to both players on every accepted move, connection change and round restart. An `error` event (`reason`: `cell occupied`, `not your turn`, `round is over`, `cell out of bounds`, `malformed message`, `unknown token`) is sent only to the player whose action caused it, and a rejected move does not close the session.
  Every field of `state` is always present in the JSON, including defaults (`winningPlayer: null`, `isBoardFull: false`, `playerAtTurn: "X"`) — no omitted-fields special cases.
- Reconnect: right after connecting every player receives `{"type":"joined","token":"<uuid>","symbol":"X"}`. If the connection drops, the player's slot is held for 10 seconds (`reconnectGraceMs`); reconnecting with `{"type":"reconnect","token":"<uuid>"}` rebinds the session to the same game and symbol and answers with the current `state`. After the grace expires the slot is freed (the next joiner takes the symbol) and the token stops working. A client that reconnects with an unknown token stays in the game it was handed on connect.
- A won or full round resets the board automatically 5 seconds later; the `score` object (`{"X":<wins>,"O":<wins>}`) accumulates across rounds for as long as the same two players stay connected.
- The board is 3×3 by default; the server operator can configure another square size per game via the game factory (`TicTacToeGame(boardSize = n)`). Clients should read the board dimensions from the `field` array rather than assuming 3×3.

## Development

```bash
./gradlew test             # integration (websocket flow) + domain unit tests
./gradlew spotlessApply    # format with ktlint
./gradlew build            # test + spotlessCheck + detekt (fails on violations)
```

CI runs `./gradlew build` on every push and pull request (GitHub Actions).

## Roadmap

- Letting the room creator pick the board size (the game already supports configurable square boards)
