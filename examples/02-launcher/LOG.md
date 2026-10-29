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

## Hardware Lab tile (example 03)

| Command | Output | Note |
|---|---|---|
| `Apps.openLab` (MAIN + LAUNCHER → `dev.framio.lab/.LabActivity`), `labInstalled` re-read in `onResume`, `<queries>` + `dev.framio.lab` | | same pattern as Hello Frame |
| three tiles, Photos weight 1.4 → 1.2 | "Hardware Lab" wrapped onto the glyph, then "Example 03 · Hardware tests" wrapped | title **Lab**, subtitle **Example 03 · Hardware** fit on one line |
| `./gradlew assembleRelease` + `adb install -r …/app-release.apk` | `Success` | |
| `KEYCODE_HOME` → tap Lab tile | `mResumedActivity: dev.framio.lab/.LabActivity` | |
| long-press dot | `dev.framio.launcher/.LauncherActivity` | |
| tap Lab tile → tap the lab's Exit | `dev.framio.lab/.LabActivity` → `dev.framio.launcher/.LauncherActivity` | |
| `dumpsys activity activities \| grep mFocusedActivity` | lagged one step behind | use `mResumedActivity` to check navigation |
| `adb logcat -d \| grep -c "FATAL EXCEPTION"` | `0` | |

## App shelf: framio apps found automatically

Tiles are no longer hard-coded: `Apps.discover` lists every `dev.framio.*` app with a `MAIN`/`LAUNCHER` activity
(`queryIntentActivities(…, GET_META_DATA)`), sorted by `order` then title. An app can style its tile from its
LAUNCHER activity (all optional; without them: its label, its icon or first letter, a colour picked from its package):

```xml
<meta-data android:name="dev.framio.tile.subtitle" android:value="Prayer times" />
<meta-data android:name="dev.framio.tile.accent" android:value="#6CC79A" />
<meta-data android:name="dev.framio.tile.order" android:value="40" />
```

Hello Frame (10) and Hardware Lab (30) keep their drawn glyphs and subtitles from defaults in `Apps.KNOWN`.
Up to 4 tiles share the row (Photos 1.15×); with more, a `LazyRow` that snaps tile by tile, 3.5 tiles visible,
edge fades where there is more, back to Photos on every resume. Titles auto-size to one line (`TextAutoSize`, 28–46 sp).
A receiver for `PACKAGE_ADDED/REMOVED/CHANGED/REPLACED` (while visible) refreshes the shelf as apps arrive over adb.
`<queries>`: Frameo + an intent for every launchable app (Android 11+ only).

| Command | Output | Note |
|---|---|---|
| `./gradlew assembleRelease lintDebug` | first: `NonObservableLocale` in `Clock` (old code, first lint run) | locale from `LocalConfiguration`, `Locale.ROOT` fallback; lint clean |
| `pm install -r` launcher + Mawaqit (`/tmp/framio-frame.lock` held: other sessions share the frame) | `Success` ×2 | |
| `KEYCODE_HOME`, screenshot | Photos · Hello Frame · Hardware Lab · Mawaqit · **Little Meadow** (another session's app, found with no launcher change) | 5 tiles → shelf |
| first screenshot | shelf left scrolled to the end; Mawaqit's "Example 04 · Prayer times" ellipsized; Little Meadow's title lower (no subtitle) | scroll to 0 on resume, subtitle "Prayer times", empty subtitle line kept |
| `input swipe 1100 620 300 620 300`, then `input tap 1000 620` | snapped to a tile edge; `mResumedActivity: dev.framio.meadow/.MeadowActivity` | |
| `KEYCODE_HOME` | `dev.framio.launcher/.LauncherActivity`, shelf back on Photos, half Mawaqit tile + fade on the right | |

## v0.2: Controls, All apps, startup app, a better home button

What changed (sources in `app/src/main/java/dev/framio/launcher/`):

| File | What |
|---|---|
| `Root.kt` | one persistent `su` (end marker + exit code per command, `su -c` fallback), as 05-hub; package names checked by regex before they reach a shell |
| `Device.kt` | media/alarm volume (`AudioManager`), brightness (`Settings.System`, root `settings put` fallback, min 30/255), backlight off/on (`rk28_bl`, recovered at start), memory, storage, IP, uptime; running apps from root `ps` (columns by header name), close = root `am force-stop` (fallback `killBackgroundProcesses`); app in front from `NavService` or `dumpsys activity activities` (`mResumedActivity`); restart / power off; **self-provisioning**: `appops SYSTEM_ALERT_WINDOW`, `appops WRITE_SETTINGS`, `pm grant ACCESS_COARSE_LOCATION`, each only when missing |
| `Wifi.kt` | on/off, scan, list (strongest AP per name; joined, saved, signal), join saved/open/WPA, forget (only networks we added: Android 6 rule); falls back to root `dumpsys wifi` when Android hands back no scan results (location off); the list is only kept while the Wi-Fi tab shows |
| `Prefs.kt` | `Config` in SharedPreferences: startup target + countdown, back-after-idle, dot on/size/rest opacity/edge+height, hidden framio apps, pinned other apps, Keep (Frameo by default) |
| `ControlCenter.kt` | panel with tabs: Quick (3 sliders, Wi-Fi / Sleep screen / Close apps / Android settings tiles), Wi-Fi, Running, Startup, Home button, Device |
| `AppDrawer.kt` | All apps (6-column grid, icons rendered once off the main thread into a 2 MB LRU), app sheet: Open · Add to/Remove from Home · Open at startup · Keep open · Close · App info · Uninstall |
| `HotCornerService.kt` | the dot: tap → menu (Home, Back, Close app, Controls; closes on outside touch or after 6 s), hold → Home, drag → snaps to the nearest side edge, saved; fades to its rest opacity after 3 s; hidden while Home is in front |
| `NavService.kt` | optional accessibility service (Controls → Home button → Instant Back): `GLOBAL_ACTION_BACK` and the app in front, `typeWindowStateChanged` only, no window content. Off: Back is root `input keyevent 4` |
| `LauncherActivity.kt` | startup app once per boot (`/proc/sys/kernel/random/boot_id` changed) after a countdown bar (Open now / Stay on Home), only while Home is in front; back-after-idle; brightness previewed on the window while dragging, written on release; Back/HOME close panels |

| Command | Output | Note |
|---|---|---|
| `./gradlew assembleDebug` | `BUILD SUCCESSFUL`; first: `setPressed(Z)V` / `setEnabled(Z)V` clashes | `View.pressed` and a `var enabled` + `fun setEnabled` → renamed `held`, `turn()` |
| `./gradlew testDebugUnitTest` | `ParsersTest`: 6 tests, 0 failures | `ps` (toolbox + toybox), `mResumedActivity`, `dumpsys wifi` table with spaces in SSIDs, `Root.pkg` rejects `x; reboot` etc. |
| `./gradlew lintDebug` | first: 8 × `MissingPermission` (`getConfiguredNetworks`/`getScanResults` want FINE location) | API 23 needs coarse; wrapped with `SecurityException` handling. Now only the 4 old warnings (Gradle 9.8.2, orientation, allowBackup, icon) |
| `./gradlew assembleRelease` | `app-release.apk` 1.28 MB (was 784 KB) | |

**Device checklist** (written before the frame was on adb; results in the next section):
install release → no `appops` → `dumpsys window windows | grep "Window #"` shows the dot; `appops get dev.framio.launcher`
(`SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS` allow); `dumpsys package dev.framio.launcher | grep COARSE` granted ·
dot: tap/hold/drag, menu actions, rest fade, hidden on Home · Controls: volumes (`dumpsys audio`), brightness
(`settings get system screen_brightness`), Sleep screen (`rk28_bl` 0 → back), Wi-Fi list (is location off? which path
filled it), join/forget, Running list vs `ps`, Close all keeps Frameo · Startup: set Photos, reboot, countdown, opens ·
`dumpsys meminfo dev.framio.launcher` (was 15.6 MB PSS).

### On the frame (2026-10-29, release build, uptime 3 min after another session's reboot)

| Command | Output | Note |
|---|---|---|
| `install -r` 0.2 (no appops) → `appops get dev.framio.launcher` | `WRITE_SETTINGS: allow` (new), `SYSTEM_ALERT_WINDOW: allow` | log: `appops set … WRITE_SETTINGS allow → ok`, `pm grant … ACCESS_COARSE_LOCATION → ok` |
| `dumpsys window windows` on Home | dot window `mViewVisibility=0x8` | hidden while Home is in front |
| Controls → Wi-Fi | 6 networks, joined one first with Forget | `getScanResults` answered: no root `dumpsys wifi` fallback needed |
| first Running tab | System UI, Android Keyboard, Wireless Update, System Update, External Storage | **Close all would have force-stopped System UI** → only apps with a LAUNCHER activity are listed; now: Frameo ≈ 121 MB, Kept |
| media slider tap 50% → 33% | `STREAM_MUSIC` speaker `5` → `8` → `5` | |
| brightness tap 50% → 70% | `screen_brightness` and `rk28_bl` `187` → `142` → `187` | |
| Sleep screen, wait, tap | `rk28_bl` `0`, `0`, then `187` | |
| Lab → tap dot | menu Home · Back · Close · "Con-" | labels wrapped to the glyph's 56 px (vertical LinearLayout gives children MATCH_PARENT) → WRAP_CONTENT, one line: "Close app", "Controls" |
| menu Close app | `Force stopping dev.framio.lab … from pid 1810`, launcher resumed, no lab process | first try hit the lab: the menu closes itself after 6 s |
| hold dot · menu Home · menu Controls | launcher resumed each time (Controls opens the panel) | |
| Lab → System → menu Back | Lab Overview | root `input keyevent 4` (~1 s). Instant Back (accessibility) not switched on: it changes a system setting on a shared frame |
| `input swipe 44 756 1200 300` on the dot | window at `[1208,267][1264,323]` | snapped to the right edge at the drop height |
| All apps, hold DeviceTest | grid of 13 apps, sheet without Uninstall (system app) | |
| startup Photos, boot id removed from prefs, `am force-stop` + HOME | "Opening Photos in 6 s" bar; at 0 `mResumedActivity: net.frameo.frame/…AWaitForDeferredInitCompleted` | then startup set back to Home. Editing the prefs as root needs `chown 10026:10026` after: a root-owned file reads as empty and the app wrote defaults over it |
| `dumpsys meminfo dev.framio.launcher` | `TOTAL 21905` KB PSS (0.1: 15572), Java heap 4.7 MB | after opening every panel |
| `logcat -b crash \| grep -ci framio` | `0` | |
