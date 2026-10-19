package dev.framio.hub.core.model

/**
 * The colours people and timers can have, as ARGB. Same names and values as the launcher and the Lab,
 * so the examples look like one family. Always shown next to a name or initials, never as the only cue.
 */
object Palette {
    const val TEAL = 0xFF5BC6B4.toInt()
    const val CORAL = 0xFFE5735C.toInt()
    const val SKY = 0xFF6AA8E8.toInt()
    const val VIOLET = 0xFFA38BE8.toInt()
    const val LIME = 0xFFA8CF5A.toInt()
    const val ROSE = 0xFFE07AA6.toInt()

    val colors = intArrayOf(TEAL, CORAL, SKY, VIOLET, LIME, ROSE)

    fun argb(index: Int): Int = colors[index.mod(colors.size)]

    /** The first index not in [taken], or the least used one when all are. */
    fun free(taken: Collection<Int>, size: Int = colors.size): Int =
        (0 until size).firstOrNull { it !in taken } ?: (0 until size).minBy { i -> taken.count { it == i } }
}
