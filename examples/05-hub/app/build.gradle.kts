plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.framio.hub"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.framio.hub"
        minSdk = 23 // the frame runs Android 6.0.1
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // R8 strips unused Compose code: less to map into RAM on a 1 GB device
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // personal device: install -r over a debug build works
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true // java.time on API 23
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric + Roborazzi
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
        lintConfig = file("lint.xml")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "**/*.kotlin_builtins", "DebugProbesKt.bin")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring(libs.desugar)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation) // own design system, no Material
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.datastore)
    implementation(libs.okhttp)
    implementation(libs.qrcodegen)
    lintChecks(libs.compose.lints)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
}

// The API 23 guard: Lint's NewApi check misses calls newer than API 23 with this toolchain (03 LOG),
// so check what actually ships. It reads every platform reference in the release APK's dex.
val api23Guard by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails if the release APK calls Android or Java APIs that Android 6 (API 23) lacks."
    dependsOn("assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val script = rootProject.layout.projectDirectory.file("tools/api23_guard.py")
    inputs.file(apk)
    inputs.file(script)
    commandLine("python3", "-I", script.asFile.path, apk.get().asFile.path)
}

tasks.named("check") { dependsOn(api23Guard) }
