#!/bin/sh
# Name: VeraDroid 安装（在线，实验机型）
# Author: ajie / wenjin.jp
# Icon: data:image/png;base64,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
# After Vera jailbreak: put this file in the Kindle documents folder and tap it once.
# EXPERIMENTAL models (PW5 / KT5 / KT6 ...): only verified in eink-emulator. Installs or updates.
# 整个脚本包在 { } 里：sh 先读完再执行。安装过程中这个文件会被删掉 / 改写，
# 不包起来的话 sh 读到半截会在最后报一条“syntax error”（安装其实已经成功）。
{
trap '' PIPE   # 书库启动器把输出管道给 fbink；fbink 缺失/崩溃时管道断开，不能让 SIGPIPE 杀掉脚本
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
BASE=${VD_BASE:-https://veradroid.wenjin.jp}
export VD_EXPERIMENTAL=1   # 实验机型：bootstrap 自动识别 PW5/KT5/KT6，选对应的安装包
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
# ---- 只在模拟器上验证过的机型（3.0 内核 PW2 / PW3 / Voyage，4.1 内核 PW4 / 入门款 2019 / Oasis 2 / Oasis 3）----
# 先在屏幕上把风险说清楚，要用户在 10 秒到 2 分钟之内再点一次本脚本才继续（Kindle 上没有键盘，这是最简单的“确认”）。
# 真机验证过的实验机型（PW5 / KT5 / KT6 / Scribe，4.9 / 5.x 内核）不经过这里，流程和以前一样。
EMU=
case "$(uname -r)" in 3.*) EMU="3.0 内核（PW2 / PW3 / Voyage 一代）"; EMU_EN="kernel 3.0 (PW2/PW3/Voyage)" ;; 4.1.*) EMU="4.1 内核（PW4 / 入门款 2019 / Oasis 2 / Oasis 3）"; EMU_EN="kernel 4.1 (PW4/KT4/Oasis 2/Oasis 3)" ;; esac
if [ -n "$EMU" ]; then
    CF=/mnt/base-us/veradroid/state/confirm
    mkdir -p /mnt/base-us/veradroid/state 2>/dev/null
    bid=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); now=$(cut -d. -f1 /proc/uptime)
    set -- $(cat "$CF" 2>/dev/null); cw=${1:-}; cb=${2:-}; cu=${3:-0}
    rm -f "$CF"
    if [ "$cw" = install ] && [ "$cb" = "$bid" ] && [ $((now - cu)) -ge 10 ] && [ $((now - cu)) -le 120 ] 2>/dev/null; then
        echo "emulator-only model ($EMU): confirmed by the second tap after $((now - cu))s"
        export VD_EMUONLY_OK=1
    else
        fwv=$(sed -n 's/.*Kindle[[:space:]]*\([0-9][0-9.]*\).*/\1/p' /etc/prettyversion.txt 2>/dev/null | head -1)
        echo "emulator-only model ($EMU, fw $fwv): warning shown, waiting for the second tap"
        echo "install $bid $now" > "$CF"; sync
        i=0
        while [ $i -lt 4 ]; do   # 书库界面可能会盖住：40 秒里重画几次
            [ -f "$CF" ] || break
            screen "VeraDroid 实验机型 · 测试版" "这台：$EMU，固件 ${fwv:-?}" "-------------------------------" \
                "这个机型只在模拟器上跑过，" "没有在真机上验证。可能无法启动；" "启动失败会自动回到 Kindle（最多约 15 分钟）。" \
                "不会改动 Kindle 系统分区，只在用户存储里放文件。" "安装前会记下书库清单并备份小文件。" \
                "请先用 USB 把重要的书拷到电脑！" "卸载：Android 设置→设备与更新→卸载 VeraDroid。" "-------------------------------" \
                "确认安装：10 秒后、2 分钟内再点一次本脚本。" "不想装：什么都不用做。" \
                -- "VeraDroid: OLD Kindle model, TEST build" "$EMU_EN, firmware ${fwv:-?}" "Only tested in emulators, never on a real" \
                "device. It may not start; a failed start" "returns to the Kindle by itself (~15 min)." "The Kindle system partition is not changed." \
                "Copy important books to a PC (USB) first!" "-----------------------------------------" \
                "To INSTALL: tap this script again in" "10 s .. 2 min. To cancel: do nothing."
            [ -f "$CF" ] || break
            sleep 10
            [ -f "$CF" ] || break
            i=$((i + 1))
        done
        exit 0
    fi
fi
say "VeraDroid：正在下载安装器…" "VeraDroid: downloading the installer..."
# 只在模拟器上验证过的机型用老机型自己的安装器（static/bootstrap-legacy.sh，publish-legacy.sh 发布）：
# 和 PW6 / PW5 / KT5 / KT6 共用的 bootstrap.sh 分开，发布老机型时不动它们的安装器
BS_URL="$BASE/bootstrap.sh"; [ -n "$EMU" ] && BS_URL="$BASE/static/bootstrap-legacy.sh"
BS_ALT="https://dl.wenjin.jp/bootstrap.sh"; [ -n "$EMU" ] && BS_ALT="https://dl.wenjin.jp/static/bootstrap-legacy.sh"
dl_bs() {
    rm -f /tmp/vd-bootstrap.sh
    for u in "$BS_URL?t=$(date +%s)" "$BS_ALT?t=$(date +%s)"; do
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
