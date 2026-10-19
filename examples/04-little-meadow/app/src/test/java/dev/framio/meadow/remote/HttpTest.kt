package dev.framio.meadow.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class HttpTest {

    private fun parse(raw: String, maxBody: Long = 1024, onContinue: () -> Unit = {}): Request? =
        Http.parse(BufferedInputStream(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))), maxBody, onContinue = onContinue)

    private fun status(raw: String, maxBody: Long = 1024): Int = try {
        parse(raw, maxBody)
        fail("expected an HttpException")
        0
    } catch (e: HttpException) {
        e.status
    }

    @Test fun `parses request line, query and lower-case headers`() {
        val r = parse("GET /api/family?from=Grandma%20Rose&x=a+b HTTP/1.1\r\nHost: frame\r\nCookie: a=1; meadow=tok\r\n\r\n")!!
        assertEquals("GET", r.method)
        assertEquals("/api/family", r.path)
        assertEquals("Grandma Rose", r.query["from"])
        assertEquals("a b", r.query["x"])
        assertEquals("frame", r.header("HOST"))
        assertEquals("tok", r.cookie("meadow"))
        assertNull(r.cookie("nope"))
    }

    @Test fun `accepts bare LF line endings`() {
        val r = parse("GET / HTTP/1.0\nHost: x\n\n")!!
        assertEquals("/", r.path)
    }

    @Test fun `empty connection gives null`() {
        assertNull(parse(""))
    }

    @Test fun `body stops at Content-Length`() {
        val r = parse("POST /api/login HTTP/1.1\r\nContent-Length: 12\r\n\r\n{\"pin\":\"1234\"}GARBAGE")!!
        assertEquals("{\"pin\":\"1234", r.text())
    }

    @Test fun `json body`() {
        val r = parse("PUT /api/settings HTTP/1.1\r\nContent-Length: 9\r\n\r\n{\"age\":4}")!!
        assertEquals(4, r.json().getInt("age"))
    }

    @Test fun `invalid json is a 400`() {
        val r = parse("PUT /api/settings HTTP/1.1\r\nContent-Length: 3\r\n\r\n{x]")!!
        try {
            r.json()
            fail()
        } catch (e: HttpException) {
            assertEquals(400, e.status)
        }
    }

    @Test fun `a body cut short is an error, not a short file`() {
        val r = parse("POST /api/family/voice HTTP/1.1\r\nContent-Length: 100\r\n\r\nonly a little")!!
        try {
            r.body.readBytes()
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }

    @Test fun `100-continue is sent when the body is first read`() {
        var sent = 0
        val r = parse("POST /x HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nhi", onContinue = { sent++ })!!
        assertEquals(0, sent)
        assertEquals("hi", r.text())
        assertEquals(1, sent)
    }

    @Test fun `limits and malformed requests`() {
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: 2000\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("GET /\r\n\r\n"))
        assertEquals(400, status("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("get / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET /%zz HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a%00b HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: x\r\n")) // ends mid-headers
    }

    @Test fun `headers larger than 16 KB are refused`() {
        val big = "X-Big: " + "a".repeat(Http.MAX_HEADER) + "\r\n"
        assertEquals(431, status("GET / HTTP/1.1\r\n$big\r\n"))
    }

    @Test fun `slow clients hit the header deadline`() {
        var t = 0L
        try {
            Http.parse(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".toByteArray()), 0, deadlineMs = 5, now = { t++ })
            fail()
        } catch (e: HttpException) {
            assertEquals(408, e.status)
        }
    }

    @Test fun `percent-decoding keeps dot-dot visible for the name checks`() {
        val r = parse("GET /family/%2e%2e%2fsettings.json HTTP/1.1\r\n\r\n")!!
        assertEquals("/family/../settings.json", r.path)
        assertEquals("ä", Http.decode("%C3%A4", plus = false))
    }

    @Test fun `writes a response with length and close`() {
        val out = ByteArrayOutputStream()
        Http.write(out, Response.bytes("hello".toByteArray(), "text/plain").header("Set-Cookie", "a=b"))
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 5\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.contains("Cache-Control: no-store\r\n"))
        assertTrue(text.contains("Set-Cookie: a=b\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhello"))
    }

    @Test fun `HEAD writes no body`() {
        val out = ByteArrayOutputStream()
        Http.write(out, Response.bytes("hello".toByteArray(), "text/plain"), head = true)
        assertTrue(out.toString("ISO-8859-1").endsWith("Content-Length: 5\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n\r\n"))
    }

    @Test fun `header values cannot inject lines`() {
        try {
            Http.write(ByteArrayOutputStream(), Response.ok().header("X", "a\r\nSet-Cookie: evil"))
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `upload extensions from type or file name`() {
        assertEquals("jpg", Http.uploadExt("image/jpeg", null, photo = true))
        assertEquals("png", Http.uploadExt("application/octet-stream", "Photo.PNG", photo = true))
        assertNull(Http.uploadExt("image/heic", "IMG_1.HEIC", photo = true))
        assertEquals("m4a", Http.uploadExt("audio/x-m4a", null, photo = false))
        assertEquals("webm", Http.uploadExt("audio/webm;codecs=opus", null, photo = false))
        assertEquals("mp3", Http.uploadExt("", "night.mp3", photo = false))
        assertNull(Http.uploadExt("text/html", "x.html", photo = false))
        assertNull(Http.uploadExt("image/jpeg", null, photo = false))
    }

    @Test fun `continue line`() {
        val out = ByteArrayOutputStream()
        Http.writeContinue(out)
        assertArrayEquals("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(), out.toByteArray())
    }
}
