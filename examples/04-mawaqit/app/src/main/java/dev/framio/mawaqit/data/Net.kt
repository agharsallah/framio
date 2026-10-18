package dev.framio.mawaqit.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Plain HttpURLConnection: no HTTP library to keep in RAM on a 1 GB frame. Call from Dispatchers.IO. */
object Net {

    // mawaqit.net sits behind Cloudflare, which is kinder to a browser-looking agent
    private const val UA = "Mozilla/5.0 (Linux; Android 6.0.1; F10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107 Safari/537.36 framio-mawaqit/0.1"

    class HttpException(val code: Int, message: String) : IOException(message)

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): String =
        open(url, "GET", headers, timeoutMs).use { it.bufferedReader().readText() }

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): String {
        val c = connection(url, "POST", headers, timeoutMs)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return response(c).use { it.bufferedReader().readText() }
    }

    /** Downloads to [target] atomically (a .part file renamed at the end), so a cut connection never leaves half a file. */
    fun download(url: String, target: File, timeoutMs: Int = 60_000) {
        val part = File(target.path + ".part")
        open(url, "GET", emptyMap(), timeoutMs).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(target)) throw IOException("rename failed: $target")
    }

    /** A connection whose body the caller reads line by line (ntfy's JSON stream). [readTimeoutMs] > keepalive interval. */
    fun stream(url: String, readTimeoutMs: Int): HttpURLConnection {
        val c = connection(url, "GET", emptyMap(), 15_000)
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("Accept-Encoding", "identity") // gzip would buffer the stream
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw HttpException(code, "HTTP $code for $url")
        }
        return c
    }

    private fun open(url: String, method: String, headers: Map<String, String>, timeoutMs: Int) =
        response(connection(url, method, headers, timeoutMs))

    private fun connection(url: String, method: String, headers: Map<String, String>, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs.coerceAtMost(15_000)
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "gzip")
        for ((k, v) in headers) c.setRequestProperty(k, v) // not Map.forEach: that one is API 24
        return c
    }

    private fun response(c: HttpURLConnection): java.io.InputStream {
        val code = c.responseCode
        if (code !in 200..299) {
            c.errorStream?.close()
            c.disconnect()
            throw HttpException(code, "HTTP $code for ${c.url}")
        }
        val body = c.inputStream
        return if (c.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(body) else body
    }
}
