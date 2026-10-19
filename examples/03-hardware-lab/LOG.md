# Example 03: Hardware Lab — command log

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` · `adb` = `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`

## Libraries

| Command | Output | Note |
|---|---|---|
| `curl …/maven-metadata.xml` (Google Maven, Maven Central) | `lifecycle-runtime-compose 2.11.0`, `core-ktx 1.19.1`, `material3 1.4.0` (BOM 2026.09.00), `kotlinx-coroutines 1.11.0`, `junit 4.13.2` | latest stable |
| `unzip -p <aar> AndroidManifest.xml \| grep minSdkVersion` | lifecycle-runtime-compose, lifecycle-runtime, core, core-ktx: `23` | all still support Android 6 |
| `adb shell pm list packages -s` | `com.android.packageinstaller`, `com.android.documentsui` present | permission dialog and file picker work |

## Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew assembleDebug` | first try: `Error parsing AndroidManifest.xml` | `--` inside an XML comment |
| `./gradlew testDebugUnitTest` | 53 tests, 0 failures | pure logic: DSP, SNTP, YUV, sensor math, heartbeat |
| first run of `SntpTest` | `expected:<…334> but was:<…333>` | fraction → ms truncated; now rounds |
| 7 hand mutations (iowait busy, flat by noise, no knock refractory, right = left gain, no channel 14, late boundary `>=`, chime into gaps) | each failed ≥ 1 test | sources restored |
| `./gradlew lintDebug` | 10 errors: `MissingPermission` (scan, P2P, notify), `RestrictedApi` (dispatchKeyEvent) | guarded or API 33+ only → annotated with the reason |
| `lint-results-debug.xml \| grep -c NewApi` | `0` | **NewApi never fires** with Kotlin 2.4 / AGP 9.4: missed 2 real crashes |
| `python3 -I tools/api23_check.py` | `checked 749 platform references against API 23`, exit 0 | own check against `platforms;android-23` |
| same, with `Process.waitFor(long, TimeUnit)` put back | `MISSING java/lang/Process.waitFor(JLjava/util/concurrent/TimeUnit;)Z`, exit 1 | the check bites |
| `javap -cp android-23.jar java.lang.Process` | shows Java 17's `inputReader()` | javap reads `java.*` from the JDK: the script extracts classes from the jar first |

## Crashes found on the frame

| Command | Output | Note |
|---|---|---|
| full self-test, Root lab | `NoSuchMethodError: … waitFor(JLjava/util/concurrent/TimeUnit;)Z` | API 26 → poll `exitValue()` |
| Media self-test | `NoSuchMethodError: No direct method <init>(Landroid/media/MediaFormat;)V` | API 29 → fresh `getTrackFormat()` |
| `AudioRecord` via `app_process` | `Segmentation fault` | needs an op package: `Looper.prepareMainLooper(); ActivityThread.systemMain()` |

`guard {}` and the self-test runner now also catch `LinkageError`, so a missing API is a FAIL, not a crash.

## Full self-test

`adb shell am start -n dev.framio.lab/.LabActivity --ez selftest true` → `SELFTEST_DONE pass=46 warn=5 fail=0 absent=17 info=24 manual=9`

| Lab | Result | Note |
|---|---|---|
| system | 4× Cortex-A7 1008 MHz, 3 online at idle; benchmark ×2.68 on 4 threads | hotplug brings cores up under load |
| display | 1280×800 @ 57.17 Hz; Animate: 53 fps, 19 janks / 240 | `Choreographer` callbacks with nothing drawn come every other vsync (31.8 Hz): not a usable headless measure |
| touch | gslX680-d708, x 0–1279, y 0–799 | |
| sensors | accelerometer 118.5 Hz at GAME, stand upright leaning 20°; 12 other sensors silent | |
| speaker | chime 29106/29106 frames; Visualizer FFT peak **991 Hz** for 1 kHz; EQ 5 bands | output path verified without a mic |
| mic | flat: sample-to-sample change 0.10 LSB | codec offset drifts ~1.5 s after power-up; first detector took that for a signal |
| media | VPU H.264 encode 720p 34–47 fps; decode HW ≈ SW 14–32 fps (ByteBuffer output) | HEVC/VP9 software only |
| graphics | Mali-400 GLES 2.0, max texture 4096, **no fragment highp**, plasma 958 fps (1 layer) | WebGL 1 only |
| network | 2.4 GHz only, DNS 17 ms, TLS 1.2 to all 6 hosts (incl. ISRG Root X1), 9 Mbps, local server OK | first TLS run failed: no SNI on an unconnected `SSLSocket` |
| time | NTP offset 9 ms, exact alarm +80 ms, job +4.1 s, no RTC | |
| storage | eMMC write 27.6 MB/s, 4 KiB sync 327 IOPS, SQLite 3.8.10.2 1000 inserts 310 ms | |
| missing | BT, camera, NFC, GPS, vibrator, TTS, speech, telephony, fingerprint, IR blaster, Play services, thermal: absent | |
| root | `su` → `uid=0` **without any prompt** | no SuperSU app: every app gets root silently |

| Command | Output | Note |
|---|---|---|
| `adb forward tcp:18080 tcp:8080; curl 127.0.0.1:18080/status` | `{"uptimeMs":4641122,…,"android":"6.0.1"}` | local server reachable |
| `curl http://192.168.11.206:8080` from the Mac | timeout | Mac on `192.168.178.x`, frame on the repeater's `192.168.11.x` |

## Quick sound test

| Command | Output | Note |
|---|---|---|
| Speaker → Play test sound (`QuickSound.chime`, static `AudioTrack`, C5 E5 G5, 660 ms) | `C–E–G chime fully played (29106 frames, 660 ms)` | `playbackHeadPosition` reached the end |
| background service with `beep 10`, Frameo / other apps in front | chime heard every 10 s | confirmed by ear on the frame |

## Background

Driven with `adb shell am start -n dev.framio.lab/.LabActivity --es background start|stop …`, `--es alarm on|off`, `--es autostart on|off` (the service isn't exported: `am startservice` → `Requires permission not exported from uid 10025`).

| Command | Output | Note |
|---|---|---|
| foreground service, `KEYCODE_HOME`, open Frameo, 60 s | 60 beats, max gap 1042 ms, 0 late, importance `foreground service (125)` | keeps running behind other apps |
| `KEYCODE_SLEEP` 75 s, wakeLock off, then on | `mWakefulness=Asleep`; both: max gap 1042 ms, 0 late | screen off doesn't pause it |
| `cat /sys/kernel/debug/wakeup_sources` | `rk312x_remote` active since boot, `usb_pcd` active | kernel never suspends: IR driver holds a wake lock |
| `dumpsys power` | `PARTIAL_WAKE_LOCK 'frameo:partialWakeLock'` | Frameo holds one too |
| plain service, `am kill dev.framio.lab` | killed → `service restarted by Android … (START_STICKY)` within ~1 s | |
| foreground service, `am kill dev.framio.lab` | pid unchanged | not killable "safely" |
| `su -c "kill -9 <pid>"` (what the low-memory killer does), plain and foreground | not back after 120 s; no "Scheduling restart" | |
| minute alarm on, process killed | `BroadcastQueue: Unable to launch app dev.framio.lab … MinuteAlarmReceiver: process is bad` | alarm can't revive it |
| framework restart (`su -c "stop; start"`) with autostart on | `Unable to launch app dev.framio.lab … BOOT_COMPLETED: process is bad` | same as example 01 at boot |
| same, with `lowmem_package_filter.xml` + `dev.framio.lab`/`launcher` bind-mounted over the original | still `process is bad` | the vendor list is not the gate; mount removed, framework restarted |
| `dumpsys activity processes \| grep -i "bad processes"` | _(empty)_ | not AOSP's crash-based bad list |
| `strings services.odex \| grep lowmem` | `etc/lowmem_package_filter.xml`, `packageForLowmemFilter` | vendor code in AMS; logic not decompiled |

→ Background work runs fine **while the process lives** (screen off included). Once the process dies, nothing but a user/HOME launch brings it back. Use a foreground service started from the HOME app (the launcher is started by the system at boot).
