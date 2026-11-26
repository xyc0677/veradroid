#!/bin/sh
# Test one 4.1.15 module set on a real Kindle without installing VeraDroid (run as root from the set's directory):
#
#   cd /mnt/us/vd-kmod-test && sh kmod-test.sh
#
# 1. check-crc.sh: every CRC the modules import must equal the running kernel's, otherwise stop here
#    (nothing is loaded; the kernel would refuse the modules anyway).
# 2. insmod binder.ko / uinput.ko, look at /proc/misc, run bindertest (a self-contained binder round trip) if present.
# 3. rmmod both again. Modules live only in RAM: nothing is written to the system partition, a reboot also removes them.
# Everything goes to the screen and to kmod-test.log next to this script.
cd "$(dirname "$0")" || exit 1
L=$PWD/kmod-test.log
exec 3>&1 > "$L.tmp" 2>&1
echo "== $(date) $(uname -r) $(uname -v)"
grep -E '^(binder|uinput) ' /proc/modules && echo "NOTE: a binder/uinput module is already loaded"
sh ./check-crc.sh . ; rc=$?
if [ $rc != 0 ]; then echo "== stopped: check-crc.sh rc=$rc, nothing loaded"; else
    insmod ./binder.ko; echo "insmod binder rc=$?"
    insmod ./uinput.ko; echo "insmod uinput rc=$?"
    grep -E '^(binder|uinput) ' /proc/modules
    grep -wE 'vndbinder|uinput' /proc/misc
    if grep -qw vndbinder /proc/misc && [ -x ./bindertest ]; then
        n=/tmp/vd-test-vndbinder; rm -f $n
        mknod $n c 10 "$(awk '$2 == "vndbinder" {print $1}' /proc/misc)" && ./bindertest $n; echo "bindertest rc=$?"; rm -f $n
    fi
    dmesg | grep -iE 'binder|uinput|version|taint' | tail -n 20
    rmmod binder; echo "rmmod binder rc=$?"
    rmmod uinput; echo "rmmod uinput rc=$?"
    grep -cE '^(binder|uinput) ' /proc/modules | sed 's/^/modules still loaded: /'
    echo "== done"
fi
mv "$L.tmp" "$L"; cat "$L" >&3
