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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
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
 * Every launchable app on the frame, Android's Settings included: the framio apps first, then what came with the
 * frame. Tap opens; hold for the app's menu (put it on Home, open it at startup, close it, its Android page, uninstall).
 * Cards echo Home's tiles: each glows in its app's colour, the tile accent for framio apps, the icon's own otherwise.
 */
@Composable
fun AllApps(c: Controller, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(Unit) { c.refreshAll() }
    val (framio, others) = remember(c.allApps) { c.allApps.partition { it.framio } }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Top, Bottom)))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 72.dp, end = 72.dp, top = 40.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("All apps", style = Heading.copy(fontSize = 44.sp))
                BasicText(
                    if (c.allApps.isEmpty()) "Looking…" else "${c.allApps.size} apps · hold one for more",
                    style = Small,
                )
            }
            Pill("Home", onClose, glyph = Glyphs.back)
        }
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            GridCells.Fixed(6),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp), // clear of the home button's corner
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            section("framio", framio.size)
            items(framio, key = { it.component.flattenToString() }) { e -> AppCard(e, c.onShelf(e), onOpen, onMenu) }
            section("On this frame", others.size)
            items(others, key = { it.component.flattenToString() }) { e -> AppCard(e, c.onShelf(e), onOpen, onMenu) }
        }
    }
}

private fun LazyGridScope.section(title: String, count: Int) {
    if (count == 0) return
    item(key = "section:$title", span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(title.uppercase(), style = Small.copy(color = InkDim, fontSize = 17.sp, letterSpacing = 3.sp))
            Spacer(Modifier.width(12.dp))
            BasicText("$count", style = Small.copy(color = InkFaint, fontSize = 17.sp))
        }
    }
}

@Composable
private fun AppCard(e: AppEntry, onHome: Boolean, onOpen: (AppEntry) -> Unit, onMenu: (AppEntry) -> Unit) {
    val icon = rememberIcon("${e.component.flattenToString()}@$CARD_ICON", CARD_ICON) { Apps.icon(it, e.component) }
    val accent = e.accent?.let(::Color) ?: icon?.accent ?: InkDim
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .press(onLongClick = { onMenu(e) }) { onOpen(e) }
            .height(184.dp)
            .clip(shape)
            .background(Card)
            .drawBehind {
                // the same corner glow as Home's tiles
                val r = size.height * 1.05f
                val corner = Offset(size.width, 0f)
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), corner, r), r, corner)
            }
            .border(1.dp, Hairline, shape)
            .padding(horizontal = 12.dp, vertical = 18.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(CARD_ICON.dp), contentAlignment = Alignment.Center) {
                IconOrPlaceholder(icon, CARD_ICON)
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                e.label,
                Modifier.fillMaxWidth(),
                style = Body.copy(fontSize = 21.sp, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Kept even when empty, so every name sits at the same height
            BasicText(
                e.subtitle.substringAfter(" · "), // "Example 03 · Hardware" → "Hardware": the card is narrow
                Modifier.fillMaxWidth(),
                style = Small.copy(fontSize = 16.sp, textAlign = TextAlign.Center),
                maxLines = 1,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onHome) {
            // On Home: a small amber house in the corner
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-6).dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Amber.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) { Glyphs.home(this, Amber) }
            }
        }
    }
}

private const val CARD_ICON = 84

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

/** An app icon drawn at its display size, and its dominant colour (for the card's glow). */
class LoadedIcon(val image: ImageBitmap, val accent: Color?)

private val icons = object : LruCache<String, LoadedIcon>(3 * 1024 * 1024) {
    override fun sizeOf(key: String, value: LoadedIcon) = value.image.width * value.image.height * 4
}

@Composable
fun ComponentIcon(component: ComponentName, sizeDp: Int) {
    val icon = rememberIcon("${component.flattenToString()}@$sizeDp", sizeDp) { Apps.icon(it, component) }
    IconOrPlaceholder(icon, sizeDp)
}

@Composable
fun PackageIcon(pkg: String, sizeDp: Int) {
    val icon = rememberIcon("$pkg@$sizeDp", sizeDp) { packageIcon(it, pkg) }
    IconOrPlaceholder(icon, sizeDp)
}

private fun packageIcon(context: Context, pkg: String) = try {
    context.packageManager.getApplicationIcon(pkg)
} catch (_: Exception) {
    null
}

@Composable
private fun rememberIcon(key: String, sizeDp: Int, load: (Context) -> android.graphics.drawable.Drawable?): LoadedIcon? {
    val context = LocalContext.current
    // 1 dp == 1 px on the frame (160 dpi), but go by density in case the launcher ever runs elsewhere
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val icon by produceState(icons.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                load(context)?.let { d ->
                    val bitmap = renderBitmap(d, px)
                    LoadedIcon(bitmap.asImageBitmap(), dominant(bitmap))
                }
            }?.also { icons.put(key, it) }
        }
    }
    return icon
}

@Composable
private fun IconOrPlaceholder(icon: LoadedIcon?, sizeDp: Int) {
    if (icon != null) {
        Image(icon.image, null, Modifier.size(sizeDp.dp))
    } else {
        Canvas(Modifier.size(sizeDp.dp)) { drawRoundRect(CardHigh, cornerRadius = CornerRadius(size.minDimension * 0.22f)) }
    }
}

/**
 * The icon's main colour: the average of its opaque, saturated pixels on a coarse grid (a few hundred reads, once per
 * icon). Null for grey icons, which then glow in a neutral tone.
 */
internal fun dominant(b: Bitmap): Color? {
    var r = 0L
    var g = 0L
    var bl = 0L
    var n = 0
    val hsv = FloatArray(3)
    val step = (b.width / 16).coerceAtLeast(1)
    for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
        val p = b.getPixel(x, y)
        if (android.graphics.Color.alpha(p) < 200) continue
        android.graphics.Color.colorToHSV(p, hsv)
        if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue
        r += android.graphics.Color.red(p)
        g += android.graphics.Color.green(p)
        bl += android.graphics.Color.blue(p)
        n++
    }
    if (n < 4) return null
    return Color((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
}
