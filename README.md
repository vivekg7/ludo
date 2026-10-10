# Ludo

A lightweight, fully offline Ludo game for Android, with Snakes & Ladders as a
second game. Pass-and-play with up to four people on one device, any seat
swappable for a bot, and a profile for each person that keeps their wins in
each game.

Requires **Android 12 (API 31)** or newer. The signed release APK is **80 KB**.
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

`local.properties` must point at an Android SDK with platform 37 installed.

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

### Ludo

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

### Snakes & Ladders

A 10×10 board numbered from 1 in the bottom left, back and forth up to 100 in
the top left. There are eight boards, which differ only in where the snakes
and ladders are:

| Difficulty | Boards                                | Rolls to finish, one player |
| ---------- | ------------------------------------- | --------------------------- |
| Easy       | Meadow, Garden                        | about 23                    |
| Normal     | **Classic**, Jungle, River and Temple | about 40                    |
| Hard       | Swamp, Volcano                        | about 56–59                 |

Classic is the Milton Bradley layout, less its ladder on square 1, which is
where everyone starts here, so nobody could land on it; the rest are our own.
The easy boards have more and longer ladders and a few short snakes, for small
children; the hard ones have fewer ladders and long snakes crowded near the
top. The normal boards other than Classic are there for variety alone.

`SnakesTest` works out each board's expected number of rolls exactly and holds
it to its band, as a share of Classic's: 0.45–0.75 for easy, 0.88–1.12 for
normal, 1.3–1.8 for hard. So a new or changed layout cannot quietly drift into
another difficulty, or turn into a game that is over at once or never ends. It
also refuses a ladder or snake that lies flat across most of a row, which is
drawn as a long plank over the board and reads as a big jump when it is not.

- Each player has one token, and everyone starts on square 1.
- Landing on the foot of a ladder climbs it; landing on a snake's head slides
  down to its tail.
- The first to land on **100 exactly** wins. A roll that would go past it is not
  played.
- A **6** grants another roll, and three in a row forfeits the turn, as in Ludo.
- Tokens never capture; any number may share a square.

A roll has at most one move, so there is nothing to pick: once the die lands
the token walks on its own. A bot seat just rolls without waiting for a tap.

## Setup screen

The first screen shows which game to play, the lineup for a new game and, when
there is one, that game's saved game.

- **The game is picked first**, Ludo or Snakes & Ladders, from two buttons under
  the title. Everything below follows the pick: the saved game, each seat's
  record, the leaderboard behind Profiles, and the small board above the title.
  The pick is remembered, so the screen opens on the game played last. The
  seats are shared: the same family usually plays both.
- **Each game has its own saved game.** Starting a game of one does not throw
  away a half-played game of the other. Ludo kept the save key it had before
  there was a second game, so a game in progress across that update resumes.
- **Snakes & Ladders adds a board button**, "Board: Classic", under the game
  buttons. It opens a list of the boards, easiest first and marked "easy" or
  "hard" unless normal, and **Random**, which draws a normal board for every
  new game, a rematch included. Random leaves out the easy and hard boards
  because picking one of those is a choice made for who is playing. The pick
  is remembered. The small board above the title shows the board picked, and
  with Random it steps through the normal ones, since no one board is the one
  that will be played.
  The saved game's card names the board it is on — "Jungle · Vivek 34% ·
  Bot 1 21%" — as it may not be the one picked now.

- **A saved game comes first.** It is an amber card above the lineup, listing
  its players leader first with how far each has got — "Vivek 34% · Jyoti 21%" —
  and tapping it resumes. While it exists, Start is drawn as the secondary
  button.
- **Starting over asks first.** Starting a new game deletes the save, and Start
  sits right below the card that resumes it, so a save is never lost to one
  tap: Start asks to confirm and names the game it would replace.
- **The seats are a 2×2 grid laid out like the yards** — Red and Green on top,
  Blue and Yellow below — so it is plain which seats are neighbours and which
  sit opposite. A list in colour order made the first two picks Red and Green,
  side by side on the board, when two players usually want to face each other.
  Tapping a tile picks who sits there. It shows the colour, who is seated, and
  the kind of seat: a profile's record ("won 4 of 9"), a guest, a bot, or
  empty. A taken seat is tinted in its colour; an empty one is only outlined
  and dimmed, so the players stand out at a glance. Bots are numbered "Bot 1",
  "Bot 2" as they are in the game; `Profiles.seatNames` names seats for both
  screens so they cannot disagree.
- **Start counts the players** — "Start game · 3 players" — and is disabled,
  with the reason shown under the rows, until there are at least two.
- **A small board above the title shows the lineup** as it will be played,
  drawn by `BoardView` (or `SnakesBoardView`) in its `bare` mode (no name
  strips, no progress or square numbers):
  seated colours with their tokens in the yard, empty ones greyed out as they
  will be in the game. It is kept small, since the grid below it is where
  seats are picked.
- **The launcher icon is the board cut down to what reads at 48dp**: the four
  yards with one token each, the home runs and the centre, on the app's own
  ink. An earlier icon drew every square and all sixteen tokens and blurred
  into noise at launcher size. A one-colour version serves themed icons.
- **The ⚙ beside the title opens [Settings](#settings).**
- The screen scrolls when it does not fit, on a short phone or at a large font
  size, and is centred otherwise.
- **Sideways, it is two columns**: the board and title on the left, the saved
  game, the seats and Start on the right, which stacked would need scrolling to
  reach Start. On a tablet held upright the column is capped at 560dp wide
  (`Style.readableWidth`), as are the settings page and the results card, so
  rows and buttons do not stretch across the screen.

## Profiles

Each seat is a profile, a guest, a bot, or empty. A profile is a name and a
record per game — games played and games won — kept on the device. The Profiles
button on the setup screen lists everyone's record in the picked game, best
first, and renames or deletes them; a new profile can also be made straight
from a seat.

- **Each game keeps its own record.** Ludo rewards choices and Snakes & Ladders
  is pure luck, so a win in one should not climb the other's leaderboard.

- **Records are ranked by wins**, then by fewer games taken to win them, then by
  name, and each shows its win rate. Wins come before win rate so that someone
  who won their one and only game does not rank above someone who has won ten.
- **Only a finished game counts.** When a game is won, every seated profile
  gets a game played and the winner's gets a win. An abandoned game changes
  nothing, so quitting a losing game is not recorded as a loss — and a finished
  game is credited in exactly one place, `GameActivity.creditIfWon`, called
  the moment the winning move is applied, so it cannot count twice or be lost to the app
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

The Ludo save format is at version 2, which adds the per-seat profile ids. A
version 1 save, from before profiles, still resumes with its human seats as
guests, so a game left in progress across the update is not lost.

The profiles save starts with a `#2` version line and holds both records on
each line. A save without that line is from before Snakes & Ladders, and its
one record per profile comes back as the Ludo record, so nobody's wins are
lost to the update.

The Snakes & Ladders save format is at version 2, which adds the board's key
(`classic`, `jungle`, …) as a last field. A version 1 save, from before there
were boards, resumes on Classic, the only board there was. The keys are stored
rather than the boards' order, so the list can be reordered or added to
without moving anyone's saved game to another board.

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

- **The app turns with the phone.** Every screen used to be locked to portrait,
  but Android 16 ignores that lock on tablets, foldables and Chromebooks, so it
  had to lay out sideways anyway. Sideways the board takes the full height on
  the left, and the banner, hint and die stack in a column beside it; above and
  below the board, as upright, they would leave it a strip. Turning the phone
  rebuilds the screen, which the game already survives: the state is written
  before every animation (see [Layout](#layout)), and the saved instance state
  restores it.
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
- **A roll with nothing to decide plays itself.** When only one token can move,
  or every token that can move stands on the same square — a stack, or a full
  yard on a six — it moves without a tap. Which token of a stack goes makes no
  difference to the game, so asking would only slow it down (`Rules.isForced`).
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

### Snakes & Ladders

The screen is the same — banner, die, results, settings, sounds — around a
different board, drawn by `SnakesBoardView`:

- **Laid out like the Ludo board.** The names sit in strips above and below the
  board, over the side where that colour's yard would be, sized in fifteenths
  of the board — a Ludo cell — so the two games' screens read alike.
- **Drawn, not pictures.** The squares alternate two paper tones, with 100 in
  gold. Ladders are two rails and rungs. Each snake is a wave along the line
  from its head to its tail, tapering, outlined and spotted, with eyes and a
  tongue, in colours that are none of the seats'. Nothing is an image file, as
  with the Ludo board.
- **A move walks, then climbs or slides.** The token walks square by square to
  where the roll took it, with a tap for each, then goes straight up the
  ladder, or down the snake along its own body. A ladder plays the home chime
  and a snake the capture slide and a vibration, and an emoji pops over the
  token (🚀 or 😱). The whole move is in the state before the token sets off,
  as in Ludo.
- **The current player's token has a ring**, since four tokens on a hundred
  squares take longer to find than a yard.
- **The results** list everyone by square reached, "on square 67".

## Settings

Opened by the ⚙ on the setup screen and on the game screen, and kept in
`Saves` like the rest:

- **Sound**, on by default.
- **Emoji and taunts**, the reactions to a capture or a token home, and to a
  ladder or a snake; on by default.
- **Top names face the far side**, which turns the top two names and progress
  upside down to face the players at the far end of a phone lying flat on the
  table. Off by default, because a phone passed from hand to hand is always
  read from the bottom.

Each switch saves as it is flipped, so leaving with Back loses nothing. The
game screen reads all three in `onResume`, so what is changed on the settings
page opened from a game applies as soon as it comes back.

## Backup

Moving to a new phone brings the game along: Android's device-to-device
transfer copies the preferences, which hold the profiles and their records, the
saved game, the lineup and the settings. Nothing goes to cloud backup — the app
opens no sockets and has no account, and a cloud copy restored later would
bring back a stale saved game with the records.

The rules are in `res/xml/data_extraction_rules.xml`. The older
`fullBackupContent` attribute is not set: on Android 12 and up, the minimum
here, `dataExtractionRules` replaces it.

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

| File                  | What it does                                                                 |
| --------------------- | ---------------------------------------------------------------------------- |
| `Match.kt`            | `Match`, the state every game shares, and its save encoding; `Turns`         |
| `Board.kt`            | Ludo board geometry: the ring, home runs, yards, safe squares                |
| `Game.kt`             | `GameState` and `Rules` — the whole Ludo variant                             |
| `Snakes.kt`           | `SnakesLayout`, `SnakesState` and `Snakes` — the boards and the rules        |
| `Profile.kt`          | Profiles: names, a win record per game, and their save encoding              |
| `Bot.kt`              | One-ply heuristic Ludo opponent                                              |
| `BoardView.kt`        | Draws the Ludo board, tokens and seat names; turns taps into choices         |
| `SnakesBoardView.kt`  | Draws the Snakes & Ladders board, tokens and seat names                      |
| `Reactions.kt`        | Which emoji and taunts a capture, a token home, a ladder or a snake sets off |
| `DieView.kt`          | The die, and its tumble animation                                            |
| `ConfettiView.kt`     | The confetti over the results of a won game                                  |
| `Style.kt`            | Colours, buttons and panels shared by the screens                            |
| `Sounds.kt`           | Synthesises and plays the sound effects                                      |
| `GameActivity.kt`     | The game screen for any game: the turn loop, saving, and the results         |
| `LudoActivity.kt`     | A game of Ludo on that screen: picking tokens, captures, reactions           |
| `SnakesActivity.kt`   | A game of Snakes & Ladders on that screen                                    |
| `SettingsActivity.kt` | The settings page: sound, reactions, name facing                             |
| `SetupActivity.kt`    | Game and seat picker, profile leaderboard and resume                         |
| `Saves.kt`            | A saved game per game, profiles, lineup and settings (preferences)           |
| `Insets.kt`           | Keeps content clear of the system bars under forced edge-to-edge             |

`Match.kt`, `Game.kt`, `Snakes.kt`, `Board.kt`, `Profile.kt` and `Reactions.kt`
touch no Android APIs, so both games' rules, the profile records and the
reaction picks are exercised from plain JVM unit tests in `app/src/test`.

**Adding a game** means a `Match` subclass for its state, a pure rules object,
a board view implementing `TableBoard`, and a `GameActivity` subclass that
plays a roll. `GameActivity` keeps everything the games do alike — rolling,
the six streak, passing the dice, saving, crediting a win, the results and
rematch — so the Ludo and Snakes & Ladders screens hold only what differs.
It is an abstract base class rather than one activity switching on the game,
so each game's turn code reads straight through without the other's in the
way.

Every transition in `GameActivity` goes through `beginTurn()`, which reads the
state and decides what happens next. A game restored from disk mid-turn — even
with the dice already rolled — resumes through the same path, so there is no
separate restore logic to keep in sync.

That only works if the state is never mid-way through anything. The token
slide and the pause before the next player are purely visual: a move, and the
turn it settles (`Rules.settle` or `Snakes.settle` — the same player rolls
again, or the dice pass on), are written to the state the instant the move is chosen. Leaving the roll
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
roughly 76 KB APK for one small cube. A physics die bouncing across the
board would be much more work, and it cannot easily land on a result chosen
before the roll.

## License

GPL-3.0. See [LICENSE](LICENSE).
