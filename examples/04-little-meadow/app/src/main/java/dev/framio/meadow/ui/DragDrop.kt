package dev.framio.meadow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Sound
import kotlinx.coroutines.launch

/**
 * Drag and drop that also works as tap-then-tap: children under 5 often can't hold a drag, so
 * tapping an item picks it up (it glows) and tapping a zone puts it there. Both paths call the
 * item's onDrop(zoneId); return true to accept, false and the item floats back home.
 *
 *     val arena = rememberDragArena()
 *     Box(Modifier.dropZone(arena, "nest")) { … }
 *     DragItem(arena, id = berry.id, onDrop = { zone -> zone == "nest" }) { Sprite("ob_berry") }
 */
@Stable
class DragArena {
    internal val zones = HashMap<Any, Rect>()
    internal val items = HashMap<Any, (Any) -> Boolean>()
    /** The item picked up by a tap, waiting for a tap on a zone. */
    var selected by mutableStateOf<Any?>(null)
    var dragging by mutableStateOf<Any?>(null)
        internal set
    /** The zone under the dragged item, for highlighting it. */
    var hover by mutableStateOf<Any?>(null)
        internal set

    /** The zone whose (slightly enlarged) bounds contain [p]; the nearest centre wins when several do. */
    fun zoneAt(p: Offset, slop: Float = 40f): Any? = zones.entries
        .filter { it.value.inflate(slop).contains(p) }
        .minByOrNull { (it.value.center - p).getDistanceSquared() }?.key

    /** Drops the selected item on [zone] (tap-then-tap). */
    fun dropSelectedOn(zone: Any): Boolean {
        val sel = selected ?: return false
        selected = null
        return items[sel]?.invoke(zone) ?: false
    }
}

@Composable
fun rememberDragArena(): DragArena = remember { DragArena() }

/** Marks a place items can be dropped on. Tapping it drops the tap-selected item. */
fun Modifier.dropZone(arena: DragArena, id: Any): Modifier = composed {
    DisposableEffect(arena, id) { onDispose { arena.zones.remove(id) } }
    this
        .onGloballyPositioned { arena.zones[id] = it.boundsInRoot() }
        .pointerInput(arena, id) { detectTapGestures { arena.dropSelectedOn(id) } }
}

@Composable
fun DragItem(
    arena: DragArena,
    id: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    corner: Dp = 40.dp,
    onDrop: (zone: Any) -> Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val app = LocalApp.current
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var home by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val drop by rememberUpdatedState(onDrop)
    DisposableEffect(arena, id) {
        arena.items[id] = { z -> drop(z) }
        onDispose {
            arena.items.remove(id)
            if (arena.selected == id) arena.selected = null
            if (arena.dragging == id) arena.dragging = null
        }
    }
    val lifted = arena.dragging == id
    val scale by animateFloatAsState(if (lifted) 1.12f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "lift")
    fun goHome() = scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow)) }

    Box(
        modifier
            .onGloballyPositioned { home = it.boundsInRoot() } // before the translation: where it lives
            .zIndex(if (lifted) 10f else 0f)
            .graphicsLayer {
                translationX = offset.value.x
                translationY = offset.value.y
                scaleX = scale
                scaleY = scale
            }
            .halo(arena.selected == id, Palette.honey, corner)
            .pointerInput(arena, id, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        arena.selected = null
                        arena.dragging = id
                        app.sfx.play(Sound.Pick)
                    },
                    onDrag = { change, d ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + d) }
                        arena.hover = arena.zoneAt(home.center + offset.value + d)
                    },
                    onDragEnd = {
                        val z = arena.hover
                        arena.hover = null
                        arena.dragging = null
                        if (z != null && drop(z)) scope.launch { offset.snapTo(Offset.Zero) } else goHome()
                    },
                    onDragCancel = {
                        arena.hover = null
                        arena.dragging = null
                        goHome()
                    },
                )
            }
            .pointerInput(arena, id, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures {
                    arena.selected = if (arena.selected == id) null else id
                    app.sfx.play(Sound.Pick)
                }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
