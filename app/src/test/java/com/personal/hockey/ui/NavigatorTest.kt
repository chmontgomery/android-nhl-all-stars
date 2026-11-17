package com.personal.hockey.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.hockey.settings.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val game = Screen.Game("MIN", "COL", Level.EASY)

    @Test fun pushPopReplaceReset() {
        val nav = Navigator()
        assertEquals(Screen.TeamSelect, nav.current.screen)
        assertFalse(nav.canGoBack)
        assertFalse(nav.pop())
        nav.push(game)
        val first = nav.current
        nav.replace(Screen.Result("MIN", "COL", Level.EASY, 3, 1))
        assertEquals(Screen.Result("MIN", "COL", Level.EASY, 3, 1), nav.current.screen)
        assertNotEquals(first.id, nav.current.id)
        assertTrue(nav.pop())
        assertEquals(Screen.TeamSelect, nav.current.screen)
        nav.push(game)
        nav.reset()
        assertEquals(Screen.TeamSelect, nav.current.screen)
        assertFalse(nav.canGoBack)
    }

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private fun probe(entry: Entry): Probe =
        ViewModelProvider(entry, viewModelFactory { initializer { Probe() } })[Probe::class.java]

    @Test fun viewModelsAreClearedWhenTheirEntryLeavesTheStack() {
        val nav = Navigator()
        val select = probe(nav.current)
        nav.push(game)
        val first = probe(nav.current)
        nav.replace(game)
        assertTrue(first.cleared)
        val second = probe(nav.current)
        nav.push(Screen.Result("MIN", "COL", Level.EASY, 0, 0))
        val result = probe(nav.current)
        nav.pop()
        assertTrue(result.cleared)
        assertFalse(second.cleared)
        nav.reset()
        assertTrue(second.cleared)
        assertTrue(select.cleared)
    }
}
