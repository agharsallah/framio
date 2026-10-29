#!/bin/bash
# Keep the frame in step with the repo: no store, just adb, the same debug key for every app, and a record on the frame
# of what was installed from which commit.
#
#   scripts/deploy.sh status               what's on the frame vs the repo, per app (no build, no install)
#   scripts/deploy.sh [app…]               build each app, install the ones whose APK differs from the frame's
#   scripts/deploy.sh --no-build [app…]    install what is already built
#   scripts/deploy.sh --force [app…]       reinstall even if the frame already has the same APK
#
# An app is a folder under examples/ ("02-launcher") or the end of its name ("launcher", "lab", "mawaqit").
# No app named: all of them.
#
# How it decides:
#   - Variant: release (R8, half the RAM) when the app signs release with the debug key, else debug. Every app uses the
#     debug key, so `adb install -r` always updates in place and keeps the app's data.
#   - Changed: md5 of the built APK vs md5 of the APK Android has installed (`pm path`). Gradle is incremental, so
#     building an unchanged app takes seconds and gives the same bytes.
#   - Record: each install appends `pkg date commit dirty md5 variant` to /data/local/tmp/framio-deploy.tsv on the
#     frame (shell-writable, survives reboots, gone on factory reset). `status` reads it back:
#       up to date     installed APK is the one last deployed, and no commit has touched the app since
#       behind         the app's folder changed after the deployed commit (or has uncommitted changes)
#       not from here  installed by something else (Android Studio, a plain adb install): run deploy once
#       not installed
#   - Device commands run under scripts/frame-lock.sh, like every other tool here: other sessions share the frame.
#
# Env: ADB (default: adb on PATH, else ~/Downloads/platform-tools/adb), JAVA_HOME (used if 17+, else JDK 21 via java_home).
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RECORD=/data/local/tmp/framio-deploy.tsv
ADB=${ADB:-$(command -v adb || echo "$HOME/Downloads/platform-tools/adb")}
# Gradle 9 needs JDK 17+; a shell profile often points JAVA_HOME at an older one
java_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
if [ -z "${JAVA_HOME:-}" ] || [ "$(java_major "$JAVA_HOME")" -lt 17 ] 2>/dev/null; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17+ 2>/dev/null)
fi
export FRAME_OWNER=${FRAME_OWNER:-deploy:$$}

build=1
force=0
cmd=deploy
names=()
for a in "$@"; do
    case $a in
        status) cmd=status ;;
        --no-build) build=0 ;;
        --force) force=1 ;;
        -h | --help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) names+=("$a") ;;
    esac
done

dirs() { # app folders, filtered by the names given
    local d
    for d in "$ROOT"/examples/*/; do
        d=$(basename "$d")
        [ -f "$ROOT/examples/$d/app/build.gradle.kts" ] || continue
        if [ ${#names[@]} -eq 0 ]; then echo "$d"; continue; fi
        local n
        for n in "${names[@]}"; do
            case $d in "$n" | *-"$n" | *-"$n"-*) echo "$d" ;; esac
        done
    done
}

pkg_of() { sed -n 's/.*applicationId *= *"\([^"]*\)".*/\1/p' "$ROOT/examples/$1/app/build.gradle.kts" | head -1; }

variant_of() { # release only if it is signed (with the debug key); an unsigned release can't be installed
    if sed -n '/release *{/,/}/p' "$ROOT/examples/$1/app/build.gradle.kts" | grep -q signingConfig; then echo release; else echo debug; fi
}

apk_of() { local v; v=$(variant_of "$1"); echo "$ROOT/examples/$1/app/build/outputs/apk/$v/app-$v.apk"; }

commit_of() { git -C "$ROOT" log -1 --format=%h -- "examples/$1" 2>/dev/null; }

dirty_of() { [ -n "$(git -C "$ROOT" status --porcelain -- "examples/$1" 2>/dev/null)" ] && echo dirty || echo clean; }

# First source file of the app changed after $2 (build outputs, Gradle and IDE folders aside)
newer_sources() {
    find "$ROOT/examples/$1" \( -name build -o -name .gradle -o -name .kotlin -o -name .idea \) -prune -o -type f -newer "$2" -print 2>/dev/null | head -1
}

local_md5() { [ -f "$1" ] && md5 -q "$1" 2>/dev/null || md5sum "$1" 2>/dev/null | cut -d' ' -f1; }

# </dev/null everywhere: adb shell reads stdin and would swallow the rest of a loop
installed_md5() {
    local path
    path=$("$ADB" shell pm path "$1" </dev/null 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
    [ -n "$path" ] && "$ADB" shell md5sum "$path" </dev/null 2>/dev/null | tr -d '\r' | cut -d' ' -f1
}

record_line() { "$ADB" shell cat "$RECORD" </dev/null 2>/dev/null | tr -d '\r' | awk -F'\t' -v p="$1" '$1 == p { l = $0 } END { print l }'; }

need_device() {
    if ! "$ADB" get-state </dev/null >/dev/null 2>&1; then
        echo "No frame on adb ($ADB devices). Connect it and try again." >&2
        exit 1
    fi
}

status() {
    need_device
    printf '%-18s %-20s %-8s %-16s %s\n' APP PACKAGE VARIANT DEPLOYED STATE
    local d pkg have rec rmd5 rcommit rdirty now state
    for d in $(dirs); do
        pkg=$(pkg_of "$d")
        have=$(installed_md5 "$pkg")
        rec=$(record_line "$pkg")
        rcommit=$(echo "$rec" | cut -f3)
        rdirty=$(echo "$rec" | cut -f4)
        rmd5=$(echo "$rec" | cut -f5)
        now=$(commit_of "$d")
        if [ -z "$have" ]; then
            state="not installed"
        elif [ -z "$rec" ] || [ "$have" != "$rmd5" ]; then
            state="not from here (run deploy once)"
        elif [ "$rcommit" != "$now" ]; then
            state="behind: repo has $now"
        elif [ "$(dirty_of "$d")" = dirty ] || [ "$rdirty" = dirty ]; then
            # Uncommitted work: the frame matches if it has the last build and no source changed after that build
            local apk
            apk=$(apk_of "$d")
            if [ -f "$apk" ] && [ "$(local_md5 "$apk")" = "$have" ] && [ -z "$(newer_sources "$d" "$apk")" ]; then
                state="up to date (uncommitted build)"
            else
                state="behind: uncommitted changes since the deployed build"
            fi
        else
            state="up to date"
        fi
        printf '%-18s %-20s %-8s %-16s %s\n' "$d" "$pkg" "$(variant_of "$d")" "${rcommit:--}${rdirty:+ $rdirty}" "$state"
    done
}

deploy() {
    need_device
    local d pkg variant apk failed=() done_=() same=()
    for d in $(dirs); do
        pkg=$(pkg_of "$d")
        variant=$(variant_of "$d")
        apk=$(apk_of "$d")
        if [ $build = 1 ]; then
            echo "== $d: assemble ${variant}"
            local task="assemble$(echo "${variant:0:1}" | tr a-z A-Z)${variant:1}"
            if ! (cd "$ROOT/examples/$d" && ./gradlew "$task" -q </dev/null); then
                echo "   build failed: skipped"
                failed+=("$d (build)")
                continue
            fi
        fi
        if [ ! -f "$apk" ]; then
            echo "   no $apk: skipped"
            failed+=("$d (no apk)")
            continue
        fi
        local want have
        want=$(local_md5 "$apk")
        have=$(installed_md5 "$pkg")
        local line
        line=$(printf '%s\t%s\t%s\t%s\t%s\t%s' "$pkg" "$(date +%Y-%m-%dT%H:%M)" "$(commit_of "$d")" "$(dirty_of "$d")" "$want" "$variant")
        if [ $force = 0 ] && [ "$want" = "$have" ]; then
            echo "   $pkg: frame already has this build"
            # Same bytes installed some other way: adopt it, so status knows its commit
            local rec
            rec=$(record_line "$pkg")
            if [ "$(echo "$rec" | cut -f5)" != "$want" ] || [ "$(echo "$rec" | cut -f3)" != "$(commit_of "$d")" ]; then
                "$ADB" shell "echo \"$line\" >> $RECORD" </dev/null
            fi
            same+=("$d")
            continue
        fi
        echo "   $pkg: installing $(basename "$apk") ($(du -h "$apk" | cut -f1 | tr -d ' '))"
        # One hold per app: install, record, and reopen the app if it was in front (install -r kills it, and this
        # firmware restarts nothing in the background). -r keeps the app's data. HOME comes back by itself.
        if "$ROOT/scripts/frame-lock.sh" run -w "deploy $pkg" bash -c '
            front=$("$1" shell dumpsys activity activities </dev/null | tr -d "\r" | sed -n "s/.*mResumedActivity: ActivityRecord{[^ ]* [^ ]* \([^/]*\)\/.*/\1/p" | head -1)
            out=$("$1" install -r "$2" </dev/null 2>&1); echo "$out" | tail -1 | sed "s/^/   /"
            echo "$out" | grep -q "^Success" || exit 1
            "$1" shell "echo \"$3\" >> $4" </dev/null
            if [ "$front" = "$5" ]; then
                echo "   was in front: reopening"
                "$1" shell monkey -p "$5" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
            fi' _ "$ADB" "$apk" "$line" "$RECORD" "$pkg" </dev/null \
            && [ "$(installed_md5 "$pkg")" = "$want" ]; then
            done_+=("$d")
        else
            failed+=("$d (install)")
        fi
    done
    echo
    [ ${#done_[@]} -gt 0 ] && echo "Installed:  ${done_[*]}"
    [ ${#same[@]} -gt 0 ] && echo "Unchanged:  ${same[*]}"
    if [ ${#failed[@]} -gt 0 ]; then
        echo "Failed:     ${failed[*]}"
        return 1
    fi
}

$cmd
