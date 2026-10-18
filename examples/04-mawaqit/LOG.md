# Example 04: Mawaqit — command log

Env: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` · `adb` = `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` · frame in Europe/Amsterdam, test mosque Grande Mosquée de Paris

## Mawaqit's APIs

| Command | Output | Note |
|---|---|---|
| `curl https://pypi.org/pypi/mawaqit/json`, unzip `mawaqit-2.3.2` wheel | official client, "generated from mawaqit/api-spec v0.5.2", base `https://mawaqit.net/api`, header `Api-Access-Token` | the real, documented API |
| same, `resources/*.py` | `POST /2.0/me` (basic auth → token), `GET /2.0/mosque/search`, `/3.0/mosque/{id}`, `/2.0/mosque/{uuid}/prayer-times`, `/3.0/mosque/{uuid}/config`, `/3.0/…/hijri-date`, `/3.0/…/flash-message`, `/2.0/hadith/random` | `prayer_times.py`, `hijri.py`: iqama `+N`, Isha after midnight, Kuwaiti Hijri — ported |
| `curl 'https://mawaqit.net/api/2.0/mosque/search?word=paris'` | `401` | search needs a token |
| `curl 'https://mawaqit.net/api/2.0/hadith/random?lang=en&maxLength=300'` | `{"text":" Jabir (رَضِيَ اللهُ عَنْهُ) said: …","lang":"en"}` | no token |
| `curl -A Mozilla https://mawaqit.net/en/grande-mosquee-de-paris` | 60 KB, `confData = {…}` with 83 keys: 12-month `calendar`, `iqamaCalendar`, screen config | **everything the screens need, no token** |
| same page | `data-remote="/api/2.0/mosque/<uuid>/weather?unit=C"`, `data-remote="/id/256/has-been-updated"`, `let lastUpdated = 1790798435` | |
| `curl …/api/2.0/mosque/05b4d393-…/weather?unit=C` | `{"temperature":10,"unit":"C","feeling":"middle","icon":"sunny"}` | |
| `curl '…/id/256/has-been-updated?lastUpdatedDate=0'` | `{"hasBeenUpdated":false}` | the screens poll it every 3 min |
| mosque JS `playSound` | `cdn + "/audio/" + e + ".mp3"`; `adhan-{afassy,madina,maquah,egypt,algeria,quds}[-fajr]`, `bip` | `curl -I cdn.mawaqit.net/audio/adhan-afassy.mp3` → 200, 1.6 MB |
| page HTML, `adhkar` / `douaa-*` / `jumua-dhikr-reminder` blocks | Arabic of the dua after adhan, azkar, Ayat al-Kursi, Jumu'a hadith | copied verbatim into `core/Texts.kt` |

## Build and checks

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest` | 76 tests, 0 failures | PrayerCalc 14, Hijri 6, MawaqitTimes 14, Timeline 11, MawaqitPage 8, Envelope 9, Settings 6, Effective 8 |
| first `python3 -I tools/api23_check.py` | `MISSING class java/time/ZonedDateTime …` (desugared, fine) and **`Map.forEach(BiConsumer)`**, **`LinkedHashMap.getOrDefault`** | real API 24 calls: a `for` loop in `Net`, a plain `HashMap` cache in `CalcTimes`; checker now allows `java/time/` & co. |
| same, after | `checked 403 platform references against API 23`, exit 0 | |
| `./gradlew lintDebug` | first: `GestureBackNavigation`, `UnusedBoxWithConstraintsScope` (errors) → fixed; now 0 errors | `OnBackPressedCallback` closes the settings sheet |
| `./gradlew assembleRelease` | `app-release.apk` 1.7 MB (debug 11.7 MB) | R8 |

## On the frame

| Command | Output | Note |
|---|---|---|
| `am start -n dev.framio.mawaqit/.MawaqitActivity` | `HttpServer: panel on port 8080`, `Mdns: advertised as Mawaqit Frame`, `Adhan: cached adhan-afassy-fajr.mp3 (1927 KB)` | TLS to the CDN fine on Android 6 |
| `curl -H 'X-Pin: 000000' -d '{"cmd":"state"}' …/api/rpc` | `401` after 1.02 s | wrong PIN costs a second |
| `rpc mosque.check {"mosque":"https://mawaqit.net/fr/grande-mosquee-de-paris"}` | `{"slug":"grande-mosquee-de-paris","name":"GRANDE MOSQUÉE DE PARIS","zone":"Europe/Paris"}` | URL reduced to its slug |
| `rpc mosque.search {"word":"amsterdam"}` | `{"ok":false,"error":"token_required"}` | as designed without a token |
| `rpc settings.patch {"source":{"mosque":…}}`, `rpc state` at 00:41 | `phase night` until 06:11, `next fajr 06:41`, iqama 06:49 (`+8`) | matches mawaqit.net |
| `rpc phase.preview` × adhan, dua, countdown, iqama, azkar, jumua + `screencap` | all screens render; Amiri shapes the Quranic marks, ﷺ renders | |
| `rpc settings.patch display.language=ar` | whole layout mirrored, Arabic-Indic Hijri digits | |
| landscape screenshot | iqama line clipped at the bottom of the prayer cards; chips under the hot-corner dot | cards 206 → 226 dp, chips pushed right (`Arrangement.Absolute`) |
| English hadith slide | laid out right-to-left: the text holds Arabic honorifics | Arabic only when most letters are Arabic |
| `rpc settings.patch remote.ntfyEnabled=true`, `node e2e.js` (independent Node AES-GCM) via ntfy.sh | `state 2567ms`, `settings.patch 1404ms`, `remote.info → lan_only`; topic text never contains "settings" | **remote control from anywhere works** |
| same request re-posted, one 1 h old, plain "hello" | `dropped request (age 4 s)`, `dropped request (age 3600 s)`, nothing | replay, stale, foreign ignored |
| `rpc adhan.test fajr` at volume 10, `rpc audio.stop` after 4 s | `Output device: 0x2 (SPEAKER)`, focus requested/abandoned, `volume_music_speaker` back to `5` | |
| `rpc screen off` → `cat /sys/class/backlight/rk28_bl/brightness` | first `30`: Android rewrote it after the window brightness dropped | "off" keeps the window brightness; now `0`, still `0` after 6 s, `255` back on `auto` |
| `rpc settings.patch remote.port=8090` | `netstat`: `:::8090 LISTEN`, mDNS re-advertised | 8080 is the Hardware Lab's |
| `frame.enabled`, `idleMinutes 1`, display in front, 75 s | `photo-frame mode: idle, back to photos` → `AScreenSlidePager` | lands on the slideshow, no red notice |
| Frameo in front, `rpc message.show` 8 s | display forward, then `back to photos` | |
| headless Chrome on `http://127.0.0.1:18090/` (adb forward), PIN, Display → Ocean → Save | `save bar says: Display`, frame theme `ocean`, no console errors | panel against the real frame |
| `dumpsys meminfo dev.framio.mawaqit` (release, 40 s) · `top` | TOTAL PSS 32 MB (Java 13, native 6) · 1 % CPU | seconds ticking |
| `curl …/api/screenshot?pin=…` | `200 image/png` 179 KB | root `screencap -p` |

Shared device: other sessions were using the frame at the same time (a planned reboot at 00:48, launcher/Frameo
launches); they were told about this app and port 8090.

## White band at the bottom (Moschee Tarik ben Ziad)

| Command | Output | Note |
|---|---|---|
| `dumpsys window windows` (MawaqitActivity) | `mFrame=[0,0][1280,800]`, `mSystemUiVisibility=0x1606` | full screen, bars hidden: not the navigation bar |
| `curl -A Mozilla mawaqit.net/en/moschee-tarik-ben-ziad-frankfurt-am-main`, `confData.flash` | `"color": "#FFFFFF"`, Arabic + German typed as one run (`…العشاءDas Ishaa…`) | the ticker: white band, white text |
| fix | vivid colours fill the band with contrasting text; white/grey/black → theme surface + accent stripe; separator where the script switches; text starts clear of the hot-corner dot | `separateScripts` unit-tested on this message |
| 6 announcements: posters with a title only | a 420 px cropped image next to empty space | poster shown whole (`ContentScale.Fit`), title under it; text slides auto-size (`TextAutoSize` 18–44 sp) |
| `rpc message.show` with ~800 characters | fits between title and hint | auto-size |
| portrait | rows crowded by a second line for the Arabic name | Arabic beside the name |
| layout for any window | `FitToDesign`: designed at 1280×800 (800×1280), scaled to fit and centred, font scale pinned to 1 | scale exactly 1 on the frame; black screens cover the margins |
| `./gradlew testDebugUnitTest assembleRelease lintDebug`, `api23_check.py` | 78 tests, 0 failures · lint 0 errors · `checked 405 platform references`, exit 0 | |

## Hiding the scrolling message

The ticker distracts in a quiet room. `display.showFlash` (already in the contract) now hides it, ours and the
mosque's: `Effective.flash` returns nothing when it is off.

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest assembleRelease lintDebug`, `api23_check.py` | 79 tests, 0 failures (`showFlashOffHidesOursAndTheMosques`) · lint 0 errors · `checked 428 platform references`, exit 0 | lint crashed twice (`Unexpected failure during lint analysis`) while other agents were saving files; passed on retry |
| install, Moschee Tarik ben Ziad, `screencap` | ticker at the bottom | |
| `input swipe 640 771 640 771 900` (long-press on the ticker) | band gone at once, pill "Scrolling message hidden · turn it back on in Settings" bottom left, gone after 3 s; `settings.get` → `"showFlash":false` | the activity's ACTION_DOWN wake still runs first; `rememberUpdatedState` keeps the gesture across the 1 s recompositions |
| `input tap 640 771` (short tap) | ticker stays | |
| gear → sheet | first try: a "Messages" section in the right column pushed Adhan and the footer off the sheet | section moved under the QR code: "✓ Scrolling message", "✓ Announcements" |
| tap "Scrolling message" | chip ticked, ticker back behind the sheet, `"showFlash":true` | |
| headless Chrome on `http://127.0.0.1:18090/` (adb forward), PIN, Messages → "Show the scrolling message" off → Save, reload; then on again | save bar "Display", toggle off after reload, ticker gone on the frame; on again → ticker back; no console errors | `?mock=1` too |

## My own adhan

Test files made on the Mac: `say -o voice.aiff "Allahu akbar…"` → `afconvert -f m4af -d aac` (m4a), `-f WAVE -d LEI16` (wav);
ffmpeg `libmp3lame` (mp3), `flac`, `vorbis -strict -2` (ogg), `libopus` (Ogg Opus, must be refused); a 2-min 440 Hz wav
(10.5 MB); 21 MB of zeros; an HTML page named `.mp3`; an ID3 header followed by garbage. Played at volume 10, stopped after a few seconds.

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest` | 112 tests, 0 failures (`AdhanAudioTest` 7: sniffing, names, resolution, Fajr file, deleted id, screen length) | |
| `curl -H 'X-Pin: …' --data-binary @test.{m4a,mp3,wav,flac,ogg} …/api/adhan/upload?name=…` | 5 × `{"ok":true,"result":{"id":"f5vvbh0n","name":"Test test","seconds":5}}`, 0.3–0.6 s each; `added custom-f5vvbh0n.m4a (22 KB, 5 s, audio/mp4)` … | the extension is dropped from the name; the file is named by id |
| same, `fake.mp3` (HTML), 1 KB of random bytes, `test.opus.ogg` | `415 bad_format` "Not an audio file the frame can play" | refused by the first bytes |
| same, ID3 header + garbage | `415 bad_format` "The frame can't play this file"; log `doesn't decode: Prepare failed.: status=0x1` | MediaPlayer is the second check |
| same, 21 MB | `413 too_large` in 0.05 s | `Expect: 100-continue`: curl never sent the body |
| wrong PIN · no PIN · `Transfer-Encoding: chunked` | `401` after 1.02 s · `401` · `411` "Content-Length required" | |
| `big.wav` (10.5 MB) | first try: `HTTP 100` then nothing — another session reinstalled the app at that second (`Force stopping … replace pkg`), the next start removed `stale .upload-traj37rr.part`; again: `200`, 120 s, 2.4 s | `dumpsys meminfo` afterwards: TOTAL PSS 34 MB, as before: streamed, not buffered |
| `rpc adhan.rename {"name":"  Test MP3\u0007 (say)  "}` | `"Test MP3 (say)"` | control characters and spaces cleaned |
| `rpc adhan.fetch` with `http://…`, `https://mawaqit.net/en/`, a 404, `https://cdn.mawaqit.net/audio/bip.mp3`, `…/adhan-quds.mp3` | `bad_request` "Only https:// links" · `bad_format` · `network` "answered HTTP 404" · `{"name":"bip","seconds":1}` · `{"name":"Al-Quds from the CDN","seconds":186}` | |
| 9th, 10th upload, then an 11th and a fetch | `200`, `200`, `409 too_many`, `too_many` | |
| `curl -D - …/api/adhan/file?id=…&pin=…` · `-H 'Range: bytes=100-199'` · `bytes=999999-` · `id=../../shared_prefs/settings` · bad PIN | `200 audio/mpeg`, `Accept-Ranges: bytes`, same bytes as the upload · `206`, `Content-Range: bytes 100-199/30797`, bytes match · `416` · `404` · `401` | |
| `rpc settings.patch {"adhan":{"volume":10,"voice":"custom:4jxkubsu","fajrVoice":"custom:f5vvbh0n","custom":[]}}` | volume 10, both voices set, the 10 adhans still there | `custom` in a patch is ignored |
| `rpc adhan.test dhuhr` · `fajr` · `{"voice":"custom:jcevtxv9"}` · `{"voice":"custom:nope1234"}`, `audio.stop` after ~3 s each | `state.audio` `test:dhuhr` · `test:fajr` · `test:custom:jcevtxv9` playing; `Output device: 0x2 (SPEAKER)`; last: `not_found` | |
| `rpc adhan.delete` the main voice's file, then the Fajr one | `inUse` → `{"voice":"adhan-afassy","fajrVoice":"custom:f5vvbh0n"}`, then `"fajrVoice":"same"` | volume patched back to 60, `volume_music_speaker` back to `5` |
| Playwright on `?mock=1` (390 × 844, then 1280 × 900) and `?mock=1&remote=1` | upload with progress, `fake.mp3` refused in the browser, drag & drop of an m4a, use for all / Fajr (badges, voice selects show "My adhans"), play in the browser, test, rename (Enter), delete (confirm names what goes back to the default), a Save of the Adhan group keeps the list; remote: upload hidden, "Add from a link" open, play disabled, fetch adds "Al-Quds"; no console errors | |
| Playwright on `http://127.0.0.1:18090/` (the frame), PIN, upload `big.wav` then `test.m4a` | progress `0% 8% 11% … 96% 99%`, then "Checking the file on the frame…"; list `big 2:00`, `test 0:05`; Opus refused before upload | |
| same, ▶ on `test`, "Use for all prayers", "Test on the frame", Preview next to Voice, delete | `<audio>` from `/api/adhan/file`, `currentTime 0.57 / 4.60`; frame `inUse` `custom:en8rmkun`; log `playing custom-en8rmkun.m4a (test:custom:en8rmkun, volume 10)`; Preview plays the frame's file; after delete Voice = `adhan-afassy` | settings restored by the script |
| `adb push test.mp3 "/sdcard/Download/Frame adhan test.mp3"`, gear → sheet → "Choose a file…" | DocumentsUI (`com.android.documentsui/.DocumentsActivity`): Recent, Audio → Unknown → Download → "Frame adhan test" | internal storage is hidden by DocumentsUI's default on Android 6; SD cards and USB sticks show up as roots |
| tap the file | toast "Adding the adhan…", `added custom-8ti3t1pe.mp3 (30 KB, 5 s, audio/mpeg)`, `inUse.voice` = `custom:8ti3t1pe`, then toast "Adhan added: Frame adhan test. It now plays at every prayer." | picked on the frame, it becomes the voice |
| cleanup | test adhans deleted (`files/audio`: only `adhan-afassy*.mp3`, `bip.mp3`), pushed file removed, adhan settings as before (volume 60, `adhan-afassy`, `same`) | |
| `./gradlew assembleRelease lintDebug`, `api23_check.py` | lint 0 errors (one run crashed inside lint on another session's `AdhkarController.kt`, passed on retry) · `checked 459 platform references`, exit 0 | |

## Secondary mosques

Main mosque Moschee Tarik ben Ziad (Frankfurt); secondary: three real Frankfurt mosques found with a web search on
mawaqit.net. The user's `source.secondary` was empty before and is empty again after the tests.

| Command | Output | Note |
|---|---|---|
| `curl -A Mozilla mawaqit.net/en/<slug>` × 3 | `200`, 63–69 KB, `confData.label` = `مسجد حسان Hassan-Moschee`, `Taqwa Moschee - Frankfurt-Am-Main`, `مسجد التسامح فرانكفورت` | slugs `hassan-moschee-frankfurt-am-main`, `taqwa-moschee-frankfurt-am-main-60327-germany`, `attassamuh-moschee-frankfurt`; names mix Arabic and Latin |
| `./gradlew testDebugUnitTest` | 95 tests, 0 failures (`SecondaryTest` 9: slugs, backoff, schedule, entry, Jumu'a, highlight) | |
| `rpc mosque.check {"mosque":"https://mawaqit.net/de/hassan-moschee-frankfurt-am-main"}` | `{"slug":"hassan-moschee-frankfurt-am-main","name":"مسجد حسان Hassan-Moschee","zone":"Europe/Berlin"}` | the panel's Check reuses it |
| `rpc settings.patch {"source":{"secondary":["https://mawaqit.net/en/hassan-…", "taqwa-…", "attassamuh-…"]}}` | `secondary` back as 3 slugs (URL reduced) | arrays replace |
| `logcat -s MawaqitSecondary` | `loaded hassan… 09:15:22.8`, `loaded taqwa… 09:15:38.9`, `loaded attassamuh… 09:15:54.1` | 15 s apart; `files/mosque/<slug>.html` cached |
| `rpc state` → `secondary` | Dhuhr 13:30 / 13:40, 13:11 / 13:21, 13:16 / 13:26, `error null` (main: 13:16 / 13:26) | each mosque's own next prayer |
| landscape screenshot, slide | "Nearby mosques" over the clock: 3 rows, Fajr…Isha adhan over iqama, Jumu'a 14:30 / 14:30 / 14:00, Dhuhr lit, Fajr faded; top bar "Moschee Tarik ben Ziad  +3 mosques" | first build: names cut to one line and the lit iqama touching its frame (rows 70 px) → smaller type, no vertical padding on the name |
| `input tap` on the name → comparison, tap "Tomorrow", `KEYCODE_BACK` | 4 rows, main first with an accent stripe and "Your mosque"; Monday 19 October times; Back → main screen | a minute untouched or a tap anywhere also closes it |
| same at 52 sp | `20:0`, `13:1`: times wider than their 140 dp column | 50 sp, 146 dp columns, `softWrap = false` |
| `rpc settings.patch display.orientation=portrait` | slide: name + Jumu'a on a line, the five prayers labelled under it; comparison: 4 cards | a tap at the bottom closes it |
| `display.language=ar`, landscape and portrait | mirrored: Fajr on the right, Arabic labels, "+3 مساجد", "مساجد قريبة", date `الأحد، 18 أكتوبر، 2026` | landscape slide: the Arabic header line was taller and clipped the iqamas → line height pinned, slide padding 20 → 14 |
| headless Chrome on `?mock=1`: add Lyon by URL + Check, move up, Save; delete, Save; search (token) → "+ Secondary" | list 1–3 with names and next prayer, input disabled at 3, save bar "Times", mock `secondary` `[…19, lyon, evry]` then `[lyon, evry]`, then `[lyon, evry, strasbourg]` | no console errors; Now tab card lists each next prayer, the soonest lit |
| headless Chrome on `http://127.0.0.1:18090/` (adb forward), the real frame | Now: the 3 mosques, "Updated 33 min ago", Dhuhr 13:30 · Iqama 13:40 · in 3 h 42…; Times: the list with names and next prayers, nothing dirty | after another session's reinstall the cached pages were read back, nothing refetched (not due) |
| `./gradlew testDebugUnitTest assembleRelease lintDebug`, `api23_check.py` | 112 tests (with the other sessions'), 0 failures · lint 0 errors · `checked 461 platform references`, exit 0 | |

Shared frame: other sessions installed builds and opened their apps between these steps (Little Meadow in front,
the adhkar reminder over the clock area until 10:45); screenshots were retaken with the display in front.

## Adhkar

| Command | Output | Note |
|---|---|---|
| `gh api search/repositories?q=hisnmuslim+json` / `azkar+json` / `hisn+almuslim` …, then each repo's license | rn0x/Adhkar-json, rn0x/hisn_almuslim_json, osamayy/azkar-db, wafaaelmaandy/…: **no license**; majmoo-io: AGPL; YousefAsalya: MIT but a scrape of hisnmuslim.com, Arabic mixed with instructions, no references | not used |
| same | **Seen-Arabic/Morning-And-Evening-Adhkar-DB** (MIT): 34 items, Arabic + count + EN translation + transliteration + virtue + source (AR and EN), type 0/1/2 = both/morning/evening · **fitrahive/dua-dhikr** (MIT): after-salah with transliteration, count in "Read 33x" · **my-prayers/muslim-data** (Apache-2.0): Hisn al-Muslim chapters in a 28 MB SQLite, AR + EN + reference | the three sources of `assets/adhkar.json` (`assets/ADHKAR-SOURCE.txt`) |
| `python3 -I tools/adhkar_build.py …` | morning 26, evening 24, after prayer 15, before sleep 15, waking 4, 68 items · 82 KB (23 KB gzipped) | the before-sleep tasbih cut at its own "(ثلاثاً وثلاثين)" annotations into 33/33/34 |
| first `tools/adhkar_crosscheck.py` | Quls "not found"; me3 5 letters too many; Muslim 2692 is a fasting hadith | the API's ayah 1 carries the basmala; me3 ends with "[البقرة 285 - 286]" (moved out); fawazahmed0's Muslim is numbered differently: searched by text |
| same, after | Ayat al-Kursi ×3, 2:285-286 ×2, suras 112-114 ×2 exact; Bukhari 6307, 6324, Muslim (by text) and Mawaqit's `Texts.azkar` all found, `ALL OK` | 2 known variants kept as Hisn prints them: Sayyid al-Istighfar "وأبوء بذنبي" (Bukhari 6306: "وأبوء لك بذنبي", in all 3 datasets), "تباركت يا ذا الجلال" (Muslim 591 without "يا") |
| `./gradlew testDebugUnitTest --tests '*Adhkar*'` | 17 tests; first 2 failures: Asr reminder 16:38, not 16:33 | my arithmetic: Asr's iqama is +10, not +5; windows/DST/Isha-after-midnight/dismissal tests then pass |
| `./gradlew testDebugUnitTest assembleRelease lintDebug`, `api23_check.py` | 112 tests, 0 failures · lint 0 errors (my `ModifierParameter`, `ConfigurationScreenWidthHeight` warnings fixed) · `checked 461 platform references`, exit 0 · APK 1.92 MB | one `packageRelease` failed on a missing `classes.dex`: another session's build at the same time; rerun fine |
| install, `rpc state` at 09:34 | `adhkar.current "morning"`, `reminder {"morning", 0/26, until 10:45}` (Shuruq 07:45 + 3 h) | reminder card in the slide area, top chip "أذكار الصباح 0/26" (the display was in Arabic) |
| screencap in Arabic | RTL reader: card right, counter left, back chevron pointing right, Arabic virtue under the text, "26 / 4" | bidi reversed the counter: numbers now forced LTR |
| `rpc adhkar.open` × categories / morning / evening / tasbih, landscape, `display.language en` | 3×2 cards, morning highlighted "now", streak/after-Fajr lines; reader: Arabic auto-sized, transliteration, translation, virtue, "Abu Dawud 4/322 · At-Tirmidhi 5/567" | the reset button was pushed out of the 320 dp column: moved to the top bar |
| `input tap 450 450` ×4 on waking | ring fills, check mark, advance after 650 ms, then "COMPLETED · Upon waking · تقبّل الله منك"; `state.today.waking {4/4, complete}`; `rpc adhkar.reset` back to 0 | |
| `display.orientation portrait` | categories 2×3, reader with the ring between ◀ ▶; reminder card squeezed in a row ("Morning" cut), mosque name cut by the chip | portrait card stacks now; standing, the chip shows only glyph + "2/26" |
| `input keyevent DPAD_RIGHT` on the main screen, then `DPAD_CENTER` | the due collection opened; OK went **back to the categories** | a focused Compose button took OK: adhkar tap targets never take focus now (`adClick`) |
| same keys, new build: `2`, OK, ▶, ◀, swipe left, tap, Back, ▶ ▼ OK | evening index 0→1 (counted, advanced) →2→1→2, tap counted Ikhlas 1/3, Back → categories, focus moved to Tasbih and opened it | |
| `rpc adhkar.open evening`, no touch | `reader closed: idle` after 3 min 1 s; `state.adhkar.view null` | |
| `adhkar.sleepTime` = now, morning reminder off a moment | `reminder sleep for 2026-10-18` | announced once (no chime: the display was behind another app, photo-frame mode off); settings restored |
| reader open (evening), `rpc phase.preview adhan 8 s` | adhan screen in front, `state.adhkar.view "reader"` (suspended); 8 s later the evening reader back where it was | a real prayer flow (> 5 min) closes it instead |
| headless Chrome, `?mock=1` and the real frame | Display → Adhkar card: toggles, minutes, bedtime; "Today on the frame" with bars, "Open on frame", reminder note, "Not today"; Save bar says "Adhkar"; no console errors | |

Not verified on the frame: the chime itself (no reminder appeared while the display was in front), photo-frame mode
with a reminder (logic: a 15 s hold through `Engine.frameMode`), a reader suspended by a real adhan (unit logic only).

## All four together (flash toggle, secondary mosques, own adhan, adhkar)

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest assembleRelease lintDebug`, `api23_check.py` | 112 tests, 0 failures · lint 0 errors · `checked 459 platform references`, exit 0 · release 1.92 MB | |
| `scripts/frame-lock.sh run -w "mawaqit final check" …` | `waiting 15 s, frame held by framio-7a/pomodoro · pomodoro M2 release measurements` → `got the frame after 27 s` | shared lock with the other sessions |
| `rpc adhkar.open morning`, two taps | reader `12 / 26`, ring 5 left of ×7, Ibn As-Sunni 71 · Abu Dawud | |
| `source.secondary` = Hassan + Attassamuh, slides every minute, sampled every 10 s | "Nearby mosques" slide in Arabic landscape: both rows, adhan over iqama, each one's Asr highlighted, Jumu'a column | earlier shots fell between two slides |
| `display.language=ar`, English hadith slide | full stop and quotes at the wrong end: the paragraph took the screen's RTL | non-Arabic slide and message text forced LTR; `message.show` with the same hadith reads correctly |
| new `display.showMosquesSlide` (panel: Display → Slides) | the secondary mosques leave the rotation, still one tap away on the mosque name | |
| `logcat \| grep -c "FATAL EXCEPTION"` | `0` | settings restored after the test |

## Adhkar display (second page), dhikr slides, six more collections

| Command | Output | Note |
|---|---|---|
| `muslim-data` chapters (`azkar_chapter_translation`), fitrahive `daily-dua`/`selected-dua` | Hisn's 133 chapters with Arabic and English names; ch. 131 (virtue of dhikr, 12), 130 (istighfar, 6), 23/108 (salawat), 35/36/54 (distress); fitrahive 38 daily du'as with transliteration | ch. 131 items are hadith *carrying* the dhikr: cut by word positions, the hadith kept as the virtue |
| `curl api.alquran.cloud/v1/ayah/<s>:<a>/editions/quran-simple,quran-simple-clean,en.pickthall` × 20 | the Tanzil text with the pause marks as separate words (`ۖ`) | partial ayat start at the du'a's own first word |
| `python3 -I tools/adhkar_build.py … <quran dir>` | dhikr 8, istighfar 7, salawat 3, quran 15, distress 10, everyday 21; 130 items, 128 KB (36 KB gzipped); the old 68 items byte for byte | first run: my Quran loop reused the name `en` and broke the transliteration pass; fitrahive's "al-Bukhari" came out "al-Al-Bukhari" (fixed, old items unchanged) |
| `tools/adhkar_crosscheck.py … <quran dir>` | 15 Quranic du'as inside their ayat; hm274/276/277/282/283/280/58/142/174/164 in Sahih Muslim; `ALL OK` | hm57 (Abu Bakr) at first not found: Muslim 2705 writes "ظلما كبيرا - وقال قتيبة كثيرا -", a note inside the text; now checked as its two halves |
| `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`, `api23_check.py` | 141 tests (with adhkar-log's), 0 failures (`AdhkarLoopTest` 8: settings, time-aware, split, meaning page, reading time, rounds, slides) · lint 0 errors · `checked 504 platform references`, exit 0 · release 2.08 MB | |
| install, `rpc page.show adhkar`, Arabic landscape | strip: clock + date on the right, "العصر بعد 1:22:44" and adhan/iqama on the left, reminder chip, gear; card: Quranic du'a 3:16 large, "سُورَةُ آلِ عِمْرَانَ 3:16", dots at the bottom | at start a dhikr slide (59:10) was already up on the main screen: first slot of the rotation |
| English, `loop.collections ["distress"]`, sequential, `loop.next` ×3 | hm140 (408 letters) on 2 pages "1 / 2", "2 / 2" cut after a comma, then a MEANING page with the whole translation; hm141 with its translation under the Arabic | auto-sized, no clipping |
| `display.orientation portrait`, en and ar | 3:16 with its translation; hm216 in Arabic only; dhikr slide hm275 "10 ×" | the count pill read "10 ×" in Arabic: an LRM before "×" |
| `rpc slide.adhkar` landscape en | 18:10 with Pickthall's translation over the clock, prayers below | `state.slide {"kind":"adhkar","id":"q18_10"}` |
| loop page, `rpc phase.preview adhan 10 s` | the adhan screen over it, `state.page "adhkar"`, 13 s later the same dhikr back on the adhkar display | `phase.preview night 8 s`: the dim night clock, then the display again |
| `input swipe` right then left (en) | main screen, then the adhkar display; `page main (swipe)`, `page adhkar (swipe)` in logcat | |
| `adhkar.open` en landscape / portrait fr | 12 cards 4 × 3 (3 × 4 standing), names stepping down ("Douas du quotidien"), streak flames, "after Dhuhr"; everyday du'a "Leaving home" with its title, transliteration, virtue; Arabic: the Hisn chapter as title | |
| `loop.home adhkar`, `returnMinutes 1`, Back + `am start` | `page adhkar (shown)`; `page.show main`, 61 s later `page adhkar (idle)` | settings back to `home main`, 15 min |
| Arabic, IR `PAGE_DOWN`, `DPAD_LEFT` ×2, `DPAD_RIGHT` | first build: index 0 → 1 → 2, then ▶ jumped to the prayer times | Compose's focus search scrolled the pager; the pages now refuse D-pad focus: ◀ ◀ ▶ ▶ = 1, 2, 1, 0, `PAGE_UP` main, ◀ on main opens the collections as before; English `CHANNEL_UP`/▶/◀/`CHANNEL_DOWN` alike |
| tap on the loop's dhikr | the reader on that dhikr (Remembrance 3 / 8) | |
| untouched 60 s, sampled every 10 s | hm274 (8.3 s) → 59:10 (22.9 s) → 25:74 (16.9 s) → hm270 | |
| headless Chrome on `?mock=1`, Display tab | "Adhkar display" card: switch, rests on (segmented), back after, 11 collection chips, time of day, order, pace, translation, transliteration, Show it on the frame / Prayer times / Next; Slides: "Adhkar now and then" + how often + "Show one now"; ticking Hard times → save bar "Adhkar display"; no console errors | |
| `logcat \| grep -c "FATAL EXCEPTION\|ANR in dev.framio.mawaqit"` | `0` | the user's settings (ar, emerald, auto, slides 4 min / 25 s…) the same as before |

Not verified on the frame: the time-aware switch at a window's edge (unit logic; `state.loop.collections` showed the mix
this afternoon), photo-frame mode bringing the display forward onto `home: adhkar` (same `onShown` path as Back +
start), a real adhan over the loop page (the preview takes the same branch of Root).

## Adhkar log and reminder schedules

Shared frame with the adhkar-display session and others (every burst through `scripts/frame-lock.sh`); the user's
settings read first and compared after: identical. History for the screenshots seeded in the debug build
(`adhkar.history.seed 130` → 127 made-up past days), removed again (`adhkar.history.unseed` → 127); the user's own
day (morning 11/26, before sleep 1/15, tasbih 49) was kept throughout.

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest` | `AdhkarLogTest` 10, `AdhkarRemindersTest` 10, `AdhkarScheduleTest` 8 (rewritten for the schedules), 0 failures | day from the progress, migration keeps the streaks, perfect-day streak with unplanned days, rates from the log's first day, heatmap cells, usual time, 400-day bound, JSON round trip; times `fajr+20` / `maghrib-30` / clock, DST gap, weekdays, catch-up then skip, old settings → same schedules |
| first install, `rpc adhkar.history` | the log started from the progress: 1 day, `morning [11,26,24,364,…]` | the share by repetitions (24 of 364: the 100× tasbih dominates) made 11/26 look like 7 % → each dhikr weighs the same now (`part`, 43 %) |
| `rpc adhkar.open {"view":"log"}` landscape, Arabic, emerald | mirrored: today newest at the left of the heatmap, weekdays on the right; the 30-day tile's label cut, the next-reminder line clipped under the title | third tile wider and re-stacked; header grows with Amiri; Arabic lines keep their full height (`LineHeightStyle.Trim.None`) |
| same, seeded, English · French portrait · sand · ocean · night | streak 2 / best 5 / 69 %, heatmap Jun–Oct, morning 12 in a row, 76 % in 30 days, usual 07:42 / 17:23 / 23:07, tasbih 544 / 1897; portrait: the Arabic title ran into the Reminders button | Arabic subtitle only in landscape |
| `adhkar.open {"view":"schedules"}`, tap "+ Add" (ar, sand) | list "بعد الفجر بـ 20 دقيقة", editor: 11 collections, clock / around a prayer, Fajr…Isha, after/before ±5, days, remind/open, phone, wake | the migrated defaults: `morning fajr+20`, `evening asr+15` |
| `adhkar.schedule.add waking 14:49 remind`, `istighfar 14:50 open` | `fired …/waking/zt1 at 14:49 (due 14:49): reminded`, card "أذكار الاستيقاظ 0/4 · حتى 16:49"; `fired …/istighfar/zt2 at 14:50: opened`, reader at 1/7 | yesterday's occurrences were logged as "skipped" → now dropped silently once their card time is over |
| `istighfar 14:55 open` under `phase.preview adhan 100 s` | `fired … at 14:56:31 (due 14:55): opened` | waits for the screen, catches up |
| `waking 15:00 open, push` under `phase.preview night 50 s` | `fired … night: quiet line, phone`; night clock with "أذكار الاستيقاظ · ابدأ", nothing opened | |
| `adhkar.push.test`, then the 15:00 reminder, `curl ntfy.sh/<random topic>/json?poll=1` | `{"title":"الأذكار","message":"تجربة من شاشة مواقيت…"}`, `{"title":"أذكار الاستيقاظ","message":"أذكار الاستيقاظ: 0/4 · حتى 17:00"}` | JSON publish keeps Arabic titles; topic and push turned off again |
| `input keyevent KEYCODE_9` on the main screen · `KEYCODE_MENU` (gear sheet) | `view "log"` · the sheet's Adhkar row was cut off at the bottom | the sheet's right column scrolls now: Log, Reminders · 2 |
| `am force-stop`, start, `adhkar.history` | 128 days before and after | `files/adhkar-log.json` 23 KB with 128 days |
| Chrome on `?mock=1` (430 px) and on the frame (`adb forward`) | Display → Adhkar: Reminders editor (add, collection, before Maghrib 20, Fri, open → Save "Adhkar" → saved), phone settings, Log: 4 tiles, SVG heatmap, 7/30-day bars, tasbih bars; frame: the same figures as on the screen | `adhkar.history {"days":126,"detail":false}` 6.3 KB, 923 B deflated: one ntfy message |
| `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`, `api23_check.py` | 141 tests (both sessions), 0 failures · lint 0 errors · `checked 504 platform references`, exit 0 · release 2.08 MB | |
| `logcat \| grep -cE "FATAL EXCEPTION\|ANR in dev.framio"` | `0` | |

Not verified on the frame: the chime itself by ear (logged as fired with the display in front; `reminder chime` is
logged since), photo-frame mode with an "open" schedule (same hold + `showDisplay` path as the RPC), a schedule waking a
black night screen (`wake`), a real adhan over a due schedule (the preview takes the same `isFullScreen` branch), a
reminder across the DST change (unit tests only).

## Screen presence (away after idle, back by itself)

Shared frame (pomodoro session testing at the same time, every burst through `scripts/frame-lock.sh`); someone was
also using the frame by hand during the run (real touches, the settings sheet's Screen row). The user's settings read
first and restored after: identical except the migration (`frame` → `presence`, off) and the two new adhan keys.

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest` | `PresenceTest` 19, 160 tests in all, 0 failures | JSON round trip and clamping, `frame` → `presence` (on/off/both/none), an old `frame` patch, holders per phase, lead minutes, night per away mode, the toggles, idle vs return times, every N within hours, random bounds (seeded, both ends hit), hours across midnight, no periodic at night, minutes before the adhan per prayer, the reminder phase per prayer, Arabic plurals |
| first install over the old settings | `presence` `{"enabled":false,…}`, no `frame` in `settings.get` | the user's photo-frame mode was off: the policy's defaults |
| `adhan.reminderMinutes 20` (the user's), Asr 16:00, at 15:46 | top bar: "العصر بعد 14 دقيقة · الأذان 16:00" with a bell, the mosque name back after the adhan | `notice-ar.png` |
| `presence {enabled, away off, idleMinutes 1}` | `Presence: away (idle): screen off` after 60 s, `state.screen.state off`, `/sys/class/backlight/rk28_bl/brightness` `0`; `input tap` → `awake: touch`, `255` | |
| same, a knock 1 s after going off, then many real touches on a black screen | backlight stuck at `0` while the state said on | two "off"/"on" root writes raced (on landed first) → the backlight writes are serialized (Mutex); the first accelerometer samples after registering read as a knock → knocks ignored for 2 s after listening starts; a backlight found at 0 with nothing saved (killed while dark) is put back on at start |
| Asr adhan with the policy on, the display brought back by another app's launch | `Presence: back: adhan` 15× a second, HTTP panel unanswered | `come()` republished the state every tick while away → it acts only on a change and asks for the activity once a minute (another app over a prayer screen isn't fought) |
| after the fix: 16:00 adhan … azkar, then | `reason adhan` throughout, `away (idle): screen off` once the azkar ended + 1 min | the flow played normally |
| `message.show` 20 s while off | lit, the message, `reason message`; off again 1 min after it ended | `message-wake.png` |
| `evening asr+15` (16:40, remind) while off | `reason reminder` at 16:40:01, off again 16:41:16 (15 s hold + 1 min) | adhkar-log's hook `presence(category, opens)` |
| `away photos`, idle 1 min | `AScreenSlidePager` in front; `nextWake {adhkar 16:50:42}` | `away-photos.png` |
| `periodic every 5, showSeconds 30` from the photos | 16:50:43 `back: adhkar`, `page adhkar (presence adhkar)`, 16:51:13 `page main (presence away)`, `away (after adhkar): photos` | the loop's own "shown → resting page" no longer undoes the adhkar page |
| `away home`, `presence.away`; a salawat schedule in `open` mode 3 min later | launcher in front; 17:02 `fired …/salawat/t3: opened` | the display was in front again by hand before it fired (touches on the frame) |
| `from 18:00 to 21:00` while away at 17:03 | `nextWake {at 18:00, reason adhkar}` (was 17:08:41) | out of hours: the next `from` |
| `phase.preview reminder` while off | lit, `reason before_adhan`, backlight 255 | |
| the sheet (Arabic) | Screen: "تعمل دائمًا · إطفاء · الصور · الشاشة الرئيسية", "بعد 5 دقائق دون لمس", "تنبيه قبل الأذان بـ 20 دقيقة", "أذكار كل 5 دقائق", "✓ الرسائل توقظها", "✓ التذكير يوقظها" | `preview-reminder-wake.png` |
| Chrome on `?mock=1` | Display → "When nobody is looking": away segments, minutes, periodic random 30–90, hours, "On screen (touched) · screen off at 16:12 · back for a prayer at 16:40", Step aside now / Wake it; Adhan → "Adhan soon" with the five prayers and the chime | no console errors |
| `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`, `api23_check.py` | 160 tests, 0 failures · lint 0 errors · `checked 509 platform references`, exit 0 · release 2.10 MB | |
| `logcat \| grep -cE "FATAL EXCEPTION\|ANR in dev.framio"`, `dumpsys dropbox` | `0`, no mawaqit crash or ANR | |

Not verified on the frame: a real "adhan soon" phase waking a switched-off screen (the preview of that phase did; the
real 15:55 one found the display already lit by touches), an "open" reminder arriving while away (the remind one did;
both take the same hook), the night rules with the policy on (unit tests: no holder at night for "off", the night
clock for "photos"/"home", no periodic at night), `leadMinutes`, and the knock wake by a real knock.

## All three together (adhkar display, adhkar log and reminders, screen presence)

| Command | Output | Note |
|---|---|---|
| `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`, `api23_check.py` | 160 tests, 0 failures · lint 0 errors · `checked 509 platform references`, exit 0 · release 2.10 MB | |
| `adb install -r` the release over the agents' builds | `Success`, settings, log and today's progress kept | |
| `page.show adhkar` · `adhkar.open {"view":"log"}` · `{"view":"schedules"}` · `adhkar.open istighfar` | loop page (Arabic, countdown to Isha in the strip) · the real log (morning 11/26, evening 5/24, tasbih 49) · the two migrated schedules (Fajr + 20, Asr + 15) · reader 1/7, Sayyid al-istighfar | `slide.adhkar` during the evening reminder card: no slide, as designed |
| `presence` on, `away: off`, `presence.away` | `away (remote): screen off`, backlight `0` from 2 s on, `state.presence.asleep: true`, next wake `before_adhan` | |
| `message.show` while asleep | `back: message`, backlight `255`, `reason: message` | policy off again, settings identical to before the test |
| `logcat -b crash` | nothing for dev.framio.mawaqit | |
