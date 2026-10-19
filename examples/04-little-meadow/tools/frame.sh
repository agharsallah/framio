#!/bin/bash
# One frame, several builders: take turns. Every command that touches the device holds a lock
# (a directory, created atomically); a lock older than 5 minutes is considered stale. Compatible with the
# repo's scripts/frame-lock.sh (same lock dir, same owner file).
#
#   tools/frame.sh shot <apk|-> <out.png> [wait_s] [am start extras…]   install (unless -), start, wait, screenshot
#   tools/frame.sh run  <command…>                                        any command while holding the lock
#   tools/frame.sh tap x y | swipe x1 y1 x2 y2 ms | shotonly <out.png>   quick steps (each takes the lock)
#
# Example: tools/frame.sh shot app/build/outputs/apk/debug/app-debug.apk /tmp/x.png 4 --es play count
set -euo pipefail
LOCK=/tmp/framio-frame.lock   # shared with the other framio sessions using the same frame
PKG=dev.framio.meadow
ADB=${ADB:-adb}

lock() {
  local waited=0 age
  until mkdir "$LOCK" 2>/dev/null; do
    age=$(( $(date +%s) - $(stat -f %m "$LOCK" 2>/dev/null || date +%s) ))
    # stale: rename it away first, so of several waiters only one removes it
    if [ "$age" -gt 300 ] && mv "$LOCK" "$LOCK.stale.$$" 2>/dev/null; then rm -rf "$LOCK.stale.$$"; continue; fi
    sleep 1; waited=$((waited + 1))
    [ $((waited % 30)) -eq 0 ] && echo "waiting for the frame ($waited s)…" >&2
  done
  # who holds it, for scripts/frame-lock.sh status (name|pid|since|what): a lock whose pid is gone is freed early
  echo "meadow|$$|$(date +%s)|${cmd:-frame.sh}" > "$LOCK.owner"
  trap 'rm -f "$LOCK.owner"; rmdir "$LOCK" 2>/dev/null || true' EXIT
}

cmd=${1:-}; shift || true
case "$cmd" in
  shot)
    apk=$1; out=$2; wait=${3:-4}; shift 3 || shift $#
    lock
    [ "$apk" != "-" ] && $ADB install -r "$apk" | tail -1
    $ADB logcat -c
    $ADB shell am force-stop $PKG
    # quote each extra for the frame's shell: JSON like {"a":1,"b":2} would otherwise be brace-expanded
    extras=""
    q="'"; qq="'\\''"   # ' inside a single-quoted word becomes '\''
    for a in "$@"; do extras+=" '${a//$q/$qq}'"; done
    $ADB shell "am start -n $PKG/.MeadowActivity$extras" >/dev/null
    sleep "$wait"
    $ADB exec-out screencap -p > "$out"
    echo "resumed: $($ADB shell dumpsys activity activities | grep mResumedActivity | sed 's/.*u0 //;s/ t[0-9]*}//')"
    $ADB logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: (java|Caused|\s+at dev\.framio)|W Meadow" | head -30 || true
    ;;
  shotonly) lock; $ADB exec-out screencap -p > "$1" ;;
  tap) lock; $ADB shell input tap "$1" "$2" ;;
  swipe) lock; $ADB shell input swipe "$@" ;;
  run) lock; "$@" ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
