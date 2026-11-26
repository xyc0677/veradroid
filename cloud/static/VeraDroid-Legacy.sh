#!/bin/sh
# Name: VeraDroid 安装（在线，老机型）
# Author: ajie / wenjin.jp
# Icon: data:image/png;base64,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
# After Vera jailbreak: put this file in the Kindle documents folder and tap it once.
# OLD models (PW2 / PW3 / PW4 / Voyage / Oasis 2 / Oasis 3): only verified in emulators. Installs or updates.
# Same flow as VeraDroid-Experimental.sh on these kernels; on any other Kindle it stops and changes nothing.
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
# say / fail：整屏写（screen 在下面定义，调用时才找；中文写不出来时 eips 写第二个参数里的英文）。安装器画了整屏进度画面
# （/tmp/vd-progress.on）时不再往上写：出错原因和代码已经在画面上
say() { echo "$1"; [ -f /tmp/vd-progress.on ] && return 0; if type screen >/dev/null 2>&1; then EN=${2:-VeraDroid}; screen "$1"; EN=; else eips 1 2 "${2:-VeraDroid}" >/dev/null 2>&1 || true; fi; }
fail() { echo "$1"; if [ ! -f /tmp/vd-progress.on ] && type screen >/dev/null 2>&1; then EN="${2:-VeraDroid: install failed}|Log: veradroid/logs/install.log"; screen "$1" "日志：veradroid/logs/install.log"; EN=; fi; sleep 10; exit 1; }
[ "$(id -u 2>/dev/null)" = 0 ] || fail "VeraDroid：需要 root 权限，请确认越狱已生效" "VeraDroid: needs root (is the jailbreak active?)"
command -v curl >/dev/null 2>&1 || fail "VeraDroid：找不到 curl，无法联网安装；请改用离线整包" "VeraDroid: no curl; use the offline package"
# ---- 只在模拟器上验证过的机型（3.0 内核 PW2 / PW3 / Voyage，4.1 内核 PW4 / 入门款 2019 / Oasis 2 / Oasis 3）----
# 先在屏幕上把风险说清楚，要用户在 10 秒到 2 分钟之内再点一次本脚本才继续（Kindle 上没有键盘，这是最简单的“确认”）。
# 真机验证过的实验机型（PW5 / KT5 / KT6 / Scribe，4.9 / 5.x 内核）不经过这里，流程和以前一样。
EMU=
case "$(uname -r)" in 3.*) EMU="3.0 内核（PW2 / PW3 / Voyage 一代）"; EMU_EN="kernel 3.0 (PW2/PW3/Voyage)" ;; 4.1.*) EMU="4.1 内核（PW4 / 入门款 2019 / Oasis 2 / Oasis 3）"; EMU_EN="kernel 4.1 (PW4/KT4/Oasis 2/Oasis 3)" ;; esac
EN=   # eips 后备时显示的英文（eips 没有中文字形）
screen() { # 多行文字，整屏显示。优先 fbink + Kindle 自带中文字体（eips 没有中文字形）；都不行就用 eips 写 $EN 里的英文（| 分行）
    eips -c >/dev/null 2>&1 || true
    ft=; for c in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do [ -f "$c" ] && ft=$c && break; done
    txt=$(printf '%s\n' "$@")
    w=$(cut -d, -f1 /sys/class/graphics/fb0/virtual_size 2>/dev/null); px=$((${w:-0} / 40)); [ "$px" -ge 24 ] 2>/dev/null || px=24   # 一行约 40 个半角字
    # 每个候选都试：/mnt/base-us 是 noexec 的老机型上 veradroid/bin/fbink 有 x 位却执行不了（rl-pw4 实测：整屏几乎是空的），
    # 动态链接的 fbink 在老固件（armel）上也起不来：失败就接着试下一个，最后用 eips 写英文
    if [ -n "$ft" ]; then
        for fb in "$(command -v fbink 2>/dev/null)" /mnt/base-us/veradroid/bin/fbink /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink; do
            [ -n "$fb" ] && [ -x "$fb" ] && "$fb" -e >/dev/null 2>&1 || continue
            if fe=$("$fb" -q -c -t regular="$ft",px=$px,top=40,left=30,right=30 "$txt" 2>&1 >/dev/null); then
                case "$fe" in
                    *Unidentified*|*SEND_UPDATE*|*rror*|*ailed*)
                        echo "screen: fbink cannot refresh here ($(printf '%s' "$fe" | head -2 | tr '\n' ' ')), eips"
                        eips -c >/dev/null 2>&1 || true ;;
                    *) return 0 ;;
                esac
            fi
        done
    fi
    r=1; printf '%s\n' "${EN:-$txt}" | tr '|' '\n' | while IFS= read -r l; do eips 1 $r "$l" >/dev/null 2>&1 || true; r=$((r + 2)); done
}
if [ -z "$EMU" ]; then
    EN="VeraDroid OLD-model installer|This Kindle is not an old model (kernel $(uname -r)).|Nothing was changed.|PW6: use the PW6 script from the website;|PW5 / Kindle 2022 / 2024: the Experimental one."
    screen "VeraDroid 老机型安装" "-------------------------------" "这台 Kindle 不是老机型（内核 $(uname -r)）。" \
        "什么都没有改动。" "PaperWhite 6 请用官网的 PW6 安装脚本；" "PW5、入门款 2022 / 2024 请用“实验机型”安装脚本。"
    echo "legacy scriptlet on a non-legacy kernel $(uname -r): stopped"
    sleep 15; exit 0
fi
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
        EN="VeraDroid: OLD Kindle model, TEST build|$EMU_EN, firmware ${fwv:-?}|Only tested in emulators, never on a real|device. It may not start; a failed start|returns to the Kindle by itself (~15 min).|The Kindle system partition is not changed.|Copy important books to a PC (USB) first!|Uninstall: Android Settings -> Device & Update -> Uninstall.|-----------------------------------------|To INSTALL: tap this script again in|10 s .. 2 min. To cancel: do nothing."
        while [ $i -lt 4 ]; do   # 书库界面可能会盖住：40 秒里重画几次
            [ -f "$CF" ] || break
            screen "VeraDroid 实验机型 · 测试版" "这台：$EMU，固件 ${fwv:-?}" "-------------------------------" \
                "这个机型只在模拟器上跑过，" "没有在真机上验证。可能无法启动；" "启动失败会自动回到 Kindle（最多约 15 分钟）。" \
                "不会改动 Kindle 系统分区，只在用户存储里放文件。" "安装前会记下书库清单并备份小文件。" \
                "请先用 USB 把重要的书拷到电脑！" "卸载：Android 设置→设备与更新→卸载 VeraDroid。" "-------------------------------" \
                "确认安装：10 秒后、2 分钟内再点一次本脚本。" "不想装：什么都不用做。"
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
        echo "dl_bs: failed or truncated from $u"
        rm -f /tmp/vd-bootstrap.sh
    done
    return 1
}
dl_bs || fail "VeraDroid：下载安装器失败或不完整，请检查 Wi-Fi；国内网络可改用离线整包" "VeraDroid: installer download failed or truncated (Wi-Fi?)"
VD_INSTALLER="$0" sh /tmp/vd-bootstrap.sh
rc=$?
rm -f /tmp/vd-bootstrap.sh
echo "bootstrap rc=$rc"
[ "$rc" = 0 ] || fail "VeraDroid：安装失败（代码 $rc）" "VeraDroid: install failed (code $rc)"
# 安装器不认这个文件名、不会删它；整个脚本已经在 { } 里读完了，自己删掉是安全的（卸载也不会留下这个入口）
case "$0" in */VeraDroid-Legacy.sh) rm -f "$0"; [ -d "$0.sdr" ] && [ ! -L "$0.sdr" ] && rm -rf "$0.sdr" ;; esac
say "VeraDroid：完成，正在启动" "VeraDroid: done, starting"
exit 0
}
