# 空闲冻结（IDLE_FREEZE，秒；0 = 关，默认关）：Android 在前台、没人碰屏幕、画面也不变超过这么多秒时，
# 用 freezer 把容器冻住（和返回 Kindle / 屏保同一个 cgroup），slirp4netns 也停下。下一次按下屏幕（或机身双击），veradroid-input
# 在把这一下交给 Android 之前就写 THAWED 解冻（--idle-mark / --idle-freezer，几百微秒），所以点击照常生效。
# 不冻的情况：没开机完、信号模式（没有 freezer 的老内核：veradroid-input 解不了冻）、开着音频（VD_AUDIO=1）、蓝牙翻页器 / 键盘桥在跑、
# 前台应用在 IDLE_FREEZE_EXCLUDE 里、最近一个看门狗周期里容器网络收发超过 32 KB（在下载）。
# 冻住满 IDLE_WAKE 秒（默认 60）解冻一次，让闹钟 / 时钟 / 推送跑一跑；还是没人碰就在下一个周期再冻上。
# system_server 的 watchdog 线程在冻结前单独放进自冻结的子组 $FZ/wd，解冻后至少 4 秒（下一个看门狗周期）才放回去（freeze.sh BH081-5）。
idle_vinput_args() {
    echo "--touch-stamp $X/lasttouch --idle-mark $W/idlefz --idle-freezer $FZ/freezer.state --slirp-pid $W/slirp.pid"
}
idle_net_bytes() {
    awk '/^ *[^: ]+:/ && !/^ *lo:/ { sub(/^ *[^:]*:/, ""); s += $1 + $9; f = 1 } END { printf "%.0f\n", s + 0 }' /proc/$(cat $W/pid 2>/dev/null)/net/dev 2>/dev/null
}
idle_mtime() { stat -c %Y "$1" 2>/dev/null || echo 0; }
idle_wd_hold() {   # watchdog 线程先进自冻结的子组（父组随后冻结 / 解冻都不影响它）
    [ -d $FZ/wd ] && return 0
    ss=$(sed 's#^#/proc/#; s#$#/comm#' $FZ/cgroup.procs 2>/dev/null | xargs grep -lx system_server 2>/dev/null | head -n 1)
    ss=${ss#/proc/}; ss=${ss%/comm}
    [ -n "$ss" ] || return 0
    wt=$(grep -lx watchdog /proc/$ss/task/*/comm 2>/dev/null | head -n 1)
    wt=${wt%/comm}; wt=${wt##*/}
    [ -n "$wt" ] || return 0
    mkdir $FZ/wd 2>/dev/null || return 0
    { echo FROZEN > $FZ/wd/freezer.state && echo $wt > $FZ/wd/tasks; } 2>/dev/null || { rmdir $FZ/wd 2>/dev/null; return 0; }
}
idle_freeze() {
    type vinput_reset >/dev/null 2>&1 && vinput_reset idle   # 冻结前 Android 里不留按着的触点
    idle_wd_hold
    touch $W/idlefz                         # 先放标记：冻结中途来的触摸也会解冻
    date +%s > $W/idlefz.t
    echo FROZEN > $FZ/freezer.state 2>/dev/null
    if_i=0
    while [ -f $W/idlefz ] && [ "$(freezer_state)" != FROZEN ] && [ $if_i -lt 20 ]; do
        usleep 100000; if_i=$((if_i + 1))
    done
    if [ ! -f $W/idlefz ]; then             # 冻结的同时手指落下：veradroid-input 已经拿走标记，这次不冻
        echo THAWED > $FZ/freezer.state 2>/dev/null
        net_resume
        return 0
    fi
    if [ "$(freezer_state)" != FROZEN ]; then
        rm -f $W/idlefz
        echo THAWED > $FZ/freezer.state 2>/dev/null
        net_resume
        return 1
    fi
    net_pause
    type wifi_ps_restore >/dev/null 2>&1 && wifi_ps_restore
    echo $(( $(cat $W/idle.n 2>/dev/null || echo 0) + 1 )) > $W/idle.n
}
idle_unfreeze() {   # 看门狗自己解冻（定时唤醒 / 条件不满足了）
    rm -f $W/idlefz
    echo THAWED > $FZ/freezer.state 2>/dev/null
    net_resume
}
idle_tick() {
    [ -f $X/ctl/sleep ] && { rm -f $W/idlefz; return 0; }
    n=${IDLE_FREEZE:-0}; case "$n" in ''|*[!0-9]*) n=0 ;; esac
    now=$(date +%s)
    b=$(idle_net_bytes); pb=$(cat $W/idle.net 2>/dev/null); echo "$b" > $W/idle.net
    if [ -f $W/idlefz ]; then
        [ "$(freezer_state)" = FROZEN ] || { rm -f $W/idlefz; return 0; }
        wk=${IDLE_WAKE:-60}; case "$wk" in ''|*[!0-9]*) wk=60 ;; esac
        if [ "$n" = 0 ] || ! alive $W/input.pid || [ -f $W/fwd.id ] || [ -f $W/adbfwd.id ] || pkg_busy || { [ -f $W/dl.active ] && [ $((now - $(idle_mtime $W/dl.active))) -lt 35 ]; } || { alive $W/bt.pid && { [ -s $W/bt.state ] || { type bt_input_present >/dev/null 2>&1 && bt_input_present; }; }; } || [ $((now - $(cat $W/idlefz.t 2>/dev/null || echo 0))) -ge "$wk" ]; then
            idle_unfreeze; echo $now > $W/idle.woke
        fi
        return 0
    fi
    # 解冻以后（触摸 / 定时）：watchdog 线程放回去（最早 4 秒后）
    if [ -d $FZ/wd ] && [ "$(freezer_state)" = THAWED ]; then
        tw=$(idle_mtime $X/lasttouch); wo=$(cat $W/idle.woke 2>/dev/null || echo 0); [ "$wo" -gt "$tw" ] && tw=$wo
        [ $((now - tw)) -ge 4 ] && wd_release
    fi
    [ "$n" -gt 0 ] || return 0
    [ -f $W/booted ] || return 0
    fz_signal && return 0
    [ "$(freezer_state)" = THAWED ] || return 0
    alive $W/input.pid || return 0        # 触摸直通或 veradroid-input 未运行时禁止空闲冻结（无人监听触摸解冻）
    [ -f $W/fwd.id ] && return 0          # 无线传书开启期间禁止空闲冻结（避免冻住 8080 端口与 slirp4netns 导致网页打不开）
    [ -f $W/adbfwd.id ] && return 0       # 无线 ADB 开启期间同理不冻结
    pkg_busy && return 0                  # APK 安装 / dex2oat / 后台预编译期间禁止空闲冻结（避免超时强杀与 Watchdog 杀 system_server）
    [ -f $W/dl.active ] && [ $((now - $(idle_mtime $W/dl.active))) -lt 35 ] && return 0   # 商店/字体/OTA 弱网下载期间禁止空闲冻结
    [ "${VD_AUDIO:-0}" = 0 ] || return 0
    if pidof vdbtkeys >/dev/null 2>&1 && { [ -s $W/bt.state ] || { type bt_input_present >/dev/null 2>&1 && bt_input_present; }; }; then return 0; fi
    fg=$(cat $X/fg 2>/dev/null)
    case ",${IDLE_FREEZE_EXCLUDE:-}," in *",$fg,"*) [ -n "$fg" ] && return 0 ;; esac
    [ -n "$b" ] && [ -n "$pb" ] && [ $((b - pb)) -lt 4096 ] || return 0
    [ $((now - $(idle_mtime $X/lasttouch))) -ge "$n" ] || return 0
    # 画面：epd.log 每次刷新都写一行。定时唤醒后时钟会刷一下，那次不算（唤醒后过了 15 秒、没人碰就再冻）
    le=$(idle_mtime $W/epd.log); wo=$(cat $W/idle.woke 2>/dev/null || echo 0)
    if [ $((now - le)) -lt "$n" ]; then
        [ "$wo" -gt "$(idle_mtime $X/lasttouch)" ] && [ $((now - wo)) -ge 15 ] || return 0
    fi
    idle_freeze
}
