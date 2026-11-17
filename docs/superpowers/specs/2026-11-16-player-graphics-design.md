# Player graphics: closer camera, low-poly 3D players, new animations

## Why

Chris finds the game's graphics "terrible" and wants the players to look closer to *Hockey All Stars 24*
(Distinctive Games, 2023), a 3D mobile hockey game with modelled players. Today a skater is about 80 px tall
on a 1600 px wide screen and is drawn as round-capped strokes with dark outlines in flat kit colours
(`render/FigurePainter.kt`). That reads as clip-art. The animation is thin too: stickhandling is a blade
wobble, a shot is one wind-up pose, and a goal shows only a banner and the goal light.

Chris picked, from a brainstormed list:

1. Bring the camera closer, so players are about 1.7× bigger.
2. Give the players volume and gear: shoulder pads, flared pants, gloves, boots, jersey trim, light and shade.
3. Draw them as low-poly 3D meshes built in code and posed on the existing skeleton.

He also wants new animations for stickhandling, shots, skating and goal celebrations. He chose approach A
below for how the 3D is drawn.

## Goals

- Players read as solid, shaded, 3D hockey players in team kit, and look clearly closer to Hockey All Stars 24
  than today's figures.
- The camera frames play tighter, so a skater is about 1.7× today's on-screen size.
- Stickhandling, the wrist shot, the slapshot, the one-timer, crossovers, the hockey stop and goal
  celebrations each have a recognisable animation.
- 60 fps on a Pixel 9 with all 12 figures on screen. No per-frame allocation in drawing, as today.

## Non-goals

- A 3D engine, OpenGL, image assets, imported models or motion-capture data (that was idea 4).
- Rink, boards, crowd, HUD or menu changes, except where the closer camera forces one.
- Ice reflections, skate marks on the ice, camera shake on hits.
- New gameplay. The animations show what the sim already does. The only sim changes are the
  celebration hooks listed under "Sim changes".
- New goalie save animations such as the butterfly or a glove snag. Goalies get the 3D treatment, keep
  today's ready and save poses, and gain one new pose (the beaten goalie's slump).

## Anti-goals

- **Unreadable play.** Bigger, prettier players that make it hard to see who has the puck, where the puck
  is, or who you'd pass to are a failure even if they look good. The puck, the control ring, the pass
  ring and the carrier marker must stay as readable as today.
- **Dropped frames.** Visible stutter on the Pixel 9 is a failure.
- **Breaking the screenshot tests.** All drawing must keep working in Robolectric's native graphics
  mode, so `PreviewImagesTest` can still render frames for review.

## Constraints

- Kotlin and Jetpack Compose, drawing on the Compose `Canvas`. No game engine and no image assets (README).
- `sim` stays pure Kotlin with no Android imports and keeps its JVM tests. `NoAllocTest` must keep passing.
  The only sim changes are the ones listed under "Sim changes", plus skater ids added to the events
  (see "Settled after the builder review").
- Drawing must not allocate per frame. Preallocate every buffer.
- Teams stay data. Kit colours come from `teams.Kit`, and nothing in `render` names a team.
- Work goes straight onto `main`.

## Approaches

**A. Meshes built in code, drawn on the existing Canvas (chosen by Chris).** Each body part is a small
mesh fixed to a bone of the existing skeleton. Each frame the parts are transformed and lit by one fixed
light. Then each figure's triangles are sorted back to front and drawn with
`android.graphics.Canvas.drawVertices` (per-vertex colours, no texture). Hockey gear is chunky, so the
seams at knees and elbows hide under pads. It stays a single renderer behind `GameRenderer`, and the
screenshot tests keep working. The risks are sort glitches where parts cross, and whether Robolectric
renders `drawVertices` at all; the first task checks the second.

**B. An OpenGL ES layer for the figures.** This gives real depth buffering and smoother lighting. Rejected: it
splits rendering into two systems, Robolectric can't render it (no screenshot review), and every visual
change would need checking on the phone.

**Dropped:** 2D shaded silhouettes only, because Chris asked for 3D. A full engine with bought models
(idea 4) was not chosen.

## The design

### 1. Camera

`render/Camera.kt` today has its eye at rink (focusX, −34, 17) looking at (focusX, −1, 0), with
`FOCAL_PER_HEIGHT = 2.35`. It shows the full rink width and about 34–36 m of length, and pans only
along x.

- **Zoom about 1.7×** (a tuning constant). A skater at mid-ice is then about 1.7× today's pixel height.
- **A little lower and more side-on.** Lower the eye so players show more of their bodies and less of
  their helmets. The pitch drops from about 27° to roughly 20°, tuned on the preview images.
- **Follows the puck in y as well as x.** The zoomed view no longer fits the full rink width, so the
  camera eases toward the puck's y as well. It's clamped so the view never goes past the far glass or
  the near boards' outer edge. It keeps the existing look-ahead (`LEAD_S`) and smoothing, and the
  end-of-rink overshoot that keeps corner play clear of the thumbs. `ThumbClearanceTest` and
  `CameraTest` are updated to the new framing.
- **An off-screen pass target gets an edge arrow (call).** When the controlled carrier's pass target
  (`sim.passTargetFor`) is off screen, draw a small arrow at the screen edge pointing to them, in the
  pass ring's yellow with its dark edge. This keeps "who would I pass to" readable now that less of the
  ice is visible.
- **Goal push-in.** During a goal the camera eases to about 1.3× further zoom, centred on the scorer.
  It eases back when the faceoff starts (see Celebrations).

### 2. 3D players

The skeleton, the joint slots in `FigurePainter` (hips, knees, ankles, heel, toe, shoulders, elbows,
hands, hip and shoulder centres, head and stick points), stays the source of pose. Meshes hang off it.

**Mesh parts (built once at start-up, in bone-local space):**

| Part | Shape | Colour |
|---|---|---|
| Torso / jersey | tapered box, wider at the shoulders, with a raised shoulder-pad yoke | jersey; trim bands at the hem and yoke |
| Pants | short flared barrel | pants |
| Thighs | tapered capsules | pants |
| Shins / socks | tapered capsules | jersey, with a trim band |
| Skates | boot block plus a thin blade | boot black, blade steel |
| Upper arms / forearms | tapered capsules, elbow pad bulge | jersey; trim band on the forearm |
| Gloves | rounded block with cuff | pants colour, as today |
| Helmet | rounded shell with a visor strip | helmet; face skin beneath |
| Stick | thin box shaft, flat blade | stick tan, black tape on the blade |
| Goalie extras | thick leg-pad blocks, blocker board, catcher with a pocket, masked head, wider chest | pad white with trim stripes; mask in helmet colour with a cage |

Count about 300–500 triangles per skater and up to 800 per goalie. The builder tunes the shapes and
counts against the preview images and the frame budget.

**Bone frames.** Each part's transform comes from its bone's two joints. The roll axis is the
figure's facing (its left vector), as for today's stripes. Parts may overlap a little at joints; pads
cover the seams.

**Shading (call).** Lambert diffuse from one fixed key light above and in front of the camera, plus
ambient, plus a faint rim term on silhouette-facing normals so dark kits still separate from the dark
crowd and boards. Colours are per vertex: kit colour × light. There are no dark outlines on the meshes. The
soft ground shadow stays, slightly darker, to plant players on the ice. If a dark kit vanishes against
the far boards in the previews, add a thin dark edge by drawing back faces slightly inflated in black
(inverted-hull outline). That's the builder's call, tuned visually.

**Drawing.** For each figure, transform all of its parts' vertices into world space, light them,
project them through `Camera.project`, compute each triangle's camera depth, sort the triangles back
to front, and emit one `drawVertices` call per figure into preallocated float and short arrays.
Figures, nets and the puck keep today's far-to-near order (`CanvasRenderer.drawOrder`). Back-face
culling is on for closed parts.

**Jersey number (call).** Keep today's large upright number with a contrasting edge, drawn over the
torso after the mesh. It's the game's main way to tell players apart, and putting it on the mesh
would make it small and often hidden.

**What doesn't change.** Kits, numbers and team data, the ground rings, the carrier marker and the
nets. The puck keeps its exaggerated size.

### 3. Animation system

- Poses stay in `FigurePainter`'s current form: joint positions in the figure frame (forward, left, up).
- A **clip** is a short array of keyframed poses with times. A clip may key only some joints. The
  rest come from the base layer.
- The **base layer** is today's procedural skating: stride, lean, deke lean and the knocked-down
  pose, extended with crossovers and stops (below).
- **Blending.** Each skater has a small render-side animation state. It holds the active clip and its
  time, a blend weight that eases in and out over about 0.1 s, and whatever the triggers need: time
  since pickup, the last pass reception time, whether the last shot was a slapshot, and the
  celebration role. The pose is base + (clip − base) × weight per keyed joint.
- **Where the state lives.** In `render`, one entry per skater and goalie, preallocated. It's updated
  every sim tick from the sim's state and that tick's events. Today `GameViewModel` hands each tick's
  events to the feedback director; the renderer gets them the same way, because `sim.events` is cleared
  every tick. This must not allocate and must be deterministic for the tests. See "Settled after the
  builder review", item 2.

### 4. Animations

**Stickhandling (carrier, not winding up).**
- The blade sweeps forehand to backhand, about ±0.3 m across the body.
- It moves at about 2 Hz while skating and faster and wider when standing still or slow.
- The blade rolls a little at each end of the sweep.
- The shoulders counter-rotate and the head stays up.
- **The puck is drawn at the blade** instead of at the sim's carry point, and its sim position is
  unchanged. Today a carried puck is always drawn last so nothing hides it, and that rule stays.

**Shots.** The sim's wind-up runs from 0.15 to 0.8 s, and power goes 0 to 1 over that range.
- **Wrist shot:** a short wind-up (power < 0.5, tunable).
  - The blade draws back beside the back hip and stays low.
  - On release, weight shifts forward, the blade sweeps through and the stick follows through to
    about chest height.
  - The follow-through clip lasts about 0.35 s.
- **Slapshot:** a longer wind-up.
  - Once power passes the threshold, the stick lifts above the shoulder and the knees bend.
  - On release there's a big downswing, and the stick follows through high.
- **One-timer (visual only, call).** A shot released within 0.6 s of receiving a pass plays a
  slapshot-style swing and follow-through, whatever its wind-up. No gameplay changes.
- **Effects:**
  - On release, a spray of ice snow comes off the blade.
  - The puck leaves a fading streak behind it while it moves faster than about 20 m/s.
  - Particles come from one preallocated pool.

**Skating.**
- **Crossovers.** When a skater's heading turns faster than a threshold at speed, the stride switches
  to a crossover. The outside skate steps over the inside one and the body leans into the turn.
- **Hockey stop.**
  - The sim already brakes when the stick is pulled hard against the heading
    (`Skating.control`, `HOCKEY_STOP_ANGLE`).
  - The sim exposes that as a read-only skater flag (see Sim changes).
  - While it's set, both skates turn sideways to the travel, the knees bend, the body leans back, and
    snow sprays from the skates in the direction of travel.

**Goal celebration.** It runs through the whole goal pause, which grows from 2.5 s to 4 s.
- **Scorer.** They glide on, then play one of three clips:
  - a fist pump,
  - a knee slide (a long sliding drop to one knee, pumping the arm),
  - a stick raised overhead.
  
  The clip cycles by the scoring team's goal count, so consecutive goals vary deterministically.
- **The scorer's teammates.** They skate to the scorer (sim change). On arrival, each plays a
  group-hug pose: arms out toward the scorer and heads leaning in.
- **The beaten goalie.** Slumps: head down, shoulders dropped, stick flat on the ice.
- **The other team's skaters.** They coast as now, with heads lowered and sticks resting on the ice.
- **Camera.** It pushes in on the scorer, as in section 1.
- **Own goals.** The "scorer" is the scoring side's skater nearest the net that was scored on.

### Sim changes (all in `sim`, pure Kotlin, tested)

1. **Who scored.** Track the last skater of each side to carry or shoot the puck.
   - On a goal, record the scorer's skater id: that tracked skater for the scoring side. For an own
     goal, it's the scoring side's skater nearest the net scored on.
   - Expose it as `lastScorerId` on `HockeySim` and add it to `SimEvent.Goal`.
2. **`Tuning.GOAL_TIME` goes from 2.5 to 4.0 s.** The clock doesn't run in a goal pause, so this
   lengthens real play time slightly and doesn't change scoring.
3. **Teammates skate to the scorer.** During `Phase.GOAL`, the scorer's teammates steer toward the
   scorer at a moderate speed (about 60% of `MAX_SPEED`, tunable) and stop about 1.2 m away. Everyone
   else glides as now. Separation still applies, so they cluster without overlapping.
4. **Hockey-stop flag.** `Skater.stopping` is a read-only boolean set when `control()` takes the
   hockey-stop branch and cleared otherwise.

## Testing

- **First task, a spike:** confirm `Canvas.drawVertices` with per-vertex colours renders in Robolectric
  `GraphicsMode.NATIVE` and appears in a `PreviewImagesTest` PNG. If it doesn't, use the flat-shaded
  path fallback ("Settled after the builder review", item 1).
- **Sim:** a goal records the right scorer, including an own goal. Teammates converge on the scorer
  during the goal pause. The new goal time is used. The stop flag is set only while braking.
  `NoAllocTest` still passes.
- **Render:**
  - The camera keeps the puck framed in x and y within the clamps, and corner play stays clear of the
    thumbs.
  - The edge arrow appears exactly when the pass target is off screen.
  - The animation state picks the right clip for each trigger (wrist, slap, one-timer, stop, crossover,
    each celebration role).
  - Triangles are sorted back to front.
  - Drawing allocates nothing per frame.
- **Preview images.** Add frames for:
  - stickhandling,
  - mid-slapshot,
  - a hockey stop,
  - the goal celebration with the pile,
  - a goalie close up.
  
  Review them after each visual task.

## Settled after the builder review (calls)

1. **If the `drawVertices` spike fails in Robolectric.** Don't stop. Keep the same mesh pipeline, but
   draw each sorted triangle as a filled path, flat-shaded with the average of its vertex colours,
   behind one small `TriangleSink` interface. Use `drawVertices` on device if it's faster, but the same
   triangles must reach both sinks. Preview images then show flat shading, which is close enough to
   review shapes and poses.
2. **How the renderer learns who acted.** Add skater ids to the sim events:
   - `SimEvent.Shot(side, power, shooterId)`,
   - `SimEvent.Pass(side, passerId, targetId)`, with `targetId = -1` when there's no target,
   - `SimEvent.Pickup(side, skaterId)`,
   - `SimEvent.Goal(scorer, scorerId)`.
   
   The feedback director ignores the new fields. `GameViewModel` calls a new `renderer.onTick(sim)`
   after every `match.tick`, next to `director.onEvents`. That gives the renderer each tick's events
   and state. `advance()` stays once per frame for the camera and animation clocks.
3. **The wind-up while holding.**
   - It shows continuously: the wrist draw-back from the start, morphing into the slapshot lift as
     `windup` passes the threshold that maps to power 0.5.
   - On release, the same threshold picks the wrist or slap follow-through.
   - A one-timer (a shot within 0.6 s of a pickup that ended a pass to that skater) always uses the
     slap follow-through.
4. **How the camera follows in y.** Move the eye and the look-at point together along rink y, a pure
   translation, so the perspective doesn't change.
   - `Camera` gains `focusY`, eased and clamped like `focusX`, and `project` subtracts it from y.
   - The joystick already maps screen-up to rink +y (`Controls.kt`), and a side-on camera keeps that
     true, so the controls are untouched.
   - Corner play clearing the thumb is still required. `ThumbClearanceTest` keeps its checks, with
     thresholds recomputed for the new framing.
5. **Own goals.**
   - The beaten goalie slumps whoever put it in.
   - The scorer is the scoring side's skater nearest the net that was scored on, and celebrates as
     normal.
6. **The 4 s goal pause.**
   - `GOAL_TIME` is used only in `Flow.kt` and in tests that already refer to `Tuning.GOAL_TIME` by
     name.
   - The banner and goal light run for the whole goal phase, so they lengthen with it.
   - Check only `FlowTest`'s banner timing ("GOAL_TIME − 0.5 s").
7. **How performance is proved.**
   - A frame budget of at most 8,000 triangles across all figures, checked in a unit test that counts
     them for a full-rink scene.
   - A no-allocation test of drawing, run on the JVM.
   - A final on-device check by Chris on the Pixel 9.

## Calls the builder makes

- Exact mesh shapes, triangle counts, colours, light direction and shading strengths.
- Exact camera numbers: zoom, eye height, y clamps, push-in amount.
- Clip keyframes and timings, and the thresholds for wrist vs slap, crossovers and the puck streak.
- How mesh code is split across files in `render`.
- Whether the inverted-hull edge is needed.
- Updating existing render tests (`NumberViewTest`, `DrawOrderTest`, `PuckViewTest` and so on) to the
  new drawing, keeping what each one protects.
