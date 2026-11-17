package com.personal.hockey.ai

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.HockeySim
import com.personal.hockey.sim.Phase
import com.personal.hockey.sim.Side
import com.personal.hockey.sim.SkaterInput
import com.personal.hockey.teams.Attributes
import com.personal.hockey.teams.Player
import com.personal.hockey.teams.Teams
import kotlin.random.Random

/** A game: the sim plus a computer brain for each side. The human, if any, drives one HOME skater. */
class Match(
    level: Level,
    seed: Long,
    humanControlled: Boolean = true,
    homeDifficulty: Difficulty = Difficulty.TEAMMATE,
    awayDifficulty: Difficulty = Difficulty.of(level),
    homeSkaters: List<Player> = Teams.standardSkaters(),
    awaySkaters: List<Player> = Teams.standardSkaters(),
) {
    val sim = HockeySim(
        Random(seed),
        // The player's own team gets the level's player goalie; any other home team keeps its own.
        if (homeDifficulty === Difficulty.TEAMMATE) Difficulty.playerGoalie(level) else homeDifficulty.goalie,
        awayDifficulty.goalie,
    )
    private val homeAi = TeamAi(Side.HOME, homeDifficulty, Random(seed * 31 + 1))
    private val awayAi = TeamAi(Side.AWAY, awayDifficulty, Random(seed * 31 + 2))
    /** The skater the human drove last tick, to spot a control switch. */
    private var lastControlled = -1

    init {
        sim.humanControlled = humanControlled
        if (!humanControlled) sim.controlledId = -1
        // The computer's faceoff skill is against the human's side; computer against computer is even.
        sim.faceoffWinAway = if (humanControlled) awayDifficulty.faceoffWin else 0.5f
        applyPlayers(Side.HOME, homeDifficulty, homeSkaters)
        applyPlayers(Side.AWAY, awayDifficulty, awaySkaters)
    }

    private fun applyPlayers(side: Side, difficulty: Difficulty, players: List<Player>) {
        for (s in sim.skatersOf(side)) {
            // A roster without this position plays a standard skater there.
            val attrs = players.firstOrNull { it.position == s.pos }?.attributes ?: STANDARD
            s.speedScale = difficulty.speedScale * attrs.speed
            s.shotScale = attrs.shot
        }
    }

    private companion object {
        val STANDARD = Attributes()
    }

    /**
     * One tick. Copies [human] into the controlled skater (if any), runs both AIs, steps the sim.
     * On a control switch the human's held buttons count as already down on the new skater, so only
     * a fresh press acts.
     */
    fun tick(human: SkaterInput?) {
        val id = if (sim.humanControlled) sim.controlledId else -1
        if (id >= 0) {
            val s = sim.skaters[id]
            val inp = s.input
            if (human == null) inp.clear() else {
                inp.moveX = human.moveX; inp.moveY = human.moveY
                inp.shoot = human.shoot; inp.pass = human.pass; inp.deke = human.deke
                inp.poke = human.poke; inp.body = human.body
            }
            if (id != lastControlled) {
                val p = s.prev
                p.shoot = inp.shoot; p.pass = inp.pass; p.deke = inp.deke; p.poke = inp.poke; p.body = inp.body
            }
        }
        lastControlled = id
        if (sim.phase == Phase.CELEBRATE || sim.phase == Phase.WHISTLE) {
            // The celebration and the whistle are stoppages: the AI rests (no random draws, no timers)
            // and nobody is steered.
            val sk = sim.skaters
            for (i in sk.indices) if (sk[i].id != id) sk[i].input.clear()
        } else {
            homeAi.think(sim, id)
            awayAi.think(sim, -1)
        }
        sim.step()
    }
}
