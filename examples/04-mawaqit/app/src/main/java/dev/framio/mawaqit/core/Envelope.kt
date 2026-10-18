package dev.framio.mawaqit.core

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of the messages sent through a public ntfy topic.
 *
 *     "fx1." + base64url( iv[12] ‖ AES-256-GCM(key, iv, aad = topic, rawDeflate(utf8(json))) ‖ tag[16] )
 *
 * The topic is the additional data, so a message can't be replayed onto another topic. Raw deflate (no zlib
 * header) is what the browser's `CompressionStream("deflate-raw")` writes: the web panel mirrors this exactly.
 * Whoever knows only the topic sees opaque text; tampering fails the GCM tag.
 */
object Envelope {
    const val PREFIX = "fx1."
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** A new 256-bit key, as base64url without padding (43 characters). */
    fun newKey(): String = Base64Url.encode(ByteArray(32).also(random::nextBytes))

    fun seal(json: String, key: String, topic: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, topic)
        return PREFIX + Base64Url.encode(iv + cipher.doFinal(deflate(json.toByteArray(Charsets.UTF_8))))
    }

    /** The JSON text, or null when the message isn't ours, was altered, or used another key or topic. */
    fun open(message: String, key: String, topic: String): String? {
        if (!message.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64Url.decode(message.substring(PREFIX.length)) ?: return null
            if (bytes.size < IV_BYTES + TAG_BITS / 8) return null
            val cipher = cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, IV_BYTES), topic)
            inflate(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))?.toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun cipher(mode: Int, key: String, iv: ByteArray, topic: String): Cipher {
        val raw = Base64Url.decode(key)
        require(raw != null && raw.size == 32) { "key must be 32 bytes of base64url" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(raw, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(topic.toByteArray(Charsets.UTF_8))
        }
    }

    private fun deflate(input: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(input)
        d.finish()
        val out = ByteArrayOutputStream(input.size / 2 + 64)
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** At most 1 MiB out: a compressed message can't blow up the frame's memory. */
    private fun inflate(input: ByteArray): ByteArray? {
        val i = Inflater(true)
        i.setInput(input)
        val out = ByteArrayOutputStream(input.size * 3)
        val buf = ByteArray(4096)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 1 shl 20) return null
            }
        } catch (_: java.util.zip.DataFormatException) {
            return null
        } finally {
            i.end()
        }
        return if (i.finished()) out.toByteArray() else null
    }
}

/** RFC 4648 base64url without padding. android.util.Base64 isn't available in JVM tests, java.util.Base64 needs API 26. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[(b0 and 3) shl 4 or (b1 shr 4)])
            if (i + 1 < bytes.size) sb.append(ALPHABET[(b1 and 15) shl 2 or (b2 shr 6)])
            if (i + 2 < bytes.size) sb.append(ALPHABET[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    /** Also accepts standard base64 (+ / and = padding). null if not base64. */
    fun decode(text: String): ByteArray? {
        val s = text.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        if (s.length % 4 == 1) return null
        val out = ByteArray(s.length * 3 / 4)
        var acc = 0
        var bits = 0
        var n = 0
        for (c in s) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            if (v < 0) return null
            acc = acc shl 6 or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits and 0xFF).toByte()
            }
        }
        return out
    }
}
