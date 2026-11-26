#!/bin/sh
# KUAL menu: install or update VeraDroid, for jailbreaks whose library does not run .sh "books"
# (KUAL / MRPI only, older Universal jailbreaks). Same steps as the library scripts:
#   install.sh online    联网安装 / 更新，按内核自动选安装器（和书库脚本一样）：
#                          3.0 / 4.1 内核（老机型 PW2 / PW3 / PW4 / Voyage / Oasis 2 / 3 / 入门款 2019）
#                            = VeraDroid-Legacy.sh：先整屏说明，10 秒后、2 分钟内再点一次才装，用 static/bootstrap-legacy.sh
#                          其他内核 = VeraDroid-Experimental.sh：bootstrap.sh + VD_EXPERIMENTAL=1
#                            （bootstrap 自己认 PW6 / Colorsoft / PW5 / KT5 / KT6 / Scribe，PW6 上这个变量不起作用）
#   install.sh offline   documents 里的离线包（VeraDroid-Offline.sh；老机型同样要点两次）
#   install.sh experimental  旧菜单项的名字（升级前装的 menu.json 还在用），和 online 一样
# 同一个文件在 host/pw6、host/k49、host/legacy 的 kual/ 里，也是官网 static/veradroid-kual.zip 里的那份（两条线共用）。
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH
trap '' PIPE HUP
BASE=${VD_BASE:-https://veradroid.wenjin.jp}
LOG=/mnt/us/veradroid/logs/install.log
mkdir -p /mnt/us/veradroid/logs 2>/dev/null
if : >>"$LOG" 2>/dev/null; then exec >>"$LOG" 2>&1; fi
KR=${VD_TEST_UNAME:-$(uname -r)}
mode=${1:-online}
echo "==== $(date) KUAL install.sh $mode: uid=$(id -u 2>/dev/null) kernel $KR fw=$(head -1 /etc/prettyversion.txt 2>/dev/null)"
rm -f /tmp/vd-progress.on
EN=   # eips 后备时显示的英文（eips 没有中文字形），| 分行
screen() { # 多行文字整屏显示：fbink + Kindle 自带中文字体；都不行就用 eips 写 $EN
    eips -c >/dev/null 2>&1 || true
    ft=; for c in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do [ -f "$c" ] && ft=$c && break; done
    txt=$(printf '%s\n' "$@")
    w=$(cut -d, -f1 /sys/class/graphics/fb0/virtual_size 2>/dev/null); px=$((${w:-0} / 40)); [ "$px" -ge 24 ] 2>/dev/null || px=24
    if [ -n "$ft" ]; then   # 每个候选都试：noexec 的 /mnt/base-us、老固件上起不来的动态 fbink
        for fb in "$(command -v fbink 2>/dev/null)" /mnt/base-us/veradroid/bin/fbink /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink; do
            [ -n "$fb" ] && [ -x "$fb" ] || continue
            "$fb" -q -c -t regular="$ft",px=$px,top=40,left=30,right=30 "$txt" >/dev/null 2>&1 && return 0
        done
    fi
    r=1; printf '%s\n' "${EN:-$txt}" | tr '|' '\n' | while IFS= read -r l; do eips 1 $r "$l" >/dev/null 2>&1 || true; r=$((r + 2)); done
}
# 安装器画了整屏进度画面（/tmp/vd-progress.on）以后不再往上写：原因和代码已经在画面上
say() { echo "$1"; [ -f /tmp/vd-progress.on ] && return 0; EN=${2:-VeraDroid}; screen "$1"; EN=; }
fail() {
    echo "$1"
    if [ ! -f /tmp/vd-progress.on ]; then
        EN="${2:-VeraDroid: install failed}|Log: veradroid/logs/install.log"; screen "$1" "日志：veradroid/logs/install.log"; EN=
    fi
    sleep 10; exit 1
}
if [ "$(id -u 2>/dev/null)" != 0 ] && [ -z "${VD_SU_TRIED:-}" ]; then
    case "$mode" in online|offline|experimental) ;; *) fail "未知的菜单项：$mode" "Unknown menu item: $mode" ;; esac
    SELF=$0
    case "$SELF" in /*) ;; *) SELF="$(pwd)/$SELF" ;; esac
    for su in /var/local/kmc/bin/su /usr/bin/su /bin/su; do
        [ -x "$su" ] || continue
        echo "KUAL install.sh: not root (uid=$(id -u 2>/dev/null)), retrying through $su"
        exec "$su" -c "VD_SU_TRIED=1 VD_INSTALL_BG=${VD_INSTALL_BG:-} sh '$SELF' '$mode'" </dev/null
    done
fi
[ "$(id -u 2>/dev/null)" = 0 ] || fail "VeraDroid：需要越狱后的 root 权限" "VeraDroid: needs root (is the jailbreak active?)"
if [ -z "${VD_TEST_UNAME:-}" ] && [ -z "${VD_INSTALL_BG:-}" ] && command -v setsid >/dev/null 2>&1; then
    SELF=$0
    case "$SELF" in /*) ;; *) SELF="$(pwd)/$SELF" ;; esac
    cd /
    VD_INSTALL_BG=1 setsid sh "$SELF" "$mode" </dev/null >/dev/null 2>&1 &
    exit 0
fi
case "$mode" in
    offline)
        for d in /mnt/us/documents /mnt/base-us/documents; do
            [ -f "$d/VeraDroid-Offline.sh" ] && exec sh "$d/VeraDroid-Offline.sh"
        done
        fail "documents 里没有离线包（VeraDroid-Offline.sh）：请把官网离线整包里的文件全部放进 documents" "No offline package (VeraDroid-Offline.sh) in documents"
        ;;
    online|experimental) ;;
    *) fail "未知的菜单项：$mode" "Unknown menu item: $mode" ;;
esac
command -v curl >/dev/null 2>&1 || fail "VeraDroid：找不到 curl，无法联网安装；请改用离线整包" "VeraDroid: no curl; use the offline package"
export VD_EXPERIMENTAL=1
BS_URL="$BASE/bootstrap.sh"
EMU=
case "$KR" in
    3.*) EMU="3.0 内核（PW2 / PW3 / Voyage 一代）"; EMU_EN="kernel 3.0 (PW2/PW3/Voyage)" ;;
    4.1.*) EMU="4.1 内核（PW4 / 入门款 2019 / Oasis 2 / Oasis 3）"; EMU_EN="kernel 4.1 (PW4/KT4/Oasis 2/Oasis 3)" ;;
esac
if [ -n "$EMU" ]; then
    # 老机型：和 VeraDroid-Legacy.sh 一样，只在模拟器上验证过，先说明风险，10 秒到 2 分钟内再点一次才继续
    BS_URL="$BASE/static/bootstrap-legacy.sh"
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
        EN="VeraDroid: OLD Kindle model, TEST build|$EMU_EN, firmware ${fwv:-?}|Only tested in emulators, never on a real|device. It may not start; a failed start|returns to the Kindle by itself (~15 min).|The Kindle system partition is not changed.|Back up your books first.||To install: open KUAL again and tap|this menu item again after 10 s (within 2 min).|To cancel: do nothing."
        while [ $i -lt 4 ]; do   # 书库界面可能会盖住：40 秒里重画几次
            [ -f "$CF" ] || break
            screen "VeraDroid 实验机型 · 测试版" "这台：$EMU，固件 ${fwv:-?}" "-------------------------------" \
                "这个机型只在模拟器上跑过，" "没有在真机上验证。可能无法启动；" "启动失败会自动回到 Kindle（最多约 15 分钟）。" \
                "不会改动 Kindle 系统分区，只在用户存储里放文件。" "安装前会记下书库清单并备份小文件。" \
                "请先用 USB 把重要的书拷到电脑！" "卸载：Android 设置→设备与更新→卸载 VeraDroid。" "-------------------------------" \
                "确认安装：10 秒后、2 分钟内在 KUAL 里再点一次这一项。" "不想装：什么都不用做。"
            [ -f "$CF" ] || break
            sleep 10
            [ -f "$CF" ] || break
            i=$((i + 1))
        done
        exit 0
    fi
fi
echo "installer: $BS_URL (VD_EXPERIMENTAL=1${VD_EMUONLY_OK:+ VD_EMUONLY_OK=1})"
say "VeraDroid：正在下载安装器…" "VeraDroid: downloading the installer..."
curl -fsSL --retry 3 --connect-timeout 20 -o /tmp/vd-bootstrap.sh "$BS_URL?t=$(date +%s)" \
    || fail "VeraDroid：连不上服务器，请检查 Wi-Fi；国内网络可改用离线整包" "VeraDroid: cannot reach the server (Wi-Fi?)"
sh /tmp/vd-bootstrap.sh
rc=$?
rm -f /tmp/vd-bootstrap.sh
echo "bootstrap rc=$rc"
# 安装器在进度画面出现之前就停下（机型 / 分辨率 / 固件检查）时，原因只在日志里：把它的最后一句话也写到屏幕上
why=; whyen=; [ -f "$LOG" ] && why=$(tail -n 20 "$LOG" 2>/dev/null | grep -v -e '^bootstrap rc=' -e '^EN: ' -e '^[[:space:]]*$' | tail -1 | cut -c1-120)
[ -f "$LOG" ] && whyen=$(tail -n 20 "$LOG" 2>/dev/null | sed -n 's/^EN: //p' | tail -1 | cut -c1-120)
[ "$rc" = 0 ] || fail "VeraDroid：安装失败（代码 $rc）${why:+：$why}" "${whyen:-VeraDroid: install failed (code $rc)}"
exit 0
