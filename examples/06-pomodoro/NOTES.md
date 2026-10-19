# 06 · Pomodoro: handoff notes

Each milestone appends its own section. Device evidence lives in LOG.md.

## M0 — device facts for Pomodoro

Built: `tools/frame.sh` (shot · shotonly · tap · swipe · run · clean). Every subcommand takes the frame through
the repo's `scripts/frame-lock.sh run -w "pomodoro <cmd>"` with `FRAME_OWNER=framio-7a/pomodoro` (queue, heartbeat,
owner file). **Later milestones: use `tools/frame.sh` or `scripts/frame-lock.sh run …` for every adb burst,
`scripts/frame-lock.sh status` to see who holds the frame, and end every turn with `tools/frame.sh clean`**
(force-stops Pomodoro, writes 187 to the backlight). `FRAME_WAIT_MAX=<s>` gives up with exit 75 instead of waiting.
Evidence for everything below: LOG.md "M0".

Conclusions that change the implementation:

1. **An IME exists, but build the Compose keyboard anyway (M5).** AOSP LatinIME 6.0.1 is installed, default
   and works (English US only). In landscape it takes 410 of 800 px, is light grey (clashes with Ink), and the
   launcher's hot-corner dot sits exactly on its `?123` key (a long-press there goes HOME). Until M5, a
   `BasicTextField` with the system IME is an acceptable stopgap. The Compose keyboard should not be backed by
   a focused `BasicTextField` (or the system IME pops up too). Keep its bottom-left 88×88 px empty.
2. **Frameo does not pop over a foreground app in the evidence, but its 23:00 sleep schedule is a risk.** Frameo
   has no overlay permission. It is HOME again this boot, so it resumes whenever our activity finishes or dies
   (expected). On API 23 nothing stops it starting an activity from the background, and it has a
   `StandbyBroadcastReceiver` alarm at 23:00. It also owns `SCREEN_BRIGHTNESS` (WRITE_SETTINGS) and resets the media
   volume at its start. Mitigations:
   - `onStop` during Focus without a user Exit: log `EVENT|focus|lost-front`. Don't fight back mid-focus. The
     deadline's `REORDER_TO_FRONT` brings us back.
   - `DisplayPower.restore` compare-and-set: restore only if sysfs still holds what we wrote. That way a Frameo
     standby that turned the screen off isn't undone.
   - M3/M6: watch `logcat -b events | grep -i frameo` around 23:00 (no lock) with a Deep session running.
3. **The hot-corner dot stays visible in Calm and vanishes in Blackout.** Its 56 px ring (3 px, `#AAAAAA` on
   black, luminance ≈ 0.40) is 3–4× brighter than the Calm content at 12 %. It is a `TYPE_PHONE` overlay above
   us, so Pomodoro can't hide it and must not edit 02-launcher.
   - Keep Calm's arc and digits far from the bottom-left so the ring stays peripheral.
   - Use a low window `screenBrightness` in Calm, which dims the ring with everything else (one backlight).
   - Blackout hides it.
   - Optional follow-up for the launcher owner (coordinator's call): a "dim dot" request, e.g. a broadcast, so
     focus apps can fade it.
4. **Backlight:** `/sys/class/backlight/rk28_bl/brightness` is 0–255, now 187. `Settings.System.SCREEN_BRIGHTNESS`
   is 187 (manual mode, setting min 30). The file is owned by `system`, so PowerManager rewrites it whenever any
   brightness changes. A 0 held for 3 s with nothing writing it back, and a single-command `su -c` works. Keep
   the 05-hub rules: no window brightness change while off; restore on stop/Exit/crash/start. Restore the level
   read before going dark, falling back to the Settings value.
5. **Cues: media volume is 2/15 (≈ −47 dB on AOSP curves) and Frameo resets it; alarm is 6/7 (≈ −7 dB).** A −24 dBFS
   chime on `USAGE_MEDIA` would be close to inaudible. Proposal for M2:
   - Play cues with `USAGE_ALARM` (still never touch system volume) and an in-app cue gain, so "−24 dB" is
     relative to the alarm stream.
   - Pick soundscape usage by ear.
   - Loudness can't be measured on the frame (the mic is dead), so the plan's "measure cue loudness" becomes an
     ear check with the user in M2.
6. **Accelerometer:** the HAL sensor is named "Gravity sensor" (AOSP vendor) but is `TYPE_ACCELEROMETER`, handle 0,
   max 200 Hz. It delivers about 100 Hz, and rest noise is about ±0.45 m/s² on z, so the 1.2 m/s² knock threshold
   has about 2.7× margin. Use `getDefaultSensor(TYPE_ACCELEROMETER)` only: there is a second type-1 virtual sensor
   ("Gyroscope Bias (debug)"), and all fusion sensors depend on the dead gyro.
7. **Time:** the clock is NTP-synced now (auto_time on, Europe/Amsterdam).
   - At boot the clock reads 01-01 01:00 (kernel), then **2021-05-15 02:00** (Android), then the real time
     about 36 s after boot.
   - `TimeGate` must not trust "a plausible year" loosely. Trust when the time is ≥ `Build.TIME` (2026-03-07),
     or after an `ACTION_TIME_CHANGED` since boot.
   - `bootId` comes from `/proc/sys/kernel/random/boot_id`, which is readable without root.
8. Tile order 60 is free (only mawaqit uses 40). The scaffold manifest already has 60 and `#E5735C`.

## Scaffold + M1 core

**Build:** Gradle mirrors 05-hub: same wrapper (9.8.1), catalog minus okhttp/biweekly/qrcodegen,
`compose_stability.conf`, `tools/api23_guard.py` + `api23_allow.txt`, and `api23Guard` wired into `check`.
`gradle.properties` adds `kotlin.compiler.execution.strategy=in-process`. `:core` adds `explicitApi()` to the hub's
setup. `:app` is a placeholder: `MainActivity` shows "Pomodoro" and an Exit control. The manifest is final:
- singleTask, full configChanges, `screenOrientation=sensor`
- tile meta-data: order 60, `#E5735C`, "Focus timer"
- a vector `ic_launcher`
- lint ignores, inline in the manifest, for DiscouragedApi (orientation) and DataExtractionRules

`libs.datastore` is in the catalog but not yet in `app/build.gradle.kts`. M2 adds it.

Green: `./gradlew :core:test assembleRelease api23Guard :app:lintRelease` (118 core tests).
- The placeholder APK doesn't reference core, so R8 strips it.
- I ran the guard once with a temporary `-keep class dev.framio.pomodoro.core.** { *; }`: 2808 refs, OK. Then I
  removed the rule.

### Public API map (`core/src/main/kotlin/dev/framio/pomodoro/core/…`)
- `model/Ids.kt`: `ModeId`, `SessionId`, `TaskId` value classes.
- `model/Mode.kt`:
  - `Mode(id, name, accent 0..7, rhythm, ritual, environment, builtIn)` with `.duplicate()`
  - `Rhythm.Fixed` / `Rhythm.Flow`, both with `autoBreak` and `pauseCap`
  - `Tier`, `SmitsTiers`
  - `Ritual(intention: IntentionRule, ifThen, checklist, showResumeNote)`
  - `Environment(display, face, showSeconds, touch, knockToPeek, cues, soundscape?, checkIn?, sunrise, tone)`
  - enums `Display`, `Face`, `CueStyle` (Off/Soft/Rising), `Soundscape`, `CopyTone`
  - `TouchPolicy.Open` / `HoldToReveal(1200 ms)`
- `model/Presets.kt`: `classic`, `deep`, `flow`, `gentle` exactly per §4. Ids `classic|deep|flow|gentle`.
  Checklist ids are `phone`, `water`, `one_window`, so the app can map them to string resources.
- `model/Settings.kt`:
  - `Settings(theme, accent?, dailyTarget=6, eveningStart 20:30, reduceMotion, soundVolume, modes, selectedMode=deep)`
  - `.normalized()` restores the presets and repairs ranges. `MAX_MODES = 8`.
- `model/ModeRules.kt`: `problem(mode): String?` gives the mode editor's limits.
- `model/Task.kt`: `Task(id, title, estimateSessions?, lastUsedWallMs?, useCount)`.
- `model/Parked.kt`: `ParkedThought(ink, at)`, plus `Stroke(points)` and `InkCodec.encode/decode`. Ink is a compact
  text form of strokes (base-32 zigzag deltas on a 1023 grid).
- `model/SessionRecord.kt`:
  - `SessionRecord` is one JSONL line: ids, mode, task, intention, planned?, focused, outcome, rating?,
    resumeNote?, parkedCount, `start: Moment`
  - `Outcome`: Done/Partly/NotYet/**Unrated**/EndedEarly/Interrupted. `.completed` is false only for the last two.
- `time/Moment.kt`:
  - `Moment(bootId, elapsedMs, wallMs?)`, with `typealias Deadline = Moment`
  - `Now(bootId, elapsedMs, wallMs?, zone)` with `.until(m)` / `.since(m)` (null means unknowable) and `.date`
  - `Deadline.remaining(now)`, `Moment.rebased(now)`, `durationUntil`, `isLost`
- `time/BootClock.kt`:
  - `BootClock` gives `bootId` and `elapsedMs()`
  - `EngineClock` is a `fun interface` with `now()`
  - `GatedClock(boot, gate, zone)` is the production clock
- `time/TimeGate.kt`: `TimeGate(notBefore)`. Pass `Build.TIME` (M0 §7). Methods: `offer(wallMs, elapsedMs)`,
  `wallAt(elapsedMs)`, `anchor: StateFlow`.
- `rhythm/`:
  - `BreakPolicy.after(rhythm, cycle, focused) → BreakPlan(kind, length)`
  - `BreakPolicy.autoFocus(rhythm)`
  - `FixedRhythm.isLong/breakAfter`
  - `FlowRhythm.breakFor/nudgeDue`
  - `BreakKind`
- `engine/Phase.kt`:
  - `Phase.Idle`, `Ritual(modeId)`, `Focus(session, deadline?, startedAt)`
  - `Paused(session, remaining?, elapsed, since, capAt?, overCap)`
  - `WrapUp(session, next, breakFor)`, `Break(kind, deadline, length, prompt, pendingWrapUp)`, `Ready`
  - `BreakPrompt` (Move/Window/Water/Eyes, plus Messages for long breaks)
- `engine/FocusState.kt`:
  - `FocusState(loaded, settings, phase, run?, session?, today?, completedToday, lastResumeNote?, tasks, notice?)`
    with `.mode` and `.snapshot()`
  - `Run(mode copy, intent, cycle)`, `Intent`, `ActiveSession`, `Notice.Interrupted`
  - `Snapshot` is the persisted form: everything except settings, plus `savedAt`
- `engine/Input.kt`:
  - Lifecycle: `Restore(snapshot?, settings)`, `TimeTrusted`, `DeadlineReached`
  - Before focus: `SelectMode`, `OpenRitual`, `CancelRitual`, `Begin(intention?, ifThen?, taskId?, focusOverride?)`
  - During focus: `Pause`, `Resume`, `EndEarly(note?)`, `Complete` (Flow), `Extend(by)`, `Park(ink)`
  - After focus: `WrapUp(outcome?, rating?, note?)`, `SkipBreak`, `StartNext`, `Home`, `DismissNotice`
  - Tasks: `AddTask`, `SetEstimate`, `RemoveTask`
  - Settings: `ChangeSettings`, `SaveMode`, `DeleteMode`
- `engine/Effect.kt`:
  - `ScheduleWake` / `CancelWake` (informational), `PlayCue(kind, style)`, `SetDisplay(level, ramp)`
  - `StartService` / `StopService`, `SetSoundscape(s?)`, `AppendLog(record)`, `Persist(snapshot)`
  - `SaveSettings`, `BringToFront`
  - `DisplayLevel` (Normal/Calm/Blackout/Dim), `CueKind`, and `Next(state, effects, rejected?)`
- `engine/Reduce.kt`: `reduce(state, input, now, config)`. It is pure. A rejected input returns the *same* state,
  no effects, and a reason.
- `engine/Derive.kt`:
  - `wake(state)`, `serviceActive`, `display`, `soundscape`, `SUNRISE = 10 s`
  - Reduce adds these effects whenever their value changes. After a Restore it emits all of them as a sync.
- `engine/FocusEngine.kt`: `FocusEngine(clock, effects: EffectHandler, scope, dispatcher, config, log)` with
  `state`, `dispatch`, and `suspend submit`. The scheduler coroutine delays to `Derive.wake` (at most 5 min).
- `engine/EngineConfig.kt`: `EngineConfig(timeScale)`, with `MinutesAsSeconds` = 1/60. Deadlines are real time; the
  session and log are logical.
- `engine/Timing.kt`: `left` / `focused` / `progress(state, now, config)` for the face. All logical.
- `engine/CoreJson.kt`: `encode/decodeSettings` and `encode/decodeSnapshot`. Decode returns null on garbage and
  tolerates unknown keys and unknown enum values.
- `log/SessionLog.kt`: `encode(record)` gives one line. `decode(text) → Decoded(records, skipped)`: it handles a torn
  last line, blanks, CRLF and unknown fields, and keeps the last record for a duplicate id.
- `stats/`:
  - `Insights.today` → `TodayProgress`; `Insights.week` → 7 `DayTotal`s
  - `Consistency.activeDays` ("N of 7")
  - `EstimateAccuracy.perTask`
  - `HourHeatmap.build` gives null below 20 rated sessions; `bestHour`
- `audio/`:
  - `Pcm` holds the 44.1 kHz constants and dB/peak/rms helpers
  - `ChimeSynth.render(peakDb)` and `.rising()`, which rises from −24 to −12 dBFS in three chimes over 8 s
  - `NoiseSynth(NoiseColor.Pink|Brown, seed)` and `RainSynth(seed)` are streaming generators with
    `fill(buf, off, len)`, `fadeOut(d)` and `isSilent`
- `gesture/KnockDetector.kt`: `KnockDetector(KnockConfig()).add(tMs, x, y, z): Knock?` returns Single or Double.
  `boost` raises the threshold while our speaker plays; `reset()`.
- `testFixtures/`:
  - `FakeClock` (advance/reboot/trust) and `VirtualClock(testScheduler)` (reboot)
  - `Driver`, which runs reduce without coroutines and records effects
  - `RecordingEffects`, `record(…)`, `wallAt`, `AMSTERDAM`

### Loop semantics and deviations from PLAN §6.1
- **Auto-break vs wrap-up.**
  - Classic, Flow and Gentle (`autoBreak`): at the deadline the Break starts at once, with `pendingWrapUp = true`.
    The wrap-up is a card on the break screen.
  - Deep: the deadline goes to `Phase.WrapUp`, which has no timer. `WrapUp(...)` then starts the break.
  - An unanswered wrap-up is logged `Unrated` when the break ends, is skipped, or on Home.
  - `EndEarly` logs at once and goes to Idle. It doesn't count toward the daily target.
- **Pausing.** Only Focus can be paused (no `resumeTo`).
  - `Focus.startedAt` is the *effective* start, shifted by pauses.
  - The pause cap is `Rhythm.pauseCap` (Deep: 10 min). When it runs out, `Paused.overCap` is set, a `PauseCap`
    cue plays, and the app offers End.
  - Flow's `nudged` flag lives on `ActiveSession`.
- **Sunrise ramp** happens only when a Calm or Blackout block *reaches its end* (→ WrapUp or Break). A pause, End
  or Home lights up at once.
- **Restore.**
  - A deadline that passed while the app was dead ends the block *at the deadline*, and the break starts now.
  - A break that expired while dead goes to Ready, never auto-starts focus.
  - A deadline lost across an untrusted reboot → Idle, plus an `Interrupted` log line (focused time =
    `savedAt − startedAt`) and `Notice.Interrupted`.
  - A Paused block survives a reboot. A lost cap counts as reached.
- **The run keeps a copy of its Mode**, so editing or deleting a mode never disturbs a session. Tasks live in the
  engine snapshot, not in Settings. `Begin(intention)` finds a task by title (ignoring case) or creates it.
- **Knock detector.**
  - Samples over the threshold less than 90 ms apart are one knock, so a slammed door's long ring stays one
    knock.
  - A pattern is reported once it has been quiet for 650 ms. So a Single arrives about 650 ms late, and a third
    knock spoils a Double.
  - The brief's "refractory 350 ms" applies **after a reported Double**. Applied per knock, it would raise the
    double-knock minimum gap from 120 to 350 ms.
- **Not built:** there is no WindSynth (`Soundscape.Wind` exists; M5) and no SNTP. TimeGate trusts the system clock
  only from `Build.TIME` on.

### For M2
- The engine does nothing until `Restore`. Load the settings and snapshot (DataStore), then
  `dispatch(Input.Restore(snapshot, settings))`.
- Pass `Dispatchers.Default`; the engine applies `limitedParallelism(1)` itself.
- `EffectHandler.handle` runs on the engine's thread. Hand file I/O off to IO:
  - `Persist` is conflated
  - `AppendLog` is fsynced
  - `SaveSettings`
- `AppendLog` comes before `Persist` in the same effect list, so after a crash a duplicate log line is possible.
  `SessionLog.decode` dedupes.
- Clock: `GatedClock(bootClock(/proc/sys/kernel/random/boot_id + elapsedRealtime), TimeGate(Build.TIME), ZoneId::systemDefault)`.
  Call `gate.offer(System.currentTimeMillis(), elapsedRealtime())` at start and on `ACTION_TIME_CHANGED`. Dispatch
  `Input.TimeTrusted` when that returns true. Dispatch `DeadlineReached` on `TIME_CHANGED` too.
- adb hooks:
  - `--ei min N` → `Begin(focusOverride = N.minutes)`
  - debug "minutes as seconds" → `FocusEngine(config = EngineConfig.MinutesAsSeconds)`
  - for `--ei sec N`: either `focusOverride = N.seconds`, or scale the whole engine. Both work.
- Cues: per M0 §5, play `ChimeSynth.rising()` / `render()` on USAGE_ALARM with an in-app gain. The −24 dB in the PCM
  is relative to that.

## M2 — app skeleton + Classic end-to-end

Evidence: LOG.md "M2". Full check green (core 118, app 49 tests). Release APK 1.65 MB, PSS 19–21 MB.

### Structure (`app/src/main/kotlin/dev/framio/pomodoro/…`)
- `PomodoroApp` (container + CrashGuard), `MainActivity` (window things + adb extras + selftest).
- `di/`: `AppContainer` (lazy graph, app `SupervisorJob` scope, `AppDispatchers`, `GatedClock(AndroidBootClock, TimeGate(Build.TIME))`,
  `start()` = time offer + TIME_CHANGED receiver + log writer + `recoverAtStart` + load stores → `Restore`), `EnginePort` (what
  ViewModels see: state, config, now, send) + `EngineAdapter`, `DebugTime` (prefs flag).
- `data/`: `JsonStore<T>` (DataStore + JSON serializer, corrupt file copied to `<name>.bad-<ms>.json`, conflated `offer`, `write`),
  `SettingsStore` / `SnapshotStore` (files `files/datastore/settings.json`, `snapshot.json`), `SessionLogFile`
  (`files/sessions.jsonl`, fsync, newline repair after a torn line). Appends go through one channel consumer (order kept).
- `platform/`: `Logs` (tag `Pomodoro`), `Time.kt` (`AndroidBootClock`, `TimeWatcher`), `RootShell` (blocking `su -c`, implements
  `Backlight`), `DisplayPower` (+ `DarkMarker`/`PrefsDarkMarker`, `DisplayController`, `WindowLevels`), `SoundEngine`,
  `FocusService` (+ `ServicePolicy`, `ServiceController`), `EffectRouter` (+ `EffectSinks`, `Presence`), `CrashGuard`.
- `ui/`: `Root.kt` (`Screen`, `screenOf`, `RootViewModel`, `PomodoroRoot` with the AnimatedContent), `Fit.kt` (`FitToDesign`,
  `LocalLandscape`), `Ticker.kt` (`rememberNow`), `UiState.kt` (`stateForUi`, `FaceTiming`), `Format.kt`; `theme/` (`QuietColors.kt`
  palettes + accents, `QuietTheme.kt` type/spacing/motion + `QuietTheme` object/composable); `components/` (`QuietText`,
  `QuietButton`, `QuietTextButton`, `Chip`, `TodayProgress`); `faces/RingFace.kt` (`RingFace`, `TargetRing`, `ProgressLine`);
  one package per screen with `XViewModel` + `XRoute`/`XScreen`: `home`, `focus`, `breaks`, `ready`, `wrapup` (+ shared
  `WrapUpCard`), `settings`.
- Tests (`app/src/test/…`): `FakePort` (real reducer on `FakeClock`), `ViewModelTest`, `RootFlowTest` (Robolectric sdk 23, real
  root), `ScreenshotTest` (Roborazzi, 11 goldens in `app/src/test/screenshots`), `ThemeContrastTest`, `FormatTest`,
  `SettingsStoreTest`, `SessionLogFileTest`, `ServicePolicyTest` (+ router), `DisplayPowerTest`.

### Decisions
- **Cues:** USAGE_ALARM (M0). Soft chime peak −18 dBFS, one-note nudge −24 dBFS, Deep's rising −24 → −12 dBFS, all times the
  in-app volume = `Settings.soundVolume` (default 0.6, Settings → Cue volume in 10 % steps, with Play). Never a system volume.
  **The user must ear-check this** (LOG M2, last row). Interrupted cues fade out in 120 ms.
- **Keep-screen-on:** while `Derive.serviceActive` (Focus, Paused, WrapUp, Break, Ready); off on Home/Ritual.
- **Window brightness:** Normal = system (−1), Dim (break) = 0.3, Calm = 0.06 (placeholder, tune in M3). Ramps step every 100 ms.
  Blackout goes through `DisplayPower` (root) and the window is never touched while dark; `onStop` restores the backlight,
  `onStart` re-applies the phase's level.
- **DisplayPower rules (M0):** restore only if sysfs still reads what we wrote; `recoverAtStart` only acts on our own
  `DarkMarker` (written with `commit()` *before* going dark), so a Frameo standby stays dark; the crash path uses the same rule.
- **Service:** `ServicePolicy.onEffect(effect, running)` → Start/Stop/None; `bringToFront(onScreen)`. Started with
  `ContextCompat.startForegroundService`, `ServiceCompat.startForeground(…, SPECIAL_USE on 34+)`, START_NOT_STICKY, IMPORTANCE_MIN.
- **adb:** `--es cmd start|pause|resume|end|skip|done|home|peek|selftest`, `--es mode`, `--es task` (quote twice through
  adb: `"'two words'"`), `--ei min N`; debug-only `--ei sec N`: sets the "minutes as seconds" flag (read when the engine is
  built, so it applies to a fresh process: force-stop first; it persists across kills; `--ei sec 0` clears it) and makes
  `start` an N-"minute" (= N s) block. Release ignores it (`BuildConfig.DEBUG` is constant false, R8 drops the code).
  Extras are handled once (`replaceExtras`) and wait for `loaded`.
- **Screens:** Ritual maps to Home until M3 (Begin works from Ritual too); WrapUp (Deep) has a minimal full-screen card.
  Portrait Home shows the modes as a 2 × 2 grid (four in a row truncate at 800 px). Settings is an overlay over Home only.
- **Transitions:** our own cross-fade (`transition.animateFloat` + `graphicsLayer { alpha; ModulateAlpha }`), not
  AnimatedContent's fadeIn/fadeOut: those use offscreen layers and made every transition frame GPU-bound (LOG).
- **Accents:** 8 hues with a light tone for dark themes and a deep tone for Paper (one colour can't do 4.5:1 on both);
  `ThemeContrastTest` checks every accent × theme × background/surface, and the filled-button label.
- **Goldens render at SDK 26** (Robolectric native graphics starts there; at 23 only wireframes). Behaviour tests run at 23.

### Deviations / traps
- `tools/api23_guard.py` now attributes each reference by its instruction's line (R8 mapping ranges, per overload; lambda-group
  glue → the case after it), and `app/proguard-rules.pro` keeps our, coroutines' and serialization's classes from cross-library
  merging. Without both, the guard blamed `SettingsStore$1` for androidx's `RemoteAction`. It still catches a real API 33
  call (verified). If you copy the guard elsewhere, copy the new version.
- `QuietColors.kt` is separate from the font-loading `QuietTheme.kt` on purpose: top-level `Typeface.create` in the same file
  class breaks plain-JVM tests (returns null with `isReturnDefaultValues`).
- `clearAndSetSemantics` on the digits means tests find the timer by content description ("25 minutes left in focus"), not text.
- Robolectric at sdk 23 measures text as nearly zero-width: assert on semantics, not sizes.
- DataStore allows one active store per file per process: tests cancel the scope between instances.
- `am force-stop` on the frame brings whichever app is next (often another session's) forward; it may set its own brightness.
- The debug APK takes ~60 s to install (dex2oat of 14.6 MB): use the release APK for device bursts when time scaling isn't needed.
- Settings changes read `state.value.settings` then send `ChangeSettings`: two taps faster than the engine applies them
  can collapse into one step (harmless at this rate; an `Input.Adjust…` in core would fix it).

### Measured vs budgets (release)
PSS 19.2 MB Home / 20.8 MB Focus (≤ 40 ✓). Idle Focus: 1 frame/s, 12 ms (✓ "draw nothing while nothing changes").
Cold start 1.17–1.32 s (✗ ≤ 0.9 s). Begin transition 35 % janky, 90th 38 ms (✗ < 3 %). APK 1.65 MB (✓).

### M3 checklist
- Ritual screen (`Phase.Ritual` currently renders Home): intention required for Deep, if-then, checklist (ids `phone`, `water`,
  `one_window`), last resume note; skip-after-first-run memory.
- Calm and Blackout: `WindowLevels.CALM` is a placeholder; Blackout via `DisplayController.set(Blackout)` → `DisplayPower.off()`
  already wired (untested on device). Re-apply on `onStart` exists. Verify restore on stop/Exit/crash/start on the device,
  and the 23:00 Frameo standby interplay (M0 §2).
- Calm face: 1/60 Hz ticker is already `rememberNow(…, 1.minutes, anchor)` when `showSeconds = false`; the Calm screen itself
  (dim arc + "38 min", away from the bottom-left dot) is new. Gfxinfo: no frames between minutes.
- Hold-to-reveal panel (1.2 s), Park pad (InkCodec), resume-note prompt on End/Pause (FocusViewModel.end sends `EndEarly()`
  without a note today), sunrise ramp (MainActivity ramps the window; for Blackout, ramp the backlight via DisplayPower).
- Knock peek: `SoundEngine.playing` exists for `KnockDetector.boost`; register the accelerometer only in Deep focus.
- Pre-render cue PCM at start (first cue is 0.6 s late) or on Begin.
- Begin "breathe in" and the first-composition cost of Focus (58 ms frame): consider composing the Focus screen invisibly on
  Home, or simplifying its first frame; both M3/M6.

## M3 — Deep mode

Evidence: LOG.md "M3". Full check green (core 131, app 97 tests; 16 new goldens). Release APK 1.74 MB; PSS 24.8 MB Calm,
26.2 MB Blackout; Calm idle draws only on minute ticks.

### Core additions (no change to `SessionRecord` or `ParkedThought`)
- `Ritual.skippable` (default true) and `Ritual.hasScreen` (if-then or checklist). Classic and Gentle have no ritual screen.
- `RitualMemory(mode, ticks, ifThen, runs, skip, display)` in `FocusState.rituals` / `Snapshot.rituals`. It is written by
  `Begin(checked = …)` (only the mode's own ids, in order), so a Begin not coming from the ritual (adb, Home, Ready) leaves
  it alone. `FocusState.skipsRitual(mode)`.
- Inputs: `OpenRitual(intention?)` (now a data class: Home's chosen task is preselected, carried on `Phase.Ritual.intention`),
  `SetRitualSkip(mode, skip)` (on only after one run), `Begin.checked`, `Begin.display` (Calm ↔ Blackout for this run: the
  run's mode copy changes, the preset doesn't; a Full mode stays Full), `ClearParked(index?)`.
- `FocusState.parkedLot` / `Snapshot.parkedLot`: when a block is dropped (end of break, Home, EndEarly, interrupt, expired
  break on restore) its uncleared parked thoughts are kept there (≤ 30). `ClearParked` acts on the block's list from Focus to
  Break, else on the lot.

### App
- `platform/DisplayPower`: `owns`, `ramp(level, over)` (Blackout peek: stepped root writes, marker updated every step,
  compare-and-set first) and `sunrise(over)` (to the day level, then lets go). Steps are ≥ 500 ms apart: a root write takes
  84–188 ms. `DisplayController.light(on)`: lifts Calm (window → `WindowLevels.PEEK` 0.3) or Blackout (backlight → 64) for the
  peek, panel, pad and end prompt; it is ignored for other levels (a sunrise isn't restarted). `onLeftScreen` restores whenever
  we own the backlight (dark or lifted). `WindowLevels.CALM = 0.12`: Android clamps window brightness to the setting minimum
  30/255, so lower values were the same.
- `platform/Motion`: accelerometer on its own HandlerThread at SENSOR_DELAY_GAME → core `KnockDetector` (threshold +1.5 while
  our speaker plays) → `doubles`. Debug `injectDoubleKnock()` feeds a synthetic trace through the same detector.
- `di/DeepHardware` (knocks, listen, light) with `AndroidDeepHardware` (adb `peek` = a peek without the detector; debug
  `knock` waits up to 3 s for the sensor: an adb intent pauses/resumes the activity, which unregisters it) and `None`.
- `SoundEngine.prepare()` renders all cue PCM 4 s after StartService (not during the breath): the first cue is 69 ms late,
  not 0.6 s.
- UI: `ritual/` (RitualViewModel seeds its edits from the engine whenever a new `Phase.Ritual` appears), `deep/` (DeepFocus
  screen + VM, `holdToReveal` modifier, `ParkPad` + `InkBuffer`, `PanelAction`), `wrapup/` rewritten for Deep (outcome,
  focus 1–5, next step, parked thoughts with ×; leaving by Start break, Skip break or Home logs what was answered),
  `components/` (`QuietField`, `CheckToggle`, `InkThumb`, `ResumeNotePrompt`, `TaskDraft` shared by Home and the ritual).
  Classic's End also asks for the resume note now. The break shows the block's parked thoughts.
- Root: `Screen.Ritual`, `Screen.Deep` (Focus/Paused of a Calm or Blackout mode); a "night" layer (Calm near-black or black)
  above the theme background gives "the background deepens"; screens are transparent (only the root paints a background).
  adb: `--es cmd ritual`, `--es display calm|blackout` (start), `peek`, debug `knock`.

### Decisions / deviations from the plan
- **Blackout is "one toggle away" on the ritual screen** ("Calm screen | Screen off"), remembered per mode, because presets
  are read-only and the mode editor is M5. Skipping the ritual still applies the remembered if-then line and screen.
- **"Skippable after the first run"** = a "Skip this screen next time" toggle that appears on the ritual after one run; Home
  then begins directly and shows a "Ritual" text button to open it on purpose.
- **Peek in Blackout lights to 64/255**, not the day level: reading the time across a desk at night shouldn't flash the room.
- **Pause and End light up at once** (no sunrise), as M1 decided; the end-note prompt is shown for Classic too (principle 4).
- **Check-in glow** (`Environment.checkIn`, off by default): a 3 s alpha glow of the Calm face; not in Blackout (it would need
  the backlight).
- **Calm's arc moves in whole minutes** (`FaceTiming.minuteProgress`) with the 600 ms ease of PLAN §5.2 per tick.
- Hold-panel actions are one list (`PanelAction.Shown`) so the history work can add "Log a distraction" there.

### Traps
- Compose `graphicsLayer` on this HWUI: a layer with alpha < 1 and the default strategy is drawn through a saveLayer (a
  full-screen FBO every frame for a full-screen rect); an `Offscreen` layer whose children change is re-rendered every frame.
  Use `ModulateAlpha` for single things that fade; `Offscreen` only for content that is static while it fades (the outgoing
  screen).
- Anything that redraws a stroked arc with a new sweep rebuilds its path (`tessellatePath`) on the RenderThread: never let a
  re-read clock nudge an arc.
- `dumpsys power` takes seconds on the frame: device scripts that poll it drift; log from the app instead
  (`EVENT|window|brightness a → b over t`).
- The Robolectric press test needs `mainClock.autoAdvance = false` around down/up, or the 1.2 s timeout fires early.
- `Input.OpenRitual` is a class now (`OpenRitual()`).

### Measured vs budgets
Begin "breathe in" (3 s): Classic 15 % janky (M2 35 %), Deep 34 %: **target < 5 % not met**. All of Deep's jank is in the
first ~0.9 s after Begin (UI thread late 10–17 ms per frame with 3 concurrent GCs and the service start); 1–3 s is smooth.
Calm idle: 70 frames in 120 s (2 ticks), 0 % CPU. PSS ≤ 27 MB.

### M4 checklist
- Break landscape (M4) must follow the same layer rules (ModulateAlpha, no animated arcs/paths per frame).
- Begin jank (M6, or M4 if the landscape work touches Root): profile allocations in the first second (GC), start the
  FocusService after the first frame, consider skipping the outgoing-screen composition work (the ritual VM still emits
  after Begin).
- Wrap-up already logs; M4's Flow wrap-up and Gentle "another 10?" can reuse `WrapUpScreen` (Classic still answers on the
  break card).
- The parked-thoughts lot is only shown on the ritual (Clear all); a review place elsewhere (Home/Insights) is open.
- Frameo 23:00 standby with a Deep session running: still unobserved (logcat only, no lock).
- The ear check of the rising cue (USAGE_ALARM, −24 → −12 dBFS × 0.6) is still pending with the user.

## History + notes — Phase A (design + core, no app changes)

Design: `DESIGN-history.md` (data model, taxonomy, capture flows with tap counts, History/Insights mock-ups, export,
retention and performance). Code: new packages only, `core/…/notes/` (`Taxonomy`, `Entry`, `Journal`) and
`core/…/history/` (`History`, `DayBucket`, `InsightsReport`, `Export`, `StatsCache`). No existing core file was changed.
Tests: 68 new (core total 207 at the time, all green). Highlights:
- Notes go in a second append-only file, `history.jsonl` (`JournalCodec`, `"v":1` lines: put/del/amend/drop/restore).
  `sessions.jsonl` is never rewritten. Unknown/future lines are kept verbatim through compaction.
- `HistoryIndex.build(records, journal, zone)` joins the two. An old record's `resumeNote` becomes an editable
  `ResumeNote` with id `rn:<session>`; its `parked` count becomes `legacyParked`.
- `StatsIndex` keeps `DayBucket`s incrementally (`refresh(index, dates)`); `InsightsReport.build` applies every honesty
  rule (n ≥ 20 rated for the hour heatmap, ≥ 3 per cell, ≥ 5 h for a rate, ≥ 5 Flow blocks, trend thresholds).
- ScaleTest (5k sessions, 20k entries, desktop JVM): decode 93 ms, join + stats + report 47 ms, one-day refresh 15 µs.
- Avoided `Map.merge` (API 24) in core; `api23Guard` will confirm once the app references this code.

### History/notes — pending core changes
**Applied in Phase B** (see "History/notes — Phase B"). Apply these after M3. Each one is additive (defaults keep old JSON and old call sites working).

- **P1 Mirror parked thoughts to the journal (one writer stays the engine).**
  - `engine/Effect.kt`: add
    `public data class ThoughtParked(val session: SessionId, val thought: ParkedThought, val at: Duration) : Effect` and
    `public data class ThoughtsCleared(val thoughts: List<ParkedThought>) : Effect`.
  - `engine/Reduce.kt`:
    - `park()` returns `Step(newState, listOf(Effect.ThoughtParked(session.id, thought, Timing.focused(s, now, config) ?: ZERO)))`.
    - `clearParked()` returns `Effect.ThoughtsCleared(<the removed items>)`. With `index == null`, that is all of `list`.
  - App: the `EffectRouter` maps them to `JournalOp.Put(Entry.Parked(EntryId.parked(thought.at), session, at, thought.at.wallMs, NoteBody.ink(thought.ink)))`,
    and to a re-put with `resolved = true` for every cleared thought (looked up by `EntryId.parked(it.at)`).
  - Tests: one `ReduceTableTest` row each, and the effect order (after `Persist` is fine).
- **P2 Handwritten resume notes.** Today the resume note is text-only end to end. Add `resumeInk: String? = null` to:
  - `Input.EndEarly` and `Input.WrapUp`
  - `FocusState` + `Snapshot` as `lastResumeInk` (`@SerialName("lastResumeInk")`), set and cleared exactly like
    `lastResumeNote` (Reduce lines ~322 and ~421; Restore ~101)
  - `SessionRecord` as `@SerialName("resumeInk") val resumeInk: String? = null`, filled where `resumeNote` is
    (`Reduce` ~473)
  - Then, in `history/History.kt` (`HistoryIndex.build`): build the legacy body as `NoteBody(note, raw.resumeInk)`, and
    synthesize the entry when either is present.
- **P3 (nice to have) Entry hook for live distraction time.** None is needed: the app uses
  `Timing.focused(state, now, config)` and `state.session.id`.
  - Before Phase B, check that `state.session` is still set in `Phase.WrapUp` and in a Classic `Break` with
    `pendingWrapUp`. It is today; Reduce keeps it until `dropSession`.
  - Keep it that way, or wrap-up reasons have no session id.
- **P4 Cleanup after Phase B (optional):** `stats/Insights.today`, `week`, `Consistency` and `HourHeatmap.build` become
  unused when the Insights screen moves to `history.InsightsReport`. Keep `TodayProgress`, `DayTotal`, `HourCell`,
  `TaskEstimate` and `HourHeatmap.MIN_RATED`, which `InsightsReport` reuses.

### Traps for Phase B
- `HistoryIndex` and `StatsIndex` are single-writer. Build them on `Dispatchers.IO.limitedParallelism(1)` in a
  `HistoryRepository`, and publish immutable snapshots.
- `Entry.at` is **logical** focus time (`Timing.focused`), not wall time. Under the debug "minutes as seconds" flag, a
  distraction 10 s in is "10 min".
- Value classes can't be `vararg` parameters (Kotlin rule). That is why the test fixtures take `a, b?, c?`.
- `DayBucket.of` walks sessions oldest-first so the latest task and mode titles win: keep `on(date)` newest-first.

## M4 — breaks, wrap-up polish, Flow, Gentle start

Evidence: LOG.md "M4". Full check green: core 207 tests, app 130 tests (24 new goldens in `M4ScreenshotTest`, 8 older
goldens re-recorded on purpose: break ×3, ready ×2, ritual ×3). Release APK 1.78 MB, api23Guard OK.

### Built
- **core**: `Input.ExtendBreak(by = 5 min)` (Break only, ≤ 30 min a press, break ≤ 90 min); `time/DayPart` (Dawn 5–8,
  Day, Dusk = 3 h before `Settings.eveningStart`, Night; untrusted clock → Day). **Flow's nudge is silent now** (brief:
  "no alarm"): the engine only sets `session.nudged`; `CueKind.Nudge` is unused. Tests: `BreakTest`, `DayPartTest`,
  table row for ExtendBreak, nudge tests updated.
- **faces/Faces.kt**: `TimerFace(face, progress, …)`, `DiscFace` (wedge + ticks), `HorizonFace` (sun sinking, clipped at
  the line, static water strokes); Digits = `QuietType.timerHuge` (260 sp). FocusScreen picks the layout per face.
- **why/Why.kt**: `Strength`, `WhyTopic` (one sentence, strength, sources, honest note; citations only from PLAN §3),
  `Why` groups (Break, LongBreak, Ritual, Display, Tiers), `InfoButton`, `WhySheet`. (i) on the break prompt, Flow's
  tier line, Flow's controls, the ritual checklist and the Calm/Screen-off chips (the "Deep display").
- **breaks/**: `Landscape` + `landscapePalette(DayPart, QuietColors)` (dark themes 25 % toward their background, Paper
  45 %: a pale wash); `BreakScreen` from public sections (`BreakPanel`, `BreakHeader`, `BreakPromptSection`,
  `FlowTierLine`, `BreakTimeSection`, `ParkedSection`, `BreakButtons`) + `BreakActions`; +5 min; `FlowTier`.
- **wrapup/WrapUpCard**: `content` slot between the answers and Skip, `OutcomeChips` shared with WrapUpScreen. Slot
  "Log a distraction" / reason chips in there (and as one more section in BreakScreen's panel). No reason system here.
- **flow/**: `Screen.Flow` for any Flow-rhythm block (Root). Calm: elapsed minutes only (once a minute), tap → "Stop at a
  natural point: N minute break" (i) + Pause + Stop here (lifts a dark display like Deep's panel); Blackout draws
  nothing until a tap; nudge = one 3.6 s glow when it arrives on screen + "90 minutes in. A real break helps." stays.
- **ready/**: Gentle (tone Warm): "Ten minutes done. That counts." / "Another 10? Stopping here is fine too." /
  Another 10 / Done for now.
- **platform**: `ServiceController` starts the FGS 3.5 s after StartService (stop cancels; `isWanted` for selftest);
  `DisplayController` ramps into Dim over 1.5 s; `MainActivity` drops the window background; Root skips its own
  background while the Break is settled. adb: `--es cmd stop` (Flow Complete), `--es cmd extend`.

### Decisions / deviations
- Nudge without sound (see above). Prompt copy: "Look out of the window — or at something about 6 m away".
- Landscape draw ops ≈ 13, not 8: on this GPU the limit is blended pixels (bandwidth), not calls (LOG M4 table).
- The ring → horizon "exhale" morph (PLAN §5.2) is not built; Focus → Break is the normal cross-fade.

### Traps
- **No partial redraw on this GPU**: any moving layer redraws the whole frame; everything on screen costs every frame.
- Every `graphicsLayer {}` block that reads a per-frame state costs ~0.3–0.5 ms of UI thread (Compose observation);
  draw-phase reads re-record the layer instead. Both showed up as "Slow UI thread".
- A force-stopped run is restored on the next start (Ready, or a Flow Focus): adb bursts must `cmd end` + `cmd home`
  before `mode` + `start`, or the start continues the old run (SelectMode is refused outside Idle/Ritual).
- `withInfiniteAnimationFrameNanos` is cancelled by the Compose test rule, so goldens show the landscape at t = 0.

### M5/M6 checklist
- **Landscape ≥ 55 fps / < 3 % janky not met (49–51 fps)**. Best next steps: drive the per-frame motion off Compose
  state (a View/RenderNode or a Choreographer callback setting RenderNode translations directly, no snapshot
  observation), or bake sky + far ridge into one static layer and drop the hill pan to 2 layers; keep the Offscreen UI
  blocks; consider an opaque panel. Re-measure with the frame otherwise idle.
- Begin jank still 36 % / 48 % in the first 1.7 s (LOG): profile allocations (`am dumpheap`/alloc tracker) during the
  breath; the FGS delay alone did not help.
- `gentle_focus` black screenshot once: re-check a Full focus right after an adb start.
- Faces for M5's mode editor exist (`TimerFace`); Why? topics for soundscapes/evening amber still to add (PLAN §3.2
  rows 11, 13).
- Frameo 23:00 standby with a session: still unobserved.

## History/notes — Phase B (app)

Evidence: LOG.md "History + notes · Phase B". Full check green: core 213 tests, app 193 tests (63 new), 80 goldens
(33 new in `HistoryScreenshotTest`; 8 re-recorded on purpose: Deep endNote, holdPanel ×2, wrapUp ×2, home ×2, settings),
lint clean, api23Guard OK. Release APK 2.01 MB.

### Applied core changes (P1–P4)
- P1 `Effect.ThoughtParked(session, thought, at)` / `Effect.ThoughtsCleared(thoughts)` from `park()` / `clearParked()`;
  `EffectRouter` → `EffectSinks.notes` → the repository (`Entry.Parked`, id `EntryId.parked(thought.at)`, resolved on clear).
- P2 `resumeInk` on `Input.EndEarly`/`WrapUp`, `FocusState`/`Snapshot.lastResumeInk`, `SessionRecord.resumeInk`; a new note
  replaces words and ink as a unit (`withResume`). Home and the ritual show it (`ResumeLine`).
- P3 checked by `NotesHooksTest` (the session stays known through Deep's wrap-up and a pending Classic card).
- P4 `stats/` keeps only `TodayProgress`, `DayTotal`, `HourCell`, `TaskEstimate`, `HourHeatmap.MIN_RATED`, `Insights.dateOf`.
- New core: `history/Seed.kt` (`HistorySeed`, ids `seed-…`), `DayBucket.foldMonths` + `KEEP_DAYS = 180` + `activeDays`,
  `Taxonomy.encode/decode`.

### App
- `data/HistoryRepository` (+ `HistoryPort`, `NoHistory`, `TagStore`, `LineFile`): single writer on
  `io.limitedParallelism(1)`; lazy load; `live` per-session entries; `need`/`needLive`/`needStats`/`release`; rebuilds the
  index only while a screen watches; `stats-cache.json` written 2 s after a rebuild, folded to months before ~6 months;
  `export(dir, now)`, debug `seed(days)`, `flush()`. `AppContainer.history`; the log writer calls `onLogged` after each append.
- Capture: `ui/notes/` (`ReasonChip(s)`, `TagGlyph` (16 drawn glyphs), `EndSheet` + `EndFlow`, `ChooserSheet`, `UndoBar`,
  text helpers), `components/KeyboardSheet` (built-in keyboard, no IME), `ParkPad` takes title/hint/save label.
  Deep panel: reason row (6 + More) above the actions, `logReason` with a 2 s merge window (`LOG_WINDOW`); Full focus:
  "Pulled away?" button → chip sheet; Deep and Full End → `EndSheet`; Deep wrap-up: reasons with counts, next step as
  chips/ink/keyboard, "+ Note" in the header, questions scroll above a fixed Start break; Classic break card: 3 + More.
- Screens: `ui/history` (list + detail + sheets + Undo), `ui/insights` (LazyColumn of cards, `Charts.kt` Canvas marks,
  `ChartColors`), `ui/settings/TagEditor`. Root has `Overlay { None, Settings, History, Insights }` over Home.
- adb: `--es cmd export` (release too) → `/sdcard/Android/data/dev.framio.pomodoro/files/export/`; debug
  `--es cmd seed --ei days N` (0 removes `seed-` lines).
- Tests: `FakeHistory` (real journal/index/buckets in memory), `CaptureViewModelTest`, `HistoryViewModelTest` (History,
  Insights, tag editor), `HistoryRepositoryTest` (real files), `ReasonGestureTest` (Robolectric sdk 23, real root),
  `HistoryScreenshotTest`.

### Deviations from DESIGN-history
- Tag reorder is ▲ ▼ buttons (72 px), not long-press drag: simpler and more reliable on this touch panel.
- The wrap-up puts "+ Note" in its header and shows outcome and focus on one row lying down (800 px of height).
- The end sheet replaced `ResumeNotePrompt` (deleted with its two strings); "Type it" uses the built-in keyboard, so the
  system IME is no longer used for resume notes (still for Home's "+ New" task and the if-then line, M5's keyboard).
- The cache folds months from day one (the device measured 653 ms for 508 unfolded days, over the 300 ms threshold).
- Device verification used the release APK plus seed files pushed with root (`core` `SeedTest` writes them when
  `POMODORO_SEED_OUT` is set), because a debug install alone holds the frame for > 60 s. The debug adb seed is tested on JVM.

### Traps
- `viewModelScope` delays run on the main looper: Robolectric tests advance them with `shadowOf(mainLooper).idleFor`, not
  the compose clock.
- A Kotlin value class can't be `vararg`; test names can't contain `;`.
- Lint: `PluralsCandidate` is ignored file-wide in `strings_history.xml` (English-only counts in sentences);
  no `Locale.getDefault()` in composables (`NonObservableLocale`) or static fields (`ConstantLocale`).
- `HistoryRepository.export`/`seed` are suspend on the writer; the debug seed also writes `sessions.jsonl`, which has its
  own writer (fine for a dev hook, never in release).

### Open
- A cold History open (or Insights without a fresh cache) reads both files: 2.6 s for 2k sessions on the frame
  (kotlinx JSON on ART 6 ≈ 0.3 MB/s). Options if it matters: preload when Home has been idle, or a binary cache.
- Insights entry is still 35 % janky (6 of 17 frames, 90th 69 ms) on the warm path; the first lazy frame composes the
  headline and the first row of cards.

## M5 — customization (mode editor, keyboard, appearance, evening amber, soundscapes, Settings)

Evidence: LOG.md "M5". Full check green: core 235 tests (22 new), app 261 tests (68 new), 112 goldens (32 new in
`M5ScreenshotTest`; re-recorded on purpose: ritual ×3 (skip toggle gone, if-then is a tap field), keyboard ×2 (new
keyboard), `settings`), lint clean, api23Guard OK. Release APK 2.18 MB. Insights was Phase B's and is untouched.

### Core
- `audio/WindSynth`: white noise → Chamberlin state-variable band-pass (220–900 Hz) + a 1.1 kHz one-pole low-pass +
  a brown rumble; gusts are cosine moves between random targets 1.5–5 s apart that raise loudness and centre
  together; the filter coefficient is recomputed every 32 samples. Seamless across `fill` calls, `fadeOut`,
  deterministic per seed. ~19 ms per minute of audio on the desktop JVM at 22.05 kHz. `WindSynthTest` (6).
- `Settings` (all keys defaulted, so old files decode; `SettingsMigrationTest` decodes a hand-made M2-era file):
  `evening` (flag) + `eveningStart` (default **20:00**, non-null) + `eveningEnd` (06:00), `soundscapeVolume` (0.35),
  `face` (override, null = each mode's own), `timerSize` (Small/Medium/Large), `systemKeyboard`; `faceOf(mode)`,
  `amberFrom`. **`normalized()` keeps your order** (presets may move too); missing presets come back at the end;
  duplicates and fake "built-in" modes go; own modes beyond `MAX_MODES` (8 in all) drop from the end.
- `ModeRules`: ranges as constants (`FOCUS 1 min..4 h`, `SHORT`, `LONG`, `PAUSE_CAP`, `NUDGE`, `CHECK_IN`, `HOLD`),
  checklist labels 1..48 and unique ids, check-in and hold ranges, `newId` ("own-N"), `copyName` ("Deep 2"),
  `newItemId`. **`longEvery` stays 0..12 with 0 = never** (Gentle start needs it); the brief's "longEvery ≥ 1" is
  read as "1..12 when on".
- `Input.MoveMode(id, by)` (clamped; a no-op writes nothing). `SaveMode` that changes the display clears the ritual's
  remembered Calm/Blackout for that mode, so the new default shows. `time/Evening` (`covers`, `at` = false without a
  trusted clock, `nextChange` across DST). Tests: `ModeEditTest`, `EveningTest`, `ModelTest` additions.

### App
- `ui/keyboard/KeyboardModel` (rules) + `components/QuietKeyboard.kt` `KeyboardSheet` (same signature as Phase B's,
  so every caller got it): QWERTY + one numbers/symbols layer, sentence case, Shift once / double-tap or long-press =
  caps lock, long-press variants (FR/DE and more: `KeyboardModel.Variants`), Backspace on press + repeat after
  450 ms every 70 ms, whole words after 12 repeats, Clear, Done. Keys 104×92 lying down (64×96 standing); the bottom
  row starts at x ≥ 96. The strip of variants sits above the key's row, or on the row below for the top row (taps
  outside the keys' box would not reach it). With Settings → Appearance → Keyboard = system, the sheet shows a
  `QuietField` + Done at the top instead. Used for: Home/ritual "+ New" (`TaskDraft`), the ritual's if-then line
  (`TapField` → sheet, Cancel restores), mode name, checklist items, reason names and notes (Phase B callers).
- `ui/editor/`: `Dial.kt` (`DialMath` pure: one turn = 60 min, relative drags, snap 1 min below 15 then 5s, ± steps;
  `DurationDial` with progress semantics), `ModeEditorViewModel` (edits apply to its own copy first, then
  `SaveMode`; presets never change; `duplicate()` returns the new id and the route reopens on it; `gone` closes),
  `ModeEditorScreen` (Rhythm / Before / During pages; a preset shows a read-only summary and "Duplicate & edit"),
  `ModePreview` (Full face in the theme, Calm's arc on near-black, Blackout's black; follows every edit).
- `ui/settings/`: sections Modes · Rituals · Deep · Sound · Appearance · Goals · Reasons · Data · About/Why (rail
  lying down from x = 96, chips standing). Modes: ▲▼, View/Edit, Copy, Delete with a 6 s Undo (back in place and
  selected). Data: export (same files as adb `export`) and Reset (everything but modes) with Undo. `SettingsHost`
  carries the cue test, soundscape preview, export and version from the activity.
- `platform/SoundscapePlayer`: its own `Thread` (THREAD_PRIORITY_AUDIO), MODE_STREAM at 22.05 kHz mono, 0.5 s buffer
  written in 2048-sample chunks (~11 wake-ups/s), USAGE_ALARM like the cues (media stream is 2/15), in-app gain only.
  Starts 3 s after Begin (after the breath) with a 4 s fade-in; core `Derive.soundscape` = Focus only, so Pause,
  the end of the block, End and Home fade it out (1.5 s) and the break is silent. Volume changes apply live.
- `platform/EveningWatch` (+ `AppContainer.evening`): sleeps until the next start/end (≤ 15 min), re-checks on
  TIME_CHANGED and settings changes. `RootViewModel` → `PomodoroRoot` steps the palette theme → amber in 16 steps over
  4 s (one step with Reduce motion), once. `DisplayController.evening(on)` caps the window at 0.35 (eased over 3 s;
  the system level is capped too, Dim/peek are below it), and Blackout's sunrise rises only to the cap.
  `QuietPalettes.blend`; evening accents are the 8 hues pulled halfway to amber with blue cut (contrast test passes).
- Appearance: `LocalTimerScale` (Full focus face and digits ×0.8/1/1.15) and `LocalSystemKeyboard` (allowed in
  `lint.xml`); the face override reaches `FocusViewModel`. Why? topics `Sound` (row 11, "moderate to strong") and
  `EveningLight` (row 13); `Why.All` for About (the sheet scrolls).
- Build type **`dev`** = release + `BuildConfig.HOOKS` (time scale, knock, seed, the evening fake). Installs in ~15 s
  where the debug APK holds the frame ~60 s. adb (hooks builds): `--es cmd evening --es at 21:30` / `--es at off`.

### Decisions / deviations
- **Skip the ritual screen moved out of the ritual** into the mode editor (Before page, own modes) and Settings →
  Rituals (every mode, including the read-only presets: the skip is per-mode memory, not part of the mode, so presets
  stay read-only). Still only after one run. **The ritual keeps its quick Calm | Screen off toggle** (the user's
  "Blackout one toggle away"); the mode's own default is set in the editor.
- At most 8 modes in all (= the carousel), as PLAN §4; presets can be reordered but not deleted.
- "Default face" is a Settings override (like the accent override), so it also changes the read-only presets.
- Evening amber needs a trusted clock; the brightness cap is a fixed 0.35 (not a setting). Existing users keep their
  stored evening start (M2 wrote the old 20:30 default into the file; new installs get 20:00).
- Reset resets everything except modes/selection (history is never touched); there's no "clear history".
- Soundscape audibility and level on USAGE_ALARM still need the user's ear (as the cues).

### Traps
- A disabled `clickable` on a sheet's root merges every descendant into one semantics node (tests can't find keys,
  TalkBack reads one blob). Use `Modifier.swallowTouches()` (pointerInput, no semantics).
- Robolectric `stateIn` VMs: assert on `vm.ui.value` only after `runCurrent()`.
- Kotlin `explicitNulls = false`: a nullable field with a non-null default can't be stored as null (it reads back as the
  default): that's why evening amber is a flag.
- `uiautomator dump` takes ~3–5 s per call on the frame: fine for finding things once, too slow inside a 60 s burst.
- After `am force-stop` another session's app comes forward and may set its own brightness (we read 255 once after
  writing 187; LOG).

### M6 checklist
- Motion pass: carousel accent cross-fade, wrap-up spring, the ring → horizon morph; Begin jank and the break
  landscape fps are still open (M3/M4).
- Accessibility pass: the editor's chips/toggles have roles; check TalkBack order on the editor and Settings.
- Performance budgets on the device with a soundscape playing in Calm (≤ 0.5 % + the stream's cost) and the editor's
  dial drags (each step is a `SaveMode` + DataStore write; conflated, but measure).
- Ear check with the user: soundscape volume default (0.35 × alarm stream) and the cues.
- README row (M6), and decide whether the `dev` build type stays (it's handy for device bursts).
- Frameo's 23:00 standby with a session: still unobserved.

## M6 — final

Evidence: LOG.md "M6 · Final". Full check green: core 235 tests, app 261 tests, 112 goldens (6 Insights goldens
re-recorded on purpose), lint clean, api23Guard OK. Release APK 2.18 MB. The release build is on the frame and
`pm clear dev.framio.pomodoro` wiped all (agent test) data: the user starts fresh.

### What changed
- **Break landscape** (`breaks/Landscape.kt`): the clock ticks every 50 ms (every 3rd vsync, `delay` then
  `withInfiniteAnimationFrameNanos`), not every vsync. 49 fps / 22 % janky → 19.3 steps/s, 0.9 % janky, 90th 7 ms.
  The motion is ≤ ~1 px a step, so it looks the same. It is a deliberate rate, not "55 fps".
- **Begin in Deep** (`platform/DisplayController.apply`): going into Calm, the window dim waits `CALM_SETTLE` (3 s,
  cancellable by any newer request). Android ramps a window-brightness change itself in system_server, writing the
  backlight every frame for ~0.8 s, right under the breath. Deep 0–1.7 s: 31–33 % → 21–22 % janky. GC is not involved
  (hooks probe: 0 GCs, ~0.6 MB allocated, 95 % on main).
- **Cold start**: `AppContainer.start()` builds the engine on the main thread first (a worker building the `lazy` made
  main wait 68 ms on its lock); the tags file is read on first use (`HistoryRepository.tags` getter, export joins it);
  `Motion` looks up the SensorManager lazily; Loading → first screen has no fade. 1.22–1.33 s → 1.25–1.33 s (release
  1.28 s): within noise.
- **History / Insights**: `components/Skeleton.kt` (static surface blocks, no shimmer) while loading; Insights' rows arrive
  one per frame (`withFrameNanos` stagger) and the offscreen 200 ms fade is gone.
- **Review fixes**: `SoundEngine.staticTrack` releases and refuses an uninitialized track (out of mixer tracks →
  `play()` would have thrown, a crash mid-session); `Motion.listen` quits its HandlerThread when registration fails
  (leaked one per try). No `!!` in main code; no compiler warnings; receivers, sensor, AudioTracks and threads are
  released on their paths; crash/kill restore of the backlight unchanged (CrashGuard + DarkMarker).
- **Tools**: `EVENT|app|home on screen` (once per process, a frame after Home is composed) and, in hooks builds,
  `EVENT|alloc|first 1.7 s: …` per focus block (`MainActivity.allocProbe`, stripped from release).
- Docs: `README.md` (this example), one row in the root README's Examples table. The `dev` build type stays.

### Final budgets (PLAN §6.3; release or dev, on the frame)
| Metric | Budget | Measured | |
|---|---|---|---|
| PSS while focusing | ≤ 40 MB | 20.8 MB Full (M2), 24.8 Calm / 26.2 Blackout (M3), 27–32 MB in a break | ✓ |
| CPU, Calm focus | ≤ 0.5 % | 0 %, frames only on minute ticks (M3) | ✓ |
| CPU, Blackout | ≈ 0 % + ~1 % knock sensing | not re-measured in M6; nothing drawn while dark (M3) | ✓ (M3 evidence) |
| Break landscape | ≥ 55 fps, < 3 % janky | 20 steps/s by design, 0.86 % janky, 90th 7 ms | jank ✓, fps by design |
| Begin transition | < 3 % (brief: < 10 %) | Classic 6–9 % (0–1.7 s); Deep 21–22 % | ✗ (Classic meets the brief's 10 %) |
| History / Insights open | — (brief: frame at once) | skeleton at once; data 2.7 s cold / 0.3 s cached for 2k sessions; 7–8 of ~10 frames janky | partly |
| Cold start to Home | ≤ 900 ms | 1.25–1.33 s (release 1.28 s); Home is the first frame | ✗ |
| APK | ≤ 3 MB | 2.18 MB | ✓ |

### Remaining known issues
- **Deep's Begin**: one 101–127 ms frame composing the Deep screen and its VM, then ~1.5 s of frames at 11–24 ms on
  main (Compose's per-frame animation pass ~6 ms: transition, night layer, breath, Calm alpha) and 10–24 ms on the
  RenderThread (GPU fill: background + night layer + outgoing screen quad, the arc re-tessellated per breath scale).
  Next: pre-compose the Deep screen under the ritual, or drop the breath's scale.
- **Cold start**: ~200 ms process + bindApplication, ~380 ms activityStart (onCreate, class loading), ~530 ms first
  traversal (Home's composition + measure), ~100 ms first draw. Only a lighter first Home frame would move it.
- **Insights/History opening**: each card row's first composition takes 50–130 ms on this CPU; a cold load of a large
  history is kotlinx JSON at ~0.3 MB/s (a binary cache or an idle-time preload would help).
- Still open from earlier milestones: the ear check of cues and soundscapes (alarm stream), Frameo's 23:00 standby
  with a session running, the hot-corner dot over Calm, the motion pass (ring → horizon morph) and an accessibility
  pass (TalkBack order on the editor and Settings) from the M5 checklist were not done (no new features in M6).
