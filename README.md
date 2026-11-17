# NHL All Stars: an arcade hockey game for Android

A small arcade hockey game for Android, written in Kotlin and Jetpack Compose. It's a personal project,
shared as-is under the MIT license. It isn't on the Play Store and has no accounts, ads, network access or
analytics. The teams are named after real NHL clubs (Minnesota Wild, Colorado Avalanche, Edmonton Oilers,
Toronto Maple Leafs) and each skater carries a real player's surname. The crests, kits, arena and app icon
are original artwork drawn in code, and no official logos are used.

This is a fan project. It is not affiliated with, endorsed by or sponsored by the National Hockey League,
the NHL Players' Association or any team. NHL and the team names are trademarks of their owners.

## What's in it

- **Team select.** The app opens on a landscape screen with a "You" row and an "Opponent" row of big team
  cards (crest, full name, team colour; the chosen card is outlined in wheat), an Easy / Medium / Hard switch and a
  large Play button. More teams than fit scroll sideways. Your last matchup and level are remembered.
- **The game.** Three periods of 20 minutes of arcade hockey from a close-in camera that follows the puck,
  with a joystick and buttons on screen, a computer-controlled opponent and goalies, synthesised sound
  effects and vibration. Skaters and goalies are low-poly 3D figures built in code, with jersey numbers on
  their backs. Skaters glide, with stickhandling, wrist shots, slapshots, crossovers and hockey stops, and
  shots fly: the puck lifts off the ice over a shadow. Goalies drop into a butterfly and can freeze a
  save; the whistle goes and play restarts with a faceoff at the nearest zone dot, where the centres'
  faceoff skill rises from Easy to Medium to Hard. The skater you control has a green ring, other puck
  carriers a yellow one. Goals get a banner naming the scorer and a celebration: the scorer celebrates
  with one of three, cycling goal by goal (a fist pump, a knee slide, the stick raised), the team piles on,
  the beaten goalie slumps and the camera pushes in. Pause gives Resume, Sound, Vibration and Quit;
  leaving the app pauses the game.
- **Final score.** The result screen shows both teams, the score and who won (or "Tie game"), with Rematch
  and Change teams buttons. System back from the result goes to team select.
- **Teams are data.** Teams, kits and rosters live in `teams/Teams.kt`; adding a team there adds it to the
  team select screen with no other change.

## Specs and constraints

- **Android 10+** (`minSdk 29`, `targetSdk 36`). Landscape only, immersive, screen kept on during a game.
- Kotlin and Jetpack Compose (Material 3). Everything is drawn on a Compose `Canvas`; there are no image
  assets, and sounds are synthesised in code. There's no game engine.
- The game rules, AI and team data are plain Kotlin with no Android imports, so they run in JVM unit tests.
- Permission: `VIBRATE` only.
- **Debug build, sideload only.** No release signing config.

## Build and install

You need the Android SDK (platform 36, build-tools, `platform-tools`) and JDK 17+ (21 recommended). The
repo ships a Gradle wrapper.

```bash
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; any JDK 17+ works
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Turn on **Developer options** and
**USB debugging** on the phone, plug it in, accept the prompt, then:

```bash
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```bash
./gradlew testDebugUnitTest
```

Everything runs on the JVM with Robolectric (pinned to API 34, the newest it emulates).

## License

[MIT](LICENSE)
