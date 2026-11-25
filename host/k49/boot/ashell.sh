#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 进入容器里的 shell（相当于 adb shell）：sh ashell.sh [命令…]
# 带上 Android 的基本环境：没有 ANDROID_ROOT / ANDROID_DATA 时 app_process 起不来，
# pm、am、settings 等命令直接 Aborted（libjavacore 初始化 ICU 失败）——曾被误判成“pm / am 坏了”。
W=/dev/shm/veradroid
P=$(cat $W/pid 2>/dev/null)
[ -n "$P" ] && kill -0 $P 2>/dev/null || { echo "VeraDroid container is not running"; exit 1; }
case "$(uname -r)" in 3.*|4.1.*)
    if grep -q '^ro.vd.artbase=' /proc/$P/root/system/build.prop 2>/dev/null; then ulimit -s 8192 2>/dev/null; else ulimit -s unlimited 2>/dev/null; fi ;;
esac
E='export PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data ANDROID_STORAGE=/storage EXTERNAL_STORAGE=/sdcard; '
if [ $# -gt 0 ]; then C="$E$*"; else C="${E}exec /system/bin/sh"; fi
if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then exec chroot /proc/$P/root /system/bin/sh -c "$C"; fi   # 3.0 内核：没有 /proc/PID/ns
exec nsenter -t $P -m -u -i -n -p -r -w /system/bin/sh -c "$C"
