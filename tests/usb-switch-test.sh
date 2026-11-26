#!/bin/sh
# 插 USB 线时的“切换到 USB 传输”提示（boot/lib/usb.sh，2026-11-19 用户：“安卓里插上 usb 没反应，要退到 kindle 再接入”）。
#  1. usb_online：USB / Mains 类型的 power_supply 在线才算插着；PW6 的进水检测（USB_WET）不算；什么都读不到按插着算
#  2. usb_ask：Android 在前台才写 ctl/usb.ask；USB_ASK=0 不问；已经回到 Kindle（frozen）不问；屏保冻结时等它醒
#  3. usb_watch：com.lab126.hal usbPlugIn → ctl/usb.ask，usbPlugOut → ctl/usb.gone（假的 lipc-wait-event 送事件）；usb_watch_stop 收干净
#  4. usb_switch：有 MTP（新固件）走 do_exit（冻结 / 按 RETURN_MODE），没有 MTP（USB 大容量存储）走 do_stop（完全退出）
#  5. 接线：common.sh 载入、start_helpers 起、do_exit 停、stop.sh 收 usb.pid、cmd usbswitch / usbnoask、USB_ASK 是已知设置、
#     VeraHome 处理 ctl/usb.ask / usb.gone；pw6 和 k49 的 usb.sh 一样
cd "$(dirname "$0")/.." || exit 2
fail=0
bad() { echo "FAIL $*"; fail=1; }
SH=${SH:-sh}
T=${TMPDIR:-/tmp}/vd-usb.$$; rm -rf "$T"; mkdir -p "$T/bin"
trap 'rm -rf "$T"' EXIT
# 新机型（host/pw6、k49）和老机型（host/legacy）各在自己的分支上发：查这个分支里有 usb.sh 的那些
HS=; for H in host/pw6 host/k49 host/legacy; do [ -f $H/boot/lib/usb.sh ] && HS="$HS $H"; done
[ -n "$HS" ] || bad "no boot/lib/usb.sh in any host tree"
for H in $HS; do
    rm -rf "$T/w" "$T/x" "$T/sys" "$T/psy"; mkdir -p "$T/w" "$T/x/ctl" "$T/sys" "$T/psy/ac" "$T/psy/wet" "$T/psy/bat"
    echo USB > "$T/psy/ac/type"; echo 0 > "$T/psy/ac/online"; echo USB_WET > "$T/psy/wet/type"; echo 1 > "$T/psy/wet/online"
    echo Battery > "$T/psy/bat/type"; echo 1 > "$T/psy/bat/online"
    # 假的 lipc-wait-event（-s 一次一个事件）：有 $T/events 就打印出来退出，没有就一直等（像没有新事件一样）
    printf '%s\n' '#!/bin/sh' "if [ -f '$T/events' ]; then cat '$T/events'; rm -f '$T/events'; exit 0; fi" 'exec sleep 30' > "$T/bin/lipc-wait-event"; chmod 755 "$T/bin/lipc-wait-event"
    cat > "$T/run.sh" <<EOF
W=$T/w; X=$T/x; SYS=$T/sys; VD_TEST_PSY=$T/psy; PATH=$T/bin:\$PATH
t() { echo "\$*" >> $T/w/log; }
do_exit() { echo do_exit >> $T/w/calls; }; do_stop() { echo do_stop >> $T/w/calls; }
settings_load() { :; }; settings_publish() { echo publish >> $T/w/calls; }
. $H/boot/lib/usb.sh
case "\$1" in
online) usb_online && echo yes || echo no ;;
ask) usb_ask ;;
watch) usb_watch_start; sleep 1; [ -f "\$X/ctl/usb.ask" ] || sleep 2; usb_watch_stop ;;
switch) usb_switch ;;
noask) usb_noask ;;
esac
EOF
    o() { $SH "$T/run.sh" "$@" 2>>"$T/err"; }
    [ "$(o online)" = no ] || bad "$H: unplugged counted as plugged (USB_WET / battery taken for a cable?)"
    echo 1 > "$T/psy/ac/online"; [ "$(o online)" = yes ] || bad "$H: cable not seen"
    rm -rf "$T/psy"; mkdir -p "$T/psy"; [ "$(o online)" = yes ] || bad "$H: nothing readable should count as plugged"

    : > "$T/w/booted"; o ask; [ -f "$T/x/ctl/usb.ask" ] || bad "$H: no ctl/usb.ask while Android is in front"
    rm -f "$T/x/ctl/usb.ask"; echo USB_ASK=0 > "$T/sys/settings.conf"; o ask; [ -f "$T/x/ctl/usb.ask" ] && bad "$H: asked with USB_ASK=0"
    rm -f "$T/sys/settings.conf" "$T/x/ctl/usb.ask"; : > "$T/w/frozen"; o ask; [ -f "$T/x/ctl/usb.ask" ] && bad "$H: asked while back in Kindle"
    rm -f "$T/w/frozen"
    # 屏保冻结着插线：直接返回不轮询等待（避免插充电器后每 2 秒唤醒一次）
    : > "$T/w/saver_frozen"; o ask; [ -f "$T/x/ctl/usb.ask" ] && bad "$H: asked while screensaver frozen"
    rm -f "$T/w/saver_frozen"
    # 空闲冻结着插线：醒了再问
    : > "$T/w/idlefz"; ( sleep 3; rm -f "$T/w/idlefz" ) & o ask
    [ -f "$T/x/ctl/usb.ask" ] || bad "$H: not asked after idlefz ended"
    rm -f "$T/x/ctl/usb.ask"
    # 空闲冻结时插上、醒来前又拔了：不问。Android 在前台时不看 online（刚插上那一刻充电芯片可能还没报）
    mkdir -p "$T/psy/ac"; echo USB > "$T/psy/ac/type"; echo 0 > "$T/psy/ac/online"
    : > "$T/w/idlefz"; ( sleep 3; rm -f "$T/w/idlefz" ) & o ask
    [ -f "$T/x/ctl/usb.ask" ] && bad "$H: asked although the cable was gone when idlefz ended"
    o ask; [ -f "$T/x/ctl/usb.ask" ] || bad "$H: in front, fresh plug-in event: should ask even before the charger reports online"
    rm -f "$T/x/ctl/usb.ask"; rm -rf "$T/psy"; mkdir -p "$T/psy"

    echo usbPlugIn > "$T/events"; o watch
    [ -f "$T/x/ctl/usb.ask" ] || bad "$H: usbPlugIn event did not ask ($(cat "$T/w/log" 2>/dev/null | tail -2 | tr '\n' ' '))"
    [ -f "$T/w/usb.pid" ] && bad "$H: usb.pid left after usb_watch_stop"
    sleep 1; pgrep -f "$T/bin/lipc-wait-event" >/dev/null 2>&1 && { bad "$H: lipc-wait-event left running"; pkill -f "$T/bin/lipc-wait-event"; }
    echo usbPlugOut > "$T/events"; o watch
    [ -f "$T/x/ctl/usb.gone" ] && [ ! -f "$T/x/ctl/usb.ask" ] || bad "$H: usbPlugOut did not take the question back"

    rm -f "$T/w/calls"; ( VD_TEST_MTP=1 o switch ); [ "$(cat "$T/w/calls" 2>/dev/null)" = do_exit ] || bad "$H: MTP switch did not return to Kindle the usual way"
    [ -f "$T/w/usb.switched" ] || bad "$H: switch not recorded"
    if [ ! -f /etc/upstart/mtp.conf ]; then
        rm -f "$T/w/calls"; o switch; [ "$(cat "$T/w/calls" 2>/dev/null)" = do_stop ] || bad "$H: mass-storage switch did not stop Android completely"
    fi
    printf 'KFB_MODE=1\nUSB_ASK=1\n' > "$T/sys/settings.conf"; o noask
    [ "$(grep -c '^USB_ASK=' "$T/sys/settings.conf")" = 1 ] && grep -qx USB_ASK=0 "$T/sys/settings.conf" && grep -qx KFB_MODE=1 "$T/sys/settings.conf" || bad "$H: usbnoask settings.conf: $(tr '\n' ' ' < "$T/sys/settings.conf")"

    # 接线
    grep -q '\. "\$VD/boot/lib/usb.sh"' $H/boot/lib/common.sh || bad "$H: common.sh does not load usb.sh"
    grep -A3 '^start_helpers()' $H/boot/veradroid.sh >/dev/null && awk '/^start_helpers\(\)/{p=1} p&&/^}/{exit} p' $H/boot/veradroid.sh | grep -q usb_watch_start || bad "$H: start_helpers does not start the USB watch"
    awk '/^do_exit\(\)/{p=1} p&&/^}/{exit} p' $H/boot/lib/watchdog.sh | grep -q usb_watch_stop || bad "$H: do_exit does not stop the USB watch"
    [ "$(grep -c 'for f in \(guard \)\{0,1\}watchdog sleep lipc usb cmd' $H/boot/stop.sh)" = 2 ] && grep -q -e 'case "$f" in watchdog|sleep|lipc|usb|' -e 'case "$f" in guard|watchdog|sleep|lipc|usb|' $H/boot/stop.sh || bad "$H: stop.sh does not clean up usb.pid"
    grep -q 'usbswitch) type usb_switch' $H/boot/lib/cmd.sh && grep -q 'usbnoask)  type usb_noask' $H/boot/lib/cmd.sh || bad "$H: cmd.sh usbswitch / usbnoask"
    [ "$(grep -c 'for k in .* USB_ASK[ ;]' $H/boot/lib/settings.sh)" = 2 ] && grep -q '^    USB_ASK=1 ' $H/boot/lib/settings.sh || bad "$H: USB_ASK not a known setting"
done
[ -f host/pw6/boot/lib/usb.sh ] && { cmp -s host/pw6/boot/lib/usb.sh host/k49/boot/lib/usb.sh || bad "host/pw6 and host/k49 usb.sh differ"; }
J=android/packages/VeraHome/src/com/veradroid/home
grep -q 'name.equals("usb.ask")' $J/HostService.java && grep -q 'name.equals("usb.gone")' $J/HostService.java || bad "VeraHome does not handle ctl/usb.ask / usb.gone"
grep -q 'HostLink.cmd("usbswitch")' $J/HostService.java && grep -q '已连接电脑：要切换到 USB 传输吗？' $J/HostService.java && grep -q '传书' $J/HostService.java && grep -q 'adb' $J/HostService.java || bad "VeraHome USB dialog text / command"
grep -q '"USB_ASK"' $J/HomeActivity.java || bad "no USB_ASK switch in the settings"
[ $fail = 0 ] && echo "usb-switch: ok"
exit $fail
