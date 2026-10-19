package dev.framio.meadow.play

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.framio.meadow.AppState
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.ui.FallingPetals
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.ProgressDots
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.Backdrop
import dev.framio.meadow.ui.floaty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Shared narration lines (voice/<lang>/common.tsv). */
object Lines {
    val praise = (1..8).map { "praise_$it" }
    val tryAgain = (1..4).map { "try_$it" }
    const val NEW_STICKER = "sticker_new"
    const val PLAY_AGAIN = "end_again"
    val finished = (1..3).map { "end_$it" }
}

internal class PlayHost(
    override val play: Play,
    override val app: AppState,
    private val scope: CoroutineScope,
) : PlayScope {

    private val settings get() = app.settings.value
    override val age get() = settings.age
    override val lang get() = settings.lang
    override val reducedMotion get() = settings.reducedMotion

    private var levelState by mutableIntStateOf(app.progress.level(play.id, settings.age))
    override val level get() = levelState

    private var hintState by mutableStateOf(false)
    override val hint get() = hintState

    private var mood by mutableStateOf(Mood.Wave)
    override var friendMood: Mood
        get() = mood
        set(value) {
            mood = value
            moodJob?.cancel()
            if (value == Mood.Happy) moodJob = scope.launch { delay(1800); if (mood == Mood.Happy) mood = Mood.Idle }
        }
    private var moodJob: Job? = null

    var finished by mutableStateOf(false)
        private set
    var sticker by mutableStateOf<String?>(null)
        private set
    var dotsDone by mutableIntStateOf(0)
        private set
    var dotsTotal by mutableIntStateOf(0)
        private set

    private var promptIds: List<String> = emptyList()
    private var misses = 0
    private val random = Random(SystemClock.uptimeMillis())
    private var lastPraise = -1
    var lastTouch = SystemClock.uptimeMillis()
    var idlePrompts = 0

    override fun say(vararg ids: String) = app.voice.say(*ids)
    override suspend fun sayAndWait(vararg ids: String) = app.voice.sayAndWait(*ids)

    override fun prompt(vararg ids: String) {
        promptIds = ids.toList()
        idlePrompts = 0
        app.voice.say(*ids)
    }

    override fun clearPrompt() {
        promptIds = emptyList()
        hintState = false
    }

    override fun repeatPrompt() {
        if (promptIds.isNotEmpty()) app.voice.say(*promptIds.toTypedArray())
    }

    private fun praiseLine(): String {
        var i = random.nextInt(Lines.praise.size)
        if (i == lastPraise) i = (i + 1) % Lines.praise.size
        lastPraise = i
        return Lines.praise[i]
    }

    override fun praise() {
        app.sfx.play(Sound.Good)
        friendMood = Mood.Happy
        app.voice.say(praiseLine())
    }

    override suspend fun praiseAndWait() {
        app.sfx.play(Sound.Good)
        friendMood = Mood.Happy
        app.voice.sayAndWait(praiseLine())
    }

    override fun notQuite() {
        app.sfx.play(Sound.Hmm)
        misses++
        app.voice.say(Lines.tryAgain[random.nextInt(Lines.tryAgain.size)])
        if (misses >= 2) hintState = true
    }

    override fun showHint(on: Boolean) {
        hintState = on
    }

    override fun sfx(s: Sound, volume: Float, rate: Float) = app.sfx.play(s, volume, rate)

    override fun roundDone(firstTry: Boolean): Int {
        misses = 0
        hintState = false
        levelState = app.progress.round(play.id, firstTry, age)
        return levelState
    }

    override fun rounds(done: Int, total: Int) {
        dotsDone = done
        dotsTotal = total
    }

    override fun practised(skill: String) = app.progress.practised(skill)

    override fun finish() {
        if (finished) return
        finished = true
        promptIds = emptyList()
        app.progress.finished(play.id)
        app.progress.log("Finished ${play.title.en}")
        sticker = app.progress.award(Stickers.all)
        friendMood = Mood.Happy
        scope.launch {
            delay(400)
            app.sfx.play(Sound.Sparkle)
            val end = Lines.finished[random.nextInt(Lines.finished.size)]
            if (sticker != null) app.voice.sayAndWait(end, Lines.NEW_STICKER) else app.voice.sayAndWait(end)
            delay(500)
            if (finished) app.voice.say(Lines.PLAY_AGAIN)
        }
    }

    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
    }

    fun idleCheck() {
        val now = SystemClock.uptimeMillis()
        if (finished || promptIds.isEmpty() || app.voice.speaking.value) return
        if (now - lastTouch > IDLE_MS && idlePrompts < 3) {
            idlePrompts++
            lastTouch = now
            repeatPrompt()
            hintState = true
        }
    }

    private companion object {
        const val IDLE_MS = 10_000L
    }
}

/** An activity on screen: backdrop, top bar, content, the friend, and the ending. */
@Composable
fun PlayScreen(play: Play, world: World, onBack: () -> Unit) {
    val app = LocalApp.current
    var run by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val host = remember(play.id, run) { PlayHost(play, app, scope) }

    LaunchedEffect(host) {
        app.playId = play.id
        app.progress.opened(play.id)
        app.music.play(play.music)
        play.intro?.let {
            // the world screen is still saying the title ("Count with me!"): let it finish first
            delay(300)
            app.voice.speaking.first { speaking -> !speaking }
            app.voice.say(it)
        }
        while (true) {
            delay(1000)
            host.idleCheck()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(host) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        host.touched()
                    }
                }
            },
    ) {
        Backdrop(play.backdrop ?: world.backdrop)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icon.Back, size = 80.dp, onTap = {
                    app.voice.stop()
                    onBack()
                })
                Spacer(Modifier.weight(1f))
                if (play.showDots && host.dotsTotal > 0) ProgressDots(host.dotsDone, host.dotsTotal, color = world.color)
                Spacer(Modifier.weight(1f))
                RoundButton(Icon.Speaker, size = 80.dp, sound = null, hint = host.hint && !host.finished, onTap = host::repeatPrompt)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                key(run) { play.content(host) }
            }
        }
        if (play.showFriend) {
            FriendView(
                play.friend,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 4.dp)
                    .size(210.dp),
                mood = host.friendMood,
                still = play.stillFriend,
                onTap = { host.repeatPrompt() },
            )
        }
        AnimatedVisibility(host.finished, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            Ending(host, world, onAgain = {
                app.voice.stop()
                run++
            }, onBack = {
                app.voice.stop()
                onBack()
            })
        }
    }
}

@Composable
private fun Ending(host: PlayHost, world: World, onAgain: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.paper.copy(alpha = 0.88f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, // nothing behind is tappable
    ) {
        FallingPetals(Modifier.fillMaxSize(), seed = host.hashCode())
        Row(
            Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            FriendView(host.play.friend, Modifier.size(380.dp), mood = host.friendMood)
            Spacer(Modifier.width(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val sticker = host.sticker
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(500); shown = true }
                AnimatedVisibility(shown && sticker != null, enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) + fadeIn()) {
                    SoftCard(Palette.cream, Modifier.size(260.dp)) {
                        if (sticker != null) Sprite(sticker, Modifier.size(200.dp).floaty(3))
                    }
                }
                if (sticker == null) Spacer(Modifier.height(40.dp))
                Spacer(Modifier.height(36.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundButton(Icon.Replay, size = 120.dp, color = world.color, iconColor = Palette.cream, onTap = onAgain)
                        BasicText(againLabel(host), style = Type.small, modifier = Modifier.padding(top = 8.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundButton(Icon.Home, size = 120.dp, onTap = onBack)
                        BasicText(world.title[host.lang], style = Type.small, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private fun againLabel(host: PlayHost) = if (host.lang == dev.framio.meadow.core.Lang.DE) "Nochmal" else "Again"
