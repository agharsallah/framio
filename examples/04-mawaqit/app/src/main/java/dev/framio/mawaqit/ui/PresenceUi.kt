package dev.framio.mawaqit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.PresenceSettings
import dev.framio.mawaqit.core.PresenceText
import dev.framio.mawaqit.core.presence
import dev.framio.mawaqit.engine.DisplayState
import org.json.JSONObject
import java.time.Duration

/** Whether the "adhan soon" notice is up ([PreAdhanNotice]). */
fun preAdhanNotice(st: DisplayState): Boolean = st.phase.kind == PhaseKind.REMINDER && st.phase.prayer != null

/**
 * The "adhan soon" notice: during the reminder phase before an adhan ([dev.framio.mawaqit.core.AdhanSettings.reminderMinutes]),
 * "Dhuhr in 20 min · Adhan 13:16" in the top bar, where the mosque's name is the rest of the time.
 */
@Composable
fun PreAdhanNotice(st: DisplayState, modifier: Modifier = Modifier) {
    val prayer = st.phase.prayer?.takeIf { st.phase.kind == PhaseKind.REMINDER } ?: return
    val p = LocalPalette.current
    val s = st.strings
    val left = Duration.between(st.now, prayer.adhan).seconds.coerceAtLeast(0)
    val minutes = ((left + 59) / 60).toInt().coerceAtLeast(1)
    val text = s.inPrefix(prayer.key) + " " + PresenceText.minutes(s.lang, minutes)
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val roomy = maxWidth >= 560.dp // standing, the top bar has no room for the adhan's time
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(p.accent.copy(alpha = if (p.light) 0.16f else 0.2f))
                .border(2.dp, p.accent, RoundedCornerShape(50)).padding(start = 16.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BellGlyph(p.accent, Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            val style = Base.copy(color = p.accent, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            BasicText(text, style = if (s.rtl) style.arabic().copy(fontSize = 29.sp) else style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (roomy) {
                Spacer(Modifier.width(14.dp))
                BasicText("·  ${s["adhan"]} ${Format.time(prayer.adhan, st.clock24h)}", style = Base.copy(color = p.inkDim, fontSize = 22.sp).let { if (s.rtl) it.arabic().copy(fontSize = 25.sp) else it }, maxLines = 1)
            }
        }
    }
}

/** The settings sheet's "Screen" row: away mode, idle time, the notice before the adhan, periodic adhkar, what wakes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresenceSettingsRow(st: DisplayState, photos: Boolean, patch: (JSONObject) -> Unit) {
    val p = LocalPalette.current
    val s = st.strings
    val pr = st.settings.presence
    fun set(vararg kv: Pair<String, Any>) = patch(JSONObject().put("presence", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))
    Column {
        Label(s.presence("screen"), p.inkDim)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PresenceChip(s.presence("always_on"), !pr.enabled) { set("enabled" to false) }
            PresenceChip(s.presence("screen_off"), pr.enabled && pr.awayMode == PresenceSettings.Away.OFF) { set("enabled" to true, "away" to "off") }
            if (photos) PresenceChip(s["photos"], pr.enabled && pr.awayMode == PresenceSettings.Away.PHOTOS) { set("enabled" to true, "away" to "photos") }
            PresenceChip(s.presence("home_screen"), pr.enabled && pr.awayMode == PresenceSettings.Away.HOME) { set("enabled" to true, "away" to "home") }
            if (pr.enabled) {
                // tap to step through the usual values
                PresenceChip(s.presence("away_after", pr.idleMinutes.coerceAtLeast(1)), false) {
                    set("idleMinutes" to after(IDLE, pr.idleMinutes))
                }
                val r = st.settings.adhan.reminderMinutes
                PresenceChip(if (r > 0) s.presence("before_adhan", r) else s.presence("before_adhan_off"), r > 0) {
                    patch(JSONObject().put("adhan", JSONObject().put("reminderMinutes", after(BEFORE, r))))
                }
                val i = PERIODIC.indexOfFirst { it.matches(pr) }
                val text = when (pr.periodic) {
                    "every" -> s.presence("periodic_every", pr.everyMinutes)
                    "random" -> s.presence("periodic_random").replace("{a}", pr.minMinutes.toString()).replace("{b}", pr.maxMinutes.toString())
                    else -> s.presence("periodic_off")
                }
                PresenceChip(text, pr.periodic != "off") { patch(JSONObject().put("presence", PERIODIC[(i + 1) % PERIODIC.size].json())) }
                PresenceChip((if (pr.messages) "✓  " else "") + s.presence("wake_messages"), pr.messages) { set("messages" to !pr.messages) }
                PresenceChip((if (pr.reminders) "✓  " else "") + s.presence("wake_reminders"), pr.reminders) { set("reminders" to !pr.reminders) }
            }
        }
    }
}

/** The next of [values] after [current], round to the first. */
private fun after(values: List<Int>, current: Int) = values.firstOrNull { it > current } ?: values.first()

private val IDLE = listOf(1, 2, 5, 10, 15, 30, 60)
private val BEFORE = listOf(0, 5, 10, 15, 20, 30)

private class Periodic(val mode: String, val every: Int = 0, val min: Int = 0, val max: Int = 0) {
    fun matches(p: PresenceSettings) = p.periodic == mode && when (mode) {
        "every" -> p.everyMinutes == every
        "random" -> p.minMinutes == min && p.maxMinutes == max
        else -> true
    }

    fun json(): JSONObject = JSONObject().put("periodic", mode).apply {
        if (mode == "every") put("everyMinutes", every)
        if (mode == "random") put("minMinutes", min).put("maxMinutes", max)
    }
}

private val PERIODIC = listOf(
    Periodic("off"), Periodic("every", every = 30), Periodic("every", every = 60), Periodic("every", every = 120),
    Periodic("random", min = 20, max = 60), Periodic("random", min = 45, max = 120),
)

@Composable
private fun PresenceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.card)
            .adClick(onClick).padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        val style = Base.copy(color = if (selected) p.onAccent else p.ink, fontSize = 22.sp)
        BasicText(text, style = if (text.any { it in '؀'..'ۿ' }) style.arabic().copy(fontSize = 24.sp) else style, maxLines = 1)
    }
}
