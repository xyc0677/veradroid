#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 停止：先停宿主辅助进程，再 SIGKILL 容器 init（新 PID 命名空间的 1 号，一死内核杀掉整个命名空间），
# 卸载挂载，最后恢复 Kindle 界面。重复执行没关系。--stale：启动前清上次残留，不恢复 Kindle 界面。
VD=${VD:-$(cd "$(dirname "$0")/.." && pwd)}
. "$VD/boot/lib/common.sh"
for m in settings memory memkeep freeze framework container input network tester; do . "$VD/boot/lib/$m.sh"; done
# VD_STOP_REASON：调用方说明为什么停（安装器等）。父进程常常已经退出，光看父进程看不出是谁停的（用户诊断）
[ "$1" = "--stale" ] || { test_on && tup > $W/test.stop0 2>/dev/null; }
[ "$1" = "--stale" ] || echo "[$(date +%H:%M:%S)] stop.sh called (parent $PPID: $(tr '\000' ' ' < /proc/$PPID/cmdline 2>/dev/null | cut -c1-120))${VD_STOP_REASON:+ reason: $VD_STOP_REASON}" >> $LOG
STALE=; [ "$1" = "--stale" ] && STALE=1
# --stale 的快速路径（社区报告：上一次退出得干干净净时，启动前这一段也要空转好几秒，单核上还和启动抢 CPU）。
# 判断一律从严，有一点拿不准就照旧走完整流程：
#  - $W 里只有完整停止以后本来就留着的东西（STALE_KEEP，下面的完整流程不碰它们）和这次启动自己的锁 starting；
#    pid 文件、显示通道 kfb、冻结组、模式标记……任何一个在都不算干净；
#  - 没有容器的挂载（$R、$SYS/ 下面；$SYS 本身是 common.sh 刚挂上的）、没有容器进程（unshare --in-ns、slirp4netns、
#    根目录在系统盘上的进程）；没有正在跑的完整 stop.sh。
# 干净时也不卸下系统盘：common.sh 刚挂上，卸下来 veradroid.sh 马上又要再挂一次（再跑一次 e2fsck）。
# 完整停止以后 $W 里还留着的（PW4 模拟器 0.8.13 实测）：常驻的自动更新 / 测试看守和它们的 pid、上次的日志和画面状态、
# 设置的副本。下面的完整流程不碰它们（input.log 除外：它只是删掉一个日志，触摸前端下次启动时重写）。
STALE_KEEP="appprof.pkg auto-update.sh autoupdate.pid bat.logt bat.start bgnice boot.retry docs.before docs.missing gestures inject input.log poem shutw.log splash.cells splash.last splash.t0 splash.txt ss.pid stop-shutdown.sh submodel tester-watch.sh testwatch.pid tz.android uiderr.lines vdkaf.log"
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
    [ -z "$(chroot_pids)" ] || return 1
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
type bg_thaw_all >/dev/null 2>&1 && bg_thaw_all 2>/dev/null
type net_resume >/dev/null 2>&1 && net_resume     # 冻结时停下的 slirp4netns（freeze.sh）：停着收不到 SIGTERM
# Collect full ancestor chain so saver_exit_check (grandchild of sleep_watch) never kills stop.sh itself.
anc=" $$ "
_ap=$$; _ai=0
while [ $_ai -lt 16 ]; do
    _ap=$(awk '$1=="PPid:"{print $2; exit}' /proc/$_ap/status 2>/dev/null)
    case "$_ap" in ''|0|1|*[!0-9]*) break ;; esac
    anc="$anc$_ap "
    _ai=$((_ai + 1))
done
in_anc() { case "$anc" in *" $1 "*) return 0 ;; esac; return 1; }
ps=
for f in guard watchdog sleep lipc usb cmd boot afterboot slirp shutw compile; do
    p=$(cat $W/$f.pid 2>/dev/null)
    [ -n "$p" ] || continue
    # 先杀非祖先子进程（watchdog 的 sleep 20 / sleep_watch 的 lipc-wait-event / after_boot 等）
    case "$f" in guard|watchdog|sleep|lipc|usb|boot|afterboot|compile) for c in $(pgrep -P "$p" 2>/dev/null); do for cc in $(pgrep -P "$c" 2>/dev/null); do in_anc "$cc" && continue; kill $cc 2>/dev/null && ps="$ps $cc"; done; in_anc "$c" && continue; kill $c 2>/dev/null && ps="$ps $c"; done ;; esac
    in_anc "$p" && continue
    kill $p 2>/dev/null && ps="$ps $p"
done
for p in $(pgrep -f "lipc-wait-event .*com\.lab126\.hal usbPlugIn,usbPlugOut" 2>/dev/null); do kill $p 2>/dev/null && ps="$ps $p"; done
# 预编译的看守循环（compile_hold 起的，pid 在 compile.guard）也写 $LOG：一起停。工作进程看到 compile.quit 就不再开始新的等待
: > $W/compile.quit 2>/dev/null
p=$(cat $W/compile.guard 2>/dev/null); [ -n "$p" ] && kill $p 2>/dev/null && ps="$ps $p"
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
for f in guard watchdog sleep lipc usb cmd boot afterboot slirp shutw compile; do rm -f $W/$f.pid; done
rm -rf $W/appslim.lock $W/compile.quit $W/compile.guard $W/compile.stale $W/compile.lock $W/compile.queue $W/compile.total $W/compile.index $W/booted $W/pkglist.stamp $W/wifi.rx $W/wifi.busy
[ -n "$STALE" ] || [ "${VD_KEEP_RETRY:-0}" = 1 ] || rm -f $W/boot.retry
croot=$(chroot_root)        # legacy：容器根的 设备号:inode，要在删 $W/pid、杀 init 之前记下来（之后就读不到了）
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
rm -rf $W/bgfz.lock $W/memfg.bg $W/memfg.bg.tmp* $W/memfg.frozen $W/memfg.frozen.tmp* $W/memfg.stopped $W/wifi.idle $W/wifi.idleoff $W/exiting $W/exit.ack $W/sleep.cur $W/lastinput $W/lasttouch $W/memfg.cur

# legacy（3.0 内核，只有挂载命名空间）：容器进程不会随 init 一起消失，按“根目录和容器 init 相同”逐个杀；
# 挂载在私有挂载命名空间里，随它一起消失；下面的卸载只是兜底
if [ "$(cat $W/mode 2>/dev/null)" = chroot ] || [ ! -e /proc/self/ns/pid ]; then
    i=0
    while [ $i -lt 20 ]; do
        cp=$(chroot_pids "$croot"); [ -n "$cp" ] || break
        kill -CONT $cp 2>/dev/null; kill -9 $cp 2>/dev/null
        usleep 200000; i=$((i + 1))
    done
    [ -n "$(chroot_pids "$croot")" ] && t "stop: container processes still alive: $(chroot_pids "$croot" | tr '\n' ' ')"
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
# Kindle 书库的 bind（$R$KLIB_MP，2026-11-13 事故后从 data/media/0/KindleLibrary 挪到根下）：在私有挂载命名空间里，
# 上面卸完之后宿主表里不应该再有。万一还在（容器进程没杀干净、unshare -m 失败等），这里大声记下来并再卸一次：
# 留着它，以后对 $SYS 的任何 rm -rf 都可能穿过去删到 Kindle 的书。
if grep -q " $R$KLIB_MP " /proc/mounts; then
    t "!!! stop: Kindle library still bound at $R$KLIB_MP on the host, unmounting"
    umount "$R$KLIB_MP" 2>/dev/null || umount -l "$R$KLIB_MP" 2>/dev/null
fi
freezer_cleanup
kmsg "container mounts released"
# 多看在前台时换过的显示密度会存进 Android 的设置文件：容器已经没了，趁系统盘还挂着把它抹掉（appprof.sh；下次开机前还会再抹一次）
[ -f "$VD/boot/lib/appprof.sh" ] && . "$VD/boot/lib/appprof.sh" && appprof_density_scrub >> $LOG 2>&1
[ -n "$STALE" ] && { lmk_kernel_restore 2>/dev/null || true; rm -rf $W/kfb; storage_umount; exit 0; }
readahead_restore 2>/dev/null || true
dedup_stop 2>/dev/null || true
zram_restore
wifi_ps_restore
rm -f $W/cmdline $W/power.fifo
if [ -n "$RS" ]; then rm -f $W/restarting; elif [ -z "$SHUT" ]; then framework_start; fi
[ -n "$SHUT" ] && vdkaf_stop 2>/dev/null      # Kindle 在关机：顶替框架的 vdkaf（KINDLE_UI=stop）不用再留
rm -rf $W/kfb
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
fi
sys_holders_kill
kmsg "storage_umount"
# 只在模拟器上验证过的机型：系统盘卸掉以前留一份宿主日志（去掉文件名），“上传测试结果”时系统盘不挂也能带上
test_on && [ -f $LOG ] && { mkdir -p $TDIR; tail -c 300000 $LOG | redact > $TDIR/veradroid-last.log; }
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
[ -z "$SHUT" ] && [ -z "$RS" ] && { userstore_check stop; test_after_stop "$uc_line"; }
echo stopped
