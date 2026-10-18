package dev.framio.mawaqit.data

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarLoop
import dev.framio.mawaqit.core.AdhkarLoopSettings
import dev.framio.mawaqit.core.Dhikr
import dev.framio.mawaqit.core.LoopPage
import dev.framio.mawaqit.engine.DisplayState
import dev.framio.mawaqit.remote.Rpc
import dev.framio.mawaqit.ui.isFullScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.random.Random

/** The frame's two pages: the prayer times and the adhkar display. */
enum class FramePage(val id: String) {
    MAIN("main"), ADHKAR("adhkar");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** Where the loop is: the round ([cycle], the shuffle's seed) and the page of that round's playlist. */
data class LoopPosition(val cycle: Int = 0, val index: Int = 0)

/**
 * The adhkar display's state: which page is up, where the loop is, and the dhikr slides of the main screen. The UI
 * advances the loop (a page stays its [LoopPage.millis] while it is on screen, so nothing runs while it isn't); every
 * call is on the main thread, like the [AdhkarController] it reads the book and the current collection from.
 */
class AdhkarLoopController(private val store: SettingsStore, private val adhkar: AdhkarController) {

    private val _page = MutableStateFlow(home(store.current.loop))
    val page: StateFlow<FramePage> = _page.asStateFlow()
    private val _position = MutableStateFlow(LoopPosition())
    val position: StateFlow<LoopPosition> = _position.asStateFlow()

    private var last: DisplayState? = null
    private var lastTouch = SystemClock.elapsedRealtime()
    private var playlistKey: List<Any?>? = null
    private var playlist: List<LoopPage> = emptyList()

    private var slideKey: AdhkarLoopSettings? = null
    private var slideCandidates: List<Pair<Dhikr, AdhkarCategory>> = emptyList()
    private val recentSlides = ArrayDeque<String>()
    private val random = Random(System.nanoTime())

    private fun home(s: AdhkarLoopSettings) = if (s.enabled && s.home == "adhkar") FramePage.ADHKAR else FramePage.MAIN

    // ---------------------------------------------------------------- pages

    /** Every second from the engine: back to the resting page after a while untouched on the other one. */
    fun tick(st: DisplayState) {
        last = st
        val s = st.settings.loop
        val home = home(s)
        if (!s.enabled && _page.value != FramePage.MAIN) return show(FramePage.MAIN, "page off")
        if (_page.value != home && s.returnMinutes > 0 && SystemClock.elapsedRealtime() - lastTouch > s.returnMinutes * 60_000L) {
            show(home, "idle")
        }
        if (_page.value == FramePage.ADHKAR) playlist() // a new collection's time is picked up here, not while drawing
    }

    /** Any touch or key (the engine's user activity): the page stays where the user put it. */
    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
    }

    /** The display came to the front (started, back from the photos): the resting page. */
    fun onShown() {
        touch()
        show(home(store.current.loop), "shown")
    }

    fun show(page: FramePage, why: String) {
        val p = if (page == FramePage.ADHKAR && !store.current.loop.enabled) FramePage.MAIN else page
        if (p == _page.value) return
        _page.value = p
        Log.i(TAG, "page ${p.id} ($why)")
    }

    /** The loop page is what Root draws now: no reader, phase, message or black screen over it. */
    private fun loopOnScreen(st: DisplayState): Boolean = _page.value == FramePage.ADHKAR && pagesOnScreen(st)

    private fun pagesOnScreen(st: DisplayState): Boolean =
        adhkar.ui.value.view == null && !st.screen.black && st.synced && st.overlay == null && st.source.times != null && !isFullScreen(st)

    // ---------------------------------------------------------------- the loop

    /** This round's pages, rebuilt when the settings, the language, the collection whose time it is or the round change. */
    fun playlist(cycle: Int = _position.value.cycle): List<LoopPage> {
        val s = store.current
        val current = adhkar.current.value
        val latin = s.display.language != "ar"
        val key = listOf(s.loop, latin, current, cycle)
        if (key != playlistKey) {
            val before = playlist.getOrNull(_position.value.index)?.key
            val sameRound = playlistKey?.let { it[0] == s.loop && it[1] == latin && it[2] == current } == true
            playlist = AdhkarLoop.playlist(adhkar.book, s.loop, current, latin, cycle)
            playlistKey = key
            // new settings or a new collection's time: carry on from the same page if it is still there
            if (!sameRound) {
                val i = playlist.indexOfFirst { it.key == before }
                _position.value = LoopPosition(cycle, if (i >= 0) i else 0)
            }
        }
        return playlist
    }

    /** The page to show now (null when no collection has anything to loop). */
    fun current(): LoopPage? {
        val list = playlist()
        val pos = _position.value
        return list.getOrNull(pos.index) ?: list.firstOrNull()
    }

    /** The next page, if [from] is still the one shown (the UI's timer and a remote "next" may meet). */
    fun next(from: LoopPosition = _position.value) {
        if (from != _position.value) return
        val size = playlist().size
        _position.value = if (from.index + 1 < size) from.copy(index = from.index + 1) else LoopPosition(from.cycle + 1, 0)
    }

    fun previous() {
        val pos = _position.value
        _position.value = if (pos.index > 0) pos.copy(index = pos.index - 1) else {
            val before = playlist(pos.cycle - 1).size
            LoopPosition(pos.cycle - 1, (before - 1).coerceAtLeast(0))
        }
    }

    /** A tap on the loop: that dhikr in the reader, to count it. */
    fun openInReader(page: LoopPage) {
        adhkar.open(page.category)
        val i = adhkar.items(page.category, null).indexOfFirst { it.id == page.dhikr.id }
        if (i >= 0) adhkar.goTo(i)
    }

    // ---------------------------------------------------------------- slides on the main screen

    /** A short dhikr for the main screen's next slide, from the loop's collections, not one of the last few shown. */
    fun slideDhikr(): Pair<Dhikr, AdhkarCategory>? {
        val s = store.current.loop
        if (s != slideKey) {
            slideCandidates = AdhkarLoop.slideCandidates(adhkar.book, s)
            slideKey = s
        }
        val pick = AdhkarLoop.pick(slideCandidates, recentSlides, random) ?: return null
        recentSlides.addLast(pick.first.id)
        while (recentSlides.size > RECENT_SLIDES) recentSlides.removeFirst()
        return pick
    }

    // ---------------------------------------------------------------- keys and remote

    /**
     * IR remote: Page/Channel up and down switch pages; on the adhkar display ◀ ▶ move through the loop (mirrored in
     * Arabic). Everything else goes on to the adhkar reader's keys and the settings.
     */
    fun onKey(keyCode: Int): Boolean {
        val st = last ?: return false
        if (!pagesOnScreen(st) || !st.settings.loop.enabled) return false
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                show(if (_page.value == FramePage.MAIN) FramePage.ADHKAR else FramePage.MAIN, "key")
            else -> {
                if (!loopOnScreen(st)) return false
                val rtl = st.settings.display.language == "ar"
                when (keyCode) {
                    if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT -> next()
                    if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT -> previous()
                    else -> return false
                }
            }
        }
        return true
    }

    /** page.show / loop.next / loop.previous (REMOTE.md). Runs on the main thread. */
    fun rpc(cmd: String, args: JSONObject): JSONObject {
        when (cmd) {
            "page.show" -> {
                val page = FramePage.of(args.optString("page")) ?: throw Rpc.Failure("bad_request", "page is main or adhkar")
                if (page == FramePage.ADHKAR && !store.current.loop.enabled) throw Rpc.Failure("bad_request", "the adhkar display is off (loop.enabled)")
                adhkar.close("remote page")
                touch()
                show(page, "remote")
            }
            "loop.next" -> next()
            "loop.previous" -> previous()
            else -> throw Rpc.Failure("unknown_cmd", cmd)
        }
        return JSONObject()
    }

    /** The `loop` object of the remote `state`: from the last playlist built (state is read off the main thread). */
    fun stateJson(): JSONObject {
        val pos = _position.value
        val list = playlist
        val p = list.getOrNull(pos.index)
        return JSONObject()
            .put("page", _page.value.id)
            .put("collections", org.json.JSONArray(AdhkarLoop.collections(store.current.loop, adhkar.current.value).map { it.id }))
            .put("index", pos.index).put("total", list.size).put("round", pos.cycle)
            .put("current", p?.let {
                JSONObject().put("id", it.dhikr.id).put("category", it.category.id).put("part", it.part).put("parts", it.parts)
                    .put("meaning", it.meaning).put("seconds", it.millis / 1000.0)
            } ?: JSONObject.NULL)
    }

    private companion object {
        const val TAG = "AdhkarLoop"
        const val RECENT_SLIDES = 6
    }
}
