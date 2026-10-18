package dev.framio.mawaqit.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.zip.Deflater

class EnvelopeTest {

    private val key = Envelope.newKey()
    private val topic = "framio-abcdefghijklmnop"

    @Test
    fun roundTrip() {
        val json = """{"v":1,"t":"rpc","id":"abc123456789","cmd":"state","text":"الأذان ✓"}"""
        val sealed = Envelope.seal(json, key, topic)
        assertTrue(sealed.startsWith("fx1."))
        assertEquals(json, Envelope.open(sealed, key, topic))
    }

    @Test
    fun sameMessageSealsDifferentlyEachTime() {
        assertNotEquals(Envelope.seal("{}", key, topic), Envelope.seal("{}", key, topic)) // fresh IV
    }

    @Test
    fun keyIs32BytesOfBase64Url() {
        assertEquals(43, key.length)
        assertEquals(32, Base64Url.decode(key)!!.size)
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun otherTopicOrKeyCannotOpen() {
        val sealed = Envelope.seal("{}", key, topic)
        assertNull(Envelope.open(sealed, key, "$topic-other"))
        assertNull(Envelope.open(sealed, Envelope.newKey(), topic))
    }

    @Test
    fun anyAlteredCharacterIsRejected() {
        val sealed = Envelope.seal("""{"cmd":"device.reboot"}""", key, topic)
        for (i in Envelope.PREFIX.length until sealed.length) {
            val c = sealed[i]
            val other = if (c == 'A') 'B' else 'A'
            val altered = sealed.substring(0, i) + other + sealed.substring(i + 1)
            assertNull("position $i", Envelope.open(altered, key, topic))
        }
    }

    @Test
    fun foreignTextIsIgnored() {
        assertNull(Envelope.open("hello from someone else on the topic", key, topic))
        assertNull(Envelope.open("fx1.", key, topic))
        assertNull(Envelope.open("fx1.!!!!", key, topic))
    }

    @Test
    fun compressesSettingsSizedPayloadsUnderNtfyLimit() {
        val settings = Settings().toJson().toString()
        val sealed = Envelope.seal("""{"v":1,"t":"res","ok":true,"result":{"settings":$settings}}""", key, topic)
        assertTrue("sealed ${sealed.length} bytes", sealed.length < 4096)
    }

    @Test
    fun base64UrlMatchesRfc4648Vectors() {
        // RFC 4648 §10, url-safe alphabet, no padding
        val vectors = mapOf("" to "", "f" to "Zg", "fo" to "Zm8", "foo" to "Zm9v", "foob" to "Zm9vYg", "fooba" to "Zm9vYmE", "foobar" to "Zm9vYmFy")
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base64Url.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), Base64Url.decode(encoded))
        }
        val bytes = ByteArray(300).also { Random(7).nextBytes(it) }
        assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        assertArrayEquals(byteArrayOf(-5, -1), Base64Url.decode("+/8=")) // standard alphabet accepted too
        assertNull(Base64Url.decode("abcde")) // impossible length
    }

    @Test
    fun decompressionBombIsRefused() {
        // 4 MiB of zeros deflate to a few KB: sealed with the real key it must still be refused (> 1 MiB out)
        val zeros = ByteArray(4 shl 20)
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(zeros); finish() }
        val buf = ByteArray(64 shl 10)
        val n = d.deflate(buf)
        assertTrue(d.finished())
        val raw = Base64Url.decode(key)!!
        val iv = ByteArray(12)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(raw, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            updateAAD(topic.toByteArray())
        }
        val bomb = Envelope.PREFIX + Base64Url.encode(iv + cipher.doFinal(buf.copyOf(n)))
        assertNull(Envelope.open(bomb, key, topic))
    }
}
