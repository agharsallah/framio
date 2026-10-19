# 06 · Pomodoro: plan

> A focus timer for the frame on the desk. It has several **modes** that differ in rhythm and in how
> much the screen asks of you. The main one is **Deep**: once it starts, the frame goes quiet and
> dark and ignores stray taps, so the screen itself stops being a distraction. Everything can be
> configured. Each feature is based on what the research supports, and the app says honestly how
> strong that evidence is.

Status: plan, not started · Package `dev.framio.pomodoro` · Folder `examples/06-pomodoro`

---

## 1. Goals and non-goals

**Goals**

1. **The frame helps you focus instead of competing for attention.** During a focus block in Deep
   mode the screen is static, dark or off, with no motion, no seconds ticking and no surprises.
2. **Every mode is configurable.** Built-in presets can be duplicated and edited, and you can build
   your own.
3. **The UI is fluid and calm.** Release builds hold 55+ fps on the Mali-400. Motion is purposeful
   and stops completely when nothing changes.
4. **The app is grounded in evidence.** Each feature traces to research (§3). The app never claims
   more than the studies show.
5. **The code is modern.** Kotlin 2.4, Compose, one-way data flow, a pure JVM core, and fast tests.

**Non-goals (v1)**

- No accounts, cloud sync or social features.
- No connection to Frameo or its photos. It is a standalone native app, opened from the launcher.
- No phone app. The frame can't control your phone, so the app only gives guidance about it.
- No points, coins or badges (§3, motivation).

---

## 2. Device constraints that shape the design

Sources: `docs/device-log.md`, `examples/03-hardware-lab/LOG.md`, `docs/framio-hub.md` §3.

| Fact | Consequence for Pomodoro |
|---|---|
| Android 6.0.1 (API 23), RK3126, Mali-400 (GLES 2, no highp), 1 GB RAM, heap 80 MB | Compose **foundation only** (no Material). No blur (RenderEffect is API 31). No `Modifier.shadow` on things that animate. Read animated values in the draw phase. Budget: ≤ 40 MB PSS |
| 57.17 Hz screen, 1280×800 @160 dpi (1 dp = 1 px), no status or navigation bar | Design at 1280×800 landscape and also support 800×1280. The frame has no Back key, so the app needs its own Exit control |
| Lint's NewApi check does not fire with Kotlin 2.4 / AGP 9.4 | Copy the `api23Guard` task and `tools/api23_guard.py` from 05-hub and wire them into `check` |
| The accelerometer works (`gs_mir3da`). Light and proximity sensors are dead | **Knock twice** to peek at the time without touching the screen. No auto-brightness from a light sensor |
| The backlight can be turned off with root (`/sys/class/backlight/rk28_bl/brightness`) | Deep mode can use **Blackout**. Reuse the 05-hub `DisplayPower` rules: never touch window brightness while the backlight is off, and always restore it on stop, Exit, crash and start |
| The speaker works. The mic records silence. No TTS, vibrator or Bluetooth | All cues are synthesized sounds (AudioTrack, no assets). No voice prompts |
| No RTC: the clock reads 01-01 01:00 at boot until NTP | Countdowns use `elapsedRealtime` plus `bootId`. Statistics timestamps wait for a trusted clock (`TimeGate`) |
| The firmware **never restarts a killed process**. The CPU never sleeps | A foreground service runs only while a session is active. No AlarmManager or WorkManager. Coroutine `delay` scheduling is enough |
| The launcher's hot-corner dot sits bottom-left (16–72 px) | Keep the bottom-left **88×88 px** free of anything tappable |
| A soft keyboard may be missing (**check in M0**) | Text entry has a fallback: recent-task chips, a built-in Compose keyboard, and handwriting for the parking lot |
| The frame is on the 192.168.11.x subnet, which phones can't reach | No phone remote in v1 (optional M7) |

---

## 3. What the science says, and what we build from it

Every citation below was checked against Crossref or Europe PMC metadata and the abstract or full
text. Strength ratings: **S** = strong (meta-analysis or replicated), **M** = moderate, **W** = weak
(single study or conflicting results), **F** = folk (no peer-reviewed support).

### 3.1 The honest summary

- **Pomodoro itself is weakly supported. It is a useful structure, not a proven productivity method.**
  - In Biwer et al. 2023 (BJEP, doi:10.1111/bjep.12593, n=87), people on fixed breaks reached the same
    task completion as people on self-regulated breaks, in less time, with less fatigue and more
    motivation.
  - Smits et al. 2025 (*Behav Sci*, doi:10.3390/bs15070861) and Göksu et al. 2026 (*Behav Sci*,
    doi:10.3390/bs16071158) found mostly no difference, and in some measures the reverse.
  - **W.** So fixed intervals are the default, Flowtime is offered as an equal option, and the app
    never says "proven".
- **Breaks make you feel better more reliably than they make you produce more.**
  - In the Albulescu et al. 2022 meta-analysis (PLOS ONE, doi:10.1371/journal.pone.0272460), breaks
    of 10 minutes or less raised vigor (d=.36) and reduced fatigue (d=.35). The effect on performance
    was not significant (d=.16), and longer breaks helped performance more.
  - **M.**

### 3.2 Principles, evidence and features

| # | Principle | Evidence | Str. | Feature |
|---|---|---|---|---|
| 1 | **During focus the screen stays calm and static** | Brief interruptions of 2.8–4.4 s doubled to tripled errors (Altmann, Trafton & Hambrick 2014, JEP:G, doi:10.1037/a0030986). Notifications disrupt even when ignored (Stothart et al. 2015, JEP:HPP, doi:10.1037/xhp0000100) | M | Deep mode: no seconds, no motion, no toasts. The display updates **once a minute** and can go fully dark (Blackout) |
| 2 | **Set a one-line intention before starting** | Implementation intentions, d=.65 across 94 tests (Gollwitzer & Sheeran 2006, doi:10.1016/S0065-2601(06)38002-1). Updated to d=.27–.66 across 642 tests (Sheeran et al. 2025, doi:10.1080/10463283.2024.2334563) | S | "What will you work on?" with an optional if-then plan: "If I get distracted, I'll ___" |
| 3 | **Park stray thoughts** | Unfinished goals intrude, and writing a plan for them removes the effect (Masicampo & Baumeister 2011, JPSP, doi:10.1037/a0024192). Self-interruptions are common (Dabbish, Mark & González 2011, CHI) | M | **Parking lot**: hold, then *Park a thought*, then scribble one word. It reappears at the break |
| 4 | **Leave a "ready to resume" note when stopping** | Reduces attention residue (Leroy 2009, doi:10.1016/j.obhdp.2009.04.002; Leroy & Glomb 2018, *Org Sci*, doi:10.1287/orsc.2017.1184) | M | Pausing or ending asks "Next step when you're back?" The note is shown when the next session starts |
| 5 | **Phone: silence it and check it in batches** | Notifications hurt attention (Stothart 2015). Batching 3 times a day lowered stress, while turning notifications off entirely raised anxiety (Fitz et al. 2019, CHB, doi:10.1016/j.chb.2019.07.016). The idea that a phone's mere presence drains you mostly failed to replicate (Ruiz Pardo & Minda 2022; Parry 2024) | M | Deep-mode ritual checklist: "Phone silenced and out of reach". Long-break copy: "Check messages now if you need to." No brain-drain claims |
| 6 | **Take breaks away from screens: move, look far away, blink** | Using a phone during the break led to 22% fewer problems solved afterwards (Kang & Kurtzberg 2019, doi:10.1556/2006.8.2019.21). Short walks improved mood and vigor (Bergouignan et al. 2016, doi:10.1186/s12966-016-0437-z). Blink rate drops under load | M/W | A break card rotates through *Stand up & move*, *Look out the window*, *Water*, *Eyes far away + slow blinks*. The frame dims during breaks so it doesn't become the thing you look at |
| 7 | **Nature and looking outside** | A 40 s green-roof view improved sustained attention (Lee et al. 2015, doi:10.1016/j.jenvp.2015.04.003). Reviews of attention restoration theory find small to moderate benefits, with real nature better than images (Stevenson et al. 2018) | M/W | The break screen is a slow, dim, procedural landscape. The main prompt still points you to a real window |
| 8 | **Fixed rhythm by default, flexible as an option, longer long breaks** | Biwer 2023. Smits 2025 Flowtime tiers. Albulescu 2022: longer breaks help performance more | W/M | Modes: Classic 25/5, Flow with tiered breaks (≤25 min of work gives 5, ≤50 gives 8, more gives 10), long break 15–30 min |
| 9 | **Record progress, and compare estimates with actuals** | Self-monitoring, d=.40, larger when physically recorded (Harkin et al. 2016, Psych Bull, doi:10.1037/bul0000025). Planning fallacy (Buehler 1994). Durations are misremembered (Roy et al. 2005) | S | Each task gets an optional "≈ sessions" estimate. Insights show estimated vs actual sessions plus daily and weekly totals |
| 10 | **Give informational feedback with forgiving totals, not fragile streaks** | Expected rewards undermine intrinsic motivation (Deci, Koestner & Ryan 1999). Broken streaks cause drop-off (Silverman & Barasch 2023, JCR, doi:10.1093/jcr/ucac029). Goal gradient (Kivetz et al. 2006). Self-compassion after failure (Breines & Chen 2012) | S/M | A daily target fills a ring (goal gradient). Consistency shows as "4 of the last 7 days". No streak counter and no shaming copy |
| 11 | **Silent by default; sound is opt-in and labelled honestly** | Lyrics disrupt reading and instrumental music has about zero effect (Vasilev et al. 2018, doi:10.1177/1745691617747398). White or pink noise helps ADHD (g=+.25) but **hurts** others (g=−.21) (Nigg et al. 2024, JAACAP, doi:10.1016/j.jaac.2023.12.014) | M/S | Soundscapes are off by default. Options are rain and wind (procedural), pink and brown noise ("may help with ADHD; may hurt otherwise"). There is no music |
| 12 | **Make time visible from across the room** | People with ADHD perceive time less accurately (Zheng et al. 2022, doi:10.1177/1087054720978557). Visual timers themselves have only F/W support | S/F | Large timer faces: a **Disc** (a shrinking wedge, Time Timer style), Ring, Horizon and Digits |
| 13 | **Dim and warm the screen in the evening** | Evening screen light delays melatonin (Chang et al. 2015, PNAS, doi:10.1073/pnas.1418490112) | M | After a set evening time, the theme switches to warm amber and caps brightness |
| 14 | **Learn your best hours instead of assuming a chronotype** | The synchrony effect is inconsistent (Chauhan et al. 2025, *Chronobiol Int*) | M | An optional one-tap rating of each session builds a heatmap of focus by hour. Suggestions start only after 20 rated sessions |

### 3.3 Claims the app will not make

| Myth | Status |
|---|---|
| "52/17 is optimal" | Folk. DeskTime's own data (2021 rerun: 112/26) |
| "It takes 23 minutes to refocus" | Misquoted. It's the time until people return to a task, not the time spent refocusing |
| "Work in 90-minute ultradian cycles" | Not supported for waking performance |
| "Pomodoro is scientifically proven" | Small student studies with mixed results |
| "Willpower is a battery" | Failed a 23-lab replication (Hagger et al. 2016, d=.04) |
| "Your phone's mere presence drains your brain" | Mostly fails to replicate |
| "20-20-20 / brown noise / blue light boost focus" | Conflicting results or no studies |

The app has a **Why?** sheet: each feature's (i) opens one sentence of evidence with its strength
label. This lets users trust the app and decide for themselves.

---

## 4. Modes

A mode bundles a **rhythm**, a **ritual** and an **environment**. The built-in presets are read-only.
"Duplicate & edit" creates your own mode. Up to 8 modes appear in the Home carousel.

| Preset | Rhythm | Ritual | Environment |
|---|---|---|---|
| **Classic** | Fixed 25 min work / 5 min break, long break 20 min every 4 work blocks. Breaks start automatically. The next focus block starts on a tap | Intention (optional) | **Full**: Ring face with seconds, soft cues |
| **Deep** (undisturbed) | Fixed 50 / 10, long break 30 min every 2 work blocks. No auto-start | Intention (required), if-then plan, a checklist (phone silenced & out of reach · water · one window or tab · …), and the last "ready-to-resume" note | **Calm** or **Blackout**: no seconds, one update a minute, touch locked, knock twice to peek, cues ramp gently, the screen fades in like a sunrise at the end |
| **Flow** | Counts up. You end the block when you hit a natural stop. Break length uses the Smits tiers. Optional nudge after 90 min ("a real break helps") | Intention | Calm, elapsed time only |
| **Gentle start** | Fixed 10 / 3, then offers "another 10?" | None | Full, warm copy. For low-motivation days (self-compassion, principle 10) |

The 50/10 split in Deep mode is a convention, not a finding. It's the editable default because deep
tasks need more ramp-up time and longer breaks restore more (Albulescu).

### 4.1 Deep mode in detail

```
          Start ──▶ Ritual (intention · if-then · checklist · last resume note)
                         │  "Begin" (a 3 s breathe-in fade: the room goes quiet)
                         ▼
   ┌──────────────── Focus, locked ─────────────────────────────────────┐
   │ Display  Calm: near-black, one thin arc + "38 min" at 12% luminance │
   │          Blackout: backlight off (root), the screen is pure black   │
   │ Touch    single taps do nothing (a faint "hold to open" for 2 s)    │
   │          hold 1.2 s → a slim panel: Park a thought · Pause · End    │
   │ Knock×2  peek: the backlight fades up for 5 s, shows the time left, │
   │          then fades out                                             │
   │ Updates  at most once a minute; nothing animates in between         │
   └─────────────────────────────────────────────────────────────────────┘
                         │ deadline
                         ▼
   End cue: a soft two-note chime, −24 dB, rising over 8 s; the backlight rises over 10 s
                         ▼
   Wrap-up (one screen, every part optional): Done / Partly / Not yet · focus 1–5 ·
   "Next step?" · parked thoughts to review
                         ▼
   Break (dim landscape + one prompt) ──▶ "Ready?" (tap to start the next focus block)
```

- **Ending early** is allowed and never shamed. It asks for a resume note ("Next step when you're
  back?"). The session is logged as *ended early*, not as *failed*.
- **Pausing** is possible but discouraged. Deep mode caps a pause at 10 minutes, then offers to end
  the session with a resume note.
- **Check-in** ("still on it?") is off by default. When it is on, it uses only a 3-second glow and
  never asks for input.

---

## 5. UX and visual design

**Own design system**, called *Quiet*. It is built on Compose foundation, with tokens defined in one
`QuietTheme` (`CompositionLocal`s for colors, type, spacing and motion).

- **Themes:**
  - *Ink* (default): near-black `#0E1116`, chalk `#E8E6E1`
  - *Paper*: warm off-white
  - *Forest*: deep green
  - *Tomato*: a nod to the original
  - *Evening amber*: applied automatically, not selectable
  - Each mode has its own accent, picked from 8 colors that pass 4.5:1 contrast on every theme.
- **Type:** Roboto through named families (`sans-serif-light` / `-medium`, because weights don't
  work below API 28). Timer digits use `fontFeatureSettings = "tnum"`. The timer face is 160–320 px
  on Full and 96 px on Calm.
- **Timer faces:** each is a stateless `@Composable` that receives `progress: () -> Float`, so it is
  read during drawing. Users pick one per mode.
  - **Disc**: a wedge that shrinks
  - **Ring**: a stroke that sweeps
  - **Horizon**: a sun sinks toward a line
  - **Digits**: large numbers only
- **Touch targets:** at least 72 px, and at least 160 px for Start, End and Ready. The bottom-left
  88×88 px stays empty.
- **Text entry:**
  - **Intention:** a chip row of recent tasks, ordered by how often and how recently you used them,
    then "+ New".
  - **New task:** the system IME if M0 finds one. Otherwise a built-in large-key Compose keyboard,
    which looks better on this screen anyway.
  - **Parking lot:** handwriting. You scribble on a pad, and the strokes are stored as compact
    vectors. No keyboard during focus, ever.
- **Tone of copy:** neutral and kind. "Ended early. Want a 10-minute one?" never "You failed".

### 5.1 Screens

```
HOME (1280×800)
┌──────────────────────────────────────────────────────────────────────────────┐
│ Pomodoro                                     ◔ 3 / 6 today        ⚙   Exit  │
│                                                                              │
│   ‹  [ Classic ]  [■ DEEP ■]  [ Flow ]  [ Gentle ]  ›    ← mode carousel     │
│                                                                              │
│   What will you work on?                                                     │
│   (Thesis ch.3)  (Review PR)  (Taxes)  (+ New)                               │
│   ↳ Last time: "rewrite §3.2 intro, then figures"     ← resume note         │
│                                                                              │
│                         ┌──────────────────┐                                 │
│                         │      Begin       │   50 min · break 10            │
│                         └──────────────────┘                                 │
│ (88×88 free)                                                                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

Other screens:

- **Ritual**: a checklist with large toggles. It remembers your choices and can be skipped after the
  first run.
- **Focus**: Full, Calm or Blackout.
- **Peek** overlay.
- **Hold panel**.
- **Park** pad.
- **Wrap-up**.
- **Break**.
- **Ready**.
- **Insights**:
  - a ring for today's target
  - a week bar chart of focused minutes
  - "4 of the last 7 days"
  - estimate vs actual per task
  - a heatmap of focus by hour (after 20 rated sessions)
- **Settings**.
- **Mode editor**:
  - dial-drag duration pickers like the Hub's, no keyboard
  - live preview of the timer face and theme
- **Why?** sheet.

### 5.2 Motion

Motion is fluid but never busy. The Mali-400 rules apply: only `graphicsLayer {}` and
`drawWithCache`, at most about 6 infinite animations per screen, and none during Deep focus.

| Moment | Animation |
|---|---|
| Changing mode in the carousel | Snap-fling with `animateItem`. The accent cross-fades into the background tint (400 ms) |
| Begin | "Breathe in": everything except the timer fades out, the timer scales 1.0→0.92→1.0, and the background deepens (3 s, ease-in-out). It marks the change of state |
| Calm minute tick | The arc eases 1 minute forward over 600 ms, then nothing until the next minute |
| Focus → break | The ring "exhales": the stroke widens and softens into the horizon of the landscape (900 ms shared-element morph by hand, using `AnimatedContent` plus shared progress) |
| Break landscape | 3–4 parallax layers drifting through `graphicsLayer.translationX` (clouds over 60 s, grass sway); about 8 draw calls per frame |
| Wrap-up | Choice chips spring in with no overshoot. Today's ring fills by one segment |
| Reduce motion (setting) | Fades only, 200 ms |

---

## 6. Architecture

This follows 05-hub's proven split and uses the device lessons from 01–05, without depending on
those apps at runtime.

```
examples/06-pomodoro/
  gradle/libs.versions.toml      # copied from 05-hub, minus okhttp/biweekly/qrcodegen
  compose_stability.conf         # dev.framio.pomodoro.core.**, kotlin.collections.*, kotlin.time.*, java.time.*
  core/                          # pure Kotlin/JVM; anything that decides something lives here
    engine/   Command Input Effect Reduce FocusEngine
    model/    Mode Rhythm Ritual Environment Session Task Parked Settings
    rhythm/   FixedRhythm FlowRhythm BreakPolicy
    time/     Deadline BootClock TimeGate
    stats/    Insights Consistency EstimateAccuracy HourHeatmap
    audio/    ChimeSynth NoiseSynth RainSynth      # PCM generators, unit-tested
    gesture/  KnockDetector                         # works on raw samples, unit-tested
    testFixtures/ FakeClock Builders
  app/                           # Android + Compose
    di/ AppContainer             # manual, lazy
    data/ SettingsStore (DataStore + kotlinx-serialization) · SessionLog (append-only JSONL)
    platform/ FocusService DisplayPower SoundEngine Motion Ime
    ui/theme/ QuietTheme tokens · ui/faces/ · ui/home ui/ritual ui/focus ui/break ui/wrapup ui/insights ui/settings ui/editor ui/keyboard ui/park
    MainActivity                 # single activity, singleTask, configChanges, immersive, keep-screen-on
  tools/ api23_guard.py api23_allow.txt
  PLAN.md README.md LOG.md
```

### 6.1 State and one-way data flow

```kotlin
// core: a pure reducer, no Android, no clocks it doesn't receive
fun reduce(state: FocusState, input: Input, now: Instant): Next  // Next(state, effects)

sealed interface Phase {
    data object Idle : Phase
    data class Ritual(val modeId: ModeId) : Phase
    data class Focus(val session: SessionId, val deadline: Deadline?, val startedAt: Mark) : Phase  // null deadline = Flow
    data class Paused(val resumeTo: Phase, val remaining: Duration, val since: Mark) : Phase
    data class WrapUp(val session: SessionId) : Phase
    data class Break(val kind: BreakKind, val deadline: Deadline, val prompt: BreakPrompt) : Phase
    data object Ready : Phase
}

@Serializable sealed interface Rhythm {
    @Serializable @SerialName("fixed") data class Fixed(
        val focus: Duration = 25.minutes, val short: Duration = 5.minutes,
        val long: Duration = 20.minutes, val longEvery: Int = 4,
        val autoBreak: Boolean = true, val autoFocus: Boolean = false,
    ) : Rhythm
    @Serializable @SerialName("flow") data class Flow(
        val tiers: List<Tier> = SmitsTiers, val nudgeAfter: Duration? = 90.minutes,
    ) : Rhythm
}

@Serializable data class Environment(
    val display: Display,            // Full · Calm · Blackout
    val face: Face, val showSeconds: Boolean,
    val touch: TouchPolicy,          // Open · HoldToReveal(1200.milliseconds)
    val knockToPeek: Boolean, val cues: CueStyle, val soundscape: Soundscape?,
    val checkIn: Duration?,
)
```

- **`FocusEngine`** is the single writer. It reads a `Channel<Input>` on
  `Dispatchers.Default.limitedParallelism(1)` and exposes a `StateFlow<FocusState>`. Effects are
  `ScheduleWake(deadline)`, `PlayCue`, `SetDisplay`, `StartService` / `StopService`,
  `AppendLog` and `Persist`. A single scheduler coroutine `delay`s until the next deadline. There is
  **no per-second ticker** in the engine; the UI derives remaining time itself.
- **Time:**
  - `Deadline(bootId, elapsedMs, wallMs?)` survives a process kill and works out remaining time on
    the next start.
  - After a reboot, a running session becomes *interrupted* and is logged with its last known
    duration.
  - Statistics wait until `TimeGate` trusts the wall clock, then back-date the session from the
    stored elapsed time.
  - `kotlin.time.Clock` / `TimeSource` are injected, so tests run on virtual time.
- **UI:**
  - One `ViewModel` per screen does `stateIn(WhileSubscribed(5_000))`.
  - Route composables hold the ViewModel. Screen composables are stateless and previewable.
  - Collect state with `collectAsStateWithLifecycle`.
  - The remaining-time display uses one `produceState` ticker that runs only while the screen is
    RESUMED. It ticks at 1 Hz in Full and 1/60 Hz in Calm.
  - The face reads `progress` through a lambda, so the ticks only trigger a redraw, never a
    recomposition.
- **Navigation:** a `Phase` → screen mapping inside one `AnimatedContent`, so there is no navigation
  library. Settings, Insights and the editor are an overlay stack held in a `rememberSaveable`
  list.
- **Persistence:**
  - **Settings and modes:** DataStore with a JSON `Serializer`, a `ReplaceFileCorruptionHandler`
    (copy aside), and conflated writes.
  - **Session log:** append-only **JSON Lines**, one line per finished session. It is fsynced on
    append and tolerates a torn last line after a power cut. A year of heavy use is about 4k lines
    (~600 kB), which loads in under 100 ms, so Room isn't needed.
  - **Engine snapshot:** the running phase and deadline are persisted on every phase change, so a
    killed process resumes exactly where it was.
- **Service:**
  - `FocusService` is a foreground service with no logic. It starts when the phase is
    Focus/Paused/Break and stops when the phase is Idle. Its type is `specialUse` with the subtype
    property, as in mawaqit.
  - `START_NOT_STICKY`, because the firmware won't restart a killed process anyway.
  - When a deadline passes while the app is in the background, it calls
    `startActivity(NEW_TASK | REORDER_TO_FRONT)`.
- **Display:**
  - `DisplayPower` is adapted from 05-hub.
  - Calm mode uses `window.attributes.screenBrightness`.
  - Blackout uses the root backlight with one command per `su -c`, polling `exitValue()` (not
    `waitFor(timeout)`, which is API 26).
  - The backlight is restored by `onStop`, Exit, the uncaught-exception handler, and every start
    (`recoverAtStart`).
- **Sound:**
  - `SoundEngine` plays static `AudioTrack` buffers rendered once by `ChimeSynth`.
  - Cues use `USAGE_MEDIA`, never change system volume, and ramp in gently.
  - Soundscapes stream from `NoiseSynth` / `RainSynth` on a `MODE_STREAM` track running on its own
    thread, at about 1% CPU (to be measured).
- **Knock:**
  - `Motion` registers the accelerometer at `SENSOR_DELAY_GAME` **only while Deep focus is on screen
    and knock is enabled**.
  - `KnockDetector` (in core) uses the threshold 1.2 m/s² and the 120–600 ms double-knock window
    validated in 03.

### 6.2 Kotlin and Android practices

- **Kotlin 2.4:**
  - `sealed interface` hierarchies, `data object`, value classes (`ModeId`, `SessionId`), and
    `kotlin.time.Duration` everywhere (no raw millis outside the platform edge)
  - `explicitApi()` in `:core`, with `allWarningsAsErrors`
  - no `!!` outside tests
- **Coroutines:**
  - structured concurrency, `viewModelScope` and an app-scoped `CoroutineScope` owned by
    `AppContainer`
  - no `GlobalScope`
  - dispatchers are injected
- **Compose:**
  - strong skipping, which is on by default
  - stability config for `:core` types; check the compiler reports so every screen is skippable
  - `Modifier` is the first optional parameter
  - state hoisting, with `remember` keys correct
  - `derivedStateOf` for "minutes left"
  - `LifecycleResumeEffect` for sensors and tickers
  - Slack compose-lints with `warningsAsErrors`
- **Accessibility:**
  - semantics on the timer ("38 minutes left in focus")
  - 4.5:1 contrast on every theme
  - Reduce motion
  - colour is never the only signal, because each mode has a name and an icon
- **Build:**
  - R8 with shrinking, `-dontobfuscate` so the API 23 guard can name callers
  - core-library desugaring for `java.time`
  - release signed with the debug key
  - configuration cache
  - `kotlin.compiler.execution.strategy=in-process` (several sessions build at once)
- **Manifest:**
  - `dev.framio.tile.subtitle = "Focus timer"`, `accent = #E5735C`, `order = 60`
  - adb hooks via `onNewIntent`:
    `--es cmd start|pause|end|peek|selftest`, `--es mode deep`, `--ei min 1`
  - structured `EVENT|area|text` / `CHECK|…` logcat lines

### 6.3 Performance budgets (release build, measured on the frame)

| Metric | Budget | How |
|---|---|---|
| PSS while focusing | ≤ 40 MB | `dumpsys meminfo dev.framio.pomodoro` |
| CPU during Calm focus | ≤ 0.5% (one redraw a minute) | `top -d 5`, 5 minutes |
| CPU during Blackout | ≈ 0%, plus about 1% while knock sensing is on | same |
| Transitions and break landscape | ≥ 55 fps, < 3% janky frames | `dumpsys gfxinfo … framestats` |
| Cold start to Home | ≤ 900 ms | `am start -W` |
| APK | ≤ 3 MB | no audio or image assets; everything is synthesized or drawn |

---

## 7. Testing

- **`:core` unit tests** (JUnit 4, coroutines-test virtual time, Turbine). This is where most
  confidence comes from.
  - Reducer tables: every phase × input, including invalid ones.
  - Fixed rhythm: a long break after every N blocks; auto-start flags.
  - Flow: the Smits tier boundaries at 25:00, 25:01, 50:00 and 50:01; the 90-minute nudge.
  - `Deadline`: same boot, after a kill, after a reboot (→ interrupted), with an untrusted wall clock.
  - Statistics: "N of 7 days" across DST and midnight; estimate vs actual; heatmap threshold.
  - `KnockDetector`: recorded sample traces from the frame (taps, door slams, typing on the desk) to
    measure false positives.
  - Synths: no clipping, correct ramp, seamless loop points.
  - `SessionLog` parser: a torn last line, an empty file, an unknown future field.
- **App JVM tests** (Robolectric `sdk=23`, `w1280dp-h800dp-land-mdpi`):
  - **Roborazzi** golden images for every screen × the 4 themes × landscape and portrait
  - Compose UI tests for the Deep gestures: a tap does nothing, a 1.2 s hold opens the panel, End
    asks for a resume note
- **Checks:** `./gradlew :core:test :app:testDebugUnitTest :app:verifyRoborazziDebug :app:lintRelease
  :app:assembleRelease api23Guard`.
- **On the device** (with `FR_LOCK` held):
  - `fr run examples/06-pomodoro --release`
  - a 1-minute Deep session through adb extras
  - `fr shot` at each phase
  - knock peek, Blackout restore after `am force-stop`, and the session resuming after a kill
  - results logged in `LOG.md` as Command / Output / Note tables

---

## 8. Milestones

| | Scope | Done when |
|---|---|---|
| **M0 Device checks** (½ day) | Check whether an IME exists (`ime list -s`). Check whether Frameo pops over a foreground app when a new photo arrives. Check that the launcher's hot-corner dot is acceptable over Blackout. Measure cue loudness at −24 dB. Reserve tile order 60 | Findings in LOG.md, plan adjusted |
| **M1 Core** | Models, reducer, both rhythms, Deadline, TimeGate, statistics, synths, KnockDetector, all with tests | `:core:test` passes and covers every table in §7 |
| **M2 Skeleton + Classic** | Gradle setup, DI, DataStore, SessionLog, FocusService, SoundEngine, the Quiet theme, Home → Focus (Full, Ring) → Break → Ready | A Classic 25/5 cycle runs on the frame. It survives `am kill` and resumes. Measured PSS |
| **M3 Deep mode** | Ritual, Calm and Blackout, hold panel, knock peek, Park pad (handwriting), resume note, sunrise end | A 1-minute Deep session on the device: no redraw between minutes (gfxinfo), the backlight is always restored |
| **M4 Breaks + Wrap-up + Flow** | Break prompts, procedural landscape, wrap-up, Flow mode with tiers, Gentle start | Break landscape ≥ 55 fps release; Flow tiers verified |
| **M5 Insights + customization** | Insights screen, mode editor with dial pickers, faces (Disc, Horizon, Digits), themes and accents, evening amber, soundscapes, Why? sheet, built-in keyboard if M0 found no IME | Roborazzi goldens for every screen and theme |
| **M6 Polish + performance** | Motion pass, Reduce motion, accessibility semantics, performance budgets, README row, LOG | Every budget in §6.3 met on the device |
| *M7 (optional)* | LAN panel on port 8060 (start, end, edit modes, export CSV), same JSON commands as adb | Only if the repeater moves to bridge mode |

---

## 9. Open questions

1. **Name:** "Pomodoro" (the plan's default) or something calmer such as "Quiet" or "Deep"?
2. **Default for Deep:** Calm (a dim arc) or Blackout (backlight off)? The plan defaults to Calm,
   with Blackout one toggle away.
3. **Daily target:** in sessions (default 6) or in minutes?
4. **Ritual phone checklist:** is a reminder enough, or would you like an honest "phone in the
   other room" step that's always on in Deep mode?
