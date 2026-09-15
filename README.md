# Ludo

A lightweight, fully offline Ludo game for Android. Pass-and-play with up to
four people on one device, any seat swappable for a bot.

The release APK is **29 KB**. There are no runtime dependencies beyond the
Kotlin standard library — no AndroidX, no Compose, no Material. The entire UI
is two custom `View`s drawing on a `Canvas`, and the app requests no
permissions and opens no sockets.

## Building

```sh
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified + resource-shrunk release APK
./gradlew testDebugUnitTest      # rules engine tests, plain JVM
```

`local.properties` must point at an Android SDK with platform 36 installed.

## Rules

The variant here is the one most people play:

- A **6** is needed to bring a token out of its yard.
- A **6** grants another roll. **Three sixes in a row forfeits the turn** — the
  third six is not played.
- Landing on an enemy token **off a safe square** sends it home and grants
  another roll. The eight safe squares are the four coloured start cells and
  the four star cells eight steps on from them.
- A token must reach the centre on an **exact roll**; an overshoot is not a
  legal move.
- Getting a token home also grants another roll. First player with all four
  tokens home wins.

Tokens of the same colour may stack on one square. There is no blocking rule.

## How a position is stored

Every token's position is a single integer, `steps`, and nearly all of the
rules fall out of that choice:

| `steps`  | meaning                                                           |
| -------- | ----------------------------------------------------------------- |
| `0`      | in its yard                                                       |
| `1..51`  | on the shared 52-cell ring, at `(start[player] + steps - 1) % 52` |
| `52..56` | in its own five-cell home run                                     |
| `57`     | finished, on the centre square                                    |

The four players start 13 ring cells apart, so the same `steps` value maps to a
different ring cell per player and no per-player path table is needed.

A player only ever touches **51** of the 52 ring cells: it enters on its own
start cell and peels off into its home run one cell before coming back round to
that start again. That is why the finish is 57 and not 58, and it is the single
easiest thing to get wrong when changing `Board`.

Because a token in a yard or a home run has no ring index, capture detection is
just an integer compare — `Board.ringIndex` returns `-1` for anything that
cannot be captured, so those tokens can never collide with anything.

## Layout

| File               | What it does                                                     |
| ------------------ | ---------------------------------------------------------------- |
| `Board.kt`         | Board geometry: the ring, home runs, yards, safe squares         |
| `Game.kt`          | `GameState`, its save encoding, and `Rules` — the whole variant  |
| `Bot.kt`           | One-ply heuristic opponent                                       |
| `BoardView.kt`     | Draws the board and tokens, turns taps into token choices        |
| `DieView.kt`       | The die, and its tumble animation                                |
| `GameActivity.kt`  | The turn loop                                                    |
| `SetupActivity.kt` | Seat picker and resume                                           |
| `Saves.kt`         | The game in progress, as one string in SharedPreferences         |
| `Insets.kt`        | Keeps content clear of the system bars under forced edge-to-edge |

`Game.kt` and `Board.kt` touch no Android APIs, so the rules are exercised from
plain JVM unit tests in `app/src/test`.

Every transition in `GameActivity` goes through `beginTurn()`, which reads the
state and decides what happens next. A game restored from disk mid-turn — even
with the dice already rolled — resumes through the same path, so there is no
separate restore logic to keep in sync.

## License

GPL-3.0. See [LICENSE](LICENSE).
