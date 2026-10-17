# fr: capture and control tool for the frame

> A small command-line tool for everyday frame development. It takes screenshots, records the
> screen, opens a live window you can click in, runs the build → install → launch → logs loop, and
> drives touch and keys. It combines tools that already work (adb, scrcpy, ffmpeg) instead of
> rebuilding them.

| | |
|---|---|
| Status | Built 2026-10-18: spikes S1–S3 and milestones M0–M4 done, `smoke.sh` 15/15 on the frame ([LOG](LOG.md)). Changes from this plan: see [Outcome](#outcome) |
| Code | `tools/fr`: Go, standard library only, binary `fr` |
| Target | Mac → Veidoo F10 over USB. Works with any Android 5+ device |
| Builds on | [device-log](../../docs/device-log.md), README "Useful commands", [scripts/backup.sh](../../scripts/backup.sh), [03 Hardware Lab LOG](../../examples/03-hardware-lab/LOG.md) |

## Contents

1. [TL;DR](#1-tldr)
2. [Goals and non-goals](#2-goals-and-non-goals)
3. [Reuse before building](#3-reuse-before-building)
4. [Language: Go](#4-language-go)
5. [Frame facts that shape the design](#5-frame-facts-that-shape-the-design)
6. [Architecture](#6-architecture)
7. [Commands](#7-commands)
8. [How the main commands work](#8-how-the-main-commands-work)
9. [Captures folder](#9-captures-folder)
10. [Performance targets](#10-performance-targets)
11. [Testing](#11-testing)
12. [Plan: spikes, then milestones](#12-plan-spikes-then-milestones)
13. [Risks](#13-risks)
14. [Later](#14-later)
15. [References](#15-references)

### Outcome

What changed while building it (details and numbers in [LOG.md](LOG.md)):

- **Native 1280×800 recording works** in spike S1 at about 25 fps, although the encoder declares a
  1280×720 maximum. There is no default `--max-size`; `--size 1152` is still available.
- **S4 (Wi-Fi adb) was not run.** `adb tcpip` restarts adbd, and two other sessions were using the
  frame. `fr connect` was not built.
- **Added `FR_LOCK`.** Several Claude sessions shared the frame during the build, so commands that
  change or read the screen take a shared lock directory (same protocol as
  `examples/04-little-meadow/tools/frame.sh`).
- **scrcpy 5.0.1 segfaulted at startup twice.** A run that dies within 4 s is retried once.
- **`fr tap "text"` looks twice.** A WebView's accessibility tree fills in shortly after it loads.
- **Size:** about 3,200 lines of Go plus 600 lines of tests, double the estimate. It still has no
  third-party dependencies and builds to a 5.6 MB binary.

---

## 1. TL;DR

- **What it is.** `fr` is one Go binary on the Mac. It installs nothing on the frame and runs no
  daemon. Each command calls `adb`, `scrcpy` or `ffmpeg`, then exits.
- **No rebuilding.** scrcpy already provides the live view, mouse and keyboard control, and recordings
  of any length on Android 5+. adb covers install, logs and input. ffmpeg covers GIFs and remuxing.
  `fr` adds what is missing: short commands, defaults tuned for this frame, files named after what was
  on screen, and the dev loop.
- **Fast where it counts.** A screenshot is sent as `screencap | gzip -1` and converted to PNG on the
  Mac. That takes **0.50 s, against 1.33 s for `screencap -p`** (measured today, §5), because PNG
  encoding on the 1 GHz Cortex-A7 is the slow part.
- **Size.** About 1,500 lines of Go in 2 packages, with no third-party dependencies. 4 short spikes,
  then 4 milestones, about 4 days in total.

```bash
fr shot                          # captures/2026-10-18/153012_dev.framio.launcher.png
fr rec --time 30s --touches      # captures/2026-10-18/153140_dev.framio.lab.mp4
fr mirror                        # window: click = tap, type = keys, drop an APK = install
fr run examples/03-hardware-lab  # gradle → install → launch (cold start ms) → live logs
fr tap "Always"                  # tap by visible text, or: fr tap 640 400
```

## 2. Goals and non-goals

| # | Goal | Done when |
|---|---|---|
| G1 | Screenshot in one command | PNG saved and its path printed in < 1 s; `--copy` puts the image on the clipboard |
| G2 | Record any length, stop with Ctrl-C | the file always plays in QuickTime, even after Ctrl-C or a reboot mid-recording |
| G3 | Live view with control | a window where a click is a tap, typing goes to the frame, and dropping an APK installs it |
| G4 | Dev loop for any Gradle app | `fr run <dir>` builds, installs, launches, prints timings and follows that app's logs |
| G5 | Script the device | tap, swipe, text, keys, tap by text, reboot and wait, backlight; works from shell scripts and Claude Code |
| G6 | Reusable | no config file; works with any adb device; the frame's quirks live in one package |

**Non-goals:** an app or agent on the frame, our own video streaming or decoder, a desktop GUI (the
scrcpy window is the GUI), device farms, iOS, and replacing Gradle or Android Studio.

## 3. Reuse before building

| Need | Best existing tool | On this frame (API 23) | Decision |
|---|---|---|---|
| Live view with mouse and keyboard | **scrcpy 5.0.1** | supported (Android 5+); no audio (needs Android 11) | **adopt**: `fr mirror` starts it |
| Recordings of any length | scrcpy `--record --no-window` | no time limit; since 4.1 it falls back to a smaller size when the encoder refuses one | **adopt**: `fr rec` default |
| Recording without scrcpy | `screenrecord` v1.2 (on the frame) | 180 s max; `--bugreport` adds a timestamp overlay | **fallback**: `fr rec --engine device` |
| Screenshot | `screencap` (on the frame); scrcpy has no screenshot command | raw RGBA, 12-byte header | **wrap**: gzip on the frame, PNG on the Mac |
| Install, launch, logs, input, UI tree | adb, `am`, `pm`, `monkey`, `logcat`, `input`, `uiautomator` | all present | **wrap** |
| GIF, remux, probe | ffmpeg 7.1 (installed) | n/a | **wrap** |
| All-in-one GUIs (Escrcpy, QtScrcpy) | Electron/Qt shells around scrcpy | probably, since they use scrcpy | **skip**: not scriptable, don't know the repo, heavy. They can still be installed alongside |
| Android Studio "Running Devices" | needs Android Studio | this repo builds with Gradle from the terminal | **skip** |
| Go/Python ADB client libraries (gadb, adbutils) | talk to the adb server socket directly | n/a | **skip**: starting `adb` costs milliseconds, the slow part is USB (~6 MB/s). One dependency fewer |

## 4. Language: Go

| | Go | Rust | Python (uv) | Bash |
|---|---|---|---|---|
| Startup | ms | ms | 0.1 to 0.3 s | ms |
| Ships as | one static binary | one static binary | venv / `uv tool` | a script that relies on BSD vs GNU tools |
| Third-party deps for this tool | **none**: `os/exec`, `os/signal`, `flag`, `image/png`, `compress/gzip`, `encoding/xml` | clap, anyhow, png, flate2, ctrlc | adbutils, typer | n/a |
| Several things at once (record + logs + capture) | goroutines | threads | asyncio | `&` and `wait` |
| Build | < 1 s | 20 to 40 s cold | n/a | n/a |

**Pick Go.** Every option is fast enough, because the limits are the frame (Cortex-A7, USB at
~6 MB/s) and the external tools, not the host language. Go produces a static binary that starts in
milliseconds and needs **no third-party dependencies**, so there is nothing to keep updated. Rust
(already installed) would be as fast, but it needs five crates and builds more slowly for no gain
here. Bash stops scaling at around ten commands because of quoting and missing exit codes; see the
`root`/`raw` helpers in `backup.sh`.

## 5. Frame facts that shape the design

Measured today with read-only commands, frame on USB:

| Command | Output | What `fr` does about it |
|---|---|---|
| `adb features` | _(empty)_ | **no shell protocol**: `adb shell false` returns exit code **0** on the Mac, stderr is mixed into stdout, lines end in `\r\n`. Every shell call ends with `; echo "::rc=$?"`, `fr` parses that and strips `\r`. Binary data only goes through `exec-out` |
| `exec-out screencap \| wc -c` | `4096012` = 12-byte header (`1280 800 1`, RGBA_8888) + pixels | decode on the Mac. Header length = total − w×h×4, so newer Android's 16-byte header also works |
| `time exec-out screencap -p` | 1.33 s, of which 1.30 s is capture + PNG encoding on the frame | don't encode on the frame |
| `time exec-out screencap` (raw) | 0.89 s (0.47 s capture, the rest is the 4 MB transfer) | |
| `time exec-out 'screencap \| gzip -1'` | **0.50 s**, 254 KB (launcher screen) | default; a photo compresses less, see spike S2 |
| 16 MiB `dd` over `exec-out` | 2.7 s, about 6 MB/s | USB is slow: don't move raw pixels; a 15 MB APK takes ~2.5 s |
| `media_codecs.xml` | `OMX.rk.video_encoder.avc` max **1280×720**; the screen is 1280×800 | native-size recording may fail → 1152×720 keeps 16:10, see spike S1 |
| `screenrecord --help` | v1.2: `--size --bit-rate --bugreport --time-limit` (max 180) | fallback engine |
| `logcat --help` | has `-b crash`, `-v color`, `-T`; **no `--pid`** | filter by PID on the Mac |
| `pidof no.such.pkg` | prints **every PID**, exit 0 | toybox `pidof` is broken here → use `ps <pkg>` |
| `ps dev.framio.launcher` | header + `u0_a26 5411 5032 … S dev.framio.launcher` | PID is column 2. The name filter can match by prefix, so check that the NAME column matches exactly |
| `command -v cmd` | missing | no `cmd package resolve-activity` → get the activity from `aapt2`, or `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` |
| `dumpsys window windows \| grep mCurrentFocus` | `Window{… u0 dev.framio.launcher/dev.framio.launcher.LauncherActivity}` | foreground package for file names |
| `ls /system/bin/uiautomator` | present | `dump` → tap by text |
| `settings get system show_touches` | `null` | after `--touches`, restore the exact previous value |
| Mac: `adb` on PATH is `~/Downloads/platform-tools` (37.0.1); the SDK has its own `platform-tools/adb` | two adb binaries | two different versions restart each other's server, so `fr` picks one and passes it to scrcpy as `ADB` |

From earlier logs: every app gets root without a prompt, and SuperSU ignores a multi-line `su -c`, so
scripts are pushed instead. The Mac (`192.168.178.x`) and the frame (`192.168.11.x`) are on
different subnets. The default JDK is 11, but Gradle needs 17+.

## 6. Architecture

### 6.1 Overview

```
Mac                                                           Frame (Android 6)
┌────────────────────────────────────────────────┐
│ fr  (one Go binary, exits after each command)  │
│                                                │
│  main: one file per command group              │
│    flags, output, file names, signals          │
│        │                                       │
│        ▼                                       │
│  internal/adb: Device ──────── exec ─► adb ──────── USB ──► adbd: screencap, am, pm,
│    Shell (+exit code), ExecOut, Push, Pull,    │                   logcat, input, uiautomator
│    ps / focus / getprop / UI-dump parsing      │
│                                                │
│  scrcpy ────────────────────── exec ─► scrcpy ───── USB ──► scrcpy-server (pushed and
│  ffmpeg, gradlew, aapt2 ────── exec            │                   removed by scrcpy)
└────────────────────────────────────────────────┘
        │
        ▼
  captures/2026-10-18/  *.png  *.mp4  *.gif  *_bug/
```

### 6.2 Rules

1. **The tools are the engine.** `fr` combines adb, scrcpy, ffmpeg and Gradle. If one of them already
   does something, `fr` passes flags to it instead of reimplementing it.
2. **All device I/O goes through `internal/adb`.** The quirks (exit codes, `\r\n`, broken `pidof`,
   which adb, which device) are handled there and nowhere else. It is also the reusable part: a later
   web panel or a test harness can import it.
3. **No state except files.** There is no daemon, config file or database. The only runtime file is
   `captures/.rec.pid`, and it exists only while a background recording runs.
4. **Leave the device as it was.** Anything `fr` changes (show touches, stay awake, temp files in
   `/data/local/tmp/fr-*`) is restored or deleted in a `defer`, including on Ctrl-C and SIGTERM.
5. **Show the commands.** `-v` prints each underlying command and how long it took, ready to paste
   into a `LOG.md`, which is how this repo already works.
6. **Root only when asked.** `fr` uses `su` only in commands that need it (`screen`, `sh --root`),
   because every app on this frame gets root without a prompt.

### 6.3 Code layout

```
tools/fr/
  PLAN.md            this file
  README.md          install + command reference (M0)
  LOG.md             commands run on the frame, with outputs (from M0)
  smoke.sh           runs each command once on the frame and checks the result (M3)
  go.mod             module github.com/agharsallah/framio/tools/fr, no require lines
  main.go            command table, global flags (-s, -v, -o), dispatch, signal handling
  shot.go            shot
  rec.go             rec, rec start/stop, gif
  mirror.go          mirror
  app.go             run, install, start, stop, restart, clear, uninstall, apps
  logs.go            logs
  input.go           tap, find, swipe, long, text, key
  device.go          info, doctor, wait, reboot, screen, sh, bug
  files.go           captures folder, file names, clipboard, open
  internal/adb/
    adb.go           Device: Run, Shell, ExecOut, Push, Pull, Root
    parse.go         ps, mCurrentFocus, getprop, screencap header, uiautomator XML, logcat lines
    adb_test.go      table tests over testdata/
    testdata/        real outputs captured from the frame, plus fake-adb (a replay script)
```

Each command gets its own `flag.FlagSet`. A table in `main.go` maps each name to a
`func(ctx, args) error` and is also the source of `fr help`. A CLI framework such as cobra would save
about 40 lines here, which isn't worth a dependency.

### 6.4 The adb layer

```go
type Device struct {
    Bin, Serial string // resolved once: which adb, which device
    Verbose     bool
}

func (d *Device) Shell(ctx context.Context, cmd string) (out string, rc int, err error) // adds `; echo "::rc=$?"`, strips \r
func (d *Device) ExecOut(ctx context.Context, cmd string) (io.ReadCloser, error)       // binary-safe stream
func (d *Device) Root(ctx context.Context, script string) (string, int, error)         // one line: su -c; several: push /data/local/tmp/fr-*.sh
func (d *Device) PID(ctx context.Context, pkg string) (pid int, ok bool, err error)    // `ps <pkg>`, exact NAME match, never pidof
func (d *Device) Focus(ctx context.Context) (pkg, activity string, err error)          // mCurrentFocus
func Quote(s string) string                                                            // single quotes for the device shell
```

- **Which adb:** `$ADB`, otherwise `adb` on PATH. `fr` passes the same path to scrcpy as `ADB`.
- **Which device:** `-s`, otherwise `$ANDROID_SERIAL`, otherwise the only one attached. If more than
  one is attached, `fr` stops with an error that lists the serials, like `backup.sh`'s preflight.

## 7. Commands

Global flags: `-s SERIAL`, `-v` (print underlying commands), `-o DIR` (captures folder).

| Command | What it does | Built on |
|---|---|---|
| **Capture** | | |
| `fr shot [label] [--copy] [--open]` | screenshot to PNG, prints the path | `exec-out 'screencap \| gzip -1'`, Go `image/png` |
| `fr shot --burst 10 --every 1s` | a series of screenshots | same, in a loop |
| `fr rec [label] [--time 30s] [--size 1152] [--fps 30] [--bitrate 4M] [--touches] [--show] [--gif]` | records until Ctrl-C or `--time` | scrcpy `--no-window --no-playback --no-audio --record`; `--show` opens the mirror window too |
| `fr rec --engine device [--stamp]` | up to 180 s without scrcpy; `--stamp` adds a timestamp overlay | `screenrecord [--bugreport]` |
| `fr rec start [label]` / `fr rec stop` | background recording for scripts | scrcpy detached, `captures/.rec.pid`, SIGINT to stop |
| `fr gif <video> [--width 640] [--fps 12]` | GIF for PRs and docs | ffmpeg `palettegen` + `paletteuse` |
| **View and control** | | |
| `fr mirror [--rec] [--top] [--off]` | live window: click to tap, type to send keys, drop an APK to install, drop any other file to push it to `/sdcard/Download` | scrcpy `--stay-awake --show-touches --window-title`; `--off` = `--turn-screen-off` |
| `fr tap X Y` / `fr tap "Always"` | tap a point, or the center of the element whose text matches | `input tap`; `uiautomator dump` + bounds |
| `fr find "text"` | list matching elements with their bounds | `uiautomator dump` |
| `fr swipe X1 Y1 X2 Y2 [ms]` / `fr long X Y [ms]` | swipe or long-press (hot corner: `fr long 44 756`) | `input swipe` |
| `fr text "hello world"` | type text | `input text`, spaces as `%s`, shell characters escaped |
| `fr key home\|back\|menu\|power\|wakeup\|<KEYCODE>` | key events | `input keyevent` |
| **Apps** | | |
| `fr run [dir] [--release] [--no-logs]` | build, install, stop, launch, follow logs, with timings | `./gradlew assemble…`, `aapt2 dump badging`, `am start -W` |
| `fr install <apk\|dir>… [--launch] [--downgrade]` | install one or more APKs | `adb install -r [-d]` |
| `fr start\|stop\|restart\|clear\|uninstall <pkg>` | app lifecycle | `am start -W`, `am force-stop`, `pm clear`, `adb uninstall` |
| `fr apps` | installed user apps with versions | `pm list packages -3`, `dumpsys package` |
| `fr logs [pkg] [--tag T]… [--since 5m] [--raw]` | one app's logs, which survive restarts | `logcat -v threadtime -b main -b system -b crash`, PID filter on the Mac |
| **Device** | | |
| `fr info` | model, Android, serial, IP, screen, foreground app, RAM, uptime, root | `getprop`, `wm`, `dumpsys`, `/proc/meminfo` |
| `fr doctor` | checks the Mac and the frame, and prints a fix for each problem | §8.8 |
| `fr bug [label]` | evidence folder: screenshot, logs, activities, windows, meminfo, top, info | the commands above |
| `fr wait` / `fr reboot [--wait]` | wait for `sys.boot_completed=1`, print the seconds | `wait-for-device` + `getprop` polling |
| `fr screen on\|off\|<0-255>` | backlight (root), or `KEYCODE_POWER` without root | `/sys/class/backlight/*/brightness` |
| `fr sh [--root] <cmd>` | shell command with a real exit code | `Shell` / `Root` |

**Left out on purpose:** push/pull and port forwarding, because `adb push`, `adb pull` and
`adb forward` already do them well.

## 8. How the main commands work

### 8.1 `fr shot`

1. Start `exec-out 'screencap | gzip -1'` and `Focus()` (for the file name) at the same time.
2. Gunzip on the Mac and read the header (width, height, format). Check that the size is
   header + w×h×4 and that the format is 1 (RGBA_8888). If not, fall back to `screencap -p`.
3. Wrap the pixels in an `image.NRGBA` without copying them. Encode with
   `png.Encoder{CompressionLevel: png.BestSpeed}`; Go writes RGB when every pixel is opaque. Write to
   `.tmp`, rename it, and print the path.
4. `--copy` runs `osascript -e 'set the clipboard to (read (POSIX file "…") as «class PNGf»)'` so the
   image can be pasted into Slack, a PR or Claude. `--open` runs `open`.

### 8.2 `fr rec`

- **scrcpy engine (default):** `scrcpy --no-window --no-playback --no-audio --record=<f>.mkv
  --max-fps=30 --video-bit-rate=4M [--max-size=1152] [--time-limit=N] [--show-touches]`, run with
  `ADB=<fr's adb>`.
- **Record to .mkv, deliver .mp4.** Matroska can still be read if scrcpy is killed or the frame
  reboots mid-recording, whereas an MP4 without its final index can't. Afterwards,
  `ffmpeg -i f.mkv -c copy -movflags +faststart f.mp4` remuxes losslessly in under a second (QuickTime
  can't open mkv), and the mkv is deleted.
- **Ctrl-C:** `fr` catches SIGINT, forwards it to scrcpy, waits up to 5 s, then remuxes. It prints
  the path, duration, size and fps (from `ffprobe`).
- **Device engine:** `screenrecord --size 1152x720 --bit-rate 4M --time-limit N
  /data/local/tmp/fr-rec.mp4`. To stop it, `fr` runs `pkill -INT screenrecord` instead of relying on
  Ctrl-C, which may not reach the frame without the shell protocol. It then waits until
  `ps screenrecord` lists nothing, pulls the file and deletes it from the frame. `--touches` sets
  `show_touches 1` and restores the previous value afterwards.
- **`rec start` / `rec stop`:** the same scrcpy command in its own process group, with its PID and
  output path stored in `captures/.rec.pid`. `stop` sends SIGINT, waits and remuxes. This lets a
  script or Claude Code run `fr rec start; fr tap "Lab"; …; fr rec stop`.

### 8.3 `fr mirror`

`scrcpy --stay-awake --show-touches --window-title "F10 68384696" --push-target=/sdcard/Download/`,
plus `--always-on-top`, `--turn-screen-off` and `--record` when asked. The frame has no navigation
bar, so scrcpy's mouse shortcuts are handy: right-click is Back, middle-click is Home. The README will
list the main keyboard shortcuts (Mod is left ⌥ or left ⌘).

### 8.4 `fr run` and `fr install`

1. Find the Gradle root: the folder given (default `.`) or the nearest parent that has `gradlew`.
2. If `JAVA_HOME` isn't JDK 17+, set it from `/usr/libexec/java_home -v 21`, as the repo does.
3. Run `./gradlew assembleDebug` (`--release` runs `assembleRelease`) and stream its output. Stop if
   it fails.
4. Take the newest APK in `app/build/outputs/apk/<variant>/`.
5. Run `aapt2 dump badging` to get `package:` and `launchable-activity:`. `aapt2` comes from
   build-tools, found through `$ANDROID_HOME` or `sdk.dir` in `local.properties`. Apps that only have
   a HOME activity (02, the Hub) are started through the HOME intent instead (spike S3).
6. `adb install -r`. A failure becomes a hint, for example `INSTALL_FAILED_UPDATE_INCOMPATIBLE` →
   "signed with a different key: run `fr uninstall <pkg>` first".
7. `am force-stop`, then `am start -W -n pkg/activity`, which prints `TotalTime`, the launch time.
8. Print one summary line, for example `build 6.1 s · install 1.9 s · launch 812 ms`. Then follow the
   app's logs unless `--no-logs` is set.

### 8.5 `fr logs`

- Runs `logcat -v threadtime -T 1 -b main -b system -b crash`. `--since 5m` becomes
  `-T '<MM-DD hh:mm:ss.000>'`, based on the frame's own `date`.
- Keeps a line when its PID matches the app's current PID, when it matches a `--tag`, or when it is
  an `ActivityManager` or `AndroidRuntime` line that names the package (start, death, `FATAL
  EXCEPTION`).
- **Following restarts:** a `Start proc <pid>:<pkg>/…` line switches to the new PID immediately. As a
  backup, a goroutine checks `ps <pkg>` every second. When the PID changes, `fr` prints
  `── dev.framio.lab restarted (5411 → 6020) ──`.
- Colors by level (V D I W E F). `--raw`, or output to a pipe, prints plain text. Without a package it
  shows every line, colored.

### 8.6 Tap by text

`uiautomator dump /data/local/tmp/fr-ui.xml`, then `exec-out cat` it and delete the file. `fr` reads
the `text`, `content-desc`, `resource-id` and `bounds="[x1,y1][x2,y2]"` attributes of each node and
matches without case, trying exact matches before partial ones. It taps the center of the match. If
several nodes match, it lists them and `--nth N` picks one. Compose exposes on-screen text through
accessibility, so this works on the repo's apps. WebView content depends on Chromium's accessibility
tree. Choosing the HOME app becomes `fr key home; fr tap "Framio Hub"; fr tap "Always"` instead of
working out coordinates from a screenshot.

### 8.7 `fr bug`

Creates a folder `captures/<date>/<time>_bug[_label]/` with `screen.png`, `logcat.txt`
(`-d -t 2000`, all buffers), `crash.txt` (`-b crash -d`), `activities.txt`, `windows.txt`,
`meminfo.txt`, `top.txt` (`top -n 1 -m 15`) and `info.txt`. The adb calls run in parallel, so it
takes about 3 s. Attach the folder to an issue or refer to it from a `LOG.md` entry.

### 8.8 `fr doctor`

Prints ✓ or ✗ per line, each ✗ with the command that fixes it:

- **adb:** found, and which version. Warns if another adb with a different version exists, and lists
  where.
- **Device:** exactly one attached and in state `device`. For `unauthorized`: accept the prompt on the
  frame. For `offline`: replug the cable.
- **Mac tools:** scrcpy ≥ 4.1 (`brew install scrcpy`), ffmpeg and ffprobe, aapt2, Java 17+.
- **Frame:** Android version, whether the shell protocol is present, root, the encoder's maximum
  size, free space in `/data/local/tmp`.
- **Captures folder:** writable and git-ignored.

## 9. Captures folder

```
captures/                              git-ignored: the screen can show family photos
  2026-10-18/
    153012_dev.framio.launcher.png
    153140_dev.framio.lab_selftest.mp4
    153140_dev.framio.lab_selftest.gif
    154001_bug_timer-crash/
  .rec.pid                             only while `fr rec start` is running
```

- **Location:** `-o`, otherwise `$FR_OUT`, otherwise `<git top-level>/captures` inside a repo,
  otherwise `~/Movies/fr`.
- **Names:** `HHMMSS_<foreground package>[_label].<ext>`, with the label turned into a slug. Existing
  files are never overwritten; `-2`, `-3`… are added instead.
- `captures/` is added to the root `.gitignore` in M0.

## 10. Performance targets

Measured on the frame and recorded in `LOG.md`:

| Operation | Target | Baseline |
|---|---|---|
| `fr help` | < 20 ms | n/a |
| `fr shot`, app screen | ≤ 0.6 s | 1.33 s with `screencap -p`; 0.50 s with `screencap \| gzip -1` (§5) |
| `fr shot`, photo on screen | ≤ 0.9 s | to measure (S2) |
| `fr rec`, time to first frame | < 2 s | to measure (S1) |
| Recording | ≥ 25 fps at 1152×720 (or 1280×800), frame CPU < 50 % | the VPU encodes 720p at 34 to 47 fps (03 LOG) |
| `fr run` overhead on top of Gradle + install | < 0.5 s | n/a |
| Binary | < 6 MB, needs only adb, scrcpy and ffmpeg at runtime | n/a |

## 11. Testing

- **Unit tests (`go test ./...`), no device needed.** They cover:
  - exit-code marker parsing with `\r\n`, including output with no final newline
  - `ps` output (a header-only result means the app isn't running) and `mCurrentFocus` variants (null
    focus, popups)
  - the screencap header (12 and 16 bytes, and a wrong size)
  - uiautomator XML: bounds and match order
  - logcat `threadtime` lines and the `Start proc` PID switch
  - file naming and name collisions, and each command's flags

  The fixtures in `testdata/` are real output from the frame.
- **Command tests.** `fr` runs against `testdata/fake-adb`, a shell script that replays recorded
  output, chosen through `$ADB`. That covers `shot`, `logs`, `run` and `tap "text"` without a frame.
- **Device smoke test (`smoke.sh`, about 1 min).** It checks that:
  - the PNG is 1280×800
  - the MP4 lasts at least N seconds according to `ffprobe`
  - `fr sh false` exits with 1
  - `fr logs` picks up `log -t FrSmoke hi`
  - `fr run examples/01-hello-frame` reports `TotalTime`
  - `show_touches` is back to its previous value

  It must pass before a milestone counts as done.
- `go vet ./...` and `gofmt -l .` must be clean.

## 12. Plan: spikes, then milestones

### 12.1 Spikes (an hour or less each, yes/no exit, results in LOG.md)

| # | Question | How | Pass |
|---|---|---|---|
| S1 | Does scrcpy record this frame well? | `brew install go scrcpy`. Record 30 s at native size and at `-m 1152`. Compare with `screenrecord` at its default size, at 1280×720 and at 1152×720. Run `adb reboot` during one mkv recording | plays in QuickTime; correct orientation (the panel is portrait with `hwrotation=90`); ≥ 25 fps according to `ffprobe`; the mkv is readable after the reboot. **Result:** default size and engine |
| S2 | Fastest screenshot with a photo on screen | put Frameo's slideshow in front and time `-p`, raw and `gzip -1` 5 times each | pick the default. Keep another method as a flag only if it wins in some case |
| S3 | Launch and tap by text | `am start -W` on 01 and 03 (is `TotalTime` printed?); launch the HOME-only 02 through the HOME intent; time `uiautomator dump`; check the launcher's tile text is in the dump | all three launch; the dump takes < 2 s and finds the "Lab" tile |
| S4 | Wi-Fi adb across the repeater's subnet | `adb tcpip 5555; adb connect 192.168.11.206:5555; fr shot`, undone with `adb usb` | works → `fr connect` goes into M3. Fails → USB only, and `fr connect` is dropped |

### 12.2 Milestones

**M0 · Skeleton (½ day).** `go.mod`; `main.go` with dispatch and help; `internal/adb` (Shell with
exit codes, ExecOut, adb and device selection) with unit tests on fixtures from the frame; `fr info`,
`fr doctor`, `fr sh`; `captures/` in `.gitignore`; README install section.
*Done when:* `go vet ./... && go test ./...` pass, `fr doctor` is all ✓ on the Mac, and
`fr sh false; echo $?` prints `1`.

**M1 · Capture (1 day).** `fr shot` (`--copy`, `--open`, `--burst`); `fr rec` (scrcpy and device
engines, mkv to mp4, `--time`, `--touches`, `--stamp`); `fr mirror`; `fr gif`.
*Done when:* the S1 and S2 numbers are in LOG.md, a 10-minute recording plays, Ctrl-C at any moment
leaves a playable mp4, and `show_touches` is back to its previous value afterwards.

**M2 · Dev loop (1 day).** `fr run`, `fr install`, `start/stop/restart/clear/uninstall`, `fr apps`,
`fr logs`.
*Done when:* `fr run examples/03-hardware-lab` prints build, install and launch times followed by
`FramioLab` log lines; a `force-stop` plus relaunch prints the restart banner; and a crash's stack
trace shows up in `fr logs`.

**M3 · Control and evidence (1 day).** `tap/find/swipe/long/text/key` including tap by text,
`fr rec start/stop`, `fr bug`, `fr wait/reboot`, `fr screen`, and `fr connect` if S4 passed.
*Done when:* `smoke.sh` passes, and a shell script of about 20 lines that uses only `fr` records
opening the Lab from the launcher (`rec start` → `tap "Lab"` → `shot` → `rec stop`).

**M4 · Polish (½ day, optional).** `fr completion zsh`, a static script generated from the command
table; `.vscode/tasks.json` with Shot, Record 30 s, Mirror and Run current example, for one-click use
without a GUI; a section in the root README; `fr version` with the commit.

**Order:** S1–S4 (½ day) → M0 → M1 → M2 → M3 → M4, about 4 days.

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| The encoder refuses 1280×800 | high (its limit is 1280×720) | scrcpy shrinks the size by itself (4.1+); `-m 1152` becomes the default if S1 says so; that's 10 % less resolution |
| A future scrcpy drops Android 6 | low to medium | LOG.md records the last version that works; `fr doctor` warns; `brew pin scrcpy` |
| The two adb binaries fight over the server | already present on this Mac | one adb, also passed to scrcpy through `ADB`; `doctor` warns |
| No exit codes or separate stderr on Android 6 | certain | exit-code marker in `Shell`, covered by unit tests |
| Toybox quirks (`pidof` lists every process) | certain | `ps <pkg>` instead; every parser is tested on real frame output, never on assumed output |
| Family photos in captures | certain | `captures/` is git-ignored; `fr bug` folders stay local |
| uiautomator is slow or misses WebView text | medium | coordinates always work; `fr find` shows what it can see |
| Wi-Fi adb opens adbd on the LAN of a device with 2016 patches | only if S4 is used | `ro.adb.secure=1` requires the RSA key; USB stays the default; `fr connect --off` runs `adb usb` |
| Scope creep into a GUI app | medium | it's a non-goal: the scrcpy window and VS Code tasks come first; a web panel is under Later |

## 14. Later

Only if a real need appears:

- **`fr ui`:** a page on localhost (Go `net/http` + `embed`) with the captures gallery and buttons that
  call `internal/adb`. The live view stays in scrcpy.
- **`fr run --watch`:** rebuild and reinstall on save (Gradle `--continuous`).
- **`fr diff a.png b.png`:** visual regression check with a threshold, using the Go standard library.
- **`fr each <cmd>`:** run a command on every attached device (`-s` already covers one device).
- **Claude Code** needs nothing extra. `fr shot` prints a path it can open, `fr tap "…"` drives the
  UI, and `fr logs --since 1m` shows what happened.

## 15. References

- scrcpy: https://github.com/Genymobile/scrcpy, in particular `doc/recording.md`, `video.md`,
  `control.md`, `device.md` and `shortcuts.md`, and the v4.1 and v5.0.1 release notes
- AOSP sources: `frameworks/av/cmds/screenrecord`, `frameworks/base/cmds/screencap`
- This repo: [device-log](../../docs/device-log.md) (encoder, `hwrotation`, SuperSU quirks),
  [03 LOG](../../examples/03-hardware-lab/LOG.md) (VPU speed, subnets),
  [backup.sh](../../scripts/backup.sh) (`exec-out` and root helpers)
