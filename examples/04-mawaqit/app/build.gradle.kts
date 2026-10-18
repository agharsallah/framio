plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.framio.mawaqit"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.framio.mawaqit"
        minSdk = 23 // the frame runs Android 6.0.1
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // R8 strips unused Compose code: less to map into RAM on a 1 GB device
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (zones, DST, durations) on API 23: D8 rewrites it to a bundled copy
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // android.util.Log etc. are no-ops in JVM tests
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // All of these still declare minSdkVersion 23 (or lower) in their AAR manifests
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation") // layout, text, clicks; the display draws its own look
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // QR code of the remote-control link. 3.3.x: plain Java 7, newer versions use API 24+ java.util calls
    implementation("com.google.zxing:core:3.3.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // android.jar's org.json is stubs only in JVM tests
}
