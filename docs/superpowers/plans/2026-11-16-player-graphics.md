# Player Graphics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A closer camera, shaded low-poly 3D hockey players built in code, and new animations:
stickhandling, wrist shot, slapshot, one-timer, crossovers, the hockey stop and goal celebrations.

**Architecture:**
- The skeleton `FigurePainter` already poses (joint slots in a figure frame) stays the source of pose.
- Small meshes built at start-up hang off its bones. Each frame they're transformed, lit, projected
  through `Camera`, back-face culled, bucket-sorted far to near, and emitted through a `TriangleSink`
  (`Canvas.drawVertices`, or a flat-shaded path fallback).
- A render-side `AnimDirector`, fed every sim tick, blends keyframed clips over the procedural
  skating pose.
- The sim gains only the celebration hooks and skater ids on its events.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, `android.graphics.Canvas.drawVertices`, JUnit 4, and
Robolectric 4 (`GraphicsMode.NATIVE`, SDK 34).

**Spec:** `docs/superpowers/specs/2026-11-16-player-graphics-design.md`. Read it before starting any task.
Its "Settled after the builder review" section overrides earlier wording.

## Global Constraints

- No game engine, no OpenGL, no image assets, no imported models. Everything is drawn in code on the
  Compose `Canvas`.
- `sim` stays pure Kotlin with no Android imports. `sim/NoAllocTest` must keep passing.
- Drawing must not allocate per frame. Preallocate every buffer, and use no lambdas, iterators or boxing in
  draw paths.
- Nothing in `render` names a team. Colours come from `teams.Kit` via `KitColors`.
- Must hold 60 fps on a Pixel 9. At most 8,000 triangles a frame across all figures.
- The puck, control ring, pass ring and carrier marker stay at least as readable as today.
- Commit straight to `main`. End each commit message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Test commands need
  `export ANDROID_HOME=~/Library/Android/sdk; export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
  Run a class with `./gradlew testDebugUnitTest --tests '<fqcn>'`.
- **Visual tasks:** after the tests pass, run
  `./gradlew testDebugUnitTest --tests 'com.personal.hockey.ui.PreviewImagesTest'`. Then open each PNG
  in `app/build/preview/` with the Read tool, judge it against the spec, and iterate before committing.
  Say in the report what you looked at.

## Review Focus

1. **A dark kit (MIN green) on a skater at the far boards** must stand out from the dark boards and
   crowd behind it, even without outlines. Test in Task 7.
2. **A skater facing any of 16 directions**, including straight at or away from the camera and
   edge-on, draws with no NaN, no missing body, and a readable number. Test in Task 7.
3. **A puck along the far boards** must stay below the scoreboard HUD. A puck along the near boards
   must stay above the bottom edge. Test in Task 4.
4. **A goal scored with no time left**, where the goal pause goes straight to the period break, must
   clear the celebration roles and zoom the camera back out. Test in Task 13.
5. **A carried puck while the blade is off the ice** (wind-up, slapshot lift) must stay on the ice at
   the sim's carry point, not float with the blade. Test in Task 10.

---

## File map

| File | Responsibility |
|---|---|
| `sim/SimEvent.kt` | Skater ids on Shot, Pass, Pickup and Goal |
| `sim/HockeySim.kt`, `sim/Flow.kt`, `sim/Actions.kt`, `sim/GoalieLogic.kt`, `sim/PuckPhysics.kt` | Track who touched the puck, `lastScorerId` |
| `sim/Entities.kt`, `sim/Skating.kt`, `sim/Tuning.kt` | `Skater.stopping`, celebration steering, `GOAL_TIME` |
| `render/Camera.kt` | Zoom, lower eye, `focusY`, zoom goal |
| `render/mesh/TriangleSink.kt` | `TriangleSink`, `VerticesSink`, `PathSink`, `TriangleSinks.default()` |
| `render/mesh/Mesh.kt` | `Mesh`, `Part`, `MeshBuilder` primitives |
| `render/mesh/MeshPainter.kt` | Bone frames, lighting, projection, culling, bucket sort, emit |
| `render/figure/Skeleton.kt` | Joint slot constants and `Pose`, moved out of `FigurePainter` |
| `render/figure/SkaterBody.kt`, `render/figure/GoalieBody.kt` | Part lists and palette slots |
| `render/FigurePainter.kt` | Posing (procedural plus clips), drawing figures through `MeshPainter`, nets, puck, rings |
| `render/anim/Clips.kt` | `Clip` enum, `ClipData`, `ClipLibrary` |
| `render/anim/AnimDirector.kt` | `AnimState` per figure, triggers from each tick, blending |
| `render/Effects.kt` | Snow-spray particle pool and puck streak |
| `render/CanvasRenderer.kt` | `onTick`, camera feeding, edge arrow, puck drawn at the blade, effects |
| `game/GameViewModel.kt` | Calls `renderer.onTick(sim)` after every tick |

---

### Task 1: Triangle sinks and the Robolectric spike

**Model:** sonnet

**Files:**
- Create: `app/src/main/java/com/personal/hockey/render/mesh/TriangleSink.kt`
- Test: `app/src/test/java/com/personal/hockey/render/mesh/TriangleSinkTest.kt` (Robolectric, `GraphicsMode.NATIVE`,
  rendered the way `NumberViewTest.render` does it, into a 400×400 `ImageBitmap` via `CanvasDrawScope`)

**Interfaces:**
- Produces:
  ```kotlin
  interface TriangleSink {
      val count: Int
      fun begin()
      /** Screen pixels, ARGB colours per vertex. Ignored once full. */
      fun add(x0: Float, y0: Float, c0: Int, x1: Float, y1: Float, c1: Int, x2: Float, y2: Float, c2: Int)
      fun flush(scope: DrawScope)
  }
  class VerticesSink(capacity: Int = MAX_SINK_TRIANGLES) : TriangleSink
  class PathSink(capacity: Int = MAX_SINK_TRIANGLES) : TriangleSink
  object TriangleSinks { fun default(): TriangleSink }
  const val MAX_SINK_TRIANGLES = 1024
  ```

- [ ] **Step 1: Write the failing tests.**
  - `vertices_sink_fills_a_triangle_with_blended_vertex_colours`: draw one triangle with red, green and
    blue corners on a white background. The centroid pixel is not white and has all three channels
    above 0.15. A pixel 5 px inside the red corner has red > 0.7. A pixel outside the triangle is white.
  - `path_sink_fills_a_triangle_flat_with_the_average_colour`: the same triangle, with the centroid pixel
    within 0.05 per channel of (1/3, 1/3, 1/3).
  - `sinks_drop_triangles_past_capacity`: a capacity of 2 and 3 adds gives `count == 2`.
  - `begin_clears`: after `begin()`, `count == 0`.
- [ ] **Step 2: Run the tests and confirm they fail.** Expected: unresolved `TriangleSink`.
- [ ] **Step 3: Implement.**
  - `VerticesSink` fills preallocated `FloatArray` verts and `IntArray` colours, then calls
    `scope.drawContext.canvas.nativeCanvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * 6, verts, 0,
    null, 0, colors, 0, null, 0, 0, paint)`.
  - Size `colors` to `2 * capacity * 3`. Some Android versions bounds-check `colors` against
    `vertexCount` (the float count), not the vertex count.
  - `PathSink` reuses one Compose `Path` per triangle, filled with the channel-wise average colour.
  - Neither may allocate in `add` or `flush`.
- [ ] **Step 4: Run the tests.** If the `VerticesSink` test fails only because Robolectric draws
  nothing, that's the spike result:
  - Mark that test `@Ignore("Robolectric cannot render drawVertices")`.
  - Make `TriangleSinks.default()` return `PathSink()` when `Build.FINGERPRINT == "robolectric"`, and
    `VerticesSink()` otherwise.
  - Add a one-line KDoc saying so.
  
  If it passes, `default()` always returns `VerticesSink()`. Either way, report which happened.
- [ ] **Step 5: Commit.** `feat(render): triangle sinks for code-built meshes`

---

### Task 2: Skater ids on events, scorer id and the stop flag

**Model:** sonnet

**Files:**
- Modify: `sim/SimEvent.kt`, `sim/Actions.kt:110,130`, `sim/GoalieLogic.kt:158`, `sim/HockeySim.kt:97` (`giveTo`),
  `sim/Flow.kt:85-103` (`checkGoal`, `startFaceoff`), `sim/Entities.kt` (Skater), `sim/Skating.kt`, `audio/Feedback.kt`
- Modify tests that compare events by equality: `ai/BalanceTest.kt`, `audio/FeedbackDirectorTest.kt`, `sim/GoalieTest.kt`,
  `sim/PuckTest.kt`, `sim/FlowTest.kt`, `sim/ActionsTest.kt`
- Create test helper: `app/src/test/java/com/personal/hockey/sim/EventMatchers.kt`
- Test: `sim/ScorerTest.kt` (new), `sim/SkatingTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class Shot(val side: Side, val power: Float, val shooterId: Int)
  data class Pass(val side: Side, val passerId: Int, val targetId: Int)   // passerId -1: a goalie's release; targetId -1: none
  data class Pickup(val side: Side, val skaterId: Int)
  data class Goal(val scorer: Side, val scorerId: Int)
  HockeySim.lastScorerId: Int          // -1 before any goal; set with lastGoalSide
  Skater.stopping: Boolean             // public get, internal set
  // test helpers
  fun List<SimEvent>.hasGoal(side: Side): Boolean
  fun List<SimEvent>.hasPickup(side: Side): Boolean
  fun List<SimEvent>.hasPass(side: Side): Boolean
  ```

- [ ] **Step 1: Write the failing tests.**
  - `ScorerTest.the_last_skater_to_carry_it_is_the_scorer`: give the puck to HOME skater 2, shoot it into
    the AWAY net. The Goal event has `scorerId == 2`, and `sim.lastScorerId == 2`.
  - `ScorerTest.a_pass_then_shot_credits_the_shooter`: HOME skater 1 passes to 3 and 3 scores. The
    scorer is 3.
  - `ScorerTest.an_own_goal_credits_the_scoring_sides_skater_nearest_that_net`: an AWAY skater carries
    the puck into the AWAY net. The scorer is the HOME skater nearest `x = +Rink.GOAL_X`.
  - `ScorerTest.shot_pass_and_pickup_events_carry_ids`: covers the shooter, passer and target ids, and
    the picker's id.
  - `SkatingTest.stopping_is_set_only_while_hockey_stopping`: a skater at 8 m/s along +x with the
    stick at (−1, 0) has `stopping == true`. With the stick at (0, 1), or centred, it's false.
- [ ] **Step 2: Run them.** Expected: compile errors on the new fields.
- [ ] **Step 3: Implement.**
  - **Touches:** keep `internal val lastTouch = IntArray(2) { -1 }`.
    - Set it in `giveTo` and on any skater deflection in `PuckPhysics`.
    - Reset it in `startFaceoff`.
  - **Scorer:** in `checkGoal`, the scorer is `lastTouch[side]` if `puck.lastSide == side` and that id
    is ≥ 0. Otherwise it's the scoring side's skater nearest `(side.dir * Rink.GOAL_X, 0)`.
  - **Stop flag:** `stopping` is cleared for every skater at the top of `stepSkaters` and set only in
    `control()` when `braking`.
  - **Feedback:** `FeedbackDirector` ignores the new fields.
  - **Old tests:** replace `events.contains(SimEvent.Goal(Side.X))` and similar with the helpers.
- [ ] **Step 4: Run the whole suite.** `./gradlew testDebugUnitTest`. Expected: everything passes,
  `NoAllocTest` included.
- [ ] **Step 5: Commit.** `feat(sim): events name the skater; record the scorer and hockey stops`

---

### Task 3: A longer goal pause, with teammates skating to the scorer

**Model:** sonnet

**Files:**
- Modify: `sim/Tuning.kt`, `sim/Skating.kt:16-27`
- Test: `sim/FlowTest.kt` (check the banner-timing test still holds), `sim/CelebrationTest.kt` (new)

**Interfaces:**
- Consumes: `HockeySim.lastScorerId` (Task 2)
- Produces: `Tuning.GOAL_TIME = 4f`, `Tuning.CELEBRATE_SPEED = 5.4f`, `Tuning.CELEBRATE_GAP = 1.2f`

- [ ] **Step 1: Write the failing tests.**
  - `teammates_gather_round_the_scorer`: score with HOME skater 0 from the slot, then run 3.0 s. Every
    other HOME skater is within `CELEBRATE_GAP + 2 * SKATER_R + 0.5f` of skater 0.
  - `opponents_just_coast`: AWAY skaters' speeds never increase during the pause.
  - `the_pause_lasts_four_seconds`: the phase is still GOAL at 3.9 s and is FACEOFF (or BREAK) by 4.05 s.
  - `nobody_steers_with_no_scorer`: with `lastScorerId == -1` in GOAL, everyone glides as before.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** In `stepSkaters`, the `!live` branch handles `phase == Phase.GOAL` for the
  scorer's teammates.
  - Accelerate at `ACCEL` toward the scorer up to `CELEBRATE_SPEED`.
  - Brake at `COAST_DECEL` once within `CELEBRATE_GAP`.
  - Everyone else uses `glide()`. Separation already keeps them apart.
- [ ] **Step 4: Run the suite.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(sim): longer goal pause; teammates skate to the scorer`

---

### Task 4: A closer, lower camera that follows in y and can zoom

**Model:** opus

**Files:**
- Modify: `render/Camera.kt`, `render/CanvasRenderer.kt:36-40` (`advance`)
- Test: `render/CameraTest.kt`, `render/ThumbClearanceTest.kt` (`settledCamera` takes y as well)

**Interfaces:**
- Produces:
  ```kotlin
  var focusY: Float                                  // eased and clamped like focusX
  fun follow(targetX: Float, targetY: Float, dt: Float)
  fun snapTo(targetX: Float, targetY: Float = 0f)
  var zoomGoal: Float                                // 1 = normal; eased toward in follow() with ZOOM_TAU = 0.4 s
  val zoom: Float
  ```
- Values: `FOCAL_PER_HEIGHT = 4.0f` (2.35 × 1.7) at zoom 1, and `EYE_Z = 12f`. `EYE_Y` and `LOOK_Y` are
  offsets from `focusY`. `project()` subtracts `focusY`, so the y-follow is a pure translation.

- [ ] **Step 1: Update and add tests.**
  - Replace `whole_rink_width_fits_vertically` and `shows_about_34_metres_at_mid_ice` with:
    - `shows_about_20_metres_at_mid_ice`: span ≈ `w` for ±10 m, ±200 px.
    - `a_skater_is_about_1_7_times_bigger_than_before`: scale at (0, 0, 0) / old scale is in 1.55..1.85.
      Hard-code the old value of `focal / DEPTH_AT_MID_ICE`, 0.0684 × h.
    - `follow_tracks_the_puck_in_y_and_clamps`: settle on y = ±`Rink.HALF_W`. `|focusY|` is never
      above the clamp, and changes smoothly.
    - `zoom_eases_to_its_goal`: after 1.2 s, zoom is within 5% of a goal of 1.3.
  - **Review Focus 3:** add `a_puck_along_either_board_stays_between_hud_and_bottom_edge`. For
    x in −28..28 step 4, and for y = `HALF_W − 0.5` and `−HALF_W + 0.5`, settle 4 s with
    `follow(x, y)`. The puck's screen y is in 0.18h..0.85h.
  - `ThumbClearanceTest`: `settledCamera(x, y)` calls `follow(x, y, DT)`. Keep both assertions
    unchanged.
- [ ] **Step 2: Run** `CameraTest` and `ThumbClearanceTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Zoom multiplies the focal length.
  - `focusY` clamp: the view never shows past the far glass top or the near boards' outer edge. Tune
    it to the HUD and edge test.
  - Retune `OVERSHOOT` and `END_OVERSHOOT`, and add a y overshoot toward the near corners, until
    `ThumbClearanceTest` passes.
  - `CanvasRenderer.advance` calls `camera.follow(puck.x + LEAD_S * puck.vx, puck.y + LEAD_S * puck.vy, seconds)`.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Look at `game.png`, `game-corner.png` and
  `game-goal.png`. Update only render tests whose pixel probes relied on the old framing, and keep what
  each one protects.
- [ ] **Step 5: Commit.** `feat(camera): closer and lower, follows the puck in y, can zoom`

---

### Task 5: An edge arrow for an off-screen pass target

**Model:** sonnet

**Files:**
- Modify: `render/CanvasRenderer.kt`, `render/FigurePainter.kt` (an arrow-drawing helper next to `drawCarrierMarker`)
- Test: `render/CanvasRendererTest.kt`

**Interfaces:**
- Produces: `internal fun CanvasRenderer.edgeArrowFor(sim: HockeySim, out: FloatArray): Boolean`. It
  returns true and fills `out` with [screen x, screen y, angle in radians] when the controlled carrier's
  `sim.passTargetFor(carrier)` projects outside the viewport inset by 24 px.

- [ ] **Step 1: Write the failing tests.**
  - `no_arrow_when_the_pass_target_is_on_screen`.
  - `an_arrow_points_from_the_edge_toward_an_off_screen_target`: put the target 25 m up-ice. `out[0]`
    is within 60 px of the right edge, and `cos(out[2]) > 0.7`.
  - `the_arrow_is_drawn_in_pass_yellow`: pass-ring yellow pixels appear within 60 px of that edge.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Clamp the target's projected point to the inset rectangle.
  - Point the arrow along the direction from screen centre.
  - Colours: `PASS_YELLOW` fill and a `PASS_OUTLINE` edge, sized like the carrier marker.
  - Draw it after the near boards.
- [ ] **Step 4: Run** the render tests. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(render): edge arrow for an off-screen pass target`

---

### Task 6: The mesh pipeline

**Model:** opus

**Files:**
- Create: `render/mesh/Mesh.kt`, `render/mesh/MeshPainter.kt`
- Test: `render/mesh/MeshPainterTest.kt` (plain JVM, with a recording `TriangleSink` fake)

**Interfaces:**
- Consumes: `TriangleSink` (Task 1), `Camera.project` (Task 4)
- Produces:
  ```kotlin
  /** Vertex v sits at along = t[v] * boneLength + e[v] metres from the bone's first joint, a[v] m to the
   *  side, b[v] m up (bone frame). Normals likewise (nt, na, nb). Triangles wind counter-clockwise seen
   *  from outside. slot[v] indexes a palette. */
  class Mesh(val t: FloatArray, val e: FloatArray, val a: FloatArray, val b: FloatArray,
             val nt: FloatArray, val na: FloatArray, val nb: FloatArray,
             val slot: ByteArray, val tri: ShortArray)
  class Part(val from: Int, val to: Int, val mesh: Mesh)   // joint slot indices
  object MeshBuilder {
      fun taperedBox(len0: Float, len1: Float, w0: Float, d0: Float, w1: Float, d1: Float, slot: Int): Mesh
      fun capsule(r0: Float, r1: Float, sides: Int, rings: Int, slot: Int, bands: FloatArray = EMPTY, bandSlot: Int = slot): Mesh
      fun ellipsoid(rx: Float, ry: Float, rz: Float, sides: Int, rings: Int, slot: Int): Mesh
      fun join(vararg parts: Mesh): Mesh
  }
  class MeshPainter(maxTriangles: Int = 1024) {
      fun begin(camera: Camera)
      /** jx/jy/jz: joints in world metres; (leftX, leftY): the figure's left on the ice. */
      fun add(part: Part, jx: FloatArray, jy: FloatArray, jz: FloatArray, leftX: Float, leftY: Float, palette: IntArray)
      /** Culls back faces, sorts far to near, sends to [sink]; returns triangles sent. */
      fun emit(sink: TriangleSink): Int
      val added: Int   // triangles added before culling, for the budget test
  }
  ```

- [ ] **Step 1: Write the failing tests.**
  - `a_box_shows_at_most_three_faces`: a unit `taperedBox` emits ≤ 6 triangles out of 12 added.
  - `far_triangles_are_emitted_first`: two boxes 2 m apart in y. Every triangle of the far one is
    recorded before any of the near one.
  - `light_from_above_makes_the_top_brighter_than_the_side`: compare a top-face vertex colour with a
    side-face one for a mid-grey slot.
  - `bone_frame_is_finite_when_the_bone_points_along_left`: no NaN when (to − from) is parallel to left.
  - `emit_does_not_allocate`: build a 500-triangle figure. 2,000 `begin/add/emit` rounds allocate under
    16 KB. Measure with the same `ThreadMXBean` reflection as `sim/NoAllocTest`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - **Bone frame:**
    - `along = (to − from)/L`.
    - `side = left − (left·along) along`, normalised. If that's degenerate, use `up × along`.
    - `up = along × side`.
  - **Lighting:** Lambert with `L = normalise(0.25, −0.55, 0.8)`, plus ambient 0.45, diffuse 0.6, and
    rim `0.2 * (1 − |n·v|)²`. `v` points to the camera eye. Clamp the result per channel.
  - **Culling:** use the sign of the projected signed area. Screen y grows downward, so check the sign
    once against a known front face in the test.
  - **Sort:** a 512-bucket counting sort on the mean triangle depth (`1/scale`), between this frame's
    min and max, in preallocated `IntArray`s. Don't use `Arrays.sort`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit.** `feat(render): mesh pipeline: bone frames, lighting, culling, depth sort`

---

### Task 7: 3D skaters

**Model:** opus

**Files:**
- Create: `render/figure/Skeleton.kt`, `render/figure/SkaterBody.kt`
- Modify: `render/FigurePainter.kt`, `render/CanvasRenderer.kt`
- Test: `render/SkaterMeshTest.kt` (new, Robolectric), plus the existing `CanvasRendererTest`, `NumberViewTest`, `PuckViewTest`
  and `DrawOrderTest`, which must still pass

**Interfaces:**
- Consumes: `MeshPainter`, `Part`, `MeshBuilder` (Task 6), `TriangleSinks.default()` (Task 1)
- Produces:
  ```kotlin
  object Skeleton { const val HIP_L = 0 /* …all of today's slot constants, unchanged… */; const val SLOTS = 28 }
  class Pose { val a = FloatArray(Skeleton.SLOTS); val b = FloatArray(Skeleton.SLOTS); val z = FloatArray(Skeleton.SLOTS) }
  object Palette { const val JERSEY = 0; const val TRIM = 1; const val PANTS = 2; const val HELMET = 3; const val SKIN = 4
                   const val BOOT = 5; const val STEEL = 6; const val STICK = 7; const val TAPE = 8; const val PAD = 9
                   const val CAGE = 10; const val SIZE = 11 }
  object SkaterBody { val parts: Array<Part> }
  FigurePainter.lastTriangles: Int   // triangles added for the last figure drawn
  ```
- Parts, shapes and colour slots are as in the spec's table. Use pseudo-joints for single-joint parts:
  - the head uses `HEAD→HEAD_FWD`,
  - the gloves use the elbow→hand bone with `t ≥ 1`.

- [ ] **Step 1: Write the failing tests.**
  - `a_skater_has_at_most_500_triangles`.
  - `a_full_rink_scene_stays_within_8000_triangles`: all 10 skaters plus 2 goalies. Sum
    `lastTriangles` over one `draw`.
  - **Review Focus 2:** `every_facing_draws_a_whole_skater`, for 16 facings × {NONE, WINDUP, DOWN}.
    - Emitted triangles are > 0 and every projected coordinate is finite.
    - A jersey-coloured pixel lies within the skater's screen box.
    - `NumberViewTest` still passes for the same facings.
  - **Review Focus 1:** `a_dark_kit_at_the_far_boards_stands_out`. Render a MIN skater at
    (0, `Rink.HALF_W − 1`), with and without the skater. The mean luminance over the skater's screen
    box differs by ≥ 0.08, or ≥ 15% of its pixels differ by ≥ 0.2 in luminance.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Move the slot constants and the pose arrays into `Skeleton`/`Pose`.
  - Replace `leg`/`arm`/`torso`/`stick`/`head`/`glove`/`skate` drawing with: project the posed joints to
    world, `MeshPainter.add` each part with the kit palette, then `emit` to the sink.
  - Keep `number()` drawn after the mesh.
  - Keep the ground shadow, and darken `SHADOW_ALPHA` to about 0.3.
  - If the dark-kit test can't pass on shading alone, add the inverted-hull edge: back faces of the
    torso, helmet and pants, inflated 0.025 m, drawn in `OUTLINE` before the figure.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Look at every PNG and iterate on shapes
  and proportions until the players read as bulky hockey players, not robots.
- [ ] **Step 5: Commit.** `feat(render): low-poly 3D skaters`

---

### Task 8: 3D goalies

**Model:** sonnet

**Files:**
- Create: `render/figure/GoalieBody.kt`
- Modify: `render/FigurePainter.kt` (`drawGoalie`)
- Test: `render/SkaterMeshTest.kt` (add goalie cases), `NumberViewTest`, `GoalViewTest`

**Interfaces:**
- Consumes: Task 7's `Skeleton`, `Palette`, `MeshPainter`
- Produces: `object GoalieBody { val parts: Array<Part> }`. The leg pads, blocker board, catcher with a
  pocket, a wider chest, and a mask in the helmet colour with a `CAGE` grille.

- [ ] **Step 1: Write the failing tests.**
  - `a_goalie_has_at_most_800_triangles`.
  - `a_goalie_draws_in_both_poses_facing_both_ways`: ready and save, HOME and AWAY. Triangles > 0 and
    everything finite.
  - `GoalViewTest` stays green.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** `drawGoalie` through `MeshPainter`. Keep `poseGoalie` and the number overlay.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Add `goalie_close_up` to `PreviewImagesTest`
  (the camera snapped to a goal mouth with zoom goal 1.3) and look at it.
- [ ] **Step 5: Commit.** `feat(render): low-poly 3D goalies`

---

### Task 9: The animation framework, fed every tick

**Model:** opus

**Files:**
- Create: `render/anim/Clips.kt`, `render/anim/AnimDirector.kt`
- Modify: `render/CanvasRenderer.kt` (`onTick`, passing `AnimState` to the painter), `render/FigurePainter.kt`
  (`drawSkater` and `drawGoalie` take an `AnimState`, and blend clips after the procedural pose),
  `game/GameViewModel.kt:123` (call `renderer.onTick(sim)` right after `director.onEvents`)
- Test: `render/anim/AnimDirectorTest.kt` (plain JVM), `game/GameViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2's event ids, `Skeleton`/`Pose` (Task 7)
- Produces:
  ```kotlin
  enum class Clip { NONE, WRIST_FOLLOW, SLAP_FOLLOW, CELEBRATE_FIST, CELEBRATE_SLIDE, CELEBRATE_STICK, HUG, DEJECTED, GOALIE_SLUMP }
  enum class Role { NONE, SCORER, HUGGER, DEJECTED, BEATEN }
  class ClipData(val times: FloatArray, val keys: Array<Pose>, val keyed: BooleanArray, val loop: Boolean)
  object ClipLibrary { operator fun get(c: Clip): ClipData? }   // entries added by Tasks 11 and 13
  class AnimState {
      var clip: Clip; var clipT: Float; var weight: Float      // weight eases 0→1 / 1→0 over BLEND_S = 0.1 s
      var sincePickup: Float; var pickupFromPass: Boolean; var slap: Boolean; var oneTimer: Boolean
      var role: Role; var turnRate: Float                     // rad/s of facing change, from the last tick
      var animT: Float                                        // free-running seconds, for stickhandling
  }
  class AnimDirector {
      val skaters: Array<AnimState>   // index = skater id
      val goalies: Array<AnimState>   // index = side.index
      fun onTick(sim: HockeySim)      // reads sim.events and state; DT = Tuning.DT
      fun advance(dt: Float)          // advances clipT, weight and animT
      fun play(state: AnimState, clip: Clip)
      fun blend(state: AnimState, base: Pose, out: Pose)   // out = base + (clip − base) × weight on keyed slots
  }
  CanvasRenderer.onTick(sim: HockeySim)   // delegates to its AnimDirector
  const val SLAP_WINDUP = 0.475f          // windup seconds at power 0.5
  const val ONE_TIMER_S = 0.6f
  ```

- [ ] **Step 1: Write the failing tests.** In `AnimDirectorTest`, inject events into `sim.events`, then
  call `onTick`.
  - `a_weak_shot_plays_the_wrist_follow_through`: `Shot(power = 0.3)` gives `WRIST_FOLLOW` and
    `slap == false`.
  - `a_hard_shot_plays_the_slap_follow_through`: power 0.7 gives `SLAP_FOLLOW`.
  - `a_quick_shot_off_a_pass_is_a_one_timer`: `Pass(target = 3)`, then `Pickup(3)`, then 0.4 s of ticks,
    then a `Shot` from 3 at power 0.1. The result is `SLAP_FOLLOW` with `oneTimer == true`.
  - `a_quick_shot_not_off_a_pass_is_a_wrist_shot`.
  - `blend_weight_eases_in_over_a_tenth_of_a_second`: 0.05 s gives a weight in 0.4..0.6. 0.1 s gives 1.
  - `blend_moves_only_keyed_slots`.
  - `turn_rate_follows_facing`: facing +0.05 rad per tick gives `turnRate ≈ 3 rad/s`.
  - `GameViewModelTest`: `renderer.onTick` runs once per sim tick. Use a counting hook or check
    `animT` against the number of ticks.
  - These clips only play once Tasks 11 and 13 add library entries. Until then, register test clips in
    the test through an `internal fun ClipLibrary.register(c: Clip, d: ClipData)`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** `onTick` must not allocate. Index arrays by id, and use no maps or
  iterators.
- [ ] **Step 4: Run** the whole suite. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(render): animation clips and blending, fed every tick`

---

### Task 10: Stickhandling, with the puck on the blade

**Model:** sonnet

**Files:**
- Modify: `render/FigurePainter.kt` (`poseSkating` carrier branch; add `bladeOnIce`), `render/CanvasRenderer.kt` (draw the carried
  puck at the blade)
- Test: `render/StickhandlingTest.kt` (new, Robolectric)

**Interfaces:**
- Consumes: `AnimState.animT` (Task 9)
- Produces: `fun FigurePainter.bladeOnIce(out: FloatArray): Boolean`. Valid after the last `drawSkater` /
  `poseSkater`. It fills world (x, y) of the blade's middle and returns false when the blade is more
  than 0.1 m above the ice. Also `fun poseSkater(camera, s, anim, hasPuck)`, which poses without
  drawing, for tests.
- Values:
  - The sweep is ±0.3 m at 2 Hz above 3 m/s, and ±0.4 m at 3 Hz below that.
  - The phase is offset per skater by `id * 1.3` rad.
  - The shoulders counter-rotate with `shB = −0.4 × blade offset`, and the head stays level.

- [ ] **Step 1: Write the failing tests.**
  - `the_blade_sweeps_across_the_body`: the lateral blade offset at `animT` 0 and at 0.25 s differs by
    > 0.3 m when skating.
  - `the_carried_puck_is_drawn_at_the_blade`: the puck's dark pixels are centred within 6 px of
    `bladeOnIce`'s projected point.
  - **Review Focus 5:** `while_winding_up_the_puck_stays_on_the_ice_at_the_carry_point`: with
    `act = WINDUP, windup = 0.7`, `bladeOnIce` returns false and the puck is drawn at the sim's
    `puck.x, puck.y`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.** Replace today's `wiggle`. Add a little blade roll at the ends of the sweep.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Add `stickhandling` to `PreviewImagesTest`
  and look at it.
- [ ] **Step 5: Commit.** `feat(anim): forehand-backhand stickhandling, puck on the blade`

---

### Task 11: Shots, snow spray and the puck streak

**Model:** opus

**Files:**
- Create: `render/Effects.kt`
- Modify: `render/FigurePainter.kt` (the WINDUP pose), `render/anim/Clips.kt` (`WRIST_FOLLOW`, `SLAP_FOLLOW`),
  `render/anim/AnimDirector.kt` (spray on Shot), `render/CanvasRenderer.kt` (step and draw the effects)
- Test: `render/ShotAnimTest.kt` (new), `render/EffectsTest.kt` (new, plain JVM)

**Interfaces:**
- Consumes: Task 9's triggers and `SLAP_WINDUP`, and Task 10's `bladeOnIce`
- Produces:
  ```kotlin
  class Effects(capacity: Int = 256) {
      val alive: Int
      fun spray(x: Float, y: Float, dirX: Float, dirY: Float, count: Int, speed: Float)
      fun notePuck(x: Float, y: Float, speed: Float)   // keeps the last 8 positions while speed > STREAK_SPEED (20 m/s)
      val streakLength: Int
      fun step(dt: Float)
      fun draw(scope: DrawScope, camera: Camera)
  }
  ```
- Values:
  - The wind-up morphs from the wrist draw-back to the slap lift through
    `smoothstep(SLAP_WINDUP − 0.05, SLAP_WINDUP + 0.1, windup)`.
  - `WRIST_FOLLOW` lasts 0.35 s and ends with the blade at about chest height ahead.
  - `SLAP_FOLLOW` lasts 0.45 s and ends higher.
  - A shot sprays 14 particles at 4 m/s from the blade, along the shot direction.

- [ ] **Step 1: Write the failing tests.**
  - `a_short_windup_keeps_the_blade_low_and_back`: at windup 0.3 s, the blade z is < 0.35 and blade
    a < 0.
  - `a_long_windup_lifts_the_stick_above_the_shoulder`: at windup 0.75 s, the blade z is > the
    shoulder z.
  - `the_follow_through_ends_high_and_ahead`: 0.2 s after `Shot(0.3)`, the blade a is > 0.5 and the
    blade z is > 0.8.
  - `a_shot_sprays_snow` and `particles_die`: `alive` is > 0 after the shot and 0 after 2 s.
  - `a_fast_puck_leaves_a_streak_and_a_slow_one_does_not`.
  - `effects_do_not_allocate`: 1,000 step/spray/notePuck rounds allocate under 16 KB.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement.**
  - The particles are white and pale blue, with alpha by life and gravity on z.
  - The streak is a tapered translucent white line.
  - `CanvasRenderer` steps `Effects` in `advance`, calls `notePuck` in `onTick`, and draws the effects
    after the figures and before the near boards.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Add `slapshot_windup` and `wrist_release`,
  and look at them.
- [ ] **Step 5: Commit.** `feat(anim): wrist shot, slapshot, one-timer, snow spray and puck streak`

---

### Task 12: Crossovers and the hockey stop

**Model:** sonnet

**Files:**
- Modify: `render/FigurePainter.kt` (`poseSkating` base layer), `render/anim/AnimDirector.kt` (spray while stopping)
- Test: `render/SkatingAnimTest.kt` (new)

**Interfaces:**
- Consumes: `Skater.stopping` (Task 2), `AnimState.turnRate` (Task 9), `Effects.spray` (Task 11)
- Values:
  - **Crossover:** above 4 m/s with `|turnRate|` > 1.5 rad/s. The outside skate's b crosses past the
    inside skate's b on its recovery stroke, and the body leans into the turn with `shB = 0.25` toward
    the inside.
  - **Stop:** both skates turn sideways (heel→toe mostly along b), the hips drop 0.15 m, the lean goes
    to −0.1, and the skates spray 3 particles per tick along the direction of travel, at 5 m/s.

- [ ] **Step 1: Write the failing tests.**
  - `a_hockey_stop_turns_the_skates_sideways`: with `stopping`, `|Δb| > |Δa|` for each heel→toe.
  - `a_hockey_stop_throws_snow_forward`: the mean particle velocity · travel direction is > 0.
  - `a_fast_turn_crosses_the_outside_foot_over`: across one stride cycle, the outside foot's b passes
    the inside foot's b at least once.
  - `straight_skating_never_crosses_over`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** in the base layer. These aren't clips; they follow the state every frame.
- [ ] **Step 4: Run** the render tests and `PreviewImagesTest`. Add `hockey_stop` and look at it.
- [ ] **Step 5: Commit.** `feat(anim): crossovers and hockey stops with snow`

---

### Task 13: Goal celebrations and the camera push-in

**Model:** opus

**Files:**
- Modify: `render/anim/Clips.kt` (`CELEBRATE_FIST`, `CELEBRATE_SLIDE`, `CELEBRATE_STICK`, `HUG`, `DEJECTED`, `GOALIE_SLUMP`),
  `render/anim/AnimDirector.kt` (roles), `render/CanvasRenderer.kt` (`advance`: push-in), `render/FigurePainter.kt` (goalie clips)
- Test: `render/anim/CelebrationAnimTest.kt` (new), `render/CameraTest.kt`, `ui/PreviewImagesTest.kt`

**Interfaces:**
- Consumes: `SimEvent.Goal.scorerId`, `HockeySim.lastScorerId` (Task 2), the converging teammates (Task 3),
  `Camera.zoomGoal` (Task 4), and `ClipLibrary`/`Role` (Task 9)
- Values:
  - **The scorer's clip** is `[FIST, SLIDE, STICK][(sim.score[side] − 1) % 3]`, starting after a
    0.6 s glide. The knee slide sprays snow.
  - **Teammates** switch from `HUGGER` to the `HUG` clip once within 2 m of the scorer.
  - **The conceding side's skaters** are `DEJECTED`: heads down and stick blades flat.
  - **The beaten goalie** plays `GOALIE_SLUMP`.
  - **Camera:** in `Phase.GOAL` it gets `zoomGoal = 1.3` and follows the scorer's position. In any
    other phase it gets `zoomGoal = 1` and follows the puck.

- [ ] **Step 1: Write the failing tests.**
  - `a_goal_gives_everyone_a_role`: the scorer is `SCORER`, their teammates `HUGGER`, opponents
    `DEJECTED`, and the scored-on goalie `BEATEN`.
  - `celebrations_cycle_with_the_teams_goal_count`: goals 1, 2, 3 and 4 give FIST, SLIDE, STICK and
    FIST.
  - `huggers_hug_once_close`.
  - **Review Focus 4:** `roles_clear_and_the_camera_zooms_out_when_the_goal_pause_ends_in_a_break`:
    score with `clockS = 0` and run through the pause into `Phase.BREAK`. Every role is `NONE`, every
    clip's weight is 0 after 0.2 s, and `camera.zoomGoal == 1f`.
  - `roles_clear_at_the_faceoff`.
  - `the_camera_pushes_in_on_the_scorer`: 1.5 s into GOAL, `camera.zoom` is > 1.2, and the scorer is
    within 15% of screen centre in x.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the roles in `onTick` (on `Goal` and on any phase other than GOAL), the clip
  keyframes, and the camera wiring.
- [ ] **Step 4: Run** the whole suite and `PreviewImagesTest`. Add `celebration` (2.5 s into the goal
  pause, with the pile visible) and look at it.
- [ ] **Step 5: Commit.** `feat(anim): goal celebrations, team pile, beaten goalie, camera push-in`

---

### Task 14: Final pass

**Model:** sonnet

**Files:**
- Modify: `README.md` (the "What's in it" game bullet: a closer camera, 3D players built in code,
  celebrations)
- Test: the whole suite

- [ ] **Step 1: Run** `./gradlew testDebugUnitTest`. Expected: everything passes, including
  `NoAllocTest`, `MeshPainterTest.emit_does_not_allocate`, `EffectsTest.effects_do_not_allocate` and
  the 8,000-triangle budget.
- [ ] **Step 2: Run** `PreviewImagesTest` and look at every PNG together. Check for:
  - consistent lighting,
  - no z-fighting or sort glitches at the joints,
  - readable numbers, puck and rings.
  
  Fix small issues here. Report anything bigger.
- [ ] **Step 3: Build** with `./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL. Tell Chris the
  `adb install` command from the README so he can check 60 fps on his Pixel 9.
- [ ] **Step 4: Update** the README bullet.
- [ ] **Step 5: Commit.** `docs: README for the new look`
