package com.personal.hockey.audio

import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SimEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Recorder : GameFeedback {
    val sounds = mutableListOf<Sfx>()
    val buzzes = mutableListOf<Buzz>()
    override fun play(sfx: Sfx) { sounds += sfx }
    override fun vibrate(buzz: Buzz) { buzzes += buzz }
}

class FeedbackDirectorTest {
    private val r = Recorder()
    private val d = FeedbackDirector(r)

    @Test fun goal_plays_horn_and_cheer_and_big_buzz() {
        d.onEvents(listOf(SimEvent.Goal(Side.HOME, 0)))
        assertEquals(setOf(Sfx.HORN, Sfx.CHEER), r.sounds.toSet())
        assertEquals(listOf(Buzz.HUGE), r.buzzes)
    }

    @Test fun opponent_goal_plays_only_the_horn() {
        d.onEvents(listOf(SimEvent.Goal(Side.AWAY, 5)))
        assertEquals(listOf(Sfx.HORN), r.sounds)
        assertTrue(r.buzzes.isEmpty())
    }

    @Test fun a_hit_by_the_controlled_skater_plays_hit_and_buzzes() {
        d.onEvents(listOf(SimEvent.Hit(3, 7)), controlledId = 3)
        assertEquals(listOf(Sfx.HIT), r.sounds)
        assertEquals(listOf(Buzz.STRONG), r.buzzes)
    }

    @Test fun a_hit_on_the_controlled_skater_buzzes() {
        d.onEvents(listOf(SimEvent.Hit(7, 3)), controlledId = 3)
        assertEquals(listOf(Buzz.STRONG), r.buzzes)
    }

    @Test fun a_hit_elsewhere_plays_the_sound_without_a_buzz() {
        d.onEvents(listOf(SimEvent.Hit(2, 8)), controlledId = 3)
        assertEquals(listOf(Sfx.HIT), r.sounds)
        assertTrue(r.buzzes.isEmpty())
    }

    @Test fun a_drop_after_a_catch_does_not_whistle_twice_but_other_drops_do() {
        d.onEvents(listOf(SimEvent.Whistle(Side.AWAY)))
        d.onEvents(listOf(SimEvent.FaceoffDrop))
        assertEquals(listOf(Sfx.WHISTLE), r.sounds)
        d.onEvents(listOf(SimEvent.FaceoffDrop))   // game start, period, after a goal
        assertEquals(listOf(Sfx.WHISTLE, Sfx.WHISTLE), r.sounds)
    }

    @Test fun a_catch_on_the_final_tick_does_not_silence_the_next_periods_drop() {
        d.onEvents(listOf(SimEvent.Whistle(Side.AWAY), SimEvent.PeriodEnd(1)))
        d.onEvents(listOf(SimEvent.FaceoffDrop))
        assertEquals(listOf(Sfx.WHISTLE, Sfx.BUZZER, Sfx.WHISTLE), r.sounds)
    }

    @Test fun events_map_to_sounds() {
        d.onEvents(listOf(
            SimEvent.Shot(Side.HOME, 1f, 0), SimEvent.Pass(Side.HOME, 0, 1), SimEvent.Pickup(Side.AWAY, 5),
            SimEvent.Boards, SimEvent.Save(Side.AWAY), SimEvent.Whistle(Side.AWAY), SimEvent.PeriodEnd(1),
            SimEvent.Poke(false), SimEvent.Cover, SimEvent.GameEnd,
        ))
        assertEquals(
            listOf(Sfx.SHOT, Sfx.PASS, Sfx.STICK, Sfx.BOARDS, Sfx.SAVE, Sfx.WHISTLE, Sfx.BUZZER),
            r.sounds,
        )
        assertTrue(r.buzzes.isEmpty())
        d.onEvents(listOf(SimEvent.Poke(true)))
        assertEquals(Sfx.STICK, r.sounds.last())
    }

    /** As in a real game: the referee whistles the stoppage; the linesman drops the puck in silence. */
    @Test fun the_whistle_blows_at_the_stoppage_not_the_drop() {
        d.onEvents(listOf(SimEvent.Cover, SimEvent.Whistle(Side.HOME)))
        assertEquals(listOf(Sfx.WHISTLE), r.sounds)
        d.onEvents(listOf(SimEvent.FaceoffDrop))
        assertEquals(listOf(Sfx.WHISTLE), r.sounds)
    }

    @Test fun sound_off_plays_nothing_but_still_vibrates() {
        d.soundOn = false
        d.onEvents(listOf(SimEvent.Goal(Side.HOME, 0), SimEvent.Shot(Side.HOME, 1f, 0)))
        assertTrue(r.sounds.isEmpty())
        assertEquals(listOf(Buzz.HUGE), r.buzzes)
    }

    @Test fun vibration_off_never_vibrates() {
        d.vibrationOn = false
        d.onEvents(listOf(SimEvent.Goal(Side.HOME, 0), SimEvent.Hit(1, 6)), controlledId = 1)
        assertTrue(r.buzzes.isEmpty())
        assertTrue(r.sounds.isNotEmpty())
    }

    @Test fun duplicate_events_in_one_tick_play_once() {
        d.onEvents(listOf(SimEvent.Hit(1, 6), SimEvent.Hit(7, 1), SimEvent.Pass(Side.HOME, 0, 1), SimEvent.Pass(Side.AWAY, 5, 6)), controlledId = 1)
        assertEquals(listOf(Sfx.HIT, Sfx.PASS), r.sounds)
        assertEquals(listOf(Buzz.STRONG), r.buzzes)
    }

    @Test fun a_later_call_plays_again() {
        d.onEvents(listOf(SimEvent.Boards))
        d.onEvents(listOf(SimEvent.Boards))
        assertEquals(2, r.sounds.size)
    }
}
