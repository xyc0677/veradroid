#!/bin/sh
# Name: VeraDroid 更新
# Author: ajie / wenjin.jp
# Icon: data:image/png;base64,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
# Tap this book to download and apply the latest full image.
trap '' PIPE   # 书库启动器把输出管道给 fbink；fbink 缺失/崩溃时管道断开，不能让 SIGPIPE 杀掉脚本
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
BASE=https://veradroid.wenjin.jp
# eips 只有 ASCII 字形：以前这里的中文提示画不出来，下载失败时屏幕上什么都没有。安装器（bootstrap.sh）自己会画中文进度和错误。
# 整个脚本包在 { } 里：安装过程中这个文件可能被改写，sh 先读完再执行。
{
LOG=/mnt/us/veradroid/logs/install.log; mkdir -p /mnt/us/veradroid/logs 2>/dev/null
echo "==== $(date) update entry tapped" >> "$LOG" 2>/dev/null
eips 1 2 "VeraDroid: checking for updates...                " >/dev/null 2>&1
B=/tmp/vd-bootstrap.sh
BS_URL="$BASE/bootstrap.sh"
BS_ALT="https://dl.wenjin.jp/bootstrap.sh"
case "$(uname -r)" in
    3.*|4.1.*)
        BS_URL="$BASE/static/bootstrap-legacy.sh"
        BS_ALT="https://dl.wenjin.jp/static/bootstrap-legacy.sh"
        export VD_EXPERIMENTAL=1
        ;;
    4.*)
        export VD_EXPERIMENTAL=1
        ;;
esac
ok=0; rc=1
for u in "$BS_URL?t=$(date +%s)" "$BS_ALT?t=$(date +%s)"; do
    curl -fsSL --retry 2 --connect-timeout 20 --max-time 120 "$u" -o "$B" && [ -s "$B" ] && sh -n "$B" 2>/dev/null
    rc=$?
    if [ "$rc" = 0 ]; then
        ok=1; break
    fi
    rm -f "$B"
done
if [ "$ok" = 1 ]; then
    sh "$B"; rc=$?; rm -f "$B"; echo "update: bootstrap rc=$rc" >> "$LOG" 2>/dev/null
    [ "$rc" = 0 ] && eips 1 2 "VeraDroid: update finished                        " >/dev/null 2>&1 \
        || { eips 1 2 "VeraDroid: update failed (code $rc)               " >/dev/null 2>&1; eips 1 4 "log: veradroid/logs/install.log" >/dev/null 2>&1; sleep 10; }
else
    rm -f "$B"; echo "update: download of bootstrap.sh failed or truncated (rc $rc)" >> "$LOG" 2>/dev/null
    eips 1 2 "VeraDroid: cannot reach the server (rc $rc)       " >/dev/null 2>&1
    eips 1 4 "Check Wi-Fi, or tap VeraDroid.sh for details." >/dev/null 2>&1; sleep 10
fi
exit 0
}
