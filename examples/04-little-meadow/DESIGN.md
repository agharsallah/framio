# Little Meadow: design and contributor guide

A calm learning app for children aged 2 to 5 on the Veidoo F10 frame (Android 6, 1280×800, Cortex-A7,
Mali-400, 1 GB RAM, no TTS/mic/camera; see `docs/device-log.md`). Native Jetpack Compose.

## The feel (rules every screen follows)

- **Calm, not flashy.** Muted pastels from `Palette`, soft clay 3D, slow eased motion (300–600 ms),
  no shaking, no flashing, no confetti bursts, nothing loud. Celebrations are slow falling petals.
- **Voice first.** Children can't read. Every instruction is spoken (`prompt`). On-screen words are for
  grown-ups and early readers only, never needed to play.
- **No failure.** No timers, lives, scores or buzzers. A wrong answer gets `notQuite()` (soft sound,
  kind line, a small tilt); the second miss turns on a visual `hint` that pulses around the answer.
- **Big, forgiving targets.** ≥ 150 dp for things to tap, ≥ 80 dp for buttons. Every drag also
  works as tap-then-tap (`DragItem`). Multi-finger gestures are never required.
- **Short rounds.** 4–6 rounds, then `finish()`: sticker, "again" or "back". Not too much per screen:
  at most ~6 things to choose from, plenty of space.
- **Age-aware.** `level` (0..4) starts from the age the grown-up set and adapts (3 first-try rounds up,
  2 helped rounds down). Use it to size the next round (how many, how many choices, how similar).
- **Good habits and kindness** run through the content: brushing, washing, tidying, sharing, naming
  feelings, breathing, sleep. Friendly, never preachy.

## The friends

| Friend | Look | Hosts | Voice style |
|---|---|---|---|
| Pip | round honey bird | Pip's Nest (numbers), Puzzle Path | cheerful guide |
| Tuka | terracotta fox kit | Tuka's Studio (colours, shapes, art) | curious, playful |
| Bo | powder-blue elephant | Bo's Word Room, Music Corner | gentle, musical |
| Moss | sage turtle with a leaf sprout | Moss's Garden (habits) | slow, kind |
| Luma | lavender owl | Quiet Tree (calm, feelings, stories, night) | soft, sleepy |

Sprites `ch_<friend>_<pose>` with poses idle, blink, talk, happy, sleep, wave. Use `FriendView`, never
the raw sprites, so breathing, blinking and talking work.

## Code layout

```
dev.framio.meadow
  AppState.kt          singletons: settings, progress, session, family, voice, sfx, music, commands, server
  MeadowActivity.kt    the one activity: adb extras, knocks, brightness, backlight
  MeadowRoot.kt        navigation, gates (rest, wiggle break), the play clock, remote commands
  core/                Settings (+JSON), Progress (+Difficulty), Session (+SessionRules), Clock, Family, Commands, Friends
  audio/               Voice (narration clips), Sfx (SoundPool + instruments), Music (looping tracks)
  hw/                  Knock (accelerometer), Device (volume, brightness, root backlight, screenshot)
  ui/                  Theme (Palette, Type), Sprites, FriendView, Components, Icons, DragDrop, Celebrate, Backdrop
  play/                Play/World/PlayScope, PlayScreen (the host), Choices (ChoiceRow), Catalog, Stickers
  screens/             Home, World, gates, sticker book, mailbox, parent screen + gate
  worlds/<id>/         <Id>World.kt (the World and its Plays) + one file per activity
  remote/              the parent dashboard server (+ assets/web)
```

## Adding an activity

1. Write `worlds/<world>/<Name>.kt` with `@Composable fun Name(s: PlayScope)`.
2. Add a `Play(...)` to that world's `plays` list in `<Id>World.kt`.
3. Add its narration to `voice/en/<world>.tsv` and `voice/de/<world>.tsv`, including `play_<id>`
   (the title, said when its card is tapped), then `cd tools/voice && uv run make_voice.py`.
4. New 3D things: `tools/render3d/models/<world>.py`, then `cd tools/render3d && uv run render.py --preview <prefix>`
   and look at `preview/<prefix>.png`.

```kotlin
@Composable
fun ColorHunt(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val (options, target) = remember(round) { balloonRound(s.level) } // a pure function of the level
    LaunchedEffect(round) {
        s.rounds(round, total)
        s.prompt("colorhunt_find_${target.color}")                    // spoken + repeatable + idle re-prompt
    }
    ChoiceRow(s, options, isRight = { it == target }, onRight = { _, first ->
        s.practised("color:${target.color}")
        s.roundDone(first)
        if (round + 1 == total) s.finish() else round++
    }) { Sprite("ob_balloon_${it.color}", Modifier.size(160.dp)) }
}
```

`Count.kt` in `worlds/numbers` is the reference: positions from a pure function, sounds rising with
each count, a big number at the end, `roundDone`/`finish`.

### PlayScope (see `play/Play.kt`)

`say`, `sayAndWait`, `prompt` (sets the repeatable instruction), `repeatPrompt`, `praise`,
`praiseAndWait`, `notQuite`, `showHint`, `hint`, `friendMood`, `sfx`, `level`, `age`, `lang`,
`reducedMotion`, `roundDone(firstTry)`, `rounds(done,total)`, `practised(skill)`, `finish()`, `app`,
`clearPrompt()` (no repeatable instruction for now: brushing, a story reading itself).

The host draws: back button (top-left), progress dots (top), "say it again" (top-right), the friend
(bottom-left, 210 dp; set `showFriend = false` to place it yourself), and the ending. The content
area is the full width × (800 − 112) dp. Keep the bottom-left ~230×230 dp free when the friend shows.

### UI kit (`ui/`)

- `Modifier.pressable { }`: squish on press, debounced, plays `Sound.Tap` (pass `sound = null` to silence).
- `Modifier.hintPulse(on)`, `.halo(on)`, `.floaty(seed)`, `.wobble(trigger)`.
- `SoftCard(color) { }`: puffy card. `RoundButton(Icon.X) { }`. `ProgressDots`.
- `Sprite(name, modifier)`, `rememberSprite(name)`, `DrawScope.drawSpriteFit(bmp)`.
- `FriendView(friend, mood = Mood.Idle|Happy|Sleep|Wave)`.
- `ChoiceRow(scope, options, isRight, onRight) { content }`: the whole right/wrong answer flow.
- `rememberDragArena()`, `Modifier.dropZone(arena, id)`, `DragItem(arena, id, onDrop = { zone -> Boolean }) { }`.
- `FallingPetals()`, `Backdrop("bg_x")`, `Type.clay(size, color)` for big numerals and letters.
- `Modifier.softShadow(corner, elevation)`: a cached blurred bitmap; use it, never `Modifier.shadow` or
  `clip(RoundedCornerShape)`, on anything on screen while things move. `rememberSlowClock()`: a ~12 Hz
  time for endless ambient motion (breathing, bobbing, twinkling) instead of `rememberInfiniteTransition`.
- `Play(stillFriend = true)` for screens with a GL view.
- Sounds: `Sound.*` (tap, pop, good, sparkle, place, hmm, whoosh, pick, drum, shaker, clap, ding,
  splash, brush, yawn) and `app.sfx.note(Instrument.Marimba|Bell|Kalimba|MusicBox, midi)`.
- Music: `Play(music = Track.Meadow|Studio|Night|Calm|null)`.

## Assets

| Kind | Source | Output | Tool |
|---|---|---|---|
| 3D sprites | `tools/render3d/models/*.py` | `assets/sprites/<name>.webp` | `uv run render.py [prefix…] --preview` |
| Narration | `voice/<lang>/*.tsv` (`id<TAB>text`) | `assets/voice/<lang>/<id>.ogg` + `manifest.json` | `uv run make_voice.py` (macOS `say`) |
| Sounds, music | `tools/sound/make_sounds.py` | `assets/sfx`, `assets/music` | `uv run make_sounds.py` |

**Naming.** Sprites: `ch_` friends, `ob_` shared objects, `bg_` backdrops (1280×800), and per-world
prefixes for anything new: `nu_` numbers, `co_` colors, `wo_` words, `mu_` music, `hb_` habits,
`pz_` puzzles, `qt_` quiet. Voice ids start with the activity id (`colorhunt_find_red`) or world
id; `name_<thing>` is the spoken name of an object (shared ones are in `common.tsv`, don't redefine;
make_voice stops on duplicates).

**Modelling** (`sdf.py`): `add/cut/clip/paint` with `sphere, ell, capsule, cone, box, cyl, torus, arc,
star, flat(2D shape extruded), plane, raw`, `sym=True` mirrors across x, `k` blends like pressed clay.
Faces: `eyes(m, x, y, z, r, mood)`, `cheeks`, `smile`. The model stands on y = 0 and faces +z.
Keep colours from `objects.COLORS` for teaching colours. Objects 320×320 (`size=(320, 320)`).

**Writing narration.** Short, warm, concrete, one idea per line. English and German for every line
(German: natural, informal "du"). Numbers and names are already there (`num_1..20`, `thats_1..10`,
`name_apple`…). A sentence that needs a variable part is split into clips played in sequence
(`s.say("feed_give", "num_3", "feed_berries")`); prefer whole sentences when the set is small.

## Performance on the frame

- Build `assembleRelease` for anything you judge by feel; debug Compose is several times slower.
- **This firmware redraws the whole screen for any change** (`debug.hwui.render_dirty_regions=false`), so
  every per-frame cost multiplies: idle home went from 81 ms to 7 ms per frame once rounded clips and
  elevation shadows were drawn instead, and idle motion moved to the slow clock (LOG.md).
- Animate in the draw phase: read animated values inside `graphicsLayer { }` / `drawBehind { }` /
  `Canvas { }`, never in composition. No per-frame recomposition of big trees.
- Few simultaneous infinite animations (≤ ~8 per screen). Sprites ≤ 512 px. No blur effects
  (RenderEffect is API 31). No `Modifier.shadow` on things that animate every frame.
- Bitmaps count against the Java heap on Android 6: use `Sprites` (bounded cache), decode photos scaled.
- API 23 only: `python3 -I tools/api23_check.py` after `assembleDebug` (Lint's NewApi doesn't fire here).

## Build, test, run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew testDebugUnitTest assembleDebug && python3 -I tools/api23_check.py
tools/frame.sh shot app/build/outputs/apk/debug/app-debug.apk /tmp/shot.png 5 --es play count
```

`tools/frame.sh` serialises device use between builders: always go through it (`shot`, `tap x y`,
`swipe …`, `run <adb …>`). Coordinates are pixels = dp on this frame (160 dpi).

adb control: `--es play <id>`, `--es world <id>`, `--es cmd home|lock|unlock|wiggle|nightlight|star|wake`,
`--ei bonus 15`, `--es set '{"age":4,"lang":"de"}'`, `--es say <voice id>`.
