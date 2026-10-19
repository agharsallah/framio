package dev.framio.meadow.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class RouterTest {

    private fun req(method: String, path: String) = Request(method, path, emptyMap(), emptyMap(), 0, ByteArrayInputStream(ByteArray(0)))

    private fun text(r: Response) = String(r.body)

    private val router = Router().apply {
        get("/", public = true) { _, _ -> Response.bytes("page".toByteArray(), "text/html") }
        get("/api/state") { _, _ -> Response.bytes("state".toByteArray(), "application/json") }
        delete("/api/family/name") { _, _ -> Response.bytes("name".toByteArray(), "text/plain") }
        delete("/api/family/{id}") { _, p -> Response.bytes("del ${p["id"]}".toByteArray(), "text/plain") }
        get("/boom") { _, _ -> throw IllegalStateException("x") }
        get("/teapot") { _, _ -> throw HttpException(415, "No tea") }
    }

    @Test fun `matches literal and parameter routes in order`() {
        val m = router.match("DELETE", "/api/family/abc12") as Router.Match.Found
        assertEquals(mapOf("id" to "abc12"), m.params)
        assertEquals("name", text(router.serve(req("DELETE", "/api/family/name"), { true })))
        assertEquals("del abc12", text(router.serve(req("DELETE", "/api/family/abc12"), { true })))
    }

    @Test fun `trailing slash and root`() {
        assertTrue(router.match("GET", "/api/state/") is Router.Match.Found)
        assertTrue(router.match("GET", "/") is Router.Match.Found)
        assertEquals(Router.Match.NotFound, router.match("GET", "/api/state/extra"))
        assertEquals(Router.Match.NotFound, router.match("DELETE", "/api/family/"))
    }

    @Test fun `HEAD is GET`() {
        assertTrue(router.match("HEAD", "/api/state") is Router.Match.Found)
    }

    @Test fun `wrong method is 405 with Allow`() {
        val r = router.serve(req("POST", "/api/state"), { true })
        assertEquals(405, r.status)
        assertEquals("GET", r.headers["Allow"])
    }

    @Test fun `private routes need a session, public ones don't`() {
        assertEquals(401, router.serve(req("GET", "/api/state"), { false }).status)
        assertEquals(200, router.serve(req("GET", "/api/state"), { true }).status)
        assertEquals(200, router.serve(req("GET", "/"), { false }).status)
        assertEquals(404, router.serve(req("GET", "/nope"), { false }).status)
    }

    @Test fun `errors become friendly JSON`() {
        var logged: Exception? = null
        val boom = router.serve(req("GET", "/boom"), { true }) { logged = it }
        assertEquals(500, boom.status)
        assertTrue(logged is IllegalStateException)
        val tea = router.serve(req("GET", "/teapot"), { true })
        assertEquals(415, tea.status)
        assertEquals("No tea", JSONObject(text(tea)).getString("error"))
    }

    @Test fun `safe names reject traversal`() {
        assertTrue(SafeName.family("lq3k2x.m4a"))
        assertFalse(SafeName.family("../settings.json"))
        assertFalse(SafeName.family("a/b.jpg"))
        assertTrue(SafeName.drawing("drawing_1712345678.png"))
        assertFalse(SafeName.drawing("..png"))
        assertFalse(SafeName.drawing("../../x.png"))
        assertFalse(SafeName.drawing("x.jpg"))
        assertTrue(SafeName.sprite("ch_pip_wave"))
        assertFalse(SafeName.sprite("../voice/en/manifest"))
        assertTrue(SafeName.web("nunito_600.ttf"))
        assertFalse(SafeName.web(".hidden.ttf"))
        assertFalse(SafeName.web("x.kt"))
    }

    @Test fun `inside only finds direct children`() {
        val dir = Files.createTempDirectory("meadow").toFile()
        val sub = File(dir, "drawings").apply { mkdirs() }
        File(sub, "a.png").writeText("x")
        File(dir, "secret.png").writeText("s")
        assertTrue(SafeName.inside(sub, "a.png") != null)
        assertNull(SafeName.inside(sub, "../secret.png"))
        assertNull(SafeName.inside(sub, "missing.png"))
        dir.deleteRecursively()
    }
}
