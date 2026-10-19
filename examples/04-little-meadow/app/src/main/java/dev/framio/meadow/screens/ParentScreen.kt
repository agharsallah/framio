package dev.framio.meadow.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.core.Command
import dev.framio.meadow.core.Lang
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable

/**
 * For grown-ups, behind the gate. The main controls live on the dashboard (phone or computer on the
 * same Wi-Fi); this screen shows how to reach it and the few things you want right at the frame.
 */
@Composable
fun ParentScreen(onBack: () -> Unit, onExit: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.flow.collectAsState()
    val de = s.lang == Lang.DE
    fun t(en: String, deText: String) = if (de) deText else en
    LaunchedEffect(Unit) {
        app.screen = "parent"
        app.voice.stop()
        app.music.play(null)
    }
    val urls = app.server.urls()
    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(470.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icon.Back, size = 72.dp, onTap = onBack)
                    Spacer(Modifier.width(18.dp))
                    BasicText(t("For grown-ups", "Für Erwachsene"), style = Type.title)
                }
                Spacer(Modifier.height(24.dp))
                SoftCard(Palette.sky.lighten(0.45f), Modifier.fillMaxWidth(), corner = 28.dp, contentAlignment = Alignment.TopStart) {
                    Column(Modifier.padding(26.dp)) {
                        BasicText(t("Parent dashboard", "Eltern-Dashboard"), style = Type.label)
                        BasicText(t("Open on a phone or computer on this Wi-Fi:", "Im selben WLAN auf Handy oder Computer öffnen:"), style = Type.small)
                        Spacer(Modifier.height(10.dp))
                        if (urls.isEmpty()) BasicText(t("No Wi-Fi right now", "Gerade kein WLAN"), style = Type.body)
                        urls.forEach { BasicText(it, style = Type.body.copy(fontSize = 26.sp)) }
                        Spacer(Modifier.height(12.dp))
                        BasicText("PIN  ${s.pin}", style = Type.clay(44.sp, Palette.ink))
                    }
                }
                Spacer(Modifier.height(22.dp))
                val left = app.session.minutesLeft
                BasicText(
                    t("Played today: ", "Heute gespielt: ") + "${app.progress.todaySeconds() / 60} min" +
                        (left?.let { t(" · left: ", " · übrig: ") + "$it min" } ?: ""),
                    style = Type.body,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(t("+15 min today", "+15 Min heute"), Palette.sage) { app.commands.send(Command.Bonus(15)) }
                    Action(if (app.session.isLocked) t("Unlock", "Entsperren") else t("Lock", "Sperren"), Palette.rose) {
                        app.commands.send(Command.Lock(!app.session.isLocked))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Action(t("Night light", "Nachtlicht"), Palette.lavender) { app.commands.send(Command.NightLight) }
                    Action(t("Leave app", "App verlassen"), Palette.paperDeep, onExit)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Setting(t("Age", "Alter")) {
                    for (a in 2..5) Chip("$a", s.age == a) { app.settings.update { it.copy(age = a) } }
                }
                Setting(t("Language", "Sprache")) {
                    for (l in Lang.entries) Chip(l.label, s.lang == l) { app.settings.update { it.copy(lang = l) } }
                }
                Setting(t("Volume", "Lautstärke")) {
                    Stepper("${s.volume} %", { app.settings.update { it.copy(volume = (it.volume - 10).coerceAtLeast(0)) } }) {
                        app.settings.update { it.copy(volume = (it.volume + 10).coerceAtMost(100)) }
                    }
                }
                Setting(t("Brightness", "Helligkeit")) {
                    Stepper("${s.brightness} %", { app.settings.update { it.copy(brightness = (it.brightness - 10).coerceAtLeast(10)) } }) {
                        app.settings.update { it.copy(brightness = (it.brightness + 10).coerceAtMost(100)) }
                    }
                }
                Setting(t("Daily play time", "Spielzeit pro Tag")) {
                    Stepper(if (s.dailyLimitMin == 0) t("no limit", "kein Limit") else "${s.dailyLimitMin} min", { app.settings.update { it.copy(dailyLimitMin = (it.dailyLimitMin - 5).coerceAtLeast(0)) } }) {
                        app.settings.update { it.copy(dailyLimitMin = (it.dailyLimitMin + 5).coerceAtMost(240)) }
                    }
                }
                Setting(t("Wiggle break every", "Bewegungspause alle")) {
                    Stepper(if (s.breakEveryMin == 0) t("never", "nie") else "${s.breakEveryMin} min", { app.settings.update { it.copy(breakEveryMin = (it.breakEveryMin - 5).coerceAtLeast(0)) } }) {
                        app.settings.update { it.copy(breakEveryMin = (it.breakEveryMin + 5).coerceAtMost(120)) }
                    }
                }
                Setting(t("Bedtime mode", "Schlafenszeit")) {
                    Chip(t("On", "An"), s.bedtimeMode) { app.settings.update { it.copy(bedtimeMode = true) } }
                    Chip(t("Off", "Aus"), !s.bedtimeMode) { app.settings.update { it.copy(bedtimeMode = false) } }
                    BasicText("${s.bedtime} – ${s.wakeUp}", style = Type.small, modifier = Modifier.padding(start = 8.dp))
                }
                Setting(t("Music", "Musik")) {
                    Chip(t("On", "An"), s.music) { app.settings.update { it.copy(music = true) } }
                    Chip(t("Off", "Aus"), !s.music) { app.settings.update { it.copy(music = false) } }
                }
                Setting(t("Show what's said", "Gesprochenes anzeigen")) {
                    Chip(t("On", "An"), s.captions) { app.settings.update { it.copy(captions = true) } }
                    Chip(t("Off", "Aus"), !s.captions) { app.settings.update { it.copy(captions = false) } }
                }
                Setting(t("Calmer motion", "Ruhigere Bewegung")) {
                    Chip(t("On", "An"), s.reducedMotion) { app.settings.update { it.copy(reducedMotion = true) } }
                    Chip(t("Off", "Aus"), !s.reducedMotion) { app.settings.update { it.copy(reducedMotion = false) } }
                }
            }
        }
    }
}

@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = Type.label.copy(fontSize = 21.sp), modifier = Modifier.width(250.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) Palette.honey else Palette.cream)
            .pressable(onTap = onTap)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) { BasicText(label, style = Type.label.copy(fontSize = 19.sp, color = if (on) Palette.cream else Palette.ink)) }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    RoundButton(Icon.Minus, size = 52.dp, onTap = onMinus)
    BasicText(value, style = Type.label.copy(fontSize = 20.sp), modifier = Modifier.width(120.dp).padding(horizontal = 8.dp))
    RoundButton(Icon.Plus, size = 52.dp, onTap = onPlus)
}

@Composable
private fun Action(label: String, color: androidx.compose.ui.graphics.Color, onTap: () -> Unit) {
    SoftCard(color.lighten(0.2f), Modifier.size(width = 220.dp, height = 70.dp).pressable(onTap = onTap), corner = 24.dp, elevation = 3.dp) {
        BasicText(label, style = Type.label.copy(fontSize = 19.sp))
    }
}
