package dev.framio.pomodoro.core.stats

/** Focus ratings by local starting hour. [mean] is null for hours with too few rated sessions. */
public data class HourCell(val hour: Int, val count: Int, val mean: Float?)

/**
 * Learns your best hours from your own ratings instead of assuming a chronotype (PLAN §3 principle 14). The cells are
 * built by `history.InsightsReport`; nothing is shown before [MIN_RATED] rated sessions, because fewer would suggest
 * patterns that aren't there.
 */
public object HourHeatmap {
    public const val MIN_RATED: Int = 20
}
