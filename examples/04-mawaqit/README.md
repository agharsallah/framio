# Example 04: Mawaqit

A native Jetpack Compose clone of the [Mawaqit](https://mawaqit.net) mosque screen for the Veidoo F10, controllable
from a phone at home or from anywhere. It reads a real Mawaqit mosque (no account needed) or computes the times itself,
plays the adhan on the frame's speaker, and can share the screen with Frameo's photos.

| | |
|---|---|
| Main screen | clock with seconds, Gregorian + Hijri date (mosque's adjustment), next prayer with countdown and progress, the five prayers with adhan and iqama (its time with a small `+N` beside it, or `+N` alone), Shuruq, Imsak, next Jumu'a, Eid, weather, flash ticker (a long-press hides it; back on from the settings or the panel) |
| Around each prayer | "adhan soon" notice ("Dhuhr in 20 min" in the top bar, a soft chime; minutes and prayers yours) → **adhan** (audio + screen) → dua after the adhan → **iqama countdown** with "turn off your phones" → iqama → prayer (dim or black) → azkar; on Fridays the Jumu'a screen with its hadith |
| Slides | Mawaqit's random hadith (its own API, in the chosen language) and announcements (the mosque's and our own), crossfading over the clock; now and then (30 % of the slides by default) a short dhikr from the adhkar display's collections, never in the last 10 minutes before an adhan |
| Secondary mosques | up to 3 more Mawaqit mosques, display only (the main one keeps the adhan and the screens): a "Nearby mosques" slide (Fajr…Isha with adhan and iqama, Jumu'a, each mosque's next prayer lit) and, on a tap on the mosque name ("+3 mosques"), a full-screen comparison of all of them, main first, today or tomorrow; closes on a tap, Back or after a minute |
| Night | dim clock drifting slowly, or backlight fully off through root; a touch or a **knock on the case** (accelerometer) wakes it for a minute |
| Screen presence | off by default: after N minutes without a touch the display steps aside — **screen off** (backlight off, a touch or a knock wakes it), **Frameo's photos** (photo-frame mode) or the **home screen** — and comes back by itself: for every prayer's screens, at the "adhan soon" notice (or N min before every adhan), for a live message, an adhkar reminder ("remind" and "open"), and, if you like, **adhkar now and then** (every N min, or at random between two times, within your hours and never at night) for a while; then away again unless touched. On the frame (settings sheet → Screen), the panel or `settings.patch`; `state.presence` says why it is on and what brings it back next |
| Adhkar | Hisn al-Muslim's morning, evening, after-prayer (by prayer: the Quls 3× after Fajr and Maghrib…), before-sleep and waking adhkar plus a free tasbih: one dhikr at a time in large Amiri, tap anywhere (or OK on the remote) to count with a ring of what is left, auto-advance, swipe or ◀ ▶ to move, virtue and source, translation and transliteration, today's progress kept until midnight, streaks for morning and evening, a completion screen; from the top bar, the settings sheet, keys 1–5/0, the panel or RPC `adhkar.open` |
| Adhkar reminders | for every collection, as many times as you like: a clock time, N min after a prayer's screens (`fajr+20`, the default morning one; evening `asr+15`) or before its adhan (`maghrib-30`), on chosen days of the week; **remind** (the card on the main screen and a soft chime) or **open** (the reader opens by itself). Never over an adhan / iqama / prayer / Jumu'a screen: it waits and catches up within 45 min, then lets it go; at night only a quiet line on the night clock, a switched-off screen stays off unless the reminder may wake it; skipped when that collection is already done, never twice. Optionally a plain-text notification to your phone through your own ntfy topic. Edited on the frame (settings sheet → Reminders) or the panel |
| Adhkar log | 400 days of every collection (adhkar said, repetitions, when completed) and the tasbih, on the frame (the collections' and completion screens' Log button, the settings sheet, key 9 / Info on the remote, the panel, RPC `adhkar.open {"view":"log"}`): current and best streak of the planned collections, 30-day completion, a heatmap of the last four months, each collection's day (ring), last 7 days, 30-day rate and streak, the tasbih's week and month, the usual time of each; also in the panel and `adhkar.history` |
| Adhkar display | a second page a swipe away from the prayer times (two dots at the bottom; also the page button in the top bar, the settings sheet, Page/Channel ± on the remote, the panel, RPC `page.show`): one dhikr after another on its own, crossfading, each for its reading time (8–50 s, with a pace setting), large Amiri sized to fit; a dhikr too long for one screen is cut at its own commas into 2–3 pages, its meaning then on a page of its own, and the very longest (Al 'Imran 190–200) stay in the reader. Shows the collection and occasion, the count (× 3), the source; a tap opens it in the reader to count it, ◀ ▶ on the remote move. Clock, next prayer and countdown on top; every adhan / iqama / prayer / Jumu'a / night screen takes over as from the main screen and gives the page back. Collections and order (shuffled or in order) are yours; by default remembrance, forgiveness, salawat and the Quran's du'as, and the morning / evening / bedtime adhkar in their own time. The frame can rest on it (back to it after 15 min untouched elsewhere) |
| More adhkar | 6 collections for any time next to the daily ones: remembrance (tasbih, tahlil, "subhanallahi wa bihamdihi"… with their hadith), forgiveness (Sayyid al-Istighfar…), salawat, 15 du'as from the Quran (Rabbana…, Tanzil text), hard times (anxiety, distress, calamity, debt) and 21 everyday du'as (home, mosque, wudu, food, clothes, travel, rain, wind) with their occasion |
| Looks | 5 themes (emerald, night, sand (light), ocean, the mosque's own picture), geometric khatam background, Amiri for Arabic, 6 languages incl. Arabic right-to-left, landscape and portrait following the stand |
| Remote control | web panel on the frame (`http://<frame>:8090`, PIN) and, from anywhere, an end-to-end encrypted channel through [ntfy](https://ntfy.sh): every setting, test adhan, live message, preview of each screen, screen on/off, open photos, screenshot, reboot |

## Data sources

| | Needs | What |
|---|---|---|
| Mawaqit mosque | the mosque's slug or URL, e.g. `https://mawaqit.net/fr/grande-mosquee-de-paris` | the public page's `confData`: yearly calendar + iqama calendar, Jumu'a, Eid, the mosque's screen settings (adhan per prayer, durations, black screen…), announcements, flash message. Cached on disk; `/id/<id>/has-been-updated` checked every 3 min like Mawaqit's screens, full reload every 6 h |
| + MAWAQIT API token (optional, free account) | `source.apiToken` | `/api/2.0/mosque/search` by name or position, in the panel |
| Secondary mosques | up to 3 slugs or URLs, `source.secondary` | the same public page, only the name and the calendars kept. Cached in `files/mosque/<slug>.html`, reloaded every 6 h and when the list changes (no update polling: it would triple the traffic), fetched 15 s apart; after a failure the cached times stay (marked offline) and the next try waits 2, 4, 8 … min, at most 1 h |
| Calculation | latitude/longitude | 16 methods (MWL, ISNA, Egypt, Umm al-Qura, UOIF, Diyanet…), Shafi'i/Hanafi Asr, high-latitude rules, per-prayer offsets and iqama delays |
| Weather | – | the mosque's Mawaqit weather endpoint, or Open-Meteo by position |
| Adhan audio | – | `cdn.mawaqit.net/audio/<voice>.mp3` (6 voices, each with a Fajr version), the mosque's own recording, or Mawaqit's beep; downloaded once to `files/audio` so the adhan never depends on Wi-Fi |
| My own adhan | a recording: mp3, m4a/aac, ogg (Vorbis), wav or flac, ≤ 20 MB | up to 10, uploaded from the panel at home (drag & drop, progress bar), fetched by the frame from an https link (works remotely too), or picked on the frame ("Choose a file…": internal storage, SD card, USB stick). Checked by content and decoded once before it's kept; one for all prayers and another for Fajr if you like; the adhan screen lasts as long as the recording |

With "follow the mosque" on (default), what the mosque decided in its back office wins for the flow around each
prayer, as on its own screens; language, theme, voice and volume stay yours. Mawaqit lets anyone create a free **HOME**
place: set its times and announcements on mawaqit.net and the frame picks the changes up within 3 minutes.

## Layout

```
app/src/main/java/dev/framio/mawaqit/
  MawaqitActivity.kt, MawaqitApplication.kt   the display (draws Engine.state) · the object graph
  core/        pure Kotlin, unit-tested: Model, Settings (the remote contract), MawaqitPage (confData parser),
               MawaqitTimes, PrayerCalc, Hijri (Mawaqit's Kuwaiti algorithm), Timeline (the screen state machine),
               Effective (our settings × the mosque's), Envelope (AES-GCM for ntfy), I18n, Texts (Arabic, verbatim from Mawaqit),
               Secondary (the secondary mosques' schedule and rows), AdhanAudio (audio format sniffing, adhan file rules),
               Adhkar (the book from assets/adhkar.json, today's progress), AdhkarSchedule (each collection's own time),
               AdhkarReminders (the schedules: times, occurrences, catch-up/skip, old settings), AdhkarLog (the 400-day log:
               a day from the progress, streaks, rates, heatmap, the log screen's figures), AdhkarLogText (their words),
               AdhkarLoop (the adhkar display's settings, playlist, page cutting, reading time; the dhikr slides),
               Presence (when the display steps aside and what brings it back), PresenceText (its words)
  data/        Net (HttpURLConnection), SettingsStore, Repository (page + cache + update polling, weather, hadith, search),
               SecondaryMosques (the other mosques' pages, cached, staggered),
               AdhkarController (reader state, counts in SharedPreferences, keys, adhkar.* RPCs), AdhkarSound,
               AdhkarScheduler (what fires when, the reminder card), AdhkarLogStore (files/adhkar-log.json, written now and
               then), AdhkarPush (the optional phone notification),
               AdhkarLoopController (which page is up, where the loop is, the resting page, page.show / loop.* RPCs)
  engine/      Engine: 1 s tick → phase, adhan on the minute, brightness/backlight, knock ·
               PresenceController: away (backlight off, Frameo, home) and back, the periodic adhkar
  audio/       AdhanPlayer: cached MP3s, audio focus, volume restored, synthesized fallback chime ·
               CustomAdhans: the user's own adhans (stream to disk, sniff, decode, files/audio/custom-<id>.<ext>)
  remote/      Rpc (all commands), HttpServer (LAN panel), NtfyBridge (remote), Mdns ("Mawaqit Frame", _http._tcp)
  service/     FrameService: foreground service so all of the above runs behind Frameo and with the screen off
  system/      Device (root, backlight, IP, Frameo), Knock
  ui/          Theme, Background, MainScreen (landscape + portrait), PhaseScreens, Root (+ on-frame settings sheet),
               Mosques (the secondary mosques' slide and full-screen comparison),
               AdhkarScreens (categories, reader, completion, tasbih), AdhkarUi (hooks: top chip, reminder card), AdhkarParts,
               AdhkarStats (the log screen, Canvas only), AdhkarSchedulesScreen (the reminders, editable on the frame),
               AdhkarLoop (the pager around the main screen, the adhkar display, the dhikr slide),
               PresenceUi (the "adhan soon" notice, the sheet's Screen row)
app/src/main/assets/adhkar.json               the adhkar, 11 collections, 130 items (128 KB); where they come from: assets/ADHKAR-SOURCE.txt
app/src/main/assets/panel/index.html          the remote panel: one file, no dependencies
REMOTE.md                                     the remote API: transports, envelope, commands, state
tools/api23_check.py                          every framework call must exist on API 23 (desugared java.time allowed)
tools/adhkar_build.py, adhkar_crosscheck.py   build adhkar.json from the datasets · check it against the Quran and hadith
```

## Build, test, install

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew testDebugUnitTest assembleRelease lintDebug
python3 -I tools/api23_check.py                  # after assembleDebug; needs platforms;android-23
cp app/build/outputs/apk/release/app-release.apk /tmp/mawaqit.apk   # own name: adb pushes it as /data/local/tmp/mawaqit.apk
# the frame is shared: take your turn (see "Sharing the frame" in the root README)
../../scripts/frame-lock.sh run -w "install mawaqit" bash -c \
  'adb install -r /tmp/mawaqit.apk && adb shell am start -n dev.framio.mawaqit/.MawaqitActivity'
```

On the frame: the first screen shows a QR code, the panel address and the PIN. Tap the gear (top right), press OK on
the IR remote, or open the panel to change anything. Back leaves the display; the service keeps the adhan schedule.

## Remote control

**At home:** scan the QR code, or open `http://<frame-ip>:8090` and type the PIN. The frame also announces itself
over mDNS as "Mawaqit Frame".

```bash
PIN=123456; F=http://192.168.11.206:8090
curl -s -H "X-Pin: $PIN" -d '{"cmd":"state"}' $F/api/rpc
curl -s -H "X-Pin: $PIN" -d '{"cmd":"settings.patch","args":{"patch":{"source":{"mosque":"https://mawaqit.net/fr/grande-mosquee-de-paris"}}}}' $F/api/rpc
curl -s -H "X-Pin: $PIN" -d '{"cmd":"message.show","args":{"text":"Iftar at the mosque tonight","seconds":60}}' $F/api/rpc
curl -s -o frame.png "$F/api/screenshot?pin=$PIN"
```

**From anywhere:** turn on *Remote access* (panel → More, or the gear on the frame). The frame then keeps one
subscription to a random ntfy topic; every message on it is AES-256-GCM encrypted with a key that exists only on the
frame and in your link (`#server=…&topic=…&key=…`; the `#` part never leaves the browser). No port forwarding,
no account, and the remote control never publishes on its own (ntfy.sh limits anonymous use; the one exception is
opt-in and separate: the adhkar phone reminders, below). Browsers only allow the
encryption on HTTPS pages or a saved file, so for use away from home either:
- save the panel (More → *Save panel*) to your phone and open the file, or
- host `app/src/main/assets/panel/index.html` on any HTTPS static host and put its address in `remote.panelUrl`:
  the remote QR code on the frame then opens it directly.

Self-hosted ntfy works too (`remote.ntfyServer`). Rotating the topic and key (More → *Rotate*) cuts off old links.
Full API: [REMOTE.md](REMOTE.md).

## Notes

- The frame has no RTC: until NTP sets the clock it shows "Waiting for the clock to sync" rather than wrong times.
- Like every app here, it can't restart itself once killed (see example 03): the foreground service keeps it alive;
  open it once after boot (or from the launcher).
- The adhan MP3s are Mawaqit's, streamed from their CDN as their own web screens do; the Arabic texts are copied
  verbatim from Mawaqit's screens. Amiri font: SIL OFL (`assets/AMIRI-OFL.txt`).
- The adhkar are Hisn al-Muslim (al-Qahtani) from three openly licensed datasets, none retyped:
  [Seen-Arabic/Morning-And-Evening-Adhkar-DB](https://github.com/Seen-Arabic/Morning-And-Evening-Adhkar-DB) (MIT; morning, evening),
  [fitrahive/dua-dhikr](https://github.com/fitrahive/dua-dhikr) (MIT; after the prayer) and
  [my-prayers/muslim-data](https://github.com/my-prayers/muslim-data-flutter) (Apache-2.0; before sleep, waking).
  The anytime collections add Hisn al-Muslim's chapters on remembrance, forgiveness, salawat and distress
  (muslim-data; a dhikr carried by a hadith is cut out of the dataset's own string, the hadith kept as its virtue),
  fitrahive's daily du'as, and du'as from the Quran in the [Tanzil](https://tanzil.net) text with Pickthall's
  (public-domain) translation.
  Ayat al-Kursi, al-Baqara 285-286 and the three Quls match the Quran letter for letter; the details, the curation and
  the known variants are in `assets/ADHKAR-SOURCE.txt`. Translations are English (other languages show the English;
  in Arabic only the Arabic).
- Adhkar and screen presence: a reminder brings the display back (backlight on, in front; 15 s hold), then
  `presence.returnAfterMinutes` sends it away again unless the reader is opened; the open reader keeps it in front.
  The reader yields to any adhan/iqama/prayer screen, comes back if that lasted under 5 min, and closes after 3 min
  without a touch. A reminder set to "open" brings the display forward with the reader. At night (dim) a due
  reminder is only a quiet line on the night clock (no chime, nothing opens).
- Reminder schedules replace the old morning/evening/bedtime switches: settings saved before them become the same
  times (`fajr+<morningAfterFajrMinutes>`, `asr+<eveningAfterAsrMinutes>`, `sleepTime`), so nothing changes until you
  edit them. A frame started (or woken) long after a reminder's time shows its card quietly, without the chime.
- The adhkar log is `files/adhkar-log.json` (a few KB a month, 400 days); counting writes only the progress, the log
  follows in memory and is written 30 s after the last change, at once when a collection is completed. The first start
  with it turns the progress's completed days into log days, so the streaks carry on.
- Phone reminders (optional, off by default): the frame POSTs one short plain-text message (the collection, how much is
  said, until when) to `adhkar.pushServer`/`adhkar.pushTopic`, which you subscribe to in the ntfy app. It is **not**
  encrypted — anyone who knows the topic can read it — and nothing else of the frame is in it; only when a reminder
  fires, 12 a day at most. The encrypted remote control is a different topic and doesn't change.
- The adhkar display only advances while it is on screen (nothing runs behind a prayer screen, the photos or the
  night); it is the page Root draws where the main screen was, so night dim/black, screen presence and the reader
  work as before. Coming back, the display is on the resting page (`loop.home`); the periodic adhkar show this page.
- Screen presence replaces photo-frame mode (`frame`): settings saved with it on become `presence` with "photos" and
  the same minutes (`leadMinutes`, `returnAfterMinutes`, `idleMinutes`), with it off the policy's defaults (off); an
  old `settings.patch {"frame": …}` is translated the same way. With "photos" or "home" the night shows the dim clock
  (as photo-frame mode did); with "off" the night is dark once untouched, and only a touch, a knock, a live message,
  a reminder allowed to wake the screen (`wake`) or Fajr's own flow light it: no periodic adhkar at night. Its timers
  run on the uptime clock, so an NTP correction can't fire them.

Results on the frame: [LOG.md](LOG.md).
