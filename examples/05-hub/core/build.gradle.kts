// Pure Kotlin/JVM: everything that decides anything lives here, with no Android dependency.
// Being a JVM module is what enforces that, and it keeps the tests fast.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) // what D8 expects from :app's classes too
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.biweekly) {
        // Only used for jCal/xCal (JSON and XML calendars). Plain .ics parsing never touches them.
        exclude(group = "com.fasterxml.jackson.core")
    }

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp) // a real HTTP client against HttpServer

    testFixturesApi(libs.coroutines.test)
    testFixturesApi(libs.junit)
}
