# framio

Custom dashboard and apps for a Veidoo F10 Android photo frame (Frameo 1.32.12).

**Device:** Android 6.0.1 (API 23) · Rockchip RK312x · armeabi-v7a · 1 GB RAM · 1280×800 @160dpi · WebView Chromium 107 · rooted (SuperSU) · stock app `net.frameo.frame`

Full command log with outputs: [docs/device-log.md](docs/device-log.md)

## Examples

| | |
|---|---|
| [`examples/01-hello-frame`](examples/01-hello-frame) | Kotlin app with a full-screen WebView, a clock page and a JS → Kotlin bridge (`window.Frame`) |

## Setup

```bash
# on the frame: Settings → About → Beta program ON → ADB access ON
brew install android-platform-tools
brew install --cask android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 17+ required
```

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

## Escape hatches

Frameo stays the HOME app while prototyping, so a reboot always brings it back.

```bash
adb shell am force-stop dev.framio.hello   # close the app
adb uninstall dev.framio.hello             # remove it completely
adb reboot
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
