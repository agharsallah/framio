package dev.framio.launcher

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Laid out for the frame: 1280×800 at 160 dpi, so 1.dp == 1 px. Sizes are meant to read from across a room.

private val Ink = Color(0xFFF1ECE4) // warm white, softer than #FFF at night
private val InkDim = Color(0xFF9A968E)
private val InkFaint = Color(0xFF55575D)
private val Card = Color(0xFF16181D)
private val Hairline = Color(0xFF262A31)
private val Amber = Color(0xFFE9A23B)
private val Bottom = Color(0xFF0B0C0F) // the background's lower colour, for the shelf's edge fades

// Named family on purpose: below API 28 Compose only reaches Roboto Light/Thin via "sans-serif-light" etc.
private val Base = TextStyle(fontFamily = FontFamily.SansSerif)

/** Up to this many tiles share the row's width; more, and the row becomes a shelf that scrolls. */
private const val ROW_TILES = 4

/** The drawn glyphs of the apps that predate icons; every other app shows its own icon or a monogram. */
private val GLYPHS: Map<String, DrawScope.(Color) -> Unit> = mapOf(
    "dev.framio.hello" to ::clockGlyph,
    "dev.framio.lab" to ::chipGlyph,
)

@Composable
fun HomeScreen(
    lifecycle: Lifecycle,
    use24h: Boolean,
    frameoInstalled: Boolean,
    apps: List<AppTile>,
    onPhotos: () -> Unit,
    onOpen: (AppTile) -> Unit,
) {
    val now by rememberMinuteClock(lifecycle)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14161B), Bottom)))
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 88.dp) // bottom: keep clear of the hot corner dot
    ) {
        BasicText(
            "framio",
            Modifier.align(Alignment.TopEnd).padding(top = 18.dp),
            style = Base.copy(color = InkFaint, fontSize = 20.sp, letterSpacing = 6.sp),
        )

        Column(Modifier.fillMaxSize()) {
            Clock(now, use24h)
            Spacer(Modifier.weight(1f))
            Shelf(lifecycle, frameoInstalled, apps, onPhotos, onOpen, Modifier.fillMaxWidth().height(280.dp))
        }
    }
}

/** Photos first, then every framio app found on the frame (see [Apps.discover]). */
@Composable
private fun Shelf(lifecycle: Lifecycle, frameoInstalled: Boolean, apps: List<AppTile>, onPhotos: () -> Unit, onOpen: (AppTile) -> Unit, modifier: Modifier) {
    val gap = 24.dp

    @Composable
    fun Photos(modifier: Modifier) = Tile(
        title = "Photos",
        subtitle = if (frameoInstalled) "Frameo slideshow" else "Frameo not installed",
        accent = Amber,
        enabled = frameoInstalled,
        onClick = onPhotos,
        modifier = modifier,
        icon = { Canvas(Modifier.size(96.dp, 72.dp)) { photosGlyph(this, Amber) } },
    )

    @Composable
    fun App(app: AppTile, modifier: Modifier) = Tile(
        title = app.title,
        subtitle = app.subtitle,
        accent = Color(app.accent),
        enabled = true,
        onClick = { onOpen(app) },
        modifier = modifier,
        icon = { AppIcon(app) },
    )

    if (apps.size + 1 <= ROW_TILES) {
        // Few apps: they share the width, Photos a little wider (the frame's main job)
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            Photos(Modifier.weight(1.15f))
            apps.forEach { App(it, Modifier.weight(1f)) }
        }
        return
    }

    // More apps: a shelf that scrolls and snaps tile by tile. 3.5 tiles show, so the cut one says "there's more".
    BoxWithConstraints(modifier) {
        val tileWidth: Dp = (maxWidth - gap * 3) / 3.5f
        val state = rememberLazyListState()
        // Home always opens on Photos, wherever the shelf was left
        LaunchedEffect(lifecycle, state) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { state.scrollToItem(0) }
        }
        LazyRow(
            Modifier.fillMaxSize(),
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            item(key = "photos") { Photos(Modifier.width(tileWidth)) }
            items(apps, key = { it.pkg }) { App(it, Modifier.width(tileWidth)) }
        }
        // Edge fades, only where there is more to scroll to
        if (state.canScrollBackward) EdgeFade(Modifier.align(Alignment.CenterStart), toRight = false)
        if (state.canScrollForward) EdgeFade(Modifier.align(Alignment.CenterEnd), toRight = true)
    }
}

@Composable
private fun EdgeFade(modifier: Modifier, toRight: Boolean) {
    val colors = listOf(Bottom.copy(alpha = 0f), Bottom)
    Box(modifier.width(56.dp).fillMaxHeight().background(Brush.horizontalGradient(if (toRight) colors else colors.reversed())))
}

@Composable
private fun Clock(now: Long, use24h: Boolean) {
    // From the configuration, so the clock re-formats if the frame's language changes
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val time = remember(now, use24h, locale) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm", locale).format(Date(now)) }
    val amPm = remember(now, locale) { SimpleDateFormat("a", locale).format(Date(now)) }
    val date = remember(now, locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    }

    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(
            time,
            Modifier.alignByBaseline(),
            style = Base.copy(
                color = Ink,
                fontSize = 196.sp,
                lineHeight = 196.sp,
                fontWeight = FontWeight.Light, // Thin looks elegant up close but fades from across the room
                letterSpacing = (-6).sp,
                fontFeatureSettings = "tnum", // digits don't shift as the minutes change
            ),
        )
        if (!use24h) {
            Spacer(Modifier.width(20.dp))
            BasicText(
                amPm,
                Modifier.alignByBaseline(),
                style = Base.copy(color = InkDim, fontSize = 40.sp, fontWeight = FontWeight.Light),
            )
        }
    }
    BasicText(
        date,
        Modifier.padding(start = 6.dp),
        style = Base.copy(color = InkDim, fontSize = 40.sp, fontWeight = FontWeight.Light),
    )
}

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(shape)
            .background(Card)
            .drawBehind {
                // soft accent glow from the top-right corner gives each tile its colour
                val r = size.height * 1.1f
                val corner = Offset(size.width, 0f)
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), corner, r),
                    radius = r,
                    center = corner,
                )
            }
            .border(if (pressed) 2.dp else 1.dp, if (pressed) accent else Hairline, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(30.dp)
    ) {
        Box(Modifier.align(Alignment.TopStart)) { icon() }
        Column(Modifier.align(Alignment.BottomStart)) {
            // Any app's name must fit: a long one steps down until it holds on one line
            BasicText(
                title,
                style = Base.copy(color = Ink, letterSpacing = (-0.5).sp),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 46.sp, stepSize = 2.sp),
            )
            Spacer(Modifier.height(4.dp))
            // The line stays even when empty, so every title sits at the same height
            BasicText(subtitle, style = Base.copy(color = InkDim, fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, minLines = 1)
        }
    }
}

/** The app's drawn glyph if it has one here, else its own icon, else its first letter. */
@Composable
private fun AppIcon(app: AppTile) {
    val accent = Color(app.accent)
    val glyph = GLYPHS[app.pkg]
    val image = remember(app.pkg, app.icon) { if (glyph == null) app.icon?.let { render(it, 72) } else null }
    when {
        glyph != null -> Canvas(Modifier.size(96.dp, 72.dp)) { glyph(accent) }
        image != null -> Image(image, null, Modifier.size(72.dp))
        else -> Box(Modifier.size(72.dp).border(5.dp, accent, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            BasicText(app.title.take(1).uppercase(), style = Base.copy(color = accent, fontSize = 38.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** Icons can be vectors, bitmaps or layer lists: draw any of them into a bitmap of the tile's size, once. */
private fun render(drawable: Drawable, sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

/** Sun over two hills. */
private fun photosGlyph(scope: DrawScope, accent: Color) = with(scope) {
    val w = size.width
    val h = size.height
    drawCircle(accent, radius = h * 0.15f, center = Offset(w * 0.78f, h * 0.22f))
    drawPath(
        Path().apply { moveTo(w * 0.36f, h); lineTo(w * 0.66f, h * 0.46f); lineTo(w, h); close() },
        accent.copy(alpha = 0.55f),
    )
    drawPath(
        Path().apply { moveTo(0f, h); lineTo(w * 0.30f, h * 0.28f); lineTo(w * 0.68f, h); close() },
        accent,
    )
}

/** A clock face: example 01 is a clock page. */
private fun clockGlyph(scope: DrawScope, accent: Color) = with(scope) {
    val stroke = 6.dp.toPx()
    val r = size.height / 2 - stroke / 2
    val c = Offset(size.height / 2, size.height / 2)
    drawCircle(accent, radius = r, center = c, style = Stroke(stroke))
    drawLine(accent, c, Offset(c.x, c.y - r * 0.6f), stroke, StrokeCap.Round)
    drawLine(accent, c, Offset(c.x + r * 0.45f, c.y), stroke, StrokeCap.Round)
}

/** A microchip: example 03 tests the frame's hardware. */
private fun chipGlyph(scope: DrawScope, accent: Color) = with(scope) {
    val stroke = 6.dp.toPx()
    val h = size.height
    val body = h * 0.62f
    val left = (h - body) / 2
    val top = (h - body) / 2
    drawRoundRect(accent, Offset(left, top), Size(body, body), CornerRadius(8.dp.toPx()), style = Stroke(stroke))
    drawRect(accent.copy(alpha = 0.55f), Offset(left + body * 0.3f, top + body * 0.3f), Size(body * 0.4f, body * 0.4f))
    // three pins on each side
    for (i in 1..3) {
        val p = left + body * i / 4
        drawLine(accent, Offset(p, 0f), Offset(p, top), stroke, StrokeCap.Round)
        drawLine(accent, Offset(p, top + body), Offset(p, h), stroke, StrokeCap.Round)
        drawLine(accent, Offset(0f, p), Offset(left, p), stroke, StrokeCap.Round)
        drawLine(accent, Offset(left + body, p), Offset(h, p), stroke, StrokeCap.Round)
    }
}

/** Current time, refreshed on each minute boundary, only while the screen is visible. */
@Composable
private fun rememberMinuteClock(lifecycle: Lifecycle): State<Long> =
    produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000L - value % 60_000L + 20L)
            }
        }
    }
