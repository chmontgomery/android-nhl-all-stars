# Gameplay Feel Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Six drawing-only changes:
- Zoom out to 1.6×.
- Jersey numbers painted on the back.
- A smaller puck.
- Gliding skaters.
- Shots that lift off the ice, with a shadow.
- A goalie butterfly on shots.

**Architecture:**
- All changes live in `render/`; the sim is untouched.
- Puck height is a render-side `PuckFlight` driven by SimEvents in `AnimDirector.onTick`.
- The butterfly is a new goalie clip.
- Gliding is a rework of the procedural leg cycle, with an eased `glideW`.
- Numbers are drawn through a preallocated `android.graphics.Matrix` mapped onto the projected back plane.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, Robolectric (GraphicsMode.NATIVE, SDK 34) for tests and preview PNGs.

**Spec:** docs/superpowers/specs/2026-11-16-gameplay-feel-design.md

## Global Constraints

- No change to sim behaviour. Puck height, the goalie butterfly and skating style are drawing-only. BalanceTest's random streams are untouched (no file under `sim/` or `ai/` changes).
- No per-frame or per-tick allocation in draw, sim or anim paths: preallocate, and no lambdas, iterators or boxing. NoAllocTest and the AnimDirectorTest allocation tests stay green.
- Keep the 8,000-triangle budget (`CanvasRendererTest` budget test).
- Unreadable play is an anti-goal: the puck stays easy to find, and the shadow marks its true position on the ice.
- Visual tasks render `PreviewImagesTest` (writes `app/build/preview/*.png`) and Read the relevant PNGs before committing.
- Full run: `./gradlew clean testDebugUnitTest`, with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- Commit on main. End each message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A saved, covered or rebounding shot leaves the puck drawn in mid-air away from the net.** Expected: it lands within 0.2 s of a Save, Cover, Pickup or Goal, or when it reverses away from the target net. Covered in Task 4.
2. **The goalie stays down after a goal, faceoff or period end.** Expected: the butterfly always ends. FaceoffDrop and any phase that is not PLAY clear it. Covered in Task 5.
3. **The back number reads mirrored when seen from behind.** Expected: the text reads left-to-right on screen whenever it is visible. Covered in Task 2.
4. **AI stick input flickers near zero and makes skaters snap between gliding and striding.** Expected: `glideW` eases, so one tick moves it by less than 0.2. Covered in Task 3.
5. **A lifted puck near the near boards.** The post-near-boards redraw must use the same drawn height and the same shadow as the main draw. Covered in Task 4.

---

### Task 1: Zoom out to 1.6× and shrink the puck

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/Camera.kt` (`FOCAL_PER_HEIGHT`)
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` companion (`PUCK_DRAW_R`, `PUCK_THICK`)
- Test: `app/src/test/java/com/personal/hockey/render/CameraTest.kt`, plus any render tests whose numbers depend on these constants

**Interfaces:** Produces `FigurePainter.PUCK_DRAW_R = 0.15f` and `PUCK_THICK = 0.05f`. `CanvasRenderer.PUCK_COVER_R` derives from these.

- [ ] **Step 1:** Rename `a_skater_is_about_1_7_times_bigger_than_before` to `a_skater_is_about_1_6_times_bigger_than_before` and assert `ratio` in `1.55f..1.65f`. Add `the_puck_is_drawn_at_0_15_m` asserting `FigurePainter.PUCK_DRAW_R == 0.15f`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*CameraTest*'`. Expected: FAIL (the ratio is about 1.79).
- [ ] **Step 3:** Set `FOCAL_PER_HEIGHT` so the ratio is 1.6. It scales linearly: 4.0 × 1.6 / 1.79 ≈ 3.58. Set `PUCK_DRAW_R = 0.15f` and `PUCK_THICK = 0.05f`. Fix the comments that say "1.7×/1.79×".
- [ ] **Step 4:** Run the full suite. The Review Focus 3 near-clamp test (puck at 0.18h..0.85h) and the edge-arrow tests must still pass. If a test's tolerance was tied to the old zoom, retune it to the new geometry and say why in the report. Never loosen a readability bound.
- [ ] **Step 5:** Render the previews and Read `game.png` and `game-corner.png`. Commit `feat(render): zoom out to 1.6x and draw a smaller puck`.

### Task 2: Numbers painted on the back of the jersey

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` (`number()`, `body()`, the companion)
- Test: `app/src/test/java/com/personal/hockey/render/BackNumberTest.kt` (new)

**Interfaces:**
- Produces `internal fun backNumberAlpha(camera: Camera): Float` on FigurePainter, for the last posed figure.
  - Facing term `d` = the dot product of the torso's back normal (−fx, −fy, 0) with the unit vector from the torso centre to the camera eye (`camera.eyeX/eyeY/eyeZ`).
  - Alpha = `smoothstep(0.1f, 0.5f, d)`.
- Produces `internal val numberMatrix: android.graphics.Matrix`, preallocated.
  - It is set each draw with `setPolyToPoly` (3 points, affine).
  - It maps the text box (width = height = `NUMBER_H` in text units) onto the projected back plane: centred between the shoulders and the hips at about 60% up the torso, spanning about 0.30 m across the shoulders' lateral axis and 0.30 m tall along the spine.
  - It sits 0.02 m behind the torso surface toward the back.

- [ ] **Step 1: Write failing tests**
  - `number_hidden_when_facing_camera`: pose a skater facing toward the camera (facing = −π/2 with the eye at −y; verify the sign against `Camera`). Assert `backNumberAlpha < 0.01f`.
  - `number_full_when_back_to_camera`: pose the skater facing away from the camera. Assert alpha `> 0.99f`.
  - `number_fades_side_on`: pose the skater facing +x. Assert alpha `< 0.3f`.
  - `number_reads_left_to_right` (Review Focus 3): with the back to the camera, map the text points (0, y) and (NUMBER_H, y) through `numberMatrix`. Assert the second has the larger screen x. Also assert the text's up direction maps to screen-up (smaller y).
- [ ] **Step 2:** Run `--tests '*BackNumberTest*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement.
  - `number()` returns early when alpha < 0.01.
  - Otherwise: `canvas.save(); canvas.concat(numberMatrix)`, draw the edge text and then the fill text, both with `paint.alpha` scaled by alpha, then `canvas.restore()`.
  - Goalies use the same path.
  - No allocation: reuse the preallocated `FloatArray(6)` src/dst arrays.
- [ ] **Step 4:** Run the full suite. If CanvasRendererTest or PreviewImagesTest probe for number pixels at the old floating position, update them to the new rule: present only when the back faces the camera.
- [ ] **Step 5:** Read `game.png`, `skater-close-up*.png` and `celebration.png`. Check that the number looks printed on the back and turns with the body. Commit `feat(render): jersey numbers printed on the back, hidden when facing the camera`.

### Task 3: Gliding stride instead of running

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` (`poseSkating` leg cycle, companion constants)
- Modify: `app/src/main/java/com/personal/hockey/render/anim/AnimDirector.kt` (`AnimState.glideW`, eased in `onTick`)
- Test: `app/src/test/java/com/personal/hockey/render/SkatingAnimTest.kt`

**Interfaces:**
- Produces `AnimState.glideW: Float` (0 = striding, 1 = gliding).
  - Its target is 1 when `hypot(s.input.moveX, s.input.moveY) < GLIDE_STICK` (0.3) and the skater is not stopping. Otherwise 0.
  - It moves toward the target at `DT / GLIDE_BLEND_S`, with `GLIDE_BLEND_S = 0.15f`.

**Stride values:**
- `STRIDE_M = 3.6f`, from 2.6: a longer cycle.
- `STRIDE_LIFT = 0.05f`, from 0.14: the recovering skate stays close to the ice.
- Push phase: the pushing skate travels out diagonally to the side, reaching `PUSH_OUT = 0.38f` lateral from the hip line at full extension, and slightly behind.
- Recovery phase: the skate returns under the hip.
- Glide pose, blended by `glideW`:
  - both skates on the ice (z = 0), parallel at ±0.17 lateral
  - hips 0.08 m lower than standing
  - torso lean forward 0.1 more than the current upright skating lean

The crossover, stop, wind-up and stickhandling paths keep working on top: they read the same leg slots.

- [ ] **Step 1: Write failing tests**
  - `stride_lift_is_low`: at 6 m/s, over a whole cycle, the max toe z is `< 0.07f`.
  - `push_goes_out_to_the_side`: the max |lateral b| of either skate over a cycle is `> 0.33f`.
  - `gliding_keeps_both_skates_on_the_ice`: with input 0 at 6 m/s, after 30 ticks, both heels and toes have z `< 0.03f` and |b| `< 0.25f`.
  - `glide_eases` (Review Focus 4): alternate the input between 0 and 1 every tick. Assert |Δ glideW| per tick `< 0.2f`.
- [ ] **Step 2:** Run `--tests '*SkatingAnimTest*'`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement as specified. Keep the crossover (`turning`) and stop (`stopW`) blends composing over the new cycle.
- [ ] **Step 4:** Run the full suite. The existing crossover, stop, sweep and allocation tests must still pass.
- [ ] **Step 5:** Read `game.png`, `stickhandling.png` and `hockey-stop.png`. Skaters should read as low, wide gliders, not runners. Commit `feat(anim): skaters glide with long low side pushes instead of running`.

### Task 4: Shots lift off the ice, with a shadow

**Files:**
- Create: `app/src/main/java/com/personal/hockey/render/anim/PuckFlight.kt`
- Modify: `app/src/main/java/com/personal/hockey/render/anim/AnimDirector.kt` (owns `val flight = PuckFlight()`, feeds it in `onTick`)
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt`: `drawPuckAt(scope, camera, x, y, z = 0f)` and new `drawPuckShadow(scope, camera, x, y)`
- Modify: `app/src/main/java/com/personal/hockey/render/CanvasRenderer.kt` (main puck draw and the near-boards redraw)
- Modify: `app/src/main/java/com/personal/hockey/render/Effects.kt`: `notePuck(x, y, z, speed)`, with the streak drawn at the puck's height
- Test: `app/src/test/java/com/personal/hockey/render/PuckFlightTest.kt` (new)

**Interfaces:** `class PuckFlight`, with:
- `val height: Float`, in metres, ≥ 0.
- `fun onShot(sim: HockeySim, shooterId: Int, side: Side, slap: Boolean)`
  - Records the shooter's x and the target goal-line x. The target is the net the shooter's side attacks; derive it from where the defending goalie stands (`sim.goalies`), not a hard-coded sign.
  - Records `peak`: `SLAP_PEAK = 1.1f` when slap, else `WRIST_PEAK = 0.5f`.
  - `slap` uses the same rule AnimDirector already uses to pick SLAP_FOLLOW, including one-timers.
- `fun land()`: begins a descent to 0 over `LAND_S = 0.15f`.
- `fun onTick(sim: HockeySim)`, while flying:
  - `progress` = the fraction of the shooter-to-goal-line x distance covered by `sim.puck.x`, clamped to 0..1.
  - `height = LAUNCH_Z + (peak − LAUNCH_Z) · smoothstep(0, 0.35, progress)`, with `LAUNCH_Z = 0.05f`.
  - It calls `land()` when any of these is true:
    - the puck is held (`sim.puckHeld()`)
    - the puck's x-velocity points away from the target net
    - the speed is below `LAND_SPEED = 8f`
    - the puck is more than 1.5 m past the goal line
- `fun advance(dt: Float)`: advances the descent.
- `fun clear()`: sets height 0 immediately.

AnimDirector calls into it as follows:
- On `SimEvent.Shot`: `flight.onShot`.
- On `Save`, `Cover`, `Pickup`, `Goal` and `Pass`: `flight.land()`.
- On `FaceoffDrop`, or any phase that is not PLAY, GOAL or CELEBRATE: `flight.clear()`.
- Each frame, from `advance`: `flight.advance(dt)`.

CanvasRenderer draws the shadow and then the puck:
- When `flight.height > 0.01f` and the puck is loose, it draws `drawPuckShadow` at the sim position (from `puckDrawXY`), then `drawPuckAt(..., z = flight.height)`.
- The shadow is a dark translucent ellipse of radius `PUCK_DRAW_R · 1.1`, with alpha fading from 0.45 at z 0 to 0.25 at z 1.1.
- The near-boards redraw uses the same z and the same shadow (Review Focus 5).

- [ ] **Step 1: Write failing tests**
  - `slapshot_reaches_crossbar_height_at_the_net`: give a shooter at x = attack goal line − 15 m a slap Shot. Tick the real sim until the puck crosses the goal line, calling `director.onTick(sim)` each tick. Assert `flight.height` is in `0.95f..1.15f` at the goal line.
  - `wrist_shot_flies_lower`: the same, with a wrist shot. Assert height `0.4f..0.6f`.
  - `puck_leaves_the_stick_fast`: 0.1 s after the release, the height is `> 0.3f · peak`.
  - `save_lands_the_puck` (Review Focus 1): inject a Save event mid-flight. After `advance(0.2f)`, height `== 0f`.
  - `rebound_away_from_net_lands`: set the puck vx to point away from the target net mid-flight, then `onTick`. After `advance(0.2f)`, height `== 0f`.
  - `passes_stay_flat`: a Pass event gives height `== 0f`.
  - `shadow_drawn_under_lifted_puck`: a CanvasRenderer pixel test. With a lifted puck at about 1 m, the screen pixel at the sim position projected at z = 0 is darker than the ice. The lifted puck's own pixels sit above it on screen (smaller y).
  - Extend the AnimDirector allocation test to cover a shot-flight-land cycle.
- [ ] **Step 2:** Run `--tests '*PuckFlightTest*'`. Expected: FAIL (unresolved).
- [ ] **Step 3:** Implement as specified.
- [ ] **Step 4:** Run the full suite.
- [ ] **Step 5:** Add a `shot_in_flight` preview: a slapshot about 0.25 s after release. Read it, plus `wrist-release.png`; the shadow and the lift should both be clear. Commit `feat(render): shots lift off the ice with a shadow under the puck`.

### Task 5: Goalie butterfly on shots

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/anim/Clips.kt` (new `Clip.GOALIE_BUTTERFLY`, registered in ClipLibrary init)
- Modify: `app/src/main/java/com/personal/hockey/render/anim/AnimDirector.kt` (trigger, hold and release)
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` (only if `poseGoalie`'s arms-out save flash needs to yield to the clip)
- Test: `app/src/test/java/com/personal/hockey/render/ButterflyTest.kt` (new)

**Interfaces:**

`Clip.GOALIE_BUTTERFLY` is a looping hold clip built from goalie slots. It has 2 keys: a drop key at t = 0.12 s and an identical hold key at t = 0.3 s.
- Knees on the ice: knee z about 0.08.
- Pads flared flat along the ice to both sides: ankle and heel lateral about ±0.75, z about 0.06.
- Hips at z about 0.45.
- Torso upright.
- Glove and blocker out to the sides at about 0.7 m height.
- The stick blade flat on the ice in front of the five-hole, keyed (STICK_TOP, BLADE_HEEL, BLADE_TOE).

AnimDirector behaviour:
- On `SimEvent.Shot`, the defending goalie (the goalie of the side the shot attacks) plays GOALIE_BUTTERFLY.
- It stays while the shot is live. On Save, Goal or Pickup, it starts a `BUTTERFLY_HOLD_S = 0.5f` countdown and then plays `Clip.NONE`, which eases out.
- While `goalie.holding` (covering) is true, the clip stays.
- A safety cap: it releases `BUTTERFLY_MAX_S = 2.0f` after the trigger.
- FaceoffDrop and any phase that is not PLAY clear it immediately (Review Focus 2).
- The existing `GOALIE_SLUMP` on a goal replaces the butterfly through the normal `play()`.
- The existing save arms-out flash must not fight the clip. The clip keys the arms, so it wins while weight is 1.

- [ ] **Step 1: Write failing tests**
  - `shot_drops_the_defending_goalie`: after a Shot event, the defending goalie's clip is GOALIE_BUTTERFLY and the other goalie's is not.
  - `butterfly_pose_has_pads_flat`: after 0.2 s, pose the goalie. Both knees have z `< 0.15f`, and both pads have lateral `|b| > 0.6f` with z `< 0.12f`.
  - `goalie_gets_up_after_a_save`: Save, then advance 0.5 s + 0.3 s. The clip weight is `0f`.
  - `goalie_never_stuck_down` (Review Focus 2): Shot then FaceoffDrop gives clip NONE immediately. A Shot with no resolving event gives weight 0 by 2.3 s.
  - `cover_holds_the_butterfly`: while `goalie.holding` is true, the clip is still GOALIE_BUTTERFLY past 2.0 s. Use a sim cover state, and check how BUTTERFLY_MAX_S interacts with holding.
  - Extend the allocation test.
- [ ] **Step 2:** Run `--tests '*ButterflyTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement as specified.
- [ ] **Step 4:** Run the full suite (the goalie mesh, budget and celebration tests).
- [ ] **Step 5:** Add a `goalie_butterfly` preview: about 0.3 s after a shot on net, close up. Read it; the pads must read as flared flat. Commit `feat(anim): goalies drop into the butterfly on shots`.

### Task 6: Save outcomes, with caught pucks going to a faceoff on the zone dot (a sim change; replaces the rebound-steering task)

Chris: "the goalie should kick out the puck to a teammate only some of the time, and otherwise it should behave as if it was caught, and then there's a face-off on the dot in the same zone, just like real hockey. That should be the primary case."

He chose to keep a few loose rebounds. This supersedes commits c88e162 and ff257e0 (rebound steering on every save). Keep their `openTeammate` helper and lead-by-rebound-speed math, and replace the rest.

Global Constraint 1 is waived for: `sim/` (GoalieLogic.kt, Flow.kt, Rink.kt, Tuning.kt, Entities.kt, SimEvent.kt), `ai/` (Difficulty.kt, Match.kt, and the AI's phase handling), and the render or game code that must handle a new phase.

**Files:**
- Modify: `sim/GoalieLogic.kt`
- Modify: `sim/Flow.kt` (new `Phase.WHISTLE`, `startFaceoff(dotX, dotY)`)
- Modify: `sim/Rink.kt` (move `FACEOFF_X = 20f` and `FACEOFF_Y = 6.7f` here from `RinkPainter`, which then reads them from Rink)
- Modify: `sim/Tuning.kt`, `sim/SimEvent.kt`, `ai/Difficulty.kt`
- Modify: every exhaustive `when (phase)` in render, game and ai
- Test: `sim/GoalieTest.kt`, `sim/FlowTest.kt`, `ai/BalanceTest.kt` (run only, never loosened)

**Interfaces and decisions:**
- **Save outcome.** Every save, whether from `trySave` or `tryStrip`, rolls one outcome with one `random.nextFloat()` `u`, using the save-making goalie's `GoalieSkill` split:
  - `u < catch` (default 0.65): **caught**. The goalie holds the puck at his position, and the sim emits `SimEvent.Cover` and then `SimEvent.Whistle(zoneSide = g.side)`. Phase goes to `WHISTLE` for `Tuning.WHISTLE_TIME = 1.0f` with the clock stopped.
  - `u < catch + kick` (default kick 0.25): **kicked to a teammate**. If `openTeammate(g)` is non-null, the rebound goes straight to him at `min(speed · REBOUND_KEEP, PASS_SPEED)`, led by that speed, with `passTarget` set and `Pass(side, -1, id)` emitted, as in ff257e0. If nobody is open, it is caught instead.
  - Otherwise: a **loose rebound** exactly as before. Draw the angle as today, after `u`.
  - A slow puck, below `COVER_SPEED`, is always caught; it rolls no `u`.
- **The old covered-puck release pass is gone.** A caught or covered puck always leads to the whistle and the faceoff. Remove `release`/`releaseTarget` and `COVER_TIME` if they become unused.
- **`GoalieSkill(reactionS, speed, saveBase, catch = 0.65f, kick = 0.25f)`.**
  - Difficulty sets the computer goalie's split. The player's goalie keeps the TEAMMATE skill, with a split that may depend on the selected difficulty (see the balance step).
  - Start every split at 0.65 / 0.25 / 0.10.
- **`Phase.WHISTLE`.** The clock stops, there are no saves, and the puck stays with the holding goalie. Skaters coast: no AI decisions, and the test bots idle, as in CELEBRATE. When it expires, call `startFaceoff(dotX, dotY)`.
  - The dot is in the zone of the goalie who caught it: `dotX = g.side`'s goal-line sign · `Rink.FACEOFF_X`, matching the goalie's own end. `dotY = ±Rink.FACEOFF_Y`, on the same side as the puck's y at the catch (y ≥ 0 gives +).
  - `Phase.afterGoal` stays false in WHISTLE.
- **`startFaceoff(dotX = 0f, dotY = 0f)`.** The existing centre-ice formation is unchanged when called with (0, 0).
  - For a zone dot, offset the formation around the dot:
    - Centres at the dot ±1.2 m along x. Each side's centre stands on his own goal side.
    - Wingers 3.5 m to either side of the dot in y, about 1.5 m back toward their own goal.
    - Defencemen about 6 m back toward their own goal, at dotY ± 3 m.
    - Everything is clamped inside the rink (`Rink.clamp`) and kept clear of the crease.
  - The puck starts at the dot, and the drop logic is unchanged.
- **Render and HUD.**
  - CanvasRenderer, AnimDirector and GameViewModel handle WHISTLE wherever they switch on phase.
  - The goalie butterfly holds while the goalie is holding, and clears on FaceoffDrop (existing behaviour).
  - The camera follows the puck to the dot (the existing follow).
  - No banner.

- [ ] **Step 1: Write failing tests**
  - `caught_save_whistles_and_faces_off_in_that_zone`: force a catch (a skill with catch = 1). After the save, the phase is WHISTLE and the clock is frozen. After `WHISTLE_TIME`, the phase is FACEOFF with the puck at (goalie-end sign · 20, ±6.7), on the side of the catch.
  - `kick_goes_to_open_teammate`: a skill with catch = 0 and kick = 1, with an open teammate, gives passTarget set and a Pass event.
  - `kick_with_nobody_open_is_caught`: the same skill with no open teammate gives WHISTLE.
  - `loose_rebound_still_happens`: a skill with catch = 0 and kick = 0 gives a loose rebound as before.
  - `zone_faceoff_formation_is_on_the_dot`: both centres are within 1.5 m of the dot, every skater is inside the rink, and nobody is in the crease.
  - `centre_faceoff_unchanged`: `startFaceoff()` gives the old positions.
  - `whistle_freezes_the_clock`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement as specified, allocation-free in the tick.
- [ ] **Step 4: Balance.**
  - Baseline over 200 seeds (pre-Task-6, ec71b09): Easy kid 100%, little 100%; Medium kid 75%, little 1%, skilled 88%; Hard kid 21%, skilled 54%.
  - After the change, the 200-seed rates must land within ±5 points of every baseline number, and the existing BalanceTest must pass with no threshold or seed count changed.
  - The dials are the per-difficulty catch/kick/loose splits: the computer goalie's from `Difficulty`, and the player's goalie's chosen by the selected difficulty. Keep the splits plausible (catch 0.5..0.8, loose ≤ 0.2).
  - If no setting meets both, stop and report the best values found, with their numbers.
- [ ] **Step 5:** Commit in this order:
  - `feat(sim): goalies catch most saves; caught pucks face off on the zone dot`
  - `feat(ai): tune save outcomes per difficulty` (if needed)

### Task 6b: No triangle above the controlled player; a yellow ring under another carrier (added by Chris mid-plan)

Chris: "no triangle above the active player... the green circle around their feet is enough".

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/CanvasRenderer.kt` (the marker draw call near the carrier/controlled player), and `FigurePainter`'s marker function if it becomes unused
- Test: `CanvasRendererTest`, `PreviewImagesTest` (any marker assertions)

**Interfaces:**
- Remove the downward triangle marker drawn above the controlled player.
- Keep the green ring at his feet.
- Keep the off-screen pass-target edge arrow. It uses `markerScale(sim)` for its size, so keep `markerScale` and whatever sizing it needs.
- Delete marker-only drawing code that becomes unused.

- Chris also asked for a yellow ring at the puck carrier's feet when the carrier is not the controlled player, whether a teammate or an opponent: "you can add a yellow circle around the feet of the person who has the puck if they are not you".
  - It uses the same yellow ring (outline plus band, `PASS_RING_R`, `PASS_YELLOW`) as the pass-target ring.
  - The two never show together: the pass-target ring only appears while you carry.
  - It is drawn in the same place as the other rings, before the figures.

- [ ] **Step 1b:** Write a failing test `yellow_ring_under_another_carrier`. With puck.carrier set to a non-controlled skater (one teammate case, one opponent case), the ring's band pixel at his feet is yellow. With the controlled skater carrying, there is no yellow ring at his own feet.
- [ ] **Step 1:** Write a failing test `no_marker_above_the_controlled_player`. In a CanvasRenderer pixel test with the controlled skater in open ice, the area from 2.0 to 2.6 m above his position (projected) contains only ice-coloured pixels. Update or remove any test that asserts the marker exists (for example the old Review Focus 3 marker checks), keeping the ring's tests.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Remove the marker draw and any dead code.
- [ ] **Step 4:** Run the full suite. Read `game.png` and `stickhandling.png`: the ring is still clear, and there is no triangle.
- [ ] **Step 5:** Commit `feat(render): drop the triangle above the controlled player; the ring is enough`.

### Task 6c: The goal banner names the scorer (added by Chris mid-plan)

Chris: "when it says who scored, say the player name... for example 'Boldy scores for the Wild!'"

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/teams/Teams.kt` (add `Team.nickname`, and fill `Player.name` for every skater)
- Modify: `app/src/main/java/com/personal/hockey/game/GameViewModel.kt` (`bannerFor`)
- Modify: `app/src/main/java/com/personal/hockey/game/GameScreen.kt` (shrink the goal banner's text to fit)
- Test: `GameViewModelTest`, `GameScreenTest`, `TeamsTest` (or the existing teams test)

**Names, in Position order C, LW, RW, LD, RD, keyed to the numbers already in `Teams.all`:**
- MIN, "Wild": 14 Eriksson Ek, 97 Kaprizov, 12 Boldy, 43 Hughes, 7 Faber
- COL, "Avalanche": 29 MacKinnon, 62 Lehkonen, 88 Necas, 7 Toews, 8 Makar
- EDM, "Oilers": 97 McDavid, 29 Draisaitl, 18 Hyman, 14 Ekholm, 2 Bouchard
- TOR, "Maple Leafs": 34 Matthews, 23 Knies, 88 Nylander, 44 Rielly, 8 Tanev

Use surnames only, exactly as above. `Team.nickname` is a new constructor parameter of `team(...)`. Goalies have no name; goalies don't score.

**Banner rule:**
- During `phase.afterGoal`, look up the scorer as `sim.skaters[sim.lastScorerId]`. Then take the team and player through `MatchSetup` (`setup.home`/`setup.away`), using the skater's side and `pos.ordinal`.
- Always name the player (Chris: "always label the player name (even if own goal)").
- The text is always `"<Name> scores for the <Nickname>!"`, where `<Nickname>` is the scorer's own team, even on an own goal. Chris: "own goal should still read 'Faber scores for the Wild!'"
- Only when `lastScorerId` is not a valid skater is the text `"Goal for the <Nickname>!"`. Every skater has a name, so a missing name never happens.
- If `lastGoalSide` is null, the text is `"GOAL!"`.
- Other banners are unchanged.
- **Fit:** while `goalBanner`, the text starts at the current size and shrinks, down to a minimum of 24 sp, until it fits the clear band between the stick and the buttons. Use `ControlsLayout` geometry, as the existing overlap test does. If it still doesn't fit at 24 sp, it wraps to 2 lines. The existing `goal_banner_clear_of_controls` test must pass with the longest text, "Eriksson Ek scores for the Maple Leafs!", at 800×360 dp.

- [ ] **Step 1: Write failing tests**
  - `banner_names_the_scorer`: MIN home, Boldy (RW) scores. The banner is "Boldy scores for the Wild!".
  - `own_goal_banner_names_the_player`: MIN's Faber scores into his own net, so COL is credited. The banner is "Faber scores for the Wild!".
  - `every_skater_has_a_name`: every `Teams.all` skater has a non-blank name, and every team has a nickname.
  - Extend the banner-overlap test with the longest string.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement as specified. `bannerFor` already caches by phase, period and scorer. Add `lastScorerId` to the cache key, so the HUD does not allocate a string per frame.
- [ ] **Step 4:** Run the full suite.
- [ ] **Step 5:** Read `game-goal.png` and `celebration.png`. Commit `feat(game): goal banner names the scorer`.

### Task 7: Final pass

**Files:** `README.md`, plus no new code unless a preview shows a defect.

- [ ] **Step 1:** Run `./gradlew clean testDebugUnitTest`. Expected: BUILD SUCCESSFUL, 0 failures.
- [ ] **Step 2:** Read every game preview PNG together. Check that the lifted puck, shadow, back numbers and butterfly are consistent and readable. List anything off in the report; don't fix large issues.
- [ ] **Step 3:** Update the README's game bullet: gliding skaters, shots that fly, the goalie butterfly, numbers on the back.
- [ ] **Step 4:** Run `./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `docs: README for the feel pass`.
