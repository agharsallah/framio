package dev.framio.meadow.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Family
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.Stickers
import dev.framio.meadow.ui.Backdrop
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.drawIcon
import dev.framio.meadow.ui.pressable
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The sticker book: every sticker the child has earned; the rest are soft shadows still to find. */
@Composable
fun StickerBook(onBack: () -> Unit) {
    val app = LocalApp.current
    val have = remember { app.progress.stickers().toSet() }
    val lang = app.settings.value.lang
    LaunchedEffect(Unit) {
        app.screen = "stickers"
        app.voice.say(if (have.isEmpty()) "stickers_empty" else "stickers_hello")
    }
    Box(Modifier.fillMaxSize()) {
        Backdrop("bg_meadow")
        Box(Modifier.fillMaxSize().background(Palette.paper.copy(alpha = 0.55f)))
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icon.Home, size = 80.dp, onTap = onBack)
            Spacer(Modifier.width(24.dp))
            BasicText(Txt("My stickers", "Meine Sticker")[lang] + "  ${have.size} / ${Stickers.all.size}", style = Type.title)
        }
        LazyVerticalGrid(
            GridCells.Fixed(8),
            Modifier.fillMaxSize().padding(top = 120.dp, start = 40.dp, end = 40.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(Stickers.all) { s ->
                val got = s in have
                SoftCard(
                    if (got) Palette.cream else Palette.paperDeep,
                    Modifier.size(132.dp).pressable(enabled = got, sound = Sound.Pop) {
                        app.voice.say("name_${s.removePrefix("ob_").substringBefore("_")}")
                    },
                    corner = 30.dp, elevation = if (got) 4.dp else 0.dp,
                ) {
                    Sprite(s, Modifier.size(104.dp), alpha = if (got) 1f else 0.14f)
                }
            }
        }
    }
}

/**
 * Messages from the family: voice notes ("Goodnight from Grandma") and photos, sent from the
 * dashboard. A voice card plays with one tap; a photo opens big.
 */
@Composable
fun Mailbox(onBack: () -> Unit, autoplay: String? = null, onAutoplayed: () -> Unit = {}) {
    val app = LocalApp.current
    val items by app.family.items.collectAsState()
    val lang = app.settings.value.lang
    var open by remember { mutableStateOf<Family.Item?>(null) }
    fun play(item: Family.Item) {
        app.family.markSeen(item.id)
        app.progress.log("Opened message from ${item.from}")
        if (item.kind == Family.Kind.Voice) app.voice.say("@file:${item.file.absolutePath}") else open = item
    }
    LaunchedEffect(Unit) {
        app.screen = "mailbox"
        app.music.play(Track.Calm)
        if (autoplay == null) app.voice.say(if (items.isEmpty()) "mail_empty" else "mail_hello")
    }
    // sent from the dashboard with "play it now": the chime of a new message, then the message itself
    LaunchedEffect(autoplay) {
        val item = autoplay?.let { id -> app.family.items.value.firstOrNull { it.id == id } } ?: return@LaunchedEffect
        delay(700)
        app.sfx.play(Sound.Sparkle)
        delay(600)
        play(item)
        onAutoplayed()
    }
    Box(Modifier.fillMaxSize()) {
        Backdrop("bg_meadow")
        Box(Modifier.fillMaxSize().background(Palette.sky.copy(alpha = 0.2f)))
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icon.Home, size = 80.dp, onTap = onBack)
            Spacer(Modifier.width(24.dp))
            BasicText(Txt("Messages", "Nachrichten")[lang], style = Type.title)
        }
        FriendView(Friend.Bo, Modifier.align(Alignment.BottomStart).padding(start = 16.dp).size(240.dp), mood = Mood.Wave)
        LazyRow(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(top = 60.dp),
            contentPadding = PaddingValues(start = 280.dp, end = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MessageCard(item) { play(item) }
            }
        }
        open?.let { item ->
            Box(Modifier.fillMaxSize().background(Palette.ink.copy(alpha = 0.6f)).pressable(sound = null) { open = null }, contentAlignment = Alignment.Center) {
                Photo(item, Modifier.fillMaxSize().padding(40.dp), ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun MessageCard(item: Family.Item, onTap: () -> Unit) {
    SoftCard(
        if (item.seen) Palette.cream else Palette.honey.copy(alpha = 0.9f),
        Modifier.size(width = 280.dp, height = 340.dp).pressable(sound = Sound.Pop, onTap = onTap),
        corner = 40.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (item.kind == Family.Kind.Photo) {
                Photo(item, Modifier.size(220.dp).clip(RoundedCornerShape(28.dp)), ContentScale.Crop)
            } else {
                Box(Modifier.size(200.dp).drawBehind { drawIcon(Icon.Speaker, Palette.ink) })
            }
            Spacer(Modifier.height(12.dp))
            BasicText(item.from, style = Type.label.copy(fontSize = 26.sp))
        }
    }
}

/** A family photo, decoded small enough for the frame (never the camera's full 12 MP). */
@Composable
fun Photo(item: Family.Item, modifier: Modifier, scale: ContentScale) {
    val bmp by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { decodeScaled(item.file.absolutePath, 1280) }
    }
    bmp?.let { Image(it, null, modifier, contentScale = scale) } ?: Spacer(modifier)
}

fun decodeScaled(path: String, maxSide: Int): ImageBitmap? = try {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
    null
}
