#!/bin/sh
# KPM install hook for VeraDroid PW5 (experimental). Never factory-wipe on upgrade; preserves user settings/apps/fonts/books.
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH
trap '' PIPE
export VD_MODEL=pw5
export VD_EXPERIMENTAL=1
BASE="${VD_BASE:-https://veradroid.wenjin.jp}"
[ -f /mnt/base-us/veradroid/network.conf ] && . /mnt/base-us/veradroid/network.conf
CURL_OPTS=
[ -n "${VD_PROXY:-}" ] && CURL_OPTS="$CURL_OPTS -x $VD_PROXY"
[ -n "${VD_IP:-}" ] && CURL_OPTS="$CURL_OPTS --resolve veradroid.wenjin.jp:443:$VD_IP --resolve veradroid.wenjin.jp:80:$VD_IP"
[ -n "${VD_DNS:-}" ] && CURL_OPTS="$CURL_OPTS --dns-servers $VD_DNS"

LOG=/mnt/base-us/veradroid/logs/install.log
mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null || true
if : >>"$LOG" 2>/dev/null; then exec >>"$LOG" 2>&1; fi
echo "==== $(date) KPM install.sh (veradroid-pw5): uid=$(id -u 2>/dev/null) kernel=$(uname -r)"
rm -f /tmp/vd-progress.on

show() {
    zh=$1; en=${2:-$1}
    echo "$zh"
    [ -f /tmp/vd-progress.on ] && return 0
    ft=; for c in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do [ -f "$c" ] && ft=$c && break; done
    if [ -n "$ft" ]; then
        for fb in "$(command -v fbink 2>/dev/null)" /mnt/base-us/veradroid/bin/fbink /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink; do
            [ -n "$fb" ] && [ -x "$fb" ] || continue
            "$fb" -q -c -t regular="$ft",px=28,top=60,left=30,right=30 "$zh" >/dev/null 2>&1 && return 0
        done
    fi
    command -v eips >/dev/null 2>&1 && { eips -c >/dev/null 2>&1 || true; eips 1 3 "$en" >/dev/null 2>&1 || true; }
}

if [ "$(id -u 2>/dev/null)" != 0 ]; then
    show "需要越狱后的 root 权限，请检查越狱状态" "VeraDroid: root required (check jailbreak)"
    exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
    show "找不到 curl，请改用离线整包安装" "VeraDroid: curl missing, use offline package"
    exit 1
fi

# 剩余空间预检：首次安装需 >= 450 MB，覆盖升级需 >= 250 MB（避免下到一半磁盘爆满）
NEED_MB=450
UPGRADE=0
if [ -f /mnt/base-us/veradroid-sys.img ] || [ -d /mnt/base-us/system/veradroid ]; then
    NEED_MB=250
    UPGRADE=1
fi
AVAIL_MB=$(df -m /mnt/base-us 2>/dev/null | awk 'END{print $(NF-2)}')
if [ -n "$AVAIL_MB" ] && [ "$AVAIL_MB" -lt "$NEED_MB" ] 2>/dev/null; then
    show "存储空间不足（剩余 ${AVAIL_MB} MB，至少需要 ${NEED_MB} MB），请清理空间后重试" "Not enough space (${AVAIL_MB}MB free, need ${NEED_MB}MB)"
    exit 21
fi
[ "$UPGRADE" = 1 ] && echo "upgrade detected: preserving settings.conf, settings.apps, fonts, screensaver and user books"

TMP=/tmp/vd-kpm-bootstrap.$$
show "正在下载 VeraDroid PW5（实验性） 安装器…" "Downloading VeraDroid PW5 (experimental) installer..."
if ! curl $CURL_OPTS -fsSL --retry 4 --retry-delay 3 --connect-timeout 20 --max-time 300 "$BASE/bootstrap.sh?t=$(date +%s)" -o "$TMP"; then
    show "下载失败：请检查 Wi-Fi 后重试（或使用离线整包）" "Download failed: check Wi-Fi or use offline package"
    rm -f "$TMP"
    exit 14
fi
if [ "$UPGRADE" = 1 ]; then
    show "正在覆盖升级 VeraDroid PW5（实验性）（自动保留设置、应用、字体与书库）…" "Upgrading VeraDroid PW5 (experimental) (preserving user data)..."
else
    show "安装器已下载，正在开始安装 VeraDroid PW5（实验性）…" "Installer ready, starting VeraDroid PW5 (experimental)..."
fi
rc=0
sh "$TMP" || rc=$?
rm -f "$TMP"
if [ "$rc" != 0 ]; then
    show "安装失败（代码 $rc），请查看 veradroid/logs/install.log" "Install failed (code $rc). See veradroid/logs/install.log"
fi
exit $rc
