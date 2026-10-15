# framio

Custom dashboard and apps for a Veidoo F10 Android photo frame (Frameo 1.32.12).

**Device:** Android 6.0.1 (API 23) · Rockchip RK312x · armeabi-v7a · 1 GB RAM · 1280×800 @160dpi · WebView Chromium 107 · rooted (SuperSU) · stock app `net.frameo.frame`

Full command log with outputs: [docs/device-log.md](docs/device-log.md)

## Examples

| | |
|---|---|
| [`examples/01-hello-frame`](examples/01-hello-frame) | Kotlin app with a full-screen WebView, a clock page and a JS → Kotlin bridge (`window.Frame`) |
| [`examples/02-launcher`](examples/02-launcher) | Jetpack Compose HOME app: clock, Photos (Frameo) and Hello Frame tiles, hot corner dot as the Home button · [LOG](examples/02-launcher/LOG.md) |

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
