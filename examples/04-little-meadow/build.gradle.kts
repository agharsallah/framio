plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins a newer Kotlin than the bundled one
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // Compose compiler ships with Kotlin, so it always uses the Kotlin version
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
