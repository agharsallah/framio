package dev.framio.launcher

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class Tab(val label: String, val glyph: Glyph) {
    QUICK("Quick", Glyphs.sun),
    WIFI("Wi-Fi", Glyphs.wifi(3)),
    APPS("Running", Glyphs.grid),
    STARTUP("Startup", Glyphs.power),
    BUTTON("Home button", Glyphs.home),
    DEVICE("Device", Glyphs.gear),
}

/**
 * The launcher's settings and the frame's controls in one panel over Home: a rail of tabs on the left, the tab on the
 * right. Everything is sized for a finger on a 10" frame, and nothing here polls: each tab reads the system when it
 * opens, and Wi-Fi follows the system's broadcasts while its tab is open.
 */
@Composable
fun ControlCenter(c: Controller, wifi: Wifi, tab: Tab, onTab: (Tab) -> Unit, onBrightnessPreview: (Float?) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) { c.refresh() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Bottom)
            // swallow taps so nothing reaches Home underneath
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Row(Modifier.fillMaxSize()) {
            Rail(tab, onTab, onClose)
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 56.dp)) {
                when (tab) {
                    Tab.QUICK -> QuickTab(c, wifi, onBrightnessPreview, onTab)
                    Tab.WIFI -> WifiTab(wifi)
                    Tab.APPS -> RunningTab(c)
                    Tab.STARTUP -> StartupTab(c)
                    Tab.BUTTON -> ButtonTab(c)
                    Tab.DEVICE -> DeviceTab(c, wifi)
                }
            }
        }
        val message = c.message
        if (message != null) {
            BasicText(
                message,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardHigh)
                    .border(1.dp, Hairline, RoundedCornerShape(24.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                style = Body.copy(fontSize = 22.sp),
            )
        }
    }
}

@Composable
private fun Rail(tab: Tab, onTab: (Tab) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .width(264.dp)
            .fillMaxHeight()
            .background(Top)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill("Home", onClose, Modifier.fillMaxWidth(), glyph = Glyphs.back, accent = Ink)
        Spacer(Modifier.height(18.dp))
        Tab.entries.forEach { t ->
            val on = t == tab
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) CardHigh else Color.Transparent)
                    .clickable { onTab(t) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(26.dp)) { t.glyph(this, if (on) Amber else InkDim) }
                Spacer(Modifier.width(16.dp))
                BasicText(t.label, style = Body.copy(color = if (on) Ink else InkDim, fontSize = 23.sp), maxLines = 1)
            }
        }
    }
}

/** Each tab is one lazy list with a title: long tabs scroll, short ones cost nothing extra. */
@Composable
private fun TabList(title: String, action: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 36.dp, bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, Modifier.weight(1f), style = Heading)
                action?.invoke()
            }
        }
        content()
    }
}

// --- Quick

@Composable
private fun QuickTab(c: Controller, wifi: Wifi, onBrightnessPreview: (Float?) -> Unit, onTab: (Tab) -> Unit) {
    val context = LocalContext.current
    TabList("Quick controls") {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LevelSlider(c.media, { c.setVolume(Device.Stream.MEDIA, it) }, {}, Sky, Glyphs.speaker(c.media), "Media volume")
                LevelSlider(c.alarm, { c.setVolume(Device.Stream.ALARM, it) }, {}, Coral, Glyphs.bell, "Alarm volume")
                LevelSlider(
                    c.brightness,
                    onChange = {
                        c.previewBrightness(it)
                        onBrightnessPreview(it)
                    },
                    onCommit = {
                        c.commitBrightness(it)
                        onBrightnessPreview(null)
                    },
                    accent = Amber,
                    glyph = Glyphs.sun,
                    label = "Brightness",
                )
            }
        }
        item {
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                QuickTile(
                    "Wi-Fi",
                    when {
                        !wifi.enabled -> "Off"
                        else -> wifi.status ?: "Not connected"
                    },
                    Glyphs.wifi(if (wifi.enabled) wifi.level ?: 0 else null),
                    on = wifi.enabled,
                    Modifier.weight(1f),
                    onLongClick = { onTab(Tab.WIFI) },
                ) { wifi.turn(!wifi.enabled) }
                QuickTile("Sleep screen", "Tap to wake", Glyphs.moon, on = false, Modifier.weight(1f)) { c.sleep() }
                QuickTile("Close apps", "Free memory", Glyphs.close, on = false, Modifier.weight(1f)) {
                    c.refreshRunning().invokeOnCompletion { c.closeAll() }
                }
                QuickTile("Android settings", "System", Glyphs.gear, on = false, Modifier.weight(1f)) { Apps.openSettings(context) }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            Hint("Brightness: Frameo sets its own when it starts. Hold the Wi-Fi tile for networks.")
        }
    }
}

@Composable
private fun QuickTile(
    title: String,
    detail: String,
    glyph: Glyph,
    on: Boolean,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .press(onLongClick = onLongClick, onClick = onClick)
            .height(150.dp)
            .clip(shape)
            .background(if (on) Teal.copy(alpha = 0.16f) else CardHigh)
            .border(1.dp, if (on) Teal.copy(alpha = 0.6f) else Hairline, shape)
            .padding(22.dp),
    ) {
        Canvas(Modifier.size(34.dp)) { glyph(if (on) Teal else Ink) }
        Spacer(Modifier.weight(1f))
        BasicText(title, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(detail, style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Wi-Fi

@Composable
private fun WifiTab(wifi: Wifi) {
    var joining by remember { mutableStateOf<Network?>(null) }
    DisposableEffect(wifi) {
        wifi.listing = true
        wifi.scan()
        onDispose { wifi.listing = false }
    }
    TabList(
        "Wi-Fi",
        action = {
            if (wifi.enabled) {
                Pill(if (wifi.busy) "Scanning…" else "Scan", { wifi.scan() }, glyph = Glyphs.refresh, enabled = !wifi.busy)
                Spacer(Modifier.width(20.dp))
            }
            Toggle(wifi.enabled, { wifi.turn(it) })
        },
    ) {
        item {
            Hint(
                when {
                    !wifi.enabled -> "Wi-Fi is off. The clock needs it after a reboot (the frame has no clock battery)."
                    wifi.status != null -> "Connected to ${wifi.status}. 2.4 GHz networks only: the frame's radio has no 5 GHz."
                    else -> "Pick a network. 2.4 GHz only: the frame's radio has no 5 GHz."
                },
                Modifier.padding(bottom = 16.dp),
            )
        }
        joining?.let { n ->
            item(key = "join") { JoinCard(n, onJoin = { pw -> wifi.connect(n, pw); joining = null }, onCancel = { joining = null }) }
        }
        items(wifi.networks, key = { it.ssid }) { n ->
            NetworkRow(
                n,
                onClick = {
                    if (n.current) return@NetworkRow
                    if (n.saved || !n.secured) wifi.connect(n, null) else joining = n
                },
                onForget = { wifi.forget(n) },
            )
        }
        if (wifi.enabled && wifi.networks.isEmpty() && !wifi.busy) {
            item { Hint("No networks found yet. Scan again in a moment.") }
        }
    }
}

@Composable
private fun NetworkRow(n: Network, onClick: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (n.current) Teal.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(30.dp)) { Glyphs.wifi(n.level)(this, if (n.current) Teal else Ink) }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            BasicText(n.ssid, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                listOfNotNull(
                    if (n.current) "Connected" else if (n.saved) "Saved" else null,
                    if (n.secured) "Secured" else "Open",
                ).joinToString(" · "),
                style = Small,
            )
        }
        if (n.secured) Canvas(Modifier.size(22.dp)) { Glyphs.lock(this, InkFaint) }
        if (n.saved) {
            Spacer(Modifier.width(18.dp))
            Pill("Forget", onForget, accent = InkDim)
        }
    }
}

/** The password goes above the list, so the keyboard (bottom half of the screen) never covers it. */
@Composable
private fun JoinCard(n: Network, onJoin: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember(n.ssid) { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(n.ssid) { focus.requestFocus() }
    val ok = password.length >= 8 // WPA passphrases are 8–63 characters
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CardHigh)
            .border(1.dp, Teal.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            .padding(24.dp),
    ) {
        BasicText("Password for ${n.ssid}", style = Body)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                password,
                { password = it },
                Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Card)
                    .border(1.dp, Hairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                textStyle = Body.copy(fontSize = 26.sp),
                singleLine = true,
                cursorBrush = SolidColor(Teal),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ok) onJoin(password) }),
            )
            Spacer(Modifier.width(14.dp))
            Pill(if (show) "Hide" else "Show", { show = !show }, accent = InkDim)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Connect", { onJoin(password) }, accent = Teal, filled = true, enabled = ok)
            Pill("Cancel", onCancel, accent = InkDim)
        }
    }
}

// --- Running apps

@Composable
private fun RunningTab(c: Controller) {
    LaunchedEffect(Unit) { c.refreshRunning() }
    val list = c.running
    TabList("Running apps", action = { Pill("Refresh", { c.refreshRunning() }, glyph = Glyphs.refresh) }) {
        item {
            MemoryBar(c.memory)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("Close all", { c.closeAll() }, accent = Coral, filled = true, glyph = Glyphs.close, enabled = !list.isNullOrEmpty())
                Spacer(Modifier.width(20.dp))
                Hint("Apps marked Keep stay open. Frameo is kept by default: it is the slideshow.", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            c.root == false -> item { Hint("Listing and closing other apps needs root, and su did not answer.") }
            list == null -> item { Hint("Looking…") }
            list.isEmpty() -> item { Hint("No other app is running.") }
            else -> items(list, key = { it.pkg }) { r ->
                val keep = r.pkg in c.config.keepRunning
                Row(Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PackageIcon(r.pkg, 52)
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(r.label, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText("≈ ${r.rssKb / 1024} MB", style = Small)
                    }
                    Pill(if (keep) "Kept" else "Keep", { c.toggleKeep(r.pkg) }, accent = if (keep) Amber else InkDim, filled = keep, glyph = Glyphs.lock)
                    Spacer(Modifier.width(12.dp))
                    Pill("Close", { c.close(r.pkg) }, accent = Coral)
                }
            }
        }
    }
}

@Composable
private fun MemoryBar(m: Device.Memory) {
    val used = 1f - m.availMb.toFloat() / m.totalMb.coerceAtLeast(1)
    Column {
        Row {
            BasicText("Memory", Modifier.weight(1f), style = Body)
            BasicText("${m.availMb} MB free of ${m.totalMb} MB", style = Small.copy(fontSize = 21.sp))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(CardHigh)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(used.coerceIn(0f, 1f))
                    .background(if (used > 0.85f) Coral else Sky)
            )
        }
    }
}

// --- Startup

@Composable
private fun StartupTab(c: Controller) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (c.allApps.isEmpty()) c.refreshAll() }
    val cfg = c.config
    val frameo = remember { Apps.isInstalled(context, Apps.FRAMEO) }
    TabList("Startup") {
        item {
            Hint("What the frame opens by itself after it starts. A countdown on Home lets you stay instead.")
            Spacer(Modifier.height(8.dp))
            SettingRow("Countdown", detail = "Seconds before it opens") {
                Choice(listOf(0 to "None", 5 to "5 s", 10 to "10 s", 30 to "30 s"), cfg.startupDelaySec, { v -> c.update { copy(startupDelaySec = v) } })
            }
            SettingRow("Back after idle", detail = "When Home sits untouched, go back to the startup app") {
                Choice(
                    listOf(0 to "Off", 2 to "2 min", 10 to "10 min", 30 to "30 min"),
                    cfg.idleReturnMin,
                    { v -> c.update { copy(idleReturnMin = v) } },
                )
            }
            Spacer(Modifier.height(12.dp))
            BasicText("Open at startup", style = Body.copy(color = InkDim))
            Spacer(Modifier.height(8.dp))
        }
        item { StartupOption("Home", "Stay on this screen", cfg.startup == null) { c.update { copy(startup = null) } } }
        if (frameo) {
            item { StartupOption("Photos", "Frameo slideshow", cfg.startup == Config.PHOTOS) { c.update { copy(startup = Config.PHOTOS) } } }
        }
        items(c.allApps.filter { it.pkg != Apps.FRAMEO }, key = { it.component.flattenToString() }) { e ->
            val target = e.component.flattenToString()
            StartupOption(e.label, if (e.framio) "framio" else null, cfg.startup == target, e) { c.update { copy(startup = target) } }
        }
    }
}

@Composable
private fun StartupOption(title: String, detail: String?, selected: Boolean, entry: AppEntry? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Amber.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(26.dp)) {
            drawCircle(if (selected) Amber else InkFaint, size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            if (selected) drawCircle(Amber, size.minDimension / 4)
        }
        Spacer(Modifier.width(20.dp))
        if (entry != null) {
            PackageIcon(entry.pkg, 44)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) BasicText(detail, style = Small)
        }
    }
}

// --- Home button

@Composable
private fun ButtonTab(c: Controller) {
    val context = LocalContext.current
    val cfg = c.config
    val overlay = remember { Settings.canDrawOverlays(context) }
    TabList("Home button") {
        item {
            Hint("The dot above every app. Tap it for Home, Back, Close app and Controls; hold it to go Home; drag it to any edge.")
            Spacer(Modifier.height(8.dp))
            SettingRow("Show the home button", detail = if (overlay) null else "Not allowed to draw over apps yet: restart the launcher (it grants itself with root)") {
                Toggle(cfg.dotEnabled, { v -> c.update { copy(dotEnabled = v) } })
            }
            SettingRow("Size") {
                Choice(listOf(44 to "Small", 56 to "Medium", 72 to "Large"), cfg.dotSize, { v -> c.update { copy(dotSize = v) } })
            }
            SettingRow("At rest", detail = "How visible it stays when not in use") {
                Choice(listOf(0.2f to "Faint", 0.45f to "Soft", 0.75f to "Clear", 1f to "Full"), cfg.dotRestAlpha, { v -> c.update { copy(dotRestAlpha = v) } })
            }
            SettingRow("Edge") {
                Choice(
                    listOf(false to "Left", true to "Right"),
                    cfg.dotPlace.right,
                    { v -> c.update { copy(dotPlace = dotPlace.copy(right = v)) } },
                )
            }
            SettingRow("Corner", detail = "Put it back in a bottom corner") {
                Pill("Reset", { c.update { copy(dotPlace = DotPlace(right = dotPlace.right, y = 1f)) } })
            }
            SettingRow(
                "Instant Back",
                detail = if (c.navEnabled) "On: Back and Close app are immediate" else "Off: Back takes about a second (root key event). Turns on an accessibility service.",
            ) {
                Toggle(c.navEnabled, { c.setNav(it) })
            }
        }
    }
}

// --- Device

@Composable
private fun DeviceTab(c: Controller, wifi: Wifi) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { c.refreshRunning() } // root check + memory
    val storage = remember { Device.storage(context) }
    val ip = remember(wifi.status) { Device.ipAddress() }
    val version = remember { versionName(context) }
    var confirm by remember { mutableStateOf<String?>(null) }
    TabList("Device") {
        item {
            Info("Frame", Device.model())
            Info("Network", listOfNotNull(wifi.status ?: "Offline", ip).joinToString(" · "))
            Info("Memory", "${c.memory.availMb} MB free of ${c.memory.totalMb} MB")
            Info("Storage", "%.1f GB free of %.1f GB".format(storage.freeGb, storage.totalGb))
            Info("Up for", Device.uptime())
            Info("Root", when (c.root) { true -> "Yes"; false -> "No"; null -> "…" })
            Info("Launcher", "Framio $version")
            Spacer(Modifier.height(24.dp))
            BasicText("Android settings", style = Body.copy(color = InkDim))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("All", { Apps.openSettings(context) }, glyph = Glyphs.gear)
                Pill("Wi-Fi", { Apps.openSettings(context, Settings.ACTION_WIFI_SETTINGS) })
                Pill("Display", { Apps.openSettings(context, Settings.ACTION_DISPLAY_SETTINGS) })
                Pill("Sound", { Apps.openSettings(context, Settings.ACTION_SOUND_SETTINGS) })
                Pill("Date & time", { Apps.openSettings(context, Settings.ACTION_DATE_SETTINGS) })
            }
            Spacer(Modifier.height(28.dp))
            BasicText("Power", style = Body.copy(color = InkDim))
            Spacer(Modifier.height(12.dp))
            // Two steps, so a stray touch never restarts the frame
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (confirm == "reboot") "Tap again to restart" else "Restart", {
                    if (confirm == "reboot") c.reboot() else confirm = "reboot"
                }, accent = Amber, filled = confirm == "reboot", glyph = Glyphs.refresh)
                Pill(if (confirm == "off") "Tap again to power off" else "Power off", {
                    if (confirm == "off") c.powerOff() else confirm = "off"
                }, accent = Coral, filled = confirm == "off", glyph = Glyphs.power)
                if (confirm != null) Pill("Cancel", { confirm = null }, accent = InkDim)
            }
            LaunchedEffect(confirm) {
                if (confirm != null) {
                    delay(5_000)
                    confirm = null
                }
            }
            Spacer(Modifier.height(10.dp))
            Hint("The frame has no clock battery: after a power off, it needs Wi-Fi to know the time again.")
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        BasicText(label, Modifier.width(180.dp), style = Body.copy(color = InkDim))
        BasicText(value, Modifier.weight(1f), style = Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: Exception) {
    ""
}
