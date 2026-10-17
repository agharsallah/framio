#!/usr/bin/env bash
# Runs each fr command once on the attached device and checks the result (~1-2 min).
#
#   tools/fr/smoke.sh            # from the repo root, frame on USB
#
# Changes it makes: installs and launches examples/01-hello-frame, taps its Exit button,
# toggles show_touches (restored), blinks the backlight off/on (restored). Captures go to
# captures/<date>/ with "smoke" in the name. Holds $FR_LOCK (default /tmp/framio-frame.lock)
# around device-changing steps, so other sessions driving the same frame wait their turn.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
export FR_LOCK=${FR_LOCK-/tmp/framio-frame.lock}
FR=${FR:-fr}
pass=0 fail=0
ok() { echo "✓ $1"; pass=$((pass + 1)); }
ko() { echo "✗ $1"; fail=$((fail + 1)); }
check() { local name=$1; shift; if "$@"; then ok "$name"; else ko "$name"; fi; }
newest() { ls -t captures/*/*"$1" 2>/dev/null | head -1; }
px() { sips -g "pixel$1" "$2" 2>/dev/null | awk '/pixel/{print $2}'; }

check "doctor" $FR doctor >/dev/null

$FR sh false; check "sh false exits 1" test $? = 1
$FR sh 'exit 3'; check "sh exit 3 exits 3" test $? = 3
check "sh --root" sh -c "$FR sh --root id | grep -q 'uid=0'"

size=$($FR sh 'wm size' | awk -F'[: x]+' '/Physical/{print $3"x"$4}')
$FR shot smoke >/dev/null
png=$(newest '_smoke.png')
check "shot is ${size} PNG" test "$(px Width "$png")x$(px Height "$png")" = "$size"

$FR rec smoke-scrcpy --time 5s >/dev/null 2>&1
mp4=$(newest '_smoke-scrcpy.mp4')
check "rec (scrcpy) writes a ${size%x*}-wide mp4" test "$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "$mp4")" = "${size%x*}"

before=$($FR sh 'settings get system show_touches')
$FR rec smoke-device --engine device --time 4s --touches >/dev/null 2>&1
check "rec (device) writes an mp4" test -s "$(newest '_smoke-device.mp4')"
check "show_touches restored ($before)" test "$($FR sh 'settings get system show_touches')" = "$before"

$FR rec start smoke-bg >/dev/null && sleep 4 && $FR rec stop >/dev/null 2>&1
check "rec start/stop" test -s "$(newest '_smoke-bg.mp4')"

log=$(mktemp)
$FR logs --tag FrSmoke --raw >"$log" 2>&1 & lp=$!
sleep 2; $FR sh 'log -t FrSmoke hello-smoke'; sleep 2; kill -INT $lp; wait $lp 2>/dev/null
check "logs follows a tag" grep -q hello-smoke "$log"
rm -f "$log"

out=$($FR run examples/01-hello-frame --no-logs 2>&1)
check "run 01-hello-frame launches ($(tail -1 <<<"$out"))" grep -q 'launch [0-9]' <<<"$out"
sleep 2
check "find sees Exit" sh -c "$FR find Exit | grep -q Exit"
check "tap Exit closes Hello Frame" sh -c "$FR tap Exit >/dev/null && sleep 1 && ! $FR info | grep -q 'front.*dev.framio.hello'"

$FR bug smoke >/dev/null
bug=$(ls -td captures/*/*_bug_smoke* | head -1)
check "bug folder" test -s "$bug/screen.png" -a -s "$bug/logcat.txt" -a -s "$bug/info.txt"

b0=$($FR sh --root 'cat /sys/class/backlight/*/brightness')
$FR screen off; b1=$($FR sh --root 'cat /sys/class/backlight/*/brightness')
$FR screen on; b2=$($FR sh --root 'cat /sys/class/backlight/*/brightness')
check "screen off/on ($b0 → $b1 → $b2)" test "$b1" = 0 -a "$b2" = "$b0"

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
