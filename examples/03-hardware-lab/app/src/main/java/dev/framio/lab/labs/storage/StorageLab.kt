package dev.framio.lab.labs.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Events
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 32 GB eMMC, an SD slot and USB host: volumes, hot-plug events, I/O speed, SQLite, the system file picker. */
object StorageLab {

    val lab = Lab(
        id = "storage",
        title = "Storage & USB",
        summary = "Volumes, SD card and USB hot-plug, eMMC speed, SQLite, system file picker",
        accent = Amber,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        LabPage("Storage & USB", "Insert an SD card or plug a USB stick (OTG adapter) while this page is open: the events show up below.") {
            VolumesSection(context)
            HotplugSection(context)
            SpeedSection(context)
            PickerSection(context)
            SelfTestSection(lab.id, note = "Lists volumes, SD and USB, writes/reads 32 MB on the eMMC, runs 1000 SQLite inserts.")
        }
    }

    @Composable
    private fun VolumesSection(context: Context) {
        var version by remember { mutableIntStateOf(0) }
        Section("Volumes", note = "Context.getExternalFilesDirs: one entry per mounted volume (internal shared storage first).", trailing = {
            ActionButton("Refresh", { version++ }, primary = false)
        }) {
            version.let {
                KeyValues(
                    StorageProbes.volumes(context).map { v ->
                        v.path to "${v.state} · ${if (v.removable) "removable" else "built-in"}${if (v.emulated) " (emulated)" else ""} · ${Format.bytes(v.free)} free of ${Format.bytes(v.total)}"
                    } + listOf(
                        "SD slot (mmc1)" to (StorageProbes.sdCard() ?: "no controller"),
                        "USB devices" to StorageProbes.usbDevices(context).ifEmpty { listOf("none") }.joinToString("\n"),
                    ),
                    keyWidth = 420.dp,
                )
                val mounts = StorageProbes.storageMounts()
                if (mounts.isNotEmpty()) Text(mounts.joinToString("\n"), style = Mono)
            }
        }
    }

    @Composable
    private fun HotplugSection(context: Context) {
        val events = remember { mutableStateListOf<String>() }
        DisposableEffect(Unit) {
            fun log(text: String) {
                events.add(0, text)
                Events.log(lab.id, text)
            }
            val media = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = log("${i.action?.substringAfterLast('.')} ${i.data?.path}")
            }
            val usb = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
                    log("${i.action?.substringAfterLast('.')} ${d?.deviceName} %04x:%04x".format(d?.vendorId ?: 0, d?.productId ?: 0))
                }
            }
            context.registerReceiver(media, IntentFilter().apply {
                listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT)
                    .forEach(::addAction)
                addDataScheme("file") // media broadcasts carry a file:// URI and are only matched with this
            })
            context.registerReceiver(usb, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            })
            onDispose {
                context.unregisterReceiver(media)
                context.unregisterReceiver(usb)
            }
        }
        Section("Hot-plug events", note = "ACTION_MEDIA_* (SD card, USB storage) and UsbManager ACTION_USB_DEVICE_* broadcasts.") {
            if (events.isEmpty()) Note("Nothing plugged in since this page opened.")
            events.forEach { Text(it, style = Mono) }
        }
    }

    @Composable
    private fun SpeedSection(context: Context) {
        val scope = rememberCoroutineScope()
        var disk by remember { mutableStateOf<String?>(null) }
        var sql by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Section("Speed", note = "Sequential 64 MB write with fsync, read back, 200 random 4 KiB synchronous writes; SQLite with a prepared statement in one transaction.") {
            Actions {
                ActionButton(if (busy) "Running…" else "eMMC speed", {
                    busy = true
                    scope.launch {
                        disk = withContext(Dispatchers.IO) { runCatching { describe(StorageProbes.diskSpeed(context.filesDir)) }.getOrElse { "failed: ${it.message}" } }
                        busy = false
                    }
                }, enabled = !busy)
                ActionButton("SQLite", {
                    scope.launch {
                        sql = withContext(Dispatchers.IO) {
                            runCatching { StorageProbes.sqlite(context) }.fold({ (ins, q, v) -> "SQLite $v: 1000 inserts $ins ms, aggregate query $q ms" }, { "failed: ${it.message}" })
                        }
                    }
                }, primary = false)
            }
            disk?.let { Text(it, style = Mono) }
            sql?.let { Text(it, style = Mono) }
        }
    }

    private fun describe(s: StorageProbes.Speed) =
        "write ${Format.f1(s.writeMBs)} MB/s · read ${Format.f1(s.readMBs)} MB/s (partly cached) · 4 KiB sync writes ${Format.f1(s.randomIops)} IOPS · ${s.sizeMb} MB"

    @Composable
    private fun PickerSection(context: Context) {
        var picked by remember { mutableStateOf<String?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            picked = uri?.let { describe(context, it) } ?: "cancelled"
        }
        Section("System file picker", note = "Storage Access Framework (ACTION_OPEN_DOCUMENT → DocumentsUI). Lets a dashboard read photos from an SD card or USB stick without storage permissions.") {
            Actions { ActionButton("Pick a file", { launcher.launch(arrayOf("*/*")) }, primary = false) }
            picked?.let { Text(it, style = Mono) }
        }
    }

    private fun describe(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use uri.toString()
            val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            val size = c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
            "$name · ${Format.bytes(size)} · ${context.contentResolver.getType(uri)} · $uri"
        } ?: uri.toString()

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        StorageProbes.volumes(context).forEach { v ->
            info("Volume", "${v.path}: ${v.state}, ${if (v.removable) "removable" else "built-in"}, ${Format.bytes(v.free)} free of ${Format.bytes(v.total)}")
        }
        val sd = StorageProbes.sdCard()
        when {
            sd == null -> absent("SD card slot", "no mmc1 controller")
            sd.startsWith("card") -> pass("SD card slot", sd)
            else -> info("SD card slot", "$sd (insert a card to test)")
        }
        val usb = StorageProbes.usbDevices(context)
        info("USB host", if (usb.isEmpty()) "no USB devices attached (UsbManager works)" else usb.joinToString())
        guard("eMMC speed") {
            val s = StorageProbes.diskSpeed(context.filesDir, sizeMb = 32)
            pass("eMMC speed", describe(s))
        }
        guard("SQLite") {
            val (ins, q, v) = StorageProbes.sqlite(context)
            pass("SQLite", "SQLite $v: 1000 inserts in $ins ms, query $q ms")
        }
        guard("File picker") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            val handler = context.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
            if (handler != null) pass("File picker", "ACTION_OPEN_DOCUMENT → $handler") else absent("File picker", "no DocumentsUI")
        }
        items
    }
}
