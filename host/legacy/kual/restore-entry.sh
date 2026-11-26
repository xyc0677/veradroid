#!/bin/sh
# KUAL 菜单“恢复书库入口”：书库里的“VeraDroid 启动”不见了（误删、被新书挤到后面）时，把它放回书库并排到前面。
# 和 boot/lib/framework.sh 的 launcher_restore 同样几步，但不依赖它（KUAL 扩展可能比程序新或旧）。不启动 Android，不动别的文件。
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH
trap '' PIPE
VD=/mnt/base-us/veradroid
L="/mnt/us/documents/VeraDroid 启动.sh"
say() {
    zh1=$1; zh2=$2; en1=${3:-$1}; en2=${4:-$2}
    echo "$zh1 $zh2"
    ft=; for c in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do [ -f "$c" ] && ft=$c && break; done
    if [ -n "$ft" ]; then
        for fb in "$(command -v fbink 2>/dev/null)" /mnt/base-us/veradroid/bin/fbink /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink; do
            [ -n "$fb" ] && [ -x "$fb" ] && "$fb" -e >/dev/null 2>&1 || continue
            fe=$("$fb" -q -c -t regular="$ft",px=26,top=60,left=30,right=30 "$zh1
$zh2" 2>&1 >/dev/null) || continue
            case "$fe" in *Unidentified*|*SEND_UPDATE*|*rror*|*ailed*) continue ;; esac
            return 0
        done
    fi
    eips 1 2 "$en1                              " >/dev/null 2>&1 || true
    [ -n "$en2" ] && eips 1 3 "$en2                              " >/dev/null 2>&1 || true
}
log() { mkdir -p $VD/logs 2>/dev/null && echo "[$(date)] KUAL restore-entry: $1" >> $VD/logs/install.log 2>/dev/null || true; }
if [ ! -f "$VD/VeraDroid-Launch.sh" ]; then
    say "没找到 VeraDroid 程序（veradroid/VeraDroid-Launch.sh）" "请重新运行安装脚本（KUAL：VeraDroid → 安装 / 更新）" \
        "VeraDroid launcher not found" "Re-run installer (KUAL: VeraDroid -> Install)"
    log "no $VD/VeraDroid-Launch.sh"; sleep 6; exit 1
fi
[ -d /mnt/us/documents ] || { say "没找到 documents 文件夹" "" "documents folder not found" ""; sleep 6; exit 1; }
if [ -f "$L" ]; then
    # 在：改一下时间让书库把它排到“最近”的前面
    touch "$L" 2>/dev/null
    log "entry present, touched"
else
    sed 's/^# Name: .*/# Name: VeraDroid 启动/' "$VD/VeraDroid-Launch.sh" > "$L" 2>/dev/null && chmod 755 "$L" 2>/dev/null \
        || { rm -f "$L" 2>/dev/null; say "放不回去：documents 写不进去（空间满了？）" "" "Restore failed: cannot write to documents" ""; log "write failed"; sleep 6; exit 1; }
    log "entry restored"
fi
lipc-set-prop com.lab126.scanner reScanFile "$L" 2>/dev/null || true
say "书库入口“VeraDroid 启动”已放回" "回到书库就能看到（按“最近”排在前面）" \
    "VeraDroid library entry restored" "Return to library (sorted by Recent)"
sleep 4
exit 0
