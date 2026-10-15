plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; this only pins a newer Kotlin than the bundled one
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
