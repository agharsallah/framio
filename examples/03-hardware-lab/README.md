# Example 03: Hardware Lab

Native Jetpack Compose app that exercises every piece of hardware and the Android APIs on the Veidoo F10
(RK3126, Android 6.0.1). Each **lab** is a page with live readouts, things to try, and an automated
**self-test**. Open it from the launcher's **Lab** tile or `adb shell am start -n dev.framio.lab/.LabActivity`.

| Lab | What it shows | Key APIs |
|---|---|---|
| System | cores and clocks (hotplug), CPU load, RAM, storage, build, WebView, benchmark | `/proc`, `/sys`, `ActivityManager`, `StatFs` |
| Display | brightness (window + system), rotation, frame pacing, test patterns | `WindowManager.LayoutParams`, `Settings.System`, `Choreographer` |
| Touch | multi-finger trails, report rate, full-screen coverage grid | `MotionEvent` via `pointerInteropFilter` |
| Sensors | accelerometer live, stand orientation, knock detection, which sensors are real | `SensorManager` |
| Speaker | quick chime, tone generator, sweep, live FFT, equalizer, volumes, every playback API | `AudioTrack`, `Visualizer`, `Equalizer`, `SoundPool`, `ToneGenerator`, `MediaPlayer`, `Ringtone` |
| Microphone | level meter, scope, record + play back, per-source check | `AudioRecord` |
| Video & images | codec list, H.264 encode → decode → play, JPEG decode speed | `MediaCodec`, `MediaMuxer`, `MediaExtractor`, `VideoView`, `BitmapRegionDecoder` |
| Graphics | GL ES limits, shader fill-rate, Compose canvas stress | `EGL14`, `GLES20`, `GLSurfaceView` |
| Network | Wi-Fi link/scan, latency, TLS per host, speed, mDNS, Wi-Fi Direct, local web server | `WifiManager`, `ConnectivityManager`, `SSLSocket`, `NsdManager`, `WifiP2pManager`, `ServerSocket` |
| Time & power | clock vs NTP, exact alarm, job, wake lock, Doze, notifications | SNTP over UDP, `AlarmManager`, `JobScheduler`, `PowerManager` |
| Background | service heartbeat off-screen (with audible beep), minute alarms, start at boot, process deaths | `Service` + `startForeground`, `BOOT_COMPLETED`, `AlarmManager` |
| Buttons & keys | input devices, live key log (case buttons, IR remote, USB keyboard) | `dispatchKeyEvent`, `InputManager` |
| Storage & USB | volumes, SD/USB hot-plug, eMMC speed, SQLite, system file picker | `getExternalFilesDirs`, `UsbManager`, `SQLiteDatabase`, `ACTION_OPEN_DOCUMENT` |
| Not on this frame | Bluetooth, camera, NFC, GPS, TTS, … claimed vs real | read-only probes of each API |
| Root | kernel state, open ports, backlight blink, screenshot | `su` (SuperSU) |

## Layout

```
app/src/main/java/dev/framio/lab/
  LabActivity.kt, LabApp.kt     single activity: side rail + selected lab, adb extras
  Labs.kt                       the list of labs (add one here)
  core/                         Lab, Check/Status, SelfTestRunner, Events, Root, Sysfs, Format
  core/ui/                      theme, Section/StatGrid/Sparkline/…, self-test card
  labs/<name>/                  one package per lab: <Name>Lab.kt (UI + self-test) + its helpers
app/src/test/…                  JVM unit tests for the pure parts (DSP, parsing, math)
tools/api23_check.py            checks every compiled framework call exists on API 23
```

A lab is a `Lab(id, title, summary, accent, selfTest, content)`. Pure logic (signal stats, SNTP, YUV,
sensor math…) lives in plain Kotlin files without Android imports so it can be unit-tested.

## Build, test, install

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew testDebugUnitTest assembleDebug lintDebug
python3 -I tools/api23_check.py        # needs: sdkmanager "platforms;android-23"
adb install -r app/build/outputs/apk/debug/app-debug.apk

# optional grants (the app can also ask on screen)
for p in RECORD_AUDIO ACCESS_FINE_LOCATION CAMERA; do adb shell pm grant dev.framio.lab android.permission.$p; done
adb shell appops set dev.framio.lab WRITE_SETTINGS allow
```

`tools/api23_check.py` exists because Android Lint's NewApi check reports nothing with this Kotlin/AGP
combination, and two calls newer than API 23 (`Process.waitFor(long, TimeUnit)`,
`MediaFormat(MediaFormat)`) compiled fine and then crashed on the frame.

## Driving it over adb

```bash
adb shell am start -n dev.framio.lab/.LabActivity --es lab speaker            # open a lab
adb shell am start -n dev.framio.lab/.LabActivity --ez selftest true          # run every self-test
adb shell am start -n dev.framio.lab/.LabActivity --es selftest_lab network   # one lab
adb shell am start -n dev.framio.lab/.LabActivity --es background start --ez foreground true --ez wakeLock false --ei beep 10
adb shell am start -n dev.framio.lab/.LabActivity --es background stop
adb logcat -s FramioLab                       # CHECK|lab|name|STATUS|detail and EVENT|lab|text lines
adb shell run-as dev.framio.lab cat files/selftest.json
```

`EVENT` lines record hands-on actions (knock, clap, key press, coverage grid complete) and the
background heartbeat, so a test done on the frame can be checked from the log.

Results on the frame: [LOG.md](LOG.md).
