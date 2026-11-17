# Gameplay feel pass: design

**Date:** 2026-11-16
**Builds on:** docs/superpowers/specs/2026-11-16-player-graphics-design.md (3D mesh players, closer camera, animations), now shipped on main.
**Size:** a small change set. There are six independent drawing changes; Chris approved the design in conversation.

## Intent

Chris played the new 3D look and wants it to feel more like real hockey on TV:
- a slightly wider view
- numbers that belong to the jersey
- a believable puck size
- skaters who glide rather than run
- shots that leave the stick and fly
- goalies that react

## Goals

1. **Zoom 1.6× instead of 1.8×.** Zoom out slightly. The 1.6× is measured the same way `CameraTest.a_skater_is_about_1_7_times_bigger_than_before` measures it today: the centre-ice scale ÷ `OLD_SCALE_PER_HEIGHT · h`.
2. **Jersey numbers on the back.** They are painted on the back of the jersey and turn and tilt with the torso. They fade out as the player turns side-on. When the player faces the camera, no number shows. Chris said: "can't tell and that's okay".
3. **Smaller puck.** The draw radius goes from 0.24 m to about 0.15 m, keeping the light outline so it stays readable.
4. **Gliding, not running.**
   - Strides are longer, lower and pushed out to the side: the skate pushes out diagonally and recovers close to the ice.
   - When a skater isn't accelerating, both skates stay on the ice with the knees bent and a slight forward lean.
5. **Shots fly.**
   - On a shot, the puck pops off the blade and rises on an arc. It is in the air when it reaches the net: about crossbar height (~1.1 m) for a slapshot, lower (~0.5 m) for a wrist shot.
   - A dark shadow stays on the ice at the puck's real position.
   - Passes stay flat.
6. **Goalie butterfly.**
   - When a shot heads at a goalie's net, he drops into the butterfly: knees on the ice, pads flared flat along the ice to both sides, glove and blocker out.
   - He holds it until about 0.5 s after the save, goal or miss resolves, then recovers.

Chris's scorer-name and zone-faceoff addenda supersede two anti-goals in the base spec (`2026-11-16-north-star-hockey-design.md`): ":38 real player names" and ":41 dead time / play stops only for goals and period ends".

## Non-goals

- No change to sim behaviour. Puck height, the goalie butterfly and skating style are all drawing-only. The sim stays 2D and BalanceTest's random streams are untouched.
- No numbers on the front or sleeves. No player names.
- No puck bounces, deflections, crossbar hits or saves that depend on height.

## Anti-goals

- **Unreadable play.** The puck must stay easy to find. The shadow marks its true position on the ice, and the smaller puck keeps its outline.
- **Dropped frames.** No per-frame allocation, and the 8,000-triangle budget still holds.
- **A goalie stuck down.** He must always get back up, including after a goal, a faceoff or a period end.

## Constraints (carried over)

- Everything is drawn on the Compose Canvas. No per-frame or per-tick allocation in draw, sim or anim paths.
- The sim stays pure Kotlin, and nothing render-side feeds back into it.
- Commits go on main.
- Preview PNGs are checked for visual tasks.
- `./gradlew clean testDebugUnitTest` with JAVA_HOME=/opt/homebrew/opt/openjdk@21.

## Approaches

**Puck flight**
- **Picked: drawing-only.** A visual height is computed per frame from the last Shot event (power, the shooter's point, distance to the target net) and drawn with a shadow.
- **Rejected: a real puck z in the sim.** It would change saves and goals and break the seed-fragile BalanceTest.

**Goalie butterfly**
- **Picked: a drawn clip** (`GOALIE_BUTTERFLY`), triggered by a Shot event toward that goalie's net.
- **Rejected: a sim-side butterfly state** that changes what he can stop. That is a gameplay change.

**Numbers**
- **Picked: draw the text through a canvas matrix** mapped onto the torso's back plane (the projected shoulder→hip axis and shoulder-width axis). Alpha comes from how squarely the back faces the camera.
- **Rejected: digits built from mesh triangles.** Far more work for a blurrier result.

**Zoom, puck size and gliding** each have one sensible way: a constant, a constant, and a rework of the procedural leg cycle in `FigurePainter.poseSkating`.

## Decided

- **Number visibility:** shown only when the back faces the camera. The facing term is the dot product of the torso's back normal with the direction to the camera. Full alpha when it is ≥ 0.5, and it fades to 0 at about 0.1.
- **Puck shadow:** a dark translucent ellipse on the ice at the sim position, drawn whenever the puck's visual height is above 0.
- **Shot arc:**
  - It starts at about blade height (~0.05 m) and rises quickly.
  - It reaches the target height by the time the puck reaches the goal line, and holds roughly level into the net.
  - It drops back to the ice smoothly when the shot is saved, covered, picked up or deflected wide, or goes past the goal line.
  - The streak follows the drawn (lifted) puck.
- **Gliding trigger:** "not accelerating" is read from the skater's input (stick centred / gliding) or a falling speed. The glide pose blends in and out over about 0.15 s.
- **Butterfly:**
  - It triggers only for shots heading at that goalie's net.
  - A covered puck holds the butterfly until the cover ends.
  - The existing save arms-out flash folds into the butterfly pose rather than fighting it.

## Left to the builder

- The exact stride timing and joint angles, tuned by eye in the previews.
- The exact arc curve.
- The butterfly key poses, within the goalie mesh's proportions.
- The shadow size.
- The number's exact placement on the back.

## Addendum: goalie rebounds go to a teammate (Chris, mid-plan)

Chris: "instead of the goalie just kicking out the puck to the side, they should pass it directly to one of their teammates to take it up the ice".

**How it works now.** A smothered (slow) puck is already passed out to a teammate. The puck he "kicks to the side" is a hard-shot rebound off him at a random angle.

**Decided.** Chris chose "steer to a teammate":
- On a hard-shot save with an open teammate (clear lane, up ice), the rebound goes straight to that teammate like a pass.
- With nobody open, it still bounces loose.
- Rebound goals still happen, just less often.

This is the one sim change in this pass, so it waives the "no sim change" non-goal for `sim/GoalieLogic.kt` only.
- The random draw order is preserved: the rebound angle is always drawn.
- BalanceTest is not retuned without a ruling. If it moves, the controller decides and records the decision.

## Addendum: no marker triangle (Chris, mid-plan)

Chris: "no triangle above the active player... the green circle around their feet is enough".
- Remove the triangle drawn above the controlled player.
- Keep the green foot ring.
- Keep the off-screen pass-target edge arrow.
- Chris also asked for a yellow ring at the feet of the puck carrier when the carrier is not you, whether a teammate or an opponent.
- It is the same yellow as the pass-target ring. The two never show together, because the pass-target ring only appears while you carry.

## Addendum: the goal banner names the scorer (Chris, mid-plan)

Chris: "when it says who scored, say the player name... for example 'Boldy scores for the Wild!'"

- **Text:** "<Surname> scores for the <Nickname>!".
- **Own goal:** the same wording, with the scorer's own team: "Faber scores for the Wild!" (Chris). "Goal for the <Nickname>!" is used only when there is no valid scorer id.
- **Names:** the controller's call is real surnames matched to the jersey numbers already on each roster. #43 for MIN is Quinn Hughes, checked on [ESPN](https://www.espn.com/nhl/player/bio/_/id/4320548). The rest are from the controller's knowledge of the current rosters, and Chris can correct any of them.
- **Size:** the text shrinks to fit between the controls.

### Rebound balance (Chris's decision)

**What happened.** Steering every hard rebound to a teammate made Hard much easier. Over 100 seeds, a kid-level player beat Hard 31% of the time (was 15%) and a skilled bot 64% (was 47%), because Hard's attackers lost their rebound goals.

**Decision: the difficulty sets how often each goalie steers.**
- `GoalieSkill` gains a steer probability.
- The computer's goalie steers often on Hard and rarely on Easy.
- The player's goalie is the reverse: often on Easy, rarely on Hard.
- On Medium the two are even.
- The chances are tuned until the existing BalanceTest passes unloosened, and the 100-seed Hard win rates are back near their pre-change values.

### Superseded: save outcomes and zone faceoffs (Chris, later)

Chris: "the goalie should kick out the puck to a teammate only some of the time, and otherwise it should behave as if it was caught, and then there's a face-off on the dot in the same zone, just like real hockey. That should be the primary case."

Decided:
- **Split of save outcomes.** About 65% caught, 25% kicked to an open teammate (caught instead if nobody is open), and 10% loose rebound. Chris kept a few loose rebounds.
- **Caught pucks.** The goalie holds the puck, the clock stops for about 1 s, then there is a faceoff on that zone's dot, on the side of the catch. Players are placed directly at their spots, as after a goal.
- **The old covered-puck release pass is removed.**
- **Balance.** Measured over 200 seeds, every win rate stays within ±5 points of the pre-change baseline, and the existing BalanceTest passes unloosened. The dial is the per-difficulty outcome split.

### Zone-faceoff balance (Chris's decision)

**What happened.** End-zone faceoffs made every difficulty easier. Over 200 seeds, kid vs Hard went from 21% to about 55% and skilled vs Hard from 54% to about 67%. The attacking side scores off zone draws about 4× more often than the AI does, and the catch/kick/loose split doesn't move this.

**Decision: faceoff skill plus a defensive-zone plan.**
- **Faceoff skill.** `Difficulty.faceoffWin` is the computer centre's chance of winning a draw: Easy 0.35, Medium 0.8, Hard 0.85 (as shipped).
  - At the drop, one roll picks the winner.
  - The puck is pulled back toward the winner's own defencemen instead of drifting randomly.
- **Defensive-zone plan.** After a faceoff in the AI's own zone, for a few seconds, the AI defends goal-side, man-to-man: each AI skater stays between an opponent and the net, and the centre covers the slot.
- **Acceptance.** 200-seed win rates within ±5 points of the pre-change baseline, and BalanceTest thresholds unloosened.
  - The game-length tick cap is a harness safety limit, not a balance threshold. It may rise to fit the extra stoppages.
