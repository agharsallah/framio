plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles :app's Kotlin itself; the JVM plugin builds :core and pins the Kotlin version for both
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}
