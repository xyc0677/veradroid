#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# VeraDroid 主入口。用法：sh veradroid.sh [home | 包名]
#   - 没在跑：暂停 Kindle 界面 → 启动系统 → 打开应用
#   - 冻着：停 Kindle 界面 → 解冻 → 打开应用
#   - 在跑：直接打开应用
# 整个脚本包在 { } 里：sh 先读完再执行，运行中被覆盖也不会读到半截。
{
# 从 SSH / 启动器继承来的多余文件描述符（管道等）会一路漏进 Android 的 zygote；zygote fork 前发现不认识的管道就中止
# （"Unsupported st_mode 4480" / "Unable to construct file descriptor table"），开机随机失败。先全部关掉，只留 0 1 2。
# 只在宿主侧的第一次调用里做。--in-ns 时本进程是新 PID 命名空间里的 1 号，而 /proc 还是宿主的：
# /proc/$$/fd 读到的是 Kindle 的 init 的描述符表。按别人的编号关自己的描述符，会关掉 ash 内部保存的
# 描述符，shell 原地空转、容器永远起不来（PW6 真机实测约三次里一次）。容器进程由 container_start
# 以 </dev/null >>$LOG 2>&1 启动，本来就只有 0 1 2。
case "$1" in --in-ns) ;; *)
for n in $(ls /proc/$$/fd 2>/dev/null); do
    # eval 后面不能再跟 2>/dev/null：ash 会把原来的 stderr 存到 11 号，如果要关的正好是 11 号
    # （启动器漏进来的），busybox ash 1.34 就地死循环（PW6 实测可稳定复现）。
    case "$n" in 0|1|2|*[!0-9]*) ;; *) eval "exec $n>&-" ;; esac
done ;;
esac
umask 022
VD=${VD:-$(cd "$(dirname "$0")/.." && pwd)}
. "$VD/boot/lib/common.sh"
for m in settings memory memkeep appslim slim freeze kmod framework container input network sleep status cmd watchdog afterboot splash bootwatch diagboot compile energy tz tester bgnice; do . "$VD/boot/lib/$m.sh"; done
mkdir -p $SYS $W
# 上次停在某个应用时留下的“当前应用显示参数”不带进这一次（开机时前台是桌面；appprof.sh）。
# 解冻（Android 冻着、还活着）时不删：显示桥一直在跑，手里还是那个应用的参数（比如微信读书的 KFB_MODE=1 / FLASH=6）。以前这里删了，
# 解冻后打开桌面时 appprof_fg 比较“新 = 全局、旧 = 空”判成“same as before”，不发 SET，桌面就一直用那个应用的刷新参数。
# 冷启动在下面另外删（同 pw6 / k49 的 3153aa9）。
if ! { [ -f $W/frozen ] && kill -0 "$(cat $W/pid 2>/dev/null)" 2>/dev/null; }; then rm -f $SYS/settings.app $W/appprof.pkg; fi
settings_load

# 第二阶段：在新命名空间里
if [ "$1" = "--in-ns" ]; then run_in_ns "$2" "$3" "$4"; exit 1; fi
if [ "$1" = "--in-chroot" ]; then HOSTNS=1 run_in_ns "$2" "$3" "$4"; exit 1; fi
# 回到 Kindle 的后备入口（action.sh exit_send：命令监听 8 秒没接走 exit）：在这个新进程里直接做 do_exit
if [ "$1" = "--exit" ]; then
    : >>"$LOG" 2>/dev/null && exec >>$LOG 2>&1
    t "return to kindle: the command listener did not take exit, doing it directly (pid $$)"
    do_exit; exit 0
fi

# 日志只留最近约 512 KB：以前从不清理，低内存机型上看门狗每 20 秒还写一行
[ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ] && { tail -c 524288 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"; }
# 日志写不进去（系统盘只读 / 挂载点不可写）时 exec 重定向失败会让整个脚本当场退出、什么都不留：先试，不行就写 tmpfs
if : >>"$LOG" 2>/dev/null; then exec >>$LOG 2>&1
else vd_slog "cannot write $LOG (system image read-only or missing?): logging to /tmp/veradroid-start.log"; exec >>/tmp/veradroid-start.log 2>&1; fi
# 脱离启动环境：新会话、干净环境、oom 调成 0，不被书库启动器的进程组牵连
if [ -z "$VERADROID_DETACHED" ]; then
    echo 0 > /proc/$$/oom_score_adj 2>/dev/null
    vd_slog "start requested (${1:-$SYSTEM_APP}), pid $$, image $(grep -q " $SYS " /proc/mounts && echo mounted || echo NOT-mounted)"
    # 没有 setsid / env 时 exec 失败，sh 当场退出、没有任何提示：先看在不在，不在就不脱离，原地接着启动
    if command -v setsid >/dev/null 2>&1 && command -v env >/dev/null 2>&1; then
        exec env -i VERADROID_DETACHED=1 PATH="$PATH" setsid sh "$0" "$@" </dev/null
    fi
    vd_slog "no setsid/env on this firmware: starting without detaching"
    VERADROID_DETACHED=1
fi
echo "== $(date) start app=${1:-$SYSTEM_APP} up=$(cut -d' ' -f1 /proc/uptime)"
APP=${1:-$SYSTEM_APP}

# Kindle 正在关机 / 重启（/etc/upstart/shutdown.conf 第一步写 /tmp/shutdown_in_progress）：这时不能再启动——会把系统盘重新挂到
# /mnt/base-us 上，Kindle 卸不掉用户存储，关机卡住；而且关机钩子（vdshutwatch）一启动就看到这个标记，立刻用 --shutdown 把刚起来的
# Android 停掉，并且不恢复 Kindle 界面（volumd / awesome 一直停着，屏幕不动、USB 也不通，只能强制重启。用户诊断 里
# "started" 之后 0.3 秒 stop.sh 被一个已经退出的父进程调用、之后再点启动没有任何反应，和这条路径吻合，但没法确证）。标记在、关机任务却没在跑 = 残留的标记：照常启动，但不挂关机钩子。
kindle_shutting_down() {
    [ -e /tmp/shutdown_in_progress ] || return 1
    initctl status shutdown 2>/dev/null | grep -q 'start/' && return 0
    case "$(runlevel 2>/dev/null | awk '{print $2}')" in 0|6) return 0 ;; esac
    return 1
}
if kindle_shutting_down; then
    t "kindle is shutting down (/tmp/shutdown_in_progress): not starting"
    vd_notice "Kindle 正在关机 / 重启，VeraDroid 不启动" "Kindle is shutting down: VeraDroid not started" 5
    exit 0
fi

# 安装 / 更新正在替换文件（bootstrap.sh 持有安装锁，阶段 replace / wait，或老安装器已经写了 incomplete 标记）：这时启动会和安装器抢同一套文件。
# 以前：刚起来的 Android 被安装器随后的 stop.sh 关掉（屏幕上没有任何解释）；或者把还在解压的新系统当成“上次安装中断”回退掉，
# 安装器接着把新版本号写进回退后的旧程序目录（版本号和实际文件对不上，自动更新以后再也不会更新它）。
# 现在：屏幕上说明在等什么，等安装器结束（最多 30 分钟）再用装好的版本启动。还在下载（check / download），或者自动更新在等用户
# 离开 Android（wait，它只在 Android 冻结 / 退出时才动手）时照常启动。
installer_busy() {
    # 安装锁在 veradroid/state/；老版本的安装器（用户手里的旧离线包）还锁在根目录
    for IL in /mnt/base-us/veradroid/state/install.lock /mnt/base-us/veradroid-install.lock; do   # root-legacy
        ip=$(cat $IL/pid 2>/dev/null); [ -n "$ip" ] && kill -0 "$ip" 2>/dev/null || continue
        [ "$(cat $IL/boot 2>/dev/null)" = "$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)" ] || continue
        IPHASE=$(cat $IL/phase 2>/dev/null)
        case "$IPHASE" in check|download|wait|done) continue ;; replace) return 0 ;; esac
        # 不写阶段的老安装器：只认已经开始替换文件的
        { [ -f /mnt/base-us/veradroid/state/install.incomplete ] || [ -f /mnt/base-us/veradroid-install.incomplete ]; } && return 0   # root-legacy
    done
    return 1
}
if [ -z "$VD_INSTWAITED" ] && installer_busy; then
    t "installer pid $ip is ${IPHASE:-replacing files}: waiting for it before starting"
    exec >>/tmp/veradroid-stop.log 2>&1          # 安装器要停 Android、卸载系统盘：这里不能开着系统盘上的日志
    splash "VeraDroid 正在安装更新，装完会自动启动（约 1–3 分钟）" 36 || eips 1 2 "VeraDroid: installing an update, will start when done" >/dev/null 2>&1
    i=0
    while installer_busy && [ $i -lt 360 ]; do sleep 5; i=$((i + 1)); done
    if installer_busy; then
        t "installer still running after $((i * 5))s: not starting"
        vd_notice "VeraDroid 更新还没装完，请稍后再点“VeraDroid 启动”" "VeraDroid: update still installing, try again later"
        exit 1
    fi
    t "installer finished after $((i * 5))s, starting"
    cd /
    VD_INSTWAITED=1 exec sh /mnt/base-us/veradroid/boot/veradroid.sh "$@"   # 装好的（或安装器回退后的）那一版
fi

# 安装器被强行结束（SIGKILL、断电）时它的 EXIT 清理没跑：临时目录 veradroid-install.<pid>（整包 + 解开的 ~290 MB）和过期的安装锁
# 一直留到下一次安装，老机型的小存储上很占地方。这里没有安装器在跑（上面已经等过）：持有者不在这次开机里活着的都删掉。
installer_leftovers() {
    il_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    il_lp=$(cat /mnt/base-us/veradroid/state/install.lock/pid 2>/dev/null)
    if [ -d /mnt/base-us/veradroid/state/install.lock ] && { [ -z "$il_lp" ] || ! kill -0 "$il_lp" 2>/dev/null || [ "$(cat /mnt/base-us/veradroid/state/install.lock/boot 2>/dev/null)" != "$il_boot" ]; }; then
        rm -f /mnt/base-us/veradroid/state/install.lock/pid /mnt/base-us/veradroid/state/install.lock/boot /mnt/base-us/veradroid/state/install.lock/phase
        rmdir /mnt/base-us/veradroid/state/install.lock 2>/dev/null && il_lp= && t "removed a stale install lock"
    fi
    [ -d /mnt/base-us/veradroid/state/install.lock ] && return 0      # 有活着的安装器：它自己管
    for il_d in /mnt/base-us/veradroid/state/install.[0-9]*; do
        [ -d "$il_d" ] || continue
        il_p=${il_d##*.}; case "$il_p" in *[!0-9]*) continue ;; esac
        kill -0 "$il_p" 2>/dev/null && [ -n "$(find "$il_d" -mmin -2 2>/dev/null | head -1)" ] && continue   # 无锁的老安装器还在写
        il_mb=$(du -sm "$il_d" 2>/dev/null | cut -f1)
        case "$il_d" in /mnt/base-us/veradroid/state/install.[0-9]*) rm -rf "$il_d" && t "removed an interrupted installer's temp dir $il_d (${il_mb:-?} MB)" ;; esac
    done
}
installer_leftovers 2>/dev/null
# 安装器被硬杀（SIGKILL）时它恢复防休眠的 EXIT 陷阱没跑：preventScreenSaver 一直是 1，电源键再也进不了屏保（社区报告，模拟器复现）。
# 安装器动它之前把原值记在 state/keepawake.orig、正常结束时删掉。这里还看到它、又没有活着的安装器（上面已经等过 / 清过锁），就放回原值。
keepawake_heal() {
    kh_f=/mnt/base-us/veradroid/state/keepawake.orig
    [ -f $kh_f ] || return 0
    [ -d /mnt/base-us/veradroid/state/install.lock ] && return 0
    kh_v=$(cat $kh_f 2>/dev/null); case "$kh_v" in 0|1) ;; *) kh_v=0 ;; esac
    lipc-set-prop -i com.lab126.powerd preventScreenSaver "$kh_v" >/dev/null 2>&1 && rm -f $kh_f && t "restored preventScreenSaver=$kh_v left by an installer that was killed"
}
keepawake_heal 2>/dev/null

# 把输出接回系统盘上的日志（之前为了让 stop.sh 能卸载系统盘挪到了 /tmp）。系统盘没挂上时不接：exec 重定向失败会让整个脚本退出，
# 也不要在镜像下面的裸目录里建一个日志文件。
log_reopen() { if [ ! -f "$SYSTEM_IMG" ] || grep -q " $SYS " /proc/mounts; then exec >>$LOG 2>&1; fi; }
open_app() { echo "$APP" > $X/ctl/app.tmp && mv -f $X/ctl/app.tmp $X/ctl/app; t "open: $APP"; }

start_helpers() {
    rm -rf $W/exiting $W/exit.ack     # 上一次回到 Kindle 的锁（do_exit）：解冻 / 重新开始后不再算数
    kill $(cat $W/cmd.pid 2>/dev/null) 2>/dev/null   # 上次留下的命令监听（冻结期间还活着）
    watchdog &
    echo $! > $W/watchdog.pid
    # 解冻：上一轮的看守（冻结后它自己会退）。它退了以后 pid 号可能已经给了别的进程：只结束命令行还是 veradroid.sh 的
    gp=$(cat $W/guard.pid 2>/dev/null); [ -n "$gp" ] && grep -q "boot/veradroid.sh" /proc/$gp/cmdline 2>/dev/null && kill "$gp" 2>/dev/null
    wd_guard &                                         # 看门狗的看守（watchdog.sh）
    echo $! > $W/guard.pid
    sleep_watch &
    echo $! > $W/sleep.pid
    type usb_watch_start >/dev/null 2>&1 && usb_watch_start   # 插 USB 线时问要不要切到 USB 传输（usb.sh）
    cmd_listen &
    echo $! > $W/cmd.pid
    # 关机钩子：Kindle 关机 / 重启时先停 Android、卸载系统盘，否则 /mnt/base-us 卸不掉（EBUSY），用户存储会脏。
    # 冻结期间也要留着（do_exit 不杀它）。cwd 设成 /、输出写 tmpfs：自己绝不能占着 /mnt/base-us 或系统盘。
    swp=$(cat $W/shutw.pid 2>/dev/null)   # busybox: kill -0 "" 也返回 0，空 pid 要单独判断
    if [ -e /tmp/shutdown_in_progress ]; then
        t "stale /tmp/shutdown_in_progress (no shutdown running): shutdown hook not armed for this run"
    elif [ -x $VD/bin/vdshutwatch ] && ! { [ -n "$swp" ] && kill -0 "$swp" 2>/dev/null; }; then
        # 关机时跑的是 tmpfs 里的 stop.sh 副本：sh 会一直开着脚本文件，不能让它自己占住 /mnt/base-us
        cp $VD/boot/stop.sh $W/stop-shutdown.sh
        ( cd / && exec $VD/bin/vdshutwatch env VD=$VD sh $W/stop-shutdown.sh --shutdown ) </dev/null >>$W/shutw.log 2>&1 &
        echo $! > $W/shutw.pid
    fi
    host_oom_protect      # watchdog.sh：宿主进程不让 lowmemorykiller 挑中
}


P=$(cat $W/pid 2>/dev/null)
# 社区（KPW6 KUAL 越狱）：“不时出 bug 之后点启动就进不去系统，只能用更新脚本进”。更新脚本能进是因为它先完整 stop.sh、再冷启动；
# 而“启动”看到 Android 还在（冻着或在跑）就只解冻 / 打开应用——那个 Android 要是已经坏了（system_server / surfaceflinger 没了、
# 容器 init 早就退出而 pid 号被别的进程用了），解冻出来还是坏的，怎么点都一样。现在先看一眼：坏了就和更新脚本一样完整停掉再冷启动。
android_unusable() { # 输出原因；能用时什么都不输出
    au_c=$(cat /proc/$P/comm 2>/dev/null)
    [ "$au_c" = init ] || { echo "pid $P is '$au_c', not the container's init"; return 0; }
    [ -f $W/booted ] || return 0                   # 还在开机：不判断
    au_l=$(cat $FZ/cgroup.procs 2>/dev/null); [ -n "$au_l" ] || au_l=$(ns_pids 2>/dev/null)
    [ -n "$au_l" ] || return 0
    au_ss=; au_sf=
    for au_p in $au_l; do
        case "$(cat /proc/$au_p/comm 2>/dev/null)" in system_server) au_ss=1 ;; surfaceflinger) au_sf=1 ;; esac
    done
    [ -n "$au_ss" ] || echo "system_server is gone"
    [ -n "$au_sf" ] || echo "surfaceflinger is gone"
    return 0
}
if [ -n "$P" ] && kill -0 $P 2>/dev/null && ! { [ -d $W/starting ] && vd_lock_alive "$(cat $W/starting/pid 2>/dev/null)"; }; then
    au_why=$(android_unusable | tr '\n' ';')
    if [ -n "$au_why" ]; then
        t "android (pid $P) is not usable ($au_why): stopping it completely and starting again (like the installer does)"
        vd_slog "android was not usable ($au_why): full stop + cold start"
        splash "VeraDroid 上次运行出了问题，正在重新启动…" 36 || eips 1 2 "VeraDroid: restarting after a problem" >/dev/null 2>&1
        exec >>/tmp/veradroid-stop.log 2>&1
        VD_STOP_REASON="start: android not usable ($au_why)" sh $VD/boot/stop.sh >/dev/null 2>&1
        storage_mount; log_reopen
        P=
    fi
fi
if [ -n "$P" ] && kill -0 $P 2>/dev/null; then
    if [ -f $W/frozen ]; then
        # 解冻也只允许一个流程：冻结时连点两次“启动”，以前会起两套看门狗 / 休眠监视，还可能把 core_pattern 的原值盖掉
        if ! mkdir $W/thawing 2>/dev/null; then
            op=$(cat $W/thawing/pid 2>/dev/null)
            if vd_lock_alive "$op"; then t "already thawing (pid $op), ignored"; framework_paused || vd_notice "VeraDroid 正在恢复，请稍候" "VeraDroid is resuming, please wait" 5; exit 0; fi
            t "stale thaw lock (pid ${op:-?}), taking over"
        fi
        echo $$ > $W/thawing/pid
        trap 'rm -f $W/thawing/pid; rmdir $W/thawing 2>/dev/null' EXIT
        t "thaw requested"
        framework_stop
        # Kindle 画面停着的时候先给个提示，避免看起来像卡死
        splash "正在恢复" 40
        # 冻结期间积压的触摸丢掉；解冻后让容器整屏重画
        touch $X/ctl/sleep
        input_grab
        android_thaw
        # 显示桥 / 触摸前端每次开机最多自动重启 3 次（watchdog.sh）：开着几天的 Android 用完了次数，屏幕就永远停住。解冻时重新计数，
        # 显示桥没了马上拉起来（不等看门狗的下一轮）
        rm -f $W/epd.restarts $W/input.restarts
        [ -x "$VD/bin/veradroid-epd" ] && ! alive $W/epd.pid && { t "thaw: display bridge was gone, starting it"; display_start; }
        type lock_reset_check >/dev/null 2>&1 && { lock_reset_check; lock_request; }
        rm -f $W/frozen
        ( sleep 1; rm -f $X/ctl/sleep; epd_send REDRAW ) &
        start_helpers
        # 冻结期间 Kindle 上改了时区 / 12、24 小时制：告诉 Android（tz.sh，没变就什么都不做）
        ( sleep 3; tz_sync_live; time_format_apply ) </dev/null >>$LOG 2>&1 &
        type net_heal_kick >/dev/null 2>&1 && net_heal_kick thaw   # 冻着的时候 Kindle 的网络可能变了（network.sh）
        t "thawed"
    fi
    # Android 活着、没冻结，Kindle 界面却没有暂停（$FWD/paused 不在）：只可能是上一次退出 / 启动互相踩了（旧版本里 stop.sh 还没跑完
    # 就又点了启动），Android 在 Kindle 界面后面空转，显示通道多半已经被清掉。以前这里只是 open_app 然后退出——屏幕上什么都不发生，
    # 用户怎么点“启动”都没反应（kt6-517104 模拟器复现）。这种状态下整个重来：先完整停掉，再往下走冷启动。
    if [ ! -f $W/frozen ] && ! framework_paused && [ ! -d $W/starting ] && [ ! -d $W/thawing ]; then
        t "android (pid $P) is running but the kindle ui is in front: inconsistent state, restarting cleanly"
        splash "VeraDroid 上次没有退出干净，正在重新启动…" 36 || eips 1 2 "VeraDroid: restarting cleanly" >/dev/null 2>&1
        exec >>/tmp/veradroid-stop.log 2>&1          # 自己开着系统盘上的日志的话，stop.sh 卸不掉系统盘
        sh $VD/boot/stop.sh >/dev/null 2>&1
        storage_mount; log_reopen
        t "restart after inconsistent state: stopped, starting again"
    else
    open_app
    vd_slog "android already running (pid $P): opened $APP"
    exit 0
    fi
fi

# 同一时间只允许一个启动流程。从这里到写出 $W/pid 要十几秒（慢机型更久）：这期间再点一次“启动”，
# 第二个实例会判定“没在跑”，用 stop.sh --stale 杀掉正在启动的第一个，两个 Android 抢同一个 binder，
# servicemanager 注册失败、反复重启（PW3 模拟器实测）。mkdir 是原子的；持有者已经不在了就当残留接管。
# 安装在替换文件时被打断（见 bootstrap.sh 的 install_recover）：能回退就回到安装前那一版，然后用那一版重新启动
# 根目录里老版本留下的日志 / 标记 / 备份搬进 veradroid/（tidy.sh；很快，重复跑没有副作用）。要在下面看安装标记之前
type vd_root_tidy >/dev/null 2>&1 && vd_root_tidy
if [ -f /mnt/base-us/veradroid/state/install.incomplete ] && [ -z "$VD_RECOVERED" ]; then
    st=$(cat /mnt/base-us/veradroid/state/install.incomplete 2>/dev/null)
    if [ -n "$st" ] && [ -d "$SYS/host.bak.$st" ] && [ -d "$SYS/rootfs.bak.$st" ] && ! grep -q " $SYS/rootfs" /proc/mounts; then
        t "interrupted install $st: rolling back to the previous version"
        # 程序目录里的 logs / state / backup 不跟着挪（vd_prog_move）；host.bak 若是老安装器整个挪过去的，里面的日志和备份搬回来
        vd_prog_move "$VD" "$SYS/host.failed.$st" && vd_prog_move "$SYS/host.bak.$st" "$VD" && { vd_data_rescue "$SYS/host.bak.$st"; rm -rf "$SYS/host.bak.$st"; } && { [ ! -e "$SYS/rootfs" ] || mv "$SYS/rootfs" "$SYS/rootfs.failed.$st"; } && mv "$SYS/rootfs.bak.$st" "$SYS/rootfs" && rm -f /mnt/base-us/veradroid/state/install.incomplete
        VD_RECOVERED=1 exec sh "$VD/boot/veradroid.sh" "$@"
    fi
    t "interrupted install ${st:-?}: cannot roll back, run the installer again"
    vd_notice "VeraDroid：上次安装没有完成，请重新运行安装脚本" "VeraDroid: the last install did not finish, run the installer again"; exit 1
fi
# 离线更新文件夹（localupdate.sh，docs/host-settings.md）：veradroid/update/ 里放了比现在新的安装包就先校验、安装，
# 装好后用新版本的脚本接着启动。只看文件名的检查很快；没有包时什么都不做。装过一次（成功或失败）的包会被删掉 / 改名，不会每次重试。
if [ -z "$VD_LOCALUPD" ] && ls "$VD"/update/veradroid-*-*.tar.gz "$VD"/update/VeraDroid-Offline*.zip >/dev/null 2>&1; then
    export VD_LOCALUPD=1
    exec >>/tmp/veradroid-stop.log 2>&1
    if cp "$VD/boot/lib/localupdate.sh" $W/localupdate.sh 2>/dev/null && ( cd / && sh $W/localupdate.sh install ) </dev/null; then
        t "local update installed, restarting with the new version"
        exec sh "$VD/boot/veradroid.sh" "$@"
    fi
    storage_mount; log_reopen
fi
if ! mkdir $W/starting 2>/dev/null; then
    op=$(cat $W/starting/pid 2>/dev/null)
    if vd_lock_alive "$op"; then t "already starting (pid $op), ignored"; framework_paused || vd_notice "VeraDroid 正在启动，请稍候" "VeraDroid is starting, please wait" 5; exit 0; fi
    t "stale start lock (pid ${op:-?}), taking over"
fi
echo $$ > $W/starting/pid
trap 'rm -f $W/starting/pid; rmdir $W/starting 2>/dev/null' EXIT
# 上一次的 stop.sh 还没跑完：等它（最多 120 秒），否则它之后的清理会拆掉这一轮刚建好的东西
sp=$(cat /tmp/veradroid-stop.pid 2>/dev/null); i=0
while [ -n "$sp" ] && [ "$sp" != "$$" ] && [ "$sp" != "$PPID" ] && kill -0 "$sp" 2>/dev/null && grep -q stop /proc/$sp/cmdline 2>/dev/null; do
    [ $i -eq 0 ] && { t "waiting for the previous stop.sh (pid $sp)"; splash "VeraDroid 正在退出上一次运行，退出完会自动启动…" 36 || eips 1 2 "VeraDroid: finishing the previous run, will start right after" >/dev/null 2>&1; }
    # 等的时候不能开着系统盘上的日志：那个 stop.sh 正要卸载系统盘，我们开着它就卸不掉（自检会报 loop-still-attached，kt6-517104 模拟器实测）
    [ $i -eq 0 ] && exec >>/tmp/veradroid-stop.log 2>&1
    i=$((i+1)); [ $i -gt 120 ] && { t "previous stop.sh still running after 120 s: killing it"; kill -9 "$sp" 2>/dev/null; rm -f /tmp/veradroid-stop.pid; break; }
    sleep 1
done
[ $i -gt 0 ] && { ws=$i; storage_mount; log_reopen; t "previous stop.sh finished after ${ws}s, starting"; }   # storage_mount 自己也用 i
sh $VD/boot/stop.sh --stale >/dev/null 2>&1
rm -f $SYS/settings.app $W/appprof.pkg      # 上次运行时某个应用的显示参数：冷启动从桌面开始
storage_mount || { vd_notice "VeraDroid：系统盘还被上次运行占用，没能启动。请长按电源键重启 Kindle 后再试" "VeraDroid: the system image is still in use by the last run. Restart the Kindle (hold power), then try again"; exit 1; }
kmod_load
[ -d $R ] && [ -e $R/init -o -f $R/.demo ] || { t "no system image at $R"; vd_notice "VeraDroid：找不到系统文件，请重新运行安装脚本" "VeraDroid: system files missing, run the installer again"; exit 1; }
# 只在模拟器上验证过的机型：独立的看护进程（开机超时 / 启动脚本中途死掉时自己回到 Kindle）+ 开机后自检（tester.sh）
test_begin
FRAMEWORK_AFTER_SAVE='splash_begin 正在准备 3'
framework_stop || { t "cannot pause kindle ui"; vd_notice "VeraDroid：没能暂停 Kindle 界面，没有启动" "VeraDroid: could not pause the Kindle UI, not started"; exit 1; }
splash_update 12
splash_after_stop &
[ -x "$FBINK" ] || { t "no fbink; continuing with framebuffer/eips fallback"; FBINK=; }
display_prepare
touch $X/ctl/sleep       # 开机画面期间，显示桥不画 Android 的帧（开机完成后 splash_end 放开）
rm -f $W/epd.restarts $W/input.restarts   # watchdog.sh helpers_supervise：每次开机各最多重启 3 次
display_start
settings_publish
type appprof_publish >/dev/null 2>&1 && appprof_publish   # 每个应用的显示设置：合并表给桌面看（/dev/kfb/appprof）
# 上次运行留在系统设置里的强制显示密度（多看在前台时换的，appprof.sh）：容器起来之前抹掉，开机一律是默认密度
type appprof_density_boot >/dev/null 2>&1 && appprof_density_boot
touch_setup
# 找不到触摸屏就不要继续：Android 起来也操作不了，而 Kindle 界面已经停了。恢复 Kindle 界面后退出。
input_start || { t "no usable touch screen: stopping"; log_release; sh $VD/boot/stop.sh >/dev/null 2>&1; vd_notice "VeraDroid：找不到触摸屏，没有启动" "VeraDroid: no usable touch screen, not started"; exit 1; }
echo "androidboot.hardware=kindle androidboot.zygote=zygote32" > $W/cmdline
# 没有 net 命名空间：系统和 Kindle 共用网络，让 netd / 以太网服务进 hostnet 模式（只读、不改 Kindle 的网络）
hostns && echo "androidboot.hardware=kindle androidboot.zygote=zygote32 androidboot.vdhostnet=1" > $W/cmdline
# 时区跟 Kindle 一致：init 开机时从 /data/property/persist.sys.timezone 读。按 Kindle 时区文件里的规则找真实的时区名
# （夏令时、+05:30 这类非整点都对），找不到才按当前偏移（tz.sh）
tz_prepare
zram_on
slim_apply
su_hide
readahead_on
net_setup
netd_hostnet_fix   # chroot（老机型全是）：换上不会在 wlan0 断开时卡死的 netd（network.sh）
container_start || { log_release; sh $VD/boot/stop.sh >/dev/null 2>&1; vd_notice "VeraDroid：系统没能启动（容器起不来），已回到 Kindle" "VeraDroid: Android could not be started, back to Kindle"; exit 1; }
bat_baseline       # 电量统计起点（energy.sh）
net_start
echo "$APP" > $X/ctl/app
splash_update 20
dedup_start
boot_watch &
echo $! > $W/boot.pid
start_helpers
# Diagnostics are opt-in: continuous logcat made the first boot feel frozen.
[ -f /mnt/base-us/veradroid/debugboot ] && { diagboot & echo $! > $W/diagboot.pid; }
# Whole-image auto updater (opt out with /mnt/base-us/veradroid/autoupdate = 0)
# 自动更新常驻（每 6 小时看一次）。从 tmpfs 里的副本运行、cwd 为 /：sh 会一直开着脚本文件，
# 直接跑 $VD 里的那份会让 /mnt/base-us 在关机时卸不掉（EBUSY），哪怕 VeraDroid 早就停了。
[ -x "$VD/boot/lib/auto-update.sh" ] && cp "$VD/boot/lib/auto-update.sh" "$W/auto-update.sh.tmp" 2>/dev/null && mv -f "$W/auto-update.sh.tmp" "$W/auto-update.sh" && ( cd / && exec setsid sh $W/auto-update.sh ) </dev/null >/dev/null 2>&1 &
after_boot &
echo $! > $W/afterboot.pid    # legacy：stop.sh 要能停掉它（见 stop.sh）
t "started"
vd_slog "started (container up, android booting)"
exit 0
}
