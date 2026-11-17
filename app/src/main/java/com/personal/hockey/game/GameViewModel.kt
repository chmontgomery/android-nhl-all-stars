package com.personal.hockey.game

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ViewModel
import com.personal.hockey.ai.Match
import com.personal.hockey.audio.FeedbackDirector
import com.personal.hockey.input.Controls
import com.personal.hockey.input.ControlsLayout
import com.personal.hockey.render.CanvasRenderer
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.Tuning
import com.personal.hockey.teams.MatchSetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HudState(
    val homeCode: String,
    val awayCode: String,
    val homeScore: Int,
    val awayScore: Int,
    /** "1st", "2nd", "3rd". */
    val periodLabel: String,
    val clock: String,
    /**
     * "<Name> scores for the <Nickname>!" through the goal pause (GOAL, then CELEBRATE); "End of 1st" in BREAK; "Final" once
     * the game is over; null otherwise.
     */
    val banner: String?,
    /**
     * True while the banner is the goal call (GOAL and CELEBRATE), which sits low so it does not cover
     * the celebration.
     */
    val goalBanner: Boolean,
    val hasPuck: Boolean,
    val paused: Boolean,
    val finished: Boolean,
)

/** Owns one game: the match, the renderer, the touch controls and the fixed-step frame loop. */
class GameViewModel(
    val setup: MatchSetup,
    private val director: FeedbackDirector,
    seed: Long = System.nanoTime(),
) : ViewModel() {
    val match = Match(setup.level, seed, homeSkaters = setup.home.skaters, awaySkaters = setup.away.skaters)
    val renderer = CanvasRenderer(setup)
    val controls = Controls(ControlsLayout(DEFAULT_W, DEFAULT_H, DEFAULT_DENSITY))

    private val sim get() = match.sim

    private var paused = false
    private var finished = false
    private var bannerPhase: Phase? = null
    private var bannerPeriod = 0
    private var bannerScorer: Side? = null
    private var bannerScorerId = -1
    private var bannerText: String? = null

    private val _hud = MutableStateFlow(buildHud(sim.clockText(), false))
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _frame = mutableIntStateOf(0)
    /** Incremented every frame while not paused; the Canvas reads it so it redraws. */
    val frame: State<Int> get() = _frame

    /** Frame time in seconds, for animation. */
    var timeS = 0f
        private set

    /** Sim ticks the last [onFrame] ran; for tests. */
    var lastFrameTicks = 0
        private set

    private var lastNanos = NO_TIME
    private var accumulator = 0f
    private var shownSeconds = sim.displaySeconds
    private var clockText = _hud.value.clock

    /** Lays the touch controls out on screen; the renderer keeps the pass arrow clear of them. */
    fun setLayout(layout: ControlsLayout) {
        controls.layout = layout
        renderer.avoidControls(layout)
    }

    /** Tells the renderer where an overlay sits on screen so the board text fades away from it. */
    fun keepBoardTextClear(slot: Int, left: Float, top: Float, right: Float, bottom: Float) {
        renderer.keepBoardTextClear(slot, left, top, right, bottom)
    }

    fun setSoundOn(on: Boolean) { director.soundOn = on }
    fun setVibrationOn(on: Boolean) { director.vibrationOn = on }

    fun pause() {
        if (paused) return
        paused = true
        lastNanos = NO_TIME
        controls.cancelAll()
        publishHud()
    }

    fun resume() {
        if (!paused) return
        paused = false
        lastNanos = NO_TIME
        publishHud()
    }

    fun onFrame(frameTimeNanos: Long) {
        timeS = frameTimeNanos / 1_000_000_000f
        if (!paused) _frame.intValue++   // a paused game does not change, so it is not redrawn
        if (paused || sim.phase == Phase.FINAL) {
            lastNanos = NO_TIME
            lastFrameTicks = 0
            if (sim.phase == Phase.FINAL) finished = true
            publishHud()
            return
        }
        if (lastNanos == NO_TIME) {
            lastNanos = frameTimeNanos
            // A quarter tick of headroom keeps frames off the tick boundary, so timestamp jitter
            // cannot make a 60 Hz frame run 0 ticks and the next run 2.
            accumulator = Tuning.DT * ACCUMULATOR_SEED
            lastFrameTicks = 0
            controls.hasPuck = carriesPuck()
            publishHud()
            return
        }
        val dt = ((frameTimeNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, MAX_GAP_S)
        lastNanos = frameTimeNanos
        accumulator += dt
        controls.hasPuck = carriesPuck()
        var ticks = 0
        while (accumulator >= Tuning.DT && ticks < MAX_TICKS) {
            accumulator -= Tuning.DT
            match.tick(controls.input)
            director.onEvents(sim.events, sim.controlledId)
            renderer.onTick(sim)
            ticks++
            if (sim.phase == Phase.FINAL) { finished = true; break }
        }
        lastFrameTicks = ticks
        if (ticks > 0) renderer.advance(sim, ticks * Tuning.DT)
        if (accumulator > Tuning.DT) accumulator = Tuning.DT   // drop what the tick cap could not run
        controls.hasPuck = carriesPuck()
        publishHud()
    }

    private fun carriesPuck(): Boolean {
        val carrier = sim.puck.carrier
        return carrier != null && carrier.id == sim.controlledId
    }

    private fun publishHud() {
        if (sim.displaySeconds != shownSeconds) {
            shownSeconds = sim.displaySeconds
            clockText = sim.clockText()
        }
        val old = _hud.value
        val label = periodLabel(sim.period)
        val banner = bannerFor(label)
        if (old.homeScore == sim.score[0] && old.awayScore == sim.score[1] && old.periodLabel == label &&
            old.clock == clockText && old.banner == banner && old.hasPuck == controls.hasPuck &&
            old.paused == paused && old.finished == finished
        ) return
        _hud.value = buildHud(clockText, controls.hasPuck)
    }

    private fun buildHud(clock: String, hasPuck: Boolean): HudState {
        val label = periodLabel(sim.period)
        return HudState(
            homeCode = setup.home.code, awayCode = setup.away.code,
            homeScore = sim.score[0], awayScore = sim.score[1],
            periodLabel = label, clock = clock,
            banner = bannerFor(label), goalBanner = sim.phase.afterGoal,
            hasPuck = hasPuck, paused = paused, finished = finished,
        )
    }

    /** Rebuilt only when the phase, period or scorer changes, so no string is made per frame. */
    private fun bannerFor(label: String): String? {
        if (sim.phase != bannerPhase || sim.period != bannerPeriod || sim.lastGoalSide != bannerScorer ||
            sim.lastScorerId != bannerScorerId
        ) {
            bannerPhase = sim.phase
            bannerPeriod = sim.period
            bannerScorer = sim.lastGoalSide
            bannerScorerId = sim.lastScorerId
            bannerText = when (sim.phase) {
                Phase.GOAL, Phase.CELEBRATE -> goalText()
                Phase.BREAK -> "End of $label"
                Phase.FINAL -> "Final"
                else -> null
            }
        }
        return bannerText
    }

    /** "Boldy scores for the Wild!", the scorer's own team even on an own goal; no valid scorer names the side. */
    private fun goalText(): String {
        if (sim.lastGoalSide == null) return "GOAL!"
        val id = sim.lastScorerId
        if (id in sim.skaters.indices) {
            val sk = sim.skaters[id]
            val team = if (sk.side == Side.HOME) setup.home else setup.away
            val name = team.skaters.firstOrNull { it.position == sk.pos }?.name
            if (name != null) return "$name scores for the ${team.nickname}!"
        }
        return when (sim.lastGoalSide) {
            Side.HOME -> "Goal for the ${setup.home.nickname}!"
            Side.AWAY -> "Goal for the ${setup.away.nickname}!"
            null -> "GOAL!"
        }
    }

    private fun periodLabel(period: Int): String = when (period) {
        1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${period}th"
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val MAX_GAP_S = 0.1f
        const val MAX_TICKS = 5
        const val ACCUMULATOR_SEED = 0.25f
        const val DEFAULT_W = 2400f
        const val DEFAULT_H = 1080f
        const val DEFAULT_DENSITY = 2.625f
    }
}
