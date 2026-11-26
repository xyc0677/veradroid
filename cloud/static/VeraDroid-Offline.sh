#!/bin/sh
# Name: VeraDroid 安装（离线）
# Author: ajie / wenjin.jp
# Icon: data:image/png;base64,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
# Offline installer: the full bundle and bootstrap.sh sit next to this file.
# 全程写日志：失败时用 USB 取 /mnt/us/veradroid/logs/install.log
# 整个脚本包在 { } 里：sh 先读完再执行，防止安装过程中本文件被清理后 sh 读到半截报 syntax error。
{
trap '' PIPE   # 书库启动器把输出管道给 fbink；fbink 缺失/崩溃时管道断开，不能让 SIGPIPE 杀掉脚本
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
LOG=/mnt/us/veradroid/logs/install.log
mkdir -p /mnt/us/veradroid/logs 2>/dev/null
if : >>"$LOG" 2>/dev/null; then exec >>"$LOG" 2>&1; fi   # 写不了日志也要继续，不能让 exec 失败直接退出
echo "==== $(date) offline install: \$0=$0 uid=$(id -u 2>/dev/null) fw=$(cat /etc/prettyversion.txt 2>/dev/null | head -1)"
rm -f /tmp/vd-progress.on
# ---- 屏幕上的说明 ----
# eips 只有 ASCII 字形（中文它画不出来），以前 msg / fail 用 eips 写中文，屏幕上什么都没有，用户看到的就是“点了闪退”
# （Scribe 用户：拿错了离线包，安装器在屏幕上什么都没说就退了）。现在：fbink + Kindle 自带的中文字体能刷出来就写中文，
# 刷不出来（没有能用的 fbink、Scribe 上旧 FBInk 不认识机型、驱动拒绝刷新）就用 eips 写英文。
# screen 中文行… [-- 英文行…]：整屏显示；没给英文行时 eips 只能写出中文行里的 ASCII 部分
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
        px=$((26 * sw / 1272)); [ $px -ge 20 ] || px=20   # 字号随屏宽（Scribe 1860 宽，PW6 的 26 px 在上面太小）
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
# 安装器画了整屏进度画面（/tmp/vd-progress.on）时不再往上写字：出错原因和代码已经在画面上
msg() { echo "$*"; }
# fail 中文原因 [英文原因]：在屏幕上停够时间（书库界面会盖住，30 秒里重画几次）再退出
fail() {
    echo "$1"
    if [ ! -f /tmp/vd-progress.on ]; then
        i=0; while [ $i -lt 3 ]; do
            screen "VeraDroid 安装（离线）：没有安装" "" "$1" "" "什么都没有改动。日志：veradroid/logs/install.log" \
                -- "VeraDroid offline install: NOT installed" "" "${2:-Error, see veradroid/logs/install.log}" "" "Nothing was changed."
            sleep 10; i=$((i + 1))
        done
    fi
    exit 1
}
# 有的固件用 sh -c 之类的方式启动，$0 不是脚本路径：退回到 documents
HERE=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
case "$HERE" in ''|/|/bin|/usr/bin|/tmp) HERE=/mnt/us/documents ;; esac
ls "$HERE"/veradroid-*-*.tar.gz >/dev/null 2>&1 || HERE=/mnt/us/documents
echo "HERE=$HERE"
[ "$(id -u 2>/dev/null)" = 0 ] || fail "需要 root 权限运行（当前 uid=$(id -u)），请确认越狱已生效" "Needs root (uid=$(id -u)). Is the jailbreak active?"
# ---- 这台 Kindle 该用哪个离线包（按内核，和 bootstrap.sh 的检查一样）----
#   5.x 内核（PW6 / Colorsoft）          veradroid-pw6-<版本>.tar.gz     VeraDroid-Offline.zip
#   4.9 内核（PW5 / KT5 / KT6 / Scribe）  veradroid-k49-<版本>.tar.gz     VeraDroid-Offline-Experimental.zip
#   3.0 / 4.1 内核（老机型）              veradroid-legacy-<版本>.tar.gz  VeraDroid-Offline-Legacy.zip
# 拿错了包以前的样子：PW6 包在 Scribe 上被 bootstrap 的“实验机型”检查拒绝，原因用 eips 写中文 = 屏幕上什么都没有；
# 老机型包在 Scribe 上什么都不拦（包里也有 bin/vdlmk），会把 3.0 内核的系统装上去。现在在这里就停下，屏幕上写清楚该用哪个 zip。
fbw=$(cut -d, -f1 /sys/class/graphics/fb0/virtual_size 2>/dev/null)
case "$(uname -r)" in
    3.*|4.1.*) WANT=legacy; WANTZ=VeraDroid-Offline-Legacy.zip; DEV="老机型（$(uname -r | cut -d. -f1-2) 内核）"; DEVEN="older Kindle (kernel $(uname -r | cut -d. -f1-2))" ;;
    4.*) WANT=k49; WANTZ=VeraDroid-Offline-Experimental.zip
         case "$fbw" in 1860|1872|2480) DEV="Kindle Scribe"; DEVEN="Kindle Scribe" ;;
             *) DEV="实验机型（PW5 / 入门款 2022、2024 / Scribe）"; DEVEN="PW5 / Kindle 2022, 2024 / Scribe" ;; esac ;;
    *) WANT=pw6; WANTZ=VeraDroid-Offline.zip; DEV="PaperWhite 6 / Colorsoft"; DEVEN="PaperWhite 6 / Colorsoft" ;;
esac
echo "device: $DEVEN, kernel $(uname -r), fb width ${fbw:-?}: needs veradroid-$WANT-*.tar.gz ($WANTZ)"
# documents 里同一种离线包有好几个（旧版本没删、浏览器又下了一份）时，以前按文件名排序拿第一个，可能装回旧版本。
# 现在按文件名里的版本号挑最新的（点分的每一段按数字比：0.8.13 > 0.8.9，1.10 > 1.9），日志里记下选了哪个。
# 文件名里读不出版本号的（如 “veradroid-pw6-1.02 (1).tar.gz”）只在没有别的可选时才用。
ver_newer() { # a b：a 比 b 新返回 0
    awk -v a="$1" -v b="$2" 'BEGIN { n = split(a, x, "."); m = split(b, y, "."); k = (n > m) ? n : m
        for (i = 1; i <= k; i++) { p = x[i] + 0; q = y[i] + 0; if (p > q) exit 0; if (p < q) exit 1 }
        exit 1 }'
}
pick_bundle() { # 目录 变体：设 B（选中的文件）BV（它的版本，读不出时为空）NB（候选个数）
    B=; BV=; NB=0; pb_all=; pb_odd=
    for f in "$1"/veradroid-$2-*.tar.gz; do
        [ -f "$f" ] || continue
        NB=$((NB + 1)); pb_all="$pb_all $(basename "$f")"
        v=$(basename "$f" .tar.gz); v=${v#veradroid-$2-}
        case "$v" in ''|*[!0-9.]*|.*|*.|*..*) [ -n "$pb_odd" ] || pb_odd=$f; continue ;; esac
        if [ -z "$BV" ] || ver_newer "$v" "$BV"; then B=$f; BV=$v; fi
    done
    [ -n "$B" ] || B=$pb_odd
    [ "$NB" -gt 1 ] && echo "offline package: $NB bundles in $1:$pb_all; using the newest: $(basename "$B")"
    return 0
}
pick_bundle "$HERE" "$WANT"
[ -z "$B" ] || echo "offline package: $(basename "$B")"
if [ -z "$B" ]; then
    other=; for f in "$HERE"/veradroid-pw6-*.tar.gz "$HERE"/veradroid-k49-*.tar.gz "$HERE"/veradroid-legacy-*.tar.gz; do [ -f "$f" ] && other=$(basename "$f") && break; done
    if [ -n "$other" ]; then
        case "$other" in veradroid-pw6-*) oz=VeraDroid-Offline.zip ;; veradroid-k49-*) oz=VeraDroid-Offline-Experimental.zip ;; *) oz=VeraDroid-Offline-Legacy.zip ;; esac
        echo "wrong offline package: $other (from $oz); this device needs $WANTZ"
        i=0; while [ $i -lt 4 ]; do   # 书库界面可能会盖住：40 秒里重画几次
            screen "VeraDroid 安装（离线）：离线包拿错了" "" "这台 Kindle：$DEV" "documents 里的是：$other" "（来自 $oz，不是给这台机器的）" "" \
                "请到官网下载：$WANTZ" "解压后把里面的 3 个文件放进 documents，" "删掉 $other，再点“VeraDroid 安装（离线）”。" "" "什么都没有改动。" \
                -- "VeraDroid: WRONG OFFLINE PACKAGE" "" "This Kindle: $DEVEN" "Found: $other" "(from $oz)" "" "Download $WANTZ" "from the VeraDroid website, copy its 3 files" "into documents, delete $other, tap again." "" "Nothing was changed."
            sleep 10; i=$((i + 1))
        done
        exit 1
    fi
    fail "缺少安装包（veradroid-$WANT-…tar.gz）：请把 $WANTZ 里的文件全部放进 documents" "Missing veradroid-$WANT-*.tar.gz. Copy all files of $WANTZ into documents."
fi
VER=$BV
if [ -z "$VER" ]; then
    VER=$(basename "$B" .tar.gz | sed -n 's/^veradroid-[a-z0-9]*-\([0-9][0-9.]*\).*/\1/p' | sed 's/\.*$//')
    case "$VER" in ''|*[!0-9.]*|.*|*.|*..*|*.*.*.*.*) VER= ;; esac
fi
case "$B" in *veradroid-k49-*|*veradroid-legacy-*) export VD_EXPERIMENTAL=1 ;; esac   # 实验机型 / 老机型离线包
# ---- 只在模拟器上验证过的机型（3.0 内核 PW2 / PW3 / Voyage，4.1 内核 PW4 / 入门款 2019 / Oasis 2 / Oasis 3）----
# 先在屏幕上把风险说清楚，要用户在 10 秒到 2 分钟之内再点一次本脚本才继续（Kindle 上没有键盘，这是最简单的“确认”）。
# 真机验证过的实验机型（PW5 / KT5 / KT6 / Scribe，4.9 / 5.x 内核）不经过这里，流程和以前一样。
EMU=; EMU_EN=
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

[ -f "$HERE/bootstrap.sh" ] || fail "缺少 bootstrap.sh，请把 $WANTZ 里的文件全部放进 documents" "Missing bootstrap.sh. Copy all files of $WANTZ into documents."
BS_RUN="$HERE/bootstrap.sh"
if ! sh -n "$BS_RUN" 2>/dev/null; then
    # 兼容 Windows 换行符（CRLF）导致 sh 报语法错；若剥掉 \r 仍不通过则说明拷贝被截断/文件损坏
    tr -d '\r' < "$HERE/bootstrap.sh" > /tmp/vd-offline-bootstrap.sh 2>/dev/null || true
    if [ -s /tmp/vd-offline-bootstrap.sh ] && sh -n /tmp/vd-offline-bootstrap.sh 2>/dev/null; then
        echo "offline bootstrap.sh had CRLF, stripped to /tmp/vd-offline-bootstrap.sh"
        BS_RUN=/tmp/vd-offline-bootstrap.sh
    else
        rm -f /tmp/vd-offline-bootstrap.sh
        fail "bootstrap.sh 文件不完整或已损坏（常见于 USB 未安全弹出或下载中断）。请重新下载并完整解压 $WANTZ 到 documents 后重试" "bootstrap.sh is truncated or corrupted. Re-extract $WANTZ into documents and eject USB safely."
    fi
fi
msg "VeraDroid：使用离线整包安装…"
VD_BUNDLE="$B" VD_OFFLINE_BUNDLE="$B" VD_INSTALLER="$0" VD_HELPER="$HERE/bootstrap.sh" VD_VERSION="$VER" sh "$BS_RUN"
rc=$?
rm -f /tmp/vd-offline-bootstrap.sh
echo "bootstrap rc=$rc"
# 安装器在进度画面出现之前就停下（机型 / 分辨率 / 固件检查）时，原因只在日志里：把它的最后一句话也写到屏幕上
why=; whyen=; [ -f "$LOG" ] && why=$(tail -n 20 "$LOG" 2>/dev/null | grep -v -e '^bootstrap rc=' -e '^EN: ' -e '^[[:space:]]*$' | tail -1 | cut -c1-120)
[ -f "$LOG" ] && whyen=$(tail -n 20 "$LOG" 2>/dev/null | sed -n 's/^EN: //p' | tail -1 | cut -c1-120)   # 安装器给 eips 留的英文原因
[ "$rc" = 0 ] || fail "安装失败（代码 $rc）${why:+：$why}" "${whyen:-Install failed (code $rc). See veradroid/logs/install.log}"
exit 0
}
