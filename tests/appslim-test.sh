#!/bin/sh
# App slimming (host/*/boot/lib/appslim.sh + boot/appslim.conf): the list is well-formed, the hooks are wired,
# and apply / revert are idempotent against a mocked PackageManager (service call package 86 / 87).
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
for h in k49 legacy; do
    cmp -s host/pw6/boot/lib/appslim.sh host/$h/boot/lib/appslim.sh || fail "host/$h/boot/lib/appslim.sh differs from host/pw6"
    cmp -s host/pw6/boot/appslim.conf host/$h/boot/appslim.conf || fail "host/$h/boot/appslim.conf differs from host/pw6"
done
for h in pw6 k49 legacy; do
    sh -n host/$h/boot/lib/appslim.sh || fail "syntax host/$h/boot/lib/appslim.sh"
    grep -q ' appslim ' host/$h/boot/veradroid.sh || fail "host/$h/boot/veradroid.sh does not load appslim.sh"
    grep -q 'appslim_all boot' host/$h/boot/lib/afterboot.sh || fail "host/$h afterboot does not apply the list"
    grep -q 'appslim_all install' host/$h/boot/lib/cmd.sh || fail "host/$h install does not apply the list"
    grep -q 'appslim) *type appslim_cmd' host/$h/boot/lib/cmd.sh || fail "host/$h has no appslim command"
    grep -q 'appslim_tick' host/$h/boot/lib/watchdog.sh || fail "host/$h watchdog does not watch for updates"
    grep -q '^    APP_SLIM=auto' host/$h/boot/lib/settings.sh || fail "host/$h settings.sh has no APP_SLIM default"
done
grep -q '\^SLIM=\[01\]\$' host/pw6/boot/lib/appprof.sh || fail "appprof.sh does not accept SLIM"
grep -q 'KNOBS_PKG' android/packages/VeraHome/src/com/veradroid/home/AppDisplayActivity.java || fail "VeraHome does not read appknobs.pkg"

# list format: "<pkg> <range> @default=lowmem|on|off" or "<pkg> <range> <class>"; every package has exactly one default per range;
# never the launcher / reader activities or the account / login parts (they are not services / receivers / providers we may touch)
C=host/pw6/boot/appslim.conf
bad=$(grep -v '^[[:space:]]*\(#\|$\)' $C | sed 's/[[:space:]]*#.*//' | while read -r p r c rest; do
    [ -n "$rest" ] && { echo "extra field: $p $c"; continue; }
    case "$p" in (''|*[!A-Za-z0-9._]*) echo "bad package: $p"; continue ;; esac
    case "$r" in ('*'|[0-9]*-|-[0-9]*|[0-9]*-[0-9]*|[0-9]*) ;; (*) echo "bad range: $p $r"; continue ;; esac
    case "$r" in (*[!0-9*-]*|*-*-*) echo "bad range: $p $r"; continue ;; esac
    case "$c" in (@default=lowmem|@default=on|@default=off) ;; (@*) echo "bad default: $p $c" ;;
        (*[!A-Za-z0-9._\$]*|'') echo "bad class: $p $c" ;;
        (*Activity|*.account.*|*Login*|*Authenticator*) echo "must not disable: $p $c" ;; esac
done)
[ -z "$bad" ] || fail "appslim.conf: $bad"
for p in $(grep -v '^[[:space:]]*\(#\|$\)' $C | awk '{print $1}' | sort -u); do
    n=$(grep -v '^[[:space:]]*#' $C | awk -v p=$p '$1 == p && $3 ~ /^@default=/' | wc -l | tr -d ' ')
    [ "$n" -ge 1 ] || fail "appslim.conf: $p has no @default line"
done
dup=$(grep -v '^[[:space:]]*\(#\|$\)' $C | sed 's/[[:space:]]*#.*//' | awk '{print $1, $2, $3}' | sort | uniq -d)
[ -z "$dup" ] || fail "appslim.conf: duplicate $dup"

# behaviour with a fake container: "service call package 87|86" against a state file
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/vd/boot $T/sys/data/system $T/w/kfb $T/bin $T/pm
cat > $T/vd/boot/appslim.conf <<'CONF'
com.example.big 100-199 @default=lowmem
com.example.big 100-199 com.example.big.PushService      # push
com.example.big 100-199 com.example.big.Gone             # not in the installed version
com.example.big 100-199 com.example.big.Keep$Inner
com.example.big 200- @default=on
com.example.big 200- com.example.big.NewPush
com.example.off * @default=off
com.example.off * com.example.off.Daemon
CONF
printf '%s\n' com.example.big.PushService 'com.example.big.Keep$Inner' com.example.big.AppOff com.example.big.NewPush > $T/pm/classes.com.example.big
printf '%s\n' com.example.off.Daemon > $T/pm/classes.com.example.off
echo 'com.example.big.Keep$Inner 1' > $T/pm/state.com.example.big        # the app had enabled this one itself
cat > $T/bin/service <<'SVC'
#!/bin/sh
# service call package <code> i32 1 s16 <pkg> s16 <cls> i32 <state|user> ...
D=$(dirname "$0")/../pm; code=$3; p=$7; c=$9; st=${11}
[ -f "$D/vc.$p" ] || { echo "Result: Parcel(fffffffd 00000000 '....')"; exit 0; }
case "$code" in
  87) v=$(awk -v c="$c" '$1 == c { print $2 }' "$D/state.$p" 2>/dev/null); printf 'Result: Parcel(00000000 %08x   %s)\n' "${v:-0}" "'........'" ;;
  86) grep -qxF "$c" "$D/classes.$p" || { echo "Result: Parcel(fffffffd 0000002c 006f0043 '....')"; exit 0; }
      { awk -v c="$c" '$1 != c' "$D/state.$p" 2>/dev/null; echo "$c $st"; } > "$D/state.$p.tmp"; mv "$D/state.$p.tmp" "$D/state.$p"
      echo "$p $c $st" >> "$D/calls"; echo "Result: Parcel(00000000    '....')" ;;
esac
SVC
cat > $T/ns <<'NS'
#!/bin/sh
# fake "nsenter ...": /system/bin/sh -c <script> sh <pkg>  -> the script with our fake service first in PATH
shift; shift; s=$1; shift
exec sh -c "$(printf '%s' "$s" | sed "s#export PATH=/system/bin:/system/xbin;#export PATH=$(dirname "$0")/bin:\$PATH;#")" "$@"
NS
chmod +x $T/bin/service $T/ns
pkgxml() { { echo '<packages>'; for f in $T/pm/vc.*; do [ -f "$f" ] && echo "<package name=\"${f##*/vc.}\" codePath=\"/data/app/x-1\" flags=\"0\" ft=\"1\" it=\"1\" ut=\"1\" version=\"$(cat $f)\" userId=\"10050\">"; done; echo '</packages>'; } > $T/sys/data/system/packages.xml; }
run() {
    VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb TT=$T MEMKB=${MEMKB:-480000} APP_SLIM=${APP_SLIM:-auto} sh -c '
    t() { echo "LOG $*"; }; vd_in() { echo "$TT/ns"; }; vd_timeout() { shift; "$@"; }
    adb_sh() { case "$*" in *boot_completed*) echo 1 ;; "dumpsys package "*) p=${*##* }; [ -f $TT/pm/vc.$p ] && echo "    versionCode=$(cat $TT/pm/vc.$p) minSdk=21 targetSdk=35" ;; esac; }
    lowmem_ram() { [ $MEMKB -lt 700000 ]; }
    . host/pw6/boot/lib/appslim.sh
    '"$1"
}
calls() { [ -f $T/pm/calls ] && wc -l < $T/pm/calls | tr -d ' ' || echo 0; }
state() { awk -v c="$2" '$1 == c { print $2 }' $T/pm/state.$1 2>/dev/null; }

echo 150 > $T/pm/vc.com.example.big; echo 7 > $T/pm/vc.com.example.off; pkgxml
# 512 MB: default on -> PushService and Keep$Inner off, Gone skipped, the off-by-default app untouched
out=$(run 'appslim_all boot; cat $X/appknobs.pkg')
[ "$(state com.example.big com.example.big.PushService)" = 2 ] || fail "512 MB: PushService not disabled: $out"
[ "$(state com.example.big 'com.example.big.Keep$Inner')" = 2 ] || fail "512 MB: Keep\$Inner not disabled"
[ -z "$(state com.example.off com.example.off.Daemon)" ] || fail "default off app was touched"
grep -qx 'com.example.big.PushService 0' $T/sys/appslim/com.example.big.list || fail "record misses the previous state 0"
grep -qx 'com.example.big.Keep$Inner 1' $T/sys/appslim/com.example.big.list || fail "record misses the previous state 1"
grep -q 'Gone' $T/sys/appslim/com.example.big.list && fail "a class missing from this version was recorded"
printf '%s\n' "$out" | grep -q '^com.example.big SLIM|.*停用了 2 个' || fail "knob line missing: $out"
printf '%s\n' "$out" | grep -q '^com.example.off SLIM|' || fail "knob line for an installed off-by-default app missing"
# idempotent: a second run sets nothing
n0=$(calls); run 'appslim_all boot' >/dev/null; [ "$(calls)" = "$n0" ] || fail "second run changed state again"
# the app re-enabled a component itself: disabled again, previous state kept from the first record
{ grep -v '^com.example.big.PushService ' $T/pm/state.com.example.big; echo 'com.example.big.PushService 1'; } > $T/pm/st.tmp; mv $T/pm/st.tmp $T/pm/state.com.example.big
run 'appslim_apply com.example.big boot' >/dev/null
[ "$(state com.example.big com.example.big.PushService)" = 2 ] || fail "re-enabled component not disabled again"
[ "$(grep -c PushService $T/sys/appslim/com.example.big.list)" = 1 ] || fail "duplicate record"
grep -qx 'com.example.big.PushService 0' $T/sys/appslim/com.example.big.list || fail "previous state overwritten"
# user picks "off" (SLIM=0): everything back to what it was before
echo 'com.example.big b SLIM=0' > $T/w/kfb/appprof
run 'appslim_apply com.example.big appset' >/dev/null
[ "$(state com.example.big com.example.big.PushService)" = 0 ] || fail "restore: PushService not back to default"
[ "$(state com.example.big 'com.example.big.Keep$Inner')" = 1 ] || fail "restore: Keep\$Inner not back to enabled"
[ -f $T/sys/appslim/com.example.big.list ] && fail "record left after restore"
# 1 GB: lowmem default is off; user SLIM=1 turns it on
rm -f $T/w/kfb/appprof
( MEMKB=1000000 run 'appslim_all boot' ) >/dev/null
[ "$(state com.example.big com.example.big.PushService)" = 0 ] || fail "1 GB: default should be off"
echo 'com.example.big u SLIM=1' > $T/w/kfb/appprof
( MEMKB=1000000 run 'appslim_apply com.example.big appset' ) >/dev/null
[ "$(state com.example.big com.example.big.PushService)" = 2 ] || fail "1 GB + SLIM=1: not disabled"
# upgrade to a version with another list: old entries restored, new ones applied
echo 250 > $T/pm/vc.com.example.big; pkgxml; rm -f $T/w/kfb/appprof
( MEMKB=1000000 run 'appslim_all update' ) >/dev/null
[ "$(state com.example.big com.example.big.PushService)" = 0 ] || fail "upgrade: old entry not restored"
[ "$(state com.example.big com.example.big.NewPush)" = 2 ] || fail "upgrade: new list not applied (@default=on)"
# APP_SLIM=0 / restore command: everything back
( APP_SLIM=0 run 'appslim_all boot' ) >/dev/null
[ "$(state com.example.big com.example.big.NewPush)" = 0 ] || fail "APP_SLIM=0 did not restore"
run 'appslim_all boot' >/dev/null; [ "$(state com.example.big com.example.big.NewPush)" = 2 ] || fail "re-apply after APP_SLIM=0"
run 'appslim_cmd restore' >/dev/null; [ "$(state com.example.big com.example.big.NewPush)" = 0 ] || fail "appslim restore"
# uninstalled: record dropped, nothing called
run 'appslim_all boot' >/dev/null
rm -f $T/pm/vc.com.example.big; pkgxml
run 'appslim_all boot' >/dev/null
[ -f $T/sys/appslim/com.example.big.list ] && fail "record of an uninstalled app kept"
# lock: a live holder makes a second run skip (no double set); a dead holder's lock is taken over
mkdir -p $T/w/appslim.lock; sleep 30 & hp=$!; echo $hp > $T/w/appslim.lock/pid
echo 150 > $T/pm/vc.com.example.big; pkgxml; n0=$(calls)
out=$(run 'appslim_all boot 0')
printf '%s\n' "$out" | grep -q 'skipped, another run is still busy' || fail "busy lock not honoured: $out"
[ "$(calls)" = "$n0" ] || fail "ran while locked"
kill $hp 2>/dev/null; wait $hp 2>/dev/null
run 'appslim_all boot 0' >/dev/null; [ -d $T/w/appslim.lock ] && fail "lock left behind"
[ "$(state com.example.big com.example.big.PushService)" = 2 ] || fail "stale lock not taken over"
# watchdog: only when packages.xml changes and a listed package changed version
touch $T/w/booted
out=$(run 'appslim_tick; sleep 1; appslim_tick; wait')
echo "appslim-test: ok"
