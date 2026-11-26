#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Native-library launcher and diagnostic-upload wiring must stay present.
set -u
B=cloud/static/bootstrap.sh
L=cloud/static/VeraDroid-Launch.sh
H=android/packages/VeraHome/src/com/veradroid/home/HomeActivity.java
C=host/pw6/boot/lib/cmd.sh
fail=0
[ -f "$L" ] || { echo 'FAIL launcher missing'; fail=1; }
grep -q '^# Name: VeraDroid' "$L" || { echo 'FAIL launcher title'; fail=1; }
grep -q 'boot/veradroid.sh home' "$L" || { echo 'FAIL launcher start'; fail=1; }
grep -q 'if \[ -f /mnt/base-us/veradroid/boot/veradroid.sh \]' "$L" || { echo 'FAIL launcher executable-bit check'; fail=1; }
grep -Eq 'if \[ (! )?-f /mnt/base-us/veradroid/boot/veradroid\.sh \]' kual/VeraDroid/start.sh || { echo 'FAIL KUAL executable-bit check'; fail=1; }
grep -q 'VeraDroid-Launch.sh' "$B" || { echo 'FAIL bootstrap launcher copy'; fail=1; }
grep -q 'HostLink.cmd("diag-upload")' "$H" || { echo 'FAIL upload button'; fail=1; }
grep -q 'diag-upload' "$C" || { echo 'FAIL host diag-upload'; fail=1; }
grep -q 'api/diag/upload' "$C" || { echo 'FAIL upload endpoint'; fail=1; }
[ "$fail" = 0 ] && echo 'LAUNCHER UPLOAD OK' || echo 'LAUNCHER UPLOAD FAILURES'
exit "$fail"
