package dev.framio.lab.core

import kotlin.coroutines.cancellation.CancellationException

/** How a probe turned out. */
enum class Status {
    /** The hardware or API works. */
    PASS,

    /** Works, with a caveat worth knowing (e.g. software-only codec, 2.4 GHz only). */
    WARN,

    /** Should work but doesn't. */
    FAIL,

    /** Not on this device (Android may still claim it). */
    ABSENT,

    /** A reading, nothing to judge. */
    INFO,

    /** Needs a person: listen, clap, press a button. */
    MANUAL,
}

/** One self-test result. [lab] is the [Lab.id] it came from. */
data class Check(
    val lab: String,
    val name: String,
    val status: Status,
    val detail: String,
)

/** Collects checks for one lab, so probes read like a list: `pass("Speaker", "…")`. */
class CheckList(private val lab: String) {
    val items = mutableListOf<Check>()

    fun add(name: String, status: Status, detail: String) {
        items += Check(lab, name, status, detail)
    }

    fun pass(name: String, detail: String) = add(name, Status.PASS, detail)
    fun warn(name: String, detail: String) = add(name, Status.WARN, detail)
    fun fail(name: String, detail: String) = add(name, Status.FAIL, detail)
    fun absent(name: String, detail: String) = add(name, Status.ABSENT, detail)
    fun info(name: String, detail: String) = add(name, Status.INFO, detail)
    fun manual(name: String, detail: String) = add(name, Status.MANUAL, detail)

    /**
     * Runs [block]; an exception becomes a FAIL instead of ending the whole self-test.
     * LinkageError too: an API missing on Android 6 (NoSuchMethodError) must not crash the app.
     */
    inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // a lab timeout, not a failed check
        } catch (e: Exception) {
            fail(name, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: LinkageError) {
            fail(name, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
