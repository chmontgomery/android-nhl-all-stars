# North Star Hockey Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded landscape Android arcade hockey game: one player controls one skater against a computer team (Easy or Hard) for three fast periods.

**Architecture:** A pure-Kotlin fixed-step simulation (`sim`) is driven by per-skater inputs that come from touch controls (`input`) for one skater and from `ai` for the rest. A Compose `Canvas` renderer (`render`) projects the flat rink through a broadcast camera. `game` owns the frame loop; `ui` is a three-screen shell. No engine, no image assets.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.2, Gradle 8.10.2, Jetpack Compose (BOM 2024.10.01, Material 3), DataStore Preferences, JUnit 4 + Robolectric 4.13.

**Spec:** `docs/superpowers/specs/2026-11-16-north-star-hockey-design.md`

## Global Constraints

- App name "North Star Hockey"; `applicationId`/namespace `com.personal.hockey`; `rootProject.name = "NorthStarHockey"`.
- `minSdk 29`, `targetSdk 36`, `compileSdk 36`, JVM target 17. Dependency versions identical to `~/git/android-sims/app/build.gradle.kts`; add nothing else.
- Landscape only (`sensorLandscape`), immersive, screen kept on during a game. Permission: `VIBRATE` only. No network.
- No image assets: everything is drawn on Compose `Canvas`; sounds are synthesised in code.
- `sim`, `ai`, `teams` and the mapping logic in `input` import nothing from `android.*` or `androidx.*`.
- No per-tick or per-frame allocation in `sim`, `ai` or `render` hot paths (reuse objects, no `Offset`/`Pair`/lambda captures/boxed floats in loops).
- No official league or team logos, real player names, real arena names or sponsors. Never use the name or look of any existing hockey game.
- **Built to extend:** later versions add teams, alternate jerseys and named players with their own attributes. Teams, kits, rosters and the arena are data; only `teams/Teams.kt` names a team (see Task 2's extensibility rule).
- Mirror the sibling repos' patterns: read `~/git/android-hot-potatoe` and `~/git/android-sims` (read-only) for `AppGraph`, `Navigator`, `AppRoot`, `Feedback`, `SoundSynth`, `Settings`, test rules and Robolectric setup.
- Work on `main`. Commit after each task. End every commit message with
  `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- Build environment for every Gradle command:
  `export ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Test command: `./gradlew testDebugUnitTest` (single class: `--tests 'com.personal.hockey.sim.SkatingTest'`).
- **Subagent models:** each task names its model. Never run a task or review on the session's default model. Reviews: Sonnet for Sonnet tasks, Opus for Opus tasks.

## Review Focus

1. **Stuck or dropped touches.** A finger slides off a button, the pointer is cancelled, or the buttons swap (puck lost mid wind-up) while one is held: no input stays stuck on, the wind-up is cancelled, and the stick keeps working. → tests in Task 9 and Task 5.
2. **Backgrounding and stalls.** The app is backgrounded, the screen locks, or a frame takes a second: the game is paused on return and the clock never jumps. → tests in Task 12.
3. **Wedged puck.** The puck sits still against the boards or behind a net with nobody able to take it, or a goalie covers with no teammate open: play resumes by itself within a few seconds. → tests in Task 4 and Task 6.
4. **Buzzer and goal collisions.** A goal on the last tick of a period, a puck crossing the line after the buzzer, a second crossing during the goal banner, and an own goal: counted exactly once, for the right team, or not at all after the buzzer. → tests in Task 6.
5. **Screen shapes and cut-outs.** Different aspect ratios (Pixel 9, Moto G Power) and camera cut-outs in landscape: HUD and every button stay fully on screen and outside the cut-out; the stick zone still covers the left 45%. → tests in Task 9 and Task 12.

Also keep in mind a 6-year-old mashing every button at once with both thumbs anywhere: nothing crashes and cooldowns absorb it (covered by Task 5's mash test).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/, gradlew*, .gitignore, README.md
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/{values/strings.xml,values/themes.xml,drawable/ic_launcher_*.xml,mipmap-anydpi-v26/ic_launcher.xml}
app/src/main/java/com/personal/hockey/
  MainActivity.kt  HockeyApp.kt  AppGraph.kt
  teams/Teams.kt
  settings/Settings.kt
  sim/Tuning.kt  Rink.kt  Entities.kt  SimEvent.kt  HockeySim.kt
      Skating.kt  PuckPhysics.kt  Actions.kt  GoalieLogic.kt  Flow.kt  Control.kt
  ai/Difficulty.kt  Formation.kt  TeamAi.kt  Match.kt
  input/Controls.kt  ControlsLayout.kt
  render/Camera.kt  GameRenderer.kt  CanvasRenderer.kt  RinkPainter.kt  FigurePainter.kt  Crests.kt
  audio/Sfx.kt  SoundSynth.kt  Wav.kt  Feedback.kt  AndroidFeedback.kt
  game/GameViewModel.kt  GameScreen.kt  Hud.kt  ControlsOverlay.kt  PauseOverlay.kt
  ui/Navigator.kt  AppRoot.kt  theme/Theme.kt  select/TeamSelectScreen.kt  result/ResultScreen.kt
app/src/test/java/com/personal/hockey/...   (mirrors main)
```

`HockeySim` holds state and the `step()` order; the per-concern logic lives in the sibling `sim` files as
`internal` functions taking the sim, so each file stays small and separately testable.

## Shared conventions (every sim/ai/render task relies on these)

- **Coordinates:** metres. `x` along the rink in `[-30, 30]`, `y` across in `[-13, 13]`. `y = -13` is the
  boards nearest the camera. `Side.HOME` (the player) defends `x = -26` and attacks `+x`. `Side.AWAY`
  attacks `-x`. Angles in radians, `0` = `+x`.
- **Time:** one tick is `Tuning.DT = 1f / 60f` seconds.
- **Skater ids:** `0..4` HOME, `5..9` AWAY, in `Position` order `C, LW, RW, LD, RD`.
  For HOME, LW is the `+y` (far) side. AWAY is mirrored through the origin.
- **Buttons are held-state booleans.** The sim detects edges itself. Shoot fires on release.

---

### Task 1: Project scaffold and launchable shell — **model: Sonnet**

**Files:**
- Create: root Gradle files, wrapper, `.gitignore`, `README.md`, `app/build.gradle.kts`, manifest, `res/*`, `MainActivity.kt`, `HockeyApp.kt`, `AppGraph.kt`, `ui/theme/Theme.kt`
- Test: `app/src/test/java/com/personal/hockey/LaunchTest.kt`, `MainDispatcherRule.kt`

**Interfaces:**
- Produces: `class HockeyApp : Application { val graph: AppGraph }`; `class AppGraph(context: Context)` (empty for now); `@Composable fun HockeyTheme(content: @Composable () -> Unit)` (dark theme, forest green `0xFF154734` primary, red `0xFFA6192E` secondary, wheat `0xFFDDCBA4` tertiary); `MainActivity` showing a centred `Text("North Star Hockey")` inside `HockeyTheme`.

- [ ] **Step 1:** Copy `gradlew`, `gradlew.bat`, `gradle/`, `gradle.properties`, `build.gradle.kts`, `settings.gradle.kts`, `.gitignore` from `~/git/android-sims`. Set `rootProject.name = "NorthStarHockey"`. Drop the Python and Mixamo lines from `.gitignore`.
- [ ] **Step 2:** Write `app/build.gradle.kts` from `android-sims`'s: namespace/applicationId `com.personal.hockey`, `minSdk 29`, same dependencies, no `noCompress`, test heap `2g`.
- [ ] **Step 3:** Manifest: `VIBRATE`; application `.HockeyApp`, label "North Star Hockey", theme `Theme.NorthStarHockey` (`android:Theme.Material.NoActionBar`, black window background, `windowLayoutInDisplayCutoutMode=shortEdges`); `MainActivity` exported launcher with `android:screenOrientation="sensorLandscape"` and `android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"`. Adaptive launcher icon as vector XML: forest-green background, a white four-point star above a simple pine tree.
- [ ] **Step 4:** Write the failing test `LaunchTest` (Robolectric, `@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")`):

```kotlin
@Test fun launches_and_shows_title() {
    composeRule.onNodeWithText("North Star Hockey").assertIsDisplayed()
}
```
  using `createAndroidComposeRule<MainActivity>()`. Run `./gradlew testDebugUnitTest`; expected: fails to compile (no `MainActivity`).
- [ ] **Step 5:** Implement `HockeyApp`, `AppGraph`, `HockeyTheme`, `MainActivity` (`enableEdgeToEdge()`, hide system bars with `WindowInsetsControllerCompat` and `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, `setContent { HockeyTheme { … } }`).
- [ ] **Step 6:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: `BUILD SUCCESSFUL`, 1 test passed, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 7:** Write `README.md` in the sibling repos' shape (what it is, specs and constraints, build/install, tests), stating it is an original personal project. Commit: `chore: scaffold North Star Hockey app`.

---

### Task 2: Teams and settings — **model: Sonnet**

**Files:**
- Create: `sim/Position.kt`, `teams/Teams.kt`, `teams/MatchSetup.kt`, `settings/Settings.kt`; modify `AppGraph.kt`
- Test: `teams/TeamsTest.kt`, `settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces:

```kotlin
// sim/Position.kt (created here so teams can name positions; Task 3 uses it)
enum class Position { C, LW, RW, LD, RD }

// teams/ — all pure Kotlin. Colours are 0xAARRGGBB.
enum class Crest { PINE, PEAK, DROP, LEAF }
data class Kit(val id: String, val label: String,
               val jersey: Long, val trim: Long, val pants: Long, val helmet: Long, val number: Long)
/** Multipliers on the standard skater; 1 = standard. v1 uses 1 everywhere. */
data class Attributes(val speed: Float = 1f, val shot: Float = 1f)
data class Player(val number: Int, val position: Position, val name: String? = null,
                  val attributes: Attributes = Attributes())
data class Team(val id: String, val name: String, val code: String,
                val primary: Long, val secondary: Long, val trim: Long, val crest: Crest,
                val kits: List<Kit>,           // [0] = dark, [1] = white; alternates are appended later
                val skaters: List<Player>,     // exactly one per Position, in Position order
                val goalieNumber: Int = 30) {
    val darkKit: Kit; val whiteKit: Kit }
object Teams {
    val all: List<Team>                        // the single place a team is added
    fun byId(id: String): Team?                // null for unknown ids
    fun next(id: String): Team                 // the team after id in `all`, wrapping
    fun standardKits(primary: Long, secondary: Long): List<Kit>   // "dark": jersey/helmet/pants primary, trim secondary, number white;
                                                                   // "white": jersey white, trim/pants/helmet/number primary
    fun standardSkaters(): List<Player>        // C 9, LW 11, RW 16, LD 4, RD 7, no names, default attributes
}
/** Everything one game needs to know about who is playing. Built in one place so kit choice can grow later. */
data class MatchSetup(val home: Team, val away: Team, val homeKit: Kit, val awayKit: Kit, val level: Level) {
    companion object { fun of(home: Team, away: Team, level: Level): MatchSetup }   // home.darkKit, away.whiteKit
}

// settings/
enum class Level { EASY, HARD }
data class Settings(val homeId: String = "MIN", val awayId: String = "COL",
                    val level: Level = Level.EASY, val soundOn: Boolean = true, val vibrationOn: Boolean = true)
interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun setMatchup(homeId: String, awayId: String, level: Level)
    suspend fun setSoundOn(on: Boolean)
    suspend fun setVibrationOn(on: Boolean)
}
class DataStoreSettings(store: DataStore<Preferences>) : SettingsStore
class AppGraph { val settings: SettingsStore }   // built as in android-hot-potatoe's AppGraph
```
- Team values (id = code, name, primary, secondary, trim, crest), each with `standardKits(primary, secondary)` and `standardSkaters()`:
  `MIN, Minnesota Wild, 0xFF154734, 0xFFA6192E, 0xFFDDCBA4, PINE` ·
  `COL, Colorado Avalanche, 0xFF6F263D, 0xFF236192, 0xFFA2AAAD, PEAK` ·
  `EDM, Edmonton Oilers, 0xFF041E42, 0xFFFF4C00, 0xFFFFFFFF, DROP` ·
  `TOR, Toronto Maple Leafs, 0xFF00205B, 0xFFFFFFFF, 0xFF8FB8E8, LEAF`.
- **Extensibility rule for every later task:** nothing outside `teams/Teams.kt` may name a specific team or assume there are four. Code takes `Team`, `Kit`, `Player` or `MatchSetup` values. Adding a team, an alternate kit, or named players with attributes must be a data-only change in `Teams.kt` (plus a `Crest` drawing for a new crest).

- [ ] **Step 1:** Failing tests. `TeamsTest`: `all` ids are `MIN, COL, EDM, TOR` in that order; ids are unique; `byId("TOR")` round-trips and `byId("XXX")` is null; `next("TOR")` is `MIN`; every team has ≥ 2 kits with unique ids, a white-jersey `whiteKit`, and exactly one skater per `Position` in order; `MatchSetup.of(EDM, TOR, HARD)` uses `EDM.darkKit` and `TOR.whiteKit`. `SettingsStoreTest` (real DataStore in a temp folder, as in `android-hot-potatoe`'s `SettingsStoreTest`): defaults equal `Settings()`; `setMatchup("TOR", "EDM", HARD)` is read back; a stored unknown team id or level name falls back to the default; `setMatchup("MIN", "MIN", EASY)` stores away as `Teams.next("MIN").id` (`COL`).
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement. Unknown stored values fall back with `runCatching { valueOf(it) }`.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest`; expected: all pass.
- [ ] **Step 5:** Commit: `feat: teams catalog and settings store`.

---

### Task 3: Sim foundation — rink, entities, skating, collisions — **model: Opus**

**Files:**
- Create: `sim/Tuning.kt`, `sim/Rink.kt`, `sim/Entities.kt`, `sim/SimEvent.kt`, `sim/HockeySim.kt`, `sim/Skating.kt`
- Test: `sim/RinkTest.kt`, `sim/SkatingTest.kt`

**Interfaces:**
- Produces:

```kotlin
object Tuning {            // every gameplay number lives here as a const or var-free val
    const val DT = 1f / 60f
    const val SKATER_R = 0.45f;  const val MAX_SPEED = 9f;  const val ACCEL = 22f
    const val GLIDE_DRAG = 1.2f  // per second, applied when stick is centred
    const val TURN_RATE = 7f     // rad/s that velocity direction can change at top speed
    const val CARRY_SPEED = 0.92f
    // later tasks append their own constants
}
object Rink {
    const val HALF_L = 30f; const val HALF_W = 13f; const val CORNER_R = 8.5f
    const val GOAL_X = 26f; const val GOAL_HALF_W = 1.1f; const val NET_DEPTH = 1.1f
    const val BLUE_X = 7.6f; const val CREASE_R = 1.8f
    /** True if a circle of radius r at (x, y) is fully inside the boards, corners included. */
    fun inside(x: Float, y: Float, r: Float): Boolean
    /** Pushes (x, y) back inside for radius r. Writes the result and the inward unit normal
     *  into out = [x, y, nx, ny]; returns false if it was already inside. */
    fun clamp(x: Float, y: Float, r: Float, out: FloatArray): Boolean
}
enum class Side { HOME, AWAY; val dir: Float /* +1 HOME, -1 AWAY */; val other: Side; val index: Int }
// Position already exists (Task 2, sim/Position.kt)
enum class Act { NONE, WINDUP, DEKE, POKE, BODY, DOWN, RECOVER }
class SkaterInput { var moveX = 0f; var moveY = 0f   // stick, length 0..1
                    var shoot = false; var pass = false; var deke = false; var poke = false; var body = false
                    fun clear() }
class Skater(val id: Int, val side: Side, val pos: Position) {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var facing = 0f
    var act = Act.NONE; var actT = 0f           // seconds left in the current act
    var windup = 0f                             // seconds held so far
    var dekeCd = 0f; var pokeCd = 0f; var pickupLock = 0f
    var speedScale = 1f                         // difficulty × player attribute, 1 = standard
    var shotScale = 1f                          // player attribute; multiplies shot speed (Task 5)
    var stride = 0f                             // metres skated, for animation
    val input = SkaterInput()
    internal val prev = SkaterInput()           // last tick's buttons, for edge detection
}
sealed interface SimEvent   // objects/classes are added by later tasks
class HockeySim(val random: Random) {
    val skaters: List<Skater>      // 10, ids as in conventions
    val events: ArrayList<SimEvent>   // cleared at the start of every step()
    fun step()                     // Task 3: skating + collisions only
    fun skatersOf(side: Side): List<Skater>   // precomputed sublists
}
```
- `Skating.kt`: `internal fun HockeySim.stepSkaters()` — for each skater with `act` in `NONE`/`WINDUP`: accelerate toward the stick vector, top speed `MAX_SPEED * speedScale * stickLength` (× `CARRY_SPEED` if carrying, from Task 4; × 0.5 during `WINDUP`); velocity direction turns at most `TURN_RATE * DT` scaled down with speed; `GLIDE_DRAG` when the stick is centred (dead zone 0.1). `facing` follows velocity above 0.5 m/s. Then integrate, resolve skater–skater overlap by pushing both apart equally, and `Rink.clamp` each skater (remove the velocity component into the boards).

- [ ] **Step 1:** Failing tests.

```kotlin
// RinkTest
@Test fun centre_is_inside() = assertTrue(Rink.inside(0f, 0f, 0.45f))
@Test fun corner_square_is_outside() = assertFalse(Rink.inside(29.5f, 12.5f, 0.1f))
@Test fun clamp_pulls_back_from_side_boards() {
    val out = FloatArray(4); assertTrue(Rink.clamp(0f, 14f, 0.5f, out))
    assertEquals(12.5f, out[1], 1e-3f); assertEquals(-1f, out[3], 1e-3f)
}
@Test fun clamp_in_corner_returns_point_inside() { /* (29f,12f,0.45f) → Rink.inside(out[0], out[1], 0.449f) */ }

// SkatingTest (helper: sim(), run(seconds) { set inputs })
@Test fun reaches_but_never_exceeds_top_speed()      // full stick +x for 3s → speed in 8.5..9.0
@Test fun half_stick_is_half_speed()                 // stick 0.5 → speed ≈ 4.5 ± 0.3
@Test fun speed_scale_caps_speed()                   // speedScale 0.75 → ≤ 6.75
@Test fun glides_then_stops()                        // release: still > 3 m/s after 0.5s, < 0.5 m/s after 6s
@Test fun cannot_reverse_instantly()                 // at top speed +x, stick -x: vx still > 0 after 0.1s
@Test fun stops_at_boards_and_stays_inside()         // skate +y for 5s → Rink.inside(x, y, SKATER_R - 1e-3f), vy ≈ 0
@Test fun skaters_do_not_overlap()                   // two skaters driven at each other for 3s → distance ≥ 2*SKATER_R - 0.02
@Test fun step_is_deterministic()                    // same seed + inputs → identical positions
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement the files above. Initial skater positions may be anywhere inside the rink (Task 6 sets faceoff spots).
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest --tests 'com.personal.hockey.sim.*'`; expected: pass.
- [ ] **Step 5:** Commit: `feat(sim): rink geometry and skating`.

---

### Task 4: Puck physics and possession — **model: Opus**

**Files:**
- Create: `sim/PuckPhysics.kt`; modify `Entities.kt`, `HockeySim.kt`, `Tuning.kt`, `SimEvent.kt`
- Test: `sim/PuckTest.kt`

**Interfaces:**
- Produces:

```kotlin
class Puck { var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
             var carrier: Skater? = null
             var passTarget: Skater? = null      // set by a pass until someone touches the puck
             var lastSide: Side? = null          // side that last carried or shot it
             var stillT = 0f }                   // seconds loose and slower than STUCK_SPEED
class HockeySim { val puck: Puck
                  fun giveTo(s: Skater)          // attach, clears passTarget, emits Pickup
                  fun knockLoose(vx: Float, vy: Float, lock: Float)  // detach; old carrier gets pickupLock = lock
                }
// SimEvent additions
data object Boards : SimEvent
data class Pickup(val side: Side) : SimEvent
// Tuning additions
const val PUCK_DRAG = 0.35f      // per second
const val BOARD_BOUNCE = 0.6f
const val STICK_REACH = 1.2f
const val CATCH_SPEED = 14f      // loose pucks faster than this deflect
const val PASS_CATCH_SPEED = 30f // for puck.passTarget only
const val CARRY_OFFSET = 0.9f    // puck sits this far ahead of the carrier along facing
const val PICKUP_LOCK = 0.5f
const val STUCK_SPEED = 0.3f;  const val STUCK_TIME = 3f
```
- `internal fun HockeySim.stepPuck()`: carried → puck placed at `carrier + CARRY_OFFSET * facing`, clamped inside the rink. Loose → drag, integrate in two half-steps (so a 40 m/s shot cannot tunnel), bounce off boards (`Rink.clamp`, reflect with `BOARD_BOUNCE`, emit `Boards` when impact speed > 3), bounce off the outside of each net (back and both sides are solid; the mouth facing centre ice is open), then pickup: the nearest eligible skater (`act` not `DOWN`/`RECOVER`, `pickupLock <= 0`) within `STICK_REACH` takes it if puck speed is below `CATCH_SPEED` (`PASS_CATCH_SPEED` for `passTarget`); faster pucks lose 30% speed and deflect by a small random angle.
- **Stuck guard:** if the puck is loose and slower than `STUCK_SPEED` for `STUCK_TIME` with no eligible skater within 3 m, give it a 6 m/s push toward centre ice and reset `stillT`.
- `step()` order from now on: clear events → skaters → puck.

- [ ] **Step 1:** Failing tests (`PuckTest`):

```kotlin
@Test fun slides_and_slows()                    // 10 m/s along +x from centre: moved 8..10 m after 1s, speed < 10
@Test fun bounces_off_side_boards_with_loss()   // vy=+20 from centre → after 2s vy < 0 and |vy| < 20*0.6+0.5; one Boards event
@Test fun never_leaves_rink()                   // 500 seeded random shots at 40 m/s, 4s each → Rink.inside(x, y, 0f) every tick
@Test fun fast_shot_does_not_tunnel_through_net_back()  // puck behind net at (28.5,0) fired at -40 m/s x → never has x in 24.9..26 with |y|<1.1 before bouncing; never counted "in net"
@Test fun slow_loose_puck_is_picked_up()        // puck 1 m from a skater at 5 m/s → carrier == skater, Pickup(HOME)
@Test fun fast_loose_puck_deflects()            // 20 m/s past a skater → carrier == null, speed dropped
@Test fun pass_target_catches_fast_puck()       // passTarget set, 25 m/s → carrier == target, passTarget == null
@Test fun carried_puck_stays_ahead_of_carrier() // after skating 1s, puck within 0.05 of carrier + 0.9*facing
@Test fun carrier_is_slower()                   // top speed with puck ≈ 0.92 * without, ±0.2
@Test fun locked_skater_cannot_repick()         // knockLoose(.., lock=0.5): old carrier doesn't regain for 0.5s even if on top of it
@Test fun wedged_puck_is_freed()                // still puck at (0, 12.9), all skaters > 10 m away → within 3.5s speed > 3 and moving toward y=0
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run sim tests; expected: pass.
- [ ] **Step 5:** Commit: `feat(sim): puck physics and possession`.

---

### Task 5: Actions — shoot, pass, deke, poke, body — **model: Opus**

**Files:**
- Create: `sim/Actions.kt`; modify `HockeySim.kt`, `Tuning.kt`, `SimEvent.kt`
- Test: `sim/ActionsTest.kt`

**Interfaces:**
- Produces:

```kotlin
class HockeySim { /** Teammate a pass from s would go to right now, or null. Used by Actions and the renderer. */
                  fun passTargetFor(s: Skater): Skater? }
data class Shot(val side: Side, val power: Float) : SimEvent      // power 0..1
data class Pass(val side: Side) : SimEvent
data class Poke(val hit: Boolean) : SimEvent
data class Hit(val victimId: Int) : SimEvent
// Tuning
const val WINDUP_MIN = 0.15f; const val WINDUP_MAX = 0.8f
const val SHOT_SPEED_MIN = 22f; const val SHOT_SPEED_MAX = 38f
const val SHOT_SPREAD = 0.05f      // rad at tap, ×2 at full power
const val PASS_SPEED = 20f
const val DEKE_TIME = 0.35f; const val DEKE_SPEED = 9f; const val DEKE_CD = 1f
const val POKE_TIME = 0.25f; const val POKE_REACH = 1.8f; const val POKE_ARC = 0.9f /* rad half-angle */; const val POKE_CD = 0.6f
const val BODY_TIME = 0.3f; const val BODY_SPEED = 11f; const val BODY_REACH = 1.1f
const val DOWN_TIME = 0.6f; const val RECOVER_TIME = 0.5f; const val RECOVER_SPEED = 0.4f
```
- `internal fun HockeySim.stepActions()` runs before `stepSkaters()`. Edge = `input.x && !prev.x`; copy `input` buttons to `prev` at the end. Decrement `actT`, `dekeCd`, `pokeCd`, `pickupLock`.
- **Shoot** (carrier only): rising edge → `WINDUP`; each tick held adds to `windup`; on release, or at `WINDUP_MAX + 0.2`, fire: `power = ((windup - WINDUP_MIN) / (WINDUP_MAX - WINDUP_MIN)).coerceIn(0f, 1f)`, speed lerps `SHOT_SPEED_MIN..MAX` then × `shotScale`, aimed at `(side.dir * GOAL_X, aimY)` where `aimY = sign(moveY) * 0.75f * GOAL_HALF_W` if `|moveY| > 0.3`, else the side of the net farther from that net's goalie (`+0.75 * GOAL_HALF_W` until Task 6 adds goalies); plus uniform spread `±SHOT_SPREAD * (1 + power)`. `knockLoose` with `lock = PICKUP_LOCK`, `lastSide = side`, emit `Shot`. Losing the puck, or `DOWN`, cancels the wind-up without firing.
- **Pass** (carrier, rising edge): `passTargetFor` scores each teammate: with stick pushed (`> 0.3`), lowest angle between stick and direction-to-teammate wins, ties (within 0.2 rad) to the nearer; with stick centred, the teammate furthest toward the attacking end whose passing line has no opponent within 1.5 m; null if no teammates (never the case in play). Fire at `PASS_SPEED` toward where the target will be when the puck arrives (one-step lead: target position + target velocity × distance / `PASS_SPEED`). Set `puck.passTarget`, emit `Pass`.
- **Deke** (carrier, rising edge, `dekeCd <= 0`): `DEKE` for `DEKE_TIME`; velocity set to `DEKE_SPEED` along the stick direction, or 90° to the left of `facing` if centred; sets `dekeCd = DEKE_CD`.
- **Poke** (non-carrier, rising edge, `pokeCd <= 0`, act `NONE`): `POKE` for `POKE_TIME`. On the first tick only: if the puck is within `POKE_REACH` and `POKE_ARC` of `facing`, and its carrier (if any) is an opponent not in `DEKE`: `knockLoose` at 7 m/s in the poker's facing ± 0.5 rad random, `lock = PICKUP_LOCK`, emit `Poke(true)`; else `Poke(false)`. A loose puck in range is poked the same way.
- **Body** (non-carrier, rising edge, act `NONE`): `BODY` for `BODY_TIME` moving at `BODY_SPEED` along the stick (or `facing`). Each tick: the first opposing skater within `BODY_REACH` not in `DEKE` or `DOWN` goes `DOWN` for `DOWN_TIME` with velocity pushed 4 m/s along the hit; if they carried the puck, `knockLoose` at 5 m/s along the hit, `lock = DOWN_TIME + 0.2`; emit `Hit(id)`; the checker returns to `NONE`. If `BODY` ends with no hit → `RECOVER` for `RECOVER_TIME` (top speed × `RECOVER_SPEED`).
- `DOWN` skaters ignore input and slide with drag 4/s. Buttons that do not apply to the current state (poke while carrying, shoot without the puck) do nothing.

- [ ] **Step 1:** Failing tests (`ActionsTest`; helper places a carrier and opponents):

```kotlin
@Test fun tap_shot_is_min_speed_full_hold_is_max()     // hold 2 ticks → 22..24 m/s; hold 0.8s → 37..38 m/s; Shot event power 0 / 1
@Test fun shot_scale_multiplies_shot_speed()             // shotScale 1.2, full hold → 44..46 m/s
@Test fun shot_fires_on_release_not_on_press()
@Test fun held_too_long_fires_itself()                 // hold 2s → exactly one Shot, at ≤ 1.0s + 1 tick
@Test fun shot_heads_for_the_attacked_net()            // HOME from (10,5): direction within 0.15 rad of (26, ±0.825)
@Test fun stick_up_and_down_pick_opposite_sides()      // moveY=+1 → aimed y>0 ; moveY=-1 → y<0
@Test fun losing_puck_cancels_windup()                 // poke lands during windup → no Shot ever, windup == 0, act != WINDUP
@Test fun pass_goes_to_teammate_in_stick_direction()   // teammates at +y and -y; stick +y → passTarget is the +y one
@Test fun centred_pass_picks_open_forward_teammate()
@Test fun pass_leads_a_moving_receiver_and_is_caught() // receiver skating at 6 m/s → becomes carrier within 1.5s
@Test fun deke_moves_sideways_and_has_cooldown()       // second deke within 1s does nothing
@Test fun poke_knocks_puck_loose()                     // opponent 1.5 m in front of facing → carrier null, Poke(true)
@Test fun poke_misses_out_of_arc_and_out_of_reach()    // Poke(false), carrier unchanged
@Test fun poke_misses_a_deking_carrier()
@Test fun poke_has_cooldown()
@Test fun body_knocks_carrier_down_and_frees_puck()    // victim act == DOWN for 0.6s, Hit(victim.id), victim can't repick for 0.8s
@Test fun body_misses_a_deking_carrier_then_recovers() // checker act == RECOVER, top speed ≤ 0.4*9 during it
@Test fun body_can_hit_a_non_carrier()
@Test fun buttons_that_do_not_apply_do_nothing()
@Test fun mashing_everything_never_breaks_invariants() // 60s, seeded: every skater gets random buttons+stick each tick;
                                                       // all positions finite and inside the rink, at most one carrier,
                                                       // every actT/cooldown ≥ -DT
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement. `step()` order: clear events → actions → skaters → puck.
- [ ] **Step 4:** Run sim tests; expected: pass.
- [ ] **Step 5:** Commit: `feat(sim): shoot, pass, deke, poke and body check`.

---

### Task 6: Goalies, goals, game flow and control switching — **model: Opus**

**Files:**
- Create: `sim/GoalieLogic.kt`, `sim/Flow.kt`, `sim/Control.kt`; modify `Entities.kt`, `HockeySim.kt`, `Actions.kt` (shot aim uses goalie), `Tuning.kt`, `SimEvent.kt`
- Test: `sim/GoalieTest.kt`, `sim/FlowTest.kt`, `sim/ControlTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class GoalieSkill(val reactionS: Float, val speed: Float, val saveBase: Float)   // saveBase 0..1
class Goalie(val side: Side, val skill: GoalieSkill) { var x = 0f; var y = 0f; var coverT = 0f; var holding = false
                                                       var saveFlash = 0f /* s since last save, for animation */ }
enum class Phase { FACEOFF, PLAY, GOAL, BREAK, FINAL }
class HockeySim(random: Random, homeGoalie: GoalieSkill, awayGoalie: GoalieSkill) {
    val goalies: List<Goalie>          // index = Side.index
    var phase: Phase; var phaseT: Float            // seconds left in FACEOFF / GOAL / BREAK
    var period: Int                    // 1..3
    var clockS: Float                  // real seconds left in the period, starts at PERIOD_SECONDS
    val score: IntArray                // [home, away]
    var lastGoalSide: Side?
    var controlledId: Int              // HOME skater the human drives; -1 disables (AI-only games)
    var humanControlled: Boolean       // set false for AI-only games; Control then leaves controlledId = -1
    val displaySeconds: Int            // 0..1200, = ceil(clockS * 10)
    fun clockText(): String            // "20:00" … "0:00" → format m:ss
}
data class Save(val side: Side) : SimEvent           // side = the goalie's side
data object Cover : SimEvent
data class Goal(val scorer: Side) : SimEvent
data object FaceoffDrop : SimEvent
data class PeriodEnd(val period: Int) : SimEvent
data object GameEnd : SimEvent
// Tuning
const val PERIOD_SECONDS = 120f; const val PERIODS = 3
const val FACEOFF_TIME = 1.5f; const val GOAL_TIME = 2.5f; const val BREAK_TIME = 2.5f
const val GOALIE_REACH = 0.75f; const val GOALIE_DEPTH = 1.0f   // stands this far out from the goal line
const val COVER_TIME = 1.0f
const val SWITCH_MARGIN = 1.5f; const val SWITCH_TIME = 0.25f
```
- **Goalie (`stepGoalies`)**: stands on the line between the puck and the net centre, `GOALIE_DEPTH` out from the goal line, `y` limited to `±GOAL_HALF_W`. Tracks a puck position that is `reactionS` old (ring buffer of puck positions) and moves toward it at most `skill.speed` m/s. A loose puck moving toward the net that comes within `GOALIE_REACH` of the goalie is saved with probability `p = (saveBase + 0.25 * (1 - shotSpeed / SHOT_SPEED_MAX) + 0.02 * distanceShotTravelled.coerceAtMost(10f)).coerceIn(0.05f, 0.97f)` (record the shot origin in `Puck` at shot time; non-shots use speed only). Saved: emit `Save`; if the puck's speed was below 25 m/s the goalie covers (`holding = true`, puck hidden at the goalie, `coverT = COVER_TIME`, emit `Cover`), else it rebounds away from the net at 30% speed with a random angle of ±1 rad. Not saved: the puck passes through. After `coverT`, the goalie passes at `PASS_SPEED` to the teammate with the clearest line (no opponent within 2 m of the line), or the nearest defender if none, and sets `passTarget`. Skaters are pushed out of the crease half-circle in front of an opposing goalie... keep it simple: skaters are pushed out of a 0.8 m circle around either goalie.
- **Goal**: during `PLAY`, a loose or carried puck whose path this tick crosses `x = ±GOAL_X` with `|y| < GOAL_HALF_W` moving into the net scores for the side attacking that net, whoever touched it last (own goals count). `score[scorer.index]++`, `lastGoalSide`, emit `Goal`, `phase = GOAL`, `phaseT = GOAL_TIME`.
- **Flow (`stepFlow`)**: `FACEOFF`: skaters are placed at faceoff spots (centres 1.2 m either side of centre ice facing each other, wings 6 m back and ±7 m wide on the centre line side, defenders 12 m back ±5 m), puck at the origin, nobody moves, clock stopped; when `phaseT` hits 0 emit `FaceoffDrop`, give the puck a random velocity ≤ 1.5 m/s, `phase = PLAY`. `PLAY`: `clockS -= DT`; at `<= 0`: clamp to 0, emit `PeriodEnd(period)`, then `period < 3` → `BREAK` for `BREAK_TIME`, else emit `GameEnd`, `FINAL`. `GOAL` → `FACEOFF` after `phaseT`. `BREAK` → `period++`, `clockS = PERIOD_SECONDS`, `FACEOFF`. In `GOAL`, `BREAK` and `FINAL`, skaters and puck keep sliding with inputs ignored, and nothing can score. Goal detection runs before the clock check within a tick, so a goal on the final tick counts and the period then ends after the goal banner.
- **Control (`stepControl`)**, only when `humanControlled`: if a HOME skater carries → that skater. Else candidate = HOME skater nearest the puck (for a pass in flight to a HOME `passTarget`, the target; set immediately when the pass is made). Switch only if the current skater's `act` is `NONE` and the candidate has been nearer by more than `SWITCH_MARGIN` for `SWITCH_TIME`. On each `FACEOFF` start → the HOME centre (id 0).
- `step()` order: clear events → flow → (if PLAY) actions → skaters → goalies → puck → goal check → control.

- [ ] **Step 1:** Failing tests.

```kotlin
// GoalieTest (skill = GoalieSkill(0.1f, 6f, 0.7f) unless stated)
@Test fun goalie_stays_between_puck_and_net()          // puck moved to (15, 8): after 2s goalie.y > 0.3, x ≈ 25
@Test fun perfect_goalie_stops_centre_shots()          // saveBase 1.0: 50 seeded straight shots → 0 goals, ≥ 45 Save events
@Test fun hopeless_goalie_lets_most_in()               // saveBase 0.0, speed 0: 50 max-power shots from 6 m → ≥ 35 goals
@Test fun shot_outside_reach_scores()                  // goalie frozen at y=-1, shot along y=+0.9 → Goal(HOME)
@Test fun wide_shot_is_not_a_goal()                    // aimed at y = 2.0 → no Goal, puck bounces off end boards
@Test fun covered_puck_comes_back_into_play()          // slow shot → Cover; within 1.2s a pass leaves; carrier becomes a teammate within 3s
@Test fun cover_with_every_teammate_marked_still_releases()   // opponents on every lane → puck released by 1.2s anyway
@Test fun puck_from_behind_cannot_score()              // puck sent into the back of the net → no Goal

// FlowTest
@Test fun starts_in_faceoff_then_drops()               // phase FACEOFF, clock 120; after 1.5s: FaceoffDrop, PLAY
@Test fun clock_only_runs_in_play()
@Test fun clock_text()                                 // clockS 120 → "20:00"; 60 → "10:00"; 0.05 → "0:01"; 0 → "0:00"
@Test fun goal_scores_once_and_resets_to_faceoff()     // score [1,0], Goal(HOME); during 2.5s banner a second crossing adds nothing; then FACEOFF with skaters on spots
@Test fun own_goal_counts_for_the_other_side()         // HOME carrier skates the puck into x=-26 net → Goal(AWAY)
@Test fun carried_puck_over_the_line_scores()
@Test fun goal_on_last_tick_counts()                   // clockS = DT, puck crossing this tick → score 1, then period advances after banner
@Test fun puck_crossing_after_buzzer_does_not_count()  // crossing one tick after clock hits 0 → score unchanged
@Test fun three_periods_then_final()                   // run 3 × (120 + pauses): PeriodEnd(1), (2), (3), GameEnd once; phase FINAL; step() afterwards is a no-op for score/clock
@Test fun tie_stays_a_tie()                            // 0–0 after period 3 → FINAL

// ControlTest
@Test fun carrier_is_controlled()
@Test fun passing_switches_to_the_receiver_at_once()
@Test fun on_defence_nearest_home_skater_is_controlled()
@Test fun no_flicker_between_two_equidistant_skaters() // puck oscillating ±0.5 m between two skaters for 5s → ≤ 1 switch
@Test fun no_switch_during_an_action()                 // controlled skater mid-BODY keeps control until NONE
@Test fun ai_only_game_has_no_controlled_skater()      // humanControlled=false → controlledId == -1 always
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement. Update the centred-stick shot aim in `Actions.kt` to the side farther from the defending goalie's `y`.
- [ ] **Step 4:** Run all sim tests; expected: pass (earlier tests construct the sim with a default `GoalieSkill(0.1f, 6f, 0.7f)` — give the constructor those defaults and set `phase = PLAY` via a test helper `HockeySim.startPlayForTest()` so Task 3–5 tests are unaffected).
- [ ] **Step 5:** Commit: `feat(sim): goalies, goals, periods and control switching`.

---

### Task 7: Computer players and difficulty — **model: Opus**

**Files:**
- Create: `ai/Difficulty.kt`, `ai/Formation.kt`, `ai/TeamAi.kt`, `ai/Match.kt`
- Test: `ai/FormationTest.kt`, `ai/TeamAiTest.kt`, `ai/MatchTest.kt`

**Interfaces:**
- Consumes: everything `HockeySim` exposes; `Level` from `settings`.
- Produces:

```kotlin
data class Difficulty(
    val speedScale: Float, val reactionS: Float,
    val pokePerSecond: Float,      // chance rate to attempt a poke when in range
    val hitPerSecond: Float,
    val timingError: Float,        // 0 = acts only when it will land, 1 = acts at random moments in range
    val passUnderPressure: Float,  // 0..1 chance to pass when a checker is within 2.5 m
    val shotHold: Float,           // seconds of wind-up it uses
    val aimsForCorners: Boolean,
    val shootRange: Float,         // metres from the net at which it shoots
    val goalie: GoalieSkill,
) { companion object {
    val EASY = Difficulty(0.75f, 0.6f, 0.15f, 0.05f, 0.8f, 0.15f, 0.15f, false, 9f, GoalieSkill(0.35f, 3.0f, 0.35f))
    val HARD = Difficulty(1.0f, 0.15f, 1.6f, 0.7f, 0.15f, 0.8f, 0.6f, true, 14f, GoalieSkill(0.08f, 6.5f, 0.72f))
    /** The player's teammates and goalie, at both levels. */
    val TEAMMATE = Difficulty(1.0f, 0.15f, 0.6f, 0.2f, 0.3f, 0.8f, 0.6f, true, 14f, GoalieSkill(0.08f, 6.5f, 0.72f))
    fun of(level: Level): Difficulty } }

object Formation { /** Home spot for a position given puck location and who has it. Writes [x, y] to out. */
    fun spot(side: Side, pos: Position, puckX: Float, puckY: Float, possession: Side?, out: FloatArray) }

class TeamAi(val side: Side, val difficulty: Difficulty, random: Random) {
    /** Writes input for every skater on [side] except skipId (the human's skater, or -1). */
    fun think(sim: HockeySim, skipId: Int) }

class Match(level: Level, seed: Long, humanControlled: Boolean = true,
            homeDifficulty: Difficulty = Difficulty.TEAMMATE, awayDifficulty: Difficulty = Difficulty.of(level),
            homeSkaters: List<Player> = Teams.standardSkaters(), awaySkaters: List<Player> = Teams.standardSkaters()) {
    val sim: HockeySim                 // home goalie = homeDifficulty.goalie, away = awayDifficulty.goalie;
                                       // skater.speedScale = side difficulty.speedScale × player.attributes.speed,
                                       // skater.shotScale = player.attributes.shot (player matched by Position)
    /** One tick. Copies [human] into the controlled skater (if any), runs both AIs, steps the sim. */
    fun tick(human: SkaterInput?) }
```
- **Formation:** attacking (own possession): C 6 m ahead of the puck toward the net in the slot, wings wide (±8 m) and ahead, defenders at the offensive blue line ±6 m. Defending: defenders between puck and own net near the slot, wings covering the points, C shadowing the puck 4 m goal-side. Loose: midway between the two. All spots clamped inside the rink and out of the creases.
- **TeamAi per skater** (decisions refresh every `reactionS`; between refreshes the last decision is held, which is what makes Easy slow):
  - *Carrier:* skate toward the net, steering away from the nearest opponent within 3 m; shoot (hold `shotHold`, corner or centre per `aimsForCorners`) when within `shootRange` of the net with a line not blocked by a skater within 1 m; when an opponent is within 2.5 m, pass with chance `passUnderPressure` to `sim.passTargetFor`; deke on Hard only, when a checker is within 1.8 m and closing.
  - *No puck, opponent carrying:* the skater nearest the carrier chases; within poke/body range it attempts at `pokePerSecond`/`hitPerSecond`, and with probability `1 - timingError` only when the geometry says it would land. Everyone else goes to `Formation.spot`.
  - *Loose puck:* the nearest skater to the puck skates at where the puck will be in 0.3s; others to their spots.
  - *Human's team:* identical logic with `TEAMMATE`, except that a teammate never chases a loose puck or a carrier while the human's skater is within 4 m of the puck, and with `skipId >= 0` a teammate who somehow carries the puck is never the case (control switches), so no special handling.
- AI-only games (`humanControlled = false`) run the full carrier logic for both sides.

- [ ] **Step 1:** Failing tests.

```kotlin
// FormationTest
@Test fun all_spots_are_inside_the_rink()              // sweep puck over a grid × possession × side × position → Rink.inside(x, y, 0.45f)
@Test fun defenders_stay_goal_side_when_defending()    // HOME defending, puck at (-15, 3): LD/RD x < -15
@Test fun formation_is_mirrored_for_away()             // spot(AWAY, pos, -px, -py, poss.other) == -spot(HOME, pos, px, py, poss)

// TeamAiTest
@Test fun nearest_skater_goes_for_a_loose_puck()       // after 2s exactly one AWAY skater is within 2 m of the puck or carries it
@Test fun carrier_heads_for_the_net_and_shoots()       // AWAY carrier at (0,0), no HOME skaters near: a Shot(AWAY) within 6s
@Test fun easy_reacts_later_than_hard()                // puck suddenly moved: ticks until chaser's stick points at it: easy > hard + 15
@Test fun easy_skaters_are_slower()                    // max AWAY speed over 20s ≤ 0.75*9 + 0.1
@Test fun teammates_leave_the_puck_to_the_human()      // human within 3 m of loose puck: no HOME AI skater closes inside 2 m in 2s
@Test fun ai_never_writes_the_human_skater()           // skipId's input object untouched by think()

// MatchTest (headless, AI vs AI, humanControlled = false)
@Test fun a_full_game_finishes()                       // tick until FINAL: reached in ≤ 3*120*60 + 3000 ticks; GameEnd once
@Test fun puck_stays_in_the_rink_and_moves()           // every tick inside; never loose-and-still for > 4s
@Test fun scoring_is_in_a_sane_range()                 // 10 seeds, HARD both sides: mean total goals between 2 and 14
@Test fun hard_beats_easy()                            // 20 seeds, home=HARD away=EASY: home wins ≥ 15, home goals > 2× away goals
@Test fun same_seed_same_game()                        // identical final scores and tick count
@Test fun player_attributes_reach_the_sim()            // awaySkaters with C speed 1.1 / shot 1.2 on EASY → sim.skaters[5].speedScale ≈ 0.825, shotScale == 1.2f
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement. Tune `Difficulty` and `Tuning` numbers as needed to satisfy `MatchTest`; keep the ordering Easy < Hard on every field. Record any changed spec starting values in a comment next to the constant.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest`; expected: all pass. `MatchTest` should take under 60s; if not, reduce seeds proportionally, not game length.
- [ ] **Step 5:** Commit: `feat(ai): computer players with Easy and Hard levels`.

---

### Task 8: Balance check with scripted players — **model: Opus**

**Files:**
- Create: `app/src/test/java/com/personal/hockey/ai/Bots.kt`, `ai/BalanceTest.kt`; modify `ai/Difficulty.kt`, `sim/Tuning.kt` as tuning requires

**Interfaces:**
- Consumes: `Match`, `SkaterInput`, `HockeySim`.
- Produces (test code only): `class KidBot(random)` and `class SkilledBot(random)` each with `fun input(sim: HockeySim): SkaterInput`.
  - `KidBot`: stick points at the opponent net when carrying, at the puck otherwise, with ±0.5 rad of wobble refreshed every 0.5s; shoots (random hold 0–0.5s) whenever carrying and within 15 m of the net; on defence presses Poke or Body at random about twice a second; never passes or dekes.
  - `SkilledBot`: carries toward the net avoiding the nearest opponent, dekes when a checker is within 1.8 m, passes when pressured, shoots with full wind-up at the open side from within 10 m; on defence chases the carrier and pokes when in range and arc.

- [ ] **Step 1:** Write `BalanceTest`:

```kotlin
@Test fun kid_beats_easy_most_of_the_time()      // 20 seeds, Level.EASY, KidBot: wins ≥ 14, and never loses by more than 2
@Test fun kid_scores_in_every_easy_game()        // ≥ 1 home goal in each of the 20
@Test fun kid_loses_to_hard_most_of_the_time()   // 20 seeds, Level.HARD, KidBot: wins ≤ 6
@Test fun skilled_player_and_hard_are_close()    // 20 seeds, Level.HARD, SkilledBot: wins between 5 and 15; mean |goal diff| ≤ 3
@Test fun hard_takes_the_puck_away()             // Level.HARD vs KidBot: ≥ 8 possession changes HOME→AWAY per game from Poke(true) or Hit
@Test fun easy_rarely_takes_the_puck_away()      // Level.EASY vs KidBot: fewer than a third as many as HARD
@Test fun games_have_shots_both_ways_on_hard()   // ≥ 5 Shot events per side per game on average
```
- [ ] **Step 2:** Run; note which fail.
- [ ] **Step 3:** Tune `Difficulty.EASY`, `Difficulty.HARD` and `Tuning` until `BalanceTest` and all earlier tests pass. Change numbers only; if a behaviour change in `TeamAi` is needed, make it and add a `TeamAiTest` for it. Never give the AI speed above `MAX_SPEED` or access to the human's `SkaterInput`.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest`; expected: all pass.
- [ ] **Step 5:** Commit: `test(ai): balance checks for Easy and Hard; tune`.

---

### Task 9: Touch controls mapping — **model: Sonnet**

**Files:**
- Create: `input/ControlsLayout.kt`, `input/Controls.kt`
- Test: `input/ControlsTest.kt`

**Interfaces:**
- Consumes: `SkaterInput`.
- Produces (pure Kotlin, pixels in, no Compose types):

```kotlin
enum class Btn { SHOOT, PASS, DEKE, POKE, BODY }
class ButtonSpot(val btn: Btn, val cx: Float, val cy: Float, val radius: Float)
class ControlsLayout(width: Float, height: Float, density: Float,
                     insetLeft: Float, insetTop: Float, insetRight: Float, insetBottom: Float) {
    val stickZoneRight: Float          // = width * 0.45f
    val stickRestX: Float; val stickRestY: Float; val stickRadius: Float   // radius = 56dp
    fun buttons(hasPuck: Boolean): List<ButtonSpot>   // two cached lists, no allocation per call
}
class Controls(var layout: ControlsLayout) {
    val input = SkaterInput()
    var hasPuck = false                // set by the game every frame; changing it releases all held buttons
    var stickActive = false; var stickBaseX = 0f; var stickBaseY = 0f; var stickKnobX = 0f; var stickKnobY = 0f
    fun isDown(btn: Btn): Boolean
    fun down(pointerId: Long, x: Float, y: Float)
    fun move(pointerId: Long, x: Float, y: Float)
    fun up(pointerId: Long)
    fun cancelAll()                    // pointer cancel, pause, backgrounding
}
```
- Layout: button radius 40dp (80dp across), touch radius = radius + 12dp. Slot A (Pass/Poke) centred 150dp from the right safe edge and 64dp from the bottom safe edge; slot B (Deke/Body) 56dp from the right, 64dp from the bottom; slot C (Shoot, offence only) 56dp from the right, 168dp from the bottom. "Safe edge" = screen edge minus the inset on that side. Stick rest position: 96dp from the left and bottom safe edges.
- Stick: the first pointer that goes down at `x < stickZoneRight` and not on a button owns the stick; base = touch point (kept at least `stickRadius` from the screen edges); vector = (pointer − base) / `stickRadius`, clamped to length 1, dead zone 0.12; if the pointer moves beyond the radius, the base follows it. **Screen `y` grows downward; rink `y = -13` is nearest the camera (bottom of screen), so `input.moveY = -screenDy`.** `moveX = screenDx`.
- Buttons: a pointer that goes down inside a button's touch radius holds that button until that pointer goes up or slides more than 1.5× the touch radius from its centre. Each button maps to its `SkaterInput` field. A second pointer in the stick zone while the stick is owned is ignored.

- [ ] **Step 1:** Failing tests (`ControlsTest`, layout `2424×1080`, density `2.625`, no insets unless stated):

```kotlin
@Test fun stick_appears_where_the_thumb_lands_and_reports_direction()  // down(1, 300,700); move(1, 300+147, 700) → moveX ≈ 1, moveY ≈ 0
@Test fun up_on_screen_is_positive_rink_y()             // drag upward → moveY > 0.9
@Test fun dead_zone_reports_zero()
@Test fun stick_vector_is_clamped_and_base_follows()    // drag 400px → length == 1; then back 100px → still pointing but < 1
@Test fun stick_and_button_work_together()              // pointer 1 stick, pointer 2 on SHOOT → shoot && moveX != 0
@Test fun releasing_stick_zeros_it_but_keeps_button()
@Test fun button_releases_when_finger_slides_off()
@Test fun offence_and_defence_show_different_buttons()  // buttons(true) = SHOOT,PASS,DEKE; buttons(false) = POKE,BODY; PASS and POKE share a centre
@Test fun swapping_mode_releases_held_buttons()         // holding SHOOT, hasPuck=false → input.shoot false and input.poke false until re-pressed
@Test fun cancel_all_clears_everything()
@Test fun second_thumb_in_stick_zone_is_ignored()
@Test fun ten_pointers_mashing_never_sticks()           // random down/move/up for 10 ids, then up for all → every input field false/0
@Test fun buttons_respect_insets()                      // insetRight=120: every ButtonSpot.cx + radius ≤ 2424 - 120; insetLeft=120 → stickRestX - stickRadius ≥ 120
@Test fun buttons_fit_on_a_short_wide_screen()          // 1600×720 @2.0 and 2400×1080 @2.625: all spots fully on screen, none overlap
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `--tests 'com.personal.hockey.input.*'`; expected: pass.
- [ ] **Step 5:** Commit: `feat(input): floating stick and action buttons mapping`.

---

### Task 10: Camera and rink drawing — **model: Opus**

**Files:**
- Create: `render/Camera.kt`, `render/GameRenderer.kt`, `render/RinkPainter.kt`, `render/Crests.kt`
- Test: `render/CameraTest.kt`, `render/RinkPainterTest.kt`

**Interfaces:**
- Consumes: `Rink`, `Team`, `Crest`, `HockeySim`.
- Produces:

```kotlin
class Camera {
    var focusX = 0f                              // rink x the camera looks at
    fun setViewport(width: Float, height: Float)
    /** Eases focusX toward targetX, clamped so the view never shows past the end boards by more than 3 m. */
    fun follow(targetX: Float, dt: Float)
    fun snapTo(targetX: Float)
    /** Rink (x, y, height z) → out[0]=screen x, out[1]=screen y, out[2]=pixels per metre at that depth. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray)
}
interface GameRenderer {
    /** timeS is wall time for animations. Must not allocate. */
    fun draw(scope: DrawScope, sim: HockeySim, timeS: Float)
}
/** Colours and centre-ice mark of the building. v1 has one; more rinks are added as data later. */
data class Arena(val primary: Long, val secondary: Long, val trim: Long, val crest: Crest, val boardText: String) {
    companion object { val NORTH_STAR = Arena(0xFF154734, 0xFFA6192E, 0xFFDDCBA4, Crest.PINE, "NORTH STAR HOCKEY") } }
class RinkPainter(arena: Arena) {
    fun draw(scope: DrawScope, camera: Camera)          // stands, far boards+glass, ice, markings, nets' back halves
    fun drawNearBoards(scope: DrawScope, camera: Camera) // called after skaters; low, 35% alpha
}
object Crests { fun draw(scope: DrawScope, crest: Crest, cx: Float, cy: Float, size: Float, primary: Color, secondary: Color) }
```
- Camera model: a pinhole camera at rink `(focusX, -34, 17)` looking at `(focusX, -1, 0)`, vertical field of view chosen so that at a 20:9 viewport about 34 m of rink length is visible at `y = 0`. The far boards (`y = +13`) land at roughly 22% of screen height from the top and the near boards (`y = -13`) at roughly 92%. `follow` uses exponential smoothing with a 0.25s time constant and leads the puck: target = `puckX + 0.15 * puckVx` clamped.
- Rink art: off-white ice (`0xFFF4F8FB`); red centre line and goal lines, blue lines at `±Rink.BLUE_X`, centre circle (radius 4.5), four end-zone faceoff circles at `(±20, ±6.7)`, light-blue creases; all drawn as projected polylines precomputed in rink space (project per frame into a reused `Path`/float buffer). Boards white with a wheat (`arena.trim`) kick plate and a forest-green (`arena.primary`) top rail; glass as a faint band; stands as three bands in dark green/red/dark grey with a deterministic speckle of crowd dots (positions fixed at construction). Centre-ice mark: `Crests.PINE` under a four-point star inside the centre circle, drawn foreshortened with the projection. Board lettering: none except "NORTH STAR HOCKEY" twice on the far boards in green. No other words or logos.
- Crests: simple original shapes built from a few path segments each: `PINE` three stacked triangles and a trunk; `PEAK` two overlapping mountain triangles with a snow cap; `DROP` a teardrop; `LEAF` a generic five-lobed leaf silhouette (not any league mark).

- [ ] **Step 1:** Failing tests.

```kotlin
// CameraTest (viewport 2400×1080)
@Test fun focus_point_is_horizontally_centred()         // project(focusX, 0, 0).x ≈ 1200 ± 1
@Test fun right_is_right_and_far_is_up()                // x+1 → larger screen x ; y+1 → smaller screen y
@Test fun far_things_are_smaller()                      // out[2] at y=+13 < out[2] at y=-13
@Test fun height_moves_up_the_screen()                  // z=1 → screen y smaller than z=0
@Test fun whole_rink_width_fits_vertically()            // y=+13 → 0.15h..0.30h ; y=-13 → 0.85h..0.97h
@Test fun shows_about_34_metres_at_mid_ice()            // screenX(focusX+17,0) - screenX(focusX-17,0) ≈ 2400 ± 150
@Test fun follow_is_smooth_and_clamped()                // target jumps 0→30: one tick moves < 1 m; after 3s focusX ≤ 30 - 17 + 3 + 0.1
@Test fun project_does_not_allocate_and_is_finite()     // corners of the rink at any focusX in -30..30 → finite numbers

// RinkPainterTest (Robolectric: draw into an ImageBitmap-backed CanvasDrawScope 800×360)
@Test fun draws_without_crashing_at_several_camera_positions()
@Test fun centre_of_ice_is_light_and_stands_are_dark()  // pixel at projected (focusX+8, 5, 0) luminance > 0.8; pixel at (400, 10) luminance < 0.5
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `--tests 'com.personal.hockey.render.*'`; expected: pass.
- [ ] **Step 5:** Commit: `feat(render): broadcast camera and rink`.

---

### Task 11: Skaters, goalies, puck and the full renderer — **model: Opus**

**Files:**
- Create: `render/FigurePainter.kt`, `render/CanvasRenderer.kt`
- Test: `render/CanvasRendererTest.kt`, `render/DrawOrderTest.kt`

**Interfaces:**
- Consumes: `Camera`, `RinkPainter`, `GameRenderer`, `HockeySim`, `Skater`, `Goalie`, `Puck`, `Act`, `Team`.
- Produces:

```kotlin
/** A teams.Kit converted once to Compose colours. */
class KitColors(kit: Kit) { val jersey: Color; val trim: Color; val pants: Color; val helmet: Color; val number: Color }
class FigurePainter { fun drawSkater(scope: DrawScope, camera: Camera, s: Skater, kit: KitColors, number: Int, hasPuck: Boolean, timeS: Float)
                      fun drawGoalie(scope: DrawScope, camera: Camera, g: Goalie, kit: KitColors, number: Int)
                      fun drawPuck(scope: DrawScope, camera: Camera, puck: Puck) }
class CanvasRenderer(setup: MatchSetup, arena: Arena = Arena.NORTH_STAR) : GameRenderer {
    val camera: Camera
    /** Skater ids and goalie markers in far-to-near order for this frame; exposed for tests. */
    internal fun drawOrder(sim: HockeySim, out: IntArray): Int
}
```
- Kits come from `setup.homeKit` / `setup.awayKit`; jersey numbers from `setup.home.skaters` / `setup.away.skaters` (matched by `Position`) and `goalieNumber`. Nothing here names a team.
- Frame order: `camera.follow(puck)` → `RinkPainter.draw` → shadows → pass-target marker (faint ring in the home trim colour under `sim.passTargetFor(carrier)` when the controlled skater carries) → control ring (bright green ellipse, 0.7 m radius, under `sim.controlledId`) → puck if it is farther than every figure… simpler: puck is sorted with the figures by `y` → figures far-to-near (larger `y` first) → `RinkPainter.drawNearBoards`.
- Figure: about 1.8 m tall, built from projected points: skates, two legs that alternate with `stride` (leg swing amplitude grows with speed), hips, torso leaning into `facing`, shoulders, arms holding a stick whose blade rests on the ice 0.9 m ahead along `facing`, helmet, number on the torso (drawn with `drawContext.canvas.nativeCanvas.drawText`, one reused `Paint`), elliptical shadow. Left/right flips by the screen-space sign of `facing`. Poses: `WINDUP` stick drawn back and up (further with `windup`); `POKE` stick thrust forward 1.8 m; `BODY` torso lowered and shoulder forward; `DEKE` strong lean into the motion; `DOWN` figure lying on the ice; `RECOVER` hunched. Goalie: wider body, pads, blocker and glove, squats; arms out briefly when `saveFlash < 0.3`. Puck: black ellipse with a small shadow; hidden while a goalie holds it.
- Nets: red posts and crossbar 1.2 m high with a white mesh back, drawn in the sort order at `y = 0` of their goal line.

- [ ] **Step 1:** Failing tests.

```kotlin
// DrawOrderTest
@Test fun far_skaters_are_drawn_before_near_ones()      // skater at y=+10 precedes skater at y=-10 in drawOrder
@Test fun order_contains_all_ten_skaters_and_two_goalies_once()

// CanvasRendererTest (Robolectric, 800×360 bitmap; a Match stepped to PLAY)
@Test fun draws_a_frame_in_every_phase_without_crashing()   // FACEOFF, PLAY, GOAL, BREAK, FINAL
@Test fun draws_every_act_pose_without_crashing()           // force each Act on a skater, facing 0 and π
@Test fun home_jersey_colour_appears_near_a_home_skater()   // MIN dark kit: some pixel within the skater's projected box is within 12/255 per channel of 0xFF154734
@Test fun away_wears_white_even_when_both_teams_are_blue()  // EDM vs TOR: away skater box contains near-white pixels and home box does not have more white than primary
@Test fun a_custom_kit_and_roster_are_used()                // setup with an invented team (magenta kit, C number 99) draws magenta pixels at its skaters
@Test fun control_ring_is_under_the_controlled_skater()     // a green-dominant pixel within 1 m (projected) of the controlled skater's feet; none under another skater
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run render tests; expected: pass.
- [ ] **Step 5:** Commit: `feat(render): skaters, goalies, puck and full frame`.

---

### Task 12: Sound, vibration, and the playable game screen — **model: Sonnet**

**Files:**
- Create: `audio/Sfx.kt`, `audio/SoundSynth.kt`, `audio/Wav.kt`, `audio/Feedback.kt`, `audio/AndroidFeedback.kt`, `game/GameViewModel.kt`, `game/GameScreen.kt`, `game/Hud.kt`, `game/ControlsOverlay.kt`, `game/PauseOverlay.kt`; modify `AppGraph.kt`
- Test: `audio/SoundSynthTest.kt`, `audio/FeedbackDirectorTest.kt`, `game/GameViewModelTest.kt`, `game/GameScreenTest.kt`

**Interfaces:**
- Consumes: `Match`, `HockeySim`, `SimEvent` subclasses, `Controls`, `ControlsLayout`, `CanvasRenderer`, `MatchSetup`, `Team`, `Level`, `SettingsStore`.
- Produces:

```kotlin
enum class Sfx { STICK, PASS, SHOT, BOARDS, SAVE, HIT, WHISTLE, BUZZER, HORN, CHEER }
enum class Buzz { LIGHT, STRONG, HUGE }
interface GameFeedback { fun play(sfx: Sfx); fun vibrate(buzz: Buzz) }
object SoundSynth { fun render(sfx: Sfx, sampleRate: Int = 22050): ShortArray }      // deterministic (fixed seed for noise)
class AndroidFeedback(context: Context) : GameFeedback                                // SoundPool + Vibrator, as in android-hot-potatoe
class FeedbackDirector(private val out: GameFeedback) {
    var soundOn = true; var vibrationOn = true
    fun onEvents(events: List<SimEvent>) }
class AppGraph { val feedback: () -> GameFeedback }      // lazy

data class HudState(val homeCode: String, val awayCode: String, val homeScore: Int, val awayScore: Int,
                    val periodLabel: String /* "1st" "2nd" "3rd" */, val clock: String,
                    val banner: String? /* "GOAL!" in GOAL; "End of 1st" in BREAK; null otherwise */,
                    val hasPuck: Boolean, val paused: Boolean, val finished: Boolean)
class GameViewModel(val setup: MatchSetup, private val director: FeedbackDirector,
                    seed: Long = System.nanoTime()) : ViewModel() {
    val match: Match                                         // built with setup.level and both teams' skaters
    val renderer: CanvasRenderer                             // CanvasRenderer(setup)
    val controls: Controls                                   // layout replaced via setLayout
    val hud: StateFlow<HudState>                             // republished only when a field changes
    val frame: State<Int>                                    // incremented every drawn frame; the Canvas reads it
    fun setLayout(layout: ControlsLayout)
    fun onFrame(frameTimeNanos: Long)                        // accumulator; ≤ 5 ticks per call; first call after resume only resets the timestamp
    fun pause(); fun resume()
    fun setSoundOn(on: Boolean); fun setVibrationOn(on: Boolean)   // update the director; persisting is the caller's job
}
@Composable fun GameScreen(vm: GameViewModel, soundOn: Boolean, vibrationOn: Boolean,
                           onSoundChange: (Boolean) -> Unit, onVibrationChange: (Boolean) -> Unit,
                           onFinished: (homeScore: Int, awayScore: Int) -> Unit, onQuit: () -> Unit)
```
- Event → feedback: `Shot` SHOT · `Pass` PASS · `Pickup` STICK · `Poke(true)` STICK · `Boards` BOARDS · `Save` SAVE · `Hit` HIT + `STRONG` · `FaceoffDrop` WHISTLE · `PeriodEnd` BUZZER · `Goal` HORN + CHEER + `HUGE` · others nothing. At most one of each `Sfx` per call.
- Synth recipes are the builder's choice; each sound is 0.05–1.5s, non-silent, peak ≤ 32767 with no clipping wrap.
- `onFrame`: `controls.hasPuck = (carrier?.id == sim.controlledId)`; when paused or `FINAL`, step nothing. A gap over 100 ms is treated as 100 ms. After the last tick's events go to the director, update `hud`. When `FINAL` is first seen set `finished = true`.
- `GameScreen`: full-screen `Canvas` reading `vm.frame` and calling `vm.renderer.draw(this, vm.match.sim, timeS)`; a `LaunchedEffect` loop of `withFrameNanos { vm.onFrame(it) }`; `pointerInput` with `awaitEachGesture`/`awaitPointerEvent` forwarding every pointer change to `controls.down/move/up` (and `cancelAll` on cancellation); `ControlsOverlay` draws the stick (resting outline at 25% alpha, base and knob when active) and the current buttons as translucent white circles with their label (`SHOOT`, `PASS`, `DEKE`, `POKE`, `BODY`), brighter while held; `Hud` top-left scoreboard (`MIN 1 | 0 COL | 2nd 14:32` as team-coloured chips) and a pause button top-right, both inside `WindowInsets.displayCutout`/safe-drawing padding; centred banner text; `PauseOverlay` with `Resume`, `Quit`, and `Sound` and `Vibration` switches. `ControlsLayout` is rebuilt from the Canvas size, density and safe-drawing insets. `BackHandler` pauses (or resumes if already paused). `LifecycleEventEffect(ON_PAUSE)` calls `vm.pause()` and `controls.cancelAll()`; the game stays paused on return until Resume is tapped. `view.keepScreenOn = true` while composed. When `hud.finished`, call `onFinished` once after a 1.5s hold.
- Test tags: `"game-canvas"`, `"pause-button"`, `"hud-clock"`, `"hud-home-score"`, `"hud-away-score"`.

- [ ] **Step 1:** Failing tests.

```kotlin
// SoundSynthTest
@Test fun every_sound_is_audible_bounded_and_short()    // for each Sfx: 0.05s..1.5s long, max |sample| ≥ 3000
@Test fun render_is_deterministic()

// FeedbackDirectorTest (fake GameFeedback recording calls)
@Test fun goal_plays_horn_and_cheer_and_big_buzz()
@Test fun hit_plays_hit_and_buzzes()
@Test fun sound_off_plays_nothing_but_still_vibrates()
@Test fun vibration_off_never_vibrates()
@Test fun duplicate_events_in_one_tick_play_once()

// GameViewModelTest
@Test fun frames_advance_the_sim_at_sixty_per_second()  // onFrame at t=0 then t=1s in 16.67ms steps → 59..61 ticks (count via sim clock/phaseT)
@Test fun a_long_stall_is_clamped()                     // onFrame(0); onFrame(5s) → at most 6 ticks ran
@Test fun paused_game_does_not_advance()                // pause(); many frames → clockS and phaseT unchanged; hud.paused
@Test fun resume_does_not_jump_time()                   // pause at t=1s, resume, onFrame(t=60s), onFrame(t=60s+16ms) → ≤ 2 ticks
@Test fun hud_shows_codes_score_period_and_clock()      // MIN vs COL at start: "MIN","COL",0,0,"1st","20:00"
@Test fun hud_has_puck_follows_possession()             // give puck to controlled skater → hasPuck true and controls.hasPuck true
@Test fun goal_banner_then_clears()
@Test fun finished_is_set_at_final()                    // fast-forward by calling match.tick until FINAL, then onFrame → hud.finished
@Test fun stick_input_moves_the_controlled_skater()     // controls.down/move right; 1s of frames in PLAY → controlled skater vx > 2

// GameScreenTest (Robolectric compose, qualifiers "w800dp-h360dp-land")
@Test fun shows_scoreboard_and_offence_or_defence_buttons()   // "MIN", "COL", "20:00" shown; POKE and BODY shown at faceoff
@Test fun pause_button_opens_overlay_and_resume_closes_it()
@Test fun quit_calls_onQuit()
@Test fun switches_report_changes()
@Test fun going_to_background_pauses()                  // move lifecycle to STARTED then RESUMED → overlay showing
@Test fun hud_and_buttons_are_inside_the_screen()       // bounds of pause button and hud within root bounds
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement audio (adapting `android-hot-potatoe`'s `SoundSynth`/`Wav`/`AndroidFeedback`), then `GameViewModel`, then the composables.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest`; expected: all pass.
- [ ] **Step 5:** Commit: `feat(game): playable game screen with sound and vibration`.

---

### Task 13: App shell — navigation, team select, final score — **model: Sonnet**

**Files:**
- Create: `ui/Navigator.kt`, `ui/AppRoot.kt`, `ui/select/TeamSelectScreen.kt`, `ui/result/ResultScreen.kt`; modify `MainActivity.kt`, `AppGraph.kt`, `README.md`
- Test: `ui/NavigatorTest.kt`, `ui/select/TeamSelectScreenTest.kt`, `ui/result/ResultScreenTest.kt`, `AppFlowTest.kt`; update `LaunchTest.kt`

**Interfaces:**
- Consumes: `Teams`, `Team`, `MatchSetup`, `Level`, `Settings`, `SettingsStore`, `GameViewModel`, `GameScreen`, `FeedbackDirector`, `Crests`, `HockeyTheme`.
- Produces:

```kotlin
sealed interface Screen {
    data object TeamSelect : Screen
    data class Game(val homeId: String, val awayId: String, val level: Level) : Screen
    data class Result(val homeId: String, val awayId: String, val level: Level, val homeScore: Int, val awayScore: Int) : Screen
}
class Navigator(start: Screen = Screen.TeamSelect)       // same API as android-sims' Navigator (push/pop/replace/reset, Entry owns a ViewModelStore)
class NavViewModel : ViewModel { val navigator: Navigator }
class AppDeps(val settings: SettingsStore, val feedback: () -> GameFeedback)
class AppGraph { val deps: AppDeps }
@Composable fun AppRoot(deps: AppDeps)
@Composable fun TeamSelectScreen(teams: List<Team>, initial: Settings, onPlay: (homeId: String, awayId: String, level: Level) -> Unit)
@Composable fun ResultScreen(home: Team, away: Team, homeScore: Int, awayScore: Int, onRematch: () -> Unit, onChangeTeams: () -> Unit)
```
- **Team select** (landscape, one screen, no vertical scrolling at 800×360dp): title "North Star Hockey"; row "You" and row "Opponent", each a `LazyRow` with one card per entry of `teams` (crest drawn with `Crests`, full team name, team primary as card colour, selected card outlined in wheat); four cards fit without scrolling and more teams scroll sideways; picking a team on one row that is selected on the other moves the other row's selection to `Teams.next(id)`; a two-option `Easy` / `Hard` segmented switch; a large `Play` button. `onPlay` → `settings.setMatchup(...)` then `navigator.push(Screen.Game(...))`. Test tags: `"you-<id>"`, `"opp-<id>"` (e.g. `"you-MIN"`), `"level-EASY"`, `"level-HARD"`, `"play"`.
- **Game route:** resolves ids with `Teams.byId` (unknown id → `navigator.reset()`), builds `GameViewModel(MatchSetup.of(home, away, level), …)` in the entry's `ViewModelStore` with a `FeedbackDirector(deps.feedback())` initialised from settings; sound/vibration changes persist through `SettingsStore`. `onFinished` → `navigator.replace(Screen.Result(...))`; `onQuit` → `navigator.reset()`.
- **Result:** "Final", both team names with scores, headline `"<Team name> win!"` or `"Tie game"`, buttons `Rematch` (→ `replace(Screen.Game(same))`) and `Change teams` (→ `reset()`). System back from Result goes to team select.
- While settings are still loading show nothing but the background (no flash of defaults).

- [ ] **Step 1:** Failing tests.

```kotlin
// NavigatorTest — port android-sims' NavigatorTest to these screens (push/pop/replace/reset clear view models).

// TeamSelectScreenTest
@Test fun shows_four_teams_on_both_rows_and_restores_last_choice()   // initial TOR/EDM/HARD → those selected
@Test fun play_reports_the_selection()                               // pick you-EDM, opp-TOR, level-HARD, play → ("EDM", "TOR", HARD)
@Test fun a_fifth_team_shows_up_and_can_be_played()                  // teams = Teams.all + an invented team "ZZZ": scroll to you-ZZZ, select, play → homeId "ZZZ"
@Test fun picking_the_same_team_moves_the_other_row()                // you=MIN, opp=COL; tap you-COL → opp becomes EDM
@Test fun everything_fits_without_scrolling()                        // "play" and all 8 cards displayed at w800dp-h360dp

// ResultScreenTest
@Test fun shows_winner()        // MIN 3, COL 1 → "Minnesota Wild win!"
@Test fun shows_away_winner()   // MIN 1, COL 3 → "Colorado Avalanche win!"
@Test fun shows_tie()           // 2–2 → "Tie game"
@Test fun buttons_call_back()

// AppFlowTest (Robolectric, fake SettingsStore and fake GameFeedback)
@Test fun select_play_quit_returns_to_select()
@Test fun matchup_is_saved_when_play_is_tapped()
@Test fun finished_game_shows_result_then_rematch_starts_a_new_game()   // drive the Game entry's view model to FINAL via match.tick, advance clock → Result; tap Rematch → scoreboard shows 0–0, 20:00
@Test fun back_on_team_select_exits_and_back_in_game_pauses()
```
- [ ] **Step 2:** Run; expected: compile failure.
- [ ] **Step 3:** Implement; `MainActivity` now sets `AppRoot((application as HockeyApp).graph.deps)`. Update `LaunchTest` to assert the `"play"` tag is displayed.
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest assembleDebug`; expected: all tests pass, APK built.
- [ ] **Step 5:** Fill in the README's "What's in it" from what was built. Commit: `feat(ui): team select, game flow and final score`.

---

### Task 14: Whole-app verification — **model: Opus**

**Files:** none planned; fixes land where the defects are, each with a test.

- [ ] **Step 1:** Run `./gradlew clean testDebugUnitTest assembleDebug lintDebug`. Expected: `BUILD SUCCESSFUL`, no lint errors (warnings acceptable).
- [ ] **Step 2:** Check the Global Constraints by search: team names, codes and colours appear in main code only in `teams/Teams.kt` (`grep -rn "MIN\b\|Wild\|Avalanche\|Oilers\|Leafs" app/src/main/java` → hits only there and in `Settings` defaults); no `android.`/`androidx.` imports under `sim/`, `ai/`, `teams/`; no image files under `app/src/main/res` or `assets`; manifest has only `VIBRATE`; no forbidden names (`grep -ri "all.stars\|all-stars\|xcel\|NHL" app/src README.md` → no hits).
- [ ] **Step 3:** Allocation check: add `sim/NoAllocTest` — after 600 warm-up ticks of an AI-only `Match`, 6000 further ticks allocate less than 64 KB in total, measured with `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`. Fix any offender.
- [ ] **Step 4:** If `adb devices` lists a device or emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, launch, play one period, capture `adb exec-out screencap -p` of team select and of live play, and read `adb logcat -d | grep -i "hockey\|AndroidRuntime"` for crashes and `Choreographer` skipped-frame warnings. If no device is attached, say so in the report; do not claim on-device results.
- [ ] **Step 5:** Review the whole diff against the spec section by section and against Review Focus items 1–5; fix gaps with tests. Commit: `chore: final verification fixes`.
