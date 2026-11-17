package com.personal.hockey.teams

import com.personal.hockey.sim.Position

/** Colours are 0xAARRGGBB. Adding a team, kit or named player is a data-only change in this file. */
enum class Crest { PINE, PEAK, DROP, LEAF }

data class Kit(
    val id: String,
    val label: String,
    val jersey: Long,
    val trim: Long,
    val pants: Long,
    val helmet: Long,
    val number: Long,
)

/** Multipliers on the standard skater; 1 = standard. v1 uses 1 everywhere. */
data class Attributes(val speed: Float = 1f, val shot: Float = 1f)

data class Player(
    val number: Int,
    val position: Position,
    val name: String? = null,
    val attributes: Attributes = Attributes(),
)

data class Team(
    val id: String,
    val name: String,
    /** The short team name used in announcements: "Wild". */
    val nickname: String,
    val code: String,
    val primary: Long,
    val secondary: Long,
    val trim: Long,
    val crest: Crest,
    /** [0] = dark, [1] = white; alternates are appended later. */
    val kits: List<Kit>,
    /** One per [Position], in Position order; a missing position plays a standard skater. */
    val skaters: List<Player>,
    val goalieNumber: Int = 30,
) {
    init {
        require(skaters.map { it.position }.toSet().size == skaters.size) { "$id has two skaters at one position" }
    }

    val darkKit: Kit get() = kits[0]
    val whiteKit: Kit get() = kits[1]
}

object Teams {
    private const val WHITE = 0xFFFFFFFF

    /** [numbers] and [names] are the skaters' in Position order: C, LW, RW, LD, RD. */
    private fun team(
        code: String, name: String, nickname: String, primary: Long, secondary: Long, trim: Long, crest: Crest,
        numbers: IntArray, names: List<String>, goalie: Int,
    ) = Team(
        id = code, name = name, nickname = nickname, code = code,
        primary = primary, secondary = secondary, trim = trim, crest = crest,
        kits = standardKits(primary, secondary),
        skaters = Position.entries.map { Player(numbers[it.ordinal], it, names[it.ordinal]) },
        goalieNumber = goalie,
    )

    /** The single place a team is added. */
    val all: List<Team> = listOf(
        team("MIN", "Minnesota Wild", "Wild", 0xFF154734, 0xFFA6192E, 0xFFDDCBA4, Crest.PINE, intArrayOf(14, 97, 12, 43, 7),
            listOf("Eriksson Ek", "Kaprizov", "Boldy", "Hughes", "Faber"), 32),
        team("COL", "Colorado Avalanche", "Avalanche", 0xFF6F263D, 0xFF236192, 0xFFA2AAAD, Crest.PEAK, intArrayOf(29, 62, 88, 7, 8),
            listOf("MacKinnon", "Lehkonen", "Necas", "Toews", "Makar"), 41),
        team("EDM", "Edmonton Oilers", "Oilers", 0xFF041E42, 0xFFFF4C00, 0xFFFFFFFF, Crest.DROP, intArrayOf(97, 29, 18, 14, 2),
            listOf("McDavid", "Draisaitl", "Hyman", "Ekholm", "Bouchard"), 35),
        team("TOR", "Toronto Maple Leafs", "Maple Leafs", 0xFF00205B, 0xFFFFFFFF, 0xFF8FB8E8, Crest.LEAF, intArrayOf(34, 23, 88, 44, 8),
            listOf("Matthews", "Knies", "Nylander", "Rielly", "Tanev"), 41),
    )

    fun byId(id: String): Team? = all.firstOrNull { it.id == id }

    /** The team after [id] in [all], wrapping. An unknown id yields the first team. */
    fun next(id: String): Team {
        val i = all.indexOfFirst { it.id == id }
        return all[(i + 1) % all.size]
    }

    fun standardKits(primary: Long, secondary: Long): List<Kit> = listOf(
        Kit("dark", "Dark", jersey = primary, trim = secondary, pants = primary, helmet = primary, number = WHITE),
        Kit("white", "White", jersey = WHITE, trim = primary, pants = primary, helmet = primary, number = primary),
    )

    fun standardSkaters(): List<Player> = listOf(
        Player(9, Position.C),
        Player(11, Position.LW),
        Player(16, Position.RW),
        Player(4, Position.LD),
        Player(7, Position.RD),
    )
}
