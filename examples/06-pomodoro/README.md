# Example 06: Pomodoro

A calm focus timer for the Veidoo F10, in native Jetpack Compose (foundation only, no WebView, no network). It keeps
one block of focus on the desk across the room: a large face for normal work, a near-black screen (or the backlight
off) for deep work, a slow procedural landscape on breaks, and a quiet record of what happened. Every feature with a
claim behind it has an (i) with one sentence of evidence and its strength (PLAN.md §3); the app makes no claims the
research doesn't back.

| | |
|---|---|
| Modes | **Classic** 25/5, long break 20 min every 4 (Full face, soft cue) · **Deep** 50/10, long break 30 every 2: a ritual first (intention, if-then line, checklist, last resume note), then **Calm** (a thin arc and "38 min" at ~12 % luminance, one redraw a minute) or **Screen off** (backlight off through root), touch locked, hold 1.2 s for the panel, **knock twice** on the case to peek, a sunrise at the end · **Flow** counts up, you stop at a natural point, the break follows the Smits tiers (≤ 25 min → 5, ≤ 50 → 8, more → 10), a silent nudge at 90 min · **Gentle start** 10/3 with "another 10?". Up to 8 modes: presets are read-only, "Duplicate & edit" makes your own (dial pickers, faces, ritual, display, sound) |
| Focus | faces Ring, Disc, Horizon, Digits (Small/Medium/Large); Begin is a 3 s "breathe in"; Pause, End (asks for a one-line resume note: chips, handwriting or the keyboard); park a stray thought by scribbling it, it comes back at the break; "Pulled away?" logs a distraction reason in one tap |
| Breaks | a dim landscape (sky, sun or moon, clouds, three hills with parallax, grass) whose colours follow the part of the day; one prompt (move, look out of the window, water, eyes far away); +5 min; the wrap-up (done / partly / not yet, focus 1–5, reasons, next step) |
| History and Insights | a day-grouped list with filters, each session's notes on a timeline, edits and deletes with Undo; Insights: today and this week against your target (sessions, default 6), "N of the last 7 days" (never a streak), how sessions ended, what pulled you away, focus by hour only after 20 rated sessions, estimates against actuals, time per task and mode |
| Sound | cues synthesized on the device (soft chime, rising chime for Deep), on the alarm stream at an in-app volume (the media stream sits at 2/15 and Frameo resets it); soundscapes rain, wind, pink and brown noise, off by default, honestly labelled |
| Looks | themes Ink, Paper, Forest, Tomato with 8 accents; evening amber from a set time (warmer colours, brightness capped); Reduce motion; a built-in keyboard (QWERTY, symbols, long-press accents) so the grey system IME never covers the screen |
| Launcher | a tile in 02-launcher's shelf: order 60, accent `#E5735C`, "Focus timer" |

## Build, install, run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard
# the frame is shared: take your turn (see "Sharing the frame" in the root README)
FRAME_OWNER=me ../../scripts/frame-lock.sh run -w "install pomodoro" bash -c \
  'adb install -r app/build/outputs/apk/release/app-release.apk && adb shell am start -n dev.framio.pomodoro/.MainActivity'
```

Three build types:
- **release**: what goes on the frame (R8, 2.2 MB, installs in ~15 s).
- **dev** (`./gradlew :app:assembleDev`): release plus the adb test hooks below (time scaling, synthetic knocks, seeded
  history, a fake evening, an allocation probe). Use it for device tests: it installs as fast as release.
- **debug**: hooks too, plus StrictMode and LeakCanary; its 14 MB dex holds the frame for about a minute while it
  installs.

`api23Guard` reads every platform call in the release APK and fails on anything Android 6 lacks (Lint's NewApi check
doesn't fire with this toolchain). Run it whenever you add a dependency or a platform call.

## adb extras

```bash
A="adb shell am start -n dev.framio.pomodoro/.MainActivity"
$A --es mode deep --es task "'Thesis ch.3'" --es cmd start   # quote twice for words with spaces
$A --es cmd pause | resume | end | stop | extend | skip | done | home | ritual | peek | export | selftest
$A --es mode classic --ei min 1 --es cmd start                # a 1-minute block
$A --es mode deep --es display blackout --es cmd start        # this run with the screen off
# dev and debug builds only:
$A --ei sec 20 --es cmd start           # "minutes as seconds" from the next process start (force-stop first); --ei sec 0 clears
$A --es cmd knock                       # a synthetic double knock (Deep focus on screen)
$A --es cmd seed --ei days 400          # made-up history (ids seed-…); --ei days 0 removes it
$A --es cmd evening --es at 21:30       # judge evening amber as if it were 21:30; --es at off
```

Every command logs `EVENT|adb|…`; the app logs `EVENT|<area>|…` lines (phase, display, window brightness, sound,
history, alloc…) under the tag `Pomodoro`, so long runs can be watched with `adb logcat -s Pomodoro` without touching
the frame. `selftest` logs `CHECK|…` lines (clock, engine, service, keep-screen-on, root, backlight, audio, log file).

**For agents and scripts:** take the frame lock for every burst that installs, starts, taps, screenshots, changes the
backlight or writes as root (`tools/frame.sh` wraps `scripts/frame-lock.sh`; `logcat` and read-only `dumpsys` need
none). Keep holds under a minute, build before taking the lock, and leave the frame clean: `tools/frame.sh clean`
force-stops Pomodoro and writes 187 back to the backlight. A force-stopped run is restored on the next start, so end it
first (`cmd end`, then `cmd home`) before starting another mode.

## Data and export

Everything stays on the frame, in `/data/data/dev.framio.pomodoro/`:

| File | What |
|---|---|
| `files/sessions.jsonl` | one line per finished or ended block (append-only, fsynced; a torn last line is repaired) |
| `files/history.jsonl` | notes, reasons, parked thoughts and edits, as an append-only journal (unknown future lines kept) |
| `files/stats-cache.json` | Insights' day buckets (folded into months after ~6 months); rebuilt when it doesn't match the two files |
| `files/datastore/settings.json`, `snapshot.json`, `tags.json` | settings and modes, the running session (survives a kill), the reason tags |
| `shared_prefs/display.xml` | the "we turned the backlight off" marker, so a crash or kill in Blackout is undone at the next start |
| `files/last-crash.txt` | the last uncaught exception, if any |

Export: Settings → Data → Export, or `--es cmd export`: one JSON file and two CSVs (sessions, entries) in
`/sdcard/Android/data/dev.framio.pomodoro/files/export/` (`adb pull` it). Settings → Data → Reset puts the settings
back (not the modes, never the history). `adb shell pm clear dev.framio.pomodoro` removes everything.

## Layout

```
core/      pure Kotlin (JVM), explicitApi, warnings as errors: engine/ (reduce(state, input, now) → state + effects,
           FocusEngine as the single writer, Derive, Timing), rhythm/ (fixed, Flow tiers), model/ (modes, settings,
           records), time/ (boot clock, TimeGate, day parts, evening), gesture/KnockDetector, audio/ (chime, rain,
           wind, noise synths), notes/ + history/ (journal, index, day buckets, Insights report, export, seed)
app/       dev.framio.pomodoro: MainActivity (window, adb extras), di/AppContainer (manual DI), data/ (DataStore JSON
           stores, session log, HistoryRepository), platform/ (DisplayPower + DisplayController, RootShell, SoundEngine,
           SoundscapePlayer, Motion, FocusService, EveningWatch, CrashGuard), ui/ (one package per screen: ViewModel +
           Route + Screen; Root is a phase-driven AnimatedContent)
tools/     frame.sh (locked device bursts), api23_guard.py + api23_allow.txt
PLAN.md · DESIGN-history.md · NOTES.md (handoff notes per milestone) · LOG.md (device measurements)
```

## Tests

- `:core:test`: the reducer tables (every phase × input), both rhythms, Flow's tier boundaries, deadlines across kills
  and reboots, untrusted clocks, statistics across DST, knock traces recorded on the frame, synth clipping and seams,
  the session log and journal parsers, history at 5k sessions.
- `:app:testDebugUnitTest`: ViewModels on the real reducer with a fake clock, Robolectric (API 23) flows through the
  real root, the Deep gestures, the keyboard, the dial, the repository on real files, theme contrast; Roborazzi goldens
  of every screen in the four themes, lying and standing (`app/src/test/screenshots`; `recordRoborazziDebug` only for
  intended changes).
- On the frame: LOG.md, milestone by milestone (Command / Output / Note).

## Known issues

- **Budgets not met** (PLAN §6.3, numbers in NOTES.md "M6 — final"): cold start is 1.25–1.33 s (budget 0.9 s; most of
  it is Compose's first composition on this CPU); Deep's Begin is ~21 % janky in its first 1.7 s (Classic ~6–9 %);
  opening Insights or History is a handful of long frames (each card row takes 50–130 ms to compose) and the first open
  of a large history reads both files (2.7 s for 2,000 sessions, off the main thread, a skeleton meanwhile).
- The break landscape moves in 20 steps a second on purpose (the motion is about a pixel a step); at 60 Hz this GPU
  couldn't keep up.
- The launcher's hot-corner dot stays visible over Calm (it is another app's overlay); Screen off hides it.
- Cue and soundscape levels on the alarm stream still need a listen by the owner; the frame's mic can't measure them.
- Frameo's 23:00 standby with a session running has not been observed yet.
- Like every app here, it can't restart itself once killed: a running session is restored when the app next starts, and
  the deadline brings it back to the front.
