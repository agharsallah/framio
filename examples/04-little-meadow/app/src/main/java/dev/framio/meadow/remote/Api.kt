package dev.framio.meadow.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import dev.framio.meadow.AppState
import dev.framio.meadow.core.Clock
import dev.framio.meadow.core.Family
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.TAG
import dev.framio.meadow.core.Txt
import dev.framio.meadow.hw.Device
import dev.framio.meadow.play.Catalog
import dev.framio.meadow.play.Stickers
import dev.framio.meadow.worlds.habits.DRAWINGS_DIR
import dev.framio.meadow.worlds.habits.RoutineSteps
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** The dashboard's endpoints: the web app, sprites, and the JSON API over [AppState]. */
class Api(private val app: AppState, private val auth: Auth, private val urls: () -> List<String>) {

    private val ctx = app.context
    private val webFiles: Set<String> = (ctx.assets.list("web") ?: emptyArray()).toSet()
    private val drawingsDir get() = File(ctx.filesDir, DRAWINGS_DIR)
    private val version: String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private val router = Router().apply {
        // ---- the page and what the sign-in screen needs
        get("/", public = true) { _, _ -> page() }
        get("/index.html", public = true) { _, _ -> page() }
        get("/{file}", public = true) { _, p -> webFile(p.getValue("file")) }
        get("/sprites/{file}", public = true) { r, p -> sprite(r, p.getValue("file")) }
        post("/api/login", public = true) { r, _ -> login(r) }
        post("/api/logout", public = true) { r, _ ->
            auth.logout(r.cookie(Auth.COOKIE))
            Response.ok().header("Set-Cookie", auth.clearCookie())
        }

        // ---- the API (signed in)
        get("/api/state") { _, _ -> Response.json(state()) }
        put("/api/settings") { r, _ -> settings(r) }
        post("/api/command") { r, _ -> command(r) }
        get("/api/catalog") { _, _ -> Response.json(catalog()).cacheFor(30) }
        get("/api/progress") { _, _ -> Response.json(progress()) }
        get("/api/screenshot") { r, _ -> screenshot(small = r.query["size"] == "small") }

        post("/api/family/voice") { r, _ -> upload(r, Family.Kind.Voice) }
        post("/api/family/photo") { r, _ -> upload(r, Family.Kind.Photo) }
        post("/api/family/name") { r, _ -> nameClip(r) }
        delete("/api/family/name") { _, _ ->
            app.family.nameClip()?.delete()
            Response.ok()
        }
        get("/api/family") { _, _ -> Response.json(family()) }
        delete("/api/family/{id}") { _, p ->
            val id = p.getValue("id")
            if (app.family.items.value.none { it.id == id }) throw HttpException(404, "That message is already gone")
            app.family.remove(id)
            Response.ok()
        }
        get("/family/{file}") { _, p -> familyFile(p.getValue("file")) }

        get("/api/drawings") { _, _ -> Response.json(drawings()) }
        get("/drawings/{file}") { _, p -> Response.file(drawing(p.getValue("file")), "image/png").cacheFor(3600) }
        delete("/drawings/{file}") { _, p -> deleteDrawing(p.getValue("file")) }
        delete("/api/drawings/{file}") { _, p -> deleteDrawing(p.getValue("file")) }
    }

    fun handle(req: Request): Response =
        router.serve(req, { auth.check(it.cookie(Auth.COOKIE)) }) { Log.w(TAG, "dashboard ${req.method} ${req.path} failed", it) }

    // ---- static

    private fun page(): Response = asset("web/index.html")?.let {
        Response.bytes(it, "text/html; charset=utf-8")
            .header("Content-Security-Policy", CSP)
            .header("X-Frame-Options", "DENY")
            .header("Referrer-Policy", "no-referrer")
    } ?: Response.error(404, "Not found")

    private fun webFile(name: String): Response {
        if (name == "index.html" || name !in webFiles || !SafeName.web(name)) throw HttpException(404, "Not found")
        val data = asset("web/$name") ?: throw HttpException(404, "Not found")
        return Response.bytes(data, Http.mime(name)).cacheFor(7 * 86400)
    }

    /** Friends are public (the sign-in screen shows Pip); everything else needs a session. */
    private fun sprite(req: Request, file: String): Response {
        val name = file.removeSuffix(".webp")
        if (!file.endsWith(".webp") || !SafeName.sprite(name)) throw HttpException(404, "Not found")
        if (!name.startsWith("ch_") && !auth.check(req.cookie(Auth.COOKIE))) throw HttpException(401, "Please sign in with the PIN")
        val data = asset("sprites/$name.webp") ?: throw HttpException(404, "No such picture")
        return Response.bytes(data, "image/webp").cacheFor(86400)
    }

    private fun asset(path: String): ByteArray? = try {
        ctx.assets.open(path).use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    // ---- sign in

    private fun login(req: Request): Response {
        val pin = req.json().optString("pin", "")
        return when (val r = auth.login(pin)) {
            is Auth.Result.Ok -> Response.ok().header("Set-Cookie", auth.cookie(r.token))
            is Auth.Result.Wrong -> Response.json(JSONObject().put("error", "That's not the PIN").put("triesLeft", r.triesLeft), 401)
            is Auth.Result.Locked -> Response.json(JSONObject().put("error", "Too many tries").put("retryAfter", r.secondsLeft), 429)
                .header("Retry-After", r.secondsLeft.toString())
        }
    }

    // ---- state, settings, commands

    private fun state(): JSONObject {
        val s = app.settings.value
        val playId = app.playId
        val play = playId?.let(Catalog::play)
        val screen = app.screen
        val world = screen.removePrefix("world:").takeIf { screen.startsWith("world:") }?.let(Catalog::world)
            ?: playId?.let(Catalog::worldOf)
        val unseen = app.family.unseen
        return JSONObject()
            .put("settings", s.toJson()) // toJson never includes the PIN
            .put("gate", app.session.gate.value.name.lowercase())
            .put("screen", screen)
            .put("playId", playId ?: JSONObject.NULL)
            .put("play", play?.let { JSONObject().put("id", it.id).put("title", txt(it.title)).put("icon", it.icon).put("friend", it.friend.id) } ?: JSONObject.NULL)
            .put("world", world?.let { JSONObject().put("id", it.id).put("title", txt(it.title)).put("friend", it.friend.id) } ?: JSONObject.NULL)
            .put("todayMinutes", app.progress.todaySeconds() / 60)
            .put("minutesLeft", app.session.minutesLeft ?: JSONObject.NULL)
            .put("sinceBreakMinutes", app.session.minutesSinceBreak)
            .put("locked", app.session.isLocked)
            .put("unseen", JSONArray(unseen.map { JSONObject().put("id", it.id).put("kind", it.kind.name.lowercase()).put("from", it.from) }))
            .put("stickers", app.progress.stickers().size)
            .put("version", version)
            .put("urls", JSONArray(urls()))
            .put("clockOk", Clock.plausible())
            .put("nowMs", System.currentTimeMillis())
    }

    private fun settings(req: Request): Response {
        val j = req.json()
        app.settings.update { it.merge(j) }
        app.session.refresh() // the gate depends on limits and bedtime
        return Response.json(JSONObject().put("ok", true).put("settings", app.settings.value.toJson()))
    }

    private fun command(req: Request): Response {
        val c = RemoteCommand.parse(req.json(), { Catalog.play(it) != null }, { Catalog.world(it) != null })
        app.commands.send(c)
        return Response.ok()
    }

    // ---- catalogue and progress

    private fun catalog(): JSONObject {
        val worlds = JSONArray()
        for (w in Catalog.worlds) {
            val plays = JSONArray()
            for (p in w.plays) plays.put(
                JSONObject().put("id", p.id).put("title", txt(p.title)).put("about", txt(p.about)).put("minAge", p.minAge)
                    .put("friend", p.friend.id).put("icon", p.icon).put("quiet", p.quiet).put("screenTime", p.countsAsScreenTime),
            )
            worlds.put(
                JSONObject().put("id", w.id).put("title", txt(w.title)).put("friend", w.friend.id).put("color", hex(w.color.toArgb()))
                    .put("quiet", w.quiet).put("props", JSONArray(w.props)).put("plays", plays),
            )
        }
        val steps = JSONArray()
        for (r in RoutineSteps.all) steps.put(
            JSONObject().put("id", r.id).put("title", txt(r.title)).put("morning", r.morning).put("evening", r.evening).put("sprite", r.sprite),
        )
        val friends = JSONArray()
        for (f in Friend.entries) friends.put(JSONObject().put("id", f.id).put("title", f.title.en).put("color", hex(f.argb.toInt())))
        return JSONObject().put("worlds", worlds).put("routineSteps", steps).put("friends", friends).put("stickers", JSONArray(Stickers.all))
    }

    private fun progress(): JSONObject {
        val p = app.progress.toJson()
        val days = Summaries.lastDays(p.optJSONObject("days"), System.currentTimeMillis())
        val skills = Summaries.skills(p.optJSONObject("skills"))
        val plays = Summaries.plays(p.optJSONObject("plays")) { id ->
            val play = Catalog.play(id) ?: return@plays null
            JSONObject().put("title", txt(play.title)).put("icon", play.icon).put("friend", play.friend.id)
                .put("world", Catalog.worldOf(id)?.id ?: "")
        }
        return p.put(
            "summary",
            JSONObject().put("days", days).put("totals", Summaries.totals(days)).put("plays", plays).put("skills", skills)
                .put("highestCount", Summaries.highest(skills.optJSONObject("count")) ?: JSONObject.NULL)
                .put("stickersTotal", Stickers.all.size).put("clockOk", Clock.plausible()),
        )
    }

    // ---- the screen

    private class Shot(val png: ByteArray, val atMs: Long) {
        var jpeg: ByteArray? = null
    }

    private var shot: Shot? = null
    private val shotLock = Any()

    /** One `screencap` (root, about a second) serves everyone asking within [SHOT_CACHE_MS]. */
    private fun screenshot(small: Boolean): Response = synchronized(shotLock) {
        val s = shot?.takeIf { System.currentTimeMillis() - it.atMs < SHOT_CACHE_MS } ?: run {
            val f = File(ctx.cacheDir, "dashboard_shot.png")
            f.delete()
            if (!runBlocking { Device.screenshot(f) }) throw HttpException(503, "The frame couldn't take a picture of its screen")
            val png = f.readBytes()
            f.delete()
            Shot(png, System.currentTimeMillis()).also { shot = it }
        }
        if (small) Response.bytes(s.jpeg ?: thumbnail(s.png).also { s.jpeg = it }, "image/jpeg")
        else Response.bytes(s.png, "image/png")
    }

    /** Half size JPEG (640×400, ~40 KB instead of ~1 MB of PNG) for the phone's thumbnail. */
    private fun thumbnail(png: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            ?: throw HttpException(503, "The screen picture was unreadable")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---- family

    private fun upload(req: Request, kind: Family.Kind): Response {
        val photo = kind == Family.Kind.Photo
        val ext = Http.uploadExt(req.header("content-type"), req.query["name"], photo)
            ?: throw HttpException(415, if (photo) "Please send a JPEG, PNG or WebP photo" else "That doesn't look like a sound file")
        if (req.contentLength == 0L) throw HttpException(400, "The file was empty")
        val item = try {
            app.family.add(kind, req.query["from"].orEmpty(), ext, req.body, MAX_UPLOAD)
        } catch (e: IllegalArgumentException) {
            throw HttpException(413, "That file is too large (max 10 MB)")
        } catch (e: IOException) {
            cleanParts()
            throw HttpException(400, "The upload was interrupted, please try again")
        }
        if (photo && !decodable(item.file)) {
            app.family.remove(item.id)
            throw HttpException(415, "The frame can't open that photo. JPEG or PNG works best.")
        }
        app.progress.log(if (photo) "Photo from ${item.from}" else "Voice message from ${item.from}")
        return Response.json(JSONObject().put("ok", true).put("item", itemJson(item)))
    }

    private fun nameClip(req: Request): Response {
        val ext = Http.uploadExt(req.header("content-type"), req.query["name"], photo = false)
            ?: throw HttpException(415, "That doesn't look like a sound file")
        if (req.contentLength == 0L) throw HttpException(400, "The file was empty")
        try {
            app.family.setNameClip(ext, req.body, MAX_UPLOAD)
        } catch (e: IllegalArgumentException) {
            throw HttpException(413, "That file is too large (max 10 MB)")
        } catch (e: IOException) {
            cleanParts()
            throw HttpException(400, "The upload was interrupted, please try again")
        }
        return Response.json(JSONObject().put("ok", true).put("name", nameJson()))
    }

    private fun family(): JSONObject = JSONObject()
        .put("items", JSONArray(app.family.items.value.map(::itemJson)))
        .put("name", nameJson())

    private fun itemJson(it: Family.Item): JSONObject = it.toJson().put("url", "/family/${it.file.name}")

    private fun nameJson(): Any = app.family.nameClip()?.let {
        JSONObject().put("file", it.name).put("url", "/family/${it.name}").put("bytes", it.length()).put("ms", it.lastModified())
    } ?: JSONObject.NULL

    /** Only files the family index (or the name clip) points at: never index.json or anything else. */
    private fun familyFile(name: String): Response {
        val known = app.family.items.value.map { it.file.name } + listOfNotNull(app.family.nameClip()?.name)
        if (!SafeName.family(name) || name !in known) throw HttpException(404, "Not found")
        val f = SafeName.inside(app.family.dir, name) ?: throw HttpException(404, "Not found")
        return Response.file(f, Http.mime(name)).cacheFor(3600)
    }

    private fun decodable(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun cleanParts() {
        app.family.dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    // ---- drawings

    private fun drawings(): JSONArray {
        val files = drawingsDir.listFiles { f -> f.isFile && SafeName.drawing(f.name) }.orEmpty().sortedByDescending { it.lastModified() }
        return JSONArray(files.map { JSONObject().put("file", it.name).put("url", "/drawings/${it.name}").put("ms", it.lastModified()).put("bytes", it.length()) })
    }

    private fun drawing(name: String): File {
        if (!SafeName.drawing(name)) throw HttpException(404, "Not found")
        return SafeName.inside(drawingsDir, name) ?: throw HttpException(404, "That painting is gone")
    }

    private fun deleteDrawing(name: String): Response {
        drawing(name).delete()
        return Response.ok()
    }

    private fun txt(t: Txt) = JSONObject().put("en", t.en).put("de", t.de)

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

    companion object {
        const val MAX_UPLOAD = 10L * 1024 * 1024
        private const val SHOT_CACHE_MS = 3000L
        private const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; font-src 'self'; " +
            "img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}

/** File names the dashboard may serve. Each pattern rules out "/", "\" and "..", so no path can leave its folder. Pure, unit-tested. */
object SafeName {
    private val WEB = Regex("^[a-z0-9][a-z0-9_-]{0,63}\\.(html|ttf|woff2?|css|js|svg|png|webp|ico)$")
    private val SPRITE = Regex("^[a-z0-9][a-z0-9_]{0,79}$")
    private val FAMILY = Regex("^[a-z0-9]{1,24}\\.[a-z0-9]{1,5}$")
    private val DRAWING = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\.png$")

    fun web(name: String) = WEB.matches(name)
    fun sprite(name: String) = SPRITE.matches(name)
    fun family(name: String) = FAMILY.matches(name)
    fun drawing(name: String) = DRAWING.matches(name)

    /** [dir]/[name] if it exists and really is directly inside [dir] (no links out). */
    fun inside(dir: File, name: String): File? {
        val f = File(dir, name)
        return try {
            if (f.isFile && f.canonicalFile.parentFile == dir.canonicalFile) f else null
        } catch (_: IOException) {
            null
        }
    }
}
