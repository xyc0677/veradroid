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
    case "$(cat /sys/class/input/$(basename "$REAL")/device/name 2>/dev/null)" in
        *goodix*|*Goodix*|*single*)
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
    # 机身双击翻页：Kindle 的 gesturetapd 把加速度计 lis2du12 的双击变成 uinput 设备
    # gesture_tap 上的按键（只在 devcap 允许的机器上跑）。有这个设备就交给 veradroid-input，Android 在前台时变成翻页键。
    TAPDEV=$(input_find '"gesture_tap"')
    [ -n "$TAPDEV" ] && t "input: bezel tap device $TAPDEV"
    : > $W/input.err   # 追加方式打开（同 epd.log：watchdog 的 log_cap 会截短它）
    $VD/bin/veradroid-input $REAL $W/input.log $VD/boot/action.sh --w ${viewWidth:-1272} --h ${viewHeight:-1696} --edge 120 --window ${G_WINDOW:-40} --edge-hold 60 --edge-swipe 80 --edge-back ${KFB_EDGE_BACK:-1} --corner-ms $cm --flag $W/nogestures --inject $W/inject --gconf $W/gestures --fg $X/fg --ctl-dir $X/ctl ${TAPDEV:+--tapdev $TAPDEV} ${KFB_KEYS:+--keydev $KFB_KEYS} --rt ${INPUT_RT:-0} $(input_cpufreq_arg) $(type idle_vinput_args >/dev/null 2>&1 && idle_vinput_args) $(rot_args) >>$W/input.err 2>&1 &
    echo $! > $W/input.pid
    # 每 50 毫秒看一次（以前 200 毫秒），最多仍是 4 秒
    i=0; while [ $i -lt 80 ]; do
        vt=$(input_sysname veradroid-touch); vk=$(input_sysname veradroid-keys)
        [ -n "$vt" ] && [ -n "$vk" ] && break
        usleep 50000; i=$((i + 1))
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
    chmod 666 $KFB_TOUCH $KFB_KEYS_VIRT
    export KFB_TOUCH KFB_KEYS_VIRT
    t "input: touch $REAL -> $KFB_TOUCH, keys $KFB_KEYS_VIRT"
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
