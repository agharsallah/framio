package dev.framio.mawaqit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.DisplaySettings
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.engine.DisplayState
import org.json.JSONObject

/** What the sheet can do, wired by the activity to the engine and the settings store. */
class Actions(
    val patch: (JSONObject) -> Unit,
    val testAdhan: () -> Unit,
    val stopAudio: () -> Unit,
    val openPhotos: (() -> Unit)?,
    val refresh: () -> Unit,
    val remoteLink: () -> String?,
    val ntfyConnected: () -> Boolean,
    /** Opens the system file picker for an adhan of the user's own (USB stick, SD card…). */
    val chooseAdhan: (() -> Unit)? = null,
)

@Composable
fun MawaqitRoot(st: DisplayState, settingsOpen: Boolean, onSettings: (Boolean) -> Unit, actions: Actions) {
    val palette = Palettes.of(st.settings.display.theme)
    val direction = if (st.strings.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val picture = st.source.mosque?.takeIf { st.settings.display.theme == "mosque" }?.let { it.config.backgroundUrl ?: it.imageUrl }
    val mosques = rememberMosquesView()
    CompositionLocalProvider(LocalPalette provides palette, LocalLayoutDirection provides direction, LocalMosquesView provides mosques) {
        Background(palette, picture) {
            FitToDesign {
                val adhkar = adhkarOnTop(st) // false while a prayer phase, a message or black is up
                val screen = when {
                    st.screen.black -> "black"
                    !st.synced -> "sync"
                    st.overlay != null -> "message"
                    st.source.times == null -> "setup"
                    adhkar -> "adhkar"
                    isFullScreen(st) -> "phase:" + st.phase.kind.id
                    mosques.open -> "mosques"
                    else -> "main"
                }
                AnimatedContent(screen, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "screen") { key ->
                    when {
                        key == "black" -> Black()
                        key == "sync" -> SyncScreen(st)
                        key == "message" -> st.overlay?.let { MessageScreen(st, it) } ?: Black()
                        key == "setup" -> SetupScreen(st)
                        key == "adhkar" -> AdhkarScreen(st)
                        key.startsWith("phase:") -> {
                            PhaseScreen(st)
                            AdhkarPhaseHint(st)
                        }
                        key == "mosques" -> MosquesScreen(st, mosques)
                        // the prayer times, and a swipe away the adhkar display (ui/AdhkarLoop.kt)
                        else -> MainPages(st, onSettings = { onSettings(true) }) {
                            MainScreen(st, onHideFlash = { actions.patch(JSONObject().put("display", JSONObject().put("showFlash", false))) }) { onSettings(true) }
                        }
                    }
                }
                AnimatedVisibility(settingsOpen, enter = fadeIn(), exit = fadeOut()) {
                    SettingsSheet(st, actions) { onSettings(false) }
                }
            }
            // Black means the whole window, margins of a scaled layout included
            if (st.screen.black && !settingsOpen) Black()
        }
    }
}

/** On the frame itself: how to reach the panel, plus the few settings worth a tap. Everything else is on the panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(st: DisplayState, a: Actions, close: () -> Unit) {
    val p = LocalPalette.current
    val s = st.strings
    val d = st.settings.display
    var remoteQr by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(40.dp).clip(RoundedCornerShape(28.dp)).background(palette(p)).padding(40.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}, // taps inside don't close
            horizontalArrangement = Arrangement.spacedBy(44.dp),
        ) {
            Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                val link = if (remoteQr) a.remoteLink() else null
                if (link != null) {
                    Column(Modifier.width(380.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(link, Modifier.size(300.dp))
                        BasicText(s["remote"], Modifier.padding(top = 16.dp), style = Base.copy(color = p.ink, fontSize = 24.sp))
                        BasicText("Keep this code private: it opens the frame from anywhere.", Modifier.padding(top = 8.dp), style = Base.copy(color = p.inkDim, fontSize = 18.sp))
                    }
                } else {
                    PanelAccess(st, Modifier.width(380.dp))
                }
                // Under the code (the right column is full): on/off, the tick says "shown" even where the fill is subtle
                Section(s["messages"]) {
                    Chip((if (d.showFlash) "✓  " else "") + s["flash"], d.showFlash) {
                        a.patch(JSONObject().put("display", JSONObject().put("showFlash", !d.showFlash)))
                    }
                    Chip((if (d.showAnnouncements) "✓  " else "") + s["announcements"], d.showAnnouncements) {
                        a.patch(JSONObject().put("display", JSONObject().put("showAnnouncements", !d.showAnnouncements)))
                    }
                }
            }
            // scrolls when its chips outgrow the 800 px window (the adhkar row is the last)
            Column(Modifier.width(620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                BasicText(s["settings"], style = Base.copy(color = p.ink, fontSize = 40.sp, fontFamily = Fonts.light))
                Section(s["language"]) {
                    DisplaySettings.LANGUAGES.forEach { lang ->
                        Chip(LANGUAGE_NAMES[lang] ?: lang, lang == d.language) { a.patch(JSONObject().put("display", JSONObject().put("language", lang))) }
                    }
                }
                Section(s["theme"]) {
                    DisplaySettings.THEMES.forEach { t ->
                        Chip(t.replaceFirstChar(Char::uppercase), t == d.theme, swatch = if (t == "mosque") null else Palettes.of(t).accent) {
                            a.patch(JSONObject().put("display", JSONObject().put("theme", t)))
                        }
                    }
                }
                Section(s["remote"]) {
                    val r = st.settings.remote
                    val status = when {
                        !r.ntfyEnabled -> s["remote_off"]
                        a.ntfyConnected() -> "ntfy ✓"
                        else -> "ntfy …"
                    }
                    Chip(status, r.ntfyEnabled) {
                        a.patch(JSONObject().put("remote", JSONObject().put("ntfyEnabled", !r.ntfyEnabled)))
                    }
                    if (r.ntfyEnabled) Chip(if (remoteQr) "LAN QR" else "Remote QR", remoteQr) { remoteQr = !remoteQr }
                }
                Section(s["adhan"]) {
                    Chip("▶  " + s.prayer(PrayerKey.DHUHR), false, onClick = a.testAdhan)
                    if (st.audioPlaying) Chip("■", true, onClick = a.stopAudio)
                    a.chooseAdhan?.let { Chip(s["choose_file"], false, onClick = it) }
                    a.openPhotos?.let { Chip(s["photos"], false, onClick = it) }
                    Chip("↻", false, onClick = a.refresh)
                }
                PresenceSettingsRow(st, photos = a.openPhotos != null, patch = a.patch)
                AdhkarSettingsRow(st, close)
                Spacer(Modifier.height(4.dp))
                BasicText("Mawaqit · framio · ${st.source.mosque?.slug ?: st.settings.source.kind}", style = Base.copy(color = p.inkFaint, fontSize = 16.sp))
            }
        }
    }
}

private fun palette(p: Palette) = if (p.light) Color(0xFFFBF6EC) else p.bottom

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column {
        Label(title, p.inkDim)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, swatch: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) p.accent else p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(swatch))
            Spacer(Modifier.width(10.dp))
        }
        BasicText(text, style = Base.copy(color = if (selected) p.onAccent else p.ink, fontSize = 22.sp))
    }
}

private val LANGUAGE_NAMES = mapOf("en" to "English", "ar" to "العربية", "fr" to "Français", "de" to "Deutsch", "nl" to "Nederlands", "tr" to "Türkçe")
