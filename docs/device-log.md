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

Escape hatches: on-screen **Exit** button · `adb shell am force-stop dev.framio.hello` · reboot (Frameo is still HOME) · `adb uninstall dev.framio.hello`

## Summary

Android 6.0.1 (API 23) · Rockchip RK312x · armeabi-v7a · 1 GB RAM · 1280×800 @160dpi · WebView Chromium 107 · shell user (no root) · stock app `net.frameo.frame`
