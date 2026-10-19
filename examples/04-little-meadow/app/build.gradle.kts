plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.framio.meadow"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.framio.meadow"
        minSdk = 23 // the frame runs Android 6.0.1
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // R8 matters more than usual here: Compose runs much smoother minified on a 1 GHz Cortex-A7
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // same key as debug, so install -r works
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // narration, sounds and sprites are already compressed; stored as-is so they can be memory-mapped
        noCompress += listOf("ogg", "webp")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log etc. are no-ops in JVM tests
    }
}

dependencies {
    // All of these still declare minSdkVersion 23 (or lower) in their AAR manifests
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // the real org.json for JVM tests (android.jar only has stubs)
}
