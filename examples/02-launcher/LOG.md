# Example 02: Launcher — command log

Native Jetpack Compose HOME app. Why: the firmware won't start apps in the background at boot
(`BOOT_COMPLETED: process is bad`), but it always starts the HOME activity in the foreground.

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` · `adb` = `~/Downloads/platform-tools/adb`

## Library versions (Google Maven)

| Command | Output | Note |
|---|---|---|
| `curl -s https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml` | latest stable `2026.09.00` | |
| same for `androidx/activity/activity-compose` | latest stable `1.13.0` | |
| `curl …/compose-bom-2026.09.00.pom` | `ui`/`foundation`/`runtime` `1.12.1`, `material3` `1.4.0` | |
| `curl -s https://repo1.maven.org/…/org.jetbrains.kotlin.plugin.compose.gradle.plugin/maven-metadata.xml` | `2.4.21` exists | same as Kotlin |
| `unzip -p <aar> AndroidManifest.xml \| grep minSdkVersion` | `ui-android 1.12.1` 23 · `foundation-android 1.12.1` 23 · `runtime-android 1.12.1` 23 · `activity 1.13.0` 23 · `activity-compose 1.13.0` 23 · `material3-android 1.4.0` 21 | **latest still supports Android 6** |

## Frameo intents

| Command | Output | Note |
|---|---|---|
| `adb shell dumpsys package net.frameo.frame \| sed -n '/Activity Resolver Table/,/Receiver/p'` | `android.intent.action.MAIN: net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted` | only exported entry point |
| `adb shell am start -n net.frameo.frame/.feature.slideshow.ui.AScreenSlidePager` | `SecurityException: Permission Denial … not exported from uid 10002` | slideshow is private → go through the entry activity |

## Build

| Command | Output | Note |
|---|---|---|
| `cp ../01-hello-frame/{gradlew,gradlew.bat,gradle/wrapper/*,gradle.properties,.gitignore} .` | | same Gradle 9.8.1 wrapper |
| `./gradlew assembleDebug` | `BUILD SUCCESSFUL in 1m 31s`, `app-debug.apk` 8.8M | foundation only, no Material |
| `grep minSdkVersion app/build/intermediates/merged_manifest/…/AndroidManifest.xml` | `minSdkVersion="23"` | no manifest-merger error → no library needs > 23 |
| `./gradlew :app:dependencies --configuration debugRuntimeClasspath` | `foundation/ui/runtime 1.12.1`, `activity 1.13.0`, `lifecycle-runtime 2.9.4`, `core 1.18.0`, `kotlin-stdlib 2.4.21` | |

## Install and set as HOME

| Command | Output | Note |
|---|---|---|
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` | `Success` | |
| `adb shell appops set dev.framio.launcher SYSTEM_ALERT_WINDOW allow` | `SYSTEM_ALERT_WINDOW: allow` | overlay for the hot corner |
| `adb shell input keyevent KEYCODE_HOME` | `mFocusedActivity: android/com.android.internal.app.ResolverActivity` | two HOME apps → chooser |
| `adb exec-out screencap -p > $TMPDIR/…/chooser.png` | "Select a Home app": Frameo (y 646), Framio (y 702), JUST ONCE / ALWAYS (y 765) | screenshot kept outside the repo |
| `adb shell input tap 500 702` then `input tap 829 765` | | Framio → Always |
| `adb shell dumpsys activity activities \| grep -i home` | `cat=[android.intent.category.HOME] … cmp=dev.framio.launcher/.LauncherActivity`, `mActivityType=HOME_ACTIVITY_TYPE` | **launcher is HOME** |
| `adb shell dumpsys package preferred-xml` | `<item name="dev.framio.launcher/.LauncherActivity">` MAIN + HOME + DEFAULT | the stored "Always" |
| `adb install -r …` (update) + `input keyevent KEYCODE_HOME` | straight to launcher, no chooser | "Always" survives updates |

## UI fixes

| Command | Output | Note |
|---|---|---|
| screencap of launcher | clock rendered Regular, not Thin; dot touches Photos tile | |
| `adb shell ls /system/fonts \| grep Roboto` | `Roboto-Thin.ttf`, `Roboto-Light.ttf`, … | weights exist |
| `FontFamily.SansSerif` + `FontWeight.Light`, bottom padding 88 | Light renders, tiles end at y 712 | below API 28 Compose only maps weights via a named family (`sans-serif-light`) |

## Photos tile (which Frameo intent?)

| Command | Output | Note |
|---|---|---|
| tap Photos, `getLaunchIntentForPackage` (MAIN + LAUNCHER) | `am_task_to_front: [0,9]` then `am_create_activity … AWaitForDeferredInitCompleted` → `APowerOffNotice` | new entry activity stacked on Frameo's task → red "use the power off button" notice every time |
| `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10000000 -n net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted` | `Warning: Activity not started, its current task has been brought to the front` | intent equals Frameo's task root intent → task reused |
| tap Photos with MAIN + HOME + explicit component | only `am_task_to_front: [0,9]`, same `AScreenSlidePager` record | **slideshow comes back as it was** |

## Tiles, hot corner, Back

| Command | Output | Note |
|---|---|---|
| `adb shell input swipe 44 756 44 756 1000` (inside Frameo) | `mFocusedActivity: dev.framio.launcher/.LauncherActivity` | long-press dot → home |
| `adb shell dumpsys window windows \| grep "Window #"` | `dev.framio.launcher` overlay + `dev.framio.hello` overlay | two dots while example 01 still had its own |
| tap Hello Frame (980 570) | `mFocusedActivity: dev.framio.hello/.MainActivity` | |
| tap Exit (1196 744) | `am_finish_activity … dev.framio.hello/.MainActivity,app-request` → launcher focused | |
| `adb shell input keyevent KEYCODE_BACK` on launcher | same `LauncherActivity` record, no `am_finish_activity` | Back is ignored |

## Memory: debug vs release

| Command | Output | Note |
|---|---|---|
| `adb shell dumpsys meminfo dev.framio.launcher` (debug) | `TOTAL 31461` KB PSS, `Code: 17872` | unshrunk Compose dex |
| `./gradlew assembleRelease` (R8, debug-signed) | `BUILD SUCCESSFUL in 58s`, `app-release.apk` 784K (debug 8.8M) | |
| `adb install -r app/build/outputs/apk/release/app-release.apk` | `Success` | same key → updates in place |
| `adb shell dumpsys meminfo dev.framio.launcher` (release) | `TOTAL 15572` KB PSS, `Code: 4436`, `Java Heap: 3000` | **~15.6 MB**, Frameo ~128 MB |

## Example 01: hot corner moved here

| Command | Output | Note |
|---|---|---|
| `git rm …/HotCornerService.kt …/BootReceiver.kt`, drop manifest entries + 2 permissions + `startService` | | |
| `./gradlew assembleDebug` (01) | `BUILD SUCCESSFUL`, merged manifest: only `INTERNET` | |
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` (01) | `Success` | |
| open Hello Frame from launcher, `dumpsys window windows \| grep "Window #"` | one overlay: `dev.framio.launcher` | **one dot** |
| `adb shell dumpsys activity services dev.framio.hello` | `(nothing)` | |
| `footer { padding-left: 48px }` in 01's `index.html` | text starts at x 96 | dot no longer covers it |

## Undo (back to Frameo as HOME)

| Command | Output | Note |
|---|---|---|
| `adb shell pm` | no `clear-preferred` / `set-home-activity` | Android 6 |
| `adb shell pm clear dev.framio.launcher` | `Success`, `preferred-xml` still has the launcher | does **not** reset HOME |
| `adb uninstall dev.framio.launcher` | `Success`, `preferred-xml` items: 0 | |
| `adb shell input keyevent KEYCODE_HOME` | `mResumedActivity: net.frameo.frame/…AScreenSlidePager`, HOME = `net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted` | **Frameo is HOME again** |
| reinstall release + appops + HOME → chooser → Framio → Always | launcher is HOME again | fresh-install flow works |

## Final check (release build)

| Command | Output | Note |
|---|---|---|
| tap Photos | `net.frameo.frame/.feature.slideshow.ui.AScreenSlidePager` | |
| long-press dot | `dev.framio.launcher/.LauncherActivity` | |
| tap Hello Frame → tap Exit | `dev.framio.hello/.MainActivity` → `dev.framio.launcher/.LauncherActivity` | |
| tap Photos → `KEYCODE_HOME` → `KEYCODE_BACK` | slideshow → launcher → launcher | |
| `adb logcat -d -b crash \| grep -ci framio` | `0` | no crashes |

Not tested here: reboot (the frame was busy with a backup). Expected: the system starts the launcher at boot, the launcher starts the dot.
