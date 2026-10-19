# Example 06: Pomodoro — command log

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` · frame `68384696`, Europe/Amsterdam · every device-changing
burst runs through `tools/frame.sh` (which takes the shared lock via `scripts/frame-lock.sh`); read-only `dumpsys`,
`settings get` and `logcat` run without the lock.

## M0 · Tooling

| Command | Output | Note |
|---|---|---|
| `tools/frame.sh` (no args) | usage, exit 1 | |
| `FRAME_WAIT_MAX=60 tools/frame.sh clean` (first version, own mkdir loop) | `clean: dev.framio.pomodoro stopped, backlight 187`, 2.5 s, lock gone afterwards | `force-stop` of a not-yet-installed package is harmless |
| own lock held, `FRAME_WAIT_MAX=2 tools/frame.sh run true` | `frame busy, gave up after 2 s`, exit 75 | |
| same lock `touch -t 202601010000` (stale), `tools/frame.sh run echo …` | took it, exit 0, lock released | stale = mtime > 5 min |
| rewritten on top of `scripts/frame-lock.sh run -w "pomodoro <cmd>"`, `FRAME_OWNER=framio-7a/pomodoro`; `tools/frame.sh clean` | `frame-lock: got the frame after 5 s`, `clean: … backlight 187`, `status` → `frame: free` | queue + heartbeat + owner file come from the shared script |

## M0 · Device checks

### a. Soft keyboard (IME)

| Command | Output | Note |
|---|---|---|
| `ime list -s` | `com.android.inputmethod.latin/.LatinIME` | one IME, enabled |
| `ime list -a` | same, `enabled=true`, `/system/app/LatinIME` `versionName=6.0.1` | AOSP keyboard of Android 6 |
| `settings get secure default_input_method` · `enabled_input_methods` | `…latin/.LatinIME` · `…latin/.LatinIME` | default and enabled |
| `dumpsys input_method` | `mCurMethodId=…LatinIME`, `mBoundToMethod=true`, subtype `English (US)` only | bound and ready |
| locked: `am start -a com.android.settings.USER_DICTIONARY_INSERT --es word framio`, `screencap`, BACK ×2 | `mInputShown=true`; keyboard drawn at y 390–800 (**410 px, 51 % of the screen**), light grey theme, QWERTY, `?123` key at bottom-left | the launcher's hot-corner dot (x 16–72, y 728–784) sits **on top of the `?123` key** |
| `content query --uri content://user_dictionary/words` · `dumpsys activity activities` | `No result found` · dialog finished (`… t103 f`), previous app resumed | nothing saved, frame left as found |

### b. Does Frameo come to the front by itself?

| Command | Output | Note |
|---|---|---|
| `dumpsys package net.frameo.frame` | `v1.32.16`, `targetSdk=34`; requests no `SYSTEM_ALERT_WINDOW`, no `REORDER_TASKS`; granted `WRITE_SETTINGS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_SPECIAL_USE` | no overlay; on API 23 any app may still start activities from the background |
| same, Receiver table | `BootCompleteReceiver`, truetime `BootCompletedBroadcastReceiver`, WorkManager proxies | nothing photo-related is exported |
| `appops get net.frameo.frame` | `AUDIO_MEDIA_VOLUME`, `AUDIO_RING_VOLUME` used ~1 min after boot | **Frameo sets the media volume at its start** |
| `dumpsys package preferred-activities` · `dumpsys activity activities` | HOME task = `net.frameo.frame/…AWaitForDeferredInitCompleted` (#89), top `…core.ui.APowerOffNotice` | Frameo is HOME again this boot (launcher is not) |
| `logcat -b events`, 08:27 → 09:30 | `APowerOffNotice` created by Frameo itself 29 s after its start, while it was already in front; later only `am_resume_activity` of it right after another app's `am_finish_activity … proc died` | no case of Frameo jumping over a live foreground app in ~70 min of app switching. It only resumes as HOME when the front app goes away |
| `dumpsys alarm \| grep -A2 frameo` | `RTC_WAKEUP … net.frameo.frame/.feature.schedule.StandbyBroadcastReceiver when=2026-10-18 23:00:00` | **Frameo's sleep schedule fires at 23:00**. What it does over another app (front itself? backlight/brightness off?) is unverified |
| root `cat …/shared_prefs/net.frameo.frame_preferences.xml` (read-only) | `KEY_DISPLAY_BRIGHTNESS=187`, `KEY_VIDEO_VOLUME=5`, `KEY_NOTIFICATION_VOLUME=6`; schedule not in prefs (Realm) | Frameo owns the system brightness and volumes |
| photo arrival | not testable (can't make a photo arrive) | |

### c. Launcher hot-corner dot

| Command | Output | Note |
|---|---|---|
| `dumpsys window windows \| grep "Window #"` | `#5 dev.framio.launcher` overlay above every app window | overlay is active |
| same, the overlay | `(16,16)(56x56) gr=BOTTOM\|START ty=2002`, `mShownFrame=[16,728][72,784]` | `TYPE_PHONE`, above apps and the IME |
| `appops get dev.framio.launcher SYSTEM_ALERT_WINDOW` | `allow` | |
| `HotCornerService.kt` (02-launcher) | oval, fill `0x66000000`, stroke 3 px `0xAAFFFFFF` | over pure black: fill vanishes, ring = `#AAAAAA` (relative luminance ≈ 0.40). Over the Calm screen it is the **brightest thing on screen** (Calm content at 12 % ≈ 0.12, i.e. 3–4× dimmer). With the backlight off (Blackout) it is invisible like everything else |
| screencap with the IME up (a.) | ring clearly visible on light grey, covering `?123` | |

### d. Backlight

| Command | Output | Note |
|---|---|---|
| root `cat /sys/class/backlight/rk28_bl/{brightness,max_brightness,actual_brightness}` | `187` / `255` / `187` | unchanged since device-log |
| root `ls -l …/rk28_bl/` | `brightness` owned by `system` | PowerManager writes it on every brightness recompute |
| `settings get system screen_brightness` · `screen_brightness_mode` | `187` · `0` (manual) | same number as Frameo's `KEY_DISPLAY_BRIGHTNESS` |
| `dumpsys power` | `mScreenBrightnessSetting=187`, `…OverrideFromWindowManager=191`, `…SettingMinimum=30`, `…Default=102`, `Display Power: state=ON` | a front window (another session's app) had a window override of 191 at the time; sysfs still read 187 |
| locked: root `echo 0 > …/brightness`; `cat`; 3 s; `cat`; `echo 187 > …`; `cat` | `0` → `0` (settings still 187) → `187`, dark 8 s | nothing wrote it back while no brightness changed; single-command `su -c` works. The 05-hub rule (no window brightness changes while off; restore on stop/Exit/crash/start) still applies; S2's "what writes it back" is still unmeasured |

### e. Audio volumes (read only, unchanged)

| Command | Output | Note |
|---|---|---|
| `dumpsys audio` STREAM_MUSIC | `Max: 15`, `Current: 2 (speaker): 2` | was 5/15 in device-log; Frameo sets it at its start |
| STREAM_ALARM | `Max: 7`, `Current: 40000000 (default): 6` | speaker uses the default entry |
| STREAM_NOTIFICATION · STREAM_SYSTEM | `6/7` (speaker) · `6/7` | |
| AOSP 6 default curves (Gains.cpp), estimate | media speaker 2/15 ≈ **−47 dB**; alarm 6/7 ≈ **−7 dB** | a −24 dBFS chime on USAGE_MEDIA would land near −70 dB: probably inaudible. Loudness can't be measured on the frame (the mic records silence); needs an ear test |

### f. Accelerometer

| Command | Output | Note |
|---|---|---|
| `dumpsys sensorservice` | `Gravity sensor \| The Android Open Source Project \| v1 \| android.sensor.accelerometer \| handle 0x0 \| type=1 \| continuous \| maxRate=200.00Hz \| no batching \| non-wakeUp` | the HAL accelerometer (`gs_mir3da`); name says "Gravity" but it is `TYPE_ACCELEROMETER` |
| same, last 50 events | `(-0.1, 9.5, 3.5…4.1)` m/s², timestamps ~10 ms apart | ≈ 100 Hz delivered; z jitters ±0.45 m/s² at rest |
| same, h/w section | `active-count=2, batch_period(ms)={20.0, 20.0}` | two other apps listen at `SENSOR_DELAY_GAME` right now |
| same | also a type-1 `Gyroscope Bias (debug)` (AOSP virtual) and AOSP `Gravity`/`Linear Acceleration`/rotation vectors | virtual sensors fed by the dead gyro/compass: use `getDefaultSensor(TYPE_ACCELEROMETER)` only |

### g. Time

| Command | Output | Note |
|---|---|---|
| `date` on the Mac and the frame | `Sun Oct 18 09:41:45 CEST 2026` both | synced to the second |
| `getprop persist.sys.timezone` · `settings get global auto_time` · `ntp_server` | `Europe/Amsterdam` · `1` · `null` (default pool) | |
| `logcat -b events` from boot | `01-01 01:00` (kernel) → `05-15 02:00` (Android) → `10-18 08:19:32` | two jumps; NTP correct ~36 s after boot (`/proc/uptime` 4969 s at 09:41:45 → boot 08:18:56) |
| `getprop ro.runtime.firstboot` · `ro.build.date.utc` | `1621036805940` (2021-05-15 00:00 UTC) · `1772863181` (2026-03-07) | the pre-NTP clock is **2021-05-15**, a plausible-looking date; "year ≥ build year" rejects it |
| `cat /proc/sys/kernel/random/boot_id` | `ebbb66b9-…` | readable without root, for `Deadline.bootId` |

### Tile order

| Command | Output | Note |
|---|---|---|
| `grep -rnE 'tile\.(order\|accent)' examples` | 04-mawaqit `40`; 06-pomodoro `60`, `#E5735C` (already in the scaffold manifest) | order 60 is free |

## M2 · Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard` | `BUILD SUCCESSFUL`; core 118 tests, app 49 tests, 0 failed; lint clean; `api23 guard: 3521 platform references … OK` | the M2 "done" check |
| first `api23Guard` on the M2 APK | `MISSING android/app/RemoteAction used by …SettingsStore$1, TimeWatcher$register$receiver$1`; `TextClassification used by kotlinx…JsonTreeDecoder` | false positives: R8 merges our lambdas/continuations with androidx's SDK_INT-guarded ones (text context menu), and the guard attributed by class+method name |
| fix: keep rules (`app/proguard-rules.pro`) + line-exact attribution in `tools/api23_guard.py` | `OK`, APK +16 kB | the guard now maps each instruction's line to R8's mapping range, per overload |
| guard sanity: inject `SystemClock.currentNetworkTimeClock()` (API 33) into `Logs.event`, assembleRelease, guard | `MISSING android/os/SystemClock.currentNetworkTimeClock()… used by dev.framio.pomodoro.platform.Logs`, `FAIL` | the guard still catches real calls; injection reverted (a `Map.getOrDefault` injection passed: core-library desugaring backports it) |
| Roborazzi `captureRoboImage` at `sdk = 23` | wireframe boxes only | Robolectric native graphics starts at API 26: goldens render at 26, behaviour tests (RootFlowTest) run at 23 |
| `ls -l app-release.apk` | 1,654,081 bytes | budget ≤ 3 MB |

## M2 · Device (frame 68384696; every burst through `scripts/frame-lock.sh run`, frame cleaned after each)

| Command | Output | Note |
|---|---|---|
| `adb install -r app-debug.apk` | `Success` after 58 s | debug APK is 14.6 MB (LeakCanary); dex2oat is slow: prefer the release APK for device bursts |
| debug, `am start … --ei sec 20 --es mode classic --es task Thesis ch.3 --es cmd start` (unquoted) | only `EVENT\|adb\|mode classic`, no start | adb joins arguments: a task with a space must be quoted twice (`--es task "'Thesis ch.3'"`) |
| same, quoted; `fr shot` at +4 s, +20 s, +27 s | `adb\|start for 20m · Thesis ch.3` → `phase\|Focus` 13:39:38.99 → `display\|Dim`, `phase\|Break` 13:39:58.99 (20.0 s) → `sound\|cue FocusEnd Soft volume 60% (2020 ms)` 13:39:59.61 → `log\|Unrated 20m Classic`, `phase\|Ready` 13:40:03.99 (5.0 s) → `cue BreakEnd` | a whole Classic cycle at "minutes as seconds"; the first cue starts 0.6 s late (PCM rendered on first use), the second 20 ms |
| `am start --es cmd start` in Ready, 7 s, `am force-stop`, 3 s, `am start -n …/.MainActivity` | old: `wake at elapsed 19285236`; new process: `restored Focus (saved at 19260236)`, `wake at elapsed 19285236`, `service\|start`; screenshot 4 s later shows the right time left | kill → resume exactly: same deadline, ring and digits continue; the block kept running while dead |
| `--es cmd selftest` (debug, in Focus) | `CHECK\|time\|boot\|PASS`, `time\|trusted\|PASS`, `engine\|loaded\|PASS\|phase Focus scale 0.0166 today 1/6`, `service\|running\|PASS`, `window\|keepScreenOn\|PASS\|flag true`, `root\|su\|PASS`, `display\|backlight\|PASS\|level 187, we wrote null`, `sound\|track\|PASS`, `store\|log\|PASS\|2 records` | all PASS |
| `--es cmd end` | `log\|EndedEarly 23m 53.52s Classic`, `service\|stop`, `phase\|Idle`, `service\|stopped` | the session log is written (fsync) and the service stops at Idle |
| debug `--ei sec 4 …`, `fr shot` 2 s into the break, `input tap 855 390` (Done) | break screen with prompt and wrap-up card; `log\|Done 4m Classic`; card gone | wrap-up card works on the device |
| `adb shell su -c "cat …/brightness"` after cleaning | `255`; `dumpsys power`: `mScreenBrightnessOverrideFromWindowManager=255`, `mResumedActivity … dev.framio.mawaqit` | not ours: Mawaqit came forward after our force-stop and sets its window brightness to 1.0. Pomodoro never wrote the backlight in M2 (`we wrote null`) |
| release: `am start -W -n …` after force-stop, 3 runs (+4 later runs) | `TotalTime` 1175, 1166, 1213 ms (later 1180, 1281, 1282, 1320) | **over the 900 ms budget** (M6). The first frame is the Loading screen, so this is process start + Compose init |
| release `dumpsys meminfo dev.framio.pomodoro`, Home after 4 s | `TOTAL 19174` KB (Java 4196, Native 2784, Code 7716) | budget ≤ 40 MB |
| same, in Focus after 6 s | `TOTAL 20770` KB (Java 4604, Native 3152, Code 8056) | |
| `top -n 1` in Focus | `0%` CPU, RSS 49 MB | |
| release, `gfxinfo reset`, idle in Focus 10 s, `gfxinfo` | `Total frames rendered: 10`, `Janky 0`, 90th 12 ms | the 1 Hz ticker: one 12 ms frame per second, nothing in between |
| release, Begin transition (`input tap 640 664`), first version: AnimatedContent `fadeIn + scaleIn(0.96)` / `fadeOut`, 450 ms | cold: 24 frames, 14 janky (58 %), 90th 61 ms; warm: 23 frames, 61 %, 90th 65 ms | too slow |
| fade only (no scale), 360 ms | 21 frames, 71 % janky, 90th 65 ms | scale wasn't it |
| `gfxinfo framestats` of that | fade frames: swap 11–22 ms, issue 4–15 ms | GPU-bound: AnimatedContent's fades draw each screen into a full-screen offscreen layer, two per frame |
| own fade: `graphicsLayer { alpha; compositingStrategy = ModulateAlpha }` on each screen, `EnterTransition.None togetherWith ExitTransition.None` | 26 frames, 9 janky (35 %), 90th 38 ms, 95th 44 ms; swap back to ~2 ms | kept. Remaining jank: the frame that first composes Focus (anim 15 + layout 4 + record 10 ms = 58 ms) and one 25 ms anim frame mid-fade. Budget < 3 %: **M6** |
| cue loudness | not measured (mic dead) | **ear check pending**: soft chime peak −18 dBFS × in-app volume 0.6 (≈ −22.4 dBFS) on USAGE_ALARM (alarm 6/7 ≈ −7 dB); Deep's rising −24 → −12 dBFS × 0.6. Settings → Cue volume → Play, in 10 % steps |

## M3 · Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard` | `BUILD SUCCESSFUL`; core 131 tests, app 97 tests, 0 failed; lint clean; `api23 guard: 3533 platform references … OK` | the M3 "done" check |
| `ls -l app-release.apk` | 1,744,232 bytes | budget ≤ 3 MB |
| Roborazzi `DeepScreenshotTest` | 16 new goldens (ritual Ink/Paper/portrait, Calm Ink/Paper/portrait, hold panel Ink/Paper, Blackout peek, Deep paused, end note, park pad Ink/Paper, wrap-up Ink/Paper, break with parked thoughts) | the 11 M2 goldens still match unchanged (screens became transparent; the test draws the theme background behind them) |

## M3 · Device (frame 68384696; every burst through `scripts/frame-lock.sh run`, frame cleaned after each; scripts and screenshots in the session scratchpad)

### Begin "breathe in" (release, `dumpsys gfxinfo … framestats`, two windows: 0–1.7 s and 1.7–4 s after the tap)

Framestats keeps only the last 120 frames, so one dump of the whole 3 s breath loses its start: each Begin was dumped twice.

| Build | Classic Home → Focus (janky / frames) | Deep Ritual → Calm | Note |
|---|---|---|---|
| a: first M3 build | 30/172 (17 %), one dump | (ritual Begin missed) | cue PCM pre-render (`prepared 3 cues in 906 ms`) ran during the breath |
| b: pre-render deferred 4 s | 20/115 + 3/45 (14 %) | 49/108 + 3/45 (34 %) | Deep frames: `record` 4–11 ms, `issue` 12–25 ms |
| c: animated root colour → night layer + Offscreen breath layer | 31/118 + 9/43 | 35/89 + 3/38 | worse; see the trace below |
| d: night layer and Calm face `ModulateAlpha` | 23/116 + 3/46 | 40/109 + 7/46 | bucketed by 200 ms: all Deep jank in 0–1000 ms; 1.0–3 s (breath, night fade, Calm fade-in) smooth |
| e: outgoing screen drawn once into an Offscreen layer | 19/120 + 6/41 (15 %) | 50/113 + 3/42 | Classic's exit now 9–12 ms frames; Deep still `record` 8–12 ms per frame |
| f: Calm arc in whole minutes + incoming pre-warm at alpha 1/255 | 18/117 + 6/43 (**15 %**) | 49/115 + 5/45 (**34 %**) | Deep `record` 0.3–1.6 ms (was 4–12). Left: ~0.9 s of 20–30 ms frames right after Begin, UI thread starting 10–17 ms late |

M2 measured 35 % on Classic's Begin. Target < 5 %: **not met** (M6). The causes found and fixed, from `atrace -t 4 gfx view sched freq am dalvik` (text output parsed by slice):

| Finding (atrace / framestats) | Fix |
|---|---|
| `Allocate 1280x800 HW Layer` + issue every frame: a full-screen rect with alpha < 1 and the default compositing strategy goes through saveLayer | night layer `CompositingStrategy.ModulateAlpha` (one rect, alpha on the draw call) |
| `Issue HW Layer DisplayList Compose 448x448` every frame: an Offscreen layer whose child RenderNode properties change is re-rendered each frame | no Offscreen on anything that animates inside |
| `tessellatePath` / `approximatePathOutlineVertices` each frame in Calm: the ticker re-reads the clock right after composing, the progress moves by a hair, the arc starts a 600 ms ease and its path is rebuilt every frame | `FaceTiming.minuteProgress`: Calm's arc only moves when the shown minutes change |
| fading the outgoing screen op by op (ModulateAlpha) re-issues every chip and glyph with alpha each frame (Classic exit frames 15–30 ms) | outgoing screen `Offscreen` (drawn once, faded as one textured quad): Classic exit frames 9–12 ms |
| `prepared 3 cues in 906 ms` on a default-dispatcher thread during the breath | `SoundEngine.prepare` waits 4 s |
| first draw of the incoming screen (glyph cache, path textures) a few frames into its fade: a 30–46 ms cluster | incoming screen pre-warmed at alpha 1/255 (drawn, invisible), so that work lands in the composition frame |
| still open: 3 concurrent GCs (88–104 ms each on HeapTaskDaemon, plus suspend checks on main) and `serviceStart` 13–14 ms on main in the first second; `Choreographer#doFrame` 20–30 ms with `animation` only 3–8 ms | M6: allocation profile of the transition; start the FocusService off the first frame |

### Calm display (release)

| Command | Output | Note |
|---|---|---|
| `--es mode deep --es task Thesis --ei min 5 --es cmd start`, 6 s, `dumpsys power`, root `cat …/brightness` | `mScreenBrightnessOverrideFromWindowManager=30`, sysfs `30` | `WindowLevels.CALM = 0.12` = the setting minimum (30/255): Android clamps any lower window value to it |
| `dumpsys sensorservice` | `activated package=dev.framio.pomodoro.platform.Motion … samplingRate=20000us` | the accelerometer only while Deep focus is on screen (SENSOR_DELAY_GAME) |
| `gfxinfo reset`, **120 s** idle in Calm, `gfxinfo` | `Total frames rendered: 70`, janky 10, 90th 19 ms | two minute ticks × the 600 ms arc ease (≈ 35 frames each); **no frames between ticks** |
| `dumpsys meminfo` · `top` | `TOTAL 24766` KB · `0%` | budget ≤ 40 MB |
| screenshot | near-black, thin periwinkle arc, "5 min" + "Thesis" dim | the launcher's hot-corner ring (bottom-left) is the brightest thing on screen, as M0 predicted; not ours to fix |

### Deep Calm cycle (debug, minutes as seconds; logcat streamed to a file per burst)

| Command | Output | Note |
|---|---|---|
| `--es cmd knock` (first version) | `motion|accelerometer off` … `knock: not listening` | an adb intent pauses and resumes the activity, so the resume effect unregisters and re-registers the sensor; the injection now waits up to 3 s for it |
| `--es cmd knock` | `accelerometer on` → `knock: injected` → `motion|double knock` → `display|lift` → `window|brightness 30 → 76 over 500ms` … 5.0 s … `display|lower` → `76 → 30 over 1s` | the synthetic double knock goes through the real `KnockDetector`; the peek lifts Calm to 0.3 and back |
| `input tap 640 400` | no event, screenshot: faint "Hold to open" | a tap does nothing else |
| `input swipe 640 400 641 401 1600` (hold) | `display|lift`, panel on screen (Park a thought · Pause · End) | |
| Park a thought, three strokes, Park it | pad screenshot with the strokes; `display|lower` | |
| hold, End, `input text figures%snext`, End session | `log|EndedEarly 1h 5m 44.22s Deep`, `display|Normal`, `window|brightness system`, `phase|Idle`; Home shows `↳ Last time: "figures next"` | the system IME shows over the bottom half (prompt sits at the top) |
| 25-"minute" block, park a scribble, wait for the deadline | `phase|WrapUp`, `display|Normal over 10s`, `window|brightness 30 → 187 over 10s` (12 ms later), `sound|cue FocusEnd Rising … (7353 ms)` **69 ms after the deadline**, `accelerometer off`; `window|brightness system` 10.8 s later | the sunrise; the first cue is on time now (M2: 0.6 s late; PCM prepared 5.6 s after Begin, 1.6 s in debug) |
| wrap-up: Done, 4, Start break | `log|Done 25m Deep`, `phase|Break`, `display|Dim`; break screen shows "Parked thoughts" with the scribble | |
| `dumpsys sensorservice` afterwards | `activated` / `de-activated package=…Motion` pairs at every Focus start/stop; no active Pomodoro connection | |

### Blackout paths (debug)

| Step | sysfs `rk28_bl/brightness` · log | Note |
|---|---|---|
| start `--es display blackout --ei sec 200` | `0` · `root|backlight 0 ok 87 ms`, `backlight off (day level 187)` | one `su -c` per write, 84–188 ms each |
| `--es cmd knock` | `64` · `display|lift`, `root|backlight 64` | Blackout's peek: stepped through DisplayPower (marker + compare-and-set) |
| 5 s later | `0` · `display|lower`, `backlight 32`, `backlight 0` | |
| tap | `0` | nothing at all in Blackout |
| hold | `64` · panel | |
| Pause | `187` · `phase|Paused`, `display|Normal`, `backlight 187` | |
| Resume | `0` · `display|Blackout` | |
| `am start -c HOME` (another app in front) | `187` · `focus|lost-front in Focus`, `backlight 187` | onStop restores |
| back to Pomodoro | `0` · `display|Blackout` | onStart re-applies |
| `am force-stop` while dark | `0` | no handler runs |
| relaunch | `backlight was left at 0 by a killed run; restored 187`, then `restored Focus`, `backlight off` | recoverAtStart (marker), then the restored block goes dark again |
| `--es cmd end` | `187` · `display|Normal` | |
| a 5-"minute" Blackout block reaching its end | `9, 28, 46, 74, 93, 112, 130 …` one per second; `backlight 9 … 84 …` every ~0.5 s; `sunrise done at 187 over 10s` | the sunrise from Blackout |
| clean (`force-stop`, write 187) | `187`, later `255` | 255 is the next app's window override (as in M2), not ours |
| release, Blackout 7 s: `meminfo` · `top` | `TOTAL 26232` KB · `0%` | |

### Frameo at 23:00

Not observed: no M3 session spanned 23:00 (bursts ran 14:28–15:34). Still open for M4/M6 (logcat only, no lock).

## History + notes · Phase A (JVM only, no device)

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test` | `BUILD SUCCESSFUL`; 207 tests, 0 failed (68 new in `core.notes` / `core.history`) | M3's tests included |
| `ScaleTest` (5k sessions, 20k entries, warm, best of 5) | decode 1022 kB log + 2944 kB journal **93 ms**; join + stats + report + filtered query **47 ms**; one-day refresh **15 µs**; stats cache 1221 kB / 1000 days, decode **26 ms** | desktop JVM proxy; Phase B measures the same on the frame |
| `validate_palette.js` (dataviz) reference categorical palette, dark steps, on Ink `#171B22` / Forest / Tomato / Evening surfaces | all PASS (worst adjacent CVD ΔE 8.4, normal 19.3) | tag hues 0..7 |
| same, light steps on Paper `#FBF8F2` | PASS, contrast WARN for 3 slots (2.0–2.7:1) | relief: chips always show glyph + label |
| same, Pomodoro's dark mode accents on Ink | FAIL lightness band (L 0.68–0.79) | accents stay mode identity, not chart colours |
| `--ordinal` outcome ramps: Ink `#cde2fb #9ec5f4 #6da7ec #3987e5`, Paper `#184f95 #256abf #3987e5 #6da7ec` | all PASS (light end 4.74:1 / 2.36:1) | Done → Unrated |

## M4 · Breaks, Flow, Gentle start

Bursts: `scratchpad/m4/burst_*.sh` through `scripts/frame-lock.sh run` (FRAME_OWNER framio-7a/pomodoro), each ending in
force-stop + `echo 187`. The debug install alone held the frame 84 s (dex2oat), the other bursts 25–60 s.

### Flow block → tiered break (debug, minutes as seconds)

| Command | Output | Note |
|---|---|---|
| `--ei sec 1 --es mode flow --es task Thesis --es cmd start`, 27 s, `--es cmd stop` | `phase|Focus` · `window|brightness 30` (Calm) · `service|start` 3.5 s after Begin · `adb|stop` → `display|Dim over 1.5s` · `phase|Break` · `window|brightness 30 → 76 over 1.5s` | the service start now waits out the breath |
| break screenshot | "Worked 27 min → 8 min break", "Stand up and move", wrap-up card, landscape (Paper theme, day) | 27 min > 25 → the 8 min tier |
| 8 s later | `log|Unrated 27m 31s Flow` · `phase|Ready` · `cue BreakEnd` · `window|brightness system`; sysfs **76 in the break, 187 after** | brightness lowered for the break and restored |
| Calm screenshot | near-black, "4 min" + "Flow · counting up" + "Thesis" dim | elapsed only, once a minute |

### Gentle start (debug)

| Command | Output | Note |
|---|---|---|
| first try: `--es mode gentle --es cmd start` | restored `phase|Ready` of the earlier Flow run: SelectMode refused in Ready, `start` began another **Flow** block | a force-stopped run is restored; bursts now send `cmd end` + `cmd home` first |
| `--ei sec 10 --es cmd end`, `home`, `--es mode gentle --ei sec 10 --es cmd start` | `phase|Focus` → `display|Dim over 1.5s` · `window|brightness 187 → 76` · `phase|Break` → `log|Unrated 10m Gentle start` · `phase|Ready` · `window|brightness system` | 10 / 3 |
| Ready screenshot, `input tap 640 445` | "Ten minutes done. That counts." · "Another 10? Stopping here is fine too." · **Another 10** · Done for now → `phase|Focus`, Disc face "7:00" | the Disc face on the device |
| `gentle_focus` screenshot 5 s after the adb start | all black but the hot-corner ring | not reproduced in the later shot (`gentle_again`); probably mid-relaunch. **Open: re-check in M6** |
| after clean, mawaqit in front | sysfs 30, `mScreenBrightnessOverrideFromWindowManager=5` | mawaqit's own window dims the frame, not ours (setting still 187) |

### Break landscape, release (`dumpsys gfxinfo`, break of a Flow block, Paper/day, wrap-up card showing)

| Build | Frames / s | Janky | Note |
|---|---|---|---|
| a: first landscape (full-screen hill and cloud textures, panels translucent) | 1162 / 30 s = **38.7 fps** | 9.4 % | `framestats`: CPU 13 ms median, but **every frame 35 ms apart** (2 vsyncs): GPU / memory-bandwidth bound |
| b: hills filled only to the next ridge, sky only to 0.66 h | 38 fps | — | no change |
| c: landscape only, no UI (experiment flag) | 55–58 fps | 1.2 % | the landscape alone fits; the UI over it doesn't |
| d: hills static (no pan) | 38 fps | 8.6 % | no partial updates on this GPU: any motion redraws the whole screen |
| e: + no window background, no root background under the break | 41.5 fps | 7.6 % | two full-screen fills per frame removed (helps every screen) |
| f: + UI blocks (panel, wrap-up card, buttons) in Offscreen layers | **50–51 fps** | 11 % | each block one textured quad instead of every glyph and border |
| g: + clouds split into 4 small textures, ridge edges as textures + opaque rects | 51 fps | 18 % | fill down, but 9 observed layer blocks: Compose `anim` 2.6 → 5.8 ms |
| h (shipped): clouds and tufts drawn in the draw phase of 2 layers (5 state reads / frame) | 1482 / 30 s = **49.4 fps** | **21.7 %** | 27 % CPU, "Slow UI thread" 254: the per-frame re-record moved the cost to the UI thread |

**Target ≥ 55 fps and < 3 % janky: not met** (best 51 fps). PSS 27.1 MB in the break.

### Begin "breathe in" (release, after the FGS start delay)

| Window | Classic Home → Focus | Deep Ritual → Calm |
|---|---|---|
| 0–1.7 s | 43 / 118 janky (36 %), 90th 32 ms | 52 / 109 (48 %), 90th 36 ms |
| 1.7–4 s | 6 / 38 (16 %) | 11 / 43 (26 %) |

Not better than M3 (Classic 15 %, Deep 34 % over the whole breath) despite `service|start` now 3.5 s after Begin
(log). Measured right after the landscape runs, with other sessions active on the frame; not repeated (time). M6.

## History + notes · Phase B

### Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard` | `BUILD SUCCESSFUL`; core 213, app 193 tests, 0 failed; 80 goldens; lint clean; `api23 guard: 3579 platform references … OK` | release APK 2,008,717 bytes |
| `POMODORO_SEED_OUT=… POMODORO_SEED_DAYS=650 ./gradlew :core:test --tests '*SeedTest*' --rerun` | `wrote 2052 sessions, 2476 journal lines` (sessions.jsonl 479 kB, history.jsonl 370 kB) | seed files for the device |

### Device (frame 68384696, release APK; every burst through `scripts/frame-lock.sh run`, frame cleaned after each)

| Command | Output | Note |
|---|---|---|
| burst 1: `install -r` release, root-copy the user's `sessions.jsonl` to `/sdcard/pomodoro-backup`, push and root-copy the seed files into `files/` (chown u0_a29) | `Success`; 21 s | the user's log: 59 lines, 13,745 bytes |
| burst 2: start, tap Insights (950,64), no cache file | `EVENT|history|loaded 2052 sessions (0 unreadable), 2476 entries in 2644 ms` · `index 2052 sessions in 567 ms` · `insights|first report in 3242 ms (full)`; gfxinfo 16 frames, 88 % janky, 90th 85 ms; PSS 34.3 MB | cold: reads both files |
| same, after force-stop (cache from the first run, 508 unfolded days) | `stats from cache: 508 days in 653 ms` · `first report in 684 ms (cache)`; PSS 30.8 MB | over the design's 300 ms threshold → months folded |
| burst 3 (82 s: over the 60 s guideline, install + two runs; later bursts split): new build with folded cache, lazy Insights, Offscreen fade | cold `first report in 3316 ms (full)`, 60 % janky, PSS 34.0 MB; warm `stats from cache: 173 days in 294 ms` · **`first report in 321 ms (cache)`**, 17 frames, 35 % janky, 90th 69 ms, **PSS 25.4 MB** | cache 172,579 bytes (was ≈ 500 kB); warm open is what users see after the first time |
| burst 4: `--es mode deep --es task "'Device test'" --ei min 1 --es cmd start`, hold (swipe 1.6 s), tap Phone (190,453), hold, End (900,675), Had to leave (408,161), suggestion (192,447), End session (914,692) | `history.jsonl`: `{"type":"distraction",…,"at":"PT11.068S","tags":["phone"]}`, `{"type":"end",…,"at":"PT30.247S","tags":["leave"]}`; `sessions.jsonl`: `"outcome":"ended_early","resumeNote":"Continue Device test"`; 41 s | panel shows "Logged · Phone", "1 distraction logged"; end sheet "Ending at 0 min of 1" (Paper theme on the frame) |
| burst 5: tap History (826,64), first row | `loaded 2053 sessions … in 2645 ms`, `index … 559 ms`; detail shows Phone, Ended: Had to leave, ↳ Continue Device test; PSS 32.8 MB | |
| same, `--es cmd export`, `adb pull …/files/export/` | `EVENT|export|2053 sessions, 2598 entries → /storage/emulated/0/Android/data/dev.framio.pomodoro/files/export`; 3 files, 1.39 MB (JSON 939 kB, sessions.csv 224 kB, entries.csv 228 kB) | no permission needed (API 19+ app dir) |
| same, restore: root-copy the backup back, remove `history.jsonl`, `stats-cache.json`, the export and seed dirs | `sessions.jsonl` 59 lines, 13,745 bytes; then `rm -rf /sdcard/pomodoro-backup` | the frame's own history is as before; the test session is gone with the seed |

## M5 · Customization

### Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard` | `BUILD SUCCESSFUL`; core 235, app 261 tests, 0 failed; 112 goldens; lint 0 errors 0 warnings (3 hints); `api23 guard: 3588 platform references … OK` | release APK 2,176,435 bytes |
| desktop JVM, 60 s of each synth at 22.05 kHz | wind 19 ms, rain 46 ms; RMS pink 0.107, brown 0.101, rain 0.138, wind 0.064 (gusting); peaks ≤ 20,267 | why the stream runs at 22.05 kHz |
| `./gradlew :app:assembleDev` | `app-dev.apk` 2,192,814 bytes (release + adb hooks) | installs in ~15–27 s; the debug APK holds the frame ~60 s |

### Device (frame 68384696; every burst through `scripts/frame-lock.sh run`, frame cleaned after each; scripts and screenshots in the session scratchpad `m5/`)

| Command | Output | Note |
|---|---|---|
| burst A (78 s: install + `uiautomator dump` taps, over the 60 s guideline; later bursts used fixed coordinates): dev APK, Home → Settings → Modes → Copy, swipe two quarter turns on the Focus dial (578,503→660,585→578,667), During → Rain | `install: Success`; editor "Classic 2" (`own-1`): Focus dial **25 → 55 min**, preview follows (36:00 ring), Rain selected, Soft chime | the row finder matched the rail's "Deep" and copied Classic: fine for the test. Paper theme (the user's), hot-corner dot over empty space |
| burst B (60 s): dev APK, Home "+ New" → keyboard; tap c a f, long-press e (900 ms), tap é, space, ?123, 2, €; hold ⌫ 1.2 s; Cancel | text "Caf" → strip é è ê ë ē on the row below → "Café 2€" (symbols layer) → held ⌫ cleared all 7 characters (1 + repeats every 70 ms after 450 ms); hint shown again; Cancel saved nothing | keys 104×92, bottom row from x 120, the launcher dot alone in the corner. `clean` read 255 afterwards: another session's app came forward and set its own brightness |
| burst C (36 s): `--es cmd evening --es at 12:00` → `--es at 21:30` → Classic `--ei min 1 --es cmd start` → `end` → `--es at off` | `EVENT|evening|judging as if it were 21:30` · `amber on` · `display|evening cap on` · `window|brightness 187 → 89 over 3s` · `window|brightness 89`; Home and Full focus in amber (screens c2, c3) | real time was 20:48, past the user's stored 20:30, so amber was already on at start (`amber on` at launch, then off at the fake noon). System clock untouched |
| burst D (72 s, over the guideline: each `am start` takes ~2.5 s here): dev, `--ei sec 30 --es mode own-1 --es cmd start` (Classic 2 + Rain, Full Ring with seconds), `top -d 5 -n 3`, `pause`, `top`, `resume`, block end, `done` | `soundscape|Rain at 35% (22.05 kHz stream)`; **Pomodoro 1 % / 1 % / 1 %, mediaserver 1 % / 0 % / 1 %** (total User 4–5 %); paused: Pomodoro 0 %; `Rain stopped` 2.2 s after Pause (1.5 s fade + 0.5 s buffer), restarted on Resume; Break at the deadline → `Rain stopped` 2.2 s later; break silent | target ≈ 1–2 %: met. The 1 % includes the Full face's 1 Hz tick; the soundscape thread is not separable at top's 1 % resolution. Logged one `Unrated 30m Classic 2` line (scaled time) |
| burst E (51 s): release APK back, start | `install: Success`; the run from D restored at Ready (D ended with `done` → Ready, not Home) | trap: end bursts with `cmd home` |
| burst F (18 s): `--es cmd home`, Settings, Delete on "Classic 2" | `phase|Phase$Idle`; Modes list back to the 4 presets, Undo bar "“Classic 2” deleted" | the user's settings are as before (the test session lines from D/C remain in the history, as in earlier milestones) |

## M6 · Final

### Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease :app:assembleRelease api23Guard` | `BUILD SUCCESSFUL`; core 235, app 261 tests, 0 failed; 112 goldens (6 Insights goldens re-recorded on purpose: headline text anti-aliasing without the offscreen fade layer); lint 0 errors 0 warnings (3 hints); `api23 guard: 3590 platform references … OK` | release APK 2,176,429 bytes |

### Device (frame 68384696, dev APK unless noted; every burst through `scripts/frame-lock.sh run`, ≤ 60 s, frame cleaned after each; scripts in the session scratchpad `m6/`)

| Command | Output | Note |
|---|---|---|
| break landscape, Flow block → break, `gfxinfo reset`, 30 s, `gfxinfo framestats` (M4 shipped: 1482 frames / 30 s = 49.4 fps, 21.7 % janky) | **579 frames / 30 s (19.3 steps/s), janky 5 (0.86 %), 90th 7 ms, 95th 13 ms**, missed vsync 0, slow UI thread 5; PSS 31.6 MB (dev) | the landscape clock now ticks every 3rd vsync (50 ms): each step ≤ ~1.1 px of cloud motion; GPU has 3 vsyncs per step. Screenshot `landscape-a.png` unchanged in look |
| Begin, Classic Home → Focus and Deep Ritual → Calm, two gfxinfo windows (0–1.7 s, 1.7–4 s), before the Calm change | Classic 7/117 (6 %) + 7/32; Deep 35/113 (31 %) + 3/34 | (M4: Classic 36 %, Deep 48 % in 0–1.7 s, measured with other sessions active) |
| hooks probe `EVENT\|alloc` (ART `art.gc.*` runtime stats + main-thread alloc counters, first 1.7 s of a block) | Classic 518–591 kB in all, main 526 kB / 12.2k objects, **0 GCs**; Deep 592–699 kB, main 584–616 kB / 15–16k objects, **0 GCs** | GC is not the cause any more (M3 saw 3 GCs: the cue pre-render, since deferred to 4 s) |
| `atrace -t 3 gfx view sched dalvik am` around Deep's Begin | first frame 124 ms (`animation` 95 ms: composing the Deep screen + its VM); then **system_server `PowerManagerService` `Choreographer#doFrame` every frame for 0.78 s** (the platform's own ramp of the window-brightness change 187 → 30, writing the backlight each frame, monitor contention with `PhotonicModulator`), overlapping app frames of `doFrame` 11–24 ms and RenderThread `DrawFrame` 13–24 ms | Classic changes no brightness, which is why only Deep was bad |
| night layer drawn as the root's own lerped colour (one opaque fill instead of a blended one) | Deep 37/111 (33 %) | no effect: reverted |
| **Calm's window dim deferred 3 s (after the breath)** | Classic 10/116 (9 %) + 3/27; **Deep 24–25/115 (21–22 %)** + 7/34 | kept. Trace after: no system ramp in the breath; app `doFrame` avg 12 ms (`animation` ~6 ms, `traversal` ~4 ms), RenderThread avg 10 ms; still 1 long composition frame (101–127 ms) |
| cold start, `am start -W` ×3 + logcat (before) | `TotalTime` 1317, 1293, 1221; `app\|start` +300 ms after `Start proc`, `restored` +635 ms, Displayed +1.29 s | the engine restore is done long before the first frame |
| `atrace` cold start (before / after building the engine on the main thread first) | bindApplication 141 → 133 ms; activityStart 405 → 377 ms (main thread **68 ms "Contended on monitor with owner DefaultDispatcher-worker-2"** gone: the engine `lazy` was being built by a worker); first `traversal` 553 → 533 ms (composition ~280 ms + `measure` 210 ms + layout 27 ms); next frame 65–75 ms + first RenderThread frame 37–47 ms | the rest is Compose's first composition of Home on this CPU |
| cold start after (dev ×3, release ×2), with `EVENT\|app\|home on screen` | dev 1330, 1327, 1322, 1252; **release 1288, 1281**; Home on screen 15–25 ms after `Displayed` (Home is the first frame) | budget 900 ms: **not met** |
| seed files (2052 sessions, 2476 journal lines) root-copied into `files/` (device-test data, wiped at the end) | `loaded 2052 sessions … in 2722 ms`, `index … 529 ms`, `insights\|first report in 3280 ms (full)`; warm `first report in 296–300 ms (cache)` | load times as Phase B (kotlinx JSON on ART 6) |
| Insights / History open, screenshots at +0.5 s | skeleton cards / skeleton rows on screen at +0.5 s while the report or list loads (`insights-a-cold-0.5s.png`, `history-a-0.5s.png`) | (Phase B: a "Loading…" line) |
| Insights warm open, gfxinfo (stagger + fade / stagger only) | 11 frames, 8 janky, 90th 81 ms / 10 frames, 7–8 janky, 90th 133 ms; History 6–8 frames, 4–5 janky | Phase B: 17 frames, 6 janky (35 %), 90th 69 ms, but 12 of those were cheap fade frames. Each row's first composition is 50–130 ms here, so every composing frame is janky; the % is not comparable on 10 frames. **Target not met** |
| **release**: `install -r`, cold start ×2, `pm clear dev.framio.pomodoro`, `ls /data/data/dev.framio.pomodoro/` | `Success`; `TotalTime` 1288, 1281; `Success`; only the `lib` link left; `/sdcard/Android/data/dev.framio.pomodoro` absent | the frame's agent test history is gone; the user starts fresh. Backlight 187, lock free |
