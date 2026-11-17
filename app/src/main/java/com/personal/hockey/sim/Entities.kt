package com.personal.hockey.sim

/** HOME is the human's team: defends x = -GOAL_X and attacks +x. AWAY attacks -x. */
enum class Side {
    HOME, AWAY;

    /** Attacking direction along x: +1 HOME, -1 AWAY. */
    val dir: Float get() = if (this == HOME) 1f else -1f
    val other: Side get() = if (this == HOME) AWAY else HOME
    val index: Int get() = ordinal
}

/** What a skater is busy doing. Stick control applies in NONE, WINDUP, POKE and RECOVER (slowed). */
enum class Act { NONE, WINDUP, DEKE, POKE, BODY, DOWN, RECOVER }

/** One tick's controls. Buttons are held state; the sim detects edges against [Skater.prev]. */
class SkaterInput {
    var moveX = 0f; var moveY = 0f   // stick, length 0..1
    var shoot = false; var pass = false; var deke = false; var poke = false; var body = false

    fun clear() {
        moveX = 0f; moveY = 0f
        shoot = false; pass = false; deke = false; poke = false; body = false
    }
}

class Skater(val id: Int, val side: Side, val pos: Position) {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var facing = 0f
    var act = Act.NONE; var actT = 0f           // seconds left in the current act
    var windup = 0f                             // seconds held so far
    var dekeCd = 0f; var pokeCd = 0f; var pickupLock = 0f
    /** Seconds left tied up after losing a faceoff draw: no poke, check or pickup, only skating. */
    var tiedUpT = 0f
        internal set
    var speedScale = 1f                         // difficulty × player attribute, 1 = standard
    var shotScale = 1f                          // player attribute; multiplies shot speed (Task 5)
    /** True while hockey-stopping (braking hard against its heading); set by the sim each tick. */
    var stopping = false
        internal set
    var stride = 0f                             // metres skated, for animation
    val input = SkaterInput()
    internal val prev = SkaterInput()           // last tick's buttons, for edge detection
}

class Puck {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var carrier: Skater? = null
    var passTarget: Skater? = null      // set by a pass until someone touches the puck
    var lastSide: Side? = null          // side that last carried or shot it
    var stillT = 0f                     // seconds loose and slower than STUCK_SPEED
    var fromShot = false                // loose because of a shot, not yet touched by anyone else
    var shotX = 0f; var shotY = 0f      // where that shot was taken, for the goalie's save chance
}

/**
 * [reactionS] how stale the puck position the goalie reacts to is; [speed] m/s along the crease;
 * [saveBase] 0..1, the base chance of saving a puck that reaches it. A save he rolls an outcome for
 * is caught [catch] of the time, kicked to an open teammate [kick] of the time, and otherwise
 * left loose.
 */
data class GoalieSkill(
    val reactionS: Float,
    val speed: Float,
    val saveBase: Float,
    val catch: Float = 0.65f,
    val kick: Float = 0.25f,
)

/** Defends the net of [side]. Not a [Skater]: it never carries, only saves, catches and kicks. */
class Goalie(val side: Side, val skill: GoalieSkill) {
    var x = 0f; var y = 0f
    var holding = false                 // caught the puck; it is hidden at the goalie until the faceoff
    var saveFlash = 99f                 // s since last save, for animation

    /** Ring buffer of past puck positions; the oldest is [GoalieSkill.reactionS] old. */
    internal val seenX = FloatArray(Math.round(skill.reactionS / Tuning.DT).coerceAtLeast(0) + 1)
    internal val seenY = FloatArray(seenX.size)
    internal var seenHead = 0
    /** A save was rolled and failed; no new roll until the puck leaves reach. */
    internal var beaten = false
    /** A carrier in reach beat the save roll; no new roll until the carried puck leaves reach. */
    internal var carryBeaten = false
}
