# Goalie realism: design

**Date:** 2026-11-17
**Builds on:** the gameplay-feel pass, which added the butterfly, catches and zone faceoffs. All of it is on main.
**Size:** a project. It touches the sim (goalie positioning), the drawing (facing, stance and movement) and the balance.

## Intent

Chris: "fix the visual goalie positioning so that it is more realistic. right now, the goalie always faces forward. the goalie should face wherever the puck is and follow the puck movement. also the goalie should be standing until shot at. work on more realistic goalie behavior".

Chris picked option B: real positioning as well as looks. He accepted that difficulty gets re-tuned the way it was for zone faceoffs.

## How it works today

**Sim (`sim/GoalieLogic.kt` `track`):**
- The goalie sits on a straight line `GOALIE_DEPTH` (1.0 m) in front of his goal line.
- He slides sideways toward where the line from the net centre to the *stale* puck crosses it. The puck is seen `GoalieSkill.reactionS` old.
- His speed is capped at `GoalieSkill.speed`, and he stays between the posts.
- With the puck behind the goal line, he goes to the near post on that same line.
- **Saves (`trySave`, `tryStrip`):** a save is rolled when the puck's path this tick passes within `GOALIE_REACH` (0.75 m) of his position.

**Drawing (`render/FigurePainter.kt` `poseGoalie`):**
- He always faces straight up the ice (`0` or `π`).
- His ready pose is a deep squat with the hips at 0.62 m.
- He drops into `Clip.GOALIE_BUTTERFLY` (`render/anim/AnimDirector.kt`) on a Shot event at his net from within `FLAT_SHOT_M` (20 m).
- He gets up `BUTTERFLY_HOLD_S` after the shot resolves. He stays down while holding a caught puck through the whistle.

## Goals

### 1. Real positioning (sim)

Each tick, the goalie's target is a point on an arc in front of his net. It sits on the ray from the goal-line centre `(goalLineX, 0)` toward the stale puck, at a depth that depends on how far away that puck is:

- **Challenging:** stale puck at least `GOALIE_CHALLENGE_R` (about 6 m) from the goal-line centre. Depth `GOALIE_DEPTH_OUT` is about 1.5 m, near the top of the 1.8 m crease.
- **Close in:** stale puck within `GOALIE_CLOSE_R` (about 3 m). Depth `GOALIE_DEPTH_IN` is about 0.6 m, deep in the crease.
- **In between:** depth eases linearly between those two.
- **Angle limit:** the ray's angle from straight out is clamped to ±`GOALIE_MAX_ANGLE` (about 70°), so a puck along the goal line draws him toward that post rather than along the goal line. At full depth he can stand up to about 0.3 m outside the post's line while staying at least 0.2 m in front of the goal line.
- **Post seal:** with the stale puck at or behind the goal line, the target is the near post: `(goalLineX + dir·GOALIE_POST_OUT, ±(GOAL_HALF_W − GOALIE_POST_IN))`. Use about 0.35 m out and 0.25 m in from the post.
- **Movement:**
  - He moves toward the target at up to `GoalieSkill.speed` m/s. The cap now applies to his whole move in x and y, not just y.
  - The stale-puck reaction delay stays, so a quick pass across can still beat him.
  - `track` makes no random draws.
- **Saves:** saves, strips, catches, kicks and rebounds keep their current rules. They now measure from where he actually stands.
  - Rebounds still leave "straight out from the net ± `REBOUND_ANGLE`".
  - Catches still put the puck at his position.
- **Reset:** `resetGoalie` puts him at the challenging depth, straight out.

### 2. Faces the puck (drawing)

- **Facing:** each goalie's facing is the angle from him to the *live* puck.
  - It eases toward that angle at `GOALIE_TURN_RATE` (about 6 rad/s), so he turns rather than snapping.
  - It's clamped to ±`GOALIE_FACE_MAX` (about 110°) from straight out of his net. When he seals a post, he turns toward the corner.
  - While he holds a caught puck, in the goal pause, at breaks and at the final, he eases back to straight out. In every other phase, faceoffs included, he faces the puck.
- **Where it lives:** the facing is per-goalie animation state, held in `AnimDirector` beside the skaters' state. It advances by frame time. The sim gets no facing field.
- **Frame:** the whole figure, mesh and back number included, is posed in that frame.

### 3. Standing until shot at (drawing)

- **Ready stance:** a new ready pose replaces the squat.
  - Hips about 0.78 m, knees bent, a slight forward lean.
  - Pads close together, glove up and open, blocker and stick blade on the ice in front.
  - It reads clearly as standing.
- **Butterfly:** the existing butterfly still triggers only on a shot at his net from within `FLAT_SHOT_M`, and gets up as it does now. The arms-out save flash still layers over whatever pose he is in.

### 4. Movement that looks like a goalie (drawing)

- **Speed:** the goalie's drawn speed comes from his position change per tick (sim positions only).
  - A jump no real move could make (a faceoff or goal reset puts him straight at his spot) counts as no movement, not as a T-push. This is the controller's call.
- **Shuffle:** below `GOALIE_SHUFFLE_SPEED` (about 2.5 m/s), small, quick lateral steps. The pads stay square to the puck.
- **T-push:** above it, the lead leg turns toward the move and the trail leg pushes, extended. Use it for crossing the crease fast.
- **At rest:** back to the ready stance.
- **Blending:** each blends in and out over about 0.1–0.15 s, like the skaters' glide.

## Non-goals

- No change to how saves are decided (`saveBase`, the speed and distance terms, `GOALIE_REACH`), to the outcome split, or to rebound direction. Balance comes from the dials below.
- No reverse-VH or other post poses beyond standing and facing the corner.
- No goalie poke checks, puck handling or leaving the crease area.
- No facing in the sim.

## Anti-goals

- **A puck through a drawn goalie.** Saves happen where he is drawn, because the drawn position is the sim position. Nothing render-side offsets him.
- **A twitchy goalie.**
  - He must not snap or spin, turn his back to the play, or jitter at a target.
  - The movement animation must not flicker between shuffle and T-push near the threshold. Use hysteresis or blend weights.
- **A goalie stuck down.** He must always get back up, as before.
- **Dropped frames.** No per-frame or per-tick allocation. The 8,000-triangle budget holds.
- **A looser BalanceTest.** Don't loosen it to make the numbers pass.

## Balance

**Acceptance:** over 200 seeds, every win rate stays within ±5 points of the current baseline. The current BalanceTest must pass unloosened.

| Matchup | Current win rate |
|---|---|
| kid vs Easy | 100 |
| little vs Easy | 100 |
| kid vs Medium | 80 |
| little vs Medium | 0 |
| skilled vs Medium | 91 |
| kid vs Hard | 26 |
| skilled vs Hard | 56.5 |

**Dials, in order of preference:**
1. Each level's `GoalieSkill` (`saveBase`, `reactionS`, `speed`) and the player's goalie in `Difficulty`.
2. The positioning constants above.

The controller may rule if the acceptance can't be met, as in the zone-faceoff pass. The rule that any re-baselined bound needs Chris's approval stands.

## Constraints (carried over)

- Pure-Kotlin sim. Deterministic. Nothing render-side feeds the sim.
- Compose Canvas drawing, with no per-frame or per-tick allocation in the draw, sim or anim paths.
- Commits go on main.
- Run `./gradlew clean testDebugUnitTest` with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- Check the preview PNGs for visual tasks.
- Install on the Pixel 9 (`adb install -r`) when done.

## Approaches

- **Picked: positioning in the sim, drawing matches.** The goalie's real position moves on the arc, and facing, stance and movement are drawn from it.
- **Rejected: draw-only positioning,** offsetting the drawn goalie from the sim line. Saves would happen up to about 0.5 m from where he's drawn.
- **Rejected: rebuild the goalie as an AI skater.** It's far larger and puts the save logic at risk.

## Left to the builder

- The exact depth curve, angle clamp, turn rate and post offsets, within the ranges above.
- The ready, shuffle and T-push key poses, within the goalie mesh's proportions.
- The blend timing.
- How facing is clamped and eased, including wrapping across ±π.
- Which `GoalieSkill` numbers move in the balance pass.
