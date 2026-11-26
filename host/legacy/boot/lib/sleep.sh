# SPDX-License-Identifier: GPL-3.0-or-later
# 休眠 / 屏保：电源键和闲置休眠仍归 Kindle 的 powerd（lab126_gui 没停）。powerd 进入 / 退出屏保时广播
# goingToScreenSaver / outOfScreenSaver：进入时让容器停止写屏（ctl/sleep），画屏保；退出时去掉标记并要求整屏重绘。
screensaver_show() {
    # 每次休眠轮换一张。用户自己放的图优先（有就只用它们）：
    #   Kindle 上的 veradroid/screensaver/（USB 可见），或 Android 里的 Screensaver 文件夹（传书网页可传）。
    # 没有用户图时用 Kindle 自带的屏保图；固件里找不到才用封面缓存 / 包里的 default.png。
    # 透明背景的 PNG 不清屏，直接叠在正在读的那一页上；JPG 和不透明 PNG 先清成白底再居中按比例铺满。
    list=$W/saver.list
    : > "$list"
    # 桌面“屏保”页的选择（saver.sh）：kindle = 跳过用户图；one = 只用那一张；rotate / 没选过 = 下面原来的做法
    smode=; sone=
    type saver_choice >/dev/null 2>&1 && saver_choice
    [ "$smode" = one ] && [ -n "$sone" ] && echo "$sone" >> "$list"
    # 用户图：两个文件夹里的 png / jpg / webp / bmp / gif ……（saver_is_img，大小写都行）；saver.sh 太旧时只认 png / jpg / jpeg
    if [ "$smode" != kindle ] && [ ! -s "$list" ]; then
        for f in "$VD"/screensaver/* "$SYS"/data/media/0/Screensaver/*; do
            [ -f "$f" ] || continue
            case "$f" in */default.png|*/default.PNG) continue ;; esac
            if type saver_is_img >/dev/null 2>&1; then saver_is_img "$f" || continue
            else case "$f" in *.[pP][nN][gG]|*.[jJ][pP][gG]|*.[jJ][pP][eE][gG]) ;; *) continue ;; esac; fi
            echo "$f" >> "$list"
        done
    fi
    if [ ! -s "$list" ]; then
        # 用户没放图：用 Kindle 自带的屏保图（和原系统休眠时看到的一样）
        # 只要 bg_ss*（成人版的那组）：同一目录里还有儿童模式的 bg_kids_ss* 和一张纯黑的 bg_kcblackbackground.png
        # （PW6 5.19.5 实测），全收进来会轮到儿童图和整屏黑。固件没有 bg_ss* 时才退到这个目录里别的图（仍跳过 kids / 黑底）。
        for f in /usr/share/blanket/screensaver/bg_ss*.png; do
            [ -f "$f" ] && echo "$f" >> "$list"
        done
        if [ ! -s "$list" ]; then
            for f in /usr/share/blanket/screensaver/*.png; do
                case "${f##*/}" in *kids*|*black*) continue ;; esac
                [ -f "$f" ] && echo "$f" >> "$list"
            done
        fi
    fi
    if [ ! -s "$list" ]; then
        # 这个固件上没找到自带屏保图：退回最近的书封面缓存，再不行用包里的 default.png
        for f in $(ls -t /mnt/us/system/thumbnails/thumbnail_* /mnt/base-us/system/thumbnails/thumbnail_* 2>/dev/null | head -10); do
            [ -f "$f" ] && echo "$f" >> "$list"
        done
        [ -f "$VD/screensaver/default.png" ] && echo "$VD/screensaver/default.png" >> "$list"
    fi
    n=$(wc -l < "$list" 2>/dev/null | tr -d ' ')
    [ "${n:-0}" -gt 0 ] || return 0
    i=$(cat "$W/saver.index" 2>/dev/null || echo 0)
    case "$i" in ''|*[!0-9]*) i=0 ;; esac
    # 轮到的这张用不了（格式不认识、坏图、fbink 画不出来）就试下一张，最多试 8 张；都不行用 Kindle 自带的
    k=0
    while [ $k -lt "$n" ] && [ $k -lt 8 ]; do
        k=$((k + 1))
        i=$(( (i % n) + 1 ))
        img=$(sed -n "${i}p" "$list")
        [ -n "$img" ] || continue
        sdraw=$img
        type saver_resolve >/dev/null 2>&1 && saver_resolve "$img"
        [ -n "$sdraw" ] || continue
        saver_draw "$sdraw" "$img" && { echo "$i" > "$W/saver.index"; return 0; }
    done
    echo "$i" > "$W/saver.index"
    for f in /usr/share/blanket/screensaver/bg_ss*.png "$VD/screensaver/default.png"; do
        [ -f "$f" ] && [ "$f" != "$img" ] || continue
        saver_draw "$f" "$f" && return 0
    done
    return 0
}
# 画一张屏保：$1 = 要画的文件（可能是桌面转好的灰度 PNG），$2 = 原图（写日志用）。画上了返回 0。
saver_draw() {
    sv_note=; [ "$1" != "$2" ] && sv_note=" (converted copy)"
    if [ -x "$FBINK" ]; then
        # PNG 文件头第 26 个字节是颜色类型：4 / 6 带透明通道
        ct=$(dd if="$1" bs=1 skip=25 count=1 2>/dev/null | od -An -tu1 2>/dev/null | tr -d ' ')
        case "$(dd if="$1" bs=1 skip=1 count=3 2>/dev/null)" in PNG) ;; *) ct= ;; esac
        if [ "$ct" = 4 ] || [ "$ct" = 6 ]; then
            "$FBINK" -q -f -i "$1" -g w=-2,h=-2,halign=CENTER,valign=CENTER >/dev/null 2>&1 && { t "screensaver: $2$sv_note (transparent, over the page)"; return 0; }
        else
            "$FBINK" -q -c -f -i "$1" -g w=-2,h=-2,halign=CENTER,valign=CENTER >/dev/null 2>&1 && { t "screensaver: $2$sv_note"; return 0; }
        fi
        t "screensaver: fbink could not draw $2$sv_note"
    fi
    # 没有带图片支持的 fbink 时退回 Kindle 自带的 eips（PW6 上只认 PNG：看文件头，不看扩展名）
    case "$(dd if="$1" bs=1 skip=1 count=3 2>/dev/null)" in
    PNG) /usr/sbin/eips -g "$1" >/dev/null 2>&1 && { t "screensaver: $2$sv_note (eips)"; return 0; } ;;
    esac
    return 1
}
sleep_apply() { # saver | active
    sa_cur=$(cat $W/sleep.cur 2>/dev/null)
    case "$1" in
    saver)
        echo saver > $W/sleep.cur
        [ "$sa_cur" = saver ] && return 0
        sw0=$(cut -d' ' -f1 /proc/uptime)
        touch $X/ctl/sleep
        # 设了锁屏密码：冻结前先让桌面把锁屏盖上（显示桥已被 ctl/sleep 挡住，看不到），醒来时第一帧就是锁屏
        type lock_request >/dev/null 2>&1 && lock_request
        usleep 300000
        # 先画屏保，再冻结（用户报告 PW4 0.8.8：按电源键“要等 1 分钟左右才有反应”）。以前是先冻结：android_freeze 里的 sync
        # 在真机上要把 vfat 上镜像文件的脏页写完（刚装完应用 / 在编译 / 在换页时可以是几十秒），逐个进程发 SIGSTOP 也要
        # 扫一遍 /proc，这段时间屏幕一直停在原来的画面上。显示桥已经被 ctl/sleep 挡住（上面等的 0.3 秒让在途的那一帧
        # 落完），Android 的帧盖不到屏保上。
        screensaver_show; sv=1
        t "screensaver: drawn $(awk -v a=$sw0 -v b=$(cut -d' ' -f1 /proc/uptime) 'BEGIN{printf "%.1f", b-a}') s after powerd went to screenSaver"
        dl_busy=0
        if [ -f $W/dl.active ]; then
            dl_age=$(( $(date +%s) - $(stat -c %Y $W/dl.active 2>/dev/null || echo 0) ))
            [ "$dl_age" -lt 35 ] && dl_busy=1
        fi
        if pkg_busy || [ -f $W/fwd.id ] || [ "$dl_busy" = 1 ]; then
            touch $W/saver_nofreeze; t "screensaver: install/compile/transfer/download active, android left running"
        else
            # KINDLE_UI=freeze：每分钟整点的 TIME_TICK 刚发出时别冻（应用在换页时处理得慢，冻在半路醒来就超时）：
            # 显示桥已经挡住，先画屏保，等到这一分钟的第 12 秒再冻；等待期间若用户立刻掀盖唤醒则中止等待，不冻结
            sa_aborted=0
            if [ "$(kindle_ui_mode 2>/dev/null)" = freeze ]; then
                sec=$(date +%S); sec=${sec#0}
                while [ "${sec:-0}" -lt 12 ]; do
                    [ "$(sleep_state)" = active ] && { sa_aborted=1; break; }
                    sleep 1
                    sec=$(date +%S); sec=${sec#0}
                done
            fi
            if [ "$sa_aborted" = 1 ]; then
                touch $W/saver_nofreeze
                t "screensaver: woke before freeze delay ended, skipping freeze"
            else
                # 冻不住（android_freeze 已经解冻回来）：当作没冻，醒来时不用解冻
                if android_freeze; then touch $W/saver_frozen; else touch $W/saver_nofreeze; fi
            fi
        fi
        type wifi_ps_restore >/dev/null 2>&1 && wifi_ps_restore
        [ "${sv:-0}" = 1 ] || screensaver_show
        date +%s > $W/saver.t0      # 醒来时看冻了多久（SAVER_EXIT_H，freeze.sh saver_exit_check）
        t "screensaver on, android $([ -f $W/saver_frozen ] && echo frozen || echo 'left running') ($(awk -v a=$sw0 -v b=$(cut -d' ' -f1 /proc/uptime) 'BEGIN{printf "%.1f", b-a}') s after powerd went to screenSaver)"
        type tset >/dev/null 2>&1 && [ -f $W/saver_frozen ] && tset sleep.ok "$(date +%H:%M:%S)" ;;
    active)
        echo active > $W/sleep.cur
        [ "$sa_cur" = saver ] || return 0
        type saver_exit_check >/dev/null 2>&1 && saver_exit_check
        if [ -f $W/saver_nofreeze ]; then rm -f $W/saver_nofreeze
        elif [ "$(kindle_ui_mode 2>/dev/null)" = freeze ]; then android_thaw_staged ${THAW_SS_DELAY:-3} 2>/dev/null
        else android_thaw 2>/dev/null; fi
        type lock_reset_check >/dev/null 2>&1 && { lock_reset_check; lock_request; }
        ( usleep 500000; trust_unlock ) </dev/null >/dev/null 2>&1 &
        rm -f $W/saver_frozen
        rm -f $X/ctl/sleep
        epd_send REDRAW
        t "screensaver off, android thawed"
        # 休眠期间 Wi-Fi 断过、重连（可能换了地址 / DNS）：等 Kindle 连上后从 Android 里查一次，不通就修（network.sh）
        type net_heal_kick >/dev/null 2>&1 && net_heal_kick resume
        type tset >/dev/null 2>&1 && [ -n "$(tget sleep.ok)" ] && { tset wake.ok "$(date +%H:%M:%S)"; test_report; } ;;
    esac
}
sleep_state() { # powerd 现在的状态 -> saver / active（过渡态或读不到时不输出）
    case "$(lipc-get-prop -s com.lab126.powerd state 2>/dev/null)" in
    screenSaver|screenSaverOn|suspended|sleeping|readyToSuspend) echo saver ;;
    active|charging) echo active ;;
    esac
}
sleep_watch() {
    # 持续订阅 powerd 进/出屏保事件（单条长连接，不反复建短连接；直接按事件名触发，避免 KPW4 掀盖发出 outOfScreenSaver
    # 瞬间 powerd state 仍在 resuming/screenSaver 导致漏判并再卡 60-120 秒）。stdout 进管道、stderr 丢掉、stdin 关掉；
    # SLEEP_POLL=1 退回每秒轮询。lipc-wait-event 意外退出时先按当前状态补一次，2 秒后重新订阅。
    rm -f $W/sleep.cur
    me=$(exec sh -c 'echo $PPID')
    s=$(sleep_state); [ -n "$s" ] && sleep_apply "$s"
    if [ "${SLEEP_POLL:-0}" != 1 ] && command -v lipc-wait-event >/dev/null 2>&1; then
        while kill -0 "$me" 2>/dev/null; do
            lipc-wait-event -m com.lab126.powerd goingToScreenSaver,outOfScreenSaver </dev/null 2>/dev/null | while read -r ev _; do
                kill -0 "$me" 2>/dev/null || exit 0
                case "$ev" in
                goingToScreenSaver) sleep_apply saver ;;
                outOfScreenSaver)   sleep_apply active ;;
                esac
            done
            [ -f $W/sleep.pid ] || break
            sleep 2
            [ -f $W/sleep.pid ] || break
            s=$(sleep_state); [ -n "$s" ] && sleep_apply "$s"
        done
        return 0
    fi
    while :; do
        s=$(sleep_state); [ -n "$s" ] && sleep_apply "$s"
        sleep 1
        [ -f $W/sleep.pid ] || break
    done
}
# 停掉休眠监视连同它的 lipc-wait-event（只杀父进程的话，订阅和管道另一头会留下来）
sleep_watch_stop() {
    sp=$(cat $W/sleep.pid 2>/dev/null); [ -n "$sp" ] || return 0
    for c in $(pgrep -P "$sp" 2>/dev/null); do
        for gc in $(pgrep -P "$c" 2>/dev/null); do kill $gc 2>/dev/null; done
        kill $c 2>/dev/null
    done
    kill "$sp" 2>/dev/null
    rm -f $W/sleep.pid
    return 0
}
