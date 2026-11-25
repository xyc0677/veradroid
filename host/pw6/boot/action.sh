#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 手势动作：由 veradroid-input 调用。 用法：sh action.sh <名字> [参数]
# 名字：clear（整屏刷新）invert（反色）light + / -（前光）warmth + / -（色温）panel（下拉面板）tl tr bl br（四角长按）poke（有人在用，别让 Kindle 睡）
#       eb <动作>（底边分三段 G_EDGE_THIRDS：veradroid-input 已经按 G_EB_L / G_EB_M / G_EB_R / G_EB_HOLD 选好动作，"back" 它自己发键，不经过这里）
#       hint back（veradroid-input 自己发了返回键，这里只画提示）
# 四角长按（G_TL/G_TR/G_BL/G_BR；左下角默认 kindle，其余 none）、双指轻点（G_TWO_TAP_ACT，默认 clear）、双指长按（G_TWO_LONG_ACT，默认 invert）能绑的动作
# （白名单，见 run_action；docs/host-settings.md 有完整列表）：
#   none back home panel switch kindle clear invert stop                       （原有，名字不变）
#   frontlight lighton lightoff  前光开关 / 开 / 关      sleep  立即休眠（屏保）     restart  重启 Android
#   screenshot  截图（存到 Android 的 Screenshots）      wifi  Wi-Fi 开关            refresh = clear，return = kindle（别名）
#   app.<包名>  打开指定应用（例如 app.com.tencent.weread.eink）
#   appopt  前台应用的显示设置（桌面的“应用优化”页；前台是桌面时打开下拉面板）。底边左段上划的默认动作
VD=${VD:-/mnt/base-us/veradroid}
W=/dev/shm/veradroid
X=$W/kfb
SYS=/mnt/base-us/system/veradroid
# 日志在内存盘里：不记 poke（每次触摸一条），超过 64 KB 就截掉前一半
AL=/dev/shm/veradroid-actions.log
if [ "$1" != poke ]; then
    [ "$(wc -c < $AL 2>/dev/null || echo 0)" -gt 65536 ] && { tail -c 32768 $AL > $AL.tmp 2>/dev/null && mv -f $AL.tmp $AL; }
    echo "[$(date +%T)] action $1 $2" >> $AL
fi
[ -f $SYS/settings.conf ] && . $SYS/settings.conf
# 给宿主的命令（FIFO，宿主没在听也不会卡住这个脚本）
send() { _p=$(cat $W/cmd.pid 2>/dev/null); [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null || return 0; ( echo "$1" > $X/ctl/cmd ) & }   # 没人监听就不发，否则每个手势留下一个永远阻塞的进程
# ---- 手势提示（docs/gesture-hint.md）：宿主自己让显示桥画一个“方块”小牌子（图标 + 粗体中文 + 英文小字），Android 卡住也看得到 ----
# GESTURE_HINT=all（默认：每个手势都提示）| stuck（只在 Android 没有响应时提示）| off
# POS = 牌子贴着哪条边 / 哪个角（t b l r tl tr bl br，逻辑画面的方向，旋转由显示桥处理），由下面的分派设置
POS=t
# 写显示桥的 FIFO：它恰好在检查之后退出的话，打开 FIFO 会一直等读者。放到后台、3 秒后还没写完就放弃
fifo_put() { ( echo "$1" > $X/epd.fifo ) & fp=$!; ( sleep 3; kill $fp 2>/dev/null ) </dev/null >/dev/null 2>&1 & }
badge() {   # badge <毫秒> <位置> <图标> <英文小字>|<中文>[|<第二行>]
    _p=$(cat $W/epd.pid 2>/dev/null); [ -p $X/epd.fifo ] && [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null || return 0
    fifo_put "BADGE $1 $2 $3 $4"
}
hint() { [ "${GESTURE_HINT:-all}" = all ] && badge 800 "$POS" "$1" "$2"; return 0; }   # hint <图标> <文字>
# 卡住时告诉用户怎么回 Kindle：说真的绑了“回 Kindle”的宿主手势（默认是左下角长按）
escape_text() {
    for c in TL:左上 TR:右上 BL:左下 BR:右下; do
        eval "a=\${G_${c%%:*}:-none}"; [ "$c" = BL:左下 ] && a=${G_BL:-kindle}   # 左下角默认回到 Kindle（settings.sh）
        case "$a" in kindle|return) echo "长按${c#*:}角回到 Kindle"; return ;; esac
    done
    case "${G_TWO_LONG:-1}:${G_TWO_LONG_ACT:-invert}" in 1:kindle|1:return) echo "两指长按回到 Kindle"; return ;; esac
    case "${G_TWO_TAP:-1}:${G_TWO_TAP_ACT:-clear}" in 1:kindle|1:return) echo "两指轻点回到 Kindle"; return ;; esac
    echo "Android 可能卡住了，请稍候"
}
# 交给桌面（VeraHome 的 HostService）处理的手势：它收到就把文件删掉。2 秒后文件还在 = Android 没接，换成“没有响应”，留 3 秒
watch_ctl() {   # watch_ctl <ctl 里的文件名>
    [ "${GESTURE_HINT:-all}" = off ] && return 0
    ( sleep 2; [ -e $X/ctl/$1 ] && { echo "[$(date +%T)] no response: $1" >> $AL; badge 3000 "$POS" stuck "NO RESPONSE|没有响应|$(escape_text)"; } ) </dev/null >/dev/null 2>&1 &
}
# 回到 Kindle 必须总能做到。命令监听一次只做一条：Android 换页卡死时它可能还卡在上一条命令里，exit 排在后面一直轮不到
# （2026-11-21 用户：番茄卡住后“回到 Kindle”没反应，只能强制重启；同一天 PW5 512 MB 的诊断里番茄编译把交换区占满）。
# 发出去 8 秒内没被接走（do_exit 一开始就写 $W/exit.ack）、Android 还开着又没冻结：自己起一个进程直接做（veradroid.sh --exit），
# 不经过命令监听。两边都做到了也没关系：do_exit 有锁，后到的那个什么都不做。
exit_send() {
    rm -f $W/exit.ack
    send exit
    ( i=0
      while [ $i -lt 16 ]; do usleep 500000; [ -e $W/exit.ack ] || [ -f $W/frozen ] && exit 0; i=$((i + 1)); done
      _p=$(cat $W/pid 2>/dev/null); [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null || exit 0
      echo "[$(date +%T)] exit not taken by the command listener in 8 s: veradroid.sh --exit" >> $AL
      cd / && exec setsid sh $VD/boot/veradroid.sh --exit ) </dev/null >/dev/null 2>&1 &
}
# 动作名 -> 做什么
run_action() {
    case "$1" in
        ''|none) ;;
        back|home|panel|switch|appopt)
            # appopt：桌面马上打开一个页面，本身就是反馈；牌子的字库里没有“显示优化”这几个字，只在没有响应时提示
            case "$1" in back) hint back "BACK|返回" ;; home) hint home "HOME|桌面" ;; panel) hint panel "PANEL|面板" ;; switch) hint switch "SWITCH APPS|切换" ;; esac
            # 每次都写一个新文件再改名（inotify 的 MOVED_TO，桌面每次都收得到）。以前是 touch：文件已经在的话（开机时桌面还没开始听、
            # 桌面进程被杀重启的那几秒里做了手势）touch 只改时间、桌面收不到事件，文件就一直留着，之后每次下划都是“没有响应”，
            # 直到重启 VeraDroid（用户诊断-20261120：开机时下划了一次，之后一整天应用里拉不出面板）。
            # 临时文件名以点开头：HostService 只认 act.*，不会把临时文件当手势吃掉；带 $$：连着两次手势不抢同一个临时文件
            : > $X/ctl/.act.$1.$$ && mv -f $X/ctl/.act.$1.$$ $X/ctl/act.$1; watch_ctl act.$1 ;;
        kindle|return) hint kindle "KINDLE|回 Kindle"; exit_send ;;
        clear|refresh) hint clear "REFRESH|清残影"; _p=$(cat $W/epd.pid 2>/dev/null); [ -p $X/epd.fifo ] && [ -n "$_p" ] && kill -0 "$_p" 2>/dev/null && fifo_put FULL ;;
        invert) hint invert "INVERT|反色"; send invert ;;
        stop) hint power "STOP|关闭 Android"; send stop ;;
        restart) hint restart "RESTART|重启 Android"; send restart ;;
        screenshot) hint camera "SCREENSHOT|截图"; send screenshot ;;
        jank) hint clear "LAG NOTED|已记下卡顿"; send jankdump ;;   # 卡顿记录（JANKREC=1 时有内容，docs/jank.md）
        frontlight|lighton|lightoff)
            frontlight "$1"
            [ "$(lipc-get-prop com.lab126.powerd flIntensity 2>/dev/null)" = 0 ] && hint light "LIGHT|前光 关" || hint light "LIGHT|前光 开" ;;
        sleep)
            # 立即休眠：和按一下电源键一样交给 powerd（它进屏保时 sleep_watch 冻结 Android、画屏保）。
            # powerButton 是 powerd 自己的“模拟电源键”属性；没有它的老固件退回 powerd_test -p
            lipc-set-prop -i com.lab126.powerd powerButton 1 2>/dev/null || powerd_test -p >/dev/null 2>&1 ;;
        wifi)
            # Wi-Fi 本身的状态（cmd disableInterfaces 的 wifi），不是飞行模式；飞行模式开着时算关（同 lib/common.sh wifi_state）
            v=$(lipc-hash-prop com.lab126.cmd disableInterfaces </dev/null 2>/dev/null | sed -n "s/^[[:space:]]*wifi = (\([0-9]*\)).*/\1/p" | head -1)
            case "$v" in 0) v=1 ;; 1) v=0 ;; *) v=$(lipc-get-prop com.lab126.cmd wirelessEnable 2>/dev/null) ;; esac
            [ "$(lipc-get-prop com.lab126.cmd wirelessEnable 2>/dev/null)" = 0 ] && v=0
            if [ "$v" = 1 ]; then hint wifi "WI-FI|Wi-Fi 关"; send "wifi 0"; else hint wifi "WI-FI|Wi-Fi 开"; send "wifi 1"; fi ;;
        app.*)
            # 打开指定应用：和 veradroid.sh 打开应用同一个通道（桌面的 HostService 监听 ctl/app）。包名只许普通字符
            pkg=${1#app.}
            case "$pkg" in ''|*[!A-Za-z0-9._]*) ;; *) hint app "OPEN APP|打开应用"; echo "$pkg" > $X/ctl/app.tmp && mv -f $X/ctl/app.tmp $X/ctl/app; watch_ctl app ;; esac ;;
    esac
}
# 前光：frontlight = 开关（关之前记下亮度，开时恢复；记不到就开到一半），lighton / lightoff
frontlight() {
    p=com.lab126.powerd
    cur=$(lipc-get-prop $p flIntensity 2>/dev/null); case "$cur" in ''|*[!0-9]*) return 0 ;; esac
    case "$1" in
        lightoff) want=off ;;
        lighton) want=on ;;
        *) [ "$cur" -gt 0 ] && want=off || want=on ;;
    esac
    if [ $want = off ]; then
        [ "$cur" -gt 0 ] && echo "$cur" > $W/fl.saved
        [ "$(lipc-get-prop $p flAuto 2>/dev/null)" = 1 ] && lipc-set-prop -i $p flAuto 0 2>/dev/null
        lipc-set-prop -i $p flIntensity 0
    else
        [ "$cur" -gt 0 ] && return 0
        n=$(cat $W/fl.saved 2>/dev/null)
        case "$n" in ''|*[!0-9]*|0) max=$(lipc-get-prop $p flMaxIntensity 2>/dev/null); n=$(( ${max:-24} / 2 )) ;; esac
        [ "$(lipc-get-prop $p flAuto 2>/dev/null)" = 1 ] && lipc-set-prop -i $p flAuto 0 2>/dev/null
        lipc-set-prop -i $p flIntensity "$n"
    fi
}
step() {   # $1 = 属性 $2 = + / -  按 2 档调，范围 0..最大
    p=com.lab126.powerd
    cur=$(lipc-get-prop $p $1 2>/dev/null); max=$(lipc-get-prop $p flMaxIntensity 2>/dev/null)
    [ -n "$cur" ] && [ -n "$max" ] || return
    [ "$2" = "+" ] && n=$((cur + 2)) || n=$((cur - 2))
    # 手动调亮度：自动亮度开着时 powerd 会按传感器改回去，先关掉（cmd.sh light_set 同理）
    [ $1 = flIntensity ] && [ "$(lipc-get-prop $p flAuto 2>/dev/null)" = 1 ] && lipc-set-prop -i $p flAuto 0 2>/dev/null
    [ $n -lt 0 ] && n=0; [ $n -gt $max ] && n=$max
    lipc-set-prop -i $p $1 $n
    [ "$max" -gt 0 ] 2>/dev/null || return 0
    if [ $1 = flIntensity ]; then hint light "LIGHT|亮度 $((n * 100 / max))%"; else hint warm "WARMTH|暖光 $((n * 100 / max))%"; fi
}
case "$1" in
    clear)  POS=t; run_action "${G_TWO_TAP_ACT:-clear}" ;;      # 双指轻点（veradroid-input 固定发 clear）
    invert) POS=t; run_action "${G_TWO_LONG_ACT:-invert}" ;;    # 双指长按 2 秒（固定发 invert）
    light)  POS=l; step flIntensity "$2" ;;                     # 左半屏上下滑
    warmth) POS=r; step currentAmberLevel "$2" ;;               # 右半屏上下滑
    panel)  POS=t; run_action panel ;;
    tl) POS=tl; run_action "${G_TL:-none}" ;;
    tr) POS=tr; run_action "${G_TR:-none}" ;;
    bl) POS=bl; run_action "${G_BL:-kindle}" ;;                 # 默认回到 Kindle（settings.sh）
    br) POS=br; run_action "${G_BR:-none}" ;;
    eb) POS=b; run_action "$2" ;;                               # 底边分三段（veradroid-input 传来的是动作名，run_action 只认白名单）
    hint)   case "$2" in back) POS=b; hint back "BACK|返回" ;; esac ;;   # 底边上滑 / 双指左右滑：返回键已经由 veradroid-input 发出
    poke)   touch $W/lastinput 2>/dev/null   # 最近一次触摸（后台预编译据此判断“有人在用”，compile.sh）
            lipc-set-prop -i com.lab126.powerd touchScreenSaverTimeout 1 2>/dev/null ;;
esac
