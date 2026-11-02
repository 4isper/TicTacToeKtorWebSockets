# TicTacToe (Ktor WebSockets)

A minimal tic-tac-toe game server: two players connect over WebSocket, every state change is broadcast to both as JSON. Built with Kotlin and Ktor.

## Run

```bash
./gradlew run          # listens on :8080, override with the PORT env var
```

## Protocol

- Endpoint: `ws://localhost:8080/play`
- Players are matched pairwise: the first two connections play one game, the next two get their own game, and so on. In each game the first connection plays `X`, the second `O`. An emptied game (both players left) is retired.
- Client → server: a text frame `make_turn#{"x":<col>,"y":<row>}` where `x` is the column and `y` is the row, both `0..2`. Messages with any other type are ignored.
- Server → client: on every state change a JSON `GameState`, for example:

  ```json
  {"field":[["X",null,null],["O",null,null],[null,null,null]],"connectionPlayers":["X","O"],"winningPlayer":null,"isBoardFull":false,"playerAtTurn":"O"}
  ```

  Note: fields equal to their default value are omitted from the JSON (`encodeDefaults = false` in kotlinx.serialization). In practice `playerAtTurn` is absent while it is `"X"`, `winningPlayer` while the round is not decided, and `isBoardFull` while the board is not full — treat missing fields as defaults.
- A won or full round resets the board automatically 5 seconds later; the `score` object (`{"X":<wins>,"O":<wins>}`) accumulates across rounds for as long as the same two players stay connected.
- A `make_turn` frame with malformed JSON closes the session.

## Development

```bash
./gradlew test             # integration (websocket flow) + domain unit tests
./gradlew spotlessApply    # format with ktlint
./gradlew build            # test + spotlessCheck + detekt (fails on violations)
```

CI runs `./gradlew build` on every push and pull request (GitHub Actions).

## Roadmap

- Reconnect handling (a dropped socket frees the slot; the next joiner inherits the ongoing game)
- Configurable board size
- Named rooms / invite links (pairing is currently automatic)
