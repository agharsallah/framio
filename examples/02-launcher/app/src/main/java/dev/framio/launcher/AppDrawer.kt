package dev.framio.launcher

import android.content.ComponentName
import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every launchable app on the frame, Android's Settings included. Tap opens; hold for the app's menu
 * (put it on Home, open it at startup, close it, its Android page, uninstall).
 */
@Composable
fun AllApps(c: Controller, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) { c.refreshAll() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bottom)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 72.dp, end = 72.dp, top = 36.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("All apps", Modifier.weight(1f), style = Heading)
            Hint("Hold an app for more")
            Spacer(Modifier.width(24.dp))
            Pill("Home", onClose, glyph = Glyphs.back)
        }
        Spacer(Modifier.height(20.dp))
        LazyVerticalGrid(
            GridCells.Fixed(6),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp), // clear of the home button's corner
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(c.allApps, key = { it.component.flattenToString() }) { e ->
                Column(
                    Modifier
                        .press(onLongClick = { onMenu(e) }) { onOpen(e) }
                        .clip(RoundedCornerShape(20.dp))
                        .background(Card)
                        .padding(vertical = 20.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ComponentIcon(e.component, 72)
                    Spacer(Modifier.height(12.dp))
                    BasicText(
                        e.label,
                        Modifier.fillMaxWidth(),
                        style = Small.copy(color = Ink, fontSize = 19.sp, textAlign = TextAlign.Center),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // a dot under the name: it is also on Home
                    Box(Modifier.padding(top = 6.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (c.onShelf(e)) Amber else Color.Transparent))
                }
            }
        }
    }
}

/** What can be done with one app; shown over Home or All apps. */
@Composable
fun AppSheet(c: Controller, e: AppEntry, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val startupTarget = e.component.flattenToString()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Card)
                .border(1.dp, Hairline, RoundedCornerShape(28.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComponentIcon(e.component, 64)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(e.label, style = Heading.copy(fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(e.pkg, style = Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(24.dp))
            val onShelf = c.onShelf(e)
            val isStartup = c.config.startup == startupTarget
            val kept = e.pkg in c.config.keepRunning
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Open", onOpen, Modifier.weight(1f), accent = Amber, filled = true)
                Pill(if (onShelf) "Remove from Home" else "Add to Home", { c.togglePinned(e) }, Modifier.weight(1.4f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(
                    if (isStartup) "Opens at startup" else "Open at startup",
                    { c.update { copy(startup = if (isStartup) null else startupTarget) } },
                    Modifier.weight(1.4f),
                    accent = if (isStartup) Amber else Ink,
                    filled = isStartup,
                    glyph = Glyphs.power,
                )
                Pill(if (kept) "Kept open" else "Keep open", { c.toggleKeep(e.pkg) }, Modifier.weight(1f), accent = if (kept) Amber else Ink, filled = kept)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Close", {
                    c.close(e.pkg)
                    onDismiss()
                }, Modifier.weight(1f), accent = Coral, glyph = Glyphs.close)
                Pill("App info", {
                    Apps.openInfo(context, e.pkg)
                    onDismiss()
                }, Modifier.weight(1f))
                if (!e.system) {
                    Pill("Uninstall", {
                        Apps.uninstall(context, e.pkg)
                        onDismiss()
                    }, Modifier.weight(1f), accent = Coral)
                }
            }
        }
    }
}

// --- Icons, loaded off the main thread and kept: the grid scrolls without decoding again

private val icons = object : LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
fun ComponentIcon(component: ComponentName, sizeDp: Int) {
    val context = LocalContext.current
    val key = "${component.flattenToString()}@$sizeDp"
    IconImage(key, sizeDp) { Apps.icon(context, component) }
}

@Composable
fun PackageIcon(pkg: String, sizeDp: Int) {
    val context = LocalContext.current
    IconImage("$pkg@$sizeDp", sizeDp) { packageIcon(context, pkg) }
}

private fun packageIcon(context: Context, pkg: String) = try {
    context.packageManager.getApplicationIcon(pkg)
} catch (_: Exception) {
    null
}

@Composable
private fun IconImage(key: String, sizeDp: Int, load: () -> android.graphics.drawable.Drawable?) {
    // 1 dp == 1 px on the frame (160 dpi), but go by density in case the launcher ever runs elsewhere
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val image by produceState(icons.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { load()?.let { render(it, px) } }?.also { icons.put(key, it) }
        }
    }
    val img = image
    if (img != null) {
        Image(img, null, Modifier.size(sizeDp.dp))
    } else {
        Canvas(Modifier.size(sizeDp.dp)) { drawRoundRect(CardHigh, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())) }
    }
}
