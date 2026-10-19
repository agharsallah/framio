package dev.framio.meadow.remote

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The dashboard's door: the 4-digit PIN shown on the frame's grown-ups screen buys a random session
 * token (kept in memory, sent back as an HttpOnly, SameSite=Strict cookie). Five wrong PINs in a
 * row lock the door for a minute, for everyone: 10 000 PINs at 5 a minute is weeks of guessing.
 * Pure, unit-tested (inject the clock).
 */
class Auth(
    private val pin: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    sealed interface Result {
        data class Ok(val token: String) : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val secondsLeft: Int) : Result
    }

    private val sessions = LinkedHashMap<String, Long>() // token → last used
    private var wrong = 0
    private var lockedUntil = 0L

    @Synchronized fun login(attempt: String): Result {
        val t = now()
        if (t < lockedUntil) return Result.Locked(((lockedUntil - t + 999) / 1000).toInt())
        val expected = pin()
        if (expected.length == 4 && same(attempt.trim(), expected)) {
            wrong = 0
            val token = newToken()
            sessions[token] = t
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
            return Result.Ok(token)
        }
        wrong++
        if (wrong >= MAX_TRIES) {
            wrong = 0
            lockedUntil = t + LOCK_MS
            return Result.Locked((LOCK_MS / 1000).toInt())
        }
        return Result.Wrong(MAX_TRIES - wrong)
    }

    /** True for a live session; refreshes its age. */
    @Synchronized fun check(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val last = sessions[token] ?: return false
        val t = now()
        if (t - last > MAX_AGE_MS) {
            sessions.remove(token)
            return false
        }
        sessions[token] = t
        return true
    }

    @Synchronized fun logout(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun cookie(token: String): String = "$COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=${MAX_AGE_MS / 1000}"

    fun clearCookie(): String = "$COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    private fun newToken(): String {
        val b = ByteArray(24)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Constant time, so the response time says nothing about how many digits were right. */
    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val COOKIE = "meadow"
        const val MAX_TRIES = 5
        const val LOCK_MS = 60_000L
        const val MAX_SESSIONS = 32
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    }
}
