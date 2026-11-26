# SPDX-License-Identifier: GPL-3.0-or-later
# 输入：宿主侧的触摸前端 veradroid-input 独占真正的触摸屏，识别全局手势，其余原样转给一块 uinput 虚拟触摸屏，容器读这块。
# 返回 Kindle（冻结）时必须把触摸屏还给 Kindle 界面：给它发 SIGUSR1 释放，再回来时发 SIGUSR2 重新独占，虚拟设备一直在，
# 容器里绑的设备节点不会变。
# 蓝牙翻页器 / 键盘桥（可选，没有蓝牙的机型什么都不做）：触摸前端起来以后接着起，跟着放开 / 独占 / 停止
[ -f "$VD/boot/lib/bt.sh" ] && . "$VD/boot/lib/bt.sh"
type bt_start >/dev/null 2>&1 || { bt_start() { :; }; bt_release() { :; }; bt_grab() { :; }; bt_stop() { :; }; }
input_sysname() {   # $1 = 设备名；打印它的 eventN
    for d in /sys/class/input/input*; do
        [ "$(cat $d/name 2>/dev/null)" = "$1" ] && { ls $d | grep '^event'; return; }
    done
}
input_start() { input_touch_start || return 1; bt_start; return 0; }
input_touch_start() {
    # Firmware 5.19.5 renamed some PW6 touch nodes. Match common FocalTech/Elan/
    # Goodix names, then fall back to the only evdev node that is not a key device.
    REAL=$(input_find 'fts_ts|ft[0-9]|focal|touch|pt_mt|cyttsp|zforce|goodix|elan|synaptics|atmel|cypress|himax|novatek|ilitek|ili2|chipone|st16|melfas|zinitix|sec_touchscreen|touchscreen')
    if [ -z "$REAL" ]; then
        REAL=$(awk '/^N: Name=/ { n=$0 } /^H: Handlers=/ { if (n !~ /key|power|hall|gpio|button|volume/) { for (i=1;i<=NF;i++) if (match($i,/event[0-9]+$/)) { print "/dev/input/" substr($i,RSTART); exit } } }' /proc/bus/input/devices)
    fi
    [ -n "$REAL" ] || { t "input: no touch screen"; return 1; }
    t "input: real touch=$REAL"
    [ -x "$VD/bin/veradroid-input" ] || { t "input: veradroid-input missing, passing the raw touch screen through"; return 0; }
    # 多点触摸协议（按设备的 ABS 能力位：ABS_MT_SLOT = 47，ABS_MT_POSITION_X = 53）：
    #   B（有 slot）→ 走 veradroid-input（手势）；A（有 MT 坐标、没有 slot，入门款 2019 的 cyttsp5 按亚马逊 defconfig 编的是
    #   CYTTSP5_MT_A）→ veradroid-input 只认 slot，转不了，原样交给 Android（InputReader 认协议 A），没有全局手势；
    #   没有 MT 坐标 = 单点（PW5 的 goodix）→ 同样原样交给 Android。
    # PW4 / 入门款 2019 也可能装的是 goodix-ts（gt9xx，协议 B、2 点），名字带 goodix 但能走手势：按能力位判断，不按名字。
    MTP=$(touch_mt_proto "$REAL")
    t "input: multitouch protocol ${MTP}"
    case "$MTP" in A) TOUCH_RAW="protocol A" ;; none) TOUCH_RAW="single-touch" ;; *) TOUCH_RAW= ;; esac
    # 新的 veradroid-input（main 的 input/vinput.c）自己把协议 A / 单点转成 slot：有这个能力就照常走它（保留手势），只有旧程序才原样交给 Android
    [ -n "$TOUCH_RAW" ] && grep -q 'multitouch protocol A' "$VD/bin/veradroid-input" 2>/dev/null && { t "input: $TOUCH_RAW converted by veradroid-input"; TOUCH_RAW=; }
    if [ -n "$TOUCH_RAW" ] && [ "$(rot_deg)" = 0 ]; then
        KFB_TOUCH=$REAL
        chmod 666 "$KFB_TOUCH" 2>/dev/null
        t "input: $TOUCH_RAW touch screen -> raw $KFB_TOUCH (gestures disabled)"
        return 0
    fi
    case "$(cat /sys/class/input/$(basename "$REAL")/device/name 2>/dev/null)" in
        *single*)
            # PW5's goodix-ts is single-touch (ABS_X/ABS_Y + BTN_TOUCH), not MT-B.
            # Pass it through; the Android InputReader supports single-touch.
            # Rotated (KFB_ROTATE != 0): the coordinates must be turned, so it goes through veradroid-input after all.
            if [ "$(rot_deg)" != 0 ]; then
                t "input: goodix, rotated $(rot_deg): through veradroid-input"
            else
            KFB_TOUCH=$REAL
            chmod 666 "$KFB_TOUCH" 2>/dev/null
            t "input: single-touch goodix -> raw $KFB_TOUCH (gestures disabled)"
            return 0
            fi ;;
    esac
    chmod 666 /dev/input/uinput /dev/uinput 2>/dev/null
    : > $W/input.log
    if [ "$KFB_GESTURES" = 0 ]; then touch $W/nogestures; else rm -f $W/nogestures; fi
    # 四角长按 2 秒。哪些角算手势由 gestures 文件里的 G_CORNERS 决定（只有配了动作的角；none 的角照样把长按给 Android），
    # 改设置后下一次触摸就生效，所以这里总是打开
    cm=2000
    gestures_write
    # 边缘手势条的宽度（像素）：120 是按 300 ppi 定的（约 10 mm）；PW2 212 ppi 用 85 才是同样的 10 mm（机型文件 TOUCH_EDGE_PX）。
    # 长按 / 滑动的判定距离按同一比例（120 时就是以前的 60 / 80）
    ge=${TOUCH_EDGE_PX:-120}; case "$ge" in ''|*[!0-9]*) ge=120 ;; esac
    $VD/bin/veradroid-input $REAL $W/input.log $VD/boot/action.sh --w ${viewWidth:-1272} --h ${viewHeight:-1696} --edge $ge --window ${G_WINDOW:-40} --edge-hold $((ge / 2)) --edge-swipe $((ge * 2 / 3)) --edge-back ${KFB_EDGE_BACK:-1} --corner-ms $cm --flag $W/nogestures --inject $W/inject --gconf $W/gestures --fg $X/fg --touch-stamp $X/lasttouch --rt ${INPUT_RT:-5} $(input_cpufreq_arg) $(rot_args) >$W/input.err 2>&1 &
    echo $! > $W/input.pid
    i=0; while [ $i -lt 20 ]; do
        vt=$(input_sysname veradroid-touch); vk=$(input_sysname veradroid-keys)
        [ -n "$vt" ] && [ -n "$vk" ] && break
        usleep 200000; i=$((i + 1))
    done
    if [ -z "$vt" ]; then
        # 触摸前端没起来（崩了 / 没有 uinput）：把真正的触摸屏直接交给 Android。没有全局手势，但至少能点“返回 Kindle”，
        # 不会出现“Android 开着却点不动、Kindle 界面又停着”的死局。
        t "input: virtual touch screen did not appear, passing the raw touch screen through (no gestures)"
        input_touch_stop
        KFB_TOUCH=$REAL; KFB_KEYS_VIRT=
        chmod 666 "$KFB_TOUCH" 2>/dev/null
        export KFB_TOUCH KFB_KEYS_VIRT
        return 0
    fi
    KFB_TOUCH=/dev/input/$vt; KFB_KEYS_VIRT=/dev/input/$vk
    # 3.0 内核的老 Kindle 没有 devtmpfs / udev：uinput 新建的设备没有 /dev/input/eventN 节点（PW3 模拟器实测），
    # 按 sysfs 里的设备号自己建
    for n in $vt $vk; do
        [ -e /dev/input/$n ] && continue
        dev=$(cat /sys/class/input/$n/dev 2>/dev/null)
        [ -n "$dev" ] && mknod /dev/input/$n c ${dev%%:*} ${dev##*:} && t "input: mknod /dev/input/$n ($dev)"
    done
    chmod 666 $KFB_TOUCH $KFB_KEYS_VIRT
    export KFB_TOUCH KFB_KEYS_VIRT
    t "input: touch $REAL -> $KFB_TOUCH, keys $KFB_KEYS_VIRT"
}
# 触摸屏的多点协议：B | A | none（读不到能力位时当 B，和以前一样走 veradroid-input）
touch_mt_proto() {
    caps=$(cat /sys/class/input/$(basename "$1")/device/capabilities/abs 2>/dev/null)
    [ -n "$caps" ] || { echo B; return; }
    set -- $caps; n=$#
    # 32 位内核：每个字 32 位，最后一个字是 0..31。第 47、53 位都在倒数第二个字里（第 15、21 位）
    [ $n -ge 2 ] || { echo none; return; }
    eval "w=\${$((n - 1))}"
    sl=$(( 0x$w & 0x8000 )); px=$(( 0x$w & 0x200000 ))
    if [ $sl != 0 ]; then echo B; elif [ $px != 0 ]; then echo A; else echo none; fi
}
input_release() { bt_release; alive $W/input.pid && kill -USR1 "$(cat $W/input.pid)" 2>/dev/null; }
input_grab()    { bt_grab; alive $W/input.pid && kill -USR2 "$(cat $W/input.pid)" 2>/dev/null; }
input_stop()    { bt_stop; input_touch_stop; }
input_touch_stop() { alive $W/input.pid && kill "$(cat $W/input.pid)" 2>/dev/null; rm -f $W/input.pid; }

# 触摸加速（veradroid-input --cpufreq，BOOST_MS / BOOST_KHZ 在 $W/gestures 里）：有 cpufreq 的内核才给
input_cpufreq_arg() {
    for d in /sys/devices/system/cpu/cpu0/cpufreq /sys/devices/system/cpu/cpufreq/policy0; do
        [ -w $d/scaling_min_freq ] && { echo "--cpufreq $d"; return 0; }
    done
}
