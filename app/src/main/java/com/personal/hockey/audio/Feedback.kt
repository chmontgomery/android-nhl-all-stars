package com.personal.hockey.audio

import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent

enum class Buzz { LIGHT, STRONG, HUGE }

/** Where sounds and vibrations go. Android implementation: [AndroidFeedback]. */
interface GameFeedback {
    fun play(sfx: Sfx)
    fun vibrate(buzz: Buzz)
}

/**
 * Turns sim events into sounds and buzzes. At most one of each sound and buzz per call. The
 * referee's whistle goes when play stops for a goalie's catch; a drop that follows no such stoppage
 * (game start, a period, after a goal) whistles at the drop instead. The human
 * plays for HOME: only a HOME goal cheers and buzzes, and only a hit by or on [controlledId] buzzes.
 */
class FeedbackDirector(private val out: GameFeedback) {
    var soundOn = true
    var vibrationOn = true

    private var soundMask = 0
    private var buzzMask = 0
    private var whistled = false   // a catch's Whistle has gone; its FaceoffDrop stays silent

    fun onEvents(events: List<SimEvent>, controlledId: Int = -1) {
        if (events.isEmpty()) return
        soundMask = 0; buzzMask = 0
        for (i in events.indices) when (val e = events[i]) {
            is SimEvent.Shot -> sound(Sfx.SHOT)
            is SimEvent.Pass -> sound(Sfx.PASS)
            is SimEvent.Pickup -> sound(Sfx.STICK)
            is SimEvent.Poke -> if (e.hit) sound(Sfx.STICK)
            SimEvent.Boards -> sound(Sfx.BOARDS)
            is SimEvent.Save -> sound(Sfx.SAVE)
            is SimEvent.Hit -> {
                sound(Sfx.HIT)
                if (controlledId >= 0 && (e.checkerId == controlledId || e.victimId == controlledId)) buzz(Buzz.STRONG)
            }
            is SimEvent.Whistle -> { sound(Sfx.WHISTLE); whistled = true }
            SimEvent.FaceoffDrop -> { if (!whistled) sound(Sfx.WHISTLE); whistled = false }
            is SimEvent.PeriodEnd -> { sound(Sfx.BUZZER); whistled = false }
            is SimEvent.Goal -> {
                sound(Sfx.HORN)
                if (e.scorer == Side.HOME) { sound(Sfx.CHEER); buzz(Buzz.HUGE) }
            }
            else -> {}
        }
    }

    private fun sound(sfx: Sfx) {
        val bit = 1 shl sfx.ordinal
        if (soundMask and bit != 0) return
        soundMask = soundMask or bit
        if (soundOn) out.play(sfx)
    }

    private fun buzz(buzz: Buzz) {
        val bit = 1 shl buzz.ordinal
        if (buzzMask and bit != 0) return
        buzzMask = buzzMask or bit
        if (vibrationOn) out.vibrate(buzz)
    }
}
