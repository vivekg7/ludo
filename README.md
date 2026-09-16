# Ludo

A lightweight, fully offline Ludo game for Android. Pass-and-play with up to
four people on one device, any seat swappable for a bot, and a profile for each
person that keeps their wins.

Requires **Android 12 (API 31)** or newer. The signed release APK is **44 KB**.
There are no runtime dependencies beyond the
Kotlin standard library — no AndroidX, no Compose, no Material. The board and
die are two custom `View`s drawing on a `Canvas`, the sound effects are
synthesised in code, the setup screen and its dialogs are plain platform
widgets, and the app requests no permissions and opens no sockets.

## Building

```sh
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified, shrunk and signed release APK
./gradlew testDebugUnitTest      # rules engine tests, plain JVM
```

`local.properties` must point at an Android SDK with platform 36 installed.

### Signing

`assembleRelease` signs the APK when `local/keystore.properties` exists:

```properties
storeFile=local/crylo-release.jks
storePassword=…
keyAlias=crylo-ludo
keyPassword=…
```

`local/` is gitignored, so neither the keystore nor its password reaches the
repository. A checkout without that file still builds release — the APK just
comes out unsigned — so a machine or CI runner that has no key is not blocked.

**The keystore is not recoverable.** Lose it and no already-installed copy of
the app can ever be updated, because Android refuses an update signed by a
different key. Keep a backup off this machine.

### Why the APK is the size it is

Two packaging choices are deliberate and pull in opposite directions:

- `minSdk 31` lets AGP drop the v1 JAR signature entirely (v2 covers API 24 and
  up), which is worth about 3.4 KB of `META-INF/`.
- At `minSdk ≥ 28` AGP stores `classes.dex` uncompressed so Android can map it
  straight from the APK. That is better on device but costs 22 KB of download,
  so `packaging { dex { useLegacyPackaging = true } }` compresses it again.
  Reverse that if startup time ever matters more than download size.

### Archiving a release

`scripts/archive-apk.sh` builds the release APK and copies it into `local/` named from
the version in the built manifest, alongside a `.sha256`, the R8 `mapping.txt` for that
build, and the signer fingerprint printed for confirmation.

```sh
./scripts/archive-apk.sh          # build, verify, archive
./scripts/archive-apk.sh --force  # replace an existing archive
```

All three files share the `ludo-v1.0.apk` prefix, so one release is removed as a unit
and no mapping can be left behind to be matched against the wrong APK later. Keeping the
mapping matters because `release` minifies: without it, an obfuscated stack trace from a
shipped build can never be read back, and the file is written under `app/build/`, which
any clean throws away. `--no-mapping` skips it, for if minification is ever turned off.

It is deliberately not wired into `assembleRelease`. A release build made while working
on a feature would otherwise overwrite the archived APK of the same version, leaving a
file labelled `v1.0` that is not the `v1.0` that shipped — silently. Three things guard
against that: a dirty working tree produces `ludo-v1.0-dirty-g1a2b3c4.apk` rather than
the release name, an existing target is never overwritten without `--force`, and an APK
that came out unsigned is refused outright rather than archived under a release name.

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

## Profiles

Each seat is a profile, a guest, a bot, or empty. A profile is a name and a
record — games played and games won — kept on the device. The Profiles button
on the setup screen lists everyone's record and renames or deletes them; a new
profile can also be made straight from a seat.

- **Only a finished game counts.** When a game is won, every seated profile
  gets a game played and the winner's gets a win. An abandoned game changes
  nothing, so quitting a losing game is not recorded as a loss — and a finished
  game is credited in exactly one place, `GameActivity.play`, the moment the
  winning move is applied, so it cannot count twice or be lost to the app
  closing mid-animation.
- **Bots and guests have no record.** A guest seat is for a visitor who does
  not need one.
- **Seats hold a profile id, not a name.** Renaming someone mid-game relabels
  their seat in the saved game. Ids come from a counter that is never wound
  back, so deleting the newest profile cannot free its id for the next one and
  have an old saved game credit the wrong person. A profile deleted while a
  game is saved just shows as its colour.
- **Names are unique, ignoring case.** The turn banner and the names around the
  board are the only things that tell two seats apart.
- **The lineup is remembered** — saved on every seat change, not on Start — so
  the same family does not pick seats again each game, and backing out of the
  setup screen keeps the picks.

The game save format is at version 2, which adds the per-seat profile ids. A
version 1 save, from before profiles, still resumes with its human seats as
guests, so a game left in progress across the update is not lost.

## Game screen

Each seat's name is written beside its own yard — the top two above the board,
the bottom two below it — and a triangle in the player's colour points from the
current player's name at their yard. A profile shows its name, a guest its
colour, and a bot "Bot 1", "Bot 2" and so on in seat order, so a table of bots
can be told apart; the turn banner uses the same names.

- **The names are drawn by `BoardView`, not laid out as separate views.** The
  board is sized to whatever space it gets, so labels in their own views would
  drift away from the yards on any screen whose shape makes the height, not the
  width, the limit. Drawn in board cells, they stay over their yards at any size.
- **The turn marker follows the turn loop, not `state.current`.** A move passes
  the dice in the state before its token starts to slide, so a marker read from
  the state would jump to the next player while the last one's token was still
  moving. `GameActivity` sets it in `beginTurn`, alongside the banner and the
  die's colour, so all three change together.
- **Yards have a pocket per token**, so a yard whose tokens are out reads as
  waiting for them, not as blank.
- **An arrow in each colour** sits on the last ring square before that colour's
  home run, pointing in, to show where its tokens turn off the ring.

## Sound

The game screen plays a rattle and a thud for each roll, a tap for every square
a token walks, a falling slide for a capture, a chime for a token reaching
home, a low two-note "womp" for a roll that cannot be played, and a fanfare for
the win. The speaker button next to the turn banner mutes them, and the choice
is remembered across games.

- **No audio files.** `Sounds` renders every effect into PCM from sine tones,
  pitch sweeps and short noise bursts when the game screen opens. The whole
  set is about three seconds of audio: a few hundred kilobytes of memory while
  the screen is open, but only about 3.6 KB of code in the APK, where even one
  compressed sample would cost more than that.
- **One static `AudioTrack` per effect**, so different effects overlap freely
  and replaying one just rewinds it. `SoundPool` would do the mixing too, but
  only loads from files, which would mean writing the samples to disk first.
- **Game usage, media volume.** The tracks play as `USAGE_GAME`, so the media
  volume controls them and they mix with music rather than interrupting it. That
  also means the ringer's silent mode does not mute them, which is what the
  in-game button is for.
- **Sounds follow what the player sees, not the state.** Each plays from the
  turn loop at the moment its animation shows it — the capture as the token
  lands, not when `Rules.apply` removes the victim — and the fanfare plays from
  the winning move rather than from `announceWinner`, which also runs when a
  finished game is restored after a rotation.
- **Silent in the background.** Bot turns are scheduled on a `Handler` that
  keeps running after the screen is paused, so the sounds are paused with the
  screen rather than rattling a die from an app the player has left.

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
| `Profile.kt`       | Profiles: names, win records, and their save encoding            |
| `Bot.kt`           | One-ply heuristic opponent                                       |
| `BoardView.kt`     | Draws the board, tokens and seat names; turns taps into choices  |
| `DieView.kt`       | The die, and its tumble animation                                |
| `Sounds.kt`        | Synthesises and plays the sound effects                          |
| `GameActivity.kt`  | The turn loop                                                    |
| `SetupActivity.kt` | Seat picker, profile management and resume                       |
| `Saves.kt`         | Saved game, profiles, last lineup and mute, in SharedPreferences |
| `Insets.kt`        | Keeps content clear of the system bars under forced edge-to-edge |

`Game.kt`, `Board.kt` and `Profile.kt` touch no Android APIs, so the rules and
the profile records are exercised from
plain JVM unit tests in `app/src/test`.

Every transition in `GameActivity` goes through `beginTurn()`, which reads the
state and decides what happens next. A game restored from disk mid-turn — even
with the dice already rolled — resumes through the same path, so there is no
separate restore logic to keep in sync.

That only works if the state is never mid-way through anything. The token
slide and the pause before the next player are purely visual: a move, and the
turn it settles (`Rules.settle` — the same player rolls again, or the dice pass
on), are written to the state the instant the move is chosen. Leaving the roll
in place until the animation finished would let a game saved during it restore
with the token already moved and the same roll still to play.

The same encoding is kept in the activity's saved instance state. Android
rebuilds `GameActivity` from its original intent after a configuration change
(dark mode, font size, split screen) or after killing the process in the
background, and for a game started from the setup screen that intent means
"new game": without the saved instance state the game would restart, and the
next `onPause` would write the fresh game over the real save.

## License

GPL-3.0. See [LICENSE](LICENSE).
