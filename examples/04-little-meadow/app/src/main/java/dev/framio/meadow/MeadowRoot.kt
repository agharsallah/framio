package dev.framio.meadow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.ui.CaptionOverlay
import dev.framio.meadow.ui.CaptionSpot
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.core.Command
import dev.framio.meadow.core.Gate
import dev.framio.meadow.play.Catalog
import dev.framio.meadow.play.PlayScreen
import dev.framio.meadow.screens.BreakScreen
import dev.framio.meadow.screens.HomeScreen
import dev.framio.meadow.screens.Mailbox
import dev.framio.meadow.screens.ParentScreen
import dev.framio.meadow.screens.RestScreen
import dev.framio.meadow.screens.StarFromAfar
import dev.framio.meadow.screens.StickerBook
import dev.framio.meadow.screens.WorldScreen
import kotlinx.coroutines.delay

/** Ids of the quiet-corner activities the root opens on its own (night light, bedtime). */
const val NIGHT_LIGHT = "nightlight"

@Composable
fun MeadowRoot(nav: Nav, resumed: () -> Boolean, onExit: () -> Unit) {
    val app = LocalApp.current
    val gate by app.session.gate.collectAsState()
    var star by remember { mutableStateOf(false) }
    /** A line a grown-up sent from the dashboard ("Dinner time!"): a friend pops up and says it. */
    var call by remember { mutableStateOf<String?>(null) }
    /** A family message to open as soon as the mailbox shows. */
    var openMessage by remember { mutableStateOf<String?>(null) }
    val route = nav.current
    val currentRoute by rememberUpdatedState(route)

    // the play clock: once a second while the frame shows the app and a child is playing
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val r = currentRoute
            val g = app.session.gate.value
            val play = (r as? Route.Play)?.let { Catalog.play(it.id) }
            val counts = resumed() && r != Route.Parent && (g == Gate.Open || g == Gate.Night) &&
                (play?.countsAsScreenTime ?: true)
            app.session.tick(play?.id, counts)
        }
    }

    LaunchedEffect(Unit) {
        app.commands.events.collect { c ->
            when (c) {
                Command.Home -> nav.home()
                is Command.OpenWorld -> Catalog.world(c.id)?.let { nav.home(); nav.go(Route.World(it.id)) }
                is Command.OpenPlay -> Catalog.worldOf(c.id)?.let { nav.openPlay(it.id, c.id) }
                is Command.Lock -> app.session.lock(c.on)
                is Command.Bonus -> app.session.addBonus(c.minutes)
                Command.Wiggle -> app.session.forceBreak()
                Command.NightLight -> Catalog.worldOf(NIGHT_LIGHT)?.let { nav.openPlay(it.id, NIGHT_LIGHT) }
                Command.Star -> {
                    app.progress.award(dev.framio.meadow.play.Stickers.all)
                    star = true
                }
                is Command.Message -> {
                    openMessage = c.id
                    nav.go(Route.Mailbox)
                }
                is Command.Say -> call = c.voiceId
                Command.Wake -> app.wakeScreen()
            }
        }
    }

    LaunchedEffect(route) {
        app.screen = when (route) {
            Route.Home -> "home"
            is Route.World -> "world:${route.id}"
            is Route.Play -> "play:${route.id}"
            Route.Stickers -> "stickers"
            Route.Mailbox -> "mailbox"
            Route.Parent -> "parent"
        }
        if (route !is Route.Play) app.playId = null
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            route,
            transitionSpec = { (fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.985f)) togetherWith fadeOut(tween(260)) },
            label = "route",
        ) { r ->
            when (r) {
                Route.Home -> HomeScreen(
                    worlds = Catalog.worlds,
                    onWorld = { nav.go(Route.World(it.id)) },
                    onStickers = { nav.go(Route.Stickers) },
                    onMailbox = { nav.go(Route.Mailbox) },
                    onParent = { nav.go(Route.Parent) },
                )
                is Route.World -> {
                    val w = Catalog.world(r.id)
                    if (w != null) WorldScreen(w, onPlay = { nav.go(Route.Play(it.id)) }, onBack = nav::back)
                    else LaunchedEffect(r) { nav.home() }
                }
                is Route.Play -> {
                    val p = Catalog.play(r.id)
                    val w = Catalog.worldOf(r.id)
                    if (p != null && w != null) PlayScreen(p, w, onBack = nav::back) else LaunchedEffect(r) { nav.home() }
                }
                Route.Stickers -> StickerBook(onBack = nav::back)
                Route.Mailbox -> Mailbox(onBack = nav::back, autoplay = openMessage, onAutoplayed = { openMessage = null })
                Route.Parent -> ParentScreen(onBack = nav::back, onExit = onExit)
            }
        }
        if (route != Route.Parent) {
            when (gate) {
                Gate.Locked, Gate.DailyLimit -> RestScreen(gate, onParent = { nav.go(Route.Parent) })
                Gate.Break -> if (route !is Route.Play || Catalog.play(route.id)?.countsAsScreenTime != false) BreakScreen()
                else -> Unit
            }
        }
        if (star) StarFromAfar(onDone = { star = false })
        call?.let { id -> FriendCall(id, Modifier.align(Alignment.BottomEnd), onDone = { call = null }) }
        val (spot, speaker) = captionSpot(route, gate, call)
        CaptionOverlay(spot, speaker, night = gate == Gate.Night)
    }
}

/**
 * Where captions go on each screen, and whose face they carry: always a band with nothing to tap.
 * Activities and worlds use their top bar; the home screen and mailbox a bubble from the corner
 * friend; the rest and wiggle-break screens the space under their centred scene.
 */
private fun captionSpot(route: Route, gate: Gate, call: String?): Pair<CaptionSpot?, Friend?> {
    if (route == Route.Parent) return null to null
    if (call != null) {
        // the friend who calls speaks: in the top bar where there is one, else in a bubble beside it
        val base = captionSpot(route, gate, null).first
        return (if (base == CaptionSpot.TopBar || base == CaptionSpot.TopEnd) base else CaptionSpot.ByCaller) to callFriend(call)
    }
    val play = (route as? Route.Play)?.let { Catalog.play(it.id) }
    when (gate) {
        Gate.Locked, Gate.DailyLimit -> return CaptionSpot.BottomCenter to Friend.Pip
        Gate.Break -> if (play?.countsAsScreenTime != false) return CaptionSpot.BottomCenter to Friend.Pip
        else -> Unit
    }
    return when (route) {
        Route.Home -> HOME_SPOT to if (gate == Gate.Night) Friend.Luma else Friend.Pip
        is Route.World -> CaptionSpot.TopBar to Catalog.world(route.id)?.friend
        is Route.Play -> (if (play?.captions != false) CaptionSpot.TopBar else null) to play?.friend
        Route.Stickers -> CaptionSpot.TopEnd to Friend.Pip
        Route.Mailbox -> MAILBOX_SPOT to Friend.Bo
        Route.Parent -> null to null
    }
}

private val HOME_SPOT = CaptionSpot.besideFriend(250.dp)
private val MAILBOX_SPOT = CaptionSpot.besideFriend(262.dp)

/** Not Pip: Pip already stands in the home screen's corner. */
private fun callFriend(voiceId: String) = if (voiceId == "call_bed") Friend.Luma else Friend.Bo

/** A friend leans in from the corner, says the grown-up's line, and leaves again. */
@Composable
private fun FriendCall(voiceId: String, modifier: Modifier, onDone: () -> Unit) {
    val app = LocalApp.current
    val friend = callFriend(voiceId)
    val shown = remember { Animatable(0f) }
    LaunchedEffect(voiceId) {
        shown.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        app.voice.sayAndWait(voiceId)
        delay(500)
        shown.animateTo(0f, tween(350))
        onDone()
    }
    FriendView(
        friend,
        modifier
            .padding(end = 24.dp, bottom = 8.dp)
            .size(280.dp)
            .graphicsLayer {
                alpha = shown.value
                translationY = (1f - shown.value) * 120f
            },
        mood = Mood.Wave,
    )
}
