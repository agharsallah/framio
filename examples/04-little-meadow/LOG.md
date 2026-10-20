# Example 04: Little Meadow — command log

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` · `adb` = `~/Downloads/platform-tools/adb` · the frame is shared
with other sessions: device commands go through `tools/frame.sh` (lock `/tmp/framio-frame.lock`).

## Asset tools (Mac)

| Command | Output | Note |
|---|---|---|
| `uv run --python 3.12 --with moderngl python -c "moderngl.create_standalone_context()"` | `Apple M1 Pro 4.1 Metal - 90.5` | headless GL 4.1: the 3D sprites are ray-marched on the Mac's GPU |
| `uv run render.py --preview ch_pip` (first try) | 6 sprites, 5 s each; beak cast a hard streak, top washed out, "touches the edge of the base view" | floor shadow had no radius: added `reach`; softer shadow floor (0.35), less key light |
| same, blink pose | read as a frown | blink now reuses the sleepy lid arc |
| `uv run render.py --preview ob_` | 59 objects, 4–9 KB each (320×320 WebP) | moon came out as a bowl (sphere minus sphere): now an extruded crescent |
| `uv run render.py bg_` | 1280×800 in 4×4 tiles, 7–11 KB | tiled so no draw call trips the GPU watchdog; first pass too hazy: fog 0.025 → 0.011, haze 0.2 → 0.08 |
| `say -v '?'` | Samantha (en_US), Anna (de_DE), Eloquence voices | Samantha/Anna sound most natural; rate 150/145 for toddler-directed speech |
| `ffmpeg … -c:a libvorbis` | `Unknown encoder 'libvorbis'` | Homebrew upgraded ffmpeg to 9.0.2 mid-session, built without libvorbis; Opus-in-Ogg needs Android 10 |
| `/opt/homebrew/Cellar/ffmpeg/7.1_4/bin/ffmpeg` | `Library not loaded: libvpx.9.dylib` | old keg broken too → Vorbis via `soundfile` (libsndfile wheel), ffmpeg only filters to WAV |
| `uv run make_sounds.py` (soundfile, one write) | exit 139 | libsndfile's Vorbis writer segfaults on one big buffer → write in 4096-frame blocks |
| `uv run make_sounds.py` | `sfx: 35 files, 239 KB`, `music: 4 files, 904 KB` | |
| `uv run make_voice.py` | 153 clips per language | trimmed, loudness-normalised, mono 22 kHz Vorbis |
| `fontTools.varLib.instancer Nunito[wght].ttf wght=600/800` | `nunito_600.ttf`, `nunito_800.ttf` (130 KB each) | Android 6 can't select weights of a variable font |

## Build and checks

| Command | Output | Note |
|---|---|---|
| `getprop dalvik.vm.heapgrowthlimit` · `heapsize` | `80m` · `384m` | bitmaps live in the Java heap on M → `largeHeap`, sprite LRU capped at 56 MB |
| `./gradlew assembleDebug` (first) | `Unresolved reference 'LocalTextStyle'` | that's material; the app uses BasicText with explicit styles |
| `./gradlew testDebugUnitTest` | 29 tests, 0 failures | Settings merge/limits, Clock windows, Difficulty, SessionRules, Progress, KnockDetector, Nav |
| 5 hand mutations (inclusive nap end, late bedtime, no age floor, bonus ignored, log drops newest) | 4 caught, "inclusive nap end" survived | added the exact-end assertion → caught |
| `python3 -I tools/api23_check.py` | `checked 230 platform references against API 23`, exit 0 | |
| `./gradlew assembleRelease` | `app-release.apk` 7.2 MB | |

## On the frame

| Command | Output | Note |
|---|---|---|
| `am start -n dev.framio.meadow/.MeadowActivity` (debug) | `Displayed … +7s779ms`; ~5 s of plain paper | SoundPool loaded 55 files on the main thread → background thread |
| `am start -W` (release) | `TotalTime: 2369` | |
| screencap at 01:00 | friends asleep, Luma hosts, night backdrop | bedtime window 19:30–06:30 works |
| `--es play count` right after start | stayed on home | SharedFlow without replay dropped it before Compose collected → buffered Channel |
| `--es play count` + 3 taps | 3 flowers counted, round 2 shown | |
| unexplained parent screen / extra round | other sessions were sending taps to the same frame | shared lock agreed with framio-7f, -40, -7a |
| `getprop debug.hwui.render_dirty_regions` | `false` | any animation redraws the whole screen |
| `dumpsys gfxinfo … framestats`, home idle (release) | 81 ms/frame median, GL issue 24 ms, swap 9 ms | stencil clips for rounded cards + elevation shadows + full-rate breathing |
| same after: drawn shapes, cached blurred shadow bitmaps, 12 Hz slow clock for idle motion | idle 7 ms/frame median (12 fps redraw), swipe 74 ms median | |
| same after: home cards in an eager `Row` + sprite preload instead of `LazyRow` | swipe 15 ms median, p90 100 ms | |

## Integrating the builders

| Command | Output | Note |
|---|---|---|
| dashboard: `curl -c ck -d '{"pin":…}' :18077/api/login` → `-d '{"type":"say","id":"call_dinner"}' /api/command` | `{"ok":true}` ×2, a friend pops up bottom-right and says it | release build can't `run-as`: PIN read with `su -c cat …/settings.json`; two Pips on screen → calls use Bo (Luma for bed) |
| first try of that test | ran 7 min later with `dev.framio.mawaqit` in front, empty curl replies | queued behind another session's hold: the test now installs + starts + checks `resumed` inside one hold |
| `frame.sh shot … --es set '{"a":1,"b":2}'` | JSON split by the frame's mksh (brace expansion) | each extra now single-quoted for the remote shell; stale lock renamed away before removal (two waiters raced) |
| words: `--es set '{"age":3}' --es play trace` | still age-4 letters | per-activity levels outlived an age change → `Progress.resetLevels()` when the age changes |
| `assembleRelease` then `make_voice.py` in one command | `voice clip missing: trace_intro …` | built before recording; rebuild after recording → no warnings |
| quiet, puzzles, words on the frame (release) | screenshots of every world and activity start | APK 21 MB, ~60 % narration |
| numbers + music, habits, colours merged; `make_voice.py` | en 532 / de 667 clips | ran while habits was mid copy-back (its German file landed first) → re-run: 764 / 764, id parity checked per file |
| `./gradlew testDebugUnitTest assembleRelease` + `api23_check.py` | 189 tests, 0 failures · 496 platform references OK · APK 31 MB | 395 sprites, 764 clips per language, 31 activities |
| every world screen at age 5 (one hold) | all 31 activities present with their 3D icons | |
| home idle / swipe with everything merged (release) | 7 ms / 19 ms median per frame | unchanged by the merges |
| `dev.framio.tile.*` metadata (order 50) → HOME, swipe the shelf | "Little Meadow · For little ones" tile with Pip's icon | between Mawaqit (40) and Pomodoro (60) |

## Captions ("Show what's said")

| Command | Output | Note |
|---|---|---|
| `uv run make_voice.py` (manifest now carries `"t"`) | `en: 764 clips (0 new)`, `de: 764 clips (0 new)`, 764/764 with text | unchanged clips get their text from the TSV without re-recording (the hash already covers the text) |
| `./gradlew testDebugUnitTest assembleRelease` + `api23_check.py` | 209 tests, 0 failures · 500 platform references OK | new: `CaptionsTest` (manifest, joining, names, word timing, pages), settings round trip + default on |
| `frame.sh shot … --es play <id>` for all 31 activities, home, a world, story pages, rest, wiggle, call, stickers, mailbox, parent | caption in the top bar between back and "say it again" never reaches content (content starts at 112 dp, pill ends ≤ 104 dp) | home: bubble beside Pip under the cards (cards end at 646 dp); calls: bubble beside the caller; night light: none; bedtime: dim night pill |
| first look at home | the bubble's tail pointed at Pip's feet | tail moved to the side, toward the face |
| a call on home | bubble at 168 dp covered the card titles | bottom-aligned beside the caller (40 dp), top bar where there is one |
| parent screen Off, then dashboard `PUT /api/settings {"captions":true}` | greeting without, then call with caption | applies immediately |
| test message via `POST /api/family/voice?from=Grandma` + `{"type":"message"}` | "A voice message from Grandma" beside Bo in the mailbox | test item deleted again |
| `gfxinfo framestats`, home idle (release) | 7.5 ms median, 0 janky | captions hidden or static cost nothing extra |
| count, speaker tapped twice, captions on/off (60 Hz fades, 3-layer shadow) | 24 % vs 2 % janky frames, p95 57 vs 15 ms | every fade frame is a full-screen redraw on top of the talking friend |
| same, fades stepped at 30 Hz, then 20 Hz, one shadow layer | 11–15 %, then 5–8 % janky, p95 17–18 ms | |
| same, pill in a `graphicsLayer` for alpha | 15–19 % janky | the offscreen alpha pass is slower on the Mali-400: alpha stays on each shape |
| home greeting only, on/off ×2 | median 9.3 vs 8.6 ms, p90 24–26 vs 16–17 ms; static caption frames 7–9 ms | text drawn once into two bitmaps per page instead of 2–3 text draws per frame (record 10 → ≤ 6 ms); the extra cost is ~10 extra redraws per line (fade in, words, fade out) |

| final release: home idle · greeting on/off ×2 | 7.2 ms median, 0 janky · median 8.7–9.5 vs 8.2–8.7 ms, p95 27–38 vs 18–20 ms | `./gradlew --stop` after |
