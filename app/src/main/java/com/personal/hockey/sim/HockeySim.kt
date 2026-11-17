package com.personal.hockey.sim

import kotlin.math.ceil
import kotlin.random.Random

/** The whole game world, advanced one fixed tick ([Tuning.DT]) per [step]. */
class HockeySim(
    val random: Random,
    homeGoalie: GoalieSkill = GoalieSkill(0.1f, 6f, 0.7f),
    awayGoalie: GoalieSkill = GoalieSkill(0.1f, 6f, 0.7f),
) {
    /** Ids 0..4 HOME, 5..9 AWAY, each in [Position] order. */
    val skaters: List<Skater> = Side.entries.flatMap { side ->
        Position.entries.map { pos -> Skater(side.index * 5 + pos.ordinal, side, pos) }
    }

    val puck = Puck()

    /** Index = [Side.index]. */
    val goalies: List<Goalie> = listOf(Goalie(Side.HOME, homeGoalie), Goalie(Side.AWAY, awayGoalie))

    /** Cleared at the start of every [step]. */
    val events = ArrayList<SimEvent>()

    var phase = Phase.FACEOFF
    /** Seconds left in FACEOFF / WHISTLE / GOAL / CELEBRATE / BREAK. */
    var phaseT = 0f
    /** The chance the AWAY centre wins a faceoff draw; HOME wins the rest. */
    var faceoffWinAway = 0.5f
    /** The dot the faceoff after the current WHISTLE is taken on. */
    internal var whistleDotX = 0f
    internal var whistleDotY = 0f
    /** 1..[Tuning.PERIODS]. */
    var period = 1
    /** Real seconds left in the period. */
    var clockS = Tuning.PERIOD_SECONDS
    /** [home, away]. */
    val score = IntArray(2)
    var lastGoalSide: Side? = null
    /** The skater credited with the goal that set [lastGoalSide]; -1 before any goal. */
    var lastScorerId = -1
        internal set

    /** Id of the last skater of each side (index = [Side.index]) to carry or deflect the puck; -1 none. */
    internal val lastTouch = IntArray(2) { -1 }

    /** False for AI-only games; control switching then leaves [controlledId] at -1. */
    var humanControlled = true
    /** The HOME skater the human drives; -1 when nobody is. */
    var controlledId = 0

    /** The clock as shown: 1200 at the start of a period down to 0 (ten shown seconds per real one). */
    val displaySeconds: Int get() = ceil(clockS * 10f).toInt()

    /** [displaySeconds] as m:ss, e.g. "20:00", "0:01". */
    fun clockText(): String {
        val d = displaySeconds
        val s = d % 60
        return "${d / 60}:${if (s < 10) "0" else ""}$s"
    }

    private val bySide: List<List<Skater>> = Side.entries.map { skaters.subList(it.index * 5, it.index * 5 + 5) }

    /** Scratch buffer for [Rink.clamp]. */
    internal val clampOut = FloatArray(4)

    /** Control switching: the skater that has been the better choice, and for how long. */
    internal var switchCandidate = -1
    internal var switchT = 0f

    init {
        startFaceoff()
    }

    fun skatersOf(side: Side): List<Skater> = bySide[side.index]

    /**
     * Order: flow timers and clock, actions (live play only), skaters, goalies, puck, goal check,
     * end of period (after the goal check, so a goal on the last tick counts), control.
     */
    fun step() {
        events.clear()
        stepFlow()
        if (phase == Phase.PLAY) stepActions() else for (i in skaters.indices) latchButtons(skaters[i])
        stepSkaters()
        stepGoalies()
        val px = puck.x; val py = puck.y
        stepPuck()
        checkGoal(px, py)
        checkPeriodEnd()
        stepControl()
    }

    /** For tests of play mechanics: live play with the clock running, positions left alone. */
    fun startPlayForTest() {
        phase = Phase.PLAY
        phaseT = 0f
    }

    /** Puts the puck on [s]'s stick. */
    fun giveTo(s: Skater) {
        puck.carrier = s
        puck.passTarget = null
        puck.lastSide = s.side
        lastTouch[s.side.index] = s.id
        puck.stillT = 0f
        puck.fromShot = false
        placeOnStick(s)
        events.add(SimEvent.Pickup(s.side, s.id))
    }

    /** Teammate a pass from [s] would go to right now, or null. Used by Actions and the renderer. */
    fun passTargetFor(s: Skater): Skater? = choosePassTarget(s)

    /** True while a goalie is holding the puck he caught. */
    fun puckHeld(): Boolean = goalies[0].holding || goalies[1].holding

    /**
     * Frees the puck at velocity (vx, vy); the old carrier can't pick it up again for [lock] s, and
     * loses any shot it was winding up.
     */
    fun knockLoose(vx: Float, vy: Float, lock: Float) {
        val c = puck.carrier
        if (c != null) {
            c.pickupLock = lock
            if (c.act == Act.WINDUP) { c.act = Act.NONE; c.windup = 0f }
        }
        puck.carrier = null
        puck.fromShot = false
        puck.vx = vx; puck.vy = vy
    }
}
