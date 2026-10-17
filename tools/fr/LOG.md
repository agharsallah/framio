# fr: command log

Veidoo F10 (Android 6.0.1, RK3126) on USB · Mac: Go 1.27.2, scrcpy 5.0.1, ffmpeg 7.1, adb 37.0.1 · 2026-10-18

## Setup

| Command | Output | Note |
|---|---|---|
| `brew install go scrcpy` | `go1.27.2 darwin/arm64`, `scrcpy 5.0.1` | scrcpy uses `adb` from PATH (or `$ADB`) |
| `scrcpy --list-encoders` | `OMX.rk.video_encoder.avc` (h264), `OMX.google.vp8.encoder` | the hardware H.264 encoder is used |

## Spikes

### S1: recording

| Command | Output | Note |
|---|---|---|
| `scrcpy --no-window --no-playback --no-audio --record=native.mkv --time-limit=10` (launcher) | 1280×800, 101 frames in 9.6 s | **native size works** although `media_codecs.xml` declares max 1280×720 |
| same, `-m 1152` | 1152×720, 100 frames | |
| same at native, Hardware Lab plasma running | 232 frames in 9.3 s = **25 fps**; `dex2oat` was using 40 % CPU meanwhile | |
| same, `-m 1152`, plasma | 216 frames in 9.3 s = 23 fps | no gain from the smaller size → **default: native, no `--max-size`** |
| `screenrecord --time-limit 5` (native) | `rc=0`, 1280×800, 123 frames in 5 s = 25 fps | the fallback engine works at native size too |
| frame extracted with ffmpeg | landscape, correct way up | `hwrotation=90` doesn't affect captures |
| `scrcpy … --record=reboot.mkv &`, then `adb reboot` after 5 s | `Recording complete`; `ffmpeg -c copy` → mp4 OK | **mkv survives a reboot mid-recording** |
| boot after that | 29 s until `sys.boot_completed=1` | |

### S2: screenshot speed

| Command | Output | Note |
|---|---|---|
| launcher: `exec-out 'screencap -p'` ×5 | 1.63–1.71 s (after boot, busy CPU) | earlier, idle: 1.33 s |
| launcher: `exec-out screencap` (raw) ×5 | 1.08–1.55 s | 4 MB over USB at ~6 MB/s |
| launcher: `exec-out 'screencap \| gzip -1'` ×5 | **0.55–0.57 s**, 250 KB | **default** |
| Frameo slideshow | stayed on its "Welcome" screen, no photo shown | photo measured with a pushed raw image instead |
| photo-like raw (macOS wallpaper, 1280×800 RGBA) pushed: `cat` vs `gzip -1 <` | 0.87–1.41 s vs 0.72–1.45 s, 1.47 MB compressed | gzip ≈ raw for a photo, 2–3× faster for UI → gzip stays the default; `--png` kept as the fallback |

### S3: launch and tap by text

| Command | Output | Note |
|---|---|---|
| `aapt2 dump badging` on 01, 02, 03, 04 | `launchable-activity` for all four | 02-launcher has a LAUNCHER activity too |
| `am start -W -n dev.framio.hello/.MainActivity` | `TotalTime: 2802` | launch time printed on Android 6 |
| `am start -W -a MAIN -c HOME -p dev.framio.launcher` | `Status: ok`, `TotalTime: 247` | `-p` works → fallback for HOME-only apps |
| `am start -W -a MAIN -c LAUNCHER -p no.such.pkg` | `Error: Activity not started, unable to resolve Intent` | how fr detects "try the next intent" |
| `uiautomator dump` on the launcher | 3.2 s; `text="Lab" bounds="[909,587][986,644]"` | Compose text is visible; slower than the 2 s target, kept |

### S4: Wi-Fi adb

Not run: `adb tcpip` restarts adbd and would have cut off two other sessions using the frame at
the same time. `fr connect` is therefore not built; USB only.

## Quirks found while building

| Command | Output | Note |
|---|---|---|
| `adb features` | _(empty)_ | no shell protocol |
| `adb shell false; echo $?` | `0` | → `Shell` appends `echo "::fr-rc=$?"` |
| `adb shell 'printf nonewline; echo "::fr-rc=$?"'` | `nonewline::fr-rc=0` | marker can follow output on the same line |
| `fr sh 'echo hi; exit 3'` (first version) | `no exit code in output` | `exit` ended the shell before the marker → script now runs in a `( … )` subshell |
| `pidof no.such.pkg` | prints every PID, exit 0 | toybox `pidof` is broken → `ps <name>`, exact NAME match |
| `logcat --help` | no `--pid`; has `-b crash`, `-T '<time>'` | PID filter on the Mac; `--since` uses the device's `date` |
| `settings get system show_touches` | `null` | restored with `settings delete` after `--touches` |
| `pkill --help` | `pkill [-l\|-SIGNAL] …` | `pkill -INT screenrecord` stops the device engine cleanly |
| `screenrecord --verbose`, stopped by `pkill -INT` on a static screen | `recorded 1 frames in 3 seconds`; ffprobe duration `N/A` | not a bug: one frame has no duration → fr says "1 frame (nothing moved on screen)" |
| `fr rec --engine device --time 5s` (first version) | `adb pull: context deadline exceeded` | cleanup timeout started before the recording → now starts after it |
| `fr mirror --rec`, stopped after 6 s | 0-byte mkv, `EBML header parsing failed` | stopped before the first frame → fr now deletes it and says so |
| `fr rec start` in smoke.sh, twice | `scrcpy exited: signal: segmentation fault` | not reproduced in 10 direct runs; both times right after a device-engine recording → fr retries a startup crash once |
| `logFilter` unit test | `dev.framio.labx` lines kept for `dev.framio.lab` | substring match → whole-word `mentions()` |

## Results

| Command | Output | Note |
|---|---|---|
| `go vet ./... && go test ./...` | 28 tests pass | fixtures from the frame + `testdata/fake-adb` |
| `ls -l ~/.local/bin/fr` | 5.6 MB | target < 6 MB |
| `time fr help` | 0.02 s | |
| `fr shot` on the launcher ×3 | 0.61 / 0.61 / 0.80 s | includes the foreground-app lookup and PNG encoding |
| `fr shot` on Mawaqit (busy screen) vs `fr shot --png` | 1.1 s vs 3.7 s | |
| `fr find` | 3.9 s | uiautomator dump |
| `fr run examples/01-hello-frame --no-logs` | `build 4.4 s · install 7.0 s · launch 2121 ms` | |
| `fr tap Exit` (Hello Frame) | `tapped "Exit" at 1196,744` → previous app in front | WebView text found by uiautomator |
| `fr screen off` / `on` | backlight 187 → 0 → 187 | |
| `fr logs --tag FrSmoke` + `adb shell log -t FrSmoke …` | line shown live | |
| `fr bug smoke` | 8 files in 4.6 s | |
| `fr doctor` | all ✓; warnings: no shell protocol, encoder max 1280×720 | |
| `tools/fr/smoke.sh` | **15 passed, 0 failed** (2 min 50 s, incl. a 30 s+ wait for another session's lock) | one scrcpy startup segfault, retried |
