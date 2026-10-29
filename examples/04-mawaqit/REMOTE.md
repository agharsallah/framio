# Remote control API

Two transports, one set of commands (RPC). The web panel (`app/src/main/assets/panel/index.html`) speaks both.

| | At home (LAN) | From anywhere |
|---|---|---|
| Transport | HTTP on the frame, `http://<frame-ip>:8090` | an [ntfy](https://ntfy.sh) topic (public server or self-hosted) |
| Auth | 6-digit PIN shown on the frame's settings screen, header `X-Pin` | knowing the 256-bit key: every message is AES-GCM encrypted |
| Panel | served by the frame at `/` | the same file, hosted anywhere (or opened from disk) with `#server=…&topic=…&key=…` |

## LAN

```
GET  /                     the panel (no auth; it asks for the PIN)
POST /api/rpc              {"cmd": "...", "args": {...}}  header X-Pin: 123456
                           → 200 {"ok": true, "result": {...}} | {"ok": false, "error": "code", "message": "..."}
                           → 401 {"ok": false, "error": "pin"} (wrong/missing PIN, answered after 1 s)
GET  /api/screenshot?pin=  image/png of the screen right now (needs root)
POST /api/adhan/upload?name=<url-encoded>   header X-Pin, raw body = the audio file, Content-Length required
                           → 200 {"ok": true, "result": CustomAdhan} | 401 pin | 409 too_many | 411 (no length / chunked)
                             | 413 too_large | 415 bad_format, too_long | 400 bad_request
GET  /api/adhan/file?id=&pin=   the stored file (audio/mpeg, audio/mp4, audio/ogg…), one "Range: bytes=a-b" honoured (206)
```

The upload is streamed to a temporary file in `files/audio` (never held in memory), at most 20 MB and 5 minutes;
with `Expect: 100-continue` (curl sends it above 1 MB) a wrong PIN, a full list or a too large Content-Length is
refused before the body is sent. The content decides, not the name: it must start like mp3, m4a/aac (MP4 or ADTS),
Ogg Vorbis, wav or flac (`core/AdhanAudio.kt`; Ogg Opus needs Android 10) and MediaPlayer must prepare it. The file
is then renamed `custom-<8 random [a-z0-9]>.<ext>`; `name` is only a label (≤ 60 characters, extension dropped).

```bash
curl -s -H 'X-Pin: 123456' --data-binary @adhan.mp3 'http://192.168.11.206:8090/api/adhan/upload?name=My%20adhan'
curl -s -H 'X-Pin: 123456' -d '{"cmd":"state"}' http://192.168.11.206:8090/api/rpc
curl -s -H 'X-Pin: 123456' -d '{"cmd":"adhan.test","args":{"prayer":"fajr"}}' http://192.168.11.206:8090/api/rpc
curl -s -H 'X-Pin: 123456' -d '{"cmd":"settings.patch","args":{"patch":{"display":{"theme":"night"}}}}' http://…/api/rpc
```

## ntfy

The frame keeps a streaming subscription to `GET {server}/{topic}/json?since=<last id>` and answers on the same topic.
Each message body is an envelope (see `core/Envelope.kt`):

```
"fx1." + base64url_nopad( iv[12] ‖ AES-256-GCM(key, iv, aad = utf8(topic), deflateRaw(utf8(json))) ‖ tag[16] )
```

- `key`: 32 random bytes, base64url without padding (`settings.remote.ntfyKey`).
- `deflateRaw` = raw DEFLATE, no zlib header (`CompressionStream("deflate-raw")` in the browser, `Deflater(…, nowrap = true)` on the frame).
- Plain JSON inside:
  - request (panel → frame): `{"v":1, "t":"rpc", "id":"<random 12+ chars>", "ts":<ms epoch>, "cmd":"...", "args":{...}}`
  - response (frame → panel): `{"v":1, "t":"res", "id":"<same id>", "ts":<ms>, "ok":true, "result":{...}}` or `"ok":false, "error":"code", "message":"..."`
- The frame drops requests older than 10 minutes or more than 2 minutes in the future, and ids it has seen.
- ntfy.sh limits a message to 4096 bytes and anonymous users to a few hundred messages a day: the frame never publishes
  on this topic on its own, it only answers. `remote.*` commands and the screenshot are LAN-only.
- Separate from all this, and only when the user turns it on (`settings.adhkar.pushEnabled`): the adhkar phone
  reminders, published **in clear** to another topic (`adhkar.pushTopic`), see [Adhkar reminders](#adhkar-reminders-and-log).

Publishing from the browser: `fetch(server + "/" + topic, {method: "POST", body: envelope})`.
Receiving: `new EventSource(server + "/" + topic + "/sse?since=" + sinceUnixSeconds)` → `JSON.parse(e.data).message`.

## Commands

| cmd | args | result |
|---|---|---|
| `state` | – | [State](#state) |
| `settings.get` | – | `{"settings": Settings}` |
| `settings.patch` | `{"patch": {...}}` deep-merged (objects merge, arrays replace) | `{"settings": Settings}` |
| `mosque.check` | `{"mosque": "slug or mawaqit.net URL"}` | `{"slug","name","address","zone"}` or error `not_found` |
| `mosque.search` | `{"word": "..."}` or `{"lat": 52.3, "lon": 4.9}` | `{"mosques": [{"slug","name","address","distanceM"}]}`; error `token_required` without `source.apiToken` |
| `refresh` | – | `{}` reloads the mosque now (and the secondary mosques, 15 s apart) |
| `adhan.test` | `{"prayer": "fajr"\|"dhuhr"\|…}` (optional), `{"voice": "custom:<id>"\|"adhan-madina"\|…}` (optional) | `{}` plays the adhan that prayer would play, or that voice; `not_found` for an unknown voice |
| `adhan.list` | – | `{"custom": [CustomAdhan], "inUse": {"voice", "fajrVoice"}}` |
| `adhan.fetch` | `{"url": "https://…", "name": "…"}` (name optional: the file's name) | `CustomAdhan`: the frame downloads the link (https only, ≤ 20 MB, 75 s) and checks it like an upload. The way in through ntfy, whose messages can't carry a file; give it ~2 min |
| `adhan.rename` | `{"id", "name"}` | as `adhan.list` |
| `adhan.delete` | `{"id"}` | as `adhan.list`; the file is removed, and a `voice` that used it goes back to `adhan-afassy`, a `fajrVoice` to `same` |
| `audio.stop` | – | `{}` |
| `adhan.skip` | – | `{"skipped": bool}`: stops the adhan and ends its screen (what follows it comes now); a previewed adhan screen closes |
| `adhan.volume` | `volume` 0–100 or `delta` ±N, and/or `muted` bool | `{"playing", "volume"}`: the playing adhan's volume now, kept as `adhan.volume`; `muted` silences this adhan only |
| `message.show` | `{"text": "...", "title": "...", "seconds": 30}` | `{}` full-screen message right now |
| `phase.preview` | `{"kind": "adhan"\|"dua"\|"countdown"\|"iqama"\|"prayer"\|"azkar"\|"jumua"\|"night", "seconds": 20}` | `{}` shows that screen (no sound) |
| `screen` | `{"mode": "on"\|"off"\|"auto"}` | `{}` force the backlight on/off, or back to the schedule |
| `open` | `{"app": "display"\|"photos"}` | `{}` brings the prayer display or Frameo to the front (the display: as if opened by hand) |
| `presence.wake` | – | `{}` the display back now (backlight on, in front), as after a touch: `presence.idleMinutes` from now |
| `presence.away` | – | `{}` steps aside now as `presence.away` says, as if the idle time were over; `bad_request` while `presence.enabled` is false |
| `remote.info` | – (LAN only) | `{"ntfyServer","ntfyTopic","ntfyKey","link"}` |
| `remote.rotate` | – (LAN only) | same, with a new topic and key (old links stop working) |
| `adhkar.open` | `{"category": "morning"\|"evening"\|"after_prayer"\|"sleep"\|"waking"\|"dhikr"\|"istighfar"\|"salawat"\|"quran"\|"distress"\|"everyday"\|"tasbih"}`, none = the collections | `{}` opens the adhkar on the frame (brought to the front), at the first dhikr not yet said today |
| `adhkar.close` | – | `{}` back to the main screen |
| `adhkar.dismiss` | – | `{}` no more reminder of the due collection today ("Not today") |
| `adhkar.reset` | `{"category": …}` | `{}` today's counts of that collection (or the tasbih) back to zero |
| `adhkar.open` | `{"view": "log"\|"schedules"}` | `{}` the log screen or the reminder schedules on the frame |
| `adhkar.history` | `{"days": 120, "detail": true}` (1–400) | `{"today", "first", "stats": Stats, "days": [{"date", "score", "tasbih", "collections": {id: share}, "sessions": {…}}]}`, newest first, days with an entry only; `"detail": false` keeps `date`, `score`, `tasbih` (fits one ntfy message for 126 days) |
| `adhkar.schedule.list` | – | `{"schedules": [AdhkarTime + "next": ms\|null], "next": Next}` |
| `adhkar.schedule.add` | `{"schedule": AdhkarTime}` (no `id`: a new one; an existing `id`: replaced) | as `adhkar.schedule.list`; `bad_request` for an unknown collection or time, `too_many` past 24 |
| `adhkar.schedule.remove` | `{"id"}` | as `adhkar.schedule.list`; `not_found` |
| `adhkar.push.test` | – | `{}` one test message to `adhkar.pushTopic` now (on or off); `bad_request` without a topic, `too_many` when today's 12 are used |
| `page.show` | `{"page": "main"\|"adhkar"}` | `{}` the prayer times or the adhkar display (closes an open adhkar view); `bad_request` for `adhkar` while `loop.enabled` is false |
| `loop.next` / `loop.previous` | – | `{}` the adhkar display's next / previous page |
| `slide.adhkar` | – | `{}` a dhikr slide on the main screen now (switches to it), for `display.slideSeconds`; `not_found` when no collection of `loop.collections` has a short one |
| `device.reboot` | – | `{}` reboots the frame (root) |

Error codes: `pin`, `bad_request`, `unknown_cmd`, `lan_only`, `token_required`, `not_found`, `network`, `no_root`, `internal`;
for the user's adhans also `too_large`, `too_many` (10 already), `bad_format` (not one of the formats, or doesn't decode),
`too_long` (over 30 min).

### My own adhans

`CustomAdhan` = `{"id": "k3v9x0qa", "name": "Grandpa", "seconds": 95}` (`seconds` measured with MediaPlayer when stored).
The list is `settings.adhan.custom`, readable with `settings.get` but owned by the `adhan.*` commands and the upload:
`settings.patch` ignores `adhan.custom`, so a panel saving a stale copy can't drop files. Choose one with
`settings.patch {"adhan": {"voice": "custom:<id>"}}` (every prayer; with `fajrVoice: "same"` Fajr too, there is no
separate Fajr version) and/or `{"adhan": {"fajrVoice": "custom:<id>"}}`; an id that isn't in the list is refused
(the voice falls back to its default). The adhan screen then lasts the recording's `seconds` (10–600), whether or not
the mosque's screen settings are followed: the voice is always ours, so is its length. It is one length for all
prayers, the main `voice`'s, so a different Fajr file keeps that length.

## State

```json
{
  "app": "framio-mawaqit", "version": "0.1",
  "now": 1792274621000, "zone": "Europe/Amsterdam", "timeSynced": true,
  "device": {"ip": "192.168.11.206", "port": 8090, "uptimeMs": 4641122, "root": true},
  "source": {"kind": "mawaqit", "mosque": "grande-mosquee-de-paris", "name": "GRANDE MOSQUÉE DE PARIS",
             "address": "2bis Place du Puits de l'Ermite, 75005 Paris", "updatedAt": 1792274000000, "error": null},
  "phase": {"kind": "normal", "prayer": null, "end": 1792279000000},
  "next": {"prayer": "asr", "adhan": 1792279000000, "iqama": 1792279480000},
  "today": [{"prayer": "fajr", "adhan": 1792230000000, "iqama": 1792231000000}, "… imsak/shuruq/jumua/eid when shown"],
  "hijri": "26 Rabi' al-Thani 1448",
  "screen": {"mode": "auto", "state": "on"},
  "foreground": "display",
  "weather": {"temp": 12, "unit": "C"},
  "audio": {"playing": false, "what": null, "volume": 60, "muted": false},
  "adhkar": {"enabled": true, "open": true, "view": "reader", "category": "morning", "index": 6, "current": "morning",
             "reminder": {"category": "morning", "done": 6, "total": 26, "until": 1792313100000},
             "today": {"morning": {"done": 6, "total": 26, "complete": false, "streak": 3, "after": null},
                       "after_prayer": {"done": 0, "total": 13, "complete": false, "streak": 0, "after": "dhuhr"}, "…": "evening, sleep, waking"},
             "tasbih": 99, "stats": {"streak": 2, "best": 5, "rate30": 0.69, "days": 128, "goals": ["morning", "evening"],
             "tasbih": {"today": 49, "week": 544, "month": 1897, "last14": [99, 0, "…"]}},
             "next": {"id": "evening", "category": "evening", "at": 1792334400000, "mode": "remind"}},
  "page": "adhkar",
  "loop": {"page": "adhkar", "collections": ["dhikr", "istighfar", "salawat", "quran"], "index": 4, "total": 33, "round": 2,
           "current": {"id": "hm216", "category": "istighfar", "part": 1, "parts": 1, "meaning": false, "seconds": 17.1}},
  "slide": {"kind": "adhkar", "id": "hm275", "category": "dhikr"},
  "presence": {"enabled": true, "awake": true, "reason": "adhan", "awayMode": "off", "asleep": false,
               "awayAt": 1792333500000, "nextWake": {"at": 1792334400000, "reason": "reminder"}},
  "secondary": [{"slug": "hassan-moschee-frankfurt-am-main", "name": "مسجد حسان Hassan-Moschee",
                 "next": {"prayer": "dhuhr", "adhan": 1792321800000, "iqama": 1792322400000},
                 "updatedAt": 1792307722000, "error": null}]
}
```

Times are milliseconds since the epoch; show them in `zone`. `foreground` is `display`, `photos` or `other`.

`adhkar`: `view` is `categories`, `reader`, `done`, `tasbih` or null (closed); `index` the dhikr shown (0-based);
`current` the collection whose time it is (morning: Fajr → Shuruq + 3 h, evening: Asr → Maghrib + 1 h, sleep:
the first enabled `sleep` schedule + 3 h); `reminder` the one shown on the main screen, null when none is due, done or
dismissed. `today.<id>.done` counts the adhkar said their full number of times; counts reset at local midnight (a
before-sleep session past midnight stays on the day it started). `after_prayer` is per prayer: `after` names the last
one. `streak` (morning, evening): days in a row completed (from the log, so it goes back 400 days). `tasbih`: today's
free count. `stats`: the log's figures without the per-collection part (`streak`, `best`, `rate30`, `days`, `goals`,
`tasbih` — see Stats below). `next`: the next reminder still to come today, `{"id", "category", "at", "mode"}` or null.
Settings: `settings.adhkar` (`enabled`, `schedules`, `chime`, `translation`, `transliteration`, `tapSound`,
`pushEnabled`, `pushServer`, `pushTopic`).

### Adhkar reminders and log

`settings.adhkar.schedules` is a list (at most 24; `settings.patch` replaces it whole, or use `adhkar.schedule.*`) of
`AdhkarTime`:

```json
{"id": "evening", "category": "evening", "at": "asr+15", "days": [], "mode": "remind", "enabled": true, "push": true, "wake": false}
```

- `at`: `"HH:MM"`, or `"<prayer>+N"` = N min (0–240) after that prayer's screens (adhan … azkar) are over, or
  `"<prayer>-N"` = N min (≤ 180) before its adhan; prayers `fajr`, `shuruq`, `dhuhr`, `asr`, `maghrib`, `isha`. A clock
  time in the spring DST gap moves after it.
- `days`: ISO days of the week, 1 = Monday; empty (or all 7) = every day.
- `mode`: `remind` (the main screen's card, the chime) or `open` (the reader opens by itself, the display comes forward).
- `push`: also the phone notification, when `pushEnabled`. `wake`: light a screen the night switched off (black mode).
- Each occurrence fires once, at its time or, when an adhan / iqama / prayer / azkar / Jumu'a screen, a message or the
  open reader is in the way, as soon as it's gone, within 45 min; later it is skipped. At night (dim) it is only the
  night clock's quiet line. Nothing fires for a collection already completed or dismissed that day. The card shows
  until the collection's own time ends when `at` is in it (morning: Shuruq + 3 h, evening: Maghrib + 1 h, sleep: 3 h),
  else for 2 h. The first enabled `sleep` schedule also starts the before-sleep time (`current`).
- Settings from before the schedules (`morningReminder`, `morningAfterFajrMinutes`, `eveningReminder`,
  `eveningAfterAsrMinutes`, `sleepTime`) are read once into `morning` `fajr+20`, `evening` `asr+15` (their minutes and
  on/off) and `sleep` `"HH:MM"`; the default is the first two.

Phone reminders: with `pushEnabled` and a `pushTopic` (letters, digits, `-`, `_`), each fired reminder whose `push` is
on is POSTed as JSON to `pushServer` (default `https://ntfy.sh`): `{"topic", "title": "Evening adhkar", "message":
"Evening adhkar: 0/24 said · until 19:45", "tags": ["crescent_moon"]}`, in the display's language. **Not encrypted**
(the phone must read it): only the reminder's text, at most 12 a day. Subscribe to the topic in the ntfy app.

`Stats` (`adhkar.history`, partly in `state`): `streak` / `best`: days in a row with every planned collection
completed (planned = the collections with an enabled schedule that day; without any schedule, morning and evening);
`rate30`: their mean 30-day completion; `days` in the log; `goals` today; `tasbih` `{"today", "week", "month",
"last14"}`; `collections.<id>`: `today` (0–1, each dhikr weighing the same), `complete`, `done`/`total` (after the
prayer: prayers of 5), `streak`, `best`, `rate7`, `rate30` (completed days over the days since the log began; after
the prayer each prayer a fifth), `week` (7 shares, oldest first), `usual` ("HH:MM", the median completion time over 30
days, with 3 or more), `goal`. A day's `score` is its planned collections' mean share (null: nothing planned). Rounded
to 0.01. In a debug build `adhkar.history.seed {"days"}` fills empty past days with made-up ones and
`adhkar.history.unseed` removes exactly those.

The collections are `morning`, `evening`, `after_prayer`, `sleep`, `waking` (daily) and `dhikr`, `istighfar`,
`salawat`, `quran`, `distress`, `everyday` (any time); `today` has them all.

`page`: what the frame shows where the main screen is, `main` (prayer times) or `adhkar` (the adhkar display); a
prayer screen, a message, black or the adhkar reader are drawn over either. `loop`: the adhkar display's round —
`collections` looped now (one daily collection in its own time when `timeAware`, else `settings.loop.collections`),
`index` of `total` pages, `round` (the shuffle's seed), `current` the page shown: dhikr `id`, its `category`, `part`
of `parts` (a long dhikr is cut into up to 3 pages), `meaning` true on the translation page after a long one,
`seconds` it stays. It only advances while it is on screen. `slide`: the slide over the clock area now (`hadith`,
`announcement`, `mosques`, `adhkar` with the dhikr's `id`), or null.

Settings `settings.loop` (the adhkar display): `enabled` (true), `home` `"main"` (default) or `"adhkar"` (the page shown
when the display comes forward, and after `returnMinutes` (15; 0 = never) without a touch on the other one),
`collections` (array of ids, in order; unknown ones dropped, empty = the default `["dhikr","istighfar","salawat","quran"]`),
`timeAware` (true), `order` `"shuffle"` or `"sequential"`, `speed` 50–200 (percent: 200 shows each page half as
long; a page lasts 5 s + 0.11 s per Arabic letter + 0.05 s per Latin letter, 8–50 s at 100), `translation` (true),
`transliteration` (false). In `settings.display`: `showAdhkarSlides` (true) and `adhkarSlidePercent` (30, 5–100):
the share of slide slots that show a short dhikr (every slot when there is no other slide), never during a reminder,
the iqama countdown or in the last 10 minutes before an adhan.

`presence`: see [Screen presence](#screen-presence). `secondary`: the mosques of `settings.source.secondary` (at most 3, slugs; a pasted mawaqit.net URL is reduced to
its slug; the main mosque is left out), in order. Display only: they never play an adhan. `name` is the slug until the
page has been read once; `next` is that mosque's next prayer (Jumu'a on Fridays), null without times; `error` is
`not_found`, `network` or `parse` from the last attempt, with the cached times still shown; `updatedAt` is when the
times shown were fetched. To change the list, `settings.patch` the whole array (arrays replace):
`{"patch":{"source":{"secondary":["hassan-moschee-frankfurt-am-main","https://mawaqit.net/de/attassamuh-moschee-frankfurt"]}}}`.

### Screen presence

`settings.presence` (core/Presence.kt): when the display steps aside and what brings it back. Off by default.

```json
{"enabled": false, "away": "off", "idleMinutes": 10, "returnAfterMinutes": 2, "leadMinutes": 0,
 "messages": true, "reminders": true, "periodic": "off", "everyMinutes": 60, "minMinutes": 30, "maxMinutes": 90,
 "showSeconds": 60, "from": "08:00", "to": "21:00"}
```

- `away`: `off` (backlight off through root over a black screen, as the night's black mode; a touch, a key or a
  knock — `night.wakeOnKnock` — wakes it), `photos` (Frameo's slideshow; without Frameo: `off`), `home` (the home
  launcher, HOME intent).
- Goes away `idleMinutes` (0–240; 0 = never by itself) after the later of the last touch / key and the end of what
  held it; when it came back by itself and nobody touched it, `returnAfterMinutes` (0–60) after that instead.
- Comes back (and holds it while it lasts): every screen around a prayer (adhan, dua, iqama countdown, iqama, prayer,
  azkar, Jumu'a, previews too), the "adhan soon" phase (`adhan.reminderMinutes`, 0–60, for the prayers of
  `adhan.reminderPrayers`, 5 booleans, Jumu'a = Dhuhr's; `adhan.reminderChime` its chime) with "Dhuhr in 20 min" in
  the top bar, `leadMinutes` (0–60) before every adhan, silently; a live `message.show` (`messages`), an adhkar
  reminder firing in `remind` or `open` mode (`reminders`; a schedule's own `wake` lights it either way); the panel
  (`phase.preview`, `slide.adhkar`, `adhkar.open`, `open display`); with `away` `photos` / `home` the night (the dim
  clock comes forward, as photo-frame mode did). With `messages` / `reminders` off those still keep a display that is
  on screen, but don't bring it back.
- `periodic`: `every` `everyMinutes` (5–720) or `random` between `minMinutes` and `maxMinutes` (5–720, drawn each
  time), counted from when it stepped aside, only between `from` and `to` ("HH:MM"; `to` < `from` runs over midnight,
  equal = all day) and never during the night phase (a time out of them moves to the next `from`). It shows the adhkar
  display (`loop`; a dhikr slide when `loop.enabled` is false) for `showSeconds` (10–1800), then steps aside at once
  unless touched.
- State `presence`: `awake` (on screen and lit), `asleep` (the `off` case), `reason` while awake (`touch`, `adhan`,
  `before_adhan`, `night`, `message`, `reminder`, `adhkar`, `remote`) or `away`, `awayAt` when it steps aside if
  nothing happens (null: it doesn't, it is away, or something holds it now), `nextWake` `{at, reason}`: the earliest of the next adhan (minus
  the notice / lead minutes), the next adhkar reminder, and while away the next periodic adhkar; null with the policy off.
- Before this policy there was photo-frame mode, `settings.frame` `{enabled, leadMinutes, returnAfterMinutes,
  idleMinutes}`: read once into `presence` (on: `away` `photos`, the same minutes; off: the defaults above), and a
  `settings.patch` that still says `frame` is turned into the same `presence` keys.
