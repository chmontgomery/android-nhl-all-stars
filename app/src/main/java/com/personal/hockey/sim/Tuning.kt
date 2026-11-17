package com.personal.hockey.sim

/** Every gameplay number. Metres, seconds, radians. */
object Tuning {
    const val DT = 1f / 60f

    // Skating
    const val SKATER_R = 0.45f
    const val MAX_SPEED = 9f
    const val ACCEL = 22f
    const val GLIDE_DRAG = 1.2f        // per second, applied when stick is centred
    const val TURN_RATE = 7f           // rad/s that velocity direction can change at top speed
    const val CARRY_SPEED = 0.92f
    const val STICK_DEAD_ZONE = 0.1f   // stick lengths below this count as centred
    const val WINDUP_SPEED = 0.5f      // top-speed factor while winding up a shot
    const val FACE_MIN_SPEED = 0.5f    // facing follows velocity only above this speed
    const val TURN_MIN_SPEED = 1f      // turn limit stops growing as speed falls below this
    const val COAST_DECEL = 8f         // m/s² shed when the stick asks for less speed than you have
    const val STOP_SPEED = 0.05f       // a gliding skater slower than this stops dead
    const val HOCKEY_STOP_ANGLE = 2.356f // rad (135°): a stick further than this from the heading...
    const val HOCKEY_STOP_STICK = 0.5f   // ...and pushed past this is a hockey stop; otherwise a carve

    // Puck
    const val PUCK_R = 0.05f
    const val PUCK_DRAG = 0.35f        // per second
    const val BOARD_BOUNCE = 0.6f
    const val BOARDS_EVENT_SPEED = 3f  // impact speed into the boards that emits SimEvent.Boards
    const val STICK_REACH = 1.2f
    const val CATCH_SPEED = 14f        // loose pucks faster than this deflect
    const val PASS_CATCH_SPEED = 30f   // for puck.passTarget only
    const val CARRY_OFFSET = 0.9f      // puck sits this far ahead of the carrier along facing
    const val PICKUP_LOCK = 0.5f
    const val DEFLECT_KEEP = 0.7f      // fraction of speed a deflected puck keeps
    const val DEFLECT_ANGLE = 0.3f     // max random change of direction on a deflection, radians
    const val DEFLECT_LOCK = 0.2f      // the deflecting skater can't touch the puck again for this long
    const val STUCK_SPEED = 0.3f
    const val STUCK_TIME = 3f
    const val STUCK_CLEAR_R = 3f       // no eligible skater this close for the stuck guard to act
    const val STUCK_PUSH = 6f          // m/s toward centre ice given to a stuck puck

    // Actions
    const val STICK_PUSHED = 0.3f      // stick past this aims a pass, shot, deke or check; below is centred
    const val WINDUP_MIN = 0.15f; const val WINDUP_MAX = 0.8f
    const val WINDUP_AUTO_FIRE = WINDUP_MAX + 0.2f   // a shot held this long fires itself
    const val SHOT_SPEED_MIN = 22f; const val SHOT_SPEED_MAX = 38f
    const val SHOT_SPREAD = 0.03f      // rad at tap, ×2 at full power (Task 7: was 0.05; slot shots missed the net)
    const val SHOT_AIM = 0.6f          // shots aim this fraction of GOAL_HALF_W off centre (Task 7: was 0.75)
    const val PASS_SPEED = 20f
    const val PASS_TIE = 0.2f          // rad: aimed-pass candidates this close in angle go to the nearer
    const val PASS_LANE_CLEAR = 1.5f   // a centred pass needs no opponent this close to the passing line
    const val DEKE_TIME = 0.35f; const val DEKE_SPEED = 9f; const val DEKE_CD = 1f
    const val POKE_TIME = 0.25f; const val POKE_REACH = 1.8f; const val POKE_ARC = 0.9f /* rad half-angle */; const val POKE_CD = 0.6f
    const val POKE_PUCK_SPEED = 7f
    const val POKE_PICKUP_LOCK = 0.25f // a poker that knocks the puck can't take it for this long
    const val POKE_SCATTER = 0.5f      // rad either side of the poker's facing
    const val BODY_TIME = 0.3f; const val BODY_SPEED = 11f; const val BODY_REACH = 1.1f
    const val HIT_PUSH = 4f            // m/s added to a checked skater along the hit
    const val HIT_PUCK_SPEED = 5f      // m/s of the puck knocked off a checked carrier
    const val DOWN_TIME = 0.6f; const val RECOVER_TIME = 0.5f; const val RECOVER_SPEED = 0.4f
    const val DOWN_DRAG = 4f           // per second, a downed skater sliding

    // Game flow
    const val PERIOD_SECONDS = 120f; const val PERIODS = 3
    const val FACEOFF_TIME = 1.5f; const val GOAL_TIME = 2.5f; const val CELEBRATE_TIME = 1.5f; const val BREAK_TIME = 2.5f
    const val WHISTLE_TIME = 1.0f      // s the clock stops after a goalie catches the puck, before the faceoff
    const val FACEOFF_WIN_SPEED = 4f     // m/s the won draw is pulled back toward the winner's defencemen
    const val FACEOFF_SPREAD = 0.3f      // rad either side of straight at them
    const val TIE_UP_S = 1.2f            // s the centre who lost the draw can't poke, check or pick up
    const val FACEOFF_PICKUP_LOCK = 0.25f // nobody can take the dropped puck for this long: a short battle
    const val CELEBRATE_SPEED = 5.4f   // m/s teammates skate at toward the scorer after a goal
    const val CELEBRATE_GAP = 1.2f     // m from the scorer at which they stop pressing in

    // Goalies
    const val GOALIE_REACH = 0.75f
    // He stands on an arc: on the ray from the goal-line centre to the puck he sees, this far out.
    const val GOALIE_DEPTH_OUT = 1.5f    // m out when challenging, near the top of the crease
    const val GOALIE_DEPTH_IN = 0.6f     // m out with the puck close, deep in the crease
    const val GOALIE_CHALLENGE_R = 6f    // puck at least this far from the goal-line centre: full depth
    const val GOALIE_CLOSE_R = 3f        // puck within this: deep; depth eases linearly in between
    const val GOALIE_MAX_ANGLE = 1.22f   // rad (70°) either side of straight out
    const val GOALIE_POST_OUT = 0.35f    // m out from the goal line when sealing a post
    const val GOALIE_POST_IN = 0.25f     // m in from the post when sealing it
    const val GOALIE_BODY_R = 0.8f     // skaters are kept this far from a goalie's centre
    const val COVER_SPEED = 25f        // a saved puck slower than this is always caught; faster rolls an outcome
    const val STRIP_SPEED = 20f        // a stripped puck's kick or rebound leaves as if saved at this speed
    const val REBOUND_KEEP = 0.3f      // fraction of speed a rebound keeps
    const val REBOUND_ANGLE = 1f       // rad either side of straight out from the net
    const val KICK_LANE_CLEAR = 2f     // a goalie's kick needs no opponent this close to the line
    const val KICK_MIN_OUT = 1f        // a kick aims at least this far up ice from the goalie

    // Control switching
    const val SWITCH_MARGIN = 1.5f; const val SWITCH_TIME = 0.25f
}
