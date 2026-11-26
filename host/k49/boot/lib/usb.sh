# 插 USB 线（2026-11-19，用户：“安卓里面插上 usb 没反应，要退到 kindle 再接入”）。
# Android 在前台时 Kindle 管 USB 的 volumd 被 SIGSTOP、MTP 服务被停掉（framework.sh），电脑上什么都不出现——这是故意的：
# 不能让 Android 和电脑同时写用户存储（/mnt/us）。现在插线时在 Android 里问一句：
#   “已连接电脑：要切换到 USB 传输吗？（会退回 Kindle，拔线后可一键回到安卓）”  切换 / 不用 / 不再询问
#   切换 = 和“返回 Kindle”同一套（cmd usbswitch → usb_switch）：Android 先冻结 / 停掉，Kindle 界面、MTP、volumd 恢复以后
#   volumd 处理停着时攒下的插线事件，Kindle 进 USB 传输。新固件是 MTP（Android 冻结着，不写存储）；没有 MTP 的老固件是 USB 大容量
#   存储，volumd 要把整个用户存储卸下来交给电脑，系统盘和容器的挂载不能留着 → 这种固件上一律完全退出（stop.sh），不冻结。
#   拔线以后在书库点“VeraDroid 启动”回到安卓（冻结的几秒就回来）。
# 插线的信号：udev 的 /lib/udev/bin/notifyusb 在 USB 控制器 add / remove 时发 lipc 事件 com.lab126.hal usbPlugIn / usbPlugOut
# （PW6 / k49：11211000.usb；老机型：fsl-usb2-udc、ci_hdrc.0 等，同一个脚本）。udevd 不归我们停，Android 在前台时也照发。
# 充电器也会触发（PW6 的 bd71827 分不出电脑和充电器，枚举要 MTP 起来以后才有），所以文字里说“已连接电脑”，充电时点“不用”；
# 不想被问：“不再询问”或 设置 → 设备 → 插上 USB 线时询问（USB_ASK=0）。传书网页和 adb 是不离开安卓的替代办法，提示里也写上。
# 测试：tests/usb-switch-test.sh（假的 lipc-wait-event 送事件）；真机上 lipc-send-event com.lab126.hal usbPlugIn 模拟插线。

# 现在有没有插着线：任何 USB / Mains 类型的 power_supply 在线，或 extcon 的 USB=1。都读不到时按插着算（只靠事件）。
usb_online() {
    uo_any=
    for uo_d in ${VD_TEST_PSY:-/sys/class/power_supply}/*; do
        [ -f "$uo_d/online" ] || continue
        case "$(cat "$uo_d/type" 2>/dev/null)" in USB|USB_*|Mains) ;; *) continue ;; esac
        case "$(cat "$uo_d/type" 2>/dev/null)" in USB_WET) continue ;; esac   # PW6 的进水检测（max20342_moisture），不是线
        uo_any=1
        [ "$(cat "$uo_d/online" 2>/dev/null)" = 1 ] && return 0
    done
    [ -n "$uo_any" ] && return 1
    return 0
}
usb_android_front() { [ ! -f $W/frozen ] && [ ! -f $W/saver_frozen ] && [ ! -f $W/idlefz ] && [ -f $W/booted ]; }
# 让桌面问（HostService 看 ctl/usb.ask）。空闲冻结着就等它醒（最多 10 分钟），中途拔了线就不问了。
usb_ask_cancel() {
    uap=$(cat $W/usb.ask.pid 2>/dev/null)
    if [ -n "$uap" ]; then
        for uac in $(pgrep -P "$uap" 2>/dev/null); do kill $uac 2>/dev/null; done
        kill "$uap" 2>/dev/null
    fi
    rm -f $W/usb.ask.pid
}
usb_ask() {
    _up=$$; read -r _up _ < /proc/self/stat 2>/dev/null || true
    grep -q '^USB_ASK=0$' $SYS/settings.conf 2>/dev/null && { t "usb: plugged in (USB_ASK=0, not asking)"; [ "$(cat $W/usb.ask.pid 2>/dev/null)" = "$_up" ] && rm -f $W/usb.ask.pid; return 0; }
    ua_i=0
    while ! usb_android_front; do
        if [ -f $W/frozen ] || [ -f $W/saver_frozen ]; then [ "$(cat $W/usb.ask.pid 2>/dev/null)" = "$_up" ] && rm -f $W/usb.ask.pid; return 0; fi          # 已经回到 Kindle 或屏保冻结：不轮询等待
        ua_i=$((ua_i + 1)); [ $ua_i -gt 300 ] && { [ "$(cat $W/usb.ask.pid 2>/dev/null)" = "$_up" ] && rm -f $W/usb.ask.pid; return 0; }
        sleep 2
    done
    # 等过（屏保 / 空闲冻结）才看线还在不在：刚插上的那一刻充电芯片可能还没报 online（事件是 USB 控制器先发的），
    # 那时候查会误判成“已拔掉”。拔线本身另有 usbPlugOut（usb_gone 收起提示）。
    if [ $ua_i -gt 0 ] && ! usb_online; then t "usb: plugged in and out again before Android was in front: not asking"; [ "$(cat $W/usb.ask.pid 2>/dev/null)" = "$_up" ] && rm -f $W/usb.ask.pid; return 0; fi
    mkdir -p $X/ctl 2>/dev/null
    echo "plug $(date +%s)" > "$X/ctl/usb.ask.tmp.$_up" 2>/dev/null && chmod 644 "$X/ctl/usb.ask.tmp.$_up" && mv -f "$X/ctl/usb.ask.tmp.$_up" $X/ctl/usb.ask
    [ "$(cat $W/usb.ask.pid 2>/dev/null)" = "$_up" ] && rm -f $W/usb.ask.pid
    t "usb: plugged in while Android is in front: asking whether to switch to USB transfer"
}
# 线拔了：桌面上还开着的提示收起来（ctl/usb.gone）
usb_gone() {
    usb_ask_cancel
    rm -f $X/ctl/usb.ask 2>/dev/null
    _ug=$$; read -r _ug _ < /proc/self/stat 2>/dev/null || true
    [ -d $X/ctl ] && : > "$X/ctl/usb.gone.tmp.$_ug" 2>/dev/null && chmod 644 "$X/ctl/usb.gone.tmp.$_ug" 2>/dev/null && mv -f "$X/ctl/usb.gone.tmp.$_ug" $X/ctl/usb.gone 2>/dev/null
    t "usb: unplugged"
}
usb_watch() {
    command -v lipc-wait-event >/dev/null 2>&1 || { t "usb: no lipc-wait-event, plug-in prompt off"; return 0; }
    me=$(exec sh -c 'echo $PPID')
    while kill -0 "$me" 2>/dev/null; do
        # 一次等一个事件（-s：最多 10 分钟，到点再订阅）。不用 -m 常驻：老固件（legacy 的 sleep.sh）也只用 -s；
        # stdout 进 $(...)、stderr 丢掉、stdin 关掉（5.19 上 lipc-wait-event 的输出曾经出现在屏幕上，见 sleep.sh）
        uw_t0=$(cut -d. -f1 /proc/uptime 2>/dev/null)
        uw_ev=$(lipc-wait-event -s 600 com.lab126.hal usbPlugIn,usbPlugOut </dev/null 2>/dev/null) || uw_ev=
        kill -0 "$me" 2>/dev/null || return 0
        case "$uw_ev" in
        usbPlugIn*) usb_ask_cancel; ( usb_ask ) </dev/null & echo $! > $W/usb.ask.pid ;;
        usbPlugOut*) usb_gone ;;
        *) # 没等到（超时）或者马上就失败了：失败时别空转
           [ "$(( $(cut -d. -f1 /proc/uptime 2>/dev/null || echo 0) - ${uw_t0:-0} ))" -ge 5 ] 2>/dev/null || sleep 5 ;;
        esac
    done
}
usb_watch_start() {
    usb_watch_stop
    usb_watch </dev/null &
    echo $! > $W/usb.pid
}
# 停掉监视连同它的 lipc-wait-event（只杀父进程的话，订阅和管道另一头会留下来）
usb_watch_stop() {
    usb_ask_cancel
    up=$(cat $W/usb.pid 2>/dev/null); [ -n "$up" ] || return 0
    for c in $(pgrep -P "$up" 2>/dev/null); do
        for cc in $(pgrep -P "$c" 2>/dev/null); do kill $cc 2>/dev/null; done
        kill $c 2>/dev/null
    done
    kill "$up" 2>/dev/null
    rm -f $W/usb.pid
    return 0
}
# 桌面上点了“切换”：回到 Kindle，让它进 USB 传输。和 cmd exit 一样在命令监听里直接调（do_exit / do_stop 最后 exit，监听随之结束，下次启动 / 解冻时 start_helpers 重起）。
usb_switch() {
    usb_ask_cancel
    rm -f $X/ctl/usb.ask $X/ctl/usb.gone 2>/dev/null
    date +%s > $W/usb.switched
    if [ -f /etc/upstart/mtp.conf ] || [ -n "${VD_TEST_MTP:-}" ]; then
        t "usb: switching to USB transfer (MTP): returning to Kindle"
        do_exit
    else
        # USB 大容量存储（没有 MTP 的老固件）：volumd 要卸下整个用户存储，系统盘 / 容器的挂载一个都不能留
        t "usb: switching to USB transfer (mass storage): stopping Android completely"
        do_stop
    fi
}
# “不再询问”：USB_ASK=0 存进 settings.conf（设置里能改回来）
usb_noask() {
    touch $SYS/settings.conf
    grep -v '^USB_ASK=' $SYS/settings.conf > $SYS/settings.conf.usb.tmp 2>/dev/null
    echo USB_ASK=0 >> $SYS/settings.conf.usb.tmp && mv -f $SYS/settings.conf.usb.tmp $SYS/settings.conf
    settings_load 2>/dev/null; settings_publish 2>/dev/null
    t "usb: USB_ASK=0 (not asking any more)"
}
