#!/bin/bash
# One frame, several builders: take turns. Every subcommand runs while holding the shared frame lock,
# taken through the repo's scripts/frame-lock.sh (queue, heartbeat, owner file; `scripts/frame-lock.sh status`
# shows who holds it). Hold it briefly (≤ ~60 s) and leave the frame clean.
#
#   tools/frame.sh shot <apk|-> <out.png> [wait_s] [am start extras…]   install (unless -), start, wait, screenshot
#   tools/frame.sh run  <command…>                                        any command while holding the lock
#   tools/frame.sh tap x y | swipe x1 y1 x2 y2 ms | shotonly <out.png>   quick steps (each takes the lock)
#   tools/frame.sh clean                                                  force-stop Pomodoro, backlight back to 187
#
# FRAME_WAIT_MAX=<s> gives up (exit 75) instead of waiting forever for a busy frame.
# Example: tools/frame.sh shot app/build/outputs/apk/release/app-release.apk /tmp/x.png 4 --es cmd start --es mode deep
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
LOCKER="$ROOT/scripts/frame-lock.sh"
export FRAME_OWNER=${FRAME_OWNER:-framio-7a/pomodoro}
PKG=dev.framio.pomodoro
ACTIVITY=.MainActivity
BACKLIGHT=/sys/class/backlight/rk28_bl/brightness
DAY_LEVEL=187
ADB=${ADB:-adb}

# Leaves the frame as we found it: no Pomodoro session that could pop to the front, backlight on.
# One command per `su -c` (SuperSU ignores multi-line scripts).
clean_frame() {
  $ADB shell am force-stop $PKG
  $ADB shell su -c "\"echo $DAY_LEVEL > $BACKLIGHT\""
  echo "clean: $PKG stopped, backlight $($ADB shell su -c "\"cat $BACKLIGHT\"" | tr -d '\r')"
}

# The steps themselves; only ever reached through the lock (see the bottom).
locked() {
  local cmd=$1; shift
  case "$cmd" in
    shot)
      local apk=$1 out=$2 wait=${3:-4}; shift 3 || shift $#
      [ "$apk" != "-" ] && $ADB install -r "$apk" | tail -1
      $ADB logcat -c
      $ADB shell am force-stop $PKG
      $ADB shell am start -n $PKG/$ACTIVITY "$@" >/dev/null
      sleep "$wait"
      $ADB exec-out screencap -p > "$out"
      echo "resumed: $($ADB shell dumpsys activity activities | grep mResumedActivity | sed 's/.*u0 //;s/ t[0-9]*}//')"
      $ADB logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Caused|\s+at dev\.framio)|EVENT\||CHECK\|" | head -30 || true
      ;;
    shotonly) $ADB exec-out screencap -p > "$1" ;;
    tap) $ADB shell input tap "$1" "$2" ;;
    swipe) $ADB shell input swipe "$@" ;;
    run) "$@" ;;
    clean) clean_frame ;;
  esac
}

cmd=${1:-}
case "$cmd" in
  __locked) shift; locked "$@" ;;
  shot|shotonly|tap|swipe|run|clean)
    shift
    exec "$LOCKER" run -w "pomodoro $cmd${1:+ $1}" "$0" __locked "$cmd" "$@"
    ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
