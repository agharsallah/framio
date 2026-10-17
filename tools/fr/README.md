# fr: capture and control the frame

`fr` is one Go binary for daily development on an Android device over adb. It takes screenshots,
records the screen, opens a live window you can click in, runs the build → install → launch → logs
loop, and drives touch and keys. It combines adb, scrcpy and ffmpeg instead of rebuilding them.

It works with any Android 5+ device. The Veidoo F10's quirks are handled in `internal/adb`:
`adb shell` exit codes that are always 0, a broken `pidof`, logcat without `--pid`, and a 1280×720
encoder for a 1280×800 screen.

[PLAN.md](PLAN.md) has the design and its decisions; [LOG.md](LOG.md) has every measurement on the
frame.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Commands](#commands)
- [Where files go](#where-files-go)
- [Flags, environment and exit codes](#flags-environment-and-exit-codes)
- [Scripts and Claude Code](#scripts-and-claude-code)
- [Sharing one frame](#sharing-one-frame)
- [Good to know](#good-to-know)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

## Install

**Needs:** macOS (Linux also works, except `--copy`, `--open` and finding the JDK automatically);
adb on PATH; Go 1.27+; scrcpy 4.1+; ffmpeg. `fr run` also needs the Android SDK build-tools (for
`aapt2`) and a JDK 17+.

```bash
brew install go scrcpy ffmpeg                  # adb: platform-tools on PATH, or set $ADB
cd tools/fr && go build -o ~/.local/bin/fr .   # any folder on PATH works
fr doctor                                      # checks the Mac and the device, prints a fix for each ✗
```

**Tab completion (zsh, optional).** It completes commands, plus installed package names for
`start`, `logs` and similar:

```bash
mkdir -p ~/.zfunc && fr completion zsh > ~/.zfunc/_fr
# in ~/.zshrc, before compinit:  fpath=(~/.zfunc $fpath)
```

**Update:** `git pull`, then rerun the `go build` line. **Remove:** `rm ~/.local/bin/fr`.

## Quick start

```bash
fr doctor                          # everything ✓?
fr shot                            # captures/2026-10-18/153012_dev.framio.launcher.png  (0.6 s)
fr rec --time 20s --touches        # captures/…/153140_dev.framio.launcher.mp4
fr mirror                          # live window; close it or Ctrl-C to quit
fr run examples/03-hardware-lab    # build, install, launch, then that app's logs (Ctrl-C to stop)
```

## Commands

`fr help` lists them all. For a command that has flags, `fr <command> -h` lists them.

### Capture

| Command | What it does |
|---|---|
| `fr shot [label]` | Screenshot to PNG and print the path. `--copy` puts the image on the clipboard (to paste into Slack, a PR or Claude). `--open` opens it. `--png` lets the device encode the PNG (slower, a fallback). |
| `fr shot --burst 10 --every 2s` | A series of screenshots. |
| `fr rec [label]` | Record until Ctrl-C, with scrcpy, for any length. `--time 30s` stops by itself. `--touches` shows touch dots. `--gif` also writes a GIF. `--size 1152` limits the longer side. `--fps 30` and `--bitrate 4M` are the defaults. `--show` opens the mirror window while recording. |
| `fr rec --engine device [--stamp]` | Record with the device's own `screenrecord`: no scrcpy needed, 180 s maximum. `--stamp` adds a timestamp overlay. |
| `fr rec start [label]` / `fr rec stop` | Background recording for scripts. `start` returns once recording has begun; `stop` saves the MP4. Takes the same flags as `rec`. |
| `fr gif <video> [--width 640] [--fps 12]` | Make a GIF next to a video. |

### View and control

| Command | What it does |
|---|---|
| `fr mirror` | Live window. Click to tap, type to send keys, right-click for Back, middle-click for Home. Drop an APK to install it; drop any other file to push it to `/sdcard/Download`. `--rec` records too, `--top` keeps it above other windows, `--off` turns the frame's screen off while mirroring. |
| `fr tap 640 400` | Tap a point. Coordinates are screen pixels (1280×800 on the frame). |
| `fr tap "Always"` | Tap the element whose text, description or resource-id matches, ignoring case. Exact matches win over partial ones. If several match, it lists them; pick one with `--nth 2`. |
| `fr find [text]` | List on-screen elements (or the matching ones) with their center and bounds. |
| `fr swipe 100 400 1100 400 [300]` | Swipe from x1 y1 to x2 y2, with an optional duration in ms. |
| `fr long 44 756 [1000]` | Long-press (this example is the launcher's hot corner). |
| `fr text "hello world"` | Type text into the focused field. |
| `fr key home back enter` | Key events: `home back menu power wakeup sleep enter tab esc del up down left right center volup voldown mute play apps`, any `KEYCODE_…` name, or a number. |

### Apps

| Command | What it does |
|---|---|
| `fr run [dir]` | Find the Gradle project (`dir` or its nearest parent with `gradlew`, default `.`), run `assembleDebug`, install the newest APK, force-stop the app, launch it, print `build · install · launch` times, then follow its logs. `--release` runs `assembleRelease`, `--task T` runs another task, `--no-logs` exits after launching. Uses JDK 21 (or 17+) when `JAVA_HOME` is older. |
| `fr install <apk\|dir>… [--launch] [--downgrade]` | Install APKs. A folder means its newest built APK. A failure comes with a hint, for example "signed with a different key: fr uninstall <pkg> first". |
| `fr start <pkg>[/activity]` | Launch an app and print its launch time (`am start -W` TotalTime). It finds the LAUNCHER activity, or the HOME one for launcher-only apps. |
| `fr stop` / `restart` / `clear` / `uninstall <pkg>` | Force-stop; stop then start; clear the app's data; uninstall. |
| `fr apps` | Installed user apps, with their version and last update. |
| `fr logs [pkg]` | Logs of one app (main, system and crash buffers) that keep following it across restarts, with a banner on each new PID. `--since 5m` starts further back. `--dump` prints and exits instead of following. `--tag T` (repeatable) adds tags. `--raw` turns off colors. With no package it shows everything. |

### Device

| Command | What it does |
|---|---|
| `fr info` | Model, Android version, serial, Wi-Fi IP, screen, foreground app, RAM, uptime, root. |
| `fr doctor` | Checks adb (and warns about a second adb with a different version), scrcpy, ffmpeg, aapt2, Java, the captures folder and `.gitignore`, then the device. Exits with 1 if anything is ✗. |
| `fr bug [label]` | Evidence folder in about 5 s: `screen.png`, `logcat.txt` (2,000 lines, all buffers), `crash.txt`, `activities.txt`, `windows.txt`, `meminfo.txt`, `top.txt`, `info.txt`. |
| `fr wait` / `fr reboot [--wait]` | Wait until the device has booted (`sys.boot_completed=1`) and print how long it took. |
| `fr screen off\|on\|<0-255>` | With root, writes the backlight directly; Android keeps running, and `on` restores the previous level. Without root, uses the sleep/wake keys and the brightness setting. |
| `fr sh [--root] <command…>` | A device shell command with its real exit code. `--root` runs it with `su`; multi-line scripts are pushed, because SuperSU ignores a multi-line `su -c`. |
| `fr version` / `fr completion zsh` / `fr help` | |

## Where files go

```
captures/                                   git-ignored: screens can show family photos
  2026-10-18/
    153012_dev.framio.launcher.png          <time>_<foreground app>[_label].<ext>
    153140_dev.framio.lab_timer-flow.mp4
    153140_dev.framio.lab_timer-flow.gif
    154001_bug_timer-crash/
  .rec.pid  .rec.log                        only around fr rec start / stop
```

- **Folder:** `-o DIR`, otherwise `$FR_OUT`, otherwise `<git top-level>/captures` when you're inside a
  repository, otherwise `~/Movies/fr`.
- **Names:** the label becomes lowercase with dashes. Existing files are never overwritten; `-2`,
  `-3`… are added instead.

## Flags, environment and exit codes

**Global flags** go before the command: `fr -v shot`. Commands that have flags of their own (`shot`,
`rec`, `run`, `logs`…) also accept them after it, mixed with the other arguments.

| Flag | |
|---|---|
| `-s SERIAL` | which device, when several are attached |
| `-v` | print every underlying adb or scrcpy command with its duration (ready to paste into a LOG.md) |
| `-o DIR` | captures folder |

| Environment | |
|---|---|
| `ADB` | adb binary (default: `adb` on PATH). fr passes the same one to scrcpy, so only one adb server runs |
| `ANDROID_SERIAL` | default device |
| `FR_OUT` | captures folder |
| `ANDROID_HOME` | Android SDK, used to find `aapt2`. Default: `sdk.dir` in the project's `local.properties`, then `/opt/homebrew/share/android-commandlinetools`, then `~/Library/Android/sdk` |
| `FR_LOCK` | lock directory shared by everyone driving the device, e.g. `/tmp/framio-frame.lock` ([below](#sharing-one-frame)) |

**Exit codes:** `0` on success, non-zero on failure, with the message on stderr. `fr sh` exits
with the device command's own code.

## Scripts and Claude Code

When stdout is not a terminal, `shot`, `rec`, `gif` and `bug` print only the **absolute path** of
what they created, and progress goes to stderr. So `$(fr shot)` is a file you can use directly:

```bash
fr rec start open-lab
fr key home && fr tap "Lab"
png=$(fr shot lab-open)
fr logs dev.framio.lab --since 30s --dump | grep -E ' E |FATAL'
fr rec stop
```

Claude Code can drive the frame the same way: `fr shot` gives an image path it can open,
`fr find` and `fr tap "…"` work the UI by its text, and `fr logs <pkg> --since 1m --dump` shows
what happened. No MCP server is needed.

`.vscode/tasks.json` at the repo root adds **fr: screenshot**, **record 30 s**, **mirror**, **run
current example** and **bug report** to *Tasks: Run Task*.

## Sharing one frame

When several people or agents use the same frame, `export FR_LOCK=/tmp/framio-frame.lock`. With it
set:

- These commands hold the lock while they run: `shot tap find swipe long text key install start
  stop restart clear uninstall screen sh bug reboot`.
- `fr run` holds it only around install and launch, not during the Gradle build or while following
  logs.
- `logs`, `rec` and `mirror` never take it, because they run for minutes.

The protocol: `mkdir` takes the lock, `rmdir` releases it, a waiting command retries every second,
and a lock older than 5 minutes counts as stale. Shell scripts can join with two lines;
`examples/04-little-meadow/tools/frame.sh` already does.

## Good to know

- **Recordings get frames only when the screen changes.** That's how scrcpy and screenrecord work:
  a static screen gives a short or 1-frame file, which fr reports as "1 frame (nothing moved on
  screen)". Nothing was lost.
- **scrcpy records .mkv and fr delivers .mp4.** An mkv stays readable after Ctrl-C, a crash or a
  reboot mid-recording. ffmpeg then copies it into an MP4 without re-encoding, in under a second,
  so QuickTime can play it. Without ffmpeg, the .mkv is kept.
- **Recording is native 1280×800 at about 25 fps on the frame**, although its encoder declares
  1280×720 as the maximum. If a device refuses a size, scrcpy shrinks it by itself, or pass
  `--size 1152`.
- **Screenshots take about 0.6 s instead of 1.3–3.7 s.** The frame sends raw pixels through
  `gzip -1` and the PNG is encoded on the Mac, because PNG encoding on the 1 GHz Cortex-A7 is the
  slow part.
- **`fr tap "text"` reads the screen with `uiautomator dump`, which takes about 3 s.** Compose and
  WebView text both show up. A WebView fills in a moment after it loads, so fr looks a second time
  before giving up. Coordinates are instant.
- **fr leaves the device as it found it.** Show-touches and stay-awake are restored, temp files in
  `/data/local/tmp/fr-*` are deleted, and Ctrl-C does the same cleanup.
- **Root is used only by `fr screen` and `fr sh --root`.** On this frame every app gets root without
  a prompt, so fr never uses it unasked.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `no device attached` | Plug in USB. On the frame: Settings → About → Beta program ON → ADB access ON. |
| `… is unauthorized` | Accept the USB debugging prompt on the frame. |
| `… is offline` | Replug, or `adb kill-server`. |
| `N devices attached` | `-s SERIAL` or `export ANDROID_SERIAL=…` |
| `adb server version … doesn't match this client`, or adb keeps restarting | Two adb binaries are in use; `fr doctor` lists them. Use one (`export ADB=…`) everywhere. |
| `scrcpy exited: signal: segmentation fault at startup, retrying once…` | scrcpy 5.0.1 occasionally crashes at startup on macOS; fr retries once. If it happens again, run the command again or use `--engine device`. |
| A recording fails or comes out black | `fr rec --size 1152`, or `fr rec --engine device`. Run with `-v` to see scrcpy's output. |
| `stopped before scrcpy recorded its first frame` | Stopped within a second or two of starting; record longer. |
| `nothing on screen matches "…"` | `fr find` shows what uiautomator can see. Use coordinates for canvas or game screens. |
| `install failed: INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was signed with another key: `fr uninstall <pkg>`, then install again. |
| `gradle … failed` / `no JDK 17+ found` | Install JDK 21; fr picks it through `/usr/libexec/java_home`. |
| `aapt2 not found` | `sdkmanager "build-tools;37.0.0"`, or set `ANDROID_HOME`. |
| A command hangs on `waiting for the device lock` | Another session holds `$FR_LOCK`. It counts as stale after 5 minutes; `rmdir /tmp/framio-frame.lock` only if you're sure nobody is using it. |
| The frame's screen stays dark after `fr screen off` | `fr screen on`, or `fr sh --root 'echo 187 > /sys/class/backlight/rk28_bl/brightness'`. |

## Development

```bash
cd tools/fr
go vet ./... && go test ./...   # 28 tests, no device needed
go build -o ~/.local/bin/fr .
./smoke.sh                      # every command once on the attached frame (1-3 min)
```

- **Unit tests** parse real output recorded from the frame (`internal/adb/testdata/`).
- **Command tests** run `fr` in-process against `testdata/fake-adb`, which replays those recordings
  and logs every adb call.
- **`smoke.sh`** runs on the real frame. It installs and launches `examples/01-hello-frame`, taps
  its Exit button, toggles show-touches and blinks the backlight, then restores both. Its captures
  have "smoke" in their names.

| File | |
|---|---|
| `main.go` | the command table (also the source of `fr help` and the zsh completion), global flags, dispatch |
| `shot.go` `rec.go` `mirror.go` | capture |
| `input.go` | tap, find, swipe, long, text, key |
| `app.go` `logs.go` | run, install, start…uninstall, apps, logs |
| `device.go` | info, doctor, bug, wait, reboot, screen, sh, version |
| `files.go` `lock.go` | captures folder and file names, `FR_LOCK` |
| `internal/adb/` | the only code that talks to the device, plus every parser, with fixtures from the frame |

**Adding a command:**

1. Write `func cmdX(ctx context.Context, e *env, args []string) error` and get the device with
   `e.device(ctx)`.
2. Add a line to the table in `main.go`.
3. If it touches the screen, add it to `lockingCommands` in `lock.go`.
4. Record new device output as a fixture before writing its parser.
