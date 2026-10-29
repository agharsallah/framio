package dev.framio.mawaqit.remote

import android.os.SystemClock
import android.util.Log
import dev.framio.mawaqit.BuildInfo
import dev.framio.mawaqit.audio.CustomAdhans
import dev.framio.mawaqit.core.AdhanFiles
import dev.framio.mawaqit.core.AdhanSettings
import dev.framio.mawaqit.core.Effective
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.SourceSettings
import dev.framio.mawaqit.data.Net
import dev.framio.mawaqit.data.Repository
import dev.framio.mawaqit.data.SettingsStore
import dev.framio.mawaqit.engine.Engine
import dev.framio.mawaqit.system.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** The commands of the remote panel (see REMOTE.md), whatever carried them: LAN HTTP or ntfy. */
class Rpc(
    private val store: SettingsStore,
    private val repo: Repository,
    private val engine: Engine,
    private val scope: CoroutineScope,
    /** The user's own adhans; also used by [HttpServer] for the upload and file routes. */
    val adhans: CustomAdhans,
) {

    class Failure(val code: String, message: String = code) : Exception(message)

    @Volatile var hasRoot: Boolean = false

    /** @param lan false for ntfy: LAN-only commands are refused there */
    suspend fun call(cmd: String, args: JSONObject, lan: Boolean): JSONObject = when (cmd) {
        "state" -> state()
        "settings.get" -> JSONObject().put("settings", store.current.toJson())
        "settings.patch" -> {
            val patch = args.optJSONObject("patch") ?: throw Failure("bad_request", "patch must be an object")
            patch.optJSONObject("adhan")?.remove("custom") // owned by the adhan.* commands: a stale copy can't drop files
            Log.i(TAG, "settings.patch ${patch.keys().asSequence().toList()} (${if (lan) "lan" else "ntfy"})")
            JSONObject().put("settings", store.patch(patch).toJson())
        }
        "mosque.check" -> {
            val slug = SourceSettings.mosqueSlug(args.optString("mosque"))
            if (slug.isEmpty()) throw Failure("bad_request", "no mosque given")
            val m = try {
                repo.check(slug)
            } catch (e: Net.HttpException) {
                throw Failure(if (e.code == 404) "not_found" else "network", e.message ?: "")
            } catch (e: IOException) {
                throw Failure("network", e.message ?: "")
            } catch (e: Exception) {
                throw Failure("not_found", "no Mawaqit mosque at this address")
            }
            JSONObject().put("slug", slug).put("name", m.name).put("address", m.address ?: JSONObject.NULL).put("zone", m.zone.id)
        }
        "mosque.search" -> {
            val token = store.current.source.apiToken
            if (token.isEmpty()) throw Failure("token_required", "add a MAWAQIT API token first")
            val lat = args.optDouble("lat").takeIf { !it.isNaN() }
            val lon = args.optDouble("lon").takeIf { !it.isNaN() }
            val hits = try {
                repo.search(token, args.optString("word").takeIf { it.isNotBlank() }, lat, lon)
            } catch (e: Net.HttpException) {
                throw Failure(if (e.code == 401 || e.code == 403) "token_required" else "network", "MAWAQIT answered ${e.code}")
            } catch (e: IOException) {
                throw Failure("network", e.message ?: "")
            }
            JSONObject().put("mosques", JSONArray(hits.map { h ->
                JSONObject().put("slug", h.slug ?: JSONObject.NULL).put("name", h.name).put("address", h.address ?: JSONObject.NULL)
                    .put("distanceM", h.distanceM ?: JSONObject.NULL)
            }))
        }
        "refresh" -> ok {
            repo.refresh()
            engine.secondary.refresh()
        }
        "adhan.test" -> {
            val prayer = PrayerKey.of(args.optString("prayer", "dhuhr")) ?: PrayerKey.DHUHR
            val voice = args.optString("voice")
            ok { if (voice.isEmpty()) engine.testAdhan(prayer) else testVoice(voice, prayer) }
        }
        "adhan.list" -> adhanList()
        "adhan.fetch" -> {
            val url = args.optString("url").trim()
            if (url.isEmpty()) throw Failure("bad_request", "url is empty")
            Log.i(TAG, "adhan.fetch (${if (lan) "lan" else "ntfy"})")
            adhanCall { withContext(Dispatchers.IO) { adhans.fetch(url, args.optString("name")) } }.toJson()
        }
        "adhan.rename" -> {
            adhanCall { adhans.rename(args.optString("id"), args.optString("name")) }
            adhanList()
        }
        "adhan.delete" -> {
            adhanCall { adhans.delete(args.optString("id")) }
            adhanList()
        }
        "audio.stop" -> ok { engine.player.stop() }
        // the adhan's controls (as on its screen): skip it, its volume now (kept for the next ones), mute it
        "adhan.skip" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            JSONObject().put("skipped", engine.skipAdhan())
        }
        "adhan.volume" -> {
            val volume = if (args.has("volume")) args.optInt("volume", -1).takeIf { it in 0..100 } ?: throw Failure("bad_request", "volume is 0–100")
            else if (args.has("delta")) (engine.player.playing.value?.volume ?: store.current.adhan.volume) + args.optInt("delta")
            else null
            val muted = if (args.has("muted")) args.optBoolean("muted") else null
            if (volume == null && muted == null) throw Failure("bad_request", "give volume, delta or muted")
            val playing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { engine.adhanVolume(volume, muted) }
            // nothing playing: a volume is still the adhan volume for the next ones
            if (!playing && volume != null) store.patch(JSONObject().put("adhan", JSONObject().put("volume", volume.coerceIn(0, 100))))
            JSONObject().put("playing", playing).put("volume", engine.player.playing.value?.volume ?: store.current.adhan.volume)
        }
        "message.show" -> {
            val text = args.optString("text").trim()
            if (text.isEmpty()) throw Failure("bad_request", "text is empty")
            ok { engine.showMessage(args.optString("title").takeIf { it.isNotBlank() }, text.take(1200), args.optInt("seconds", 30).coerceIn(5, 3600)) }
        }
        "phase.preview" -> {
            val kind = PhaseKind.of(args.optString("kind")) ?: throw Failure("bad_request", "unknown kind")
            ok { engine.preview(kind, args.optInt("seconds", 20).coerceIn(3, 600)) }
        }
        "screen" -> {
            val mode = args.optString("mode")
            if (mode !in listOf("on", "off", "auto")) throw Failure("bad_request", "mode is on, off or auto")
            ok { engine.setScreenMode(mode) }
        }
        "open" -> when (args.optString("app")) {
            "display" -> ok { engine.bringDisplay() }
            "photos" -> ok { engine.openPhotos() }
            else -> throw Failure("bad_request", "app is display or photos")
        }
        "remote.info" -> if (lan) remoteInfo() else throw Failure("lan_only")
        "remote.rotate" -> if (lan) {
            store.rotateRemote()
            remoteInfo()
        } else throw Failure("lan_only")
        "adhkar.open", "adhkar.close", "adhkar.dismiss", "adhkar.reset" ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { engine.adhkar.rpc(cmd, args) }
        // the log and the reminder schedules (data/AdhkarController.kt, REMOTE.md)
        "adhkar.history", "adhkar.schedule.list", "adhkar.schedule.add", "adhkar.schedule.remove", "adhkar.push.test",
        "adhkar.history.seed", "adhkar.history.unseed" ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { engine.adhkar.rpc(cmd, args) }
        "page.show", "loop.next", "loop.previous" ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { engine.loop.rpc(cmd, args) }
        "slide.adhkar" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (!engine.showDhikrSlide()) throw Failure("not_found", "no short dhikr in the adhkar display's collections")
            JSONObject()
        }
        // the presence policy (engine/PresenceController.kt): back now as by hand, or away now
        "presence.wake" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            engine.presence.touch()
            engine.bringDisplay()
            JSONObject()
        }
        "presence.away" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (!store.current.presence.enabled) throw Failure("bad_request", "presence.enabled is false")
            engine.presence.goAwayNow()
            JSONObject()
        }
        "device.reboot" -> {
            if (!hasRoot) throw Failure("no_root")
            Log.w(TAG, "reboot requested (${if (lan) "lan" else "ntfy"})")
            ok {
                scope.launch {
                    delay(1500) // answer the request first
                    Device.reboot()
                }
            }
        }
        else -> throw Failure("unknown_cmd", "unknown command $cmd")
    }

    private inline fun ok(block: () -> Unit): JSONObject {
        block()
        return JSONObject()
    }

    private inline fun <T> adhanCall(block: () -> T): T = try {
        block()
    } catch (e: CustomAdhans.Rejected) {
        throw Failure(e.code, e.message ?: e.code)
    }

    private fun adhanList(): JSONObject {
        val a = store.current.adhan
        return JSONObject().put("custom", JSONArray(a.custom.map { it.toJson() }))
            .put("inUse", JSONObject().put("voice", a.voice).put("fajrVoice", a.fajrVoice))
    }

    /** Plays [voice] ("custom:<id>", a Mawaqit voice, "beep" or "mosque") as [prayer] would, settings unchanged. */
    private fun testVoice(voice: String, prayer: PrayerKey) {
        val s = store.current
        val known = voice == "mosque" || voice == "beep" || voice.removeSuffix("-fajr") in AdhanSettings.VOICES || AdhanFiles.find(s.adhan, voice) != null
        if (!known) throw Failure("not_found", "unknown voice $voice")
        val trial = s.copy(adhan = s.adhan.copy(voice = voice, fajrVoice = "same"))
        engine.player.play(Effective.adhanSound(trial, repo.state.value.mosque, prayer), s.adhan.volume, "test:$voice")
    }

    /** The remote link carries the key in its #fragment, which browsers never send to a server. */
    fun remoteInfo(): JSONObject {
        val r = store.current.remote
        val base = r.panelUrl.ifEmpty { Device.ipAddress()?.let { "http://$it:${r.port}/" } ?: "" }
        val link = base + "#server=" + enc(r.ntfyServer) + "&topic=" + enc(r.ntfyTopic) + "&key=" + enc(r.ntfyKey)
        return JSONObject().put("ntfyEnabled", r.ntfyEnabled).put("ntfyServer", r.ntfyServer).put("ntfyTopic", r.ntfyTopic)
            .put("ntfyKey", r.ntfyKey).put("link", link)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun state(): JSONObject {
        val st = engine.state.value
        val src = st.source
        val s = st.settings
        fun t(p: PrayerTime) = JSONObject().put("prayer", p.key.id).put("adhan", p.adhan.toInstant().toEpochMilli())
            .put("iqama", p.iqama?.toInstant()?.toEpochMilli() ?: JSONObject.NULL)
        val today = st.today?.let { d ->
            listOfNotNull(d.imsak, d.fajr, d.shuruq, d.dhuhr, d.asr, d.maghrib, d.isha) + d.jumua + d.eid
        }.orEmpty().sortedBy { it.adhan }
        return JSONObject()
            .put("app", "framio-mawaqit").put("version", BuildInfo.VERSION)
            .put("now", System.currentTimeMillis()).put("zone", st.now.zone.id).put("timeSynced", st.synced)
            .put("device", JSONObject().put("ip", Device.ipAddress() ?: JSONObject.NULL).put("port", s.remote.port)
                .put("uptimeMs", SystemClock.elapsedRealtime()).put("root", hasRoot))
            .put("source", JSONObject().put("kind", s.source.kind).put("mosque", s.source.mosque)
                .put("name", src.mosque?.name ?: s.source.placeName.takeIf { s.source.kind == "calc" } ?: JSONObject.NULL)
                .put("address", src.mosque?.address ?: JSONObject.NULL)
                .put("updatedAt", src.updatedAt ?: JSONObject.NULL).put("error", src.error ?: JSONObject.NULL))
            .put("phase", JSONObject().put("kind", st.phase.kind.id).put("prayer", st.phase.prayer?.key?.id ?: JSONObject.NULL)
                .put("end", st.phase.end?.toInstant()?.toEpochMilli() ?: JSONObject.NULL))
            .put("next", st.next?.let(::t) ?: JSONObject.NULL)
            .put("today", JSONArray(today.map(::t)))
            .put("hijri", st.hijri ?: JSONObject.NULL)
            .put("screen", JSONObject().put("mode", st.screen.mode).put("state", st.screen.state))
            .put("foreground", if (engine.displayVisible) "display" else "other")
            .put("weather", st.weather?.let { JSONObject().put("temp", it.temperature).put("unit", it.unit) } ?: JSONObject.NULL)
            .put("audio", engine.player.playing.value.let { a ->
                JSONObject().put("playing", a != null).put("what", a?.what ?: JSONObject.NULL)
                    .put("volume", a?.volume ?: s.adhan.volume).put("muted", a?.muted == true)
            })
            .put("adhkar", engine.adhkar.stateJson())
            .put("page", engine.loop.page.value.id)
            .put("presence", engine.presence.stateJson(st, engine.adhkar.nextReminder()?.at))
            .put("loop", engine.loop.stateJson())
            .put("slide", when (val sl = st.slide) {
                null -> JSONObject.NULL
                is dev.framio.mawaqit.engine.Slide.OfHadith -> JSONObject().put("kind", "hadith")
                is dev.framio.mawaqit.engine.Slide.OfAnnouncement -> JSONObject().put("kind", "announcement")
                dev.framio.mawaqit.engine.Slide.OfMosques -> JSONObject().put("kind", "mosques")
                is dev.framio.mawaqit.engine.Slide.OfDhikr -> JSONObject().put("kind", "adhkar").put("id", sl.dhikr.id).put("category", sl.category.id)
            })
            .put("secondary", JSONArray(st.secondary.map { e ->
                JSONObject().put("slug", e.slug).put("name", e.name).put("next", e.next?.let(::t) ?: JSONObject.NULL)
                    .put("updatedAt", e.updatedAt ?: JSONObject.NULL).put("error", e.error ?: JSONObject.NULL)
            }))
    }

    private companion object {
        const val TAG = "Rpc"
    }
}
