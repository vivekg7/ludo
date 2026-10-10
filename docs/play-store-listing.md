# Google Play listing

The text and settings of the app's Google Play listing, kept here so the next
release starts from what is live rather than from the Play Console's memory. If
you change the listing in the Console, change it here too.

As of 11 October 2026, before the first release (1.7).

## App identity

| Field        | Value                     |
| ------------ | ------------------------- |
| App name     | `Ludo + Snakes & Ladders` |
| Package name | `com.crylo.ludo`          |
| Category     | Game › Board              |

The package name is `applicationId` in `app/build.gradle.kts`. It can never
change once a build has been uploaded.

The store name says both games because "Ludo" alone sits among hundreds of
identical listings. The name under the icon on the phone stays "Ludo"
(`app_name` in `strings.xml`).

## Short description

80 characters at most.

```
Classic Ludo and Snakes & Ladders for the family. Offline, no ads, one phone.
```

## Full description

4,000 characters at most.

```
Ludo and Snakes & Ladders, the way you play them at home: everyone around one phone, passing it from hand to hand. No ads, no accounts, no internet.

LUDO
The classic board game for 2 to 4 players, with the rules most families play by:
• Roll a 6 to bring a token out of the yard
• A 6 rolls again, but three 6s in a row loses the turn
• Land on an opponent to send them back home, then roll again
• Star squares and start squares are safe
• Reach the centre with an exact roll; first to get all four tokens home wins

SNAKES & LADDERS
Climb the ladders, dodge the snakes, and land on 100 exactly to win. Eight boards, from easy to hard:
• Easy: Meadow and Garden, quick games for small children
• Normal: Classic, Jungle, River and Temple
• Hard: Swamp and Volcano, for a longer game full of setbacks
• Or let the game pick a random board each time

PLAY YOUR WAY
• 2 to 4 players on one phone; just pass it round
• Short a player? Put a bot in any seat
• Profiles for everyone, each keeping their own wins in each game
• A leaderboard for Ludo and another for Snakes & Ladders
• Leave a game halfway and pick it up later; each game keeps its own save
• Rematch with one tap when the game is over

FUN AT THE TABLE
• Emoji and taunts when a token is captured, gets home, climbs a ladder or meets a snake (turn them off in Settings if you prefer)
• Confetti and a results card for the winner
• Sound effects and gentle vibration on big moments
• Playing with the phone flat on a table? Names at the top can turn to face the players across from you

SMALL, PRIVATE AND OFFLINE
• Works with no internet, on a plane or anywhere else
• No ads, no in-app purchases, no sign-in
• Asks for no permissions and collects no data
• A tiny download that leaves your storage alone
• Works on phones and tablets, upright or sideways

Gather the family, pick your colours and roll the die.
```

Every claim in it is true of the code as of 1.7. The rules are under
[Rules](../README.md#rules) in the README, and the privacy claims match
[PRIVACY.md](../PRIVACY.md). A change to either can make the description wrong,
so check it when either changes.

It avoids "free", "best", "#1" and the like, which Play's metadata policy does
not allow. It says "a tiny download" rather than a size in KB, because Play
shows a download size that varies by device.

## Graphics

| Asset                 | Size             | Source                                          |
| --------------------- | ---------------- | ----------------------------------------------- |
| App icon              | 512 × 512 PNG    | `local/play-store/icon-512.png`                 |
| Feature graphic       | 1024 × 500 JPEG  | `local/play-store/feature-graphic-1024x500.jpg` |
| Phone screenshots (6) | 1080 × 1920 JPEG | `local/play-store/screenshots/`                 |

`local/` is gitignored, so these exist only on the machine that made them. They
are the only copies: the design file they were exported from has been deleted,
so back them up off that machine. The icon is the exception. Its source is the
vector drawables in `app/src/main/res/drawable/`, so a new 512 px icon can be
drawn from those. A changed headline or screenshot means rebuilding that panel:
1080 × 1920, one player colour as the background, a two-line Roboto Black
headline at 84 px, and the screenshot in a phone frame 640 px wide.

The screenshots, in order, each with a player colour behind it:

1. Red: "Classic Ludo. Just pass the phone." A Ludo game, a 6 rolled.
2. Green: "Up to four players, one phone." The setup screen, four seats filled.
3. Yellow: "Send them home 😈" A capture, with its taunt.
4. Blue: "Plus Snakes & Ladders. Eight boards." A ladder climb on Jungle.
5. Red: "Everyone keeps their own wins." The results card under confetti.
6. Green: "No ads. No internet. No accounts." The Snakes & Ladders setup screen.

The players in them are Asha, Rohan, Meera and Kabir, made-up names. The
positions were set by writing saves to a debug build on the emulator, and the
status bar was cleaned up with Android's demo mode (9:41, full battery).

## Policy and content

- **Privacy policy URL:**
  `https://github.com/vivekg7/ludo/blob/main/PRIVACY.md`
- **Data safety:** no data collected, no data shared. The app has no network
  access, so nothing can be.
- **Ads:** none.
- **App access:** everything is available without an account.
- **Target audience:** families, children included. See the "Children" section
  of [PRIVACY.md](../PRIVACY.md).

## Releases

Upload the App Bundle, not the APK. Archive it first with
`./scripts/archive-apk.sh --aab` (see
[Archiving a release](../README.md#archiving-a-release)), and give Play the
bundle's `.mapping.txt` as its deobfuscation file, so crash reports in the
Console are readable.

Release notes are 500 characters at most per language. The first release
describes the app. Later ones should list only what changed.

### 1.7 (8): first release

```
<en-US>
Welcome to Ludo + Snakes & Ladders, the first release on Google Play.

• Classic Ludo for 2 to 4 players on one phone: just pass it round
• Snakes & Ladders on eight boards, from easy to hard
• Short a player? Seat a bot in any seat
• Profiles keep everyone's wins in each game
• Captures and ladders get emoji and taunts (can be turned off)
• Fully offline: no ads, no accounts, no internet needed
</en-US>
```
