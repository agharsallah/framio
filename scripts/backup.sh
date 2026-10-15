#!/usr/bin/env bash
# Read-only full backup of the Veidoo F10 photo frame (Rockchip RK3126, Android 6.0.1, rooted).
#
#   scripts/backup.sh OUT_DIR
#
# Writes to OUT_DIR (keep it OUTSIDE the repo, it contains personal photos):
#   info/        getprop, pm list, dumpsys, partition table, verify results, run log
#   partitions/  loader-area.img + one raw image per partition (all except userdata)
#   data/        data.tar (file-level archive of /data, incl. /data/media = /sdcard)
#   frameo/      Frameo APKs + file listings
#   SHA256SUMS
#
# Device side it only READS block devices. The single temp file it creates lives in
# /data/local/tmp/framio-tmp/ and is removed on exit. No reboot, no pm, no input.
set -euo pipefail

OUT=${1:?usage: backup.sh OUT_DIR}
ADB=${ADB:-$HOME/Downloads/platform-tools/adb}
TMP=/data/local/tmp/framio-tmp
mkdir -p "$OUT"/{info,partitions,data,frameo}
OUT=$(cd "$OUT" && pwd)
LOG=$OUT/info/backup-run.log
: > "$LOG"

log() { echo "$*" | tee -a "$LOG"; }
# root shell, text output. Command must not contain single quotes.
# </dev/null: adb shell otherwise swallows stdin, e.g. the list a while-read loop is reading.
root() { "$ADB" shell "su -c '$1'" </dev/null | tr -d '\r'; }
# root shell, binary-safe stdout (Android 6 has no shell protocol: stderr would mix in, so redirect it).
raw() { "$ADB" exec-out "su -c '$1'" </dev/null; }
md5_dev() { root "$1" | awk '{print $1}'; }

cleanup() { "$ADB" shell "su -c 'rm -rf $TMP'" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# --- 0. preflight ---------------------------------------------------------------
n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$n" = 1 ] || { log "need exactly one device, found $n"; exit 1; }
log "root: $(root id)"
log "mac free: $(df -h "$OUT" | awk 'NR==2{print $4}')"

# sizes in 512-byte sectors from sysfs
root 'for p in /sys/block/mmcblk0/mmcblk0p*; do echo $(basename $p) $(cat $p/start) $(cat $p/size); done' \
  | sort -k2 -n > "$OUT/info/sysfs-partition-offsets.txt"
root 'ls -l /dev/block/platform/*/by-name/' | awk '{print $(NF-2), $NF}' | sort > "$OUT/info/by-name.txt"
part_sectors=$(awk '{s+=$3} END{print s}' "$OUT/info/sysfs-partition-offsets.txt")
data_kb=$(root 'du -s /data' | awk '{print $1}')
userdata_dev=$(awk '$1=="userdata"{print $2}' "$OUT/info/by-name.txt")
userdata_sec=$(awk -v d="$(basename "$userdata_dev")" '$1==d{print $3}' "$OUT/info/sysfs-partition-offsets.txt")
need_mb=$(( (part_sectors - userdata_sec) / 2048 + 8 + data_kb / 1024 ))
free_mb=$(df -m "$OUT" | awk 'NR==2{print $4}')
log "estimated backup size: ${need_mb} MB, free: ${free_mb} MB"
[ "$free_mb" -gt $(( need_mb * 2 + 2048 )) ] || { log "not enough free space"; exit 1; }

# --- 1. device info ---------------------------------------------------------------
"$ADB" shell getprop </dev/null | tr -d '\r' > "$OUT/info/getprop.txt"
"$ADB" shell pm list packages -f </dev/null | tr -d '\r' > "$OUT/info/pm-list-packages-f.txt"
"$ADB" shell dumpsys package net.frameo.frame </dev/null | tr -d '\r' > "$OUT/info/dumpsys-package-frameo.txt"
root 'cat /proc/partitions' > "$OUT/info/proc-partitions.txt"
root 'cat /proc/cmdline' > "$OUT/info/proc-cmdline.txt"
root 'mount' > "$OUT/info/mount.txt"
root 'df' > "$OUT/info/df.txt" || true
log "info files written"

# --- 2. loader area: eMMC sector 0 up to the first partition -----------------------
VERIFY=$OUT/info/partition-md5-verify.txt
: > "$VERIFY"
first=$(awk 'NR==1{print $2}' "$OUT/info/sysfs-partition-offsets.txt")   # sectors
[ $(( first % 2048 )) = 0 ] || { log "first partition not MiB aligned"; exit 1; }
mib=$(( first / 2048 ))
raw "dd if=/dev/block/mmcblk0 bs=1048576 count=$mib 2>/dev/null" > "$OUT/partitions/loader-area.img"
dev=$(md5_dev "dd if=/dev/block/mmcblk0 bs=1048576 count=$mib 2>/dev/null | md5sum")
mac=$(md5 -q "$OUT/partitions/loader-area.img")
r=MISMATCH; [ "$dev" = "$mac" ] && r=MATCH
log "loader-area mmcblk0[0..$((first-1))] $(stat -f %z "$OUT/partitions/loader-area.img") dev=$dev mac=$mac $r" | tee -a "$VERIFY"

# --- 3. raw partition images (everything except userdata) --------------------------
while read -r name dev_path; do
  [ "$name" = userdata ] && continue
  img=$OUT/partitions/$name.img
  raw "dd if=$dev_path bs=1048576 2>/dev/null" > "$img"
  dev=$(md5_dev "md5sum $dev_path")
  mac=$(md5 -q "$img")
  r=MISMATCH; [ "$dev" = "$mac" ] && r=MATCH
  log "$name $dev_path $(stat -f %z "$img") dev=$dev mac=$mac $r" | tee -a "$VERIFY"
done < "$OUT/info/by-name.txt"

# --- 4. /data at file level ---------------------------------------------------------
# toybox tar on this build aborts on paths > 100 bytes, so use toybox cpio (newc: long
# names, numeric uid/gid, mode, mtime, symlinks, sockets). bsdtar on the Mac reads it.
root "mkdir -p $TMP"
raw "cd / && find data > $TMP/list.txt 2>/dev/null; cpio -o -H newc < $TMP/list.txt 2>$TMP/cpio.err" > "$OUT/data/data.cpio"
root "cat $TMP/cpio.err" > "$OUT/info/data-cpio-stderr.txt" || true
tar -tf "$OUT/data/data.cpio" > "$OUT/info/data-cpio-list.txt"
dev_n=$(root "wc -l < $TMP/list.txt" | tr -d ' ')
mac_n=$(wc -l < "$OUT/info/data-cpio-list.txt" | tr -d ' ')
log "data.cpio $(stat -f %z "$OUT/data/data.cpio") bytes, entries device=$dev_n archive=$mac_n, cpio stderr lines: $(wc -l < "$OUT/info/data-cpio-stderr.txt" | tr -d ' ')"
# spot-check file contents inside the archive against the device
for f in $(grep -E '^data/app/net\.frameo\.frame-[0-9]+/base\.apk$|^data/system/packages\.xml$|^data/misc/wifi/wpa_supplicant\.conf$' "$OUT/info/data-cpio-list.txt"); do
  dev=$(md5_dev "md5sum /$f"); mac=$(tar -xOf "$OUT/data/data.cpio" "$f" | md5 -q)
  r=MISMATCH; [ "$dev" = "$mac" ] && r=MATCH
  log "cpio spot-check $f dev=$dev mac=$mac $r" | tee -a "$VERIFY"
done

# --- 5. Frameo specifics --------------------------------------------------------------
"$ADB" shell pm path net.frameo.frame </dev/null | tr -d '\r' > "$OUT/frameo/pm-path.txt"
apk=$(sed -n 's/^package://p' "$OUT/frameo/pm-path.txt" | head -1)
raw "cat $apk" > "$OUT/frameo/net.frameo.frame-base.apk"
log "frameo apk $apk dev=$(md5_dev "md5sum $apk") mac=$(md5 -q "$OUT/frameo/net.frameo.frame-base.apk")"
for d in /data/data/net.frameo.frame /data/media/0/frameo /data/media/0/frameo_files; do
  f=$(echo "$d" | tr / _)
  root "ls -lR $d" > "$OUT/frameo/ls-lR$f.txt" 2>&1 || true
  log "$d files=$(root "find $d -type f | wc -l") du_kb=$(root "du -s $d" | awk '{print $1}')"
done

# --- 6. checksums -----------------------------------------------------------------------
cleanup
log "done"
cd "$OUT"
find . -type f ! -name SHA256SUMS ! -name .DS_Store -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
shasum -a 256 -c --quiet SHA256SUMS && echo "SHA256SUMS ok ($(wc -l < SHA256SUMS | tr -d ' ') files)"
