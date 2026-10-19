package dev.framio.lab.labs.missing

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import dev.framio.lab.core.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Asks each API what it can do, read-only (nothing is switched on), and sets that against what
 * PackageManager claims. Android 6 on this frame declares a generic tablet's features.
 */
object Absent {

    data class Probe(val name: String, val feature: String?, val claimed: Boolean?, val status: Status, val detail: String)

    private fun installed(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun claims(context: Context, feature: String) = context.packageManager.hasSystemFeature(feature)

    suspend fun all(context: Context): List<Probe> = listOf(
        bluetooth(context), camera(context), nfc(context), location(context), vibrator(context),
        tts(context), speech(context), telephony(context), fingerprint(context), ir(context),
        playServices(context), thermal(),
    )

    @Suppress("DEPRECATION")
    fun bluetooth(context: Context): Probe {
        val feature = PackageManager.FEATURE_BLUETOOTH
        val stack = installed(context, "com.android.bluetooth")
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val state = adapter?.let { runCatching { it.state }.getOrNull() }
        val stateName = when (state) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning on"
            null -> "none"
            else -> "state $state"
        }
        return Probe(
            "Bluetooth / BLE", feature, claims(context, feature),
            if (stack && state == BluetoothAdapter.STATE_ON) Status.PASS else Status.ABSENT,
            "stack app com.android.bluetooth installed=$stack, adapter $stateName, ro.rk.bt_enable=false; RK915 has no BT radio",
        )
    }

    @Suppress("DEPRECATION")
    fun camera(context: Context): Probe {
        val legacy = android.hardware.Camera.getNumberOfCameras()
        val ids = runCatching { context.getSystemService(CameraManager::class.java).cameraIdList.toList() }.getOrDefault(emptyList())
        return Probe("Camera", PackageManager.FEATURE_CAMERA_ANY, claims(context, PackageManager.FEATURE_CAMERA_ANY),
            if (legacy > 0 || ids.isNotEmpty()) Status.PASS else Status.ABSENT, "Camera.getNumberOfCameras()=$legacy, camera2 ids=$ids")
    }

    fun nfc(context: Context): Probe {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return Probe("NFC", PackageManager.FEATURE_NFC, claims(context, PackageManager.FEATURE_NFC),
            if (adapter != null) Status.PASS else Status.ABSENT, "NfcAdapter.getDefaultAdapter()=${adapter ?: "null"} (no nfc service)")
    }

    fun location(context: Context): Probe {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = lm.allProviders
        val gps = LocationManager.GPS_PROVIDER in providers
        return Probe("GPS / location", PackageManager.FEATURE_LOCATION_GPS, claims(context, PackageManager.FEATURE_LOCATION_GPS),
            if (gps) Status.PASS else Status.ABSENT,
            "providers $providers; no GPS chip and no Google network location — use a fixed location or IP geolocation")
    }

    fun vibrator(context: Context): Probe {
        val has = context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
        return Probe("Vibration", null, null, if (has) Status.PASS else Status.ABSENT, "Vibrator.hasVibrator()=$has")
    }

    /** TextToSpeech binds to an engine app; with none installed, init reports ERROR. Callback runs on the main thread. */
    suspend fun tts(context: Context): Probe {
        val result = withContext(Dispatchers.Main) {
            var engine: TextToSpeech? = null
            val status = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine { cont ->
                    engine = TextToSpeech(context.applicationContext) { s -> if (cont.isActive) cont.resume(s) }
                }
            }
            val engines = engine?.engines?.map { it.name }.orEmpty()
            engine?.shutdown()
            status to engines
        }
        val (status, engines) = result
        return Probe("Text to speech", null, null, if (status == TextToSpeech.SUCCESS) Status.PASS else Status.ABSENT,
            "init=${when (status) { TextToSpeech.SUCCESS -> "SUCCESS"; TextToSpeech.ERROR -> "ERROR"; else -> "timeout" }}, engines=$engines (Pico voice files exist, the engine app doesn't)")
    }

    fun speech(context: Context): Probe {
        val ok = SpeechRecognizer.isRecognitionAvailable(context)
        return Probe("Speech recognition", "android.software.voice_recognizers", claims(context, "android.software.voice_recognizers"),
            if (ok) Status.PASS else Status.ABSENT, "SpeechRecognizer.isRecognitionAvailable()=$ok")
    }

    fun telephony(context: Context): Probe {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val type = tm?.phoneType
        return Probe("Telephony", PackageManager.FEATURE_TELEPHONY, claims(context, PackageManager.FEATURE_TELEPHONY),
            if (type != null && type != TelephonyManager.PHONE_TYPE_NONE) Status.PASS else Status.ABSENT, "phoneType=$type")
    }

    /** FingerprintManager is no longer in the compile SDK's stubs, so it is reached by name on this Android 6 device. */
    fun fingerprint(context: Context): Probe {
        val fm = context.getSystemService("fingerprint")
        val hw = fm?.let { runCatching { it.javaClass.getMethod("isHardwareDetected").invoke(it) as Boolean }.getOrNull() } == true
        return Probe("Fingerprint", PackageManager.FEATURE_FINGERPRINT, claims(context, PackageManager.FEATURE_FINGERPRINT),
            if (hw) Status.PASS else Status.ABSENT, "fingerprint service ${if (fm == null) "missing" else "isHardwareDetected=$hw"}")
    }

    fun ir(context: Context): Probe {
        val ir = context.getSystemService(ConsumerIrManager::class.java)
        val emitter = ir?.hasIrEmitter() == true
        return Probe("IR blaster", PackageManager.FEATURE_CONSUMER_IR, claims(context, PackageManager.FEATURE_CONSUMER_IR),
            if (emitter) Status.PASS else Status.ABSENT, "ConsumerIrManager.hasIrEmitter()=$emitter (the IR driver here is a receiver: see Buttons & keys)")
    }

    fun playServices(context: Context): Probe {
        val gms = installed(context, "com.google.android.gms")
        return Probe("Google Play services", null, null, if (gms) Status.PASS else Status.ABSENT,
            if (gms) "installed" else "not installed: no FCM push, no Play Store, no fused location; WebView is updated only by replacing /system/app/webview")
    }

    fun thermal(): Probe {
        val zones = java.io.File("/sys/class/thermal").list()?.size ?: 0
        return Probe("Temperature sensor", null, null, if (zones > 0) Status.PASS else Status.ABSENT, "/sys/class/thermal has $zones zones; the battery temperature is a fixed HAL value")
    }
}
