# 06 · Pomodoro: history, notes and insights (design)

> Two asks from the user: **a log of every pomodoro with good stats**, and **a good way to write and handle several
> reasons and notes per session**. The frame is a touch-only 10" screen with no keyboard. Typing is the exception,
> never required.

Status: built. Phase A (this design + `:core`) and Phase B (the app). Deviations and device numbers: NOTES.md
"History/notes — Phase B" and LOG.md "History + notes · Phase B".
Core code: `core/src/main/kotlin/dev/framio/pomodoro/core/notes/` and `…/core/history/`. API map in §11.

---

## 0. Decisions in one screen

1. **Notes live in a second append-only file, `history.jsonl`, next to `sessions.jsonl`.** The session log is not
   touched: old builds keep reading it, and the new code reads both and joins them. An edit is a new line with the
   same id. A delete is a tombstone. Undo writes the inverse line. (§2)
2. **Five typed entries, many per session, each placed on the session's own clock:** `Distraction(tags…)`,
   `EndReason(tags…)`, `Note`, `Parked`, `ResumeNote`. A note body is text, handwriting, or both. (§2.2)
3. **One reason taxonomy with stable chip positions.** 9 default tags plus 2 end-only ones. You can rename, reorder,
   hide and add tags, but never delete one. Chips never reshuffle by usage, so a finger can learn where "Phone" is.
   (§3)
4. **Input ranking: chips → handwriting → Compose keyboard.** The system IME is never opened by default. (§4)
5. **Tap budget:** logging a distraction in Deep focus is **hold + 1 tap**. Ending early with a reason is
   **hold + 3 taps**. The Deep wrap-up with outcome, rating and one reason is **4 taps**. Every flow can be finished
   with **zero typing**. (§5)
6. **History is a list grouped by day, with one filter row.** The session detail shows a vertical notes timeline.
   Edit in place, delete with a 6 s Undo bar, and **never a confirm dialog**. (§7)
7. **Insights show only what the data supports.** Small samples are withheld. Every block shows its *n*. There are no
   streaks and no percentages under 20. The rating heatmap appears only after 20 rated sessions. Trends show only
   when the counts carry them. Charts are static Canvas draws with one entry fade. (§8)
8. **Export** goes through `--es cmd export`: one JSON file (complete, with ink) and two CSV files (sessions and
   entries), written to app-specific external storage and fetched with `adb pull`. (§9)
9. **Performance:** at about 5k sessions and 20k entries, decoding both files takes ~93 ms on the desktop JVM.
   Join, stats, report and a filtered query take ~47 ms. Refreshing one day takes ~15 µs. A day-bucket cache file
   gives Insights a warm start. Add a monthly summary only if the frame measures the cache over 300 ms. (§10)

---

## 1. What the research says, and what it changes here

| Source | Finding | What we build | Str. |
|---|---|---|---|
| Harkin et al. 2016, *Psych Bull*, doi:10.1037/bul0000025 (verified via Crossref) | Monitoring goal progress promotes attainment (d≈.40). The effect is larger when progress is **physically recorded** or reported | Recording is one tap at the moment it happens, not a reconstruction at the end of the day. Insights feeds back totals against the target | S |
| Masicampo & Baumeister 2011, doi:10.1037/a0024192 | Writing down a plan for an unfinished goal stops it intruding | The **Parked** entry is handwriting on a pad. It comes back at the break and in History, and can be marked dealt with | M |
| Leroy & Glomb 2018, doi:10.1287/orsc.2017.1184 | A "ready-to-resume" plan reduces attention residue | A **ResumeNote** is asked for on every early end and offered on every wrap-up. The latest one is shown when the next session starts | M |
| Deci, Koestner & Ryan 1999 | Expected tangible rewards undermine intrinsic motivation. Informational feedback does not | Insights is *information*: totals, mixes and patterns. No points, badges or "level up" | S |
| Silverman & Barasch 2023, *JCR*, doi:10.1093/jcr/ucac029 | Breaking a streak makes people drop off | No streak counter anywhere. Consistency is "N of the last 7 days" and "N of the last 28 days" | M |
| Kivetz, Urminsky & Zheng 2006 | Goal gradient: effort rises as the goal nears | Today's ring and this week's ring, both against the session target | M |
| Chauhan et al. 2025 (PLAN §3.2 #14) | The synchrony effect is inconsistent | The rating-by-hour heatmap appears only after **20 rated sessions**, and no hour's mean is shown below 3 sessions | M |
| Fitts 1954, *J Exp Psych*, doi:10.1037/h0055392 (verified) | Pointing time grows with distance and shrinks with target width | Chips are ≥ 96×72 px. Capture rows sit where the thumb already is (the hold panel). Primary actions are ≥ 160 px | S |
| Hick 1952, *QJEP*, doi:10.1080/17470215208416600 (verified) | Choice time grows with the log of the number of alternatives | The hold panel shows **6 chips + More**, not the whole taxonomy. The end sheet shows at most 11 | S |
| Practice, not a study (Nielsen's "user control and freedom" heuristic) | Undo beats "Are you sure?" | Delete is instant with Undo. No confirm dialog anywhere | F |

**What this means for honesty:** "logged distractions" means what you chose to log. The app never says "you were
distracted 9 times". It says "9 distractions logged".

---

## 2. Data model

### 2.1 Files (all under `files/`)

| File | Owner | Shape | Notes |
|---|---|---|---|
| `sessions.jsonl` | engine → `AppendLog` (existing) | one `SessionRecord` per line | **unchanged**. Deduped by id, last wins |
| `history.jsonl` (new) | `HistoryRepository`, a single writer | one `JournalOp` per line, `"v":1` | append-only, fsynced, compacted when ≥ 25 % is superseded |
| `datastore/tags.json` (new) | `TagStore` (a `JsonStore<Taxonomy>`) | `Taxonomy` | not in `Settings`, so `Settings` stays untouched |
| `stats-cache.json` (new) | `HistoryRepository` | `StatsCache` (day buckets + checkpoint) | disposable: losing it costs one rebuild |

### 2.2 Entries

```
Entry (sealed)                    common fields: id · s (session) · at (focus time into the session, null = recalled later) · w (wall ms written)
├─ Distraction(tags: [TagId], note: NoteBody?)   something pulled you away; one distraction may have several reasons
├─ EndReason(tags: [TagId])                       why an early end happened; only on sessions that ended early
├─ Note(body)                                     a free note, during or after
├─ Parked(body, resolved)                         the parking lot (Masicampo & Baumeister)
└─ ResumeNote(body)                               "Next step when you're back?" (Leroy & Glomb)
NoteBody(text?, ink?)                             ink = InkCodec text, the parking lot's format. Both may be set
```

- **`at` is logical focus time** (pauses excluded), on the same clock as `SessionRecord.focused`. It places the entry
  on the session's timeline. Together with `start.wallMs` it also gives the hour of day. A debug time-scaled run
  stays coherent because both are logical.
- **Recalled entries have no `at`** (wrap-up chips, edits in History). They count in totals but not in "by hour"
  charts, and Insights says how many were not placed.
- **Ids:** the app generates `e-<uuid>` ids. Derived ids make entries from other sources idempotent:
  `rn:<session>` for a resume note an old record carried (`EntryId.legacyResume`), and `pk:<bootId>:<elapsedMs>`
  (`EntryId.parked(thought.at)`) for parked thoughts mirrored from the engine (pending change P1).

### 2.3 Journal lines (versioned)

```
{"v":1,"op":"put","e":{"type":"distraction","id":"e-1f…","s":"b7:1203344","at":"PT12M30S","tags":["phone","noise"]}}
{"v":1,"op":"put","e":{"type":"parked","id":"pk:b7:1950000","s":"b7:1203344","at":"PT3M","body":{"ink":"A0b1…"}}}
{"v":1,"op":"del","id":"e-1f…","s":"b7:1203344"}                  ← delete (undo = the put again)
{"v":1,"op":"amend","s":"b7:1203344","outcome":"partly","rating":4} ← History correction; later fields win; rating 0 clears
{"v":1,"op":"drop","s":"b7:1203344"}                                ← hide a whole session (undo = "restore")
{"v":1,"op":"restore","s":"b7:1203344"}
```

- **Forward compatibility:**
  - A line with `v` greater than 1, an unknown `op`, or an unknown entry `type` is **kept verbatim**. Compaction
    writes it back first.
  - Unknown fields are ignored.
  - Unknown enum values (a tag icon or scope) fall back to defaults (`coerceInputValues`).
- **Robustness:** a torn last line or garbage is skipped and counted (`Journal.skipped`). Blank lines are ignored.
- **Compatibility with the existing log** (no migration step, no rewrite):
  - A record's `resumeNote` appears as a `ResumeNote` with id `rn:<session>`, so it can be edited or deleted like any
    other entry. Its tombstone survives compaction.
  - A record's `parked` count with no `Parked` entries appears as "2 parked thoughts (older version kept only the
    count)".
- **Why not put everything in `sessions.jsonl`?**
  - Notes arrive *during* a session, before its record exists.
  - Edits would mean rewriting a fsynced log that the engine appends to.
  - Ink (~0.3–1 kB a thought) would slow down the stats path, which never needs it.

### 2.4 The taxonomy (`Taxonomy`, `ReasonTag`)

`ReasonTag(id, label, icon, hue 0..7 | -1, scope Any|EndOnly, hidden, builtIn)`, with at most 24 tags and labels of
at most 24 characters.

- **Operations:**
  - `rename`
  - `move(id, index)`
  - `setHidden`
  - `restyle(icon, hue)`
  - `add(label, icon, hue)`: inserted before "Other". Adding a hidden label shows that tag again instead of creating a
    duplicate.
  - `normalized()`: repairs duplicate ids, bad hues and blank labels, and re-adds missing built-ins.
- **There is no delete.** History keeps pointing at tags. Hide them instead.
- **Ids never change meaning.** Custom tags get `u-<slug>[-n]`. For an id the taxonomy doesn't know,
  `label(id)` falls back to a readable label.

---

## 3. Reason taxonomy: defaults and rules

| # | Id | Label | Glyph | Chart slot (Ink / Paper) | Scope |
|---|---|---|---|---|---|
| 1 | `phone` | Phone | phone | 0 · `#3987e5` / `#2a78d6` | any |
| 2 | `person` | Person | person | 1 · `#d95926` / `#eb6834` | any |
| 3 | `noise` | Noise | sound waves | 2 · `#199e70` / `#1baf7a` | any |
| 4 | `hunger` | Hunger/thirst | cup | 3 · `#c98500` / `#eda100` | any |
| 5 | `tired` | Tired | moon | 4 · `#d55181` / `#e87ba4` | any |
| 6 | `wander` | Wandering mind | cloud | 5 · `#008300` / `#008300` | any |
| 7 | `urgent` | Urgent task | bolt | 6 · `#9085e9` / `#6250d6` | any |
| 8 | `tech` | Tool/tech | wrench | 7 · `#e66767` / `#e34948` | any |
| 9 | `other` | Other | three dots | neutral gray | any |
| 10 | `done` | Step done | check | neutral | end only |
| 11 | `leave` | Had to leave | door | neutral | end only |

- **Colours come from the `dataviz` reference categorical palette, in its validated order.** I ran
  `validate_palette.js` against our real surfaces:
  - On Ink `#171B22`, Forest, Tomato and Evening, all checks **PASS**. The worst adjacent CVD ΔE is 8.4 and the
    normal-vision floor is 19.3.
  - On Paper `#FBF8F2`, everything passes, with a contrast **WARN** for 3 slots. The relief is built in, because a
    chip always shows its glyph and label.
  - The mode accents (QuietColors) **fail** the dark lightness band for chart use (L 0.68–0.79). So they stay mode
    identity and are never used to tell tags apart.
- **A tag's colour is identity, not meaning.** No tag is red because it is "bad". The text on a chip is always ink,
  never the tag colour. Colour shows only as a 12 px dot or a 4 px left bar on the chip.
- **Chip order = the user's order, fixed.** Usage never reorders it (motor memory beats a "smart" order; Fitts).
  Settings shows usage counts next to each tag and offers a one-tap "Sort by use" that the user triggers. The app
  never sorts on its own.
- **A 9th or later custom tag gets a hue only if the user picks one of the 8.** Otherwise it is neutral. Charts never
  generate a 9th hue.

### 3.1 Tag editor (Settings → Reasons)

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│  ‹ Settings        Reasons                                                    + Add reason      │
│                                                                                                │
│   ≡  ● ☏  Phone                 used 41×                          Rename   Hide                │
│   ≡  ● ☺  Person                used 12×                          Rename   Hide                │
│   ≡  ● ≋  Noise                 used 9×                           Rename   Hide                │
│   ≡  ● ☕ Hunger/thirst         used 3×                           Rename   Hide                │
│   …                                                                                            │
│   ≡  ○ …  Other                 used 5×                           Rename   Hide                │
│   ── Only when ending early ──────────────────────────────────────────────────────────────    │
│   ≡  ○ ✓  Step done             used 4×                           Rename   Hide                │
│   ── Hidden ────────────────────────────────────────────────────────────────────────────     │
│      ● ✎  Email (hidden)        used 2×                                    Show                │
│ (88×88 free)                                      Sort by use · Restore defaults               │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Reorder:** long-press ≡ (400 ms), then drag. Rows are 72 px tall.
- **Rename / Add** opens the Compose keyboard sheet, the only keyboard in the whole feature. "Add" first offers 8
  glyphs and 8 colour dots, then the label field.
- **Hide/Show** is instant, with a 6 s Undo bar. "Restore defaults" is undoable too.

---

## 4. Input methods, ranked

| Rank | Method | When | Why |
|---|---|---|---|
| 1 | **Chips** (single tap, multi-select) | reasons, outcome, rating, resume suggestions | one tap; aggregates cleanly into stats; no reading during focus; works from 2 m away |
| 2 | **Handwriting pad** | parked thoughts, notes, resume notes | no IME; works in Deep focus; "physically recorded" (Harkin 2016); InkCodec already exists. **No recognition**: ink is shown as ink |
| 3 | **Compose keyboard** (M5, large keys, Ink theme, bottom-left 88×88 kept empty) | tag names, typing a caption under ink, History search (later) | only where text is the point. Never during focus |
| — | System IME | never by default | covers 51 % of the screen, light grey, and the hot-corner dot sits on `?123` (M0 LOG a.) |

**Pad spec** (shared by Park, Note and Resume):

- A 960×420 ink area, with a 3 px stroke in ink colour and no smoothing beyond quadratic midpoints.
- Undo-stroke and Clear buttons.
- `Done` is a 160 px button. The pad **auto-saves 2.5 s after the last stroke**, so `Done` is optional.
- A "Type instead" text link opens the Compose keyboard. It is hidden during focus.
- The engine's `MAX_PARKED` still applies.

---

## 5. Capture flows (with tap counts)

**Counting rule:** a *tap* is one touch. *Hold* is the 1.2 s hold that opens the Deep panel. A scribble is not
counted as taps.

| Flow | Minimum | Typical | Typing |
|---|---|---|---|
| Log a distraction, Deep focus | hold + **1** | hold + 1 (2 reasons: hold + 2) | none |
| Log a distraction, Classic/Full focus | **1** (the "Pulled away" pill) + 1 chip = **2** | 2 | none |
| Park a thought, Deep | hold + 1 + scribble (auto-save) | hold + 1 + scribble | none |
| End early, Deep (no reason, no note) | hold + 1 (End) + 1 (End session) = hold + **2** | with one reason and a resume chip: hold + **4** | none |
| Deep wrap-up | **1** (Start break) | outcome + rating + 1 reason + Start break = **4** | none |
| Classic break card | **0** (ignore it → Unrated) | outcome + 1 reason = **2** | none |
| Resume note at the next start | **0** (it is just shown) | 1 ("Use as intention") | none |
| Add a note in History | 2 (+ Note, scribble, auto-save) | 2 | optional |
| Delete an entry | 2 (open it, Delete) | 2, Undo for 6 s | none |

### 5.1 Deep focus: the hold panel gains a reason row

M3 builds the panel with *Park a thought · Pause · End*. Phase B adds one row of reason chips above it. Tapping a chip
logs a `Distraction` at the current focus time **at once**. The chip fills and shows "Logged". More chips can be
tapped in the next **2 s** (multi-select into the *same* entry). Then the panel fades out by itself (200 ms), and the
screen is dark again. Tapping outside closes it at once. No confirmation and no toast. The Calm screen stays static
once the panel is gone.

```
1280×800, Calm (near-black). The panel slides up 24 px and fades in after the 1.2 s hold.
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                                │
│                                   ◜‾‾‾‾◝   38 min                                              │
│                                                                                                │
│   ┌──────────────────────────────────────────────────────────────────────────────────────┐   │
│   │  Pulled away by…                                                                       │   │
│   │  [● ☏ Phone] [● ☺ Person] [● ≋ Noise] [● ☁ Wandering] [● ⚡ Urgent] [● ☕ Hunger] [ More ]│   │
│   │ ─────────────────────────────────────────────────────────────────────────────────── │   │
│   │      [ ✎ Park a thought ]          [ ❙❙ Pause ]           [ ■ End… ]                  │   │
│   └──────────────────────────────────────────────────────────────────────────────────────┘   │
│ (88×88 free)                                                                                   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
After a tap on Phone:  [◉ ☏ Phone · logged]  … 2 s …  the panel fades, the screen is dark again.
```

- **Chips** are 136×80 px, with 16 px gaps. At most **6 chips + More** are shown (Hick): the first 6 offered tags in
  the user's order. *More* expands in place to the rest (one more tap).
- **Undo:** for 6 s after the panel closes, a hold again shows "Phone · logged 12 min · Undo" at the top of the panel.
  Everything else is editable in History.
- **Cost on the Mali:** one panel draw. The 2 s window is a single `delay`, not an animation. Nothing redraws once the
  panel is gone.

### 5.2 Classic / Full focus

Full mode has no hold lock. A small **"Pulled away?"** pill sits next to Pause (72 px tall). A tap opens the same chip
row as a bottom sheet (≤ 240 px tall, above the 88 px corner), and a chip tap logs and closes it. That is **2 taps**.

### 5.3 Ending early (Deep and Classic)

`End…` in the hold panel (or End in Full mode) opens **one sheet**. The session is not ended until the big button is
pressed. "Keep going" is just as big, because ending is a choice, not a failure.

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Ending at 23 min of 50                                                                        │
│                                                                                                │
│  Why stop now?  (optional, pick any)                                                           │
│  [✓ Step done] [⌂ Had to leave] [☏ Phone] [☺ Person] [≋ Noise] [☕ Hunger] [☾ Tired]            │
│  [☁ Wandering] [⚡ Urgent] [⚙ Tool/tech] [… Other]                                              │
│                                                                                                │
│  Next step when you're back?                                                                   │
│  [ Continue "Thesis ch.3" ]  [ Re-read where I stopped ]  [ ✎ Write it ]                       │
│                                                                                                │
│        ┌──────────────────────┐                     ┌──────────────────────────┐               │
│        │      Keep going      │                     │       End session        │               │
│        └──────────────────────┘                     └──────────────────────────┘               │
│ (88×88 free)                                                                                   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Reasons** are multi-select toggles. Pressing "End session" writes one `EndReason(tags)` with
  `at = focused so far`.
- **Distractions already logged** in this session show as a count under the title ("2 distractions logged"). They are
  not asked again.
- **Resume chips:**
  - *Continue "<intention>"* writes a text ResumeNote: "Continue <intention>".
  - *Re-read where I stopped* writes the fixed text.
  - *Write it* opens the pad (ink).
  - Nothing selected means no resume note, which is fine: Leroy & Glomb is an offer, not a gate.
- **Copy:** "Ending at 23 min" and never "failed". The record is still `EndedEarly` (existing model).

### 5.4 Deep wrap-up (`Phase.WrapUp`, full screen, every part optional)

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│  50 minutes on "Thesis ch.3"                                          ◔ 4 / 6 today            │
│                                                                                                │
│  How did it go?      [   Done   ]   [  Partly  ]   [  Not yet  ]                               │
│  Focus               ( 1 )  ( 2 )  ( 3 )  ( 4 )  ( 5 )                                         │
│                                                                                                │
│  Pulled away?   [◉ ☏ Phone ×2] [● ☺ Person] [● ≋ Noise] [● ☁ Wandering] [More]   [ None ]       │
│                 ↑ pre-filled from the hold-panel log; a tap here adds a recalled one             │
│  Parked (2)     ✎ ~~scribble~~  [Dealt with]   ✎ ~~scribble~~  [Keep]                           │
│  Next step?     [ Continue "Thesis ch.3" ]  [ ✎ Write it ]        + Note                       │
│                                                                                                │
│                                         ┌───────────────────────────┐                          │
│                                         │   Start break · 10 min    │                          │
│                                         └───────────────────────────┘                          │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- Live-logged distractions show as **filled chips with a count** (×2). Tapping one does nothing; long-press opens
  "Remove one".
- Tapping an empty chip adds a **recalled** `Distraction` (no `at`). "None" is purely visual: it just collapses the
  row.
- **Outcome, rating and resume note** go to the engine (`Input.WrapUp`). Reasons and notes go to the journal. Both
  are keyed by `Phase.WrapUp.session`.
- Parked thoughts: *Dealt with* sets `Parked.resolved = true` (and `ClearParked` in the engine, pending P1). *Keep*
  leaves it for Home's review.

### 5.5 Classic break card

M2 already shows `WrapUpCard` (Done/Partly/Not yet) on the break screen. Phase B adds one compact row under it:
**"Pulled away? [top 4 chips] [More]"**, plus the rating row only if the mode asks for it. The break stays a break:
the card is dim, and ignoring it costs nothing (Unrated).

### 5.6 The next start

The Ritual / Home shows the latest `ResumeNote`, as ink or text: "↳ Last time: <note>", plus a
**[Use as intention]** chip when it is text. This is `HistoryIndex.latestResume()`. Once the pending changes land,
it is the engine's `lastResumeNote`/`lastResumeInk`.

---

## 6. Where each entry is written (single writers)

| What | Writer | How |
|---|---|---|
| outcome, rating, resume text/ink, focused, the session itself | engine (existing `Input.WrapUp`, `EndEarly`) | `AppendLog` → `sessions.jsonl` |
| Distraction, EndReason, Note | `HistoryRepository.put(entry)` from the ViewModel | session id = `state.session.id`; `at = Timing.focused(state, now, config)` |
| Parked | engine `Input.Park` (existing), mirrored by an effect (pending P1) | `pk:<bootId>:<elapsedMs>` ids keep it idempotent |
| amend, drop, delete, undo | History ViewModel → `HistoryRepository` | journal ops |

`HistoryRepository` (app, Phase B):

- It runs on `Dispatchers.IO.limitedParallelism(1)` and owns the `Journal`, the latest `HistoryIndex` and a
  `StatsIndex`.
- `put` follows a fixed order: append the line, fsync, `journal.apply`, rebuild the index, `stats.refresh(dates)`,
  then publish `StateFlow<HistorySnapshot>`.
- At ~5k sessions an index rebuild is ~10–40 ms on the JVM. It happens off the main thread and only after an edit.
- It collects the engine's `AppendLog` effects (through `EffectRouter`) to refresh the index after a session is
  logged.

---

## 7. History screen

Opened from Home (a "History" text button next to Insights), as an overlay stack entry like Settings.

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│  ‹  History                                                               Insights ›           │
│  [ Last 7 days ▾ ] [ Task ▾ ] [ Mode ▾ ] [ Outcome ▾ ] [ Reason ▾ ] [ ✎ With notes ]  Clear     │
│ ──────────────────────────────────────────────────────────────────────────────────────────────│
│  Today · Sun 18 Oct                                   3 sessions · 2 h 05 min                  │
│   14:00  Deep    Thesis ch.3      50 min   Done     ★4   ☏☏ ≋   ✎2  ↳                       › │
│   10:10  Classic Review PR        25 min   Partly   —    ☺                                    › │
│   09:00  Deep    Thesis ch.3      23 min   Ended early · Had to leave        ↳              › │
│  Yesterday · Sat 17 Oct                               1 session · 25 min                       │
│   16:30  Flow    Taxes            38 min   Done     ★3                                        › │
│  Thu 15 Oct …                                                                                  │
│ (88×88 free)                                                                                   │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Rows** are 72 px tall: time · mode glyph and name · task · minutes · outcome · rating · reason glyphs (≤ 4, then
  "+n") · note count · ↳ when there is a resume note.
- **Filters** sit in one row above the list (dataviz rule).
  - Date presets come first: Today · 7 days · 28 days · 12 weeks · All.
  - Each dropdown is a chip sheet, never a text field. Task choices come from `HistoryIndex.taskChoices()`, ordered by
    time spent.
  - `HistoryFilter` handles all of them. Unknown-date sessions go into a "Date unknown" group at the end and drop out
    whenever a date filter is set.
- **List** is a `LazyColumn` of day headers and rows, keyed by session id. It holds ~5k rows but composes only those
  on screen.

### 7.1 Session detail (a right-hand panel in landscape, full screen in portrait)

```
┌──────────────────────────────────────────────┬─────────────────────────────────────────────────┐
│ (the list stays, dimmed)                     │  Deep · Thesis ch.3 · Sun 18 Oct 14:00          │
│                                              │  50 of 50 min   [Done ▾]   ★★★★☆ (tap to change) │
│                                              │                                                 │
│                                              │  0 ─┬─ 3 min   ✎ parked  ~~ink~~   [Dealt with]  │
│                                              │     ├─ 12 min  ● ☏ Phone                       ⋯ │
│                                              │     ├─ 31 min  ● ☏ Phone · ● ≋ Noise           ⋯ │
│                                              │  50 ┴─ end     ↳ "figures, then §3.2 intro"    ⋯ │
│                                              │     after      ● ☺ Person (recalled)           ⋯ │
│                                              │                ✎ note  ~~ink~~                 ⋯ │
│                                              │                                                 │
│                                              │  [+ Distraction] [+ Note] [+ Next step]         │
│                                              │                         Delete session          │
└──────────────────────────────────────────────┴─────────────────────────────────────────────────┘
```

- **Timeline:** a vertical line scaled to the session's focused time (≤ 560 px). Entries sit at their `at`. Recalled
  entries go under "after".
- **⋯ on an entry** offers *Change reasons* (the chip sheet, pre-selected), *Edit* (the pad or keyboard) and
  *Delete*. Every change is written at once, and the bottom shows "Deleted · Undo" for **6 s**. Undo writes the
  inverse op. There is no confirm dialog.
- **Outcome, rating and task** edits are `amend` lines. **Delete session** is a `drop` with Undo, and the session
  vanishes from the list, Insights and export.

---

## 8. Insights screen

**Principles:** informational, forgiving, honest.

- Every block shows its sample size.
- Numbers are rounded to what they can carry: whole minutes, and hours as "9 h 10 min".
- Percentages appear only with n ≥ 20. Below that the app shows "3 of 7".

**Default range** is 28 days. The range switcher is one chip row above everything: Week · 28 days · 12 weeks · All.

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│  ‹  Insights      [ Week ] [■ 28 days ■] [ 12 weeks ] [ All ]                     History ›    │
│                                                                                                │
│   ◕ Today 4 / 6        ◑ This week 17 / 42        5 of the last 7 days · 19 of the last 28      │
│     2 h 55 min           12 h 40 min                                                           │
│ ─────────────────────────────────────────────────────────────────────────────────────────────  │
│  Focus per day (sessions; target 6)               │  How sessions ended   n = 58               │
│   6 ┼┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄  target             │  ████████████▓▓▓▓▓▒▒▒░░ ▪▪                 │
│     │ ▌  ▌▌    ▌  ▌▌▌  ▌ ▌▌▌                       │  Done 31 · Partly 12 · Not yet 6           │
│   0 ┴─┴──┴┴────┴──┴┴┴──┴─┴┴┴─                       │  Unrated 4 · Ended early 4 · Interrupted 1 │
│      21 Sep            today                      │                                            │
│ ─────────────────────────────────────────────────────────────────────────────────────────────  │
│  What pulled you away   47 logged in 31 h of focus (1.5 / h)                                    │
│   ● ☏ Phone        ██████████████ 19   ↑ from 9      ╷ small multiples, last 8 weeks:           │
│   ● ☁ Wandering    ████████ 11          about same   │ Phone  ▁▂▂▃▅▆▇█                          │
│   ● ☺ Person       █████ 7              —            │ Wander ▃▃▂▄▃▃▃▃                          │
│   ● ≋ Noise        ███ 4                —            │ Person ▁▁▂▁▃▁▂▂                          │
│  When (distractions per hour of the day, focus time beneath)   6 recalled at wrap-up not placed  │
│   ▁▁▁▁▁▁▁▃▅▆▄▂▁▃▆▇▅▂▁▁▁▁▁▁                                                                       │
│ ─────────────────────────────────────────────────────────────────────────────────────────────  │
│  Focus rating by hour   12 of 20 rated sessions needed before this appears                       │
│  Tasks: Thesis 9 h 20 · Review PR 2 h · Taxes 1 h 15 · Other 25 min    Estimate vs actual ›     │
│  Modes: Deep 8 h · Classic 3 h 30 · Flow 1 h 10 (typical Flow block 34 min, 25–48, n = 9)        │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

(The real screen scrolls vertically in two columns. The mock compresses it.)

### 8.1 Each stat, defined (all in `InsightsReport.build`)

| Block | Definition | Withheld when | Form (dataviz) |
|---|---|---|---|
| Today ring | finished sessions today / daily target; focused minutes beneath | never | **ring meter**: track = a lighter step of the same hue (goal gradient) |
| This week ring | Monday→today finished sessions / (target × days so far) | never | ring meter |
| Consistency | days with ≥ 1 finished session among the last 7 and the last 28 | never | **text**, no chart, no streak |
| Focus per day | finished sessions per day in range, with a hairline at the target | never (zeros shown) | columns ≤ 24 px with a 4 px round top, one hue; a target hairline, not a second axis; minutes in the tooltip-free table view |
| How sessions ended | count per `Outcome` | n = 0 | **one horizontal stacked bar**, 2 px surface gaps. Done→Unrated is an **ordinal one-hue ramp** (validated `--ordinal`: Ink `#cde2fb #9ec5f4 #6da7ec #3987e5`, Paper `#184f95 #256abf #3987e5 #6da7ec`). Ended early and Interrupted are neutral grays (never status red: no shaming). The labelled counts below are the legend |
| What pulled you away | count per tag (a tag counts once per distraction); total distractions; rate per focus hour | rate withheld below **5 h** of focus | horizontal bars, **one hue** for all (nominal, one series), with the tag's dot and glyph beside the label in ink |
| Trend | this range vs the previous equal range | All time; larger count < 5; difference < 3 or < 25 % | words ("↑ from 9", "about the same"), never a percentage |
| Weekly sparkline | top 3 tags, last 8 weeks | no tags | **small multiples**, one hue, 2 px line, end dot ≥ 8 px |
| By hour of day | placed distractions per clock hour; focus seconds per hour beneath | — ; the "not placed" count is shown | 24 thin columns plus a 1 px focus strip under them (not a dual axis) |
| Focus rating by hour | mean rating by starting hour | < **20 rated** sessions in the range; any hour with < **3** has no mean | heatmap cells, a sequential one-hue ramp, n printed in each cell |
| Estimate vs actual | per task with an estimate: all-time finished sessions vs the estimate | no estimates | dumbbell (two shades of one hue), estimate ○ → actual ● |
| Modes / tasks | focused time, started, finished; tasks top 7 + "Other" | — | a sorted list with a thin bar; it never gets 8+ colours |
| Flow typical block | median and interquartile range of finished Flow blocks, whole minutes | < **5** blocks | text: "34 min (25–48), n = 9" |

**Drawing on the Mali-400:**

- Every chart is a stateless `@Composable` drawing one `drawWithCache` path set from an immutable model.
- No animation except **one 200 ms alpha fade** of the whole screen on entry.
- Nothing ticks, and Insights never redraws while idle.
- There is no hover on a touch screen. A tap on a bar or cell shows a fixed readout line above the chart (value first,
  then label), and every number is also in the table view.

---

## 9. Export

- **adb:** `am start -n dev.framio.pomodoro/.MainActivity --es cmd export` makes `HistoryRepository` write three
  files to `getExternalFilesDir("export")`. No permission is needed on API 19+. The path is
  `/sdcard/Android/data/dev.framio.pomodoro/files/export/`:
  - `pomodoro-<yyyyMMdd-HHmm>.json`
  - `…-sessions.csv`
  - `…-entries.csv`
- Logcat shows `EVENT|export|<n> sessions, <m> entries → <path>`.
- Then: `adb pull /sdcard/Android/data/dev.framio.pomodoro/files/export/ ./pomodoro-export/`.
- **JSON** (`Export.json`) is complete and re-importable:
  - top level: `format`, `version`, `exportedAt` (UTC), `zone`, `tags` (taxonomy)
  - `sessions`: each one is the `SessionRecord` fields plus `date`, `startLocal`, `legacyParked` and `entries`, with
    ink included
  - corrections are applied and dropped sessions are excluded
- **CSV** (`Export.sessionsCsv`, `entriesCsv`):
  - RFC 4180: CRLF, quoting for `,` `"` CR LF and edge spaces, doubled quotes
  - a UTF-8 BOM for spreadsheet apps (optional)
  - **formula injection is defused**: a text cell starting with `= + - @ TAB CR` gets a leading `'`, but plain numbers
    don't
  - durations in **whole seconds** (`planned_s`, `focused_s`), the log's own precision
  - reasons as "Phone; Noise" using current labels
  - ink as its InkCodec text in an `ink` column
- Optional later: the M7 LAN panel can serve the same strings.

---

## 10. Retention and performance

**Retention: keep everything.** It's small:

- 5k sessions take ~1.0 MB of `sessions.jsonl`.
- 20k entries, 10 % of them ink, take ~2.9 MB of `history.jsonl`.
- The frame has gigabytes free.

**No automatic deletion of user data, ever.** The journal is compacted (`Journal.shouldCompact`: ≥ 500 superseded
lines and ≥ 25 % of the file) by writing `compacted()` to a temp file, fsyncing it and renaming it over the journal.
Unknown future lines and tombstones are kept.

Measured in `ScaleTest` (desktop JVM, warm, best of 5; the frame will be several times slower):

| Step | 5k sessions + 20k entries | Budget (test) |
|---|---|---|
| Decode `sessions.jsonl` (1.0 MB) + `history.jsonl` (2.9 MB) | **93 ms** | < 400 ms |
| Join + rebuild every day bucket + full report + one filtered query | **47 ms** | < 200 ms |
| Refresh one day after an append or edit | **15 µs** | < 5 ms |
| Decode the stats cache (1000 days, 1.2 MB) | **26 ms** | < 200 ms |

**What the app does with that:**

- **Start:** read `stats-cache.json`. If `isFresh(file lengths, zone)` holds, Insights can render at once. Then, in
  the background, decode both files, build the index, and rebuild the buckets only if the checkpoint differs. Write
  the cache back (conflated, at most once every 30 s).
- **After a session or an edit:** `refresh` only the touched day(s), then build the report: O(days).
- **When to add a compacted monthly summary file:** only if Phase B measures *on the frame* that the cache decode
  goes over **300 ms**, or the full decode goes over **1.5 s**. Expected around 15k sessions. The fix is to fold day
  buckets older than 12 weeks into month buckets: Insights' ranges (Week/28/84 days) need days only within 84 days,
  and "All" sums months. Even then, JSONL stays the source of truth. No Room.
- **Memory:** ~5k `SessionView`s plus 20k entries is roughly 6–8 MB on the heap. That is within the 40 MB PSS budget,
  but measure it. If it is tight, `HistoryIndex` can drop ink bodies (`NoteBody.ink`) from memory and re-read them
  for the detail panel.

---

## 11. Phase A code map (`:core`, new files only)

| File | What |
|---|---|
| `notes/Taxonomy.kt` | `TagId`, `TagIcon`, `TagScope`, `ReasonTag`, `Taxonomy` (defaults, `offered`, `label`, `rename`, `setHidden`, `restyle`, `move`, `add`, `normalized`) |
| `notes/Entry.kt` | `EntryId` (+ `legacyResume`), `NoteBody` (+ `cleaned`), sealed `Entry` (Distraction, EndReason, Note, Parked, ResumeNote), `tags`/`body`/`kind` |
| `notes/Journal.kt` | `SessionAmend`, `JournalOp` (Put, Delete, Amend, Drop, Restore), `JournalCodec` (versioned lines; Op/Unknown/Garbage; `read`), `Journal` (fold, `superseded`, `shouldCompact`, `compacted`) |
| `history/History.kt` | `SessionView`, `DayGroup`, `HistoryFilter`, `HistoryIndex` (`build` joins log + journal + legacy synthesis; `query`, `on(date)`, `taskChoices`, `latestResume`, `openParked`, `orphans`), `SessionRecord.amendedBy` |
| `history/DayBucket.kt` | `DayBucket.of(date, sessions, zone)`: per-day aggregates (outcomes, modes, tasks, tags, end tags, hour arrays, ratings, Flow blocks), `Tally`, `TagCount` |
| `history/InsightsReport.kt` | `StatsIndex` (incremental `rebuild`/`refresh`/`load`), `StatsRange`, `Trend`, `ReasonCount`, `TagWeeks`, `HourLoad`, `Total`, `FlowSummary`, `InsightsReport.build` + `trendOf` |
| `history/Export.kt` | `Export.json`, `sessionsCsv`, `entriesCsv`, `cell` |
| `history/StatsCache.kt` | `Checkpoint`, `StatsCache` (+ codec, `isFresh`) |

Tests (68, all JVM): `notes/TaxonomyTest` 11, `notes/JournalTest` 12, `history/HistoryIndexTest` 12,
`history/InsightsReportTest` 19 (DST, midnight, empty data, the 20-rated threshold, the 3-per-cell rule, trends,
Flow percentiles, top-7 folding, and an incremental-equals-rebuild fuzz test over 300 random appends, edits and
drops), `history/ExportTest` 7 (RFC 4180, formula guard, BOM, JSON round trip), `history/StatsCacheTest` 3,
`history/ScaleTest` 4.

The older `core/stats/*` (`Insights`, `Consistency`, `HourHeatmap`, `EstimateAccuracy`) stay as they are, because
Home's ring uses the engine's `completedToday`. `InsightsReport` reuses their `TodayProgress`, `DayTotal`, `HourCell`,
`TaskEstimate` and `HourHeatmap.MIN_RATED`. After Phase B, `stats/Insights`, `Consistency` and `HourHeatmap.build`
are unused and can be deleted.

---

## 12. Phase B plan (after M3)

1. Apply the pending core changes (NOTES.md, "History/notes — pending core changes").
2. **App data layer:**
   - `HistoryRepository`: journal file, index, stats, cache, export
   - `TagStore`: `JsonStore<Taxonomy>`, `files/datastore/tags.json`
   - wire them in `AppContainer`
   - route `AppendLog` and the new parked effects to the repository
3. **Capture UIs:**
   - the hold-panel reason row (§5.1)
   - the Full-mode "Pulled away?" pill (§5.2)
   - the end-early sheet (§5.3)
   - the Deep wrap-up rows (§5.4)
   - the Classic break-card row (§5.5)
   - the resume line on Ritual/Home (§5.6)
   - a shared `ReasonChips` and `InkPad` composable, and drawn tag glyphs (`ui/glyphs`)
4. **Screens:**
   - `ui/history` (list + detail, Undo bar)
   - `ui/insights` (report + Canvas charts)
   - Settings → Reasons (tag editor)
   - the Compose keyboard sheet for tag names (minimal; the M5 keyboard can replace it)
5. **adb:**
   - `--es cmd export`
   - debug-only `--es cmd seed --ei days 120`: realistic synthetic history through the same repository, written to
     separate files that a later `seed` with `--ei days 0` removes
6. **Tests:**
   - ViewModel tests with a fake repository
   - Robolectric tests for the hold-panel chip (tap logs once, the 2 s window merges a second tag, an outside tap
     closes the panel) and for Undo
   - Roborazzi goldens: History list, History detail, Insights (empty, sparse, rich), the end sheet, the wrap-up and the
     tag editor, on Ink + Paper, landscape and portrait
7. **Device (lock protocol):**
   - seed, then time-to-first-Insights from a cold start
   - `gfxinfo` on Insights entry and History scroll
   - PSS with 5k seeded sessions
   - the export + `adb pull` round trip
   - results in LOG.md

### 12.1 As built (Phase B)

- Measured on the frame with 2,052 seeded sessions: Insights opens in **321 ms** from the cache (folded to months before
  ~6 months, 173 buckets, 173 kB) and in 3.3 s the very first time (both files read: 2.6 s). History's first open
  takes the same 2.6 s read. PSS 25 MB (Insights from cache) to 34 MB (history loaded), within the 40 MB budget.
- Tag reorder uses ▲ ▼ buttons instead of drag; the wrap-up puts "+ Note" in its header and outcome + focus on one row.

## 13. Open questions for the user

1. Should "Step done" as an end reason also set the outcome to Done? I propose **yes**: the session stays
   `EndedEarly` for timing, and the History row reads "Ended early · step done".
2. Should the hold panel's reason row be on by default in Deep? I propose **yes**, with a setting to hide it, for
   people who only want Park/Pause/End.
3. Should parked thoughts marked "Keep" ever expire from Home's review? I propose **no**. They leave the review after
   7 days but stay in History.
