package dev.framio.meadow

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    data object Home : Route
    data class World(val id: String) : Route
    data class Play(val id: String) : Route
    data object Stickers : Route
    data object Mailbox : Route
    data object Parent : Route
}

/** A tiny back stack. Home is always at the bottom. */
@Stable
class Nav {
    var stack by mutableStateOf(listOf<Route>(Route.Home))
        private set

    val current: Route get() = stack.last()

    fun go(r: Route) {
        if (current != r) stack = stack.filterNot { it == r } + r
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun home() {
        stack = listOf(Route.Home)
    }

    /** Opens an activity with its world underneath, so "back" lands in the world. */
    fun openPlay(worldId: String, playId: String) {
        stack = listOf(Route.Home, Route.World(worldId), Route.Play(playId))
    }
}
