#!/bin/sh
# VeraDroid online APK installer / updater.
# Run on a Kindle that already has VeraDroid + SSH:
#   curl -fsSL https://veradroid.wenjin.jp/install.sh | sh
set -eu
BASE="${VD_BASE:-https://veradroid.wenjin.jp}"
W=/dev/shm/veradroid
P=$(cat "$W/pid" 2>/dev/null || true)
[ -n "$P" ] && kill -0 "$P" 2>/dev/null || { echo "VeraDroid 没有运行，请先启动系统。"; exit 1; }
TMP=/mnt/base-us/system/veradroid/data/local/tmp
mkdir -p "$TMP"

run_in_android() {
    if [ -f /mnt/base-us/veradroid/boot/ashell.sh ]; then
        sh /mnt/base-us/veradroid/boot/ashell.sh "$1"
    elif [ "$(cat "$W/mode" 2>/dev/null || true)" = chroot ]; then
        chroot "/proc/$P/root" /system/bin/sh -c \
          "export PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data ANDROID_STORAGE=/storage EXTERNAL_STORAGE=/sdcard; $1"
    else
        nsenter -t "$P" -m -u -i -n -p -r -w/ /system/bin/sh -c \
          "export PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data ANDROID_STORAGE=/storage EXTERNAL_STORAGE=/sdcard; $1"
    fi
}

install_apk() {
    pkg="$1"; label="$2"
    json=$(curl -fsSL "$BASE/api/latest?package=$pkg") || { echo "$label: 查询失败"; return 1; }
    url=$(printf '%s' "$json" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
    ver=$(printf '%s' "$json" | sed -n 's/.*"version_name":"\([^"]*\)".*/\1/p')
    [ -n "$url" ] || { echo "$label: 没有发布版本"; return 1; }
    echo "下载 $label $ver ..."
    curl -fL --retry 3 -o "$TMP/online.apk" "$url" || { echo "$label: 下载失败"; return 1; }
    chmod 644 "$TMP/online.apk" 2>/dev/null || true
    echo "安装 $label ..."
    sz=$(stat -c%s "$TMP/online.apk" 2>/dev/null || wc -c < "$TMP/online.apk" | tr -d ' ')
    run_in_android "cmd package install -r -d -g -S $sz < /data/local/tmp/online.apk || pm install -r -d /data/local/tmp/online.apk" || return 1
    if [ "$pkg" = "com.veradroid.ime" ]; then
        run_in_android "ime enable com.veradroid.ime/.VeraImeService; ime set com.veradroid.ime/.VeraImeService" >/dev/null 2>&1 || true
    fi
}

install_apk com.veradroid.ime VeraIME
install_apk com.veradroid.home VeraHome
rm -f "$TMP/online.apk"
echo "完成。VeraHome 会被重启；如果画面没有变化，等几秒或按 Home。"
