# North Star Hockey — arcade hockey for Android (v1 design)

Date: 2026-11-16. Status: approved by Chris 2026-11-16.

Items marked **(call)** were decided by the designer, not stated by Chris. Everything else is his.

## Intent

A landscape arcade hockey game for one family's phones, played by Chris, his 11-year-old and his
6-year-old. One player controls one skater at a time against a computer team. The first version exists
to make the on-ice game feel good: fighting for the puck, skating around people, shooting, and an
opponent that can steal the puck and score. Almost nothing exists outside the game.

It is inspired by mobile arcade hockey games with a left stick and right-side action buttons. It is an
original game: its own name, look, HUD and art.

## Goals

- Skate with a left-thumb stick; Shoot / Pass / Deke with the puck; Poke / Body without it.
- Loose-puck battles, getting past defenders, and shots on a goalie all work and feel fair.
- The opponent steals the puck and shoots.
- Two opponent levels: **Easy**, which a 6-year-old can beat, and **Hard**, which challenges an adult
  and an 11-year-old.
- Pick your team and the opposing team from four: Minnesota Wild, Colorado Avalanche, Edmonton Oilers,
  Toronto Maple Leafs.
- A full game of three periods on a fast clock, in Minnesota's home rink.
- Same architecture as `~/git/android-sims` and `~/git/android-hot-potatoe`.

## Non-goals (v1)

Pull-goalie button, manual player switching, offside, icing, penalties, line changes, fatigue, overtime,
shootouts, replays, player names or ratings, stats, seasons, multiplayer, commentary, controller support,
tablets, portrait.

## Anti-goals

- Looking like, or carrying the name or branding of, any existing hockey game.
- Official league or team logos, real player names, or real arena sponsor branding.
- An Easy level that a 6-year-old loses. Easy must be winnable with imprecise play.
- Opponents that cheat visibly: no speed above the skater cap, no reading inputs, no catch-up boosts.
- Dead time. Play stops only for goals and period ends.

## Constraints

- Kotlin, Jetpack Compose (Material 3), Compose `Canvas`, no game engine, no image assets. Same Gradle
  setup and versions as `android-sims` (AGP 8.7.2, Kotlin 2.0.21, Compose BOM 2024.10.01, Gradle 8.10.2,
  JDK 17 target).
- `applicationId` / namespace `com.personal.hockey`, `rootProject.name = "NorthStarHockey"` **(call)**; the app is named "North Star Hockey" (Chris's choice).
- `minSdk 29`, `targetSdk 36` **(call)**: Pixel 9 is the target; Moto G Power 2021 should stay playable.
- Landscape only (`sensorLandscape`), immersive full screen, screen kept on during a game.
- Debug build, sideloaded. No network, accounts, ads or analytics. Permission: `VIBRATE` only.
- 60 fps on a Pixel 9. No per-frame allocation in the simulation or drawing hot paths.
- Tests run on the JVM with JUnit and Robolectric, as in the sibling repos.

## Approaches

1. **Compose Canvas, everything drawn in code (chosen).** A flat-rink simulation in pure Kotlin is
   projected into an angled broadcast view; skaters are jointed figures drawn in team colours. Matches
   the sibling repos, needs no art pipeline, and puts the effort into gameplay. Players look stylised.
2. **Pre-rendered sprites (the Little Sims route).** Better-looking skaters, but every animation must be
   rendered from many angles in four teams' colours before the game is playable.
3. **A 3D engine.** Closest to a console look, but abandons the existing architecture and test setup and
   spends v1 on rendering.

Chris asked for the same architecture and for gameplay first, so 1 was chosen **(call)**. Drawing sits
behind one interface so sprites can replace it later without touching the simulation.

## The game

### Flow

`Team select → Game → Final score`, using the same `Navigator` / `AppRoot` / `AppGraph` pattern as the
sibling repos.

- **Team select:** a "You" row and an "Opponent" row of four team cards, an Easy / Hard switch, and Play.
  The same team cannot be picked on both rows **(call)**. Last choices are remembered (DataStore).
- **Game:** described below. A pause button opens an overlay with Resume, Quit, and Sound and Vibration
  switches **(call)**. Backgrounding the app pauses. Back button pauses.
- **Final score:** score, winner or "Tie", Rematch, Change teams.

A game in progress is not saved; if the process dies the app returns to team select **(call)**.

### Screen

```
[MIN 1 | 0 COL | 2nd 14:32]                      [ II ]
            angled side-on view of the rink,
            camera follows the puck
                                          ( SHOOT )
 ( stick )                         ( PASS ) ( DEKE )
```

Without the puck the right side shows `( POKE )` and `( BODY )` in the Pass and Deke positions and no
third button. The buttons switch the moment the controlled skater gains or loses the puck (a teammate
never carries it uncontrolled; see Control).

- **Stick:** floating **(call)**. It appears wherever the left thumb lands in the left 45% of the screen,
  with a faint resting outline at the default spot. Analogue: direction and how far it is pushed.
- **Buttons:** fixed, at least 72dp, with a generous touch area. Stick and buttons work at the same time
  (independent pointers).
- **HUD:** home-made scoreboard top left (three-letter codes, score, period, clock), pause top right.

### Rules

- 5 skaters (C, LW, RW, LD, RD) and a goalie per side.
- Faceoff at centre to start each period and after each goal. The puck is dropped between the centres
  after a short pause and is loose; nobody needs to press anything.
- No other stoppages. The puck cannot leave the rink. A goalie who covers the puck passes it to a
  teammate after about a second.
- Three periods. Each shows 20:00 counting down and lasts 120 real seconds **(call)**. The period ends
  on the buzzer even with a shot in flight. A tie stays a tie.
- The player always attacks toward the right; teams do not switch ends **(call)**, so a 6-year-old
  never has to relearn which net is theirs.
- After a goal: horn, a banner for about 2.5 seconds, then the faceoff.
- All four teams play identically; they differ only in colours and crest **(call)**.

### Control

The player controls exactly one skater, marked with a ring under the skates.

- Player's team has the puck: the carrier is controlled. Passing hands control to the receiver the
  moment the puck leaves the stick.
- Otherwise: the player's skater nearest the puck is controlled. A switch needs the new skater to be
  clearly nearer for a short time, and never happens mid-action, so control does not flicker.
- Goalies are never controlled.

### Actions

Numbers are starting points for tuning.

| Action | What it does |
|--------|--------------|
| Skate | Accelerate toward the stick direction up to a top speed scaled by how far the stick is pushed. Glide and limited turn rate so it feels like ice. Carrying the puck is slightly slower (about 8%). |
| Shoot | Press to wind up, release to fire. Power grows with hold time from about 0.15s to 0.8s; a tap is a quick wrist shot. Aimed at the opponent's net; pushing the stick up or down picks the far or near side. Small random spread, larger at full power and when off-balance. |
| Pass | Goes to the teammate best matching the stick direction (angle first, then distance); with the stick centred, the most open teammate ahead. Leads the receiver. A faint marker shows the current pass target. |
| Deke | A quick sidestep (about 0.35s) in the stick direction. During it, pokes miss and body checks slide past. About 1s cooldown. |
| Poke | A stick jab (about 0.25s) reaching about 1.8m in front. If it reaches the puck, the puck is knocked loose. About 0.6s cooldown. |
| Body | A short lunge. Hitting an opposing skater knocks them down for about 0.6s and frees the puck if they had it. A miss leaves the checker slowed for about 0.5s. Goalies cannot be hit. |

After losing the puck, a skater cannot pick it up again for a brief moment, so a poke or hit produces a
real loose puck instead of an instant re-grab.

### Puck and physics

- Rink in metres: 60 × 26 with rounded corners, goal lines, blue lines, centre line, creases and nets.
- Puck is a point with 2D velocity and ice friction; it bounces off boards and off the outside of the
  nets with energy loss. Any shot height is cosmetic **(call)**.
- A skater picks up a loose puck that comes within stick reach below a catch speed; faster pucks deflect.
  Passes to a teammate are caught at higher speeds than stray pucks.
- Skaters are circles that push each other apart and stop at the boards.
- **Goalie:** moves across the crease to stay between puck and net, limited by a reaction delay and a top
  speed. A shot that reaches the goalie's reach is saved with a chance that falls with shot speed and
  closeness and rises with goalie skill; a shot outside reach and inside the posts scores. Saves rebound
  or are covered.
- Fixed simulation step of 1/60s with an accumulator; long frame stalls are clamped.

### Computer players

One AI drives every skater the player is not controlling, on both teams.

- **Shape:** each position has a home spot that shifts with the puck and with who has it (attacking,
  defending, loose).
- **With the puck (opponents only, since the player controls their own carrier):** carry toward the net,
  avoid the nearest checker, pass when pressured and a teammate is open, shoot from a good spot.
- **Without it:** one skater pressures the carrier and may poke or hit; the rest cover passing lanes and
  their home spots. On a loose puck the nearest skater goes for it.
- **Player's teammates:** always play at full competence. They get open for passes, cover, and defend
  modestly, but leave the puck to the player's skater when the player is close to it.

Difficulty changes only the opposing team, through one table of numbers:

| | Easy | Hard |
|---|---|---|
| Top speed vs. the player | about 75% | 100% |
| Reaction delay | long (about 0.6s) | short (about 0.15s) |
| Pokes and hits | rare, often mistimed | frequent, well timed |
| Passing | seldom | to open players, under pressure |
| Shooting | weak, at the goalie | hard, at corners |
| Their goalie | slow, saves less | quick, saves more |

The player's own goalie is the same strong goalie at both levels **(call)**.

### Look

- Broadcast camera: side-on, raised, with perspective, showing roughly half the rink and following the
  puck smoothly along the rink's length. One pure function maps rink position (and height) to the screen.
- Ice with standard markings, boards with a coloured kick plate, glass, simple stands as blocks of crowd
  colour. Near-side boards are low and see-through so they never hide play.
- Minnesota home rink: forest green, red and wheat trim, and an original centre-ice mark (pine tree and
  north star). No real arena name or sponsors.
- Skaters: jointed figures with helmet, jersey, number, stick, a skating stride, wind-up, poke, check and
  knocked-down poses, and a soft shadow. Drawn far-to-near.
- Player's team wears its dark colour; the opponent wears white with its own trim **(call)**, so any
  pairing (including Edmonton vs. Toronto) is readable.
- Teams: full name on the select screen, three-letter code in the HUD (MIN, COL, EDM, TOR), team
  colours, and a simple original crest each (pine tree, mountain peak, oil drop, leaf).

### Sound and vibration

Synthesised in code as in Hot Potato: stick on puck, pass, shot, boards, save, body check, whistle,
period buzzer, goal horn and a crowd cheer. Vibration on checks and goals. No music or constant crowd
bed **(call)**. Both can be switched off.

## Structure

Package `com.personal.hockey`, one `app` module.

| Package | Holds |
|---------|-------|
| `sim` | Pure Kotlin. Rink geometry, puck, skaters, goalies, actions, possession, goals, clock, periods, faceoffs. `HockeySim.step(input)` advances one tick and emits events (shot, save, hit, goal, period end…). No Android or Compose imports. Takes a seeded `Random`. |
| `ai` | Pure Kotlin. Positioning, carrier, defender and goalie decisions; the `Difficulty` table. Produces the same per-skater input the player produces. |
| `teams` | The four teams: name, code, colours, crest id. |
| `input` | Touch pointers → stick vector and button states. Pure mapping logic separate from the Compose pointer handling. |
| `render` | Camera projection, rink drawing, skater drawing, behind a `GameRenderer` interface. |
| `audio` | Sound synthesis, `Feedback` interface, Android implementation, and a director that turns sim events into sounds and buzzes. |
| `settings` | DataStore: last teams, difficulty, sound, vibration. |
| `game` | `GameViewModel` (owns the sim, steps it from the screen's frame clock, pauses), `GameScreen`, HUD, controls overlay, pause overlay. |
| `ui` | `Navigator`, `AppRoot`, team select, final score, theme, shared components. |

All tuning numbers live in one place in `sim` and one `Difficulty` table in `ai`.

## Testing

- **Sim:** skating limits, board and net bounces, pickup rules, each action's effect and cooldown, deke
  beating poke and body, goal detection, goalie saves, clock and period flow, faceoffs, control switching.
- **AI, headless and seeded:** whole games run without a screen. Games always finish; the puck never
  leaves the rink or sticks; Hard beats Easy over many seeded games; scoring per game stays in a sane
  range. A simple scripted "kid" player (skate at the net, shoot) beats Easy most of the time.
- **Render and input:** projection maths, far-to-near ordering, pointer-to-control mapping, and that a
  frame draws without crashing (Robolectric).
- **App:** launch, team select → game → final score flow, pause on background, settings store.

## Built to extend

Chris plans later versions with more teams, alternate jerseys and specific players. So in v1:

- A team is data: id, name, code, colours, crest, a list of jerseys (dark and white now, alternates
  appended later) and a roster of five skaters plus a goalie number. Each player has a number, an
  optional name, and attribute multipliers for speed and shot (all standard in v1).
- All teams are listed in one file. Nothing else in the code names a team or assumes there are four;
  the team select rows scroll sideways when there are more.
- One "match setup" object chooses the two teams, their jerseys and the level, so a jersey picker later
  changes one place.
- The rink's colours, centre-ice mark and board text are one data object, so more rinks can follow.

## Left to the builder

Exact tuning values, formation numbers, how figures are drawn, class breakdown inside each package,
synth recipes, and the app icon.

## Later, not now

Sprite-based skaters, overtime and shootouts, a Medium level, more teams, manual switching, stats.
