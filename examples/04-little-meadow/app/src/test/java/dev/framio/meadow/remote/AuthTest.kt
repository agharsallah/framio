package dev.framio.meadow.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private var now = 1_000_000L
    private var pin = "4821"
    private val auth = Auth({ pin }, { now })

    private fun ok(r: Auth.Result) = (r as Auth.Result.Ok).token

    @Test fun `right PIN gives a working token`() {
        val t = ok(auth.login("4821"))
        assertEquals(48, t.length)
        assertTrue(auth.check(t))
        assertFalse(auth.check("nope"))
        assertFalse(auth.check(null))
        assertFalse(auth.check(""))
    }

    @Test fun `tokens are distinct`() {
        assertNotEquals(ok(auth.login("4821")), ok(auth.login("4821")))
    }

    @Test fun `five wrong tries lock for a minute`() {
        for (left in 4 downTo 1) assertEquals(Auth.Result.Wrong(left), auth.login("0000"))
        assertEquals(Auth.Result.Locked(60), auth.login("0000"))
        // even the right PIN waits out the lock
        now += 30_000
        assertEquals(Auth.Result.Locked(30), auth.login("4821"))
        now += 30_000
        assertTrue(auth.login("4821") is Auth.Result.Ok)
    }

    @Test fun `a right PIN resets the count`() {
        repeat(4) { auth.login("1111") }
        ok(auth.login("4821"))
        assertEquals(Auth.Result.Wrong(4), auth.login("1111"))
    }

    @Test fun `after a lock the tries start over`() {
        repeat(5) { auth.login("1111") }
        now += 60_000
        assertEquals(Auth.Result.Wrong(4), auth.login("1111"))
    }

    @Test fun `no PIN set means no way in`() {
        pin = ""
        assertTrue(auth.login("") is Auth.Result.Wrong)
    }

    @Test fun `whitespace around the PIN is fine`() {
        assertTrue(auth.login(" 4821 ") is Auth.Result.Ok)
        assertTrue(auth.login("48 21") is Auth.Result.Wrong)
    }

    @Test fun `logout and expiry`() {
        val t = ok(auth.login("4821"))
        auth.logout(t)
        assertFalse(auth.check(t))
        val u = ok(auth.login("4821"))
        now += Auth.MAX_AGE_MS + 1
        assertFalse(auth.check(u))
    }

    @Test fun `use keeps a session alive`() {
        val t = ok(auth.login("4821"))
        now += Auth.MAX_AGE_MS - 1
        assertTrue(auth.check(t))
        now += Auth.MAX_AGE_MS - 1
        assertTrue(auth.check(t))
    }

    @Test fun `oldest sessions are dropped beyond the cap`() {
        val first = ok(auth.login("4821"))
        repeat(Auth.MAX_SESSIONS) { auth.login("4821") }
        assertFalse(auth.check(first))
    }

    @Test fun `cookie is HttpOnly and SameSite Strict`() {
        val c = auth.cookie("abc")
        assertTrue(c.startsWith("meadow=abc;"))
        assertTrue(c.contains("HttpOnly"))
        assertTrue(c.contains("SameSite=Strict"))
        assertTrue(auth.clearCookie().contains("Max-Age=0"))
    }
}
