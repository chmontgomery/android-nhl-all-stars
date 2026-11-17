package com.personal.hockey.sim

/** Something that happened during a step, for sound, haptics and the HUD. Later tasks add cases. */
sealed interface SimEvent {
    /** The loose puck hit the boards hard enough to hear. */
    data object Boards : SimEvent

    /** Skater [skaterId] of [side] took possession of the puck. */
    data class Pickup(val side: Side, val skaterId: Int) : SimEvent

    /** Skater [shooterId] of [side] shot; [power] 0 (tap) .. 1 (full wind-up). */
    data class Shot(val side: Side, val power: Float, val shooterId: Int) : SimEvent

    /** Skater [passerId] (-1: a goalie's kick) of [side] passed to [targetId] (-1: nobody). */
    data class Pass(val side: Side, val passerId: Int, val targetId: Int) : SimEvent

    /** A poke check was thrown; [hit] if it knocked the puck. */
    data class Poke(val hit: Boolean) : SimEvent

    /** Skater [checkerId]'s body check knocked down skater [victimId]. */
    data class Hit(val checkerId: Int, val victimId: Int) : SimEvent

    /** The goalie of [side] saved. */
    data class Save(val side: Side) : SimEvent

    /** A goalie caught (covered) the puck after a save. */
    data object Cover : SimEvent

    /** Play stopped for a caught puck; the faceoff is in the zone [zoneSide] defends. */
    data class Whistle(val zoneSide: Side) : SimEvent

    /** Skater [scorerId] of [scorer] scored (own goals count for the other side). */
    data class Goal(val scorer: Side, val scorerId: Int) : SimEvent

    /** The faceoff puck was dropped; play is live. */
    data object FaceoffDrop : SimEvent

    /** The clock ran out in [period]. */
    data class PeriodEnd(val period: Int) : SimEvent

    /** The last period ended. */
    data object GameEnd : SimEvent
}
