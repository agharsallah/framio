package dev.framio.lab.core

import java.io.File

/** Reads small kernel files (/proc, /sys). Android 6 still lets apps read most of them. */
object Sysfs {

    fun read(path: String): String? = try {
        File(path).readText().trim()
    } catch (_: Exception) { // missing, or blocked by permissions/SELinux
        null
    }

    fun readLong(path: String): Long? = read(path)?.toLongOrNull()

    fun exists(path: String): Boolean = File(path).exists()

    fun list(path: String): List<String> = File(path).list()?.sorted() ?: emptyList()
}
