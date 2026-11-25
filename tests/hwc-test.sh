#!/bin/sh
# Host test of android/hwcomposer/hwc_kindle.c (hwcomposer.kindle): frames with a dialog (dim layer) and the frames after
# it, with and without the bridge's fb2.ok (tests/hwc/run.c). Regression: legacy 0.8.10 shipped the module with a bridge
# that read "P 2" as page 0, and the module kept writing into page 2 after the dialog was gone, so the screen froze on
# the first dialog (阅读 / Legado's first start, the PW4 Wi-Fi dialog) until VeraDroid was restarted.
# Needs Linux with gcc (clock_nanosleep). The same source is checked against the legacy copy.
set -eu
[ "$(uname -s)" = Linux ] || { echo "SKIP hwc-test: needs a Linux host (clock_nanosleep)"; exit 0; }
R=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/kfb"
gcc -O2 -std=gnu99 -Wall -Wno-unused-function -pthread -DKFB_DIR="\"$T/kfb\"" -I"$R/tests/hwc/include" -I"$R/android/hwcomposer" \
    -o "$T/run" "$R/tests/hwc/run.c"
rc=0
for mode in ok nofb2 y8; do
    rm -f "$T/kfb"/*
    echo "64 48" > "$T/kfb/info"; echo 30 > "$T/kfb/hz"; echo 51 > "$T/kfb/hwc.dim"
    dd if=/dev/zero of="$T/kfb/fb" bs=$((64 * 48 * 4)) count=2 2>/dev/null
    mkfifo "$T/kfb/epd.fifo"
    [ $mode != nofb2 ] && : > "$T/kfb/fb2.ok"
    [ $mode = y8 ] && : > "$T/kfb/y8.ok"
    "$T/run" "$T/kfb" $mode || rc=1
    [ $mode = nofb2 ] && [ -e "$T/kfb/fb2" ] && { echo "FAIL nofb2: the module created page 2"; rc=1; }
done
# the bridges must announce page 2 (display/epd.c creates fb2.ok next to its shared buffer)
grep -q '"/fb2.ok"' "$R/display/epd.c" || { echo "FAIL display/epd.c does not create fb2.ok"; rc=1; }
# every host ships the module built from this source: same version string as the source's dump line
for so in "$R"/host/*/bin/hwcomposer.kindle.so; do
    [ -e "$so" ] || continue    # built modules are not in the source tree
    grep -q 'left page 2' "$so" || { echo "FAIL $so is older than android/hwcomposer/hwc_kindle.c (no fb2.ok handling)"; rc=1; }
done
[ $rc = 0 ] && echo "hwc-test: ok"
exit $rc
