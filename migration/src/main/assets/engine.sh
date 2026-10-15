#!/system/bin/sh
# Same-device root-only engine. Never deletes source data or imports arbitrary archives.
set -eu
set -o pipefail
export PATH=/system/bin:/system/xbin:/vendor/bin
umask 077
ROOT=/data/local/multiusers
fail() { echo "ERROR: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail 'root required'
[ "$#" = 6 ] || fail 'invalid arguments'
MODE=$1 ID=$2 PKG=$3 SRC=$4 DST=$5 BACK=$6
case "$MODE" in copy|backup|restore|recover|verify) ;; *) fail 'invalid mode';; esac
case "$ID" in *[!a-f0-9-]*|'') fail 'invalid task id';; esac
[ ${#ID} = 36 ] || fail 'invalid task length'
case "$PKG" in *[!A-Za-z0-9_.]*|'') fail 'invalid package';; esac
[ "$PKG" != top.writerpass.multiusers ] || fail 'self migration prohibited'
case "$SRC:$DST" in *[!0-9:]*) fail 'invalid users';; esac
[ "$SRC" -le 21473 ] && [ "$DST" -le 21473 ] || fail 'user out of range'
if [ -n "$BACK" ]; then
 case "$BACK" in *[!a-f0-9-]*) fail 'invalid backup id';; esac
 [ ${#BACK} = 36 ] || fail 'invalid backup length'
fi
[ ! -L "$ROOT" ] || fail 'unsafe root path'
mkdir -p "$ROOT"; chown 0:0 "$ROOT"; chmod 700 "$ROOT"
[ ! -L "$ROOT/lock" ] || fail 'unsafe lock path'
# Android 的 mksh 给重定向 fd 加 O_CLOEXEC（fd>=3 子进程看不到），toybox flock 又只接受 fd，
# 只有 fd 0 会被子进程继承，所以必须用 exec 0<> + flock -n 0（与 app 侧 RootLock.kt 一致）。
exec 0<>"$ROOT/lock"
flock -n 0 || fail 'another migration is running'
JOB="$ROOT/$ID"
[ ! -e "$JOB" ] || fail 'task already started; use explicit recovery'
mkdir "$JOB"
printf '%s\n' "$PKG" > "$JOB/package"
printf '%s\n' "$SRC" > "$JOB/source-user"
printf '%s\n' "$DST" > "$JOB/target-user"
printf '%s\n' "$MODE" > "$JOB/mode"
exec >>"$JOB/log" 2>&1
phase() { printf '%s\n' "$1" > "$JOB/state.tmp"; mv "$JOB/state.tmp" "$JOB/state"; sync; echo "$(date +%s) $1"; }
restore_enabled() {
 case "$2" in 0) pm default-state --user "$1" "$PKG";; 1) pm enable --user "$1" "$PKG";; 2) pm disable --user "$1" "$PKG";; 3) pm disable-user --user "$1" "$PKG";; 4) pm disable-until-used --user "$1" "$PKG";; *) return 1;; esac
 [ "$(enabled_for "$1")" = "$2" ] || fail 'enabled setting verification failed'
}
SOURCE_DISABLED=0
cleanup() {
 rc=$?
 if [ "$SOURCE_DISABLED" = 1 ]; then restore_enabled "$SRC" "$(cat "$JOB/source-enabled")" || true; fi
 if [ "$rc" != 0 ]; then echo "FAILED rc=$rc; target may remain disabled; use recovery"; echo FAILED > "$JOB/result"; fi
 sync
}
trap cleanup EXIT
phase PREPARING
for tool in tar sha256sum find stat chcon restorecon sort cmp flock; do command -v "$tool" >/dev/null || fail "missing $tool"; done
unlocked() { [ "$(am get-started-user-state "$1" 2>/dev/null | tr -d '\r\n')" = RUNNING_UNLOCKED ] || fail "user $1 ($2) must be running and unlocked"; }
serial_for() {
 val=$(dumpsys user | sed -n "/UserInfo{$1:/s/.* serialNo=\([0-9]*\).*/\1/p")
 case "$val" in ''|*[!0-9]*) fail 'cannot determine user serial';; esac
 echo "$val"
}
uid_for() {
 val=$(pm list packages -U --user "$1" "$PKG" | awk -v p="package:$PKG" '$1==p {sub(/^uid:/,"",$2); print $2}')
 case "$val" in ''|*[!0-9]*) fail 'missing or ambiguous uid';; esac
 echo "$val"
}
enabled_for() {
 val=$(dumpsys package "$PKG" | sed -n "/^[[:space:]]*User $1:/s/.* enabled=\([0-4]\).*/\1/p")
 case "$val" in 0|1|2|3|4) echo "$val";; *) fail 'cannot determine enabled setting';; esac
}
# 失败前把可见事实写进日志（只诊断，任何一步出错都不影响判定）
diag_dir() {
 ( set +e
  echo "DIAG path=$1 exists=$([ -e "$1" ] && echo yes || echo no) type=$([ -e "$1" ] && stat -c %F "$1" 2>/dev/null || echo missing) symlink=$([ -L "$1" ] && echo yes || echo no)"
  [ -L "$1" ] && echo "DIAG symlink target=$(readlink "$1" 2>/dev/null)"
  echo "DIAG parent=$(ls -ld "$(dirname "$1")" 2>&1 | tr -s ' ')"
  echo "DIAG userdirs=$(ls -d /data/user/* 2>&1 | tr '\n' ' ')"
  echo "DIAG userde=$(ls -d /data/user_de/* 2>&1 | tr '\n' ' ')"
  echo "DIAG mounts=$(grep -c '/data/user/' /proc/self/mountinfo 2>&1)"
  echo "DIAG context=$(cat /proc/self/attr/current 2>&1)"
 ) >&2
}
check_app() {
 role=${2:-target}
 unlocked "$1" "$role"
 UID_VALUE=$(uid_for "$1"); APPID=$((UID_VALUE % 100000))
 [ "$APPID" -ge 10000 ] && [ "$APPID" -le 19999 ] && [ "$((UID_VALUE / 100000))" = "$1" ] || fail 'not an ordinary app uid'
 [ "$(pm list packages -U --user "$1" | awk -v u="$UID_VALUE" '$2=="uid:"u {n++} END {print n+0}')" = 1 ] || fail 'shared UID not supported'
 ! pm list packages -s --user "$1" | grep -Fx "package:$PKG" >/dev/null || fail 'system app migration prohibited'
 for base in /data/user /data/user_de; do
  d="$base/$1/$PKG"
  [ -d "$d" ] && [ ! -L "$d" ] || { diag_dir "$d"; fail "data directory unavailable ($role user $1): $d; install and launch the app once in that user, then retry"; }
  [ "$(stat -c %u "$d")" = "$UID_VALUE" ] || { diag_dir "$d"; fail "directory uid mismatch ($role user $1): $d"; }
 done
}
apk_hashes() {
 pm path --user "$1" "$PKG" > "$JOB/apkpaths.tmp"
 [ -s "$JOB/apkpaths.tmp" ] || fail 'no APK path'
 : > "$JOB/apkhash.tmp"
 while IFS= read -r line; do
  case "$line" in package:/data/app/*|package:/system/*|package:/product/*) path=${line#package:};; *) fail 'unsupported APK storage path';; esac
  sha256sum "$path" | cut -d ' ' -f 1 >> "$JOB/apkhash.tmp"
 done < "$JOB/apkpaths.tmp"
 sort "$JOB/apkhash.tmp"
}
check_tree() {
 dir=$1 owner=$2
 bad=$(find "$dir" ! -type d ! -type f -print -quit)
 [ -z "$bad" ] || fail 'symlink/special file unsupported'
 bad=$(find "$dir" -type f -links +1 -print -quit)
 [ -z "$bad" ] || fail 'hardlinked file unsupported'
 expected_context=$(ls -Zd "$dir" | awk '{print $1}')
 case "$expected_context" in u:object_r:app_data_file:s0*) ;; *) fail 'unsupported data context';; esac
 cache=$((owner / 100000 * 100000 + owner % 100000 + 10000)); shared=$((owner % 100000 + 40000))
 find "$dir" -exec sh -c '
  owner=$1 cache=$2 shared=$3 expected=$4; shift 4
  for f do
   case "$f" in *"
"*) exit 1;; esac
   [ "$(stat -c %u "$f")" = "$owner" ] || exit 1
   g=$(stat -c %g "$f")
   [ "$g" = "$owner" ] || [ "$g" = "$cache" ] || [ "$g" = "$shared" ] || exit 1
   [ "$(ls -Zd "$f" | cut -d " " -f 1)" = "$expected" ] || exit 1
  done
 ' sh "$owner" "$cache" "$shared" "$expected_context" {} + || fail 'foreign UID/GID, context or newline filename'
}
manifest() { (cd "$1"; find . -type f -exec sha256sum {} + | sort); }
layout() { (cd "$1"; find . -mindepth 1 -exec stat -c '%a %F %n' {} + | sort); }
snapshot() {
 user=$1 out=$2 uid=$(uid_for "$1")
 mkdir "$out"
 echo "$uid" > "$out/uid"; echo "$PKG" > "$out/package"
 enabled_for "$user" > "$out/enabled"
 apk_hashes "$user" > "$out/apk.sha256"
 for space in ce de; do
  case "$space" in ce) dir=/data/user/$user/$PKG;; de) dir=/data/user_de/$user/$PKG;; esac
  check_tree "$dir" "$uid"
  ls -Zd "$dir" | awk '{print $1}' > "$out/$space.context"
  manifest "$dir" > "$out/$space.files"; layout "$dir" > "$out/$space.layout"
  tar -cpf "$out/$space.tar" -C "$dir" .
  manifest "$dir" > "$out/$space.after"
  cmp "$out/$space.files" "$out/$space.after" || fail 'data changed during snapshot'
 done
 (cd "$out"; sha256sum ce.tar de.tar ce.files de.files ce.layout de.layout uid package enabled apk.sha256 ce.context de.context > SHA256SUMS; sha256sum -c SHA256SUMS)
 sync; touch "$out/COMPLETE"; sync
}
verify_backup() {
 [ -f "$1/COMPLETE" ] && [ ! -L "$1" ] || fail 'incomplete backup'
 [ "$(cat "$1/package")" = "$PKG" ] || fail 'backup package mismatch'
 (cd "$1"; sha256sum -c SHA256SUMS) || fail 'backup checksum mismatch'
}
apply_backup() {
 archive=$1 user=$2
 verify_backup "$archive"; check_app "$user" target
 newuid=$(uid_for "$user") olduid=$(cat "$archive/uid")
 apk_hashes "$user" > "$JOB/current-apk.sha256"
 cmp "$archive/apk.sha256" "$JOB/current-apk.sha256" || fail 'APK changed; restore blocked'
 for space in ce de; do
  case "$space" in ce) dir=/data/user/$user/$PKG;; de) dir=/data/user_de/$user/$PKG;; esac
  context=$(ls -Zd "$dir" | awk '{print $1}')
  case "$context" in u:object_r:app_data_file:s0*) ;; *) fail 'unexpected target SELinux type';; esac
  # Preserve encrypted root inode/FBE policy. Only verified, root-created archives are accepted.
  find "$dir" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  tar -xpf "$archive/$space.tar" -C "$dir"
  find "$dir" -user "$olduid" -exec chown "$newuid" {} +
  find "$dir" -group "$olduid" -exec chgrp "$newuid" {} +
  oldcache=$((olduid / 100000 * 100000 + olduid % 100000 + 10000)); newcache=$((newuid / 100000 * 100000 + newuid % 100000 + 10000))
  find "$dir" -group "$oldcache" -exec chgrp "$newcache" {} +
  oldshared=$((olduid % 100000 + 40000)); newshared=$((newuid % 100000 + 40000))
  find "$dir" -group "$oldshared" -exec chgrp "$newshared" {} +
  chcon -R "$context" "$dir"; restorecon -RF "$dir"
  [ "$(ls -Zd "$dir" | awk '{print $1}')" = "$context" ] || fail 'SELinux root context mismatch'
  check_tree "$dir" "$newuid"
  manifest "$dir" > "$JOB/$space.restored"; layout "$dir" > "$JOB/$space.layout"
  cmp "$archive/$space.files" "$JOB/$space.restored" || fail 'restored file hash mismatch'
  cmp "$archive/$space.layout" "$JOB/$space.layout" || fail 'restored tree/mode mismatch'
 done
}
if [ "$MODE" = verify ]; then
 verify_backup "$ROOT/$BACK/source"; phase COMPLETE; echo SUCCEEDED > "$JOB/result"; exit 0
fi
if [ "$MODE" = recover ]; then
 OLD="$ROOT/$BACK"
 [ "$(cat "$OLD/package")" = "$PKG" ] || fail 'recovery package mismatch'
 [ "$(cat "$OLD/target-user")" = "$DST" ] || fail 'recovery target mismatch'
 if [ -f "$OLD/source-enabled" ]; then
  [ "$(serial_for "$(cat "$OLD/source-user")")" = "$(cat "$OLD/source-serial")" ] || fail 'source user identity changed'
  restore_enabled "$(cat "$OLD/source-user")" "$(cat "$OLD/source-enabled")"
 fi
 if [ -f "$OLD/target-enabled" ]; then
  [ "$(serial_for "$DST")" = "$(cat "$OLD/target-serial")" ] || fail 'target user identity changed'
 fi
 if [ ! -f "$OLD/target/COMPLETE" ]; then
  state=$(cat "$OLD/state")
  case "$state" in PREPARING|SOURCE_READY)
   if [ -f "$OLD/target-enabled" ]; then restore_enabled "$DST" "$(cat "$OLD/target-enabled")"; fi
   echo RECOVERED > "$OLD/result"; phase COMPLETE; echo SUCCEEDED > "$JOB/result"; exit 0;;
   *) fail 'no complete target snapshot; manual investigation required';;
  esac
 fi
 check_app "$DST" target
 pm disable-user --user "$DST" "$PKG"
 [ "$(enabled_for "$DST")" = 3 ] || fail 'target disable failed'
 am force-stop --user "$DST" "$PKG"
 phase WRITING; apply_backup "$OLD/target" "$DST"
 restore_enabled "$DST" "$(cat "$OLD/target-enabled")"
 echo RECOVERED > "$OLD/result"; phase COMPLETE; echo SUCCEEDED > "$JOB/result"; exit 0
fi
if [ "$MODE" = backup ] || [ "$MODE" = copy ]; then
 check_app "$SRC" source
 # 目标用户的运行/解锁是无副作用的先决条件：提前检查，避免做完源快照才发现目标没启动
 unlocked "$DST" target
 enabled_for "$SRC" > "$JOB/source-enabled"; serial_for "$SRC" > "$JOB/source-serial"; sync
 SOURCE_DISABLED=1
 pm disable-user --user "$SRC" "$PKG"
 [ "$(enabled_for "$SRC")" = 3 ] || fail 'source disable failed'
 am force-stop --user "$SRC" "$PKG"
 snapshot "$SRC" "$JOB/source"
 restore_enabled "$SRC" "$(cat "$JOB/source-enabled")"; SOURCE_DISABLED=0
 phase SOURCE_READY
 if [ "$MODE" = backup ]; then phase COMPLETE; echo SUCCEEDED > "$JOB/result"; exit 0; fi
 [ "$SRC" != "$DST" ] || fail 'identical source and target'
 BACKUP="$JOB/source"
else
 BACKUP="$ROOT/$BACK/source"; verify_backup "$BACKUP"
fi
unlocked "$DST" target; pm install-existing --user "$DST" "$PKG"; check_app "$DST" target
enabled_for "$DST" > "$JOB/target-enabled"; serial_for "$DST" > "$JOB/target-serial"; sync
pm disable-user --user "$DST" "$PKG"
[ "$(enabled_for "$DST")" = 3 ] || fail 'target disable failed'
am force-stop --user "$DST" "$PKG"
snapshot "$DST" "$JOB/target"; phase TARGET_SAVED; verify_backup "$BACKUP"
phase WRITING; apply_backup "$BACKUP" "$DST"; phase VERIFIED
restore_enabled "$DST" "$(cat "$JOB/target-enabled")"
phase COMPLETE; echo SUCCEEDED > "$JOB/result"; sync
