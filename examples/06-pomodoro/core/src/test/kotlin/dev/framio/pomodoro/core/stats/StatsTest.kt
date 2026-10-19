package dev.framio.pomodoro.core.stats

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The calendar helpers left here; the reports are tested in history.InsightsReportTest. */
class InsightsTest {
    @Test
    fun `a record's date is its local start date, midnight belongs to the new day`() {
        assertEquals(LocalDate.parse("2026-10-18"), Insights.dateOf(record("2026-10-18T23:59:59"), AMSTERDAM))
        assertEquals(LocalDate.parse("2026-10-19"), Insights.dateOf(record("2026-10-19T00:00"), AMSTERDAM))
        assertEquals(LocalDate.parse("2026-10-18"), Insights.dateOf(record("2026-10-19T00:00"), ZoneId.of("America/New_York")))
    }

    @Test
    fun `a record without a known start has no date`() {
        val r = record("2026-10-18T09:00").let { it.copy(start = it.start.copy(wallMs = null)) }
        assertNull(Insights.dateOf(r, AMSTERDAM))
    }

    @Test
    fun `the today ring's fraction is capped and safe with no target`() {
        assertEquals(1f, TodayProgress(9, 6, kotlin.time.Duration.ZERO).fraction)
        assertEquals(0f, TodayProgress(3, 0, kotlin.time.Duration.ZERO).fraction)
        assertEquals(2f, TaskEstimate(TaskId("t"), "T", 2, 4).ratio)
    }
}
