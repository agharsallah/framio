# Veidoo F10 — device log

Frameo 1.32.12 · ADB enabled via Settings → About → Beta program → ADB access
`adb` = `~/Downloads/platform-tools/adb`

## Device info

| Command | Output | Note |
|---|---|---|
| `adb devices` | `68384696 device` | connected, authorized |
| `adb shell getprop ro.build.version.release` | `6.0.1` | Android 6 |
| `adb shell getprop ro.build.version.sdk` | `23` | minSdk 23 |
| `adb shell getprop ro.product.cpu.abi` | `armeabi-v7a` | 32-bit ARM |
| `adb shell getprop ro.board.platform` | `rk312x` | Rockchip RK312x |
| `adb shell wm size` | `1280x800` | viewport 1280×800 |
| `adb shell wm density` | `160` | mdpi, 1 CSS px = 1 px |
| `adb shell pm list packages \| grep -i frameo` | `net.frameo.frame` | stock app to replace |
| `adb shell dumpsys package com.android.webview \| grep versionName` | _(empty)_ | not this package |
| `adb shell pm list packages \| grep -i webview` | `com.google.android.webview` | WebView provider |
| `adb shell dumpsys package com.google.android.webview \| grep versionName` | `107.0.5304.36` | Chromium 107, modern JS/CSS OK |
| `adb shell id` | `uid=2000(shell)` | not root; enough for install/launcher |
| `adb shell cat /proc/meminfo \| head -3` | `MemTotal: 1023588 kB` | ~1 GB RAM, keep dashboard light |
| `adb shell df /data` | `27.0G size, 26.7G free` | plenty of storage |

## RAM

| Command | Output | Note |
|---|---|---|
| `adb shell dumpsys meminfo \| head -45` | `net.frameo.frame 147MB`, `system 47MB`, `com.adups.fota 12.5MB`, `inputmethod.latin 11MB`, `rockchip.update.service 6.8MB` | disable candidates: adups.fota, rockchip.update; `daemonsu` running → SuperSU root present |
| `adb shell su -c id` | `uid=0(root)` | **root available** |
| `adb shell pm list packages -s` | 32 system pkgs | bloat: `com.adups.fota`, `com.adups.fota.sysoper`, `android.rockchip.update.service`, `com.DeviceTest`, `com.android.phasebeam`, `com.android.music`, `com.android.musicfx`, `android.rk.RockVideoPlayer`, `com.android.rk.mediafloat` |
| `adb shell pm list packages -3` | _(empty)_ | no user apps |

## Security

| Command | Output | Note |
|---|---|---|
| `adb shell getprop ro.build.version.security_patch` | `2016-07-05` | unpatched since 2016 (Dirty COW, BlueBorne, KRACK) |
| `adb shell getprop ro.adb.secure` | `1` | ADB needs RSA key approval |
| `adb shell su -c "netstat -tln"` | `0.0.0.0:45076 LISTEN` | open on LAN |
| `adb shell su -c "cat /proc/net/tcp"` | port `B014` (45076) uid `10002` | |
| `adb shell dumpsys package net.frameo.frame \| grep userId` | `userId=10002` | port 45076 + root user = Frameo |
| `adb shell getprop ro.build.tags` / `ro.build.fingerprint` | `release-keys` / `rockchip/rk312x/rk312x:6.0.1/MXC89K/user.wanghy.20260307…` | built 2026-03-07, patch level 2016 |

## WebView signature

| Command | Output | Note |
|---|---|---|
| `adb shell pm path com.google.android.webview` | `/system/app/webview/webview.apk` | system app |
| `apksigner verify --print-certs webview.apk` | `DOES NOT VERIFY … requires signature scheme v2` | v1-only signed; use keytool |
| `keytool -printcert -jarfile webview.apk` | `CN=Android, android@android.com` SHA256 `CF:2A:B3:81…85:25` | **not Google's key** |
| `keytool -printcert -jarfile framework-res.apk` | same DN, SHA256 `2D:37:0C:21…35:22` | platform key |
| AOSP public certs (testkey/platform/shared/media/networkstack) | no match | vendor-private keys with default DN |

→ Google-signed WebView updates can't install via `adb install -r` (signature mismatch). Only path: replace `/system/app/webview/` as root.

## Launcher / navigation

| Command | Output | Note |
|---|---|---|
| `adb shell dumpsys package net.frameo.frame \| grep category.HOME` | _(empty)_ | M doesn't print filters here |
| `adb shell dumpsys activity activities \| grep -i home` | `cat=[android.intent.category.HOME] cmp=net.frameo.frame/.ui.activities.AWaitForDeferredInitCompleted` | Frameo is HOME, no other launcher |
| `adb shell getprop qemu.hw.mainkeys` | _(empty)_ | |
| `adb shell dumpsys window policy` | `mStatusBarLayer=-1`, `mSystem=(0,0)-(1280,800)` | no status bar, no nav bar → no Home/Back |

## Toolchain (Mac)

| Command | Output | Note |
|---|---|---|
| `java -version` / `java_home -V` | JDK 11 default, 17 + 21 available | use 21 |
| `brew install --cask android-commandlinetools` | SDK root `/opt/homebrew/share/android-commandlinetools` | |
| `brew install gradle` | gradle 9.8.1 | only to generate wrapper |
| `yes \| sdkmanager --licenses; sdkmanager "platforms;android-35" "build-tools;35.0.0"` | installed | |
| `gradle wrapper --gradle-version 8.11.1` | failed: `app` dir missing → `mkdir app`, retry OK | pins Gradle 8.11.1 (AGP 8.7.3) |

## Example 01: Hello Frame (`examples/01-hello-frame/`)

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

| Command | Output | Note |
|---|---|---|
| `./gradlew assembleDebug` | `BUILD SUCCESSFUL`, `app-debug.apk` 798K | |
| `adb install -r app/build/outputs/apk/debug/app-debug.apk` | `Success` | user app, no root |
| `adb shell am start -n dev.framio.hello/.MainActivity` | `Starting: Intent { cmp=dev.framio.hello/.MainActivity }` | |
| `adb exec-out screencap -p > hello.png` | clock + `Frame · Android 6.0.1 · WebView 107.0.5304.36 · 1280×800` | JS bridge works |

### Toolchain upgrade

| Command | Output | Note |
|---|---|---|
| `sdkmanager --list` (needs JAVA_HOME 17+) | latest `platforms;android-37.x`, `build-tools;37.0.0`, `platform-tools 37.0.1` | adb already latest |
| `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"` | installed | |
| `./gradlew wrapper --gradle-version 9.8.1` then `./gradlew wrapper` | `gradle-9.8.1-bin.zip` | 2nd run refreshes scripts/jar |
| AGP 8.7.3 → 9.4.1, Kotlin 2.1.0 → 2.4.21, SDK 35 → 37 | `BUILD SUCCESSFUL`, APK 932K | AGP 9 built-in Kotlin: no kotlin-android plugin in app |
| `./gradlew buildEnvironment \| grep kotlin-gradle-plugin` | `2.2.10 -> 2.4.21` | root pin wins |
| `--warning-mode all` | `Configuration.setVisible` deprecated | from a plugin, not our scripts |
| `org.gradle.configuration-cache=true` | build 2s → 560ms | |
| `adb install -r …` + `am start` + screencap | `Success`, renders same | works on Android 6 |

### Hot corner (open the app from the frame)

| Command | Output | Note |
|---|---|---|
| `adb shell appops set dev.framio.hello SYSTEM_ALERT_WINDOW allow` | `SYSTEM_ALERT_WINDOW: allow` | overlay permission, no Settings UI on frame |
| `adb shell input keyevent KEYCODE_HOME` + screencap | dot invisible | white dot on white UI, and covers Frameo's X at top-left |
| `adb shell dumpsys window windows` | `mFrame=[16,16][72,72]` | overlay exists → moved bottom-left, dark fill + light ring |
| `adb shell input swipe 44 756 44 756 1000` | `mFocusedApp=dev.framio.hello/.MainActivity` | long-press opens the app |
| `adb shell dumpsys window windows \| grep "Window #"` | `KeyguardScrim`, `dev.framio.hello` (ty=2002), `frameo…AScreenSlidePager` | dot sits above Frameo |
| `adb logcat -d -b events \| grep framio` | `START … from uid 10024` → `am_finish_activity … app-request` | opened via dot, closed via Exit, no crash |

### Reboot: dot gone

| Command | Output | Note |
|---|---|---|
| `adb shell dumpsys activity services dev.framio.hello` | `(nothing)` | service not running |
| `adb shell dumpsys package dev.framio.hello \| grep stopped=` | `stopped=false` | not in stopped state |
| `adb logcat -d -b all \| grep framio` | `Unable to launch app dev.framio.hello/10024 for broadcast BOOT_COMPLETED: process is bad` | no crash logged → firmware blocks background start at boot |
| `adb shell getprop \| grep -iE "rk\.\|boot"` | `ro.rk.soc=rk3126`, `ro.rk.bt_enable=false`, `ro.boot.selinux=permissive` | no BT; SELinux permissive |
| `adb shell pm \| grep -i home` | _(empty)_ | no `set-home-activity` on M → use "Always" in chooser |

→ Fix: be the HOME app (system starts it in the foreground at boot) and start the hot corner from there.

Escape hatches: on-screen **Exit** button · `adb shell am force-stop dev.framio.hello` · reboot (Frameo is still HOME) · `adb uninstall dev.framio.hello`

## Summary

Android 6.0.1 (API 23) · Rockchip RK312x · armeabi-v7a · 1 GB RAM · 1280×800 @160dpi · WebView Chromium 107 · shell user (no root) · stock app `net.frameo.frame`
