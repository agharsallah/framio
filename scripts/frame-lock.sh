#!/bin/bash
# Take turns on the one frame: several Claude sessions, their agents and people share it over adb.
#
#   scripts/frame-lock.sh run [-w "what"] <command…>   run a command while holding the frame (waits its turn)
#   scripts/frame-lock.sh status                       who holds it, for how long, who is waiting
#   scripts/frame-lock.sh break                        remove the lock (only when you know its holder is gone)
#
#   scripts/frame-lock.sh run -w "install mawaqit" adb install -r app-release.apk
#   scripts/frame-lock.sh run -w "screenshots" bash -c 'adb shell input tap 640 400; adb exec-out screencap -p > a.png'
#
# The protocol is the one every framio tool already uses (tools/fr with FR_LOCK, examples/*/tools/frame.sh):
#   - The lock is the directory /tmp/framio-frame.lock: mkdir takes it, rmdir releases it. Nothing goes inside it,
#     or the other tools' rmdir would fail.
#   - A lock untouched for 5 minutes is stale. This script touches it every 20 s while it holds it, so only a
#     crashed holder ever looks stale; and when the holder wrote an owner file whose pid is gone, the lock is freed
#     at once instead of after 5 minutes.
#   - /tmp/framio-frame.lock.owner (next to the lock, optional) says who holds it: name|pid|since|what.
#   - Waiters using this script queue in /tmp/framio-frame.queue: first come, first served.
#   - Whoever frees a stale lock first takes /tmp/framio-frame.lock.reap (a sibling mutex dir, stale after 30 s, as in tools/fr).
#
# Env: FRAME_OWNER   who you are in status lines (default: $CLAUDE_SESSION_NAME, else user:pid)
#      FRAME_WAIT_MAX give up after this many seconds of waiting (exit 75); default: wait as long as it takes
# Keep holds short (under a minute or two): others are waiting. Long read-only things (logcat, scrcpy) need no lock.
set -uo pipefail

LOCK=${FRAME_LOCK:-/tmp/framio-frame.lock}
OWNER_FILE="$LOCK.owner"
REAP="$LOCK.reap"
QUEUE="${LOCK%.lock}.queue"
STALE=300
ME=${FRAME_OWNER:-${CLAUDE_SESSION_NAME:-$(id -un):$$}}

now() { date +%s; }
# 0 when the path is gone (never "very old")
mtime() { stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0; }
alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

# name|pid|since|what of the current holder, empty when unknown. An owner file older than the lock directory is a
# leftover (its holder wrote it, then someone took the lock with a bare mkdir): it says nothing about the holder.
owner() {
  [ -f "$OWNER_FILE" ] && [ -d "$LOCK" ] && [ "$(mtime "$OWNER_FILE")" -ge "$(mtime "$LOCK")" ] && cat "$OWNER_FILE" 2>/dev/null || true
}

describe() {
  local o age name pid since what
  o=$(owner)
  age=$(( $(now) - $(mtime "$LOCK") ))
  if [ -n "$o" ]; then
    IFS='|' read -r name pid since what <<<"$o"
    echo "held by $name${what:+ · $what} · $(( $(now) - since )) s (pid $pid$(alive "$pid" || echo ', gone'))"
  else
    echo "held by a tool without owner info (tools/fr, a frame.sh, a loop) · touched $age s ago"
  fi
}

# Frees a lock whose holder is provably gone (owner pid dead) or that went untouched for 5 minutes. Reapers of every
# tool (this script, tools/fr) take turns through $LOCK.reap, so two of them never free the same lock twice.
reap() {
  [ -d "$LOCK" ] || return 1
  if ! mkdir "$REAP" 2>/dev/null; then
    [ $(( $(now) - $(mtime "$REAP") )) -gt 30 ] && [ "$(mtime "$REAP")" -gt 0 ] && rmdir "$REAP" 2>/dev/null
    return 1
  fi
  reap_checked
  local freed=$?
  rmdir "$REAP" 2>/dev/null
  return $freed
}

reap_checked() {
  [ -d "$LOCK" ] || return 1
  local o pid
  o=$(owner)
  pid=$(cut -d'|' -f2 <<<"$o")
  if [ -n "$o" ] && ! alive "$pid"; then
    # re-check just before removing: the lock may have changed hands meanwhile
    [ "$(owner)" = "$o" ] || return 1
    rm -f "$OWNER_FILE"; rmdir "$LOCK" 2>/dev/null
    echo "frame-lock: freed a lock left by a holder that is gone ($o)" >&2
    return 0
  fi
  # Old by its own mtime, read twice: a lock released (and maybe re-taken) in between must not be removed
  local m
  m=$(mtime "$LOCK")
  if [ "$m" -gt 0 ] && [ $(( $(now) - m )) -gt $STALE ] && [ "$(mtime "$LOCK")" = "$m" ]; then
    [ -z "$(owner)" ] && rm -f "$OWNER_FILE"
    rmdir "$LOCK" 2>/dev/null && echo "frame-lock: freed a lock untouched for over $STALE s" >&2
    return 0
  fi
  return 1
}

# First live ticket in the queue (tickets of dead waiters are cleared on the way)
head_ticket() {
  local t pid
  for t in $(ls "$QUEUE" 2>/dev/null | sort); do
    pid=$(cut -d- -f2 <<<"$t")
    if alive "$pid"; then echo "$t"; return; fi
    rm -f "$QUEUE/$t"
  done
}

ticket=""
heartbeat=""
held=""
cleanup() {
  [ -n "$heartbeat" ] && kill -KILL "$heartbeat" 2>/dev/null  # TERM trips a bash 3.2 trap bug in the subshell
  [ -n "$ticket" ] && rm -f "$QUEUE/$ticket"
  if [ -n "${held:-}" ]; then
    # Release only what is still ours: if our lock was taken for stale and someone holds it now, leave theirs alone
    if [ "$(cut -d'|' -f2 "$OWNER_FILE" 2>/dev/null)" = "$$" ]; then
      rm -f "$OWNER_FILE"
      rmdir "$LOCK" 2>/dev/null
    fi
  fi
}

acquire() {
  local what=$1 waited=0 start next=15
  start=$(now)
  mkdir -p "$QUEUE"
  ticket="$(printf '%012d' "$(now)")-$$-$RANDOM"
  : >"$QUEUE/$ticket"
  trap cleanup EXIT
  trap 'exit 130' INT TERM
  while :; do
    first=0
    [ "$(head_ticket)" = "$ticket" ] && first=1
    if [ $first = 1 ] && mkdir "$LOCK" 2>/dev/null; then
      held=1
      echo "$ME|$$|$(now)|$what" >"$OWNER_FILE"
      rm -f "$QUEUE/$ticket"; ticket=""
      # keep the lock fresh while we hold it, so nobody takes it for stale
      # Never creates anything (touch -c), and stops the moment we're gone or the lock isn't ours any more:
      # a touch after a kill -9 would otherwise leave a plain file named like the lock, blocking everyone.
      ( trap - EXIT INT TERM  # not the parent's traps (bash 3.2 warns when they fire in a subshell)
        while sleep 20; do
          kill -0 $$ 2>/dev/null && [ -d "$LOCK" ] && [ "$(cut -d'|' -f2 "$OWNER_FILE" 2>/dev/null)" = "$$" ] || exit 0
          touch -c "$LOCK" "$OWNER_FILE" 2>/dev/null
        done ) &
      heartbeat=$!
      disown "$heartbeat" 2>/dev/null  # no "Killed" job notice when we stop it
      [ $waited -gt 0 ] && echo "frame-lock: got the frame after $waited s" >&2
      return 0
    fi
    # Only the first in line reaps: two waiters freeing the same dead lock could remove the one just re-taken
    [ $first = 1 ] && [ -d "$LOCK" ] && reap && continue
    waited=$(( $(now) - start ))
    if [ -n "${FRAME_WAIT_MAX:-}" ] && [ $waited -ge "$FRAME_WAIT_MAX" ]; then
      echo "frame-lock: gave up after $waited s, frame $( [ -d "$LOCK" ] && describe || echo 'is free but others are queued' )" >&2
      exit 75
    fi
    if [ $waited -ge $next ]; then
      echo "frame-lock: waiting $waited s, frame $( [ -d "$LOCK" ] && describe || echo 'free, queued behind others' )" >&2
      next=$(( next + 30 ))
    fi
    sleep 1
  done
}

case "${1:-}" in
  run)
    shift
    what=""
    if [ "${1:-}" = "-w" ]; then what=$2; shift 2; fi
    [ $# -gt 0 ] || { echo "usage: $0 run [-w what] <command…>" >&2; exit 2; }
    acquire "${what:-$*}"
    "$@"
    exit $?
    ;;
  status)
    if [ -d "$LOCK" ]; then echo "frame: $(describe)"; else echo "frame: free"; fi
    for t in $(ls "$QUEUE" 2>/dev/null | sort); do
      pid=$(cut -d- -f2 <<<"$t")
      alive "$pid" && echo "  waiting: pid $pid since $(( $(now) - 10#$(cut -d- -f1 <<<"$t") )) s"
    done
    ;;
  break)
    echo "removing: $( [ -d "$LOCK" ] && describe || echo 'no lock' )"
    rm -f "$OWNER_FILE"; rmdir "$LOCK" 2>/dev/null; true
    ;;
  *)
    sed -n '2,8p' "$0"; exit 2
    ;;
esac
