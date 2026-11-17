package com.personal.hockey.teams

import com.personal.hockey.settings.Level
import com.personal.hockey.sim.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsTest {
    @Test fun catalogIsInOrder() {
        assertEquals(listOf("MIN", "COL", "EDM", "TOR"), Teams.all.map { it.id })
    }

    @Test fun every_skater_has_a_name() {
        for (t in Teams.all) {
            assertTrue("${t.id} nickname", t.nickname.isNotBlank())
            for (p in t.skaters) assertTrue("${t.id} #${p.number}", !p.name.isNullOrBlank())
        }
    }

    @Test fun idsAreUnique() {
        assertEquals(Teams.all.size, Teams.all.map { it.id }.toSet().size)
    }

    @Test fun byIdRoundTripsAndUnknownIsNull() {
        assertEquals("TOR", Teams.byId("TOR")?.id)
        assertNull(Teams.byId("XXX"))
    }

    @Test fun nextWraps() {
        assertEquals("MIN", Teams.next("TOR").id)
        assertEquals("COL", Teams.next("MIN").id)
    }

    @Test fun everyTeamIsWellFormed() {
        for (team in Teams.all) {
            assertTrue(team.id, team.kits.size >= 2)
            assertEquals(team.kits.size, team.kits.map { it.id }.toSet().size)
            assertEquals(0xFFFFFFFF, team.whiteKit.jersey)
            assertSame(team.kits[0], team.darkKit)
            assertSame(team.kits[1], team.whiteKit)
            assertEquals(Position.entries.toList(), team.skaters.map { it.position })
        }
    }

    /** Each team's own numbers, in Position order (C, LW, RW, LD, RD), and the goalie's. */
    @Test fun teamsWearTheirOwnNumbers() {
        val expected = mapOf(
            "MIN" to (listOf(14, 97, 12, 43, 7) to 32),
            "COL" to (listOf(29, 62, 88, 7, 8) to 41),
            "EDM" to (listOf(97, 29, 18, 14, 2) to 35),
            "TOR" to (listOf(34, 23, 88, 44, 8) to 41),
        )
        for (team in Teams.all) {
            val (skaters, goalie) = expected.getValue(team.id)
            assertEquals(team.id, Position.entries.toList(), team.skaters.map { it.position })
            assertEquals(team.id, skaters, team.skaters.map { it.number })
            assertEquals(team.id, goalie, team.goalieNumber)
            assertTrue(team.id, team.skaters.all { !it.name.isNullOrBlank() })
        }
    }

    @Test fun standardKitsFollowTheRecipe() {
        val (dark, white) = Teams.standardKits(0xFF111111, 0xFF222222)
        assertEquals(0xFF111111, dark.jersey)
        assertEquals(0xFF111111, dark.helmet)
        assertEquals(0xFF111111, dark.pants)
        assertEquals(0xFF222222, dark.trim)
        assertEquals(0xFFFFFFFF, dark.number)
        assertEquals(0xFFFFFFFF, white.jersey)
        assertEquals(0xFF111111, white.trim)
        assertEquals(0xFF111111, white.pants)
        assertEquals(0xFF111111, white.helmet)
        assertEquals(0xFF111111, white.number)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRosterWithTwoPlayersAtOnePositionIsRejected() {
        val roster = Teams.standardSkaters().map { if (it.position == Position.LW) it.copy(position = Position.C) else it }
        Teams.all[0].copy(skaters = roster)
    }

    @Test fun aRosterMissingAPositionIsAllowed() {
        Teams.all[0].copy(skaters = Teams.standardSkaters().filter { it.position != Position.RD })
    }

    /** Only Teams.kt may name a team: no other main source holds a team's id, code or name in quotes. */
    @Test fun onlyTeamsKtNamesATeam() {
        val root = java.io.File("src/main/java")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val names = Teams.all.flatMap { listOf(it.id, it.code, it.name) }.toSet()
        val offenders = root.walk().filter { it.isFile && it.extension == "kt" && it.name != "Teams.kt" }
            .flatMap { f -> names.filter { n -> f.readText().contains("\"$n\"") }.map { n -> "${f.name}: $n" } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun matchSetupUsesHomeDarkAndAwayWhite() {
        val edm = Teams.byId("EDM")!!
        val tor = Teams.byId("TOR")!!
        val setup = MatchSetup.of(edm, tor, Level.HARD)
        assertSame(edm.darkKit, setup.homeKit)
        assertSame(tor.whiteKit, setup.awayKit)
        assertEquals(Level.HARD, setup.level)
    }
}
