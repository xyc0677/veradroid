#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 离线调整系统盘大小（lib/storage.sh 的说明）。用法：sh resize.sh <MB>
# 由 st_request（桌面 / 管理网页的请求）用 setsid 脱离出来运行，也可以从 SSH 直接跑。
# Android 在跑：开机画面上显示进度 → 停 Android（不恢复 Kindle 界面）→ 调整 → 重新启动 Android。
# Android 没在跑：只调整，不启动。
{
VD=${VD:-$(cd "$(dirname "$0")/.." && pwd)}
cd /
. "$VD/boot/lib/common.sh"
exec >> $ST_LOG 2>&1 </dev/null
TO=$1
[ -f "$SYSTEM_IMG" ] || { echo "no system image on this model"; exit 2; }
st_num "$TO" || { echo "usage: resize.sh <MB>"; exit 2; }
tail -n 400 $ST_LOG > $ST_LOG.tmp 2>/dev/null && cat $ST_LOG.tmp > $ST_LOG; rm -f $ST_LOG.tmp
st_lock $$ || { st_log "another resize is running"; exit 3; }
ST_OWNER=$$; export ST_OWNER
trap 'st_unlock' EXIT
st_calc
FROM=$ST_IMG
e=$(st_check "$TO")
[ -z "$e" ] || { st_last refused resize "$FROM" "$TO" "$e"; exit 4; }
[ "$TO" -gt "$FROM" ] && OP=grow || OP=shrink
RES=$(st_tool resize2fs) || { st_last failed $OP "$FROM" "$TO" notool; exit 5; }
WAS=; P=$(cat $W/pid 2>/dev/null); [ -n "$P" ] && kill -0 "$P" 2>/dev/null && WAS=1
st_log "offline $OP $FROM -> $TO MB (android ${WAS:+running}${WAS:-stopped}, used $ST_FS_USED, kindle free $ST_KFREE)"
show() { [ -n "$WAS" ] && splash "$1" 36; st_log "step: $1"; }
st_state $OP "$FROM" "$TO" check
# 下面停 Android 时不恢复 Kindle 界面（$W/restarting），恢复靠 finish 最后重新启动 Android。本脚本要是没走到 finish 就没了
# （被杀、被 OOM 杀、shell 出错退出……断电不算），屏幕就停在“正在调整”上，Kindle 界面一直暂停着，只能强制重启。
# 一个小守护进程等本脚本结束：没看到 finish 的标记就替它收尾——重新启动 Android（common.sh 的 st_recover 会先把做了一半的调整收拾好），
# 起不来就 stop.sh 把 Kindle 界面放出来。
RDONE=$W/resize.$$.done
rm -f $W/resize.*.done 2>/dev/null
restart_after() { # Android 停过：重新启动它，起不来就回 Kindle 界面
    rm -f $W/restarting
    sh "$VD/boot/veradroid.sh" </dev/null >/dev/null 2>&1
    i=0; while [ $i -lt 60 ]; do
        sleep 2; i=$((i + 1))
        P=$(cat $W/pid 2>/dev/null); [ -n "$P" ] && kill -0 "$P" 2>/dev/null && return 0
        [ -d $W/starting ] || [ $i -lt 10 ] || break
    done
    st_log "android did not start after the resize: back to the kindle ui"
    sh "$VD/boot/stop.sh" >/dev/null 2>&1
}
if [ -n "$WAS" ]; then
    me=$$
    ( trap '' HUP INT TERM
      while kill -0 $me 2>/dev/null; do sleep 2; done
      [ -f $RDONE ] && { rm -f $RDONE; exit 0; }
      st_log "resize.sh (pid $me) ended without finishing: restarting android / restoring the kindle ui"
      # 它留下的 e2fsck / resize2fs / dd 还在改镜像的话先等它们做完（中途杀掉才真会弄坏文件系统），最多 30 分钟
      i=0; while pgrep -f "$SYSTEM_IMG" >/dev/null 2>&1 && [ $i -lt 900 ]; do sleep 2; i=$((i + 1)); done
      st_unlock
      restart_after ) </dev/null >/dev/null 2>&1 &
    [ -f $W/frozen ] || touch $X/ctl/sleep 2>/dev/null      # 显示桥别再画 Android 的帧
    splash "正在调整存储空间（1/4）：关闭 Android…" 36
    touch $W/restarting                                     # stop.sh：不要把 Kindle 界面放出来
    sh "$VD/boot/stop.sh" >/dev/null 2>&1
else
    storage_umount
fi
sync
# 镜像必须彻底放开：还挂着 / 还有 loop 指着它就不能动
i=0
while st_mounted || [ -n "$(img_loops)" ]; do
    storage_umount
    for l in $(img_loops); do loop_detach "$(loop_dev $l)"; done
    i=$((i + 1)); [ $i -ge 25 ] && break
    usleep 200000
done
finish() { # result reason
    rm -f $ST_STATE
    st_last "$1" $OP "$FROM" "$(st_img_mb)" "$2"
    st_notify_last
    st_unlock; trap - EXIT; unset ST_OWNER
    touch $RDONE          # 守护进程：正常收尾，不用它管
    if [ -n "$WAS" ]; then
        case "$1" in ok) splash "存储空间已调整为 $(st_img_mb) MB，正在启动…" 36 ;; *) splash "存储空间没有调整（数据没有变化），正在启动…" 36 ;; esac
        restart_after       # Android 起不来就把 Kindle 界面放出来，别停在开机画面上
    fi
    exit 0
}
if st_mounted || [ -n "$(img_loops)" ]; then st_log "image still busy"; finish failed busy; fi
show "正在调整存储空间（2/4）：检查文件系统… 请勿关机"
st_fsck || finish failed fsck
if [ $OP = grow ]; then
    st_state grow "$FROM" "$TO" extend
    show "正在调整存储空间（3/4）：扩大到 $TO MB… 请勿关机"
    if ! st_extend "$TO"; then st_cut "$FROM"; finish failed extend; fi
    sync
    st_state grow "$FROM" "$TO" resize
    if ! "$RES" "$SYSTEM_IMG"; then st_fsck; finish failed resize2fs; fi
else
    # resize2fs 自己估的最小块数：目标比它小就不做（什么都还没动）
    pm=$("$RES" -P "$SYSTEM_IMG" 2>/dev/null | sed -n 's/.*: *\([0-9][0-9]*\) *$/\1/p' | tail -1)
    l=$(st_sb 24)
    if st_num "$pm"; then
        pmb=$(awk -v b="$pm" -v l="${l:-2}" 'BEGIN { printf "%d", b * (2 ^ l) / 1024 + 1 }')
        st_log "resize2fs -P: minimum $pmb MB"
        [ $((pmb + 64)) -le "$TO" ] || finish refused small
    fi
    st_state shrink "$FROM" "$TO" resize
    show "正在调整存储空间（3/4）：缩小到 $TO MB… 请勿关机"
    if ! "$RES" "$SYSTEM_IMG" "${TO}M"; then st_fsck; finish failed resize2fs; fi
    sync
    st_state shrink "$FROM" "$TO" truncate
    st_cut "$TO" || finish failed truncate
fi
sync
show "正在调整存储空间（4/4）：再次检查… 请勿关机"
st_fsck || finish failed fsck-after
sync
finish ok offline
}
