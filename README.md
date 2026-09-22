# Ludo

A lightweight, fully offline Ludo game for Android. Pass-and-play with up to
four people on one device, any seat swappable for a bot, and a profile for each
person that keeps their wins.

Requires **Android 12 (API 31)** or newer. The signed release APK is **60 KB**.
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

- **A random seat rolls first.** Going first is a small edge, so it is drawn
  for each new game rather than always falling to the lowest occupied seat.
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

## Setup screen

The first screen shows the lineup for a new game and, when there is one, the
saved game.

- **A saved game comes first.** It is an amber card above the lineup, listing
  its players leader first with how far each has got — "Vivek 34% · Jyoti 21%" —
  and tapping it resumes. While it exists, Start is drawn as the secondary
  button.
- **Starting over asks first.** Starting a new game deletes the save, and Start
  sits right below the card that resumes it, so a save is never lost to one
  tap: Start asks to confirm and names the game it would replace.
- **Each seat is a whole-width row.** Tapping anywhere on it picks who sits
  there. The row shows who is seated, with the colour and the kind of seat
  underneath: a profile's record ("won 4 of 9"), a guest, a bot, or empty. An
  empty seat is dimmed and its dot is a ring, so the players stand out at a
  glance. Bots are numbered "Bot 1", "Bot 2" as they are in the game;
  `Profiles.seatNames` names seats for both screens so they cannot disagree.
- **Start counts the players** — "Start game · 3 players" — and is disabled,
  with the reason shown under the rows, until there are at least two.
- **The board above is the lineup**, drawn by `BoardView` in its `bare` mode
  (no name strips, no progress): seated colours with their tokens in the yard,
  empty ones greyed out as they will be in the game.
- **The ⚙ beside the title opens [Settings](#settings).**
- The screen scrolls when it does not fit, on a short phone or at a large font
  size, and is centred otherwise.

## Profiles

Each seat is a profile, a guest, a bot, or empty. A profile is a name and a
record — games played and games won — kept on the device. The Profiles button
on the setup screen lists everyone's record, best first, and renames or deletes
them; a new profile can also be made straight from a seat.

- **Records are ranked by wins**, then by fewer games taken to win them, then by
  name, and each shows its win rate. Wins come before win rate so that someone
  who won their one and only game does not rank above someone who has won ten.
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
can be told apart; the turn banner uses the same names. Along the outer edge of
each yard's coloured border is how far that player has got — "34% · 1/4 home" —
where the percentage is the steps all four tokens have walked out of the 228
(4 × 57) it takes to bring them all home, rounded down so 100% only ever means
the game is won. It sits in the border rather than under the name so the name
strips need only one line of text, which leaves more of the screen for the
board; it is white on red, green and blue, and dark on yellow.

- **The names are drawn by `BoardView`, not laid out as separate views.** The
  board is sized to whatever space it gets, so labels in their own views would
  drift away from the yards on any screen whose shape makes the height, not the
  width, the limit. Drawn in board cells, they stay over their yards at any size.
- **The turn marker follows the turn loop, not `state.current`.** A move passes
  the dice in the state before its token starts to slide, so a marker read from
  the state would jump to the next player while the last one's token was still
  moving. `GameActivity` sets it in `beginTurn`, alongside the banner and the
  die's colour, so all three change together.
- **Progress counts what is drawn, not the state.** For the same reason, the
  percentage is taken from where each token is on screen, so it ticks up as a
  token walks and down as a captured one is walked home.
- **The only control beside the banner is ⚙**, which opens
  [Settings](#settings) over the game. Sound, the reactions and the name facing
  once had a button each round the board; they are changed rarely enough that
  one way in keeps the game screen to the banner, the board and the die, but
  can matter mid-game (a phone call, the phone turned round on the table), so
  the settings open from here too rather than only from the setup screen.
- **Yards have a pocket per token**, so a yard whose tokens are out reads as
  waiting for them, not as blank.
- **An arrow in each colour** sits on the last ring square before that colour's
  home run, pointing in, to show where its tokens turn off the ring, and a dark
  arrow on each start square shows which way tokens travel. The start arrows are
  translucent black rather than white, which vanished on yellow.
- **The die carries the current player's colour as a thick border**, and before
  it is rolled shows a lightning bolt instead of a blank face and swells gently,
  so it reads as something to tap. Only a die waiting for a person swells; a bot
  rolls on its own.
- **The die lands on its result while still turning.** The tumble's faces are
  picked before it starts, with the result last and no face repeating the one
  before it, and the face changes slow down as the spin eases out. An earlier
  tumble picked each face at random and swapped in the result only when the
  animation ended, so the die looked settled on one number and then jumped to
  another.
- **Picking a token previews where each one lands**: a faint ring on the square,
  or a faint ticked ring round the token that move would capture, with a faint
  dotted line along the squares on the way. A token leaving its yard gets no
  line, having only one step to take. The marks are translucent and still on
  purpose: the pulsing tokens are what ask for a tap, and a louder preview
  competed with them. Tapping a ring plays that token, which saves hunting for
  the right one in a stack. The preview asks `Rules.victims`, the same function
  `Rules.apply` captures with, so it cannot promise a capture the rules then
  refuse.
- **A captured token walks home backwards along its own path**, square by
  square, instead of jumping to its yard, so everyone sees what happened and how
  much ground it lost. Every captured token arrives home together, in 0.3–1 s
  depending on how far the farthest had come. The capture sound and a vibration
  fire as the capturing token lands; the turn carries on once the captured
  tokens are home.
- **Captures and tokens home get a reaction.** As a capturing token lands, an
  emoji pops up in the middle of the capturing player's yard (😂, 😎) and of
  each victim's yard (😭, 😤), and a taunt ("Back to base 😂") appears in a
  bubble from the capturing player's name. A token reaching home gets a cheer
  (🥳) in its yard. Bots react the same way as people.
  - _Placement._ The emoji sit in the empty middle of each yard and the bubble
    in the gap between the name and the board, where the turn marker is, which
    it replaces while it shows. A chat panel beside the board was considered
    and rejected: on one phone passed round a table nobody types, so it would
    be the app speaking for the players; it would take space from the board;
    and a log is read after the moment, while everyone is watching the board
    as a capture happens.
  - _Graded._ A capture is `CHEAP` if the victim had come at most 6 steps,
    `BIG` from 40 steps on, where it was nearly at its home run, and `MULTI`
    if it took two or more tokens; each grade has its own emoji and taunts.
    Picks are random but never repeat the last from the same pool, so a long
    game does not keep saying the same thing.
  - _Out of the way._ Reactions last about two seconds and never hold up the
    turn. The emoji follow the name facing setting like the names, and with
    animations turned off they show still instead of popping. They can be
    turned off in Settings.
  - _From moves only._ A reaction is set off by a move as it lands, so a game
    resumed or rebuilt after a rotation gets them for every move played from
    then on, but does not replay one for a capture that happened before.
- **A win opens the results** over a dimmed screen, after a short pause so the
  winning token is seen arriving, with confetti in the four colours. Everyone is
  listed in finishing order: the winner, then by tokens home, then by ground
  covered, and players who are level share a place. Each line shows how far
  that player got and, for a profile, their record with this game counted.
  **Rematch** starts the same seats again straight away, with a new seat drawn
  to roll first; **Change players** goes back to the setup screen. Tapping the
  dimmed part puts the card away to look at the final board, and a Results
  button brings it back. A finished game restored after the screen is rebuilt
  shows the results at once, with no confetti, for the same reason it plays no
  fanfare. The confetti is skipped when the system has animations turned off.
- **The phone vibrates** on a person's six, on any capture, and three times on a
  win. It uses `View.performHapticFeedback`, which needs no permission and
  follows the system's touch-feedback setting, so it is off for anyone who has
  turned that off. Sixes a bot rolls do not vibrate, since nobody rolled them.

## Settings

Opened by the ⚙ on the setup screen and on the game screen, and kept in
`Saves` like the rest:

- **Sound**, on by default.
- **Emoji and taunts**, the reactions to a capture or a token home; on by
  default.
- **Top names face the far side**, which turns the top two names and progress
  upside down to face the players at the far end of a phone lying flat on the
  table. Off by default, because a phone passed from hand to hand is always
  read from the bottom.

Each switch saves as it is flipped, so leaving with Back loses nothing. The
game screen reads all three in `onResume`, so what is changed on the settings
page opened from a game applies as soon as it comes back.

## Sound

The game screen plays a rattle and a thud for each roll, a tap for every square
a token walks, a falling slide for a capture, a chime for a token reaching
home, a low two-note "womp" for a roll that cannot be played, and a fanfare for
the win. They can be turned off in [Settings](#settings).

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

| File                  | What it does                                                     |
| --------------------- | ---------------------------------------------------------------- |
| `Board.kt`            | Board geometry: the ring, home runs, yards, safe squares         |
| `Game.kt`             | `GameState`, its save encoding, and `Rules` — the whole variant  |
| `Profile.kt`          | Profiles: names, win records, and their save encoding            |
| `Bot.kt`              | One-ply heuristic opponent                                       |
| `BoardView.kt`        | Draws the board, tokens and seat names; turns taps into choices  |
| `Reactions.kt`        | Which emoji and taunts a capture or a token home sets off        |
| `DieView.kt`          | The die, and its tumble animation                                |
| `ConfettiView.kt`     | The confetti over the results of a won game                      |
| `Style.kt`            | Colours, buttons and panels shared by the screens                |
| `Sounds.kt`           | Synthesises and plays the sound effects                          |
| `GameActivity.kt`     | The turn loop, and the results of a won game                     |
| `SettingsActivity.kt` | The settings page: sound, reactions, name facing                 |
| `SetupActivity.kt`    | Seat picker, profile leaderboard and resume                      |
| `Saves.kt`            | Saved game, profiles, lineup and settings (preferences)          |
| `Insets.kt`           | Keeps content clear of the system bars under forced edge-to-edge |

`Game.kt`, `Board.kt`, `Profile.kt` and `Reactions.kt` touch no Android APIs,
so the rules, the profile records and the reaction picks are exercised from
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

## Improvements

Ideas worked out but not built yet.

### A 3D die, drawn on the Canvas

The die spins flat today. It could tumble as a cube without adding a
dependency, by staying in `DieView`'s `Canvas` drawing:

- Keep the cube as 8 corners and 6 faces. Each frame, rotate it, project the
  corners with a little perspective, and draw only the faces turned toward
  the viewer. A cube never hides part of itself, so nothing needs sorting.
- Draw each face as the flat face drawn now (tinted border, cream face,
  pips), stretched onto its projected corners with `Matrix.setPolyToPoly`, so
  the die keeps its look.
- Darken each face by how far it is angled away from a fixed light. That is
  most of what makes it read as 3D.
- Choose the final orientation with the result facing the viewer, and turn
  toward it from a random start with a couple of extra spins. The result is
  still fixed before the roll starts, so the die cannot land on one number
  and jump to another.

Points that need care:

- A real die layout: opposite faces add up to 7, and the faces round a corner
  run in the right direction.
- Blend rotations with quaternions. Interpolating angles one axis at a time
  can snap or wobble partway through.
- End with a slight tilt, or at 76dp the die lands looking flat.
- Decide the idle look: a flat bolt face as now, or a tilted cube that shows
  its depth.

Estimated at 200–300 lines replacing the current tumble, with no change to
the APK's size. Breathing, the landing pop and bot rolls carry over.

Rejected: an OpenGL engine (Filament, SceneView) would add several MB to a
roughly 60 KB APK for one small cube. A physics die bouncing across the
board would be much more work, and it cannot easily land on a result chosen
before the roll.

## License

GPL-3.0. See [LICENSE](LICENSE).
