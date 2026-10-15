plugins {
    id("com.android.application")
}

android {
    namespace = "dev.framio.hello"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.framio.hello"
        minSdk = 23 // the frame runs Android 6.0.1
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
