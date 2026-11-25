#!/bin/sh
# Network after standby / Wi-Fi changes (fix/1105-net-resume, field reports 2026-11-21: KT6 "network gone after a night in
# standby, pages load forever until I exit and re-enter", KT5 "network drops while reading").
# Root causes reproduced on the pw5-516211 emulator (chroot / hostnet mode):
#  1. netd: modifyIpRule is a no-op in hostnet mode, so clearTetheringRules' `while (ret == 0)` loop never ends: the first time
#     wlan0 goes away netd spins at 100 % with its big lock held, every later command times out, Android never gets a network back.
#  2. chroot mode never put Android into the freezer group: "android frozen" froze an empty group, Android kept running.
# Checks: the netd patch is in small-patches.py and the fixed binaries are shipped; the host installs them; the freezer fix;
# the net check hooks (resume, thaw, watchdog) exist; and the shell helpers behave (slirp DNS rewritten in place, chroot
# network check waits for the tracked interface, first watchdog round only records, fixed netd copied only when different).
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
grep -q 'int clearTetheringRules(const char\* inputInterface) {\\n    if (vdHostNet()) return 0;' android/small-patches.py || fail "netd clearTetheringRules patch missing in small-patches.py"
for h in pw6 k49 legacy; do
    b=host/$h/boot
    if [ "$h" != legacy ]; then
        [ ! -d host/$h/bin ] || [ -s host/$h/bin/netd-hostnet ] || fail "$h: bin/netd-hostnet missing"
        grep -q '^netd_hostnet_fix   #' $b/veradroid.sh || fail "$h: veradroid.sh does not install the fixed netd"
        awk '/if hostns; then/ { h = 1 } h && /freezer_enter/ { e = 1 } h && /--in-chroot/ { print (e ? "ok" : "late"); exit }' $b/lib/container.sh | grep -qx ok \
            || fail "$h: chroot container is started outside the freezer group"
    fi
    for f in $b/lib/network.sh $b/lib/sleep.sh $b/lib/watchdog.sh $b/lib/container.sh $b/stop.sh $b/veradroid.sh; do sh -n $f || fail "syntax $f"; done
    grep -q 'net_heal_kick thaw' $b/veradroid.sh || fail "$h: no net check after thaw"
    grep -q 'net_heal_kick resume' $b/lib/sleep.sh || fail "$h: no net check after the screensaver"
    grep -q 'net_watch_tick' $b/lib/watchdog.sh || fail "$h: watchdog does not watch the network"
    grep -q 'mkdir \$W/netheal.lock' $b/lib/network.sh || fail "$h: net_heal_kick has no atomic lock"
    grep -q 'getconf CLK_TCK' $b/lib/network.sh && grep -q '\$((nh_hz \* 3 / 2))' $b/lib/network.sh || fail "$h: netd_wedged still hardcodes HZ=100"
    [ "$(grep -c 'for f in .* netheal; do' $b/stop.sh)" = 2 ] || fail "$h: stop.sh does not include netheal in helper stop/cleanup loops"
    grep -q 'case "\$f" in .*|netheal)' $b/stop.sh || fail "$h: stop.sh does not reap netheal descendant processes"
    grep -q '\$W/netheal.lock' $b/stop.sh || fail "$h: stop.sh does not clean up netheal.lock"
    ! grep -q '^kill \$(cat \$W/netheal.pid' $b/stop.sh || fail "$h: stop.sh still has late bare kill of netheal.pid"
    grep -q 'rm -f \$W/netheal.pid \$W/net.sig \$W/net.last' $b/stop.sh || fail "$h: stop.sh leaves net check files (stop --stale fast path would never be clean)"
done
cmp -s host/pw6/boot/lib/network.sh host/k49/boot/lib/network.sh || fail "network.sh differs between pw6 and k49"

# ---- functional: network.sh helpers with a scratch $W ----
W=$T/w; X=$W/kfb; mkdir -p $X; LOG=$T/log; : > $LOG; NET_UPTIME=$T/uptime; echo "5000.00 1" > $NET_UPTIME
t() { echo "$*" >> $LOG; }
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
VD=$T/vd; R=$T/r; mkdir -p $VD/bin $R/system/bin
. host/pw6/boot/lib/network.sh
# slirp upstream DNS: rewritten in place (bind mount keeps the inode), only when it changed
ANDROID_DNS="223.5.5.5"
printf 'nameserver 223.5.5.5\nnameserver 10.0.0.1\n' > $W/resolv.conf
ino0=$(ls -i $W/resolv.conf | awk '{print $1}')
mkdir -p $T/etc; printf 'nameserver 10.9.9.9\n' > $T/etc/resolv.conf
readlink() { [ "$2" = /etc/resolv.conf ] && { echo $T/etc/resolv.conf; return 0; }; command readlink "$@"; }
net_slirp_dns
[ "$(ls -i $W/resolv.conf | awk '{print $1}')" = "$ino0" ] || fail "slirp resolv.conf replaced by a new file (slirp keeps the old inode)"
grep -q '^nameserver 10.9.9.9$' $W/resolv.conf || fail "slirp resolv.conf not updated with the Kindle DNS"
head -1 $W/resolv.conf | grep -q '223.5.5.5' || fail "ANDROID_DNS no longer first"
# chroot: network check waits while the default route is on another interface than the one Android tracks
echo chroot > $W/mode; printf 'iface=wlan0\n' > $X/hostnet
net_sig() { echo "usb0 6 10.0.15.244/24 10.0.15.201 10.0.15.3,"; }
ip() { echo "    inet 10.0.15.244/24 scope global"; }
net_host_up && fail "chroot: kindle counted as online through usb0 while android tracks wlan0"
net_sig() { echo "wlan0 9 10.0.2.15/24 10.0.2.2 10.0.2.3,"; }
net_host_up || fail "chroot: kindle on wlan0 not counted as online"
# watchdog: first round only records; a change starts one check; no check while frozen work is pending
touch $W/booted; kicks=$T/kicks; : > $kicks
net_heal_kick() { echo "$1" >> $kicks; }
net_watch_tick; [ -s $kicks ] && fail "first watchdog round started a network check"
net_sig() { echo "wlan0 10 10.0.2.15/24 10.0.2.2 10.0.2.3,"; }   # wlan0 recreated (new ifindex)
net_watch_tick; grep -qx change $kicks || fail "wlan0 recreated but no check"
: > $kicks; net_watch_tick; [ -s $kicks ] && fail "unchanged network checked again right away"
echo saver > $W/sleep.cur; echo "0 ok" > $W/net.last; net_watch_tick; [ -s $kicks ] && fail "periodic check during the screensaver"
rm -f $W/sleep.cur; net_watch_tick; grep -qx periodic $kicks || fail "no periodic check while in use"
# fixed netd: copied when different, left alone when the same
hostns() { return 0; }
echo new > $VD/bin/netd-hostnet; echo old > $R/system/bin/netd
netd_hostnet_fix; cmp -s $VD/bin/netd-hostnet $R/system/bin/netd || fail "fixed netd not installed"
: > $LOG; netd_hostnet_fix; [ -s $LOG ] && fail "netd replaced again although it was already the fixed one"

# net_heal_kick atomic lock: concurrent callers start only one net_heal; stale lock is taken over; lock cleaned on exit
for h in pw6 legacy; do
    W=$T/w-lock-$h; mkdir -p $W; LOG=$T/log-$h; : > $LOG
    . host/$h/boot/lib/network.sh
    runs=$T/runs-$h; : > $runs
    net_heal() { echo "$1" >> $runs; sleep 1; }
    ( NET_CHECK=0; net_heal_kick disabled )
    [ -s $runs ] && fail "$h: net_heal_kick ran when NET_CHECK=0"
    for i in 1 2 3 4 5 6 7 8 9 10; do ( net_heal_kick "c$i" ) & done
    wait
    i=0; while { [ -d $W/netheal.lock ] || [ -f $W/netheal.pid ]; } && [ $i -lt 40 ]; do sleep 0.1; i=$((i + 1)); done
    [ "$(wc -l < $runs | tr -d ' ')" = 1 ] || fail "$h: concurrent net_heal_kick ran $(wc -l < $runs | tr -d ' ') times, want 1"
    [ ! -d $W/netheal.lock ] && [ ! -f $W/netheal.pid ] || fail "$h: lock or pid left behind after net_heal exited"
    # stale lock with dead pid -> taken over cleanly
    : > $runs; mkdir -p $W/netheal.lock; echo 999999 > $W/netheal.lock/pid; echo 999999 > $W/netheal.pid
    net_heal() { echo "$1" >> $runs; }
    net_heal_kick stale
    i=0; while { [ -d $W/netheal.lock ] || [ -f $W/netheal.pid ]; } && [ $i -lt 40 ]; do sleep 0.1; i=$((i + 1)); done
    [ "$(cat $runs)" = stale ] || fail "$h: stale netheal.lock was not taken over"
    [ ! -d $W/netheal.lock ] && [ ! -f $W/netheal.pid ] || fail "$h: lock or pid left behind after fast net_heal"

    # stop.sh helper cleanup kills netheal, its multi-level descendants (even ignoring SIGTERM), netheal.pid, and netheal.lock
    rm -f $T/leaf-$h.pid
    net_heal() { sh -c "trap '' TERM; sh -c 'trap \"\" TERM; echo \$\$ > $T/leaf-$h.pid; sleep 60'"; }
    ( net_heal_kick stoptest )
    i=0; while [ ! -s $T/leaf-$h.pid ] && [ $i -lt 40 ]; do sleep 0.05; i=$((i + 1)); done
    nhp=$(cat $W/netheal.pid 2>/dev/null); lfp=$(cat $T/leaf-$h.pid 2>/dev/null)
    [ -n "$nhp" ] && [ -n "$lfp" ] && kill -0 "$lfp" 2>/dev/null || fail "$h: netheal descendant tree did not start"
    cat > $T/stop-part-$h.sh <<EOF
W=$W
kmsg() { :; }
usleep() { sleep 0.05; }
$(awk '/^ps=$/{p=1} p{print} /^rm -rf \$W\/netheal\.lock/{if(p){exit}}' host/$h/boot/stop.sh)
EOF
    sh $T/stop-part-$h.sh
    kill -0 "$nhp" 2>/dev/null && { kill -9 "$nhp" 2>/dev/null; fail "$h: stop.sh left netheal subshell alive"; }
    kill -0 "$lfp" 2>/dev/null && { kill -9 "$lfp" 2>/dev/null; fail "$h: stop.sh left netheal grandchild alive"; }
    [ ! -f $W/netheal.pid ] && [ ! -d $W/netheal.lock ] || fail "$h: stop.sh left netheal.pid or netheal.lock"

    # netd_wedged CLK_TCK scaling (HZ=100, 250, 1000 and fallback to 100 on empty/non-numeric/0)
    NET_PROC=$T/proc-$h; mkdir -p $NET_PROC/4321/task/4321
    echo netd > $NET_PROC/4321/comm
    statf=$NET_PROC/4321/task/4321/stat
    mkstat() { printf '4321 (netd) R 1 1 1 0 -1 0 0 0 0 0 %s 0 0 0 20 0 1 0 0\n' "$1" > $statf; }
    check_hz() {
        FAKE_HZ=$1; DELTA=$2
        (
            chroot() { return 1; }
            getconf() { echo "$FAKE_HZ"; }
            sleep() { mkstat $((100 + DELTA)); }
            mkstat 100
            netd_wedged
        )
    }
    check_hz 100 149 && fail "$h: HZ=100 delta 149 counted as wedged"
    check_hz 100 150 || fail "$h: HZ=100 delta 150 not counted as wedged"
    check_hz 250 374 && fail "$h: HZ=250 delta 374 counted as wedged (hardcoded 150?)"
    check_hz 250 375 || fail "$h: HZ=250 delta 375 not counted as wedged"
    check_hz 1000 1499 && fail "$h: HZ=1000 delta 1499 counted as wedged"
    check_hz 1000 1500 || fail "$h: HZ=1000 delta 1500 not counted as wedged"
    for badhz in "" "invalid" "0"; do
        check_hz "$badhz" 149 && fail "$h: fallback HZ ($badhz) delta 149 counted as wedged"
        check_hz "$badhz" 150 || fail "$h: fallback HZ ($badhz) delta 150 not counted as wedged"
    done
done
echo "PASS net-resume"
