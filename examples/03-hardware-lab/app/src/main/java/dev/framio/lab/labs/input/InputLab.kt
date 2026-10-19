package dev.framio.lab.labs.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Events
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.ToggleRow

/** Buttons, IR remote and any USB keyboard: every key event that reaches the app. */
object InputLab {

    val lab = Lab(
        id = "input",
        title = "Buttons & keys",
        summary = "Case buttons, IR remote, USB keyboards: input devices and a live key log",
        accent = Teal,
        selfTest = ::selfTest,
        content = { Content() },
    )

    /** Keys we ask each device about with InputDevice.hasKeys. */
    private val probeKeys = intArrayOf(
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
    )

    data class Device(val id: Int, val name: String, val sources: String, val keys: List<String>, val virtual: Boolean, val vendorProduct: String)

    fun devices(): List<Device> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.map { d ->
        val has = d.hasKeys(*probeKeys)
        Device(
            id = d.id,
            name = d.name,
            sources = sourceNames(d.sources),
            keys = probeKeys.filterIndexed { i, _ -> has[i] }.map { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") },
            virtual = d.isVirtual,
            vendorProduct = "%04x:%04x".format(d.vendorId, d.productId),
        )
    }

    private fun sourceNames(s: Int) = listOf(
        InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
        InputDevice.SOURCE_KEYBOARD to "keyboard",
        InputDevice.SOURCE_DPAD to "dpad",
        InputDevice.SOURCE_MOUSE to "mouse",
        InputDevice.SOURCE_JOYSTICK to "joystick",
        InputDevice.SOURCE_GAMEPAD to "gamepad",
    ).filter { (flag, _) -> s and flag == flag }.joinToString { it.second }.ifEmpty { "0x%x".format(s) }

    private data class Logged(val text: String)

    @Composable
    private fun Content() {
        val host = LocalHost.current
        val context = host.context
        val log = remember { mutableStateListOf<Logged>() }
        var capture by remember { mutableStateOf(true) }
        var deviceVersion by remember { mutableIntStateOf(0) }

        DisposableEffect(capture) {
            host.captureKeys = capture
            onDispose { host.captureKeys = false }
        }
        LaunchedEffect(Unit) {
            host.keys.collect { e ->
                val action = when (e.action) {
                    KeyEvent.ACTION_DOWN -> "down"
                    KeyEvent.ACTION_UP -> "up"
                    else -> "multiple"
                }
                val text = "${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")} $action · scan ${e.scanCode} · repeat ${e.repeatCount} · ${e.device?.name ?: "virtual"}"
                log.add(0, Logged(text))
                if (log.size > 30) log.removeAt(log.size - 1)
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) Events.log(lab.id, "key $text")
            }
        }
        DisposableEffect(Unit) {
            val im = context.getSystemService(InputManager::class.java)
            val listener = object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(id: Int) { deviceVersion++; Events.log(lab.id, "input device added: ${InputDevice.getDevice(id)?.name}") }
                override fun onInputDeviceRemoved(id: Int) { deviceVersion++; Events.log(lab.id, "input device removed: $id") }
                override fun onInputDeviceChanged(id: Int) { deviceVersion++ }
            }
            im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
            onDispose { im.unregisterInputDeviceListener(listener) }
        }

        LabPage("Buttons & keys", "Press the frame's buttons, point an IR remote at it, or plug in a USB keyboard. POWER is handled by the system and never reaches apps.") {
            Section("Key log", note = "Activity.dispatchKeyEvent. Test without touching the frame: adb shell input keyevent VOLUME_UP") {
                ToggleRow("Capture keys", capture, { capture = it }, note = "On: keys only show up here (volume keys won't change the volume). Off: they also do their normal job.")
                if (log.isEmpty()) Note("No key pressed yet.")
                log.forEach { Text(it.text, style = Mono) }
            }
            Section("Input devices", note = "InputDevice.getDeviceIds, updated live (InputManager.InputDeviceListener).") {
                deviceVersion.let {
                    devices().forEach { d ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("#${d.id}", Modifier.width(44.dp), style = Mono)
                            Text(d.name + if (d.virtual) " (virtual)" else "", Modifier.width(260.dp), style = MaterialTheme.typography.bodyMedium)
                            Text("${d.sources} · ${d.vendorProduct}" + if (d.keys.isNotEmpty()) " · keys: ${d.keys.joinToString()}" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(color = InkDim))
                        }
                    }
                }
            }
            SelfTestSection(lab.id)
        }
    }

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val all = devices().filter { !it.virtual }
        all.forEach { d -> info(d.name, "${d.sources}${if (d.keys.isNotEmpty()) ", keys ${d.keys.joinToString()}" else ""}") }
        val keypad = all.firstOrNull { it.name.contains("keypad") }
        if (keypad != null) pass("Button driver", "${keypad.name} declares ${keypad.keys.joinToString()}")
        else absent("Button driver", "no keypad device")
        val ir = all.firstOrNull { it.name.contains("remotectl") }
        if (ir != null) info("IR remote driver", "${ir.name} loaded; a receiver on the board is not confirmed")
        manual("Press buttons", "Buttons & keys → press each case button; each should appear in the key log")
        items
    }
}
