# Goalie Realism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Goalies position themselves on an arc that cuts down the angle and seal the post. They face and follow the puck, stand in a ready stance until shot at, and shuffle or T-push when they move.

**Architecture:**
- **Positioning** is in the sim (`sim/GoalieLogic.kt` `track`). Saves already measure from the goalie's position, so they now happen where he stands.
- **Facing, stance and movement** are drawing-only. Per-goalie `AnimState` fields are updated in `AnimDirector.onTick`, and `FigurePainter.poseGoalie` poses in that frame.
- **Balance** is restored through each level's `GoalieSkill`.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, JUnit4 + Robolectric (NATIVE graphics, SDK 34).

**Spec:** docs/superpowers/specs/2026-11-17-goalie-realism-design.md

## Global Constraints

- The sim stays pure Kotlin and deterministic. `track` makes no random draws, and nothing render-side feeds the sim.
- No per-frame or per-tick allocation in the draw, sim or anim paths (NoAllocTest and the AnimDirectorTest allocation tests). The 8,000-triangle budget still holds.
- **Balance acceptance:** over 200 seeds, every win rate is within ±5 points of the baseline. BalanceTest passes unloosened, and a re-baselined bound needs Chris's approval through the controller.

  | Matchup | Baseline |
  |---|---|
  | kid vs Easy | 100 |
  | little vs Easy | 100 |
  | kid vs Medium | 80 |
  | little vs Medium | 0 |
  | skilled vs Medium | 91 |
  | kid vs Hard | 26 |
  | skilled vs Hard | 56.5 |

- Saves, strips, catches, kicks, the outcome split and the rebound direction keep their current rules.
- Commit on main. End every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Full run:** `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew clean testDebugUnitTest`. It must be green before each task's final commit.
- **Visual tasks:** read the preview PNGs in `app/build/preview/` (written by `PreviewImagesTest`, which must call `renderer.onTick(sim)` per sim step).

## Review Focus

1. **A faceoff or goal reset teleports the goalie.** Expected: no spin, no T-push flash. Facing settles to straight out. Pinned in Task 2 and Task 3.
2. **The puck swings past ±π relative to straight out,** for example behind the net from one corner to the other. Expected: facing turns the short way within the clamp and never flips through his back. Pinned in Task 2.
3. **The puck crosses behind the net.** Expected: the goalie goes post to post along the goal mouth and never enters the net (`(g.x − goalLineX)·dir ≥ 0.3`). Pinned in Task 1.
4. **The puck sits on the goalie** (he's holding it, or it's within 0.05 m). Expected: facing holds and doesn't jump to an arbitrary angle. Pinned in Task 2.
5. **Speed hovers around the shuffle/T-push threshold.** Expected: no flicker between the two. The weights ease and there's hysteresis. Pinned in Task 3.

---

### Task 1: Arc positioning in the sim, and balance

**Model:** opus.

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/sim/GoalieLogic.kt` (`track`, `resetGoalie`)
- Modify: `app/src/main/java/com/personal/hockey/sim/Tuning.kt` (Goalies section)
- Modify: `app/src/main/java/com/personal/hockey/ai/Difficulty.kt` (balance only)
- Test: `app/src/test/java/com/personal/hockey/sim/GoalieTest.kt`, `app/src/test/java/com/personal/hockey/ai/BalanceTest.kt` (only with a ruling)

**Interfaces:**
- **Produces these Tuning constants:**

  | Constant | Value | Meaning |
  |---|---|---|
  | `GOALIE_DEPTH_OUT` | 1.5f | depth when challenging |
  | `GOALIE_DEPTH_IN` | 0.6f | depth when the puck is close |
  | `GOALIE_CHALLENGE_R` | 6f | at or beyond this, full depth |
  | `GOALIE_CLOSE_R` | 3f | within this, deep |
  | `GOALIE_MAX_ANGLE` | 1.22f | rad (70°) |
  | `GOALIE_POST_OUT` | 0.35f | metres out from the goal line when sealing a post |
  | `GOALIE_POST_IN` | 0.25f | metres in from the post |

- **Removes `GOALIE_DEPTH`.** Fix its uses: `resetGoalie` and any test that reads it.
- **Produces `internal fun goalieTarget(lineX: Float, dir: Float, puckX: Float, puckY: Float, out: FloatArray)`** in GoalieLogic.kt. It writes the target `(x, y)` into `out[0..1]` and is a pure function, so it can be tested directly.

- [ ] **Step 1: Write the failing tests** in GoalieTest. The AWAY goalie defends `+26`, with `dir = -1` and `goalLineX = 26`.

```kotlin
private val target = FloatArray(2)

@Test fun goalie_challenges_a_far_puck_at_the_top_of_the_crease() {
    goalieTarget(26f, -1f, 15f, 8f, target)
    val r = sqrt(11f * 11f + 8f * 8f)
    assertEquals(26f - 1.5f * 11f / r, target[0], 0.01f)
    assertEquals(1.5f * 8f / r, target[1], 0.01f)
}

@Test fun goalie_backs_in_as_the_puck_closes() {
    goalieTarget(26f, -1f, 24f, 0f, target)   // 2 m out: deep
    assertEquals(25.4f, target[0], 0.01f); assertEquals(0f, target[1], 0.01f)
    goalieTarget(26f, -1f, 21.5f, 0f, target) // 4.5 m: halfway between 3 and 6
    assertEquals(26f - 1.05f, target[0], 0.01f)
}

@Test fun goalie_angle_is_clamped_near_the_goal_line() {
    goalieTarget(26f, -1f, 25.5f, 8f, target)  // nearly along the goal line
    val a = atan2(target[1], 26f - target[0])
    assertEquals(Tuning.GOALIE_MAX_ANGLE, a, 0.01f)
}

@Test fun goalie_seals_the_near_post_with_the_puck_behind_the_net() {
    goalieTarget(26f, -1f, 28f, -3f, target)
    assertEquals(26f - 0.35f, target[0], 0.01f)
    assertEquals(-(Rink.GOAL_HALF_W - 0.25f), target[1], 0.01f)
}

@Test fun goalie_never_enters_the_net_as_the_puck_goes_round_behind_it() {
    val sim = sim()
    val g = sim.goalies[away]
    sim.run(3f) { t -> val a = t * 0.05f; sim.loose(26f + 2.5f * cos(a), 2.5f * sin(a) * 2f, 0f, 0f) }
    // and, each tick, record (26f - g.x) >= 0.3f
}

@Test fun goalie_move_is_capped_at_his_speed_in_x_and_y() {
    // From the reset spot, the puck jumps to the far corner. After one tick he has moved at most
    // speed·DT (+1e-4).
}

@Test fun tracking_draws_no_random_numbers() {
    // With a counting Random and the goalie away from the puck's path for 2 s: draws == 0 from track.
    // Use the existing counting-Random pattern from `slow_save_rolls_no_outcome`.
}
```

Rewrite `goalie_stays_between_puck_and_net` for the arc. With the puck at (15, 8), the goalie ends at `goalieTarget(26, -1, 15, 8)` within 0.05 m.

- [ ] **Step 2: Run the tests to verify they fail.**
  - Run: `./gradlew testDebugUnitTest --tests '*GoalieTest*'`
  - Expected: FAIL. `goalieTarget` is unresolved.

- [ ] **Step 3: Implement `goalieTarget`, then use it in `track` and `resetGoalie`.**
  - **Depth:** eases linearly from `GOALIE_DEPTH_IN` at r ≤ `GOALIE_CLOSE_R` to `GOALIE_DEPTH_OUT` at r ≥ `GOALIE_CHALLENGE_R`. Here r is the stale puck's distance from `(lineX, 0)`.
  - **Angle:** `atan2(py, (px − lineX)·dir)`, clamped to ±`GOALIE_MAX_ANGLE`.
  - **Behind the goal line,** where `(px − lineX)·dir ≤ 0`: the post-seal point on the stale puck's side (`y ≥ 0` → `+`).
  - **`track`:** steps toward the target, limited to `skill.speed·DT` along the straight line to it in both x and y.
  - **`resetGoalie`:** puts him at `(lineX + dir·GOALIE_DEPTH_OUT, 0)`.

- [ ] **Step 4: Run GoalieTest and the full suite.**
  - Expected: GoalieTest PASSES.
  - Other suites may fail where they assumed the 1 m line, including BalanceTest. List every failure in the report.
  - Fix non-balance tests whose scene relied on the old line by updating the scene, not the assertion's intent.

- [ ] **Step 5: Measure balance.**
  - Run a temporary 200-seed measurement of the seven matchups (don't commit the harness).
  - Report each rate against the baseline in Global Constraints.

- [ ] **Step 6: Tune until acceptance holds.**
  - Adjust each level's `GoalieSkill` (`saveBase`, then `reactionS`, then `speed`) and `TEAMMATE.goalie`. Prefer changing as few numbers as possible.
  - If acceptance can't be met without moving a BalanceTest bound or a positioning constant outside the spec's ranges, stop. Report BLOCKED with the table and the closest settings.
  - Comment each changed `GoalieSkill` with old → new.

- [ ] **Step 7: Run the full suite.**
  - Run: `./gradlew clean testDebugUnitTest`
  - Expected: all green.

- [ ] **Step 8: Commit.** Two commits:
  - `feat(sim): goalies cut the angle on an arc and seal the post`
  - `chore(ai): re-tune goalies for arc positioning`

---

### Task 2: The goalie faces the puck (drawing)

**Model:** sonnet.

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/anim/AnimDirector.kt` (`AnimState`, `onTick`)
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` (`poseGoalie(camera, g, anim)` `begin` call)
- Test: `app/src/test/java/com/personal/hockey/render/anim/GoalieFacingTest.kt` (new)

**Interfaces:**
- **Consumes:** sim goalie `x, y, holding, side`; `sim.puck.x/y`; `sim.phase`.
- **Produces:**
  - `AnimState.facing: Float`, used by goalies only. It is the drawn facing in radians, rink frame.
  - `const val GOALIE_TURN_RATE = 6f` (rad/s) and `const val GOALIE_FACE_MAX = 1.92f` (rad, 110°), both in AnimDirector.kt.
  - `FigurePainter.poseGoalie` calls `begin(camera, g.x, g.y, anim.facing)`.
- **Spec deviation (record it):** the spec says facing "advances by frame time". Update it per sim tick in `onTick`. At 60 Hz this is equivalent, and it can be tested deterministically.

- [ ] **Step 1: Write the failing tests** in GoalieFacingTest. Drive `AnimDirector.onTick` over a `HockeySim` in PLAY.

```kotlin
@Test fun goalie_turns_toward_the_puck_at_the_turn_rate()
// The AWAY goalie (straight out = π) and the puck at 30° off straight out. After 1 tick, facing
// moved by ≤ GOALIE_TURN_RATE·DT (+1e-4). After 1 s, it is within 0.02 rad of the angle to the puck.

@Test fun facing_is_clamped_with_the_puck_behind_the_net()
// The puck directly behind the AWAY net (x = 28, y = 0.5). After 2 s,
// |wrap(facing − π)| == GOALIE_FACE_MAX (±0.01).

@Test fun facing_turns_the_short_way_across_the_back()
// The puck moves behind the net from y = +3 to y = −3 over 1 s. Facing never passes through
// straight in: |wrap(facing − π)| stays ≤ GOALIE_FACE_MAX + 0.01 every tick.

@Test fun facing_holds_when_the_puck_is_on_the_goalie()
// Puck at the goalie's position (holding = true): facing eases toward straight out and never
// changes by more than GOALIE_TURN_RATE·DT in a tick.

@Test fun a_faceoff_reset_does_not_spin_the_goalie()
// After sim.startFaceoff(...) teleports everyone, facing changes by ≤ GOALIE_TURN_RATE·DT per tick.

@Test fun goalie_facing_allocates_nothing()
// The existing AnimDirectorTest allocation-measurement pattern over 600 ticks with a moving puck.
```

- [ ] **Step 2: Run the tests to verify they fail.**
  - Run: `./gradlew testDebugUnitTest --tests '*GoalieFacingTest*'`
  - Expected: FAIL. `facing` is unresolved.

- [ ] **Step 3: Implement the facing.** In `onTick`, for each goalie:
  - **Desired angle:**
    - straight out (`0` for HOME, `π` for AWAY) while he is holding, while `sim.phase` isn't PLAY, or when the puck is within 0.05 m;
    - otherwise `atan2(puck.y − g.y, puck.x − g.x)`, with its offset from straight out wrapped and clamped to ±`GOALIE_FACE_MAX`.
  - **Easing:** ease the offset (not the absolute angle) toward the desired offset by at most `GOALIE_TURN_RATE·DT`. Working with the offset keeps him from turning through his back.
  - **First tick:** start at straight out.
  - **Painter:** `FigurePainter.poseGoalie` uses `anim.facing`.

- [ ] **Step 4: Run GoalieFacingTest, ButterflyTest and the full suite.**
  - Expected: PASS.
  - The butterfly and slump clips are keyed in the figure frame, so they turn with him.

- [ ] **Step 5: Commit** `feat(render): goalies face and follow the puck`.

---

### Task 3: Ready stance, shuffle and T-push (drawing)

**Model:** opus.

**Files:**
- Modify: `app/src/main/java/com/personal/hockey/render/anim/AnimDirector.kt` (`AnimState`, `onTick`)
- Modify: `app/src/main/java/com/personal/hockey/render/FigurePainter.kt` (the private `poseGoalie(armsOut)` ready pose, plus shuffle and T-push layers)
- Test: `app/src/test/java/com/personal/hockey/render/GoalieStanceTest.kt` (new); keep `ButterflyTest` green

**Interfaces:**
- **Consumes:** Task 2's `AnimState.facing`.
- **Produces these `AnimState` fields** (goalies only):

  | Field | Meaning |
  |---|---|
  | `moveX`, `moveY` | smoothed drawn velocity, m/s |
  | `shuffleW` | 0..1 |
  | `pushW` | 0..1 |
  | `stepPhase` | radians |
  | `internal lastX`, `internal lastY` | previous position |

- **Produces these constants** in AnimDirector.kt:

  | Constant | Value | Meaning |
  |---|---|---|
  | `GOALIE_SHUFFLE_SPEED` | 2.5f | m/s: T-push above this |
  | `GOALIE_SPEED_HYST` | 0.4f | m/s each side of it |
  | `GOALIE_MOVE_BLEND_S` | 0.12f | blend time |
  | `GOALIE_JUMP_M` | 0.5f | a move larger than this in one tick is a reset, not movement |
  | `GOALIE_STILL_SPEED` | 0.3f | below this he's at rest |

- [ ] **Step 1: Write the failing tests** in GoalieStanceTest. Pose through `FigurePainter.poseGoalie` and read the joint slots the same way ButterflyTest does.

```kotlin
@Test fun ready_stance_is_standing()        // hip centre z ≥ 0.74, and both knees above 0.35 m
@Test fun ready_stance_holds_until_a_shot() // 3 s of PLAY with the puck moving 10 m out and no Shot: clip stays NONE, hips ≥ 0.74
@Test fun a_shot_still_drops_him()          // existing shot_drops_the_defending_goalie, unchanged, still passes
@Test fun slow_lateral_move_shuffles()      // goalie moving at 1.5 m/s: shuffleW → 1, pushW → 0 within 0.3 s, and the feet's lateral offsets alternate over a step cycle
@Test fun fast_lateral_move_t_pushes()      // 5 m/s: pushW → 1; the trail leg's ankle is ≥ 0.6 m from the hip centre on the side away from the move
@Test fun near_the_threshold_it_does_not_flicker() // speed oscillating 2.3–2.7 m/s each tick for 2 s: pushW changes sign of its slope at most twice
@Test fun a_reset_jump_is_not_a_t_push()    // teleport 3 m in one tick: pushW and shuffleW stay 0 that tick and the next
@Test fun stance_allocates_nothing()        // the allocation-measurement pattern over 600 ticks of movement
```

- [ ] **Step 2: Run the tests to verify they fail.**
  - Run: `./gradlew testDebugUnitTest --tests '*GoalieStanceTest*'`
  - Expected: FAIL.

- [ ] **Step 3: Implement the stance and movement.**
  - **Ready pose:** replace the squat in `poseGoalie(armsOut)` with a standing ready pose.
    - Hips about 0.78 m, knees bent, a slight forward lean, pads close.
    - Glove up and open, blocker and stick blade on the ice in front.
    - Keep the `armsOut` save-flash variant.
  - **Movement tracking:** in `onTick`, derive the move from the position change per tick and ignore any step over `GOALIE_JUMP_M`. Smooth it into `moveX/moveY`.
  - **Weights:** ease `shuffleW` and `pushW` over `GOALIE_MOVE_BLEND_S`.
    - T-push turns on above `GOALIE_SHUFFLE_SPEED + GOALIE_SPEED_HYST` and off below `− GOALIE_SPEED_HYST`.
    - Shuffle covers moving speeds below the T-push.
    - Both fade to 0 below `GOALIE_STILL_SPEED`.
  - **Step cycle:** advance `stepPhase` with speed.
  - **Painter:** lays the shuffle and T-push leg offsets over the ready pose by their weights, before `blendPose(anim, ...)`, so the butterfly clip still overrides them.

- [ ] **Step 4: Run GoalieStanceTest, ButterflyTest, the AnimDirectorTest allocation tests, NoAllocTest and the full suite.**
  - Expected: PASS.

- [ ] **Step 5: Check the previews.**
  - Run: `./gradlew testDebugUnitTest --tests '*PreviewImagesTest*'`
  - Then read `goalie-close-up.png`, `goalie-close-up-far-end.png` and `goalie-butterfly.png`.
  - Expected: he reads as standing in close-ups, and the butterfly is unchanged.

- [ ] **Step 6: Commit** `feat(render): goalie ready stance, shuffle and T-push`.

---

### Task 4: Previews, README, build

**Model:** sonnet.

**Files:**
- Modify: `app/src/test/java/com/personal/hockey/ui/PreviewImagesTest.kt`
- Modify: `README.md` (the game bullet's goalie sentence)

- [ ] **Step 1: Add three previews.** Each runs `renderer.onTick(sim)` per step.
  - **`goalie-angle.png`:** the puck carried at a sharp angle near the AWAY faceoff dot. The goalie is out on his arc, facing the carrier.
  - **`goalie-post.png`:** the puck behind the AWAY net. The goalie seals the near post, facing the corner.
  - **`goalie-t-push.png`:** a cross-crease pass, captured mid-move with `pushW > 0.8`.

- [ ] **Step 2: Run the previews and read all goalie PNGs.**
  - Run: `./gradlew testDebugUnitTest --tests '*PreviewImagesTest*'`
  - Expected:
    - He faces the puck in each.
    - He stays out of the net in the post image.
    - The trail leg is extended in the T-push image.
  - List anything that looks off in the report.

- [ ] **Step 3: Update the README.** Change the goalie sentence to say goalies square up to the puck, come out to cut the angle, seal the post, shuffle and T-push, and drop into the butterfly on shots.

- [ ] **Step 4: Run the full suite and build.**
  - Run: `./gradlew clean testDebugUnitTest assembleDebug`
  - Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 5: Commit** `docs: goalie previews and README`.
