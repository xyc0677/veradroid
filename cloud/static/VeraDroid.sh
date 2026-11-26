#!/bin/sh
# Name: VeraDroid 安装（在线）
# Author: ajie / wenjin.jp
# Icon: data:image/png;base64,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
# After Vera jailbreak: put this file in the Kindle documents folder and tap it once.
# PW6: installs VeraDroid, or updates it when it is already installed. Needs Wi-Fi.
# 整个脚本包在 { } 里：sh 先读完再执行。安装过程中这个文件会被删掉 / 改写，
# 不包起来的话 sh 读到半截会在最后报一条“syntax error”（安装其实已经成功）。
{
trap '' PIPE   # 书库启动器把输出管道给 fbink；fbink 缺失/崩溃时管道断开，不能让 SIGPIPE 杀掉脚本
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
BASE=https://veradroid.wenjin.jp
# 安装和更新是同一个脚本：已经装过时再点一次，就是更新到最新版（书、应用和账号保留）。
LOG=/mnt/us/veradroid/logs/install.log
mkdir -p /mnt/us/veradroid/logs 2>/dev/null
if : >>"$LOG" 2>/dev/null; then exec >>"$LOG" 2>&1; fi   # 写不了日志也继续
echo "==== $(date) online install: uid=$(id -u 2>/dev/null) fw=$(head -1 /etc/prettyversion.txt 2>/dev/null) exp=${VD_EXPERIMENTAL:-0}"
rm -f /tmp/vd-progress.on
# ---- 屏幕上的说明 ----
# eips 只有 ASCII 字形（中文它画不出来）：以前 say / fail 用 eips 写中文，出错时屏幕上什么都没有（Scribe 用户：“网络不行”“闪退”）。
# 现在 fbink + Kindle 自带的中文字体能刷出来就写中文，刷不出来（没有能用的 fbink、Scribe 上旧 FBInk 不认识机型）就用 eips 写英文。
# screen 中文行… [-- 英文行…]：整屏显示
screen() {
    zh=; en=; sec=zh
    for l in "$@"; do
        if [ "$l" = -- ]; then sec=en; continue; fi
        if [ $sec = zh ]; then zh="$zh$l
"; else en="$en$l
"; fi
    done
    eips -c >/dev/null 2>&1 || true
    fb=; for c in "$(command -v fbink 2>/dev/null)" /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink /mnt/base-us/veradroid/bin/fbink; do
        [ -n "$c" ] && [ -x "$c" ] && "$c" -e >/dev/null 2>&1 && fb=$c && break   # 每个候选真的跑一次（KT6 上越狱自带的那个会段错误）
    done
    ft=; for c in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do [ -f "$c" ] && ft=$c && break; done
    if [ -n "$fb" ] && [ -n "$ft" ]; then
        sw=$("$fb" -e 2>/dev/null | tr ';' '\n' | sed -n 's/^viewWidth=//p' | head -1)
        case "$sw" in ''|*[!0-9]*) sw=1272 ;; esac
        px=$((26 * sw / 1272)); [ $px -ge 20 ] || px=20   # 字号随屏宽（Scribe 1860 宽）
        # FBInk 不认识这台机器（Scribe 上越狱带的旧 FBInk、模拟器）时它用 i.MX 的刷新命令，驱动拒绝（"MXCFB_SEND_UPDATE: ..."），
        # 返回值却是 0，屏幕上什么都没有（scr-ks 模拟器实测）：看它的报错，有就当画不出来，改用 eips
        if fe=$("$fb" -q -c -t regular="$ft",px=$px,top=$((40 * sw / 1272)),left=30,right=30 "$zh" 2>&1 >/dev/null); then
            case "$fe" in
                *Unidentified*|*SEND_UPDATE*|*rror*|*ailed*)
                    echo "screen: fbink cannot refresh here ($(printf '%s' "$fe" | head -2 | tr '\n' ' ')), eips"
                    eips -c >/dev/null 2>&1 || true ;;   # fbink 写进了帧缓冲但没刷出来：先清掉，免得 eips 刷新时带出半截中文
                *) return 0 ;;
            esac
        fi
    fi
    [ -n "$en" ] || en=$(printf '%s' "$zh" | tr -cd '\n -~')
    r=2; printf '%s' "$en" | while IFS= read -r l; do eips 1 $r "$l" >/dev/null 2>&1 || true; r=$((r + 2)); done
}
# say 中文 [英文]：一行进度（只写日志 + eips 英文；整屏进度画面出来以后就不写了）
say() { echo "$1"; [ -f /tmp/vd-progress.on ] || [ -z "${2:-}" ] || eips 1 2 "$(printf '%-50s' "$2")" >/dev/null 2>&1 || true; }
# fail 中文原因 [英文原因]：整屏说明，书库界面会盖住，30 秒里重画几次
fail() {
    echo "$1"
    if [ ! -f /tmp/vd-progress.on ]; then
        i=0; while [ $i -lt 3 ]; do
            screen "VeraDroid：没有安装" "" "$1" "" "日志：veradroid/logs/install.log" -- "VeraDroid: NOT installed" "" "${2:-Error, see veradroid/logs/install.log}"
            sleep 10; i=$((i + 1))
        done
    fi
    exit 1
}
# 这台机器的离线整包（按内核，和 bootstrap.sh / VeraDroid-Offline.sh 的规则一样）
case "$(uname -r)" in 3.*|4.1.*) OZ=VeraDroid-Offline-Legacy.zip ;; 4.*) OZ=VeraDroid-Offline-Experimental.zip ;; *) OZ=VeraDroid-Offline.zip ;; esac
# 下载安装器失败：再探一次服务器，说清楚卡在哪一步（Wi-Fi / 域名解析 / 连接 / TLS / 太慢），国内网络建议用离线整包。
# 只连我们自己的服务器，不找第三方。设 NZ（中文原因）NE（英文原因）
net_why() { # 第一次下载时 curl 的退出码
    r1=$1
    host=${BASE#*://}; host=${host%%/*}; host=${host%%:*}
    sug="国内网络常见：可以换个网络，或改用离线整包 $OZ（官网下载，电脑拷进 documents）"
    rt=$(awk '$2 == "00000000" && $1 != "lo" {print $1; exit}' /proc/net/route 2>/dev/null)
    if [ -z "$rt" ]; then NZ="没连上 Wi-Fi：请在 Kindle 的设置里连上 Wi-Fi，再点一次"; NE="No Wi-Fi. Connect Wi-Fi in Kindle settings, tap again."; return; fi
    w=$(curl -sS -o /dev/null -I --connect-timeout 10 --max-time 20 -w '%{time_namelookup} %{time_connect} %{time_appconnect} %{http_code}' "$BASE/" 2>/dev/null) && r2=0 || r2=$?
    set -- $w; tc=${2:-0}; ta=${3:-0}
    echo "net_why: first curl rc=$r1 probe rc=$r2 times=$w route=$rt"
    yr=$(date +%Y 2>/dev/null)
    case "$r2" in
        6) NZ="域名解析失败（DNS）：Wi-Fi 连上了，但解析不了 $host。$sug"; NE="DNS failed for $host. Use $OZ (offline)." ;;
        7) NZ="连不上服务器（连接被拒绝 / 被拦截）。$sug"; NE="Cannot connect to $host (refused/blocked). Use $OZ." ;;
        35|51|53|54|58|59|60|64|66|77|80|83|90|91)
            if [ "${yr:-2026}" -lt 2025 ] 2>/dev/null; then NZ="安全连接（TLS）失败：Kindle 的时间不对（现在显示 $(date '+%Y-%m-%d')）。请在 Kindle 设置里改对日期时间再点一次"; NE="TLS failed: Kindle clock is wrong ($(date +%F)). Fix date/time, tap again."
            else NZ="安全连接（TLS）失败（curl $r2）：网络在拦截 / 篡改 HTTPS。$sug"; NE="TLS/HTTPS failed (curl $r2). Use $OZ (offline)."; fi ;;
        28) if [ "$(awk -v t="$tc" 'BEGIN{print (t > 0)}')" = 1 ] && [ "$(awk -v t="$ta" 'BEGIN{print (t == 0)}')" = 1 ]; then
                NZ="连上了服务器，但安全连接（TLS）握手卡住（常见于 HTTPS 被干扰）。$sug"; NE="TLS handshake stalled (HTTPS blocked?). Use $OZ."
            else NZ="服务器 10 秒连不上（超时：被拦截或网络太差）。$sug"; NE="Connect to $host timed out. Use $OZ (offline)."; fi ;;
        0) NZ="网络太慢或中断了（服务器能连上，但下载没完成，curl $r1）。再点一次重试；$sug"; NE="Network too slow / dropped (curl $r1). Tap again, or use $OZ." ;;
        *) NZ="连不上服务器（curl $r2）。$sug"; NE="Cannot reach $host (curl $r2). Use $OZ (offline)." ;;
    esac
}
[ "$(id -u 2>/dev/null)" = 0 ] || fail "VeraDroid：需要 root 权限，请确认越狱已生效" "Needs root. Is the jailbreak active?"
command -v curl >/dev/null 2>&1 || fail "VeraDroid：找不到 curl，无法联网安装；请改用离线整包 $OZ" "No curl on this Kindle. Use $OZ (offline)."
say "VeraDroid：正在下载安装器…" "VeraDroid: downloading the installer..."
# 下载后先用 sh -n 校验完整性（弱网截断时不执行半截脚本；失败则自动切直链/重试一次）
dl_bs() {
    rm -f /tmp/vd-bootstrap.sh
    for u in "$BASE/bootstrap.sh?t=$(date +%s)" "https://dl.wenjin.jp/bootstrap.sh?t=$(date +%s)"; do
        curl -fsSL --retry 2 --connect-timeout 20 --max-time 120 "$u" -o /tmp/vd-bootstrap.sh && [ -s /tmp/vd-bootstrap.sh ] && sh -n /tmp/vd-bootstrap.sh 2>/dev/null && return 0
        rc=$?
        echo "dl_bs: failed or truncated from $u (rc=$rc)"
        rm -f /tmp/vd-bootstrap.sh
    done
    return "${rc:-1}"
}
dl_bs || { rc=$?; say "VeraDroid：下载安装器失败（curl $rc），正在检查网络…" "Download failed (curl $rc), checking network..."; net_why $rc; fail "VeraDroid：$NZ" "$NE"; }
VD_INSTALLER="$0" sh /tmp/vd-bootstrap.sh
rc=$?
rm -f /tmp/vd-bootstrap.sh
echo "bootstrap rc=$rc"
# 安装器在进度画面出现之前就停下（机型 / 分辨率 / 固件检查）时，原因只在日志里：把它的最后一句话也写到屏幕上
why=; whyen=; [ -f "$LOG" ] && why=$(tail -n 20 "$LOG" 2>/dev/null | grep -v -e '^bootstrap rc=' -e '^EN: ' -e '^[[:space:]]*$' | tail -1 | cut -c1-120)
[ -f "$LOG" ] && whyen=$(tail -n 20 "$LOG" 2>/dev/null | sed -n 's/^EN: //p' | tail -1 | cut -c1-120)   # 安装器给 eips 留的英文原因
[ "$rc" = 0 ] || fail "VeraDroid：安装失败（代码 $rc）${why:+：$why}" "${whyen:-Install failed (code $rc). See veradroid/logs/install.log}"
say "VeraDroid：完成，正在启动" "VeraDroid: done, starting"
exit 0
}
