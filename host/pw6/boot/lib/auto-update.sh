#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Whole-image auto updater.  Runs detached from veradroid.sh; checks every 6h.
# Set /mnt/base-us/veradroid/autoupdate to 0 to disable, to notify to only write $VD/update.available (no install).
VD=/mnt/base-us/veradroid
BASE=https://veradroid.wenjin.jp
[ -f "$VD/network.conf" ] && . "$VD/network.conf"
CURL_OPTS=
[ -n "${VD_PROXY:-}" ] && CURL_OPTS="$CURL_OPTS -x $VD_PROXY"
[ -n "${VD_IP:-}" ] && CURL_OPTS="$CURL_OPTS --resolve veradroid.wenjin.jp:443:$VD_IP --resolve veradroid.wenjin.jp:80:$VD_IP"
[ -n "${VD_DNS:-}" ] && CURL_OPTS="$CURL_OPTS --dns-servers $VD_DNS"
curl_do() { curl $CURL_OPTS "$@"; }
# 直链优先（站长 2026-11-19）：元数据和安装器先从直链拿（dl.wenjin.jp：同一台服务器，不经过 Cloudflare），拿不到 / 不像样再走主站。
# dl.wenjin.jp 现在只放行下载路径，这两样回 404，于是退回主站（多一次很快的请求）；放行以后自动走直链。network.conf 里 VD_DIRECT= 关掉
case "$BASE" in https://veradroid.wenjin.jp) DIRECT=${VD_DIRECT-https://dl.wenjin.jp} ;; *) DIRECT=${VD_DIRECT-} ;; esac
au_meta() { # 主站地址 -> 元数据（stdout）
    if [ -n "$DIRECT" ]; then
        k=${1#*://}; k=${k#*/}
        j=$(curl_do -fsSL --connect-timeout 6 --max-time 20 "${DIRECT%/}/$k" 2>/dev/null) \
            && printf '%s' "$j" | grep -q '"version"' && printf '%s' "$j" | grep -q '"url"' && { printf '%s' "$j"; return 0; }
    fi
    curl_do -fsSL --max-time 20 "$1"
}
au_script() { # 主站地址 输出文件：完整下载、非空、语法检查通过才算（直链先，主站后）；日志记下用了哪个
    if [ -n "$DIRECT" ]; then
        k=${1#*://}; k=${k#*/}; rm -f "$2"
        if curl_do -fsSL --connect-timeout 6 --max-time 120 -o "$2" "${DIRECT%/}/$k" 2>/dev/null && [ -s "$2" ] && sh -n "$2" 2>/dev/null; then
            echo "$(date) auto update: installer from the direct link ${DIRECT%/}/$k" >> "$LOG"; return 0
        fi
    fi
    rm -f "$2"
    curl_do -fsSL --max-time 120 -o "$2" "$1" && [ -s "$2" ] && sh -n "$2" 2>>"$LOG"
}
LOG=/mnt/base-us/veradroid/logs/autoupdate.log
PID=/dev/shm/veradroid/autoupdate.pid
LOCK=/mnt/base-us/veradroid/state/autoupdate.lock
mkdir -p /mnt/base-us/veradroid/logs /mnt/base-us/veradroid/state 2>/dev/null
[ -f "$VD/autoupdate" ] && [ "$(cat "$VD/autoupdate")" = "0" ] && exit 0
# 只留一个实例。以前只看锁目录：锁目录没了（或者锁里的 pid 已经不是自动更新）以后，每次启动 Android 都会再起一个，
# 旧的那些照样每 6 小时醒来一次，谁也不退出（两台 PW6 测试机上各堆了 6-7 个 sh auto-update.sh，最老的 3 天）。
# 现在：① 锁里的 pid 必须真是一个自动更新进程（断电后 pid 会被别的进程用掉）；② 已经有别的自动更新进程在跑就不起；
# ③ 每次睡醒先确认锁还是自己的，不是就悄悄退出（不删别人的锁），已经多出来的实例最多 6 小时内自己消失。
au_is() { case "$(tr '\000' ' ' < /proc/$1/cmdline 2>/dev/null)" in "sh "*"/auto-update.sh "*) return 0 ;; esac; return 1; }   # 只认 "sh …/auto-update.sh"（不认 cp / grep 之类）
au_others() {
    for d in /proc/[0-9]*; do
        p=${d#/proc/}; [ "$p" = "$$" ] && continue
        au_is "$p" && [ "$(awk '{print $4}' $d/stat 2>/dev/null)" != "$$" ] && return 0   # 自己的子进程（$(...)）不算
    done
    return 1
}
if [ -d "$LOCK" ]; then
    old=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null && au_is "$old"; then exit 0; fi
    rm -rf "$LOCK"
fi
au_others && exit 0
mkdir "$LOCK" 2>/dev/null || exit 0
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK" 2>/dev/null' EXIT
au_mine() { [ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ] || { trap - EXIT; exit 0; }; }
echo $$ > "$PID"
# 点分版本号比较：$1 比 $2 新时返回 0（0.25 > 0.24，0.21.41 > 0.21.9）
ver_gt() {
    [ "$1" = "$2" ] && return 1
    printf '%s\n%s\n' "$1" "$2" | awk -F. '
        NR == 1 { n1 = split($0, a, ".") } NR == 2 { n2 = split($0, b, ".") }
        END { n = n1 > n2 ? n1 : n2
              for (i = 1; i <= n; i++) { x = a[i] + 0; y = b[i] + 0; if (x > y) exit 0; if (x < y) exit 1 }
              exit 1 }'
}
# 当前装的版本：bootstrap 写 $VD/version 和 system/veradroid/version（很早以前写 /mnt/base-us/veradroid-version）
cur_version() {
    cat "$VD/version" 2>/dev/null || cat /mnt/base-us/system/veradroid/version 2>/dev/null || cat /mnt/base-us/veradroid-version 2>/dev/null || echo 0   # root-legacy
}
sleep 120
while :; do
    au_mine
    [ -f "$VD/autoupdate" ] && [ "$(cat "$VD/autoupdate")" = "0" ] && exit 0
    cur=$(cur_version)
    # Old installs may not have a version file at all. Treat them as 0 so
    # auto-update can still bring them forward instead of silently skipping.
    [ -n "$cur" ] || cur=0
    # 与 bootstrap.sh 一致：4.9 内核机型（PW5/KT5/KT6）跟随 k49 包的版本，3.x / 4.1 跟随 legacy，其余跟 PW6 的 /api/bootstrap
    case "$(uname -r)" in
        3.*|4.1.*) meta="$BASE/static/bootstrap-legacy.json?t=$(date +%s)" ;;
        4.9.*) meta="$BASE/static/bootstrap-k49.json?t=$(date +%s)" ;;
        *) meta="$BASE/api/bootstrap" ;;
    esac
    # 装的是按类别编的系统（bootstrap 记在 $VD/image-class）：同时看类别清单和通用清单，哪个版本更新就按哪个，避免类别清单滞后卡住更新
    ic=$(cat "$VD/image-class" 2>/dev/null)
    json=; fam_json=
    [ -n "$ic" ] && json=$(au_meta "$BASE/static/bootstrap-$ic.json?t=$(date +%s)" 2>/dev/null)
    fam_json=$(au_meta "$meta" 2>/dev/null) || fam_json=
    if [ -n "$json" ] && [ -n "$fam_json" ]; then
        ic_v=$(printf '%s' "$json" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
        fam_v=$(printf '%s' "$fam_json" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
        if [ -z "$ic_v" ] || { [ -n "$fam_v" ] && ver_gt "$fam_v" "$ic_v"; }; then json=$fam_json; fi
    elif [ -z "$json" ]; then
        json=$fam_json
    fi
    [ -n "$json" ] || { sleep 3600; continue; }
    # 版本号有 "version":"x" 和 "version": "x" 两种写法
    new=$(printf '%s' "$json" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
    # 安装时记录的实验机型：更新时沿用，否则 bootstrap 会拒绝（要求 VD_EXPERIMENTAL=1）
    # loop 镜像机型停机时系统盘已卸载，回退读镜像外的 $VD/model；4.9.* 内核一律置 VD_EXPERIMENTAL=1
    m=$(cat /mnt/base-us/system/veradroid/model 2>/dev/null || cat "$VD/model" 2>/dev/null)
    case "$m" in ''|pw6|cs) ;; *) export VD_MODEL="$m" VD_EXPERIMENTAL=1 ;; esac
    case "$(uname -r)" in 4.9.*) export VD_EXPERIMENTAL=1 ;; esac
    [ -n "$new" ] || { sleep 3600; continue; }
    # 只往新版本更新：本地版本更新（比如用离线包装了新版）时绝不降级（KT6 模拟器实测 0.25 被拉回 0.24）
    if ! ver_gt "$new" "$cur"; then
        sleep 21600
        continue
    fi
    # 只提醒（设置 → 自动更新 → 只提醒）：记下有新版本，桌面下次回到前台时弹出更新内容，让用户自己选“更新 / 以后再说”
    if [ "$(cat "$VD/autoupdate" 2>/dev/null)" = notify ]; then
        if [ "$(sed -n 's/^version=//p' "$VD/update.available" 2>/dev/null)" != "$new" ]; then
            printf 'version=%s\ncurrent=%s\ntime=%s\n' "$new" "$cur" "$(date +%s)" > "$VD/update.available.tmp" && chmod 644 "$VD/update.available.tmp" && mv -f "$VD/update.available.tmp" "$VD/update.available"
            echo "$(date) auto update: $cur -> $new available (notify only)" >> "$LOG"
        fi
        sleep 21600
        continue
    fi
    # 不打断正在用的人：Android 在前台运行（没冻结）时，过半小时再看。冻结（回到 Kindle）或没在运行时才更新。
    P=$(cat /dev/shm/veradroid/pid 2>/dev/null)
    if [ -n "$P" ] && kill -0 "$P" 2>/dev/null && [ ! -f /dev/shm/veradroid/frozen ]; then
        sleep 1800
        continue
    fi
    echo "$(date) auto update $cur -> $new" >> "$LOG"
    # BH081-1：走到这里时用户在用 Kindle（Android 冻结或没开）。更新完不要把 Android 弹到前台盖住正在读的书：
    # VD_NO_START=1 让 bootstrap 装完不启动；冻结的旧 Android 已被 bootstrap 停掉，下次点“VeraDroid 启动”冷启动新版。
    # 日志只留最近 256 KB（下载进度行很多）。
    [ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 262144 ] && { tail -c 131072 "$LOG" > "$LOG.tmp" && mv -f "$LOG.tmp" "$LOG"; }
    # BH081-7：不要 `curl | sh`——下载中途断网/超时，sh 会执行半截脚本。先完整下载到文件，curl 成功且语法检查通过才执行。
    BS=/mnt/base-us/veradroid/state/autoupdate-bootstrap.sh
    rm -f "$BS.part"
    if au_script "$BASE/bootstrap.sh" "$BS.part"; then
        mv -f "$BS.part" "$BS"
        # VD_AUTOUPDATE=1：下载期间用户又打开了 Android 时，安装器等用户离开再替换，不把正在用的 Android 关掉；
        # 等太久（2 小时）它以 75 退出，什么都没动，过半小时再来
        VD_AUTOUPDATE=1 VD_NO_START=1 sh "$BS" < /dev/null >> "$LOG" 2>&1
        rc=$?
        if [ $rc = 75 ]; then echo "$(date) auto update deferred: android in use" >> "$LOG"; sleep 1800; continue; fi
    else
        echo "$(date) auto update: bootstrap download incomplete/invalid, retry in 1h" >> "$LOG"
        rm -f "$BS.part"; sleep 3600; continue
    fi
    after=$(cur_version)
    if [ "$after" != "$new" ]; then
        echo "$(date) auto update stopped: version state $after != $new" >> "$LOG"
        exit 1
    fi
    sleep 21600
done
