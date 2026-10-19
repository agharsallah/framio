package dev.framio.meadow

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTest {

    @Test fun backNeverLeavesHome() {
        val n = Nav()
        n.back()
        assertEquals(Route.Home, n.current)
    }

    @Test fun openPlayPutsItsWorldUnderneath() {
        val n = Nav()
        n.openPlay("numbers", "count")
        assertEquals(Route.Play("count"), n.current)
        n.back()
        assertEquals(Route.World("numbers"), n.current)
    }

    @Test fun goingToAScreenAlreadyOnTheStackMovesItToTheTop() {
        val n = Nav()
        n.go(Route.Stickers)
        n.go(Route.World("colors"))
        n.go(Route.Stickers)
        assertEquals(listOf(Route.Home, Route.World("colors"), Route.Stickers), n.stack)
    }
}
