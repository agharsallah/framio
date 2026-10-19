# Example 04: Little Meadow

A calm, cheerful learning app for children aged 2 to 5, made for this frame. Five clay-style 3D friends
guide 31 activities in seven places, everything is spoken (English or German, no reading needed), and a
grown-up can set it up and send messages from a phone on the same Wi-Fi.

- **Calm by design:** muted pastels, slow eased motion, no flashing, no buzzers, no timers or scores.
  A wrong answer gets a kind word and, the second time, a soft pulse around the right one.
- **Adapts:** every activity starts from the child's age and gets easier or harder with how it goes.
- **Good habits:** brushing (a real 2 minutes), hand washing, picture routines, tidying, healthy food,
  feelings, breathing, a wiggle break every 15 minutes, a daily limit (45 min by default) and bedtime.
- **Original everything:** characters, objects and scenes ray-marched on the Mac (`tools/render3d`),
  narration recorded with macOS `say` (the frame has no TTS), sounds and music synthesised (`tools/sound`).

## The meadow

| Place (friend) | Activities |
|---|---|
| Pip's Nest (Pip, a honey bird) | Count with me · How many? · Feed Pip · More or fewer (4+) |
| Tuka's Studio (Tuka, a fox kit) | Color hunt · Shape sorter · Mixing colors (3+) · Finger paint · Coloring pages |
| Bo's Word Room (Bo, an elephant) | ABC garden · Trace (3+) · First sounds (4+) · Animal friends |
| Music Corner (Bo) | Xylophone (5 songs) · Bubble notes · Echo drums (3+) |
| Moss's Garden (Moss, a turtle) | Brush teeth · Wash hands · My day (routines) · Healthy plate · Tidy up time · Dress for the weather (3+) |
| Puzzle Path (Pip) | Memory match · Jigsaw · Shadow match · What comes next? (3+) · Spin the shapes (real-time 3D, 3+) |
| Quiet Tree (Luma, an owl) | Breathe with Luma · Feelings · Stories (4 original) · Night light |

Every finished activity adds a sticker to the sticker book. Between bedtime and wake-up only the Quiet
Tree is open, the screen is dimmer, and the night light fades out and turns the backlight off (any touch,
key or knock on the case turns it back on).

## Frame features used

| Block | Use |
|---|---|
| Display + backlight | window brightness from settings, dimmer at bedtime; root writes `rk28_bl` to switch it off after the night light |
| Touch | taps, drags, tap-then-tap for small hands, multi-finger painting, tracing |
| Accelerometer | knock on the frame to wake it; a double knock on the home screen is a knock-knock joke |
| Speaker | 764 narration clips per language, SoundPool effects, synthesised instruments, music loops |
| GPU (Mali-400, GLES 2.0) | "Spin the shapes" renders real 3D with clay lighting and 4× MSAA |
| Wi-Fi | parent dashboard on port 8077 (mDNS "Little Meadow"), family voice messages and photos |
| Root | backlight off/on, dashboard screenshot of what the child sees |

## Build, install, run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew testDebugUnitTest assembleRelease && python3 -I tools/api23_check.py
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n dev.framio.meadow/.MeadowActivity
```

Release builds only for real use: Compose is several times smoother minified on this CPU.

**For grown-ups on the frame:** hold the gear (top right) for two seconds, then tap the numbers written
in words. The parent screen shows the dashboard address and PIN, and the most-used settings.

**From a phone:** open `http://<frame-ip>:8077` (shown on the parent screen) and sign in with the PIN.
Now (what's playing, a live screenshot, lock, +15 min, wiggle break, night light, send a star, "Dinner time!"
and other calls a friend says out loud), Progress, Family (voice messages, photos, the child's name
recorded in your voice), Gallery (paintings), Settings, Activities on/off, Routines.

## Driving it over adb

```bash
adb shell am start -n dev.framio.meadow/.MeadowActivity --es play count          # open an activity
adb shell am start -n dev.framio.meadow/.MeadowActivity --es world habits        # open a place
adb shell am start -n dev.framio.meadow/.MeadowActivity --es cmd lock            # home|lock|unlock|wiggle|nightlight|star|wake
adb shell am start -n dev.framio.meadow/.MeadowActivity --ei bonus 15            # extra minutes today
adb shell "am start -n dev.framio.meadow/.MeadowActivity --es set '{\"age\":4,\"lang\":\"de\"}'"
adb shell am start -n dev.framio.meadow/.MeadowActivity --es say call_dinner     # a friend says it
```

The frame is shared with other sessions: `tools/frame.sh` wraps device commands in the shared lock
(`/tmp/framio-frame.lock`, compatible with `scripts/frame-lock.sh`).

## Assets

```bash
cd tools/render3d && uv run render.py --preview [prefix…]   # 3D sprites → assets/sprites (needs the Mac's GPU)
cd tools/voice && uv run make_voice.py                        # voice/<lang>/*.tsv → assets/voice
cd tools/sound && uv run make_sounds.py                       # sfx + music
```

How the app is put together, its rules and how to add an activity: [DESIGN.md](DESIGN.md).
Everything run on the frame, with outputs: [LOG.md](LOG.md). Nunito font: SIL OFL ([LICENSES](LICENSES)).
