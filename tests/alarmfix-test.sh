#!/bin/sh
# AlarmManager timerfd fix (host/*/boot/lib/container.sh alarmfix_*): the copy of libandroid_servers.so gets the two
# changes only when both original byte runs are where 1.00's images have them; the image's own file is never touched.
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
for h in pw6 k49 legacy; do
    c=host/$h/boot/lib/container.sh
    grep -q '^    alarmfix_prepare ' $c || fail "$c: container_start does not call alarmfix_prepare"
    grep -q '^        alarmfix_mount ' $c || fail "$c: run_in_ns does not call alarmfix_mount"
    sed -n '/^ALARMFIX_LIB=/,/^alarmfix_mount()/p' $c > /tmp/vd-af.$$.$h
done
cmp -s /tmp/vd-af.$$.pw6 /tmp/vd-af.$$.k49 && cmp -s /tmp/vd-af.$$.pw6 /tmp/vd-af.$$.legacy || { rm -f /tmp/vd-af.$$.*; fail "alarmfix differs between hosts"; }
rm -f /tmp/vd-af.$$.*
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mk() {   # mk <file> <table hex> <movt hex>: a 166540-byte file with the two runs at their offsets
    mkdir -p "${1%/*}"; dd if=/dev/zero of="$1" bs=166540 count=1 2>/dev/null
    printf "$(awk -v s="$2" 'BEGIN{h="0123456789abcdef";s=tolower(s);for(i=1;i<=length(s);i+=2){v=(index(h,substr(s,i,1))-1)*16+index(h,substr(s,i+1,1))-1;printf "\\%03o",v}}')" | dd of="$1" bs=1 seek=154856 conv=notrunc 2>/dev/null
    printf "$(awk -v s="$3" 'BEGIN{h="0123456789abcdef";s=tolower(s);for(i=1;i<=length(s);i+=2){v=(index(h,substr(s,i,1))-1)*16+index(h,substr(s,i+1,1))-1;printf "\\%03o",v}}')" | dd of="$1" bs=1 seek=52736 conv=notrunc 2>/dev/null
}
run() { R=$T/r W=$T/w sh -c 't() { echo "LOG $*"; }; eval "$(sed -n "/^ALARMFIX_LIB=/,/^alarmfix_mount()/p" host/pw6/boot/lib/container.sh | sed "\$d")"; '"$1"; }
mkdir -p $T/w
mk $T/r/system/lib/libandroid_servers.so 080000000000000009000000070000000100000000000000 c2f20005
before=$(cksum < $T/r/system/lib/libandroid_servers.so)
out=$(run alarmfix_prepare)
case "$out" in *"LOG alarmfix: AlarmManager timerfd clocks"*) ;; *) fail "prepare did not patch: $out" ;; esac
[ "$(cksum < $T/r/system/lib/libandroid_servers.so)" = "$before" ] || fail "the image's own library was changed"
[ "$(wc -c < $T/w/alarmfix/lib.so | tr -d ' ')" = 166540 ] || fail "copy has another size"
[ "$(run 'alarmfix_hex $W/alarmfix/lib.so 154856 24')" = 000000000000000007000000070000000100000000000000 ] || fail "clock table not patched"
[ "$(run 'alarmfix_hex $W/alarmfix/lib.so 52736 4')" = aff30080 ] || fail "EPOLLWAKEUP movt not patched"
cmp -l $T/r/system/lib/libandroid_servers.so $T/w/alarmfix/lib.so | wc -l | tr -d ' ' | grep -qx 5 || fail "more than the 5 expected bytes changed"
# ALARM_FIX=0, an already fixed / different library: no copy
out=$(ALARM_FIX=0 run alarmfix_prepare); [ -e $T/w/alarmfix ] && fail "ALARM_FIX=0 still made a copy"
mk $T/r/system/lib/libandroid_servers.so 000000000000000007000000070000000100000000000000 c2f20005
out=$(run alarmfix_prepare); [ -e $T/w/alarmfix ] && fail "patched a library whose clock table differs"
mk $T/r/system/lib/libandroid_servers.so 080000000000000009000000070000000100000000000000 aff30080
out=$(run alarmfix_prepare); [ -e $T/w/alarmfix ] && fail "patched a library whose epoll code differs"
rm -f $T/r/system/lib/libandroid_servers.so
out=$(run alarmfix_prepare); [ -e $T/w/alarmfix ] && fail "made a copy without a library"
# the source fix for future images is in small-patches.py
grep -q 'com_android_server_AlarmManagerService.cpp' android/small-patches.py || fail "small-patches.py has no AlarmManager timerfd fix"
echo "alarmfix: ok"
