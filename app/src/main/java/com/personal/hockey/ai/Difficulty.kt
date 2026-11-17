package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.GoalieSkill

/**
 * How well a computer-driven team plays. No field makes [EASY] play better than [MEDIUM], or [MEDIUM] better than [HARD]; Easy's larger
 * [shootRange] is deliberate, and weaker play: it shoots from further out, where the goalie saves more.
 */
data class Difficulty(
    /** Multiplies every skater's top speed. */
    val speedScale: Float,
    /** Seconds between decisions, and how stale the puck position a skater reacts to is. */
    val reactionS: Float,
    /** Chance rate (per second) of attempting a poke check when in range of the carrier. */
    val pokePerSecond: Float,
    /** Chance rate (per second) of attempting a body check when in range of the carrier. */
    val hitPerSecond: Float,
    /** 0 = acts only when it will land, 1 = acts at random moments in range. */
    val timingError: Float,
    /** 0..1 chance, per decision, to pass when a checker is within 2.5 m. */
    val passUnderPressure: Float,
    /** Seconds of wind-up used on a shot. */
    val shotHold: Float,
    /** Shoots for the corner away from the goalie; otherwise at the goalie's side. */
    val aimsForCorners: Boolean,
    /** Metres from the net at which the carrier shoots. */
    val shootRange: Float,
    val goalie: GoalieSkill,
    /** 0..1, the chance its centre wins a faceoff draw against the human's side. */
    val faceoffWin: Float = 0.5f,
) {
    /** Only players with sharp timing (Hard and the human's teammates) deke. */
    val dekes: Boolean get() = timingError < 0.5f

    companion object {
        // Tuned against scripted players in BalanceTest. EASY sits inside the design's Easy column
        // (slow, late, rarely checks, weak shots at the goalie, a weak goalie) so a real 6-year-old
        // wins; it shoots from long range so it still gets the odd shot away. HARD beats a
        // button-mashing kid and gives a skilled player an even game.
        // The 0.65 catch / 0.25 kick splits are identical at every level on purpose: they stay tuning
        // knobs, left equal because balance is carried by faceoffWin.
        val EASY = Difficulty(0.75f, 0.5f, 0.3f, 0.1f, 0.9f, 0.25f, 0.25f, false, 23f, GoalieSkill(0.3f, 3.5f, 0.4f, catch = 0.65f, kick = 0.25f), faceoffWin = 0.35f)
        // MEDIUM, for a kid who skates and shoots but rarely passes or dekes: it skates, reads the play
        // and shoots like Hard, but checks far less often, shoots at the goalie rather than the
        // corners, and has a softer goalie, so the kid usually wins a close-ish game and has to work
        // to keep the puck. A relentless kid's results swing hard on speed and reaction; slowing
        // either even slightly hands the kid lopsided wins.
        // Goalie saveBase 0.53 -> 0.52 for arc positioning (2026-11-17).
        val MEDIUM = Difficulty(1.0f, 0.13f, 0.45f, 0.6f, 0.15f, 0.8f, 0.6f, false, 14f, GoalieSkill(0.15f, 5.5f, 0.52f, catch = 0.65f, kick = 0.25f), faceoffWin = 0.8f)
        // Goalie reactionS 0.08 -> 0.05 for arc positioning (2026-11-17): a quicker read of the puck holds
        // off the relentless kid without making the skilled player's game one-sided.
        val HARD = Difficulty(1.0f, 0.13f, 2.5f, 1.0f, 0.15f, 0.8f, 0.6f, true, 14f, GoalieSkill(0.05f, 6.5f, 0.62f, catch = 0.65f, kick = 0.25f), faceoffWin = 0.85f)
        // Goalie saveBase 0.72 -> 0.75 for arc positioning (2026-11-17).
        /** The player's teammates, at every level, and the player's goalie apart from its save outcomes: see [playerGoalie]. */
        val TEAMMATE = Difficulty(1.0f, 0.15f, 0.6f, 0.2f, 0.3f, 0.8f, 0.6f, true, 14f, GoalieSkill(0.08f, 6.5f, 0.75f))

        fun of(level: Level): Difficulty = when (level) {
            Level.EASY -> EASY
            Level.MEDIUM -> MEDIUM
            Level.HARD -> HARD
        }

        /** The player's goalie at [level]: [TEAMMATE]'s, with that level's catch / kick split. */
        fun playerGoalie(level: Level): GoalieSkill = when (level) {
            Level.EASY -> PLAYER_GOALIE_EASY
            Level.MEDIUM -> PLAYER_GOALIE_MEDIUM
            Level.HARD -> PLAYER_GOALIE_HARD
        }

        private val PLAYER_GOALIE_EASY = TEAMMATE.goalie.copy(catch = 0.65f, kick = 0.25f)
        private val PLAYER_GOALIE_MEDIUM = TEAMMATE.goalie.copy(catch = 0.65f, kick = 0.25f)
        private val PLAYER_GOALIE_HARD = TEAMMATE.goalie.copy(catch = 0.65f, kick = 0.25f)
    }
}
