package dev.framio.lab.labs.speaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The other ways Android plays sound, each wrapped so the lab and the self-test can call them. */
object SoundApis {

    private val media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun outputDevices(context: Context): List<String> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { "${deviceType(it.type)} (${it.productName})" }

    fun inputDevices(context: Context): List<String> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).map { "${deviceType(it.type)} (${it.productName})" }

    fun deviceType(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        else -> "type $t"
    }

    /** Native mixer properties: what the HAL runs at. Matching them gives the lowest latency. */
    fun mixerProperties(context: Context): Pair<String?, String?> {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) to am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
    }

    /** DTMF digit or a beep through ToneGenerator (the dial-pad API). */
    fun dtmf(tone: Int, ms: Int = 200) {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        tg.startTone(tone, ms)
        Thread { Thread.sleep(ms + 100L); tg.release() }.start()
    }

    /** Writes a click WAV, loads it into SoundPool, plays it. Returns load time in ms, or null if it failed. */
    suspend fun soundPoolClick(context: Context): Long? {
        val file = File(context.cacheDir, "click.wav").apply { writeBytes(Wav.click()) }
        val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(media).build()
        val loaded = CompletableDeferred<Int>()
        pool.setOnLoadCompleteListener { _, _, status -> loaded.complete(status) }
        val t0 = System.nanoTime()
        val id = pool.load(file.path, 1)
        val status = withTimeoutOrNull(3000) { loaded.await() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (status != 0) {
            pool.release()
            return null
        }
        pool.play(id, 1f, 1f, 1, 0, 1f)
        Thread { Thread.sleep(500); pool.release() }.start()
        return ms
    }

    /** Plays the default notification sound with MediaPlayer. Returns its duration in ms, or null. */
    fun playNotification(context: Context): Int? {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(media)
            mp.setDataSource(context, uri)
            mp.prepare()
            mp.setOnCompletionListener { it.release() }
            mp.start()
            mp.duration
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    /** Ringtone API: what alarm clocks use. Plays the default alarm for [ms] then stops. */
    fun playAlarmBriefly(context: Context, ms: Long = 1500): String? {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return null
        val r = RingtoneManager.getRingtone(context, uri) ?: return null
        r.play()
        Thread { Thread.sleep(ms); r.stop() }.start()
        return r.getTitle(context)
    }

    fun ringtoneCounts(context: Context): Map<String, Int> = mapOf(
        "ringtones" to RingtoneManager.TYPE_RINGTONE,
        "notifications" to RingtoneManager.TYPE_NOTIFICATION,
        "alarms" to RingtoneManager.TYPE_ALARM,
    ).mapValues { (_, type) ->
        val rm = RingtoneManager(context).apply { setType(type) }
        rm.cursor.use { it.count }
    }

    /** Audio effects the platform offers (Equalizer, BassBoost, …). */
    fun effects(): List<String> = AudioEffect.queryEffects().orEmpty().map { "${it.name} (${it.implementor})" }
}
