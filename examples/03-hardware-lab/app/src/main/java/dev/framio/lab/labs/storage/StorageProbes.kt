package dev.framio.lab.labs.storage

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Environment
import android.os.StatFs
import dev.framio.lab.core.Sysfs
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.random.Random

/** Volumes, SD/USB presence, and I/O benchmarks. Blocking: call off the main thread. */
object StorageProbes {

    data class Volume(val path: String, val state: String, val removable: Boolean, val emulated: Boolean, val free: Long, val total: Long)

    fun volumes(context: Context): List<Volume> = context.getExternalFilesDirs(null).filterNotNull().map { dir ->
        val s = runCatching { StatFs(dir.path) }.getOrNull()
        Volume(
            dir.path,
            Environment.getExternalStorageState(dir),
            Environment.isExternalStorageRemovable(dir),
            Environment.isExternalStorageEmulated(dir),
            s?.availableBytes ?: 0, s?.totalBytes ?: 0,
        )
    }

    /** Mount table lines that are storage, not system plumbing. */
    fun storageMounts(): List<String> = Sysfs.read("/proc/mounts")?.lines().orEmpty()
        .filter { l -> listOf("/storage", "/mnt/media_rw", "mmcblk1", "/dev/block/sd", "/dev/block/vold").any { it in l } }

    /** The RK3126 SD controller is mmc1 (rksdmmc@10214000); a card shows up as mmc1:xxxx. */
    fun sdCard(): String? {
        val host = "/sys/class/mmc_host/mmc1"
        if (!Sysfs.exists(host)) return null
        val card = Sysfs.list(host).firstOrNull { it.startsWith("mmc1:") } ?: return "slot present, no card"
        return "card ${Sysfs.read("$host/$card/name") ?: "?"} (${Sysfs.read("$host/$card/type") ?: "?"})"
    }

    fun usbDevices(context: Context): List<String> =
        context.getSystemService(UsbManager::class.java).deviceList.values.map { d ->
            "${d.deviceName} · %04x:%04x · class ${d.deviceClass} · ${d.interfaceCount} interface(s)".format(d.vendorId, d.productId)
        }

    data class Speed(val writeMBs: Double, val readMBs: Double, val randomIops: Double, val sizeMb: Int)

    /** Sequential write (+fsync), read back, then 4 KiB random synchronous writes. The file is deleted after. */
    fun diskSpeed(dir: File, sizeMb: Int = 64): Speed {
        val file = File(dir, "speed.bin")
        val block = ByteArray(1 shl 20).also { Random.nextBytes(it) }
        try {
            var t0 = System.nanoTime()
            FileOutputStream(file).use { out ->
                repeat(sizeMb) { out.write(block) }
                out.fd.sync()
            }
            val write = sizeMb / ((System.nanoTime() - t0) / 1e9)

            // Read right after writing: some of it comes from the page cache, so this is optimistic
            t0 = System.nanoTime()
            file.inputStream().use { input -> while (input.read(block) > 0) Unit }
            val read = sizeMb / ((System.nanoTime() - t0) / 1e9)

            val small = ByteArray(4096)
            val ops = 200
            t0 = System.nanoTime()
            RandomAccessFile(file, "rwd").use { raf -> // rwd: every write reaches the eMMC, like a database commit
                repeat(ops) {
                    raf.seek(Random.nextLong(sizeMb.toLong() * 256) * 4096)
                    raf.write(small)
                }
            }
            val iops = ops / ((System.nanoTime() - t0) / 1e9)
            return Speed(write, read, iops, sizeMb)
        } finally {
            file.delete()
        }
    }

    /** SQLite: 1000 inserts in one transaction, then an aggregate query. Returns (insert ms, query ms, sqlite version). */
    fun sqlite(context: Context): Triple<Long, Long, String> {
        val name = "lab.db"
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY, path TEXT, taken INTEGER, liked INTEGER)")
            var t0 = System.nanoTime()
            db.beginTransaction()
            try {
                val stmt = db.compileStatement("INSERT INTO photos (path, taken, liked) VALUES (?, ?, ?)")
                repeat(1000) {
                    stmt.bindString(1, "/sdcard/DCIM/IMG_$it.jpg")
                    stmt.bindLong(2, 1_600_000_000_000L + it * 60_000L)
                    stmt.bindLong(3, (it % 7 == 0).compareTo(false).toLong())
                    stmt.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            val insertMs = (System.nanoTime() - t0) / 1_000_000
            t0 = System.nanoTime()
            db.rawQuery("SELECT COUNT(*), SUM(liked) FROM photos WHERE taken > ?", arrayOf("1600000000000")).use { it.moveToFirst() }
            val queryMs = (System.nanoTime() - t0) / 1_000_000
            val version = db.rawQuery("SELECT sqlite_version()", null).use { it.moveToFirst(); it.getString(0) }
            return Triple(insertMs, queryMs, version)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
