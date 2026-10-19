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

## Hardware

SuperSU ignores a multi-line `su -c '…'` (prints usage, rest runs as shell) → push a script and run `adb shell su -c "sh /data/local/tmp/probe.sh"`.

### SoC, memory, storage

| Command | Output | Note |
|---|---|---|
| `getprop ro.rk.soc` · `ro.rksdk.version` | `rk3126` · `RK30_ANDROID6.0.1-SDK-v1.00.00` | RK3126 (not RK3126C) |
| `grep "CPU part" /proc/cpuinfo` | 4× `0xc07`, `neon vfpv4 idiva` | 4× Cortex-A7 |
| `cat …/cpu0/cpufreq/scaling_available_frequencies` · `scaling_governor` | `816000 1008000` · `interactive` | max 1.0 GHz; cores hotplug (cpu1 was offline) |
| `dumpsys SurfaceFlinger \| grep GLES` | `ARM, Mali-400 MP, OpenGL ES 2.0` | **WebGL 1 only**, no WebGL 2 |
| `cat /proc/swaps` | `/dev/block/zram0 131064` | 1 GB RAM + 128 MB zram |
| `uname -a` | `Linux 3.10.0 … Sat Mar 7 2026` | old Rockchip BSP kernel |
| `cat /sys/bus/mmc/devices/mmc0:0001/name` | `SLD32G` | 32 GB eMMC |
| `cat /proc/cmdline` | `androidboot.selinux=permissive … mtdparts=rk29xxnand:…` | Rockchip parameter layout, no verified boot |
| `ls -l /dev/block/platform/*/by-name` | `uboot misc resource kernel boot recovery backup cache metadata kpanic system(1 GB) radical_update userdata` | `mmcblk0p1`…`p13` |
| `ls /sys/class/thermal` · `find /sys -name "temp*_input"` | _(empty)_ | no temperature readout |
| `ls /sys/class/rtc` · `ls /dev/rtc*` | _(empty)_ · `/dev/rtc_rk808_test` | **no RTC**: boots at `01-01 01:00`, clock is right only after Wi-Fi/NTP |
| `dumpsys battery` | `AC powered: true`, `present: false` | mains only, no battery |

### Display, touch, buttons

| Command | Output | Note |
|---|---|---|
| `dumpsys display \| grep DisplayDeviceInfo` | `1280 x 800, fps=57.166, density 160` | |
| `cat /sys/class/graphics/fb0/modes` · `getprop ro.sf.hwrotation` | `U:800x1280p-57` · `90` | panel is portrait, rotated to landscape |
| `dmesg \| grep -i mipi` | `rk32-mipi … mipi_dsi probe success` | MIPI-DSI panel |
| `settings get system accelerometer_rotation` | `1` | auto-rotate on |
| `cat /sys/class/backlight/rk28_bl/{brightness,max_brightness}` | `187` / `255` | root can write it (0 = backlight off) |
| `settings get system screen_off_timeout` | `2147483647` | screen never times out |
| `getevent -pl` | `gslX680-d708` `ABS_MT_POSITION_X 0–1280`, `_Y 0–800`, `INPUT_PROP_DIRECT` | Silead multitouch, 1:1 with screen px |
| `cat /sys/bus/i2c/devices/*/name` + driver link | `0-0026 gs_mir3da` (bound), `0-0040 gslX680-d708` (bound), `2-0055 gt8xx` (**no driver**) | gt8xx is a leftover probe, not real hardware |
| `getevent -pl` | `rk29-keypad`: `KEY_VOLUMEDOWN KEY_VOLUMEUP KEY_POWER KEY_WAKEUP` | ADC/GPIO keys; check which buttons the case has with `adb shell getevent -l` |
| `getevent -pl` | `rkxx-remotectl.25`: digits, D-pad, `KEY_HOME KEY_BACK KEY_MENU`, media keys | IR remote driver loaded; receiver on board not confirmed |

### Sensors

| Command | Output | Note |
|---|---|---|
| `pm list features` | accelerometer, gyroscope, compass, barometer, light, step counter, GPS, NFC, Bluetooth… | **generic tablet list, mostly false** |
| `dumpsys sensorservice` | 8 HAL sensors (accel, magnetic, orientation, gyro, proximity, light, pressure, temperature) | only the accelerometer has events |
| same, accelerometer `last 50 events` | `0.1, 9.5, 3.4` m/s² @ 100 Hz | MiraMEMS `gs_mir3da` on i2c-0; frame on its stand, tilted ~20° |
| `/sys/bus/i2c/devices` | only `gs_mir3da` + touch | no chip behind the gyro, compass, light, proximity, pressure or temperature entries |

### Audio

| Command | Output | Note |
|---|---|---|
| `cat /proc/asound/cards` · `/proc/asound/pcm` | `RK_RK312X` · `rk312x-hifi-0` and `rk312x-voice-1`, playback + capture | built-in RK312x codec |
| `dumpsys media.audio_flinger` | `Sample rate: 44100 Hz`, `pcm16`, 2 ch, `Output device: 0x2 (SPEAKER)` | |
| `dumpsys media.audio_policy` | outputs: `SPEAKER`; inputs: `BUILTIN_MIC` | HDMI/BT/headset in policy file, not attached |
| `tinymix 0` · `tinymix 1` | `Playback Path: OFF RCV SPK HP … BT …` · `Capture MIC Path: MIC OFF, Main Mic, …` | HAL switches path per stream |
| `tinymix 0 SPK; tinyplay tone.wav -D 0 -d 0; tinymix 0 OFF` | `Playing sample: 2 ch, 44100 hz, 16 bit` | 1 s 880 Hz tone sent to the speaker |
| `dumpsys audio \| grep -A5 STREAM_MUSIC` | `Max: 15`, `Current: 2 (speaker): 5` | music at 5/15 on the speaker |
| `/sys/class/switch/*` | only `usb_audio*` | no headphone jack detect |
| `tinycap` | `not found` | → `AudioRecord` via `app_process` (below) |
| `CLASSPATH=mictest.dex app_process /system/bin MicTest` | `Segmentation fault` | M `AudioRecord` needs an op package name |
| same after `Looper.prepareMainLooper(); ActivityThread.systemMain()` | `state=1`, all rates 8–48 kHz OK; after a 0.5 s pop: `rms=1274.1 peak=1277` | **flat DC, ±3 LSB → no mic signal**, input path is probably not wired |
| `pm list packages \| grep -iE "tts\|pico"` · `ls /system/tts` | _(empty)_ · `lang_pico` | voice data but no TTS engine app → `TextToSpeech` won't speak |

### Video decode

| Command | Output | Note |
|---|---|---|
| `grep MediaCodec /system/etc/media_codecs.xml` | `OMX.rk.video_decoder.{avc,vp8,m2v,m4v,h263,flv1,mjpeg}` max `1920x1088` | hardware decode |
| same | `OMX.google.hevc.decoder` max `1024x1024`, `OMX.google.vp9.decoder` | HEVC/VP9 software only → **encode clips as H.264** |
| same | `OMX.rk.video_encoder.avc` max `1280x720` | hardware H.264 encode |

### Radios and buses

| Command | Output | Note |
|---|---|---|
| `lsmod` · `cat /sys/class/rkwifi/chip` | `rk915` · `RK915` | Rockchip RK915 Wi-Fi over SDIO (`mmc2`) |
| `dumpsys wifi` scan results by frequency | only `2412–2462` MHz (15 BSS) | **2.4 GHz only**; `p2p0` = Wi-Fi Direct |
| `pm list packages \| grep -i bluetooth` | _(empty)_ | **no Bluetooth stack** (no `com.android.bluetooth`) |
| `getprop ro.rk.bt_enable` · `ls /sys/class/rfkill` | `false` · only `rfkill0 phy0 wlan` | no BT radio; RK915 is Wi-Fi only |
| `ls /system/etc/bluetooth` · `/dev/rtk_btusb` | `rtkbt.conf`, Broadcom `.hcd` blobs · Realtek BT USB node | SDK leftovers; a USB BT dongle still needs the missing stack |
| `service call bluetooth_manager 6` | `enabled: true`, `Fail to bind to: Intent { act=android.bluetooth.IBluetooth }` | confirms no stack; undone with `service call bluetooth_manager 8` → `bluetooth_on 0` |
| `cat /sys/kernel/debug/usb/devices` | `dwc_otg_hcd` (OTG), `ehci_hcd` + `ohci_hcd` (host), no devices | USB host exists; untested with a device (OTG adapter + `dmesg`) |
| `getprop sys.usb.config` | `adb` (`persist: mtp,adb`) | |
| `ls /sys/class/mmc_host` · `cat …/mmc1/device/uevent` | `mmc0 mmc1 mmc2` · `rksdmmc@10214000` | SD card controller on `mmc1`, no card inserted |
| `getprop ro.rk.hdmi_enable` · `ls /sys/class/display` | `false` · _(empty)_ | HDMI block in SoC, not wired |
| `dumpsys media.camera` | `Number of camera devices: 0` | no camera |
| `dumpsys nfc` · `ls /system/lib/hw` | `Can't find service: nfc` · no `gps.*` HAL | no NFC, no GPS |
| `dumpsys ethernet` · `ls /sys/class/timed_output` | `Not tracking any interface` · _(missing)_ | no Ethernet, no vibrator |

### Background execution (example 03, Background lab)

| Command | Output | Note |
|---|---|---|
| foreground service behind Frameo / screen off 75 s | 0 missed heartbeats | runs fine while the process lives |
| `cat /sys/kernel/debug/wakeup_sources` · `dumpsys power` | `rk312x_remote` + `usb_pcd` active · `frameo:partialWakeLock` | CPU never suspends |
| `kill -9` the app, then alarm / framework restart | `Unable to launch app dev.framio.lab … process is bad` | firmware refuses background (re)starts: alarms, sticky restarts, BOOT_COMPLETED |
| same with the app added to `/system/etc/lowmem_package_filter.xml` (bind mount) | still refused | Frameo is listed there, but the list isn't the gate |
| app `su -c id` | `uid=0`, no prompt | no SuperSU app: **every app gets root silently** |

### What we can use and how

| Block | Status | Kotlin (native) | WebView JS |
|---|---|---|---|
| Display 1280×800 @57 Hz | ✅ | `FLAG_KEEP_SCREEN_ON`; `window.attributes.screenBrightness` (per window, no permission) | 1 CSS px = 1 px; animate only `transform`/`opacity` |
| Backlight | ✅ | `Settings.System.SCREEN_BRIGHTNESS` (needs `adb shell appops set <pkg> WRITE_SETTINGS allow`); off at night: root `echo 0 > /sys/class/backlight/rk28_bl/brightness` | expose via `window.Frame` bridge |
| Touch (multitouch) | ✅ | standard `MotionEvent` / Compose `pointerInput` | Pointer Events / touch events |
| Accelerometer | ✅ | `SensorManager` `TYPE_ACCELEROMETER`: portrait vs landscape stand, knock/tap to wake | `devicemotion` event (Chromium reads the same sensor) |
| GPU Mali-400 | ⚠️ GLES 2.0 | `GLSurfaceView` ES 2.0 | WebGL 1 only, CSS 3D OK; keep canvas small |
| Speaker | ✅ | `SoundPool` (chimes), `MediaPlayer`/`AudioTrack`; Media3/ExoPlayer if its AAR still declares minSdk ≤ 23 | `<audio>`, Web Audio; set `mediaPlaybackRequiresUserGesture = false` for autoplay |
| Video | ✅ H.264 ≤ 1080p | `MediaPlayer`/`VideoView`, `MediaCodec` | `<video>` with H.264 MP4 |
| Wi-Fi 2.4 GHz | ✅ | `HttpURLConnection`/OkHttp, `NsdManager` (mDNS: find Home Assistant, printers…), Wi-Fi Direct via `WifiP2pManager` | `fetch`, WebSocket, EventSource |
| Background | ✅ while alive | foreground `Service` (survives `am kill`, runs with the screen off); start it from the HOME app — nothing else can revive a killed process | — |
| Time | ⚠️ no RTC | wait for `ACTION_TIME_CHANGED` / plausible year before showing a clock | don't trust `Date()` right after boot |
| IR remote | ❓ driver only | `onKeyDown` (D-pad, `KEYCODE_HOME`, media keys) | `keydown` events |
| Hardware keys | ❓ check case | `onKeyDown` `KEYCODE_VOLUME_UP/DOWN` | `keydown` (only if the bridge forwards them) |
| SD card / USB host | ❓ untested | `StorageManager` / `UsbManager`; USB serial with `usb-serial-for-android` | via bridge |
| Microphone | ❌ flat signal | (`AudioRecord` works, records silence) | `getUserMedia` would give silence |
| Bluetooth / BLE | ❌ no stack | external bridge: ESP32 over Wi-Fi (HTTP/MQTT) or USB serial | — |
| TTS | ❌ no engine | play pre-rendered audio, or install an engine APK (e.g. eSpeak NG) | `speechSynthesis` has no voices |
| Camera, NFC, GPS, HDMI, Ethernet, vibrator, light/compass/gyro/pressure/proximity | ❌ | — | — |

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

### Reboot with examples/02-launcher as HOME

| Command | Output | Note |
|---|---|---|
| `adb reboot; adb wait-for-device; until boot_completed=1` | ~23s | |
| `adb shell dumpsys activity activities \| grep -i home` | `cmp=dev.framio.launcher/.LauncherActivity`, focused | launcher starts at boot |
| `adb shell dumpsys window windows \| grep "Window #"` | `dev.framio.launcher` overlay + `LauncherActivity` | **dot survives reboot** |
| `adb logcat -d -b all \| grep framio` | `START … LauncherActivity from uid 0`; `Unknown element <queries>`, `unavailable shared library androidx.window.*` | warnings harmless on M |
| `adb shell ps \| grep frameo` | _(only dev.framio.launcher)_ | **Frameo not running until Photos is tapped** → no photo sync |

Escape hatches: on-screen **Exit** button · `adb shell am force-stop dev.framio.hello` · reboot (Frameo is still HOME) · `adb uninstall dev.framio.hello`

## Summary

Android 6.0.1 (API 23) · Rockchip RK3126 (4× Cortex-A7 1.0 GHz, Mali-400 GLES 2.0) · armeabi-v7a · 1 GB RAM · 32 GB eMMC · 1280×800 @160dpi · WebView Chromium 107 · root via SuperSU · stock app `net.frameo.frame`

Works: touch, accelerometer, speaker, H.264 decode, 2.4 GHz Wi-Fi · Missing: Bluetooth, mic signal, camera, RTC, TTS engine, other sensors
