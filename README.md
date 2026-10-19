# framio

Custom dashboard and apps for a Veidoo F10 Android photo frame (Frameo 1.32.12).

**Device:** Android 6.0.1 (API 23) · Rockchip RK312x · armeabi-v7a · 1 GB RAM · 1280×800 @160dpi · WebView Chromium 107 · rooted (SuperSU) · stock app `net.frameo.frame`

Full command log with outputs: [docs/device-log.md](docs/device-log.md)

## Examples

| | |
|---|---|
| [`examples/01-hello-frame`](examples/01-hello-frame) | Kotlin app with a full-screen WebView, a clock page and a JS → Kotlin bridge (`window.Frame`) |
| [`examples/02-launcher`](examples/02-launcher) | Jetpack Compose HOME app: clock, Photos (Frameo) and a shelf of every installed framio app (found automatically, styled by `dev.framio.tile.*` metadata), hot corner dot as the Home button · [LOG](examples/02-launcher/LOG.md) |
| [`examples/03-hardware-lab`](examples/03-hardware-lab) | Native Compose app with 15 labs (display, touch, sensors, speaker, mic, video, GPU, network, time, keys, storage, root…), each with an automated self-test |
| [`examples/04-mawaqit`](examples/04-mawaqit) | Mawaqit mosque-screen clone: a real Mawaqit mosque or computed times, adhan on the speaker, iqama countdown, azkar, night mode, Frameo photo-frame mode; web panel on the LAN and encrypted remote control through ntfy · [LOG](examples/04-mawaqit/LOG.md) · [API](examples/04-mawaqit/REMOTE.md) |
| [`examples/04-little-meadow`](examples/04-little-meadow) | Calm learning app for ages 2–5 in native Compose: five clay 3D friends, 31 voice-guided activities in 7 places (numbers, colours and art, letters and animals, music, good habits, puzzles with real-time 3D shapes, feelings, stories and a night light), adaptive levels, sticker book, wiggle breaks, daily limit and bedtime; English and German; parent dashboard on the LAN with family voice messages · [LOG](examples/04-little-meadow/LOG.md) · [DESIGN](examples/04-little-meadow/DESIGN.md) |
| [`examples/06-pomodoro`](examples/06-pomodoro) | Calm focus timer in native Compose: Classic, Deep (ritual, dim Calm screen or backlight off, knock to peek, park a thought), Flow (counts up, tiered breaks) and Gentle start; a slow landscape on breaks, history, notes and honest insights, synthesized cues and soundscapes, a built-in keyboard; offline, nothing leaves the frame · [LOG](examples/06-pomodoro/LOG.md) |

## Setup

```bash
# on the frame: Settings → About → Beta program ON → ADB access ON
brew install android-platform-tools
brew install --cask android-commandlinetools
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 17+ required, also by sdkmanager
yes | sdkmanager --licenses
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

Toolchain: Gradle 9.8.1 · AGP 9.4.1 (built-in Kotlin) · Kotlin 2.4.21 · compileSdk/targetSdk 37 · minSdk 23

Each example needs a `local.properties` (git-ignored):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## Build, install, run

```bash
cd examples/01-hello-frame
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.framio.hello/.MainActivity
```

**On the frame:** tap **Exit** to go back to the previous screen.

### Launcher (02)

```bash
cd examples/02-launcher
./gradlew assembleRelease     # R8-minified, debug-signed: ~15 MB RAM vs ~31 MB for assembleDebug
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set dev.framio.launcher SYSTEM_ALERT_WINDOW allow   # once: lets the hot corner draw over apps
adb shell input keyevent KEYCODE_HOME   # "Select a Home app" → Framio → ALWAYS (screenshot to find the taps)
adb shell dumpsys activity activities | grep -i home   # cmp=dev.framio.launcher/.LauncherActivity
```

**On the frame:** tap a tile; long-press the dot in the bottom-left corner to come back home from any app.

## Capture and control: `fr`

[`tools/fr`](tools/fr) is one Go binary for daily work on the frame: screenshots, recordings, a
clickable mirror window, build → install → launch → logs, and tap/key control. It wraps adb, scrcpy
and ffmpeg. [README](tools/fr/README.md) · [PLAN](tools/fr/PLAN.md) · [LOG](tools/fr/LOG.md)

```bash
brew install go scrcpy ffmpeg && (cd tools/fr && go build -o ~/.local/bin/fr .) && fr doctor
fr shot                               # captures/<date>/<time>_<app>.png (git-ignored)
fr rec --time 30s --touches           # mp4; Ctrl-C stops early
fr mirror                             # live window: click = tap, drop an APK = install
fr run examples/03-hardware-lab       # build, install, launch, follow its logs
fr tap "Lab" · fr key home · fr logs dev.framio.lab --since 2m --dump
export FR_LOCK=/tmp/framio-frame.lock # when several sessions share the frame
```

## Sharing the frame

One frame, several sessions and agents: everything that changes the device (install, `am start`, taps, reboots)
takes turns through one lock, the directory `/tmp/framio-frame.lock` (`mkdir` takes it, `rmdir` releases it,
stale after 5 min). `tools/fr` (with `FR_LOCK`) and the examples' `tools/frame.sh` use it;
[`scripts/frame-lock.sh`](scripts/frame-lock.sh) adds a first-come-first-served queue, a heartbeat for long
holds, instant release of a crashed holder's lock, and an owner line others can read.

```bash
FRAME_OWNER=me scripts/frame-lock.sh run -w "install mawaqit" adb install -r app-release.apk
scripts/frame-lock.sh status          # frame: held by framio-7a · install pomodoro · 23 s · 1 waiting
FRAME_WAIT_MAX=600 scripts/frame-lock.sh run …   # give up (exit 75) instead of waiting forever
```

Keep holds short, leave the frame as you found it, and after taking the lock check the foreground app and the
installed build before trusting a screenshot: someone else may have changed them in between.

## Backup

Read-only full backup (raw partitions + `/data` archive + Frameo files, md5-verified). Keep it **outside the repo**: it contains photos and Frameo's pairing keys.

```bash
scripts/backup.sh ~/framio-backup/$(date +%F)
cd ~/framio-backup/<date> && shasum -a 256 -c SHA256SUMS   # verify
```

Partition table, commands and restore notes: [docs/backup-log.md](docs/backup-log.md) and `RESTORE.md` inside the backup folder.

## Escape hatches

Android 6 has no `pm set-home-activity` and `pm clear` keeps the "Always" choice. Uninstalling the launcher makes Frameo HOME again.

```bash
adb uninstall dev.framio.launcher          # back to Frameo as HOME (works without a reboot)
adb shell am force-stop dev.framio.hello   # close Hello Frame
adb uninstall dev.framio.hello             # remove it completely
```

## Useful commands

```bash
adb devices                                   # check the connection
adb shell su -c id                            # root shell check → uid=0(root)
adb exec-out screencap -p > screen.png        # screenshot
adb logcat -s chromium                        # WebView console.log output
adb shell dumpsys meminfo | head -45          # RAM per process
adb shell pm list packages -s                 # system apps
adb shell su -c "pm disable <pkg>"            # disable a system app (undo: pm enable <pkg>)
```

Debug the WebView from desktop Chrome: open `chrome://inspect` while the app runs.
