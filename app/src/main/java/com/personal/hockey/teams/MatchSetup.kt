package com.personal.hockey.teams

import com.personal.hockey.settings.Level

/** Everything one game needs to know about who is playing. Built in one place so kit choice can grow later. */
data class MatchSetup(val home: Team, val away: Team, val homeKit: Kit, val awayKit: Kit, val level: Level) {
    companion object {
        fun of(home: Team, away: Team, level: Level): MatchSetup =
            MatchSetup(home, away, home.darkKit, away.whiteKit, level)
    }
}
