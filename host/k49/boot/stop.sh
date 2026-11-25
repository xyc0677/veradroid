#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 停止：先停宿主辅助进程，再 SIGKILL 容器 init（新 PID 命名空间的 1 号，一死内核杀掉整个命名空间），
# 卸载挂载，最后恢复 Kindle 界面。重复执行没关系。--stale：启动前清上次残留，不恢复 Kindle 界面。
VD=${VD:-$(cd "$(dirname "$0")/.." && pwd)}
. "$VD/boot/lib/common.sh"
for m in settings memory freeze framework container input network; do . "$VD/boot/lib/$m.sh"; done
# VD_STOP_REASON：调用方说明为什么停（安装器等）。父进程常常已经退出，光看父进程看不出是谁停的（用户诊断）
[ "$1" = "--stale" ] || echo "[$(date +%H:%M:%S)] stop.sh called (parent $PPID: $(tr '\000' ' ' < /proc/$PPID/cmdline 2>/dev/null | cut -c1-120))${VD_STOP_REASON:+ reason: $VD_STOP_REASON}" >> $LOG
STALE=; [ "$1" = "--stale" ] && STALE=1
# --stale 的快速路径（社区报告：上一次退出得干干净净时，启动前这一段也要空转，单核上还和启动抢 CPU）。
# 判断一律从严，有一点拿不准就照旧走完整流程：
#  - $W 里只有完整停止以后本来就留着的东西（STALE_KEEP，下面的完整流程不碰它们）和这次启动自己的锁 starting；
#    pid 文件、显示通道 kfb、冻结组、模式标记、loop 直通 I/O 的记录……任何一个在都不算干净；
#  - 没有容器的挂载（$R、$SYS/ 下面；$SYS 本身是 common.sh 刚挂上的）、没有容器进程（unshare --in-ns、slirp4netns；
#    chroot 模式下根目录在系统盘上的进程）、没有留下的无线调试密钥（完整流程每次都删）、没有正在跑的完整 stop.sh。
# 干净时也不卸下系统盘：common.sh 刚挂上，卸下来 veradroid.sh 马上又要再挂一次（再跑一次 e2fsck）。
# 完整停止以后 $W 里还留着的（PW6 模拟器 1.02 实测；k49 多两个它自己的）：常驻的自动更新和它的 pid、上次的日志、
# 画面 / 预编译的状态、设置的副本。下面的完整流程不碰它们（input.log 除外：只是删掉一个日志，触摸前端下次启动时重写）
STALE_KEEP="alarmfix lmk.state appprof.pkg auto-update.sh autoupdate.pid bat.logt bat.start bw.released compile.dexmb.* compile.last compile.out docs.before docs.missing gestures idle.net inject input.log memfg.cur poem shutw.log splash.cells splash.last splash.t0 splash.txt stop-shutdown.sh tz.android uiderr.lines submodel vdkaf.log"
stale_clean() {
    sc_p=$(cat "${VD_STOP_PIDF:-/tmp/veradroid-stop.pid}" 2>/dev/null); [ -n "$sc_p" ] && kill -0 "$sc_p" 2>/dev/null && return 1
    for sc_f in $(ls -A $W 2>/dev/null); do
        for sc_k in starting $STALE_KEEP; do case "$sc_f" in $sc_k) continue 2 ;; esac; done
        return 1
    done
    awk -v r="$R" -v s="$SYS/" '$2 == r || index($2, r "/") == 1 || index($2, s) == 1 { f = 1 } END { exit f }' "${VD_MOUNTS:-/proc/mounts}" || return 1
    [ -d $FZ ] && return 1
    [ -e "${VD_ADBKEYS:-$SYS/data/misc/adb/adb_keys}" ] && return 1
    [ -z "$(pgrep -f "^unshare -m -u -i -n -p -f -- sh .*--in-ns" 2>/dev/null)" ] || return 1
    [ -z "$(pgrep -f "^[^ ]*slirp4netns .*--api-socket $W/slirp.sock" 2>/dev/null)" ] || return 1
    if hostns; then [ -z "$(chroot_pids)" ] || return 1; fi
    return 0
}
if [ -n "$STALE" ] && stale_clean; then t "stop --stale: nothing left from the previous run" >> $LOG 2>/dev/null; exit 0; fi
# 完整的 stop 和下一次启动不能叠在一起：负载高时 stop 可能跑很久，晚到的清理会删掉新一轮的 $W/fw、$W/kfb（kt5 模拟器实测）。
# veradroid.sh 启动前会等这个 pid 结束。放在 /tmp：$W 里的东西本脚本自己会删。
if [ -z "$STALE" ]; then echo $$ > /tmp/veradroid-stop.pid; trap 'rm -f /tmp/veradroid-stop.pid' EXIT; fi
# 启动还没做完就叫停（KT5 用户：点了启动 8 秒左右就退出）：以前这里不管还在跑的 veradroid.sh，它照样往下把 Android 起了
# （模拟器 kt5-5182：stop.sh 在第 8 秒、容器在第 26 秒起来），而本脚本同时在恢复 Kindle 界面——两边各做一半，回 Kindle 要十几分钟。
# 先结束那个 veradroid.sh（只结束本脚本开始之前就在启动的那个、而且不是本脚本的祖先：veradroid.sh 自己出错时也调本脚本），
# 再往下杀容器、恢复 Kindle 界面。它已经起来的子进程（显示桥、输入、容器……）下面照常按 pid 收拾；framework_stop 在后台
# 停服务的那一段由 framework_start 叫停。
if [ -z "$STALE" ]; then
    sp=$(cat $W/starting/pid 2>/dev/null)
    if [ -n "$sp" ] && kill -0 "$sp" 2>/dev/null && grep -q "boot/veradroid.sh" /proc/$sp/cmdline 2>/dev/null; then
        a=$$; anc=" $$ "
        while [ -n "$a" ] && [ "$a" -gt 1 ] 2>/dev/null; do a=$(awk '/^PPid:/ {print $2}' /proc/$a/status 2>/dev/null); anc="$anc$a "; done
        s0=$(awk '{print $22}' /proc/$$/stat 2>/dev/null); s1=$(awk '{print $22}' /proc/$sp/stat 2>/dev/null)
        case "$anc" in *" $sp "*) ;; *)
            if [ -n "$s0" ] && [ -n "$s1" ] && [ "$s1" -le "$s0" ] 2>/dev/null; then
                kill "$sp" 2>/dev/null
                i=0; while kill -0 "$sp" 2>/dev/null && [ $i -lt 20 ]; do usleep 100000; i=$((i + 1)); done
                kill -9 "$sp" 2>/dev/null
                rm -f $W/starting/pid; rmdir $W/starting 2>/dev/null
                t "stop: a start was still in progress (veradroid.sh pid $sp): ended it first" >> $LOG 2>/dev/null
            fi ;;
        esac
    fi
fi
# --shutdown：Kindle 正在关机 / 重启（bin/vdshutwatch 调用）。停 Android、卸载系统盘，但不要再把 Kindle 界面拉起来
SHUT=; [ "$1" = "--shutdown" ] && SHUT=1
# 关机时 /mnt/base-us 上的日志可能来不及落盘：每一步同时写进内核日志（串口 / dmesg 里能看到）
kmsg() { [ -n "$SHUT" ] && echo "veradroid-stop: $*" > /dev/kmsg 2>/dev/null; return 0; }
kmsg "begin"
RS=; [ -f $W/restarting ] && RS=1

[ -d $FZ ] && android_thaw 2>/dev/null          # 冻着的进程收不到 SIGKILL
type net_resume >/dev/null 2>&1 && net_resume     # 冻结时停下的 slirp4netns（freeze.sh）：停着收不到 SIGTERM
ps=
for f in guard watchdog sleep lipc usb cmd boot afterboot slirp shutw compile vdpin; do
    p=$(cat $W/$f.pid 2>/dev/null)
    [ "$p" = "$PPID" ] && continue              # 看门狗自己调的：它在等这个脚本跑完
    [ "$p" = "$$" ] && continue                 # vdshutwatch exec 成了本脚本：pid 就是自己
    # 先杀子进程（watchdog 的 sleep 20 之类）：父进程死了它们还会继承着打开的脚本文件，让 /mnt/base-us 卸不掉
    # 只限这几个循环：cmd 的子进程里有脱离出去的卸载 / 整包更新，不能杀
    case "$f" in guard|watchdog|sleep|lipc|usb|boot|afterboot|compile) [ -n "$p" ] && for c in $(pgrep -P "$p" 2>/dev/null); do for cc in $(pgrep -P "$c" 2>/dev/null); do kill $cc 2>/dev/null && ps="$ps $cc"; done; kill $c 2>/dev/null && ps="$ps $c"; done ;; esac
    [ -n "$p" ] && kill $p 2>/dev/null && ps="$ps $p"
done
for p in $(pgrep -f "lipc-wait-event .*com\.lab126\.hal usbPlugIn,usbPlugOut" 2>/dev/null); do kill $p 2>/dev/null && ps="$ps $p"; done
for p in $(pgrep -f "^unshare -m -u -i -n -p -f -- sh .*--in-ns" 2>/dev/null); do
    for c in $(cat /proc/$p/task/$p/children 2>/dev/null) $p; do kill -9 $c 2>/dev/null && ps="$ps $c"; done
done
i=0
while [ -n "$ps" ] && [ $i -lt 10 ]; do
    a=; for p in $ps; do kill -0 $p 2>/dev/null && a=1; done
    [ -n "$a" ] || break
    usleep 100000; i=$((i + 1))
done
for p in $ps; do kill -9 $p 2>/dev/null; done
kmsg "helpers and container killed"
for p in $(pgrep -f "^[^ ]*slirp4netns .*--api-socket $W/slirp.sock" 2>/dev/null); do kill $p 2>/dev/null; done
for f in guard watchdog sleep lipc usb cmd boot afterboot slirp shutw compile vdpin; do rm -f $W/$f.pid; done
rm -rf $W/ss.pid $W/prefault.done $W/prefault.d $W/prefault.pid $W/compile.manual $W/compile.wake $W/compile.mkill.* $W/compile.stale $W/compile.lock $W/compile.queue $W/compile.total $W/compile.index $W/booted $W/pkglist.stamp $W/wifi.rx $W/wifi.busy
input_stop
display_stop
# 共用网络模式下传书开着时放行过的端口（cmd.sh fwd_on）：撤掉，不让 Kindle 的防火墙一直开着这个口
case "$(cat $W/fwd.id 2>/dev/null)" in ipt:*) iptables -D INPUT -i "$(cut -d: -f3 $W/fwd.id)" -p tcp --dport "$(cut -d: -f2 $W/fwd.id)" -j ACCEPT 2>/dev/null ;; esac
# saver_*：屏保期间被停掉时 sleep.sh 来不及删；残留的 saver_frozen 让下一次运行的看门狗一直当作在屏保（不预编译、不应用设置、蓝牙按键全丢；PW6 真机遇到）
[ -f $W/adbfwd.ipt ] && iptables -D INPUT -i "$(cut -d: -f2 $W/adbfwd.ipt)" -p tcp --dport "$(cut -d: -f1 $W/adbfwd.ipt)" -j ACCEPT 2>/dev/null   # 无线调试（webadmin.sh）
rm -f $W/adbfwd.ipt $W/adbfwd.id $W/adb.until $X/ctl/adb.on $SYS/data/misc/adb/adb_keys 2>/dev/null
[ -f $W/fwd.ipt ] && iptables -D INPUT -i "$(cut -d: -f2 $W/fwd.ipt)" -p tcp --dport "$(cut -d: -f1 $W/fwd.ipt)" -j ACCEPT 2>/dev/null
kill $(cat $W/netheal.pid 2>/dev/null) 2>/dev/null
rm -f $W/netheal.pid $W/net.sig $W/net.last $W/resolv.new
rm -f $W/fwd.id $W/fwd.ipt $W/epd.log $W/pid $W/unshare.pid $W/frozen $W/saver_frozen $W/saver_nofreeze $W/slirp.sock $W/slirp.log $W/resolv.conf $W/input.log $W/input.err
rm -rf $W/appslim.lock $W/idlefz $W/idlefz.t $W/exiting $W/exit.ack $W/alarmfix $W/appprof.pkg $W/bat.start $W/bw.released $W/docs.before $W/docs.missing $W/gestures $W/inject $W/lastinput $W/lasttouch $W/memfg.cur $W/poem $W/shutw.log $W/sleep.cur $W/splash.* $W/tz.android $W/uiderr.lines $W/webview.mode
[ -n "$SHUT" ] || rm -f $W/stop-shutdown.sh

# chroot 模式（3.0 内核，没有命名空间）：容器进程不会随 init 一起消失，按根目录逐个杀；
# 挂载做在宿主上，由深到浅卸掉 $R 下面的每一个，再卸 $R 自己
if [ "$(cat $W/mode 2>/dev/null)" = chroot ] || hostns; then
    i=0
    while [ $i -lt 20 ]; do
        cp=$(chroot_pids); [ -n "$cp" ] || break
        kill -CONT $cp 2>/dev/null; kill -9 $cp 2>/dev/null
        usleep 200000; i=$((i + 1))
    done
    [ -n "$(chroot_pids)" ] && t "stop: chroot processes still alive: $(chroot_pids | tr '\n' ' ')"
    awk -v r="$R/" 'index($2, r) == 1 { print length($2), $2 }' /proc/mounts | sort -rn | while read -r _ m; do
        umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null
    done
    rm -f $W/mode
fi
# 容器里的挂载随命名空间消失；外面只有 $R 自己的只读绑定
# Drop host-ns binds under $R/$SYS (binder_repair) before storage_umount
awk -v d="$SYS" 'index($2, d "/") == 1 {print length($2), $2}' /proc/mounts | sort -rn | while read _ m; do
    umount -l "$m" 2>/dev/null || true
done
while grep -q " $R " /proc/mounts; do umount -l $R || break; done
freezer_cleanup
kmsg "container mounts released"
# 多看在前台时换过的显示密度会存进 Android 的设置文件：容器已经没了，趁系统盘还挂着把它抹掉（appprof.sh；下次开机前还会再抹一次）
[ -f "$VD/boot/lib/appprof.sh" ] && . "$VD/boot/lib/appprof.sh" && appprof_density_scrub >> $LOG 2>&1
# the Kindle's own /mnt/base-us loop back to buffered I/O also on a stale-state stop (a crashed run must not leave it in
# direct mode: the next start would see dio=1 already, not record it, and never restore it)
[ -n "$STALE" ] && { loopdio_restore 2>/dev/null || true; lmk_kernel_restore 2>/dev/null || true; rm -rf $W/kfb; storage_umount; exit 0; }
readahead_restore 2>/dev/null || true
dedup_stop 2>/dev/null || true
loopdio_restore 2>/dev/null || true
zram_restore
wifi_ps_restore
rm -f $W/cmdline $W/power.fifo
if [ -n "$RS" ]; then rm -f $W/restarting; elif [ -z "$SHUT" ]; then framework_start; fi
[ -n "$SHUT" ] && vdkaf_stop 2>/dev/null      # Kindle 在关机：顶替框架的 vdkaf（KINDLE_UI=stop）不用再留
rm -rf $W/kfb $W/vdkaf.log
if [ -n "$SHUT" ]; then   # 关机时连自动更新也停掉（它的锁目录和日志在 /mnt/base-us 上）
    ap=$(cat $W/autoupdate.pid 2>/dev/null)
    [ -n "$ap" ] && { for c in $(pgrep -P "$ap" 2>/dev/null); do kill $c 2>/dev/null; done; kill "$ap" 2>/dev/null; }
fi
if [ -n "$SHUT" ]; then
    # 关机时：任何还开着 $VD/boot 里脚本的 VeraDroid 进程（漏记 pid 的子 shell 等）都会让 /mnt/base-us 卸不掉。
    # 只杀命令行里带 $VD/boot/ 的进程（都是 VeraDroid 自己的），本脚本跑的是 tmpfs 副本，不会误杀自己。
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}; [ "$pid" = "$$" ] && continue
        case "$(tr '\000' ' ' < $d/cmdline 2>/dev/null)" in *"$VD/boot/"*) kill "$pid" 2>/dev/null && kmsg "killed leftover $pid" ;; esac
    done
    usleep 300000 2>/dev/null || sleep 1
elif [ -z "$RS" ]; then
    # 正常退出也要清：cmd 监听派出去的后台小任务（切换 Wi-Fi 最长等 30 秒、网络诊断、存储信息……）是 veradroid.sh 的子 shell，
    # 一直开着 $VD/boot 里的脚本文件和 $LOG。用户诊断（KT5，USB 大容量存储固件）：退出前 12 秒切过 Wi-Fi，退出以后它还在写
    # ctl/wifi.result。这种机型插上数据线时 volumd 要先卸掉 /mnt/base-us，有任何进程开着里面的文件就卸不掉，电脑上连不上，只能重启 Kindle。
    # 只杀：stop.sh 开始之前就在的、命令行里带 $VD/boot/ 的进程。不杀自己和祖先（调用方在等本脚本，例如 cmd 监听自己），
    # 不杀之后才起来的（退出过程中用户又点了启动：它在等这次 stop 结束）。脱离出去的卸载 / 整包更新 / 离线更新跑的是 tmpfs 副本，命令行里没有 $VD/boot/。
    st0=$(awk '{print $22}' /proc/$$/stat 2>/dev/null)
    anc=" $$ "; a=$$
    while [ -n "$a" ] && [ "$a" -gt 1 ] 2>/dev/null; do a=$(awk '/^PPid:/ {print $2}' /proc/$a/status 2>/dev/null); anc="$anc$a "; done
    kl=
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        case "$anc" in *" $pid "*) continue ;; esac
        case "$(tr '\000' ' ' < $d/cmdline 2>/dev/null)" in *"$VD/boot/"*) ;; *) continue ;; esac
        ps0=$(awk '{print $22}' $d/stat 2>/dev/null)
        [ -n "$ps0" ] && [ -n "$st0" ] && [ "$ps0" -le "$st0" ] 2>/dev/null || continue
        kill "$pid" 2>/dev/null && kl="$kl $pid"
    done
    if [ -n "$kl" ]; then
        i=0; while [ $i -lt 10 ]; do a=; for p in $kl; do kill -0 $p 2>/dev/null && a=1; done; [ -n "$a" ] || break; usleep 100000; i=$((i + 1)); done
        for p in $kl; do kill -9 $p 2>/dev/null; done
        t "stop: ended leftover helpers still holding the user store:$kl"
    fi
fi
kmsg "storage_umount"
storage_umount
if [ -n "$SHUT" ]; then
    t "shutdown: android stopped, system image released ($(grep -c " $SYS " /proc/mounts) mounts left)"
    kmsg "done; mounts left under $SYS: $(awk -v d="$SYS" '$2 == d || index($2, d "/") == 1 {printf "%s ", $2}' /proc/mounts); loop: $(losetup -a 2>/dev/null | grep -c veradroid)"
    # 还有谁占着 /mnt/base-us（诊断用）
    for d in /proc/[0-9]*; do
        h=$(ls -l $d/cwd $d/fd 2>/dev/null | grep -c "/mnt/base-us")
        [ "$h" -gt 0 ] && kmsg "holder ${d#/proc/} $(tr '\000' ' ' < $d/cmdline 2>/dev/null | cut -c1-100)"
    done
fi
# 自检：回到 Kindle 以后不应该再有任何东西占着用户存储、volumd 不应该还停着（common.sh 的 userstore_check）
[ -z "$SHUT" ] && [ -z "$RS" ] && userstore_check stop
echo stopped
