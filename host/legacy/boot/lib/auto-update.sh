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
au_is() { case "$(tr '\000' ' ' < /proc/$1/cmdline 2>/dev/null)" in "sh "*"/auto-update.sh "*) return 0 ;; esac; return 1; }
au_others() {
    for d in /proc/[0-9]*; do
        p=${d#/proc/}; [ "$p" = "$$" ] && continue
        au_is "$p" && [ "$(awk '{print $4}' $d/stat 2>/dev/null)" != "$$" ] && return 0
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
# au_kmod_mine：这台机器现在加载的那一套模块，"<uname -r>/<套件>=<编号>"（和 pack-legacy.sh 写的 kmod.sets 同一格式、同一算法：
# 套件目录里全部文件按路径排序后内容的 sha256 前 12 位；选套件同 boot/lib/kmod.sh 的 kmod_dir）。认不出时什么都不输出。
au_kmod_mine() {
    akd=$VD/kmod/$(uname -r); [ -d "$akd" ] || return 0
    command -v sha256sum >/dev/null 2>&1 || return 0
    aks=
    if ls "$akd"/*.ko >/dev/null 2>&1; then aks=$akd
    else
        akv=$(uname -v)
        for akx in "$akd"/*/; do
            [ -f "${akx}uname-v" ] || continue
            while IFS= read -r akl || [ -n "$akl" ]; do [ "$akl" = "$akv" ] && aks=${akx%/}; done < "${akx}uname-v"
            [ -n "$aks" ] && break
        done
    fi
    [ -n "$aks" ] || return 0
    # 0.8.14 起包里有 kmod/kmod.files（套件由哪些文件组成）：只算列出的文件，套件目录里多出来的文件不改变编号。
    # 没有这个文件（0.8.13 装的）就算目录里的全部文件，和 0.8.13 一样（打包时保证两种算法结果相同）。
    if [ -f "$VD/kmod/kmod.files" ]; then
        echo "${aks#$VD/kmod/}=$(cd "$VD" && awk -v p="${aks#$VD/}/" 'index($0, p) == 1 && index(substr($0, length(p) + 1), "/") == 0' kmod/kmod.files | LC_ALL=C sort | xargs cat | sha256sum | cut -c1-12)"
    else
        echo "${aks#$VD/kmod/}=$(find "$aks" -type f | LC_ALL=C sort | xargs cat | sha256sum | cut -c1-12)"
    fi
}
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
    # 与 bootstrap.sh 一致：4.9 内核机型（PW5/KT5/KT6）跟随 k49 包的版本，不跟 PW6 的 /api/bootstrap
    # 与 bootstrap.sh 一致：3.0 / 4.1 内核（legacy 整包：Cortex-A9 系统 + binder 模块）跟随 bootstrap-legacy.json；
    # 以前 3.0 去比 /api/bootstrap（PW6 包的版本号），4.1 去比 k49 包的版本号：比的都不是自己装的那个包
    case "$(uname -r)" in
        3.*|4.1.*) meta="$BASE/static/bootstrap-legacy.json?t=$(date +%s)" ;;
        4.*) meta="$BASE/static/bootstrap-k49.json?t=$(date +%s)" ;;
        *) meta="$BASE/api/bootstrap" ;;
    esac
    json=$(au_meta "$meta" 2>/dev/null) || { sleep 3600; continue; }
    # 版本号有 "version":"x" 和 "version": "x" 两种写法
    new=$(printf '%s' "$json" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
    # 安装时记录的实验机型：更新时沿用，否则 bootstrap 会拒绝（要求 VD_EXPERIMENTAL=1）
    # loop 镜像机型停机时系统盘已卸载，回退读镜像外的 $VD/model；4.9.* 内核一律置 VD_EXPERIMENTAL=1
    m=$(cat /mnt/base-us/system/veradroid/model 2>/dev/null || cat "$VD/model" 2>/dev/null)
    case "$m" in ''|pw6|cs) ;; *) export VD_MODEL="$m" VD_EXPERIMENTAL=1 ;; esac
    case "$(uname -r)" in 4.9.*) export VD_EXPERIMENTAL=1 ;; esac
    [ -n "$new" ] || { sleep 3600; continue; }
    # 只往新版本更新：本地版本更新（比如用离线包装了新版）时绝不降级（KT6 模拟器实测 0.25 被拉回 0.24）
    # 只在模拟器上验证过的机型（$VD/tested = emulator-only）：内核模块（binder / uinput / zram）换了的更新绝不在后台悄悄装，
    # 要用户自己再点一次“VeraDroid 安装（实验机型）”（会再看到警告并确认）。模块没变（kmod 编号相同）的更新照常自动装。
    # 0.8.13 起：元数据里有每个套件的编号（"kmodsets"）时只看这台机器加载的那一套变没变（au_kmod_mine）；
    # 没有，或认不出这台机器用哪一套时，照旧比整体编号（"kmod" 和 $VD/kmod.id，0.8.12 及以前的更新程序只会比这个）。
    if [ "$(cat "$VD/tested" 2>/dev/null)" = emulator-only ]; then
        nk=$(printf '%s' "$json" | sed -n 's/.*"kmod": *"\([^"]*\)".*/\1/p')
        ks=$(printf '%s' "$json" | sed -n 's/.*"kmodsets": *"\([^"]*\)".*/\1/p'); mine=; kok=
        [ -n "$ks" ] && mine=$(au_kmod_mine)
        if [ -n "$mine" ]; then case " $ks " in *" $mine "*) kok=1 ;; esac
        else [ -n "$nk" ] && [ "$nk" = "$(cat "$VD/kmod.id" 2>/dev/null)" ] && kok=1; fi
        if [ -z "$kok" ]; then
            ver_gt "$new" "$cur" && echo "$(date) auto update $cur -> $new skipped: emulator-only model and the kernel modules changed (kmod ${nk:-?}${mine:+, set on this device $mine}); tap the installer to update" >> "$LOG"
            sleep 21600; continue
        fi
        export VD_EMUONLY_OK=1
    fi
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
    if au_script "$BASE/static/bootstrap-legacy.sh" "$BS.part"; then
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
