package com.personal.hockey.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.personal.hockey.settings.Level

sealed interface Screen {
    data object TeamSelect : Screen
    data class Game(val homeId: String, val awayId: String, val level: Level) : Screen
    data class Result(
        val homeId: String, val awayId: String, val level: Level, val homeScore: Int, val awayScore: Int,
    ) : Screen
}

/** A screen on the back stack. [id] is unique per visit; the entry owns that visit's view models. */
class Entry(val id: Int, val screen: Screen) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

class Navigator(start: Screen = Screen.TeamSelect) {
    private var nextId = 0
    private val stack = mutableStateListOf(Entry(nextId++, start))

    val current: Entry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) { stack.add(Entry(nextId++, screen)) }

    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex).viewModelStore.clear()
        return true
    }

    fun replace(screen: Screen) {
        val old = stack.set(stack.lastIndex, Entry(nextId++, screen))
        old.viewModelStore.clear()
    }

    fun reset(screen: Screen = Screen.TeamSelect) {
        clear()
        stack.clear()
        stack.add(Entry(nextId++, screen))
    }

    /** Clears every entry's view models (the stack itself is left as is). */
    fun clear() { stack.forEach { it.viewModelStore.clear() } }
}

/** Keeps the back stack (and each entry's view models) across activity recreation. */
class NavViewModel : ViewModel() {
    val navigator = Navigator()
    override fun onCleared() { navigator.clear() }
}
