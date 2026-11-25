# SPDX-License-Identifier: GPL-3.0-or-later
# 看门狗：每 10 秒看一次容器还在不在。桌面的命令走 lib/cmd.sh 的 FIFO（即时），这里只留旧的“命令文件”作后备：
#   $X/ctl/exit 返回 Kindle   $X/ctl/stop 关机   $X/ctl/restart 只重启系统   $X/ctl/diag 存诊断日志
# 诊断日志：存进 Android 存储的 Diagnostics/<时间>/，不含书的文件
diag_save() {
    d=$SYS/data/media/0/Diagnostics/$(date +%Y%m%d-%H%M%S)
    mkdir -p $d && chmod 777 $SYS/data/media/0/Diagnostics $d 2>/dev/null
    {
        echo "VeraDroid diagnostics"
        date
        echo "model=$(cat $SYS/model 2>/dev/null)"
        echo "version=$(cat $SYS/version 2>/dev/null)"
        echo "firmware=$(head -1 /etc/prettyversion.txt 2>/dev/null)"
        echo "mode=$(cat $W/mode 2>/dev/null || echo namespace)"
        echo "kernel=$(uname -a 2>/dev/null)"
        echo "cmdline=$(cat /proc/cmdline 2>/dev/null)"
        echo "soc=$(cat /proc/device-tree/compatible 2>/dev/null | tr '\0' ' ')"
        echo "fb=$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null)"
        echo "battery=$(cat /sys/class/power_supply/bd71827_bat/capacity 2>/dev/null)"
    } > $d/README.txt 2>/dev/null
    cp $LOG $d/veradroid.log 2>/dev/null
    cp $W/epd.log $d/epd.log 2>/dev/null
    cp $W/input.log $d/input.log 2>/dev/null
    # 停止 / 安装的记录不在 veradroid.log 里：用户诊断 里更新后有一次“启动后 10 秒被停掉、再点启动 20 分钟没反应”，
    # 当时 stop.sh 和安装器写了什么没有带上来
    tail -c 65536 /tmp/veradroid-stop.log > $d/stop.log 2>/dev/null
    # 诊断包里的文件名照旧（veradroid-install.log 等）：新位置 veradroid/logs/ 没有就取根目录老版本写的那份   (root-legacy: 只是诊断包里的名字)
    for f in install install-detail uninstall autoupdate fsck localupdate; do
        dsl=/mnt/base-us/veradroid/logs/$f.log; [ -f "$dsl" ] || dsl=/mnt/base-us/veradroid-$f.log   # root-legacy
        [ -f "$dsl" ] && tail -c 65536 "$dsl" | tr '\r' '\n' | grep -av '下载中' > "$d/veradroid-$f.log" 2>/dev/null
    done
    # 启动失败的原因（书库 / KUAL 每点一次记一行，vd_notice 的原因）和停止时的自检：社区报告“点启动进不去”时诊断包里一直没有这两份
    tail -c 65536 /mnt/base-us/veradroid/logs/start.log > $d/start.log 2>/dev/null
    tail -c 32768 $VD/last-stop.log > $d/last-stop.log 2>/dev/null
    ls -la $VD/apps $VD/update 2>/dev/null > $d/kindle-apps-folder.txt
    # Full host-side picture, not a 300-line tail.
    dmesg > $d/kindle-dmesg.txt 2>/dev/null
    cat /proc/last_kmsg > $d/kindle-last_kmsg.txt 2>/dev/null
    cat /proc/meminfo > $d/kindle-meminfo.txt 2>/dev/null
    cat /proc/loadavg > $d/kindle-loadavg.txt 2>/dev/null
    cat /proc/interrupts > $d/kindle-interrupts.txt 2>/dev/null
    cat /proc/diskstats > $d/kindle-diskstats.txt 2>/dev/null
    cat /proc/mounts > $d/kindle-mounts.txt 2>/dev/null
    ps > $d/kindle-ps.txt 2>/dev/null
    df -h > $d/kindle-df.txt 2>/dev/null
    free > $d/kindle-free.txt 2>/dev/null
    # 前光：powerd 的亮度 / 自动亮度 / 传感器读数、sysfs 实际值、桌面看到的状态（“调亮度没反应”时要看）
    { for fl_k in flIntensity flMaxIntensity flRawIntensity flAuto alsLux currentAmberLevel; do echo "$fl_k=$(lipc-get-prop com.lab126.powerd $fl_k 2>&1)"; done
      for fl_d in /sys/class/backlight/*; do [ -d "$fl_d" ] && echo "${fl_d##*/}: brightness=$(cat $fl_d/brightness 2>/dev/null) actual=$(cat $fl_d/actual_brightness 2>/dev/null) max=$(cat $fl_d/max_brightness 2>/dev/null)"; done
      echo "-- ctl/status"; cat $X/ctl/status 2>/dev/null; } > $d/kindle-frontlight.txt 2>&1
    { ls -la /var/local/platform-metrics/ /var/local/upstart/; df -k /var/local; initctl status platformmetricsd; initctl status minerva_service; } > $d/kindle-varlocal.txt 2>&1   # 开机卡树的排查（framework.sh pm_pending_guard）
    for f in version cmdline cpuinfo partitions filesystems modules devices buddyinfo vmstat zoneinfo slabinfo pagetypeinfo softirqs locks keys; do
        cat /proc/$f > $d/kindle-proc-$f.txt 2>/dev/null
    done
    cp -a /sys/fs/pstore "$d/kindle-pstore" 2>/dev/null || true
    for f in /var/log/messages /var/log/syslog; do
        [ -f "$f" ] && cp "$f" "$d/host-$(basename $f).txt" 2>/dev/null || true
    done
    # Android crash/ANR state is more useful than another summary dump.
    for x in anr tombstones system/dropbox log; do
        if [ -e "$SYS/data/$x" ]; then
            cp -a "$SYS/data/$x" "$d/android-$(echo $x | tr / -)" 2>/dev/null || true
        fi
    done
    if kill -0 "$P" 2>/dev/null && [ ! -f $W/frozen ]; then
        # Full Android state: all logcat buffers (main/system/crash/events/
        # kernel/radio), every dumpsys service, props, settings, network.
        adb_sh 'export PATH=/system/bin:/system/xbin; logcat -G 8M' >/dev/null 2>&1 || true
        adb_sh 'export PATH=/system/bin:/system/xbin; logcat -b all -d -v threadtime' > $d/android-logcat-all.txt 2>&1
        adb_sh 'export PATH=/system/bin:/system/xbin; logcat -b events -d -v threadtime' > $d/android-logcat-events.txt 2>&1
        adb_sh 'export PATH=/system/bin:/system/xbin; logcat -b crash -d -v threadtime' > $d/android-logcat-crash.txt 2>&1
        adb_sh 'export PATH=/system/bin:/system/xbin; getprop' > $d/android-getprop.txt 2>&1
        # 没开机完成（system_server 起不来、binder 不通）时 dumpsys / settings / pm 每条都只会留下一行
        # "Aborted"（用户诊断：几十个 23 字节的文件）。日志和属性上面已经有了，这里只记一句。
        if ! grep -q '^\[sys.boot_completed\]: \[1\]' $d/android-getprop.txt 2>/dev/null; then
            echo "android has not finished booting: dumpsys skipped (see android-logcat-all.txt and android-tombstones)" > $d/android-NOT-BOOTED.txt
        else
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys' > $d/android-dumpsys-all.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys meminfo' > $d/android-meminfo.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys activity activities' > $d/android-activities.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys activity processes' > $d/android-processes.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys window' > $d/android-window.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys input' > $d/android-input.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys battery' > $d/android-battery.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys power' > $d/android-power.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys connectivity' > $d/android-connectivity.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys wifi' > $d/android-wifi.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys display' > $d/android-display.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys SurfaceFlinger' > $d/android-surfaceflinger.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys package com.tencent.weread.eink' > $d/android-weread-package.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; settings list global' > $d/android-settings-global.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; settings list secure' > $d/android-settings-secure.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; ip addr' > $d/android-ip.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; netstat -tunlp' > $d/android-netstat.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; top -n 1 -d 1 -m 40' > $d/android-top.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; top -H -n 1 -d 1 -m 60' > $d/android-top-threads.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; ps -A -o USER,PID,PPID,VSZ,RSS,STAT,NAME' > $d/android-ps-all.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys -l' > $d/android-dumpsys-list.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys cpuinfo' > $d/android-cpuinfo.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys meminfo -a' > $d/android-meminfo-all.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys procstats --hours 6' > $d/android-procstats.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys batterystats --charged' > $d/android-batterystats.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys alarm' > $d/android-alarm.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys jobscheduler' > $d/android-jobscheduler.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys package' > $d/android-package-all.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys activity top' > $d/android-activity-top.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys gfxinfo com.veradroid.home' > $d/android-gfx-home.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys gfxinfo com.tencent.weread.eink' > $d/android-gfx-weread.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys media.audio_flinger' > $d/android-audio.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys thermalservice' > $d/android-thermal.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys sensorservice' > $d/android-sensors.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys deviceidle' > $d/android-deviceidle.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys usagestats' > $d/android-usagestats.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; dumpsys diskstats' > $d/android-diskstats.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; df -h' > $d/android-df.txt 2>&1
            adb_sh 'export PATH=/system/bin:/system/xbin; mount' > $d/android-mounts.txt 2>&1
        fi
    fi
    t "diagnostics saved: $d"
}

# 返回 Kindle：RETURN_MODE=freeze 时冻结留在内存（再打开几秒），否则关掉。在哪个辅助进程里调都行，调完结束该进程。
do_exit() {
    touch $W/exit.ack 2>/dev/null       # action.sh exit_send：命令监听接到了
    # 同一时间只做一次（命令监听晚到的 exit 和 action.sh 的后备 --exit 可能都来）：锁里记着 pid，那个进程还在就不做
    read -r de_me _ < /proc/self/stat
    if ! mkdir $W/exiting 2>/dev/null; then
        de_p=$(cat $W/exiting/pid 2>/dev/null)
        if [ -n "$de_p" ] && [ "$de_p" != "$de_me" ] && grep -q "veradroid.sh" /proc/$de_p/cmdline 2>/dev/null; then t "return to kindle already in progress (pid $de_p)"; exit 0; fi
    fi
    echo "$de_me" > $W/exiting/pid
    settings_load
    t "return to kindle requested"
    if [ "$RETURN_MODE" = freeze ] && [ -d $FZ ]; then
        touch $X/ctl/sleep
        # 多看在前台时换过的显示密度：冻结前换回默认（显示桥已经挡住，看不到重排；解冻时再按前台应用换回去，appprof.sh）
        type appprof_density_reset >/dev/null 2>&1 && appprof_density_reset
        if android_freeze; then
            touch $W/frozen
            cpu_gov_restore 2>/dev/null || true   # android_freeze 切到 powersave 是给屏保用的；回到 Kindle 界面要恢复原来的调速器
            input_release
            wifi_ps_restore 2>/dev/null || true   # 回到 Kindle：Wi-Fi 省电交还 Kindle（WIFI_PS=off / auto 临时关掉的）
            type sleep_watch_stop >/dev/null 2>&1 && sleep_watch_stop
            type usb_watch_stop >/dev/null 2>&1 && usb_watch_stop   # 回到 Kindle：插线由 Kindle 自己处理
            for f in sleep lipc guard watchdog; do kill $(cat $W/$f.pid 2>/dev/null) 2>/dev/null; done
            framework_start
            userstore_check freeze
            t "frozen, kindle ui back"
            exit 0
        fi
        t "freeze failed, stopping"
    fi
    log_release; sh $VD/boot/stop.sh >/dev/null 2>&1
    exit 0
}
# 调 stop.sh 之前先把自己的输出从系统盘上的 veradroid.log 挪开：stop.sh 不杀调用自己的进程，
# 这个打开的文件会让 loop 系统盘卸不掉（legacy PW3 模拟器实测；k49 同样的代码路径）。
log_release() { t "releasing the log on the system image (stop follows)"; exec >>/tmp/veradroid-stop.log 2>&1; }
do_stop() { t "stop requested"; log_release; sh $VD/boot/stop.sh >/dev/null 2>&1; exit 0; }
do_restart() {
    t "restart requested"; touch $W/restarting
    log_release; sh $VD/boot/stop.sh >/dev/null 2>&1
    sh $VD/boot/veradroid.sh </dev/null >/dev/null 2>&1 &
    exit 0
}

# /var 是 Kindle 的 64 MB tmpfs（/tmp 也指向 /var/tmp）。Kindle 的 fastmetrics 每 10 分钟把一批指标存成 /var/tmp/fm-out-<毫秒>
# （约 150 KB），由 tmd 上传后删掉。Android 在前台时 tmd 被我们停着（framework.sh 的 FW_BASE），批次只进不出；没注册 / 离线的
# Kindle 上 tmd 在也发不出去。一台 PW6 测试机（5.20.2）用了 5 天堆了 336 个、52 MB，/var 到了 87-93%。满了以后 Kindle 和我们往
# /tmp、/var/run、/var/log 写的东西全失败（停止日志、整包更新的下载、ssh、upstart 的状态文件），界面和返回都可能卡住。
# /var 用到 70% 以上时从最旧的批次删起，降到 60% 以下为止，最新的 20 个不动。只删这一种文件（Kindle 自己的遥测缓存，不是用户数据）。
var_used() { df -k /var 2>/dev/null | awk 'NR == 2 { sub("%", "", $5); print $5 + 0 }'; }
var_guard() {
    vu0=$(var_used); [ "${vu0:-0}" -ge 70 ] 2>/dev/null || return 0
    vl=$(ls /var/tmp 2>/dev/null | grep '^fm-out-[0-9]*$' | sort)
    vc=$(printf '%s\n' "$vl" | grep -c .); [ "$vc" -gt 20 ] || return 0
    vn=0
    for f in $(printf '%s\n' "$vl" | head -n $((vc - 20))); do
        [ -f "/var/tmp/$f" ] && [ ! -L "/var/tmp/$f" ] || continue
        rm -f "/var/tmp/$f"; vn=$((vn + 1))
        [ $((vn % 10)) = 0 ] && [ "$(var_used)" -lt 60 ] && break
    done
    t "var: /var was ${vu0}% full: removed $vn old Kindle metrics batches (/var/tmp/fm-out-*), now $(var_used)%"
}

# 内存盘（/dev/shm，占的是内存）里的日志：显示桥每刷一次写一行，开着几天能长到几 MB。超过上限就留后一半
log_cap() {   # log_cap <文件> <字节>
    [ -f "$1" ] || return 0
    sz=$(wc -c < "$1" 2>/dev/null || echo 0)
    [ "$sz" -gt "$2" ] 2>/dev/null || return 0
    tail -c $(($2 / 2)) "$1" > "$1.tmp" 2>/dev/null && cat "$1.tmp" > "$1"; rm -f "$1.tmp"
}
helpers_supervise() {
    _hs_now=$(cut -d. -f1 /proc/uptime 2>/dev/null || echo 0)
    if [ -x "$VD/bin/veradroid-epd" ] && [ -f $W/epd.pid ]; then
        if ! alive $W/epd.pid; then
            n=$(cat $W/epd.restarts 2>/dev/null || echo 0)
            if [ "$n" -lt 3 ]; then
                echo $((n + 1)) > $W/epd.restarts
                echo "$_hs_now" > $W/epd.up_since
                t "display bridge (pid $(cat $W/epd.pid)) gone, restarting it ($((n + 1))/3); last log: $(tail -n 2 $W/epd.log 2>/dev/null | tr '\n' ' ')"
                cp $W/epd.log $W/epd.log.crashed 2>/dev/null
                display_start; epd_send REDRAW
            fi
        elif [ -s $W/epd.restarts ]; then
            _eu=$(cat $W/epd.up_since 2>/dev/null || echo "$_hs_now")
            [ -f $W/epd.up_since ] || echo "$_hs_now" > $W/epd.up_since
            [ $((_hs_now - _eu)) -ge 120 ] && rm -f $W/epd.restarts $W/epd.up_since
        fi
    fi
    if [ -f $W/input.pid ] && ! alive $W/input.pid && [ ! -f $W/frozen ]; then
        n=$(cat $W/input.restarts 2>/dev/null || echo 0)
        if [ "$n" -lt 3 ]; then
            echo $((n + 1)) > $W/input.restarts
            t "touch front end (pid $(cat $W/input.pid)) gone, restarting it ($((n + 1))/3); last log: $(tail -n 2 $W/input.log 2>/dev/null | tr '\n' ' ')"
            cp $W/input.log $W/input.log.crashed 2>/dev/null
            if input_touch_start; then
                # 容器里的 /dev/input/event0、event1 是开机时绑进去的；Android 的 EventHub 在旧设备挂断时把它们删了，
                # 只有目录里出现新文件（inotify）才会再打开。按新设备的设备号在容器的 /dev/input 里建两个新节点
                ci=/proc/$(cat $W/pid 2>/dev/null)/root/dev/input   # 容器自己的 /dev（挂载命名空间里的 tmpfs），宿主的 $R/dev 看不到
                rm -f $ci/event1[0-9] 2>/dev/null
                k=0; for d in $KFB_TOUCH $KFB_KEYS_VIRT; do
                    dn=$(cat /sys/class/input/${d##*/}/dev 2>/dev/null)
                    [ -n "$dn" ] && mknod -m 666 $ci/event1$((n * 2 + k)) c ${dn%%:*} ${dn##*:} 2>/dev/null   # 建出来就要是 666：EventHub 收到 inotify 立刻打开，晚一步 chmod 就是 Permission denied
                    k=$((k + 1))
                done
                t "touch front end restarted: $KFB_TOUCH / $KFB_KEYS_VIRT handed to android as $(ls $ci | grep '^event1[0-9]' | tr '\n' ' ')"
            else t "touch front end: restart failed"; fi
        fi
    fi
}

# 看门狗自己的看守（社区报告：watchdog.pid 只写不读）。看门狗没了（OOM、崩溃、被误杀），命令监听、显示桥 / 触摸前端的重启、
# Android 退出后的收尾就都没人管了，只能重启 Kindle。start_helpers 起这个循环：每 30 秒看一眼，Android 还活着、没冻结、
# 没有 stop.sh 在跑时把看门狗拉起来（每次运行最多 5 次）。冻结（do_exit 有意停掉看门狗）、Android 没了、正在停止 / 重启时自己退出；
# stop.sh 和 do_exit 按 guard.pid 停它，解冻时 start_helpers 重起。
wd_guard() {
    wg_n=0
    while :; do
        if [ -z "$WD_GUARD_S" ] && [ -f $W/saver_frozen ]; then sleep 120; else sleep ${WD_GUARD_S:-30}; fi
        kill -0 "$P" 2>/dev/null || break
        [ -f $W/frozen ] || [ -f $W/restarting ] && break
        wg_s=$(cat /tmp/veradroid-stop.pid 2>/dev/null); [ -n "$wg_s" ] && kill -0 "$wg_s" 2>/dev/null && break
        alive $W/watchdog.pid && continue
        [ $wg_n -ge 5 ] && { t "guard: watchdog gone again after $wg_n restarts, giving up"; break; }
        wg_n=$((wg_n + 1))
        t "guard: watchdog (pid $(cat $W/watchdog.pid 2>/dev/null)) is gone, restarting it ($wg_n/5)"
        watchdog &
        echo $! > $W/watchdog.pid
    done
}
# 电量同步（fix/battery-sync）：nocap_mount 去掉 healthd 的 CAP_WAKE_ALARM 以后它建不起周期定时器（timerfd CLOCK_BOOTTIME_ALARM，
# 没有 CAP_WAKE_ALARM 就 EPERM），Android 的电量只在驱动偶尔发的电池 uevent 时更新：应用里的状态栏、桌面停在充满时的 100 %
# （PW6 1.03 用户“电量显示不同步”；测试机上 healthd 没有 timerfd，dmesg 里的 healthd 电量行只跟着驱动的 uevent 走）。
# 没冻结时每 BAT_POKE_S 秒（默认 60，和 healthd 原来插着电时的周期一样）、刚解冻马上，给电池写一条 change uevent，healthd 照常重读
# 并广播；不用定时器、不挂唤醒源，冻结时不发（休眠不受影响）。
bat_poke_tick() {
    bp_now=$(cut -d. -f1 ${BAT_UPTIME:-/proc/uptime})
    [ $((bp_now - ${BAT_POKE_T:-0})) -ge ${BAT_POKE_S:-60} ] || return 0
    BAT_POKE_T=$bp_now
    for bp_d in ${BAT_PS_DIR:-/sys/class/power_supply}/*; do
        # Oasis 2 5.13.6 powerd parse_uevent has 32 fixed slots without bounds check; max77796-battery/uevent has 31 lines
        # (35 key-value pairs with kernel header/footer), so writing "change" corrupts glibc heap and aborts powerd every minute.
        [ "$(cat $bp_d/type 2>/dev/null)" = Battery ] && [ -w $bp_d/uevent ] || continue
        [ "$(wc -l < $bp_d/uevent 2>/dev/null || echo 0)" -ge 28 ] && continue
        echo change > $bp_d/uevent 2>/dev/null
    done
    return 0
}
# 宿主自己的进程不让内核的 lowmemorykiller 挑中。它们是从 veradroid.sh 继承的 oom_score_adj 0，和 Android 前台应用同一档：
# 512 MB 机型换页卡死时（2026-11-21 PW5 诊断：交换区满、zram 分配失败、49 次 page allocation failure）内核按“同一档里最大的”
# 挑，命令监听 / 触摸前端 / 显示桥 / 看门狗被杀，就只剩强制重启。都很小（合计几 MB），给 -800（和 Android 常驻进程一档）。
# 启动 / 解冻时（start_helpers）和看门狗每一轮各做一次：它们会被看门狗重启、换 pid。HOST_OOM_ADJ= 空 关掉。
host_oom_protect() {
    [ -n "${HOST_OOM_ADJ--800}" ] || return 0
    for ho_n in cmd watchdog guard sleep input epd slirp vdkaf shutw; do
        [ -r $W/$ho_n.pid ] || continue
        ho_p=; read -r ho_p < $W/$ho_n.pid 2>/dev/null || true
        case "$ho_p" in ''|*[!0-9]*) continue ;; esac
        [ -r /proc/$ho_p/oom_score_adj ] || continue
        ho_cur=; read -r ho_cur < /proc/$ho_p/oom_score_adj 2>/dev/null || true
        [ "$ho_cur" = "${HOST_OOM_ADJ--800}" ] || echo "${HOST_OOM_ADJ--800}" > /proc/$ho_p/oom_score_adj 2>/dev/null
    done
    return 0
}
watchdog() {
    # PERF: poll every 120s while saver_frozen (60s while frozen) to cut host wakeups. FIFO cmd.sh still handles UI.
    # MEM: re-apply kernel LMK each tick on low-RAM (<700MB) devices (lab126 can reset minfree); cache MemTotal once.
    wd_mt=
    if [ -r "${LMK_MEMINFO:-/proc/meminfo}" ]; then
        while read -r wd_k wd_v _; do
            [ "$wd_k" = "MemTotal:" ] && { wd_mt=$wd_v; break; }
        done < "${LMK_MEMINFO:-/proc/meminfo}"
    fi
    wd_tick=0
    while :; do
        if [ -f $W/saver_frozen ]; then sleep_for=120
        elif [ -f $W/frozen ]; then sleep_for=60
        else sleep_for=20; fi
        sleep $sleep_for
        kill -0 $P 2>/dev/null || { t "system exited"; log_release; sh $VD/boot/stop.sh >/dev/null 2>&1; break; }
        wd_tick=$((wd_tick + 1))
        [ -n "$wd_mt" ] && [ "$wd_mt" -lt 700000 ] 2>/dev/null && { lmk_kernel_tune 2>/dev/null || true; }
        # 低频维护（每 3 轮 = 60 秒；冻结时每轮已经 ≥60 秒）：减少前台阅读时的后台 fork
        if [ $((wd_tick % 3)) -eq 1 ] || [ $sleep_for -ge 60 ]; then
            host_oom_protect
            log_cap $W/epd.log 262144; log_cap $W/input.log 131072; log_cap $W/input.err 65536
        fi
        # 空闲冻结（idle.sh，IDLE_FREEZE）：冻 / 定时唤醒 / 放回 watchdog 线程。屏保冻结、返回 Kindle 时不管
        [ -f $W/saver_frozen ] || [ -f $W/frozen ] || { type idle_tick >/dev/null 2>&1 && idle_tick; }
        if [ ! -f $W/saver_frozen ] && [ ! -f $W/frozen ] && [ ! -f $W/idlefz ]; then
            # 命令监听没了（FIFO 建不起来、被杀）：桌面的“返回 Kindle”、设置、手势全都没反应，只能重启 Kindle。重新起一个。
            alive $W/cmd.pid || { t "cmd listener gone, restarting it"; cmd_listen & echo $! > $W/cmd.pid; }
            # 显示桥 / 触摸前端没了（崩溃、被 OOM 杀掉）：以前没人管，屏幕永远停在最后一帧，或者 Android 再也收不到触摸，
            # 只能重启 Kindle。重新起一个（每次开机各最多 3 次，再多就是程序本身起不来，留给诊断）。
            helpers_supervise
            # 桌面 / 输入法不换出、后台应用定时停止（memkeep.sh）
            type mem_keep_tick >/dev/null 2>&1 && { mem_keep_tick; bg_stop_tick; wifi_idle_tick; }
            # 清单里的应用装了 / 更新了（应用自己更新也算）：按新版本的清单再做一遍瘦身（appslim.sh）
            type appslim_tick >/dev/null 2>&1 && appslim_tick
            # system_server 重启（Watchdog 杀掉后软重启）以后 ActivityManager 会把 lmkd 的门槛写回原厂的大屏手机档，
            # 512 MB 机型上前台的微信读书又会被反复杀掉（用户诊断）。pid 变了就重新设一次。
            ss=$(pidof system_server 2>/dev/null | cut -d' ' -f1)
            if [ -n "$ss" ] && [ "$ss" != "$(cat $W/ss.pid 2>/dev/null)" ]; then
                [ -f $W/ss.pid ] && [ -f $W/booted ] && { t "system_server restarted ($(cat $W/ss.pid) -> $ss): re-tuning lmkd"; ( sleep 20; lmk_tune; i=0; while [ $i -lt 30 ] && [ "$(adb_sh 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n')" != 1 ]; do sleep 5; i=$((i+1)); done; trust_unlock ) & }
                echo "$ss" > $W/ss.pid
            fi
        fi
        [ -f $W/saver_frozen ] || [ -f $W/frozen ] || [ -f $W/idlefz ] || { [ -f $X/ctl/settings.new ] && settings_apply; }
        # Wi-Fi 省电（network.sh）、新装 / 更新的应用排队预编译（compile.sh）、电量记录（energy.sh）
        if [ ! -f $W/saver_frozen ] && [ ! -f $W/frozen ] && [ ! -f $W/idlefz ]; then
            type wifi_ps_tick >/dev/null 2>&1 && wifi_ps_tick
            type net_watch_tick >/dev/null 2>&1 && net_watch_tick    # Kindle 的网络变了 / 定时：从 Android 里查一次网络（network.sh）
            [ -f $W/booted ] && type compile_watch >/dev/null 2>&1 && compile_watch
        fi
        type bat_log >/dev/null 2>&1 && bat_log
        if [ -f $W/saver_frozen ] || [ -f $W/frozen ] || [ -f $W/idlefz ]; then BAT_POKE_T=0; else bat_poke_tick; fi
        type wa_adb_tick >/dev/null 2>&1 && wa_adb_tick     # 无线调试到时间自动关（webadmin.sh）
        var_guard; pm_pending_guard
        # 系统盘快满了就自动扩（storage.sh）。冻结时不做。
        [ -f $W/saver_frozen ] || [ -f $W/frozen ] || [ -f $W/idlefz ] || { type st_auto_tick >/dev/null 2>&1 && st_auto_tick; }
        if [ -f $X/ctl/diag ]; then rm -f $X/ctl/diag; ( diag_save; touch $X/ctl/diag.done ) & fi
        [ -f $X/ctl/stop ] && { rm -f $X/ctl/stop; do_stop; }
        [ -f $X/ctl/restart ] && { rm -f $X/ctl/restart; do_restart; }
        [ -f $X/ctl/exit ] && { rm -f $X/ctl/exit; do_exit; }
    done
}
