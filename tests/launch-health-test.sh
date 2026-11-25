#!/bin/sh
# "启动" after a bug (fix/1105-launch-kual; community, KPW6 + KUAL: "every so often after a bug, tapping start does not get
# into the system, only the update script does"). The update script works because it always runs a full stop.sh and a cold
# start; "start" used to thaw / reopen whatever Android was there, broken or not. Checks:
#  - veradroid.sh looks at the existing Android first (container init still ours, system_server and surfaceflinger alive)
#    and does a full stop + cold start when it is not usable; the check sits before the thaw / reopen branches and skips a
#    start in progress;
#  - thaw resets the display bridge / touch front end restart budget and restarts a dead display bridge;
#  - the KUAL entry logs every tap to start.log, leaves KUAL right away (setsid, background), handles non-root;
#  - diagnostics carry start.log and last-stop.log.
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
for h in pw6 k49 legacy; do
    v=host/$h/boot/veradroid.sh
    sh -n $v || fail "syntax $v"
    awk '/^android_unusable\(\)/ { a = NR } /au_why=\$\(android_unusable/ { u = NR } /if \[ -f \$W\/frozen \]; then/ && !f { f = NR }
         END { exit !(a && u && f && a < u && u < f) }' $v || fail "$h: health check missing or after the thaw branch"
    grep -q 'au_c" = init' $v || fail "$h: container init identity not checked (pid reuse)"
    grep -q 'system_server is gone' $v && grep -q 'surfaceflinger is gone' $v || fail "$h: system_server / surfaceflinger not checked"
    grep -q 'VD_STOP_REASON="start: android not usable' $v || fail "$h: unusable android not stopped completely"
    grep -q '\[ -d \$W/starting \] && vd_lock_alive' $v || fail "$h: health check would kill a start in progress"
    grep -q 'rm -f \$W/epd.restarts \$W/input.restarts' $v || fail "$h: thaw does not reset the helper restart budget"
    grep -q 'unusable.since' $v || fail "$h: zygote soft-restart grace window missing"
    grep -q '/tmp/veradroid-stop.log' $v && grep -q 'start.log' $v || fail "$h: stop.log backup on storage_mount failure missing"
    k=host/$h/kual/start.sh
    sh -n $k || fail "syntax $k"
    grep -q 'KUAL entry tapped' $k || fail "$h: KUAL tap not logged"
    grep -q 'setsid sh /mnt/base-us/veradroid/boot/veradroid.sh home </dev/null >/dev/null 2>&1 &' $k || fail "$h: KUAL start does not detach"
    grep -q 'id -u' $k || fail "$h: KUAL start does not check root"
    if [ "$h" != legacy ]; then
        w=host/$h/boot/lib/watchdog.sh
        grep -q 'logs/start.log > \$d/start.log' $w && grep -q 'last-stop.log > \$d/last-stop.log' $w || fail "$h: diagnostics miss start.log / last-stop.log"
    fi
done
cmp -s host/pw6/kual/start.sh host/k49/kual/start.sh || fail "KUAL start.sh differs between pw6 and k49"
cmp -s host/pw6/kual/start.sh host/legacy/kual/start.sh || fail "KUAL start.sh differs between pw6 and legacy"

# Runtime check for android_unusable() zygote soft-restart grace window (#7)
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
for h in pw6 k49 legacy; do
    awk '/^android_unusable\(\)/,/^}/' host/$h/boot/veradroid.sh > $T/au.sh
    rm -rf $T/w $T/fz $T/proc; mkdir -p $T/w $T/fz $T/proc/100 $T/proc/101 $T/proc/102 $T/proc/103
    echo init > $T/proc/100/comm
    echo surfaceflinger > $T/proc/101/comm
    echo zygote > $T/proc/102/comm
    printf '100\n101\n102\n' > $T/fz/cgroup.procs
    touch $T/w/booted
    echo "1000.00 0.00" > $T/proc/uptime
    # 1. zygote alive, not frozen, within 45s grace -> deferred (empty stdout), sets unusable.since
    out=$(P=100 W=$T/w FZ=$T/fz AU_PROC=$T/proc UNUSABLE_WAIT_S=0 UNUSABLE_GRACE_S=45 sh -c 't() { echo "LOG $*" >&2; }; . '$T'/au.sh; android_unusable' 2>$T/err)
    [ -z "$out" ] || fail "$h: soft restart within grace declared dead: $out"
    [ "$(cat $T/w/unusable.since 2>/dev/null)" = 1000 ] || fail "$h: unusable.since not recorded"
    # 2. system_server recovers -> clears unusable.since, returns empty
    echo system_server > $T/proc/103/comm
    printf '100\n101\n102\n103\n' > $T/fz/cgroup.procs
    out=$(P=100 W=$T/w FZ=$T/fz AU_PROC=$T/proc UNUSABLE_WAIT_S=0 sh -c 't() { :; }; . '$T'/au.sh; android_unusable')
    [ -z "$out" ] && [ ! -e $T/w/unusable.since ] || fail "$h: unusable.since not cleared on recovery"
    # 3. missing past grace window -> declared dead and unusable.since cleaned up
    rm -f $T/proc/103/comm
    echo 1000 > $T/w/unusable.since
    echo "1050.00 0.00" > $T/proc/uptime
    out=$(P=100 W=$T/w FZ=$T/fz AU_PROC=$T/proc UNUSABLE_WAIT_S=0 UNUSABLE_GRACE_S=45 sh -c 't() { :; }; . '$T'/au.sh; android_unusable')
    [ "$out" = "system_server is gone" ] && [ ! -e $T/w/unusable.since ] || fail "$h: past grace not declared dead ($out)"
    # 4. frozen -> immediate failure even with zygote present
    touch $T/w/frozen
    out=$(P=100 W=$T/w FZ=$T/fz AU_PROC=$T/proc UNUSABLE_WAIT_S=0 sh -c 't() { :; }; . '$T'/au.sh; android_unusable')
    [ "$out" = "system_server is gone" ] || fail "$h: frozen missing system_server deferred ($out)"
    rm -f $T/w/frozen
    # 5. zygote also dead -> immediate failure without grace
    rm -f $T/proc/102/comm
    out=$(P=100 W=$T/w FZ=$T/fz AU_PROC=$T/proc UNUSABLE_WAIT_S=0 sh -c 't() { :; }; . '$T'/au.sh; android_unusable')
    [ "$out" = "system_server is gone" ] || fail "$h: dead zygote deferred ($out)"
done
echo "PASS launch-health"
