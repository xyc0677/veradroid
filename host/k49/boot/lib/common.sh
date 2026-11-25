# SPDX-License-Identifier: GPL-3.0-or-later
# 公共定义。所有模块都 source 这个文件。
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
# 4.1 内核（Oasis 2 / 3，2G/2G 分割）：默认的自顶向下 mmap 把 libart.so 放在 ~0x76xxxxxx，压在 0x70000000 启动镜像上。
# 未带 ro.vd.artbase 的镜像用 ulimit -s unlimited 切换自底向上布局（同 host/legacy），4.9+ 内核直接跳过。
vd_art_stack() {   # [build.prop]
    case "$(uname -r)" in 3.*|4.1.*) ;; *) return 0 ;; esac
    if grep -q '^ro.vd.artbase=' "${1:-/mnt/base-us/system/veradroid/rootfs/system/build.prop}" 2>/dev/null; then ulimit -s 8192 2>/dev/null
    else ulimit -s unlimited 2>/dev/null; fi
}
vd_art_stack
VD=${VD:-/mnt/base-us/veradroid}          # 程序目录（一律走 ext4 的 /mnt/base-us，不走 /mnt/us 的 FUSE）
SYS=/mnt/base-us/system/veradroid          # 数据：rootfs、data、日志、设置（书库不扫 system/）
SYSTEM_IMG=/mnt/base-us/veradroid-sys.img   # vfat /mnt/base-us 上的 ext4 系统盘（PW5 等）
# 日志 / 标记 / 备份的位置（veradroid/logs、state、backup）和根目录旧文件的搬迁：tidy.sh
[ -f "$VD/boot/lib/tidy.sh" ] && . "$VD/boot/lib/tidy.sh"
# 更新前备份的设置和“已备份的数据”（列出 / 删除）：backup.sh
[ -f "$VD/boot/lib/backup.sh" ] && . "$VD/boot/lib/backup.sh"
# 有的机型（KT6，eink-emulator 实测）loop 节点只有 /dev/loop/N，没有 /dev/loopN，`mount -o loop` 找不到空闲设备。
# 先试 mount -o loop，不行就自己挑一个空闲节点用 busybox losetup 绑定。
vdblk_try_load() {
    grep -q "^vdblk " /proc/modules 2>/dev/null && return 0
    vk="$VD/kmod/$(uname -r)/vdblk.ko"
    [ -f "$vk" ] && insmod "$vk" 2>/dev/null || true
    if [ ! -e /dev/vdblk-ctl ]; then
        vm=$(awk '$2=="vdblk-ctl"{print $1}' /proc/misc 2>/dev/null)
        [ -n "$vm" ] && mknod -m 600 /dev/vdblk-ctl c 10 "$vm" 2>/dev/null || true
    fi
}
img_drop_file_cache() {
    [ -f "$SYSTEM_IMG" ] || return 0
    sync
    [ -x "$VD/bin/vdloopdio" ] && "$VD/bin/vdloopdio" --drop "$SYSTEM_IMG" >/dev/null 2>&1 || true
}
vdblk_attach() { # img -> /dev/vdblkN
    [ "${VDBLK_DIRECT:-1}" != 0 ] && [ "$1" = "$SYSTEM_IMG" ] && [ -x "$VD/bin/vdblkctl" ] || return 1
    vdblk_try_load
    grep -q "^vdblk " /proc/modules 2>/dev/null || return 1
    vd_b=$("$VD/bin/vdblkctl" --attach "$1" 2>/dev/null) || vd_b=
    [ -n "$vd_b" ] && [ -b "$vd_b" ] || return 1
    echo "$vd_b"
}
loop_detach() {
    case "$1" in
        *vdblk*) [ -x "$VD/bin/vdblkctl" ] && "$VD/bin/vdblkctl" --detach "$SYSTEM_IMG" >/dev/null 2>&1 || true ;;
        *) losetup -d "$1" 2>/dev/null || true ;;
    esac
}
loop_mount() { # img dir [attached_bdev]
    vd_b=${3:-}
    [ -z "$vd_b" ] && vd_b=$(vdblk_attach "$1" 2>/dev/null || true)
    if [ -n "$vd_b" ] && [ -b "$vd_b" ]; then
        if mount -t ext4 -o rw,noatime,commit=30 "$vd_b" "$2" 2>/dev/null || mount -t ext4 -o rw,noatime "$vd_b" "$2" 2>/dev/null || mount -t ext3 -o rw,noatime "$vd_b" "$2" 2>/dev/null; then
            return 0
        fi
        "$VD/bin/vdblkctl" --detach "$1" >/dev/null 2>&1 || true
    fi
    mount -o loop,rw,noatime "$1" "$2" 2>/dev/null && return 0
    for d in /dev/loop/[0-9]* /dev/loop[0-9]*; do
        [ -b "$d" ] || continue
        losetup "$d" >/dev/null 2>&1 && continue      # 已占用
        losetup "$d" "$1" 2>/dev/null || continue
        { mount -t ext4 -o rw,noatime "$d" "$2" 2>/dev/null || mount -t ext3 -o rw,noatime "$d" "$2" 2>/dev/null; } && return 0   # 3.0 内核只有 ext3
        losetup -d "$d" 2>/dev/null
    done
    return 1
}
# 系统盘镜像当前挂在哪些 loop / vdblk 设备上
img_loops() {
    [ -d /sys/block/vdblk0 ] && echo vdblk0
    for b in /sys/block/loop*/loop/backing_file; do
        case "$(cat "$b" 2>/dev/null)" in *veradroid-sys.img) d=${b%/loop/backing_file}; echo "${d##*/}" ;; esac
    done
}
loop_dev() { # loopN / vdblk0 -> 设备节点（有的机型只有 /dev/loop/N）
    case "$1" in vdblk*) echo /dev/$1; return ;; esac
    n=${1#loop}
    [ -b /dev/loop$n ] && echo /dev/loop$n || echo /dev/loop/$n
}
# 占着系统盘镜像（这些 loop 上的文件系统）的进程：打开的文件、当前目录、根目录、映射的程序 / 库。一行一个 pid（不含自己和祖先）。
img_holders() { # loopN...
    ih_maps=; ih_devs=
    for l in "$@"; do
        d=$(cat /sys/block/$l/dev 2>/dev/null) || continue
        ma=${d%%:*}; mi=${d##*:}
        ih_maps="$ih_maps $(printf '%02x:%02x' $ma $mi)"
        ih_devs="$ih_devs $(printf '%x' $(( (ma << 8) | (mi & 255) | ((mi & ~255) << 12) )))"
    done
    [ -n "$ih_maps" ] || return 0
    ih_anc=" $$ "; a=$$
    while [ -n "$a" ] && [ "$a" -gt 1 ] 2>/dev/null; do a=$(awk '/^PPid:/ {print $2}' /proc/$a/status 2>/dev/null); ih_anc="$ih_anc$a "; done
    for d in /proc/[0-9]*; do
        n=${d#/proc/}
        case "$ih_anc" in *" $n "*) continue ;; esac
        h=
        for x in $ih_maps; do grep -q " $x " $d/maps 2>/dev/null && { h=1; break; }; done
        if [ -z "$h" ]; then
            for f in $d/cwd $d/root $d/fd/*; do
                fd=$(stat -L -c %D "$f" 2>/dev/null) || continue
                case " $ih_devs " in *" $fd "*) h=1; break ;; esac
            done
        fi
        [ -n "$h" ] && echo "$n"
    done
}
# 结束占着系统盘的残留进程、卸掉这些 loop 的残留挂载（只在 Android 没在运行时）。storage_mount 摘 loop 摘不掉时调用。
img_release() { # loopN...
    [ $# -gt 0 ] || return 0
    rp=$(cat $W/pid 2>/dev/null)
    if [ -n "$rp" ] && kill -0 "$rp" 2>/dev/null; then t "storage: image still attached and android (pid $rp) is running: not touching it"; return 0; fi
    hl=$(img_holders "$@")
    # 书库启动时这一段还在第一阶段，输出接的是启动器的管道：同时记进系统盘外面的 start.log
    mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null
    { echo "[$(date '+%Y-%m-%d %H:%M:%S')] storage: system image still attached to $*; holders: $(for p in $hl; do printf '%s(%s) ' $p "$(tr '\000' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-60)"; done)"; } >> "${VD_START_LOG:-/mnt/base-us/veradroid/logs/start.log}" 2>/dev/null
    for p in $hl; do t "storage: pid $p still holds the system image: $(tr '\000' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-120), ending it"; kill -CONT $p 2>/dev/null; kill $p 2>/dev/null; done
    if [ -n "$hl" ]; then
        k=0; while [ $k -lt 20 ]; do a=; for p in $hl; do kill -0 $p 2>/dev/null && a=1; done; [ -n "$a" ] || break; usleep 100000; k=$((k + 1)); done
        for p in $hl; do kill -9 $p 2>/dev/null; done
    fi
    for l in "$@"; do
        awk -v d="$(loop_dev $l)" -v e="/dev/$l" '$1 == d || $1 == e {print length($2), $2}' /proc/mounts | sort -rn | while read -r _ m; do
            umount "$m" 2>/dev/null || umount -l "$m" 2>/dev/null; t "storage: dropped a leftover mount of the system image at $m"
        done
    done
    [ -n "$hl" ] || t "storage: system image still attached to $* but no process holds it"
    return 0
}
storage_mount() {
    [ -f "$SYSTEM_IMG" ] || return 0
    grep -q " $SYS " /proc/mounts && return 0
    mkdir -p "$SYS" 2>/dev/null || true
    # 绝不能把同一个镜像挂两次：上次停止时延迟卸载（umount -l）的挂载树如果还被没退干净的进程引用着，
    # 镜像的文件系统实例和它的 loop 设备都还活着，只是 $SYS 上看不到了。这时再挂一次（新的 loop 设备）
    # 就是两个 ext4/ext3 实例同时写一个文件，元数据必坏（PW3 模拟器实测：loop1 和 loop9 同时指向镜像），
    # e2fsck 去修一个还在用的镜像也一样会弄坏它。所以先把残留的 loop 摘掉；摘不掉就不启动。
    i=0
    while [ -n "$(img_loops)" ]; do
        for l in $(img_loops); do loop_detach "$(loop_dev $l)"; done
        [ -z "$(img_loops)" ] && break
        # 摘不掉 = 还有进程占着镜像里的文件（上次退出没收干净的子进程、没死透的 Android 进程）。以前只是等 5 秒然后不启动，
        # 而这时 Kindle 界面在前台、提示被书库重画盖掉：用户看到的是“正在打开”之后什么都没有，只有重启 Kindle 才好
        # （pw5-5192 模拟器用一个占着镜像的 loop 复现）。Android 没在运行时，占着我们自己系统盘的只可能是 VeraDroid 的残留：结束它们再摘。
        [ $i = 3 ] && img_release $(img_loops)
        i=$((i + 1)); [ $i -ge 25 ] && { t "storage: system image still attached to $(img_loops | tr '\n' ' ')(previous run not finished; holders: $(img_holders $(img_loops) | tr '\n' ' '))"; return 1; }
        usleep 200000
    done
    img_drop_file_cache
    # 上次调整大小没做完（断电 / 被杀）、或者留了“下次启动时扩容”：先收尾（storage.sh）。别的进程正在调整就不挂。
    if type st_recover >/dev/null 2>&1; then
        st_recover || { t "storage: a resize is in progress, not mounting"; return 1; }
    fi
    # 系统盘是 vfat 上的一个镜像文件：没电关机、死机、强制重启都可能让它的 ext4/ext3 元数据不一致
    # （KT5 模拟器实测：“block bitmap corrupt … Data will be lost”，日志都写坏了）。挂载前先自动修：
    # vdblk 直通时直接对 /dev/vdblk0 用随包新版 e2fsck 检查，避免 vfat 文件页缓存与块设备缓存产生别名冲突。
    sm_bdev=$(vdblk_attach "$SYSTEM_IMG" 2>/dev/null || true)
    if [ -n "$sm_bdev" ]; then
        sm_fsck=; [ -x "$VD/bin/e2fsck" ] && sm_fsck="$VD/bin/e2fsck" || sm_fsck=$(command -v e2fsck 2>/dev/null || true)
        if [ -n "$sm_fsck" ]; then
            mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null
            "$sm_fsck" -p "$sm_bdev" >/mnt/base-us/veradroid/logs/fsck.log 2>&1; rc=$?
            if [ $rc -ge 4 ]; then
                echo "-- e2fsck -p rc=$rc, retry with -y" >>/mnt/base-us/veradroid/logs/fsck.log
                "$sm_fsck" -y "$sm_bdev" >>/mnt/base-us/veradroid/logs/fsck.log 2>&1; rc=$?
            fi
            echo "-- $(date) e2fsck ($sm_bdev) rc=$rc" >>/mnt/base-us/veradroid/logs/fsck.log
        fi
    elif command -v e2fsck >/dev/null 2>&1; then
        mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null
        e2fsck -p "$SYSTEM_IMG" >/mnt/base-us/veradroid/logs/fsck.log 2>&1; rc=$?
        if [ $rc -ge 4 ]; then
            echo "-- e2fsck -p rc=$rc, retry with -y" >>/mnt/base-us/veradroid/logs/fsck.log
            e2fsck -y "$SYSTEM_IMG" >>/mnt/base-us/veradroid/logs/fsck.log 2>&1; rc=$?
        fi
        echo "-- $(date) e2fsck rc=$rc" >>/mnt/base-us/veradroid/logs/fsck.log
    fi
    loop_mount "$SYSTEM_IMG" "$SYS" "$sm_bdev" || return 1
}
storage_umount() {
    # Host-ns leftovers under $SYS (notably after_boot binder_repair bind on
    # $R/dev/binder, or binderfs) keep umount(SYS) busy → Kindle cannot drop
    # userstore (/mnt/us) at shutdown (mntus EBUSY/32) and loop0 needs journal
    # recovery next boot. Drop nested mounts deepest-first before SYS itself.
    awk -v d="$SYS" 'index($2, d "/") == 1 {print length($2), $2}' /proc/mounts | sort -rn | while read _ m; do
        umount -l "$m" 2>/dev/null || true
    done
    # Also exact $SYS may already be gone after lazy nested drops; re-read.
    dev=$(awk -v m="$SYS" '$2==m {print $1}' /proc/mounts | tail -1)
    [ -n "$dev" ] || {
        # Detach any loop still tied to the system image even if mount gone
        for l in $(img_loops); do loop_detach "$(loop_dev "$l")"; done
        return 0
    }
    # 刚杀掉的容器进程、延迟卸载（umount -l）的挂载要过一会儿才真正放手：立刻 umount 会报 busy，
    # 系统盘就一直挂着，下次启动前的 e2fsck 也跳过了（KT5、PW3 模拟器都实测到）。最多等 5 秒。
    i=0
    while ! umount "$SYS" 2>/dev/null; do
        # Re-drop anything that reappeared / still nested
        awk -v d="$SYS" 'index($2, d "/") == 1 {print length($2), $2}' /proc/mounts | sort -rn | while read _ m; do
            umount -l "$m" 2>/dev/null || true
        done
        i=$((i + 1)); [ $i -ge 25 ] && { t "storage: $SYS still busy, left mounted"; break; }
        usleep 200000
    done
    case "$dev" in /dev/vdblk*) loop_detach "$dev" ;; /dev/loop*) losetup -d "$dev" 2>/dev/null || true ;; esac
}
W=/dev/shm/veradroid                       # 运行时状态（tmpfs，重启即消失）
R=$SYS/rootfs                              # 系统根目录
X=$W/kfb                                   # 宿主和系统之间的控制通道，容器里是 /dev/kfb
FZ=$W/freezer/vd                           # freezer cgroup
LOG=$SYS/veradroid.log
FB=/sys/class/graphics/fb0
# vfat /mnt/base-us is mounted noexec on some models; the host binaries live there.
# 在挑 fbink 之前做（noexec 时 -x 检查必然失败）。注意 KT5 模拟器上整包和越狱自带的 fbink 跑 -e 都会段错误，
# 那里退回“无 fbink”是 fbink 自己的问题，显示桥里编进去的 FBInk 库不受影响。
case "$(stat -f -c %T /mnt/base-us 2>/dev/null || true)" in
    vfat|msdos|exfat) mount -o remount,exec /mnt/base-us 2>/dev/null || true ;;
esac
# FBInk：优先用自己带的，否则借 KOReader / 越狱包里的。每个候选都真的跑一次 `-e`（打印屏幕几何）：
# 整包里的是 armhf 动态链接的，老固件（5.16 及以前的 armel 系统）上没有它的动态链接器；PW5 的面板上它还段错误过。
# 跑不起来就换下一个；都不行时显示桥有自己的退路。
FBINK=
fbink_probe() {
    if [ -n "$FBINK" ] && [ -x "$FBINK" ]; then
        "$FBINK" -e >/dev/null 2>&1 && return 0
        FBINK=
    fi
    for f in "$VD/bin/fbink" /mnt/us/koreader/fbink /mnt/us/libkh/bin/fbink; do
        [ -x "$f" ] || continue
        if "$f" -e >/dev/null 2>&1; then
            FBINK=$f
            return 0
        fi
    done
    return 1
}
fbink_probe || true
KFONT=
for f in /usr/java/lib/fonts/STHeitiMedium.ttf "$VD/fonts/DroidSansFallback.ttf" /system/fonts/DroidSansFallback.ttf; do
    [ -f "$f" ] && KFONT=$f && break
done
[ -n "$KFONT" ] || KFONT=/usr/java/lib/fonts/STHeitiMedium.ttf
# 方块风格的进度画面（开机画面底部的进度框、回到 Kindle 时的等待画面；安装器里内嵌同一份）
[ -f "$VD/boot/lib/progress.sh" ] && . "$VD/boot/lib/progress.sh"

t() { echo "[$(date +%H:%M:%S) up=$(cut -d' ' -f1 /proc/uptime)] $*"; }
# 计时点（tools/bench/vdbench.sh 跑的时候才有 /dev/shm/vdperf；平时只是一次 [ -f ]，什么都不写）
perf_mark() { [ -f /dev/shm/vdperf ] && t "perf: $*"; return 0; }
# Wi-Fi 开关只动 Wi-Fi。以前用的 com.lab126.cmd wirelessEnable 是 Kindle 的“飞行模式”：关它连蓝牙也关了，开它又把蓝牙打开
# （KPW6 用户报告）。com.lab126.wifid enable 写得进去但不起作用（一台测试机 5.19.5：仍然连着）。起作用的是 cmd 的哈希属性
# disableInterfaces（{ wan = (0), wifi = (1) } = 关掉 Wi-Fi 接口；输入格式里整数要带括号）：测试机实测 Wi-Fi 断开（wifid enable 跟着变 0、
# wlan0 不在了），飞行模式和蓝牙不变，写回 (0) 2 秒内重新连上。没有这个属性的固件退回 wirelessEnable。飞行模式开着时 Wi-Fi 算关。
wifi_dis() { lipc-hash-prop com.lab126.cmd disableInterfaces </dev/null 2>/dev/null | sed -n "s/^[[:space:]]*$1 = (\([0-9]*\)).*/\1/p" | head -1; }
wifi_state() {
    wa=$(lipc-get-prop com.lab126.cmd wirelessEnable 2>/dev/null); wd=$(wifi_dis wifi)
    if [ -n "$wd" ]; then [ "$wd" = 0 ] && ww=1 || ww=0; else ww=${wa:-1}; fi
    [ "$wa" = 0 ] && ww=0
    echo "$ww"
}
wifi_set() { # 0|1
    wd=$(wifi_dis wifi)
    if [ -n "$wd" ]; then
        wn=$(wifi_dis wan); [ "$1" = 1 ] && wv=0 || wv=1
        echo "{ wan = (${wn:-0}), wifi = ($wv) }" | lipc-hash-prop com.lab126.cmd disableInterfaces >/dev/null 2>&1
        # 用户明确要开 Wi-Fi 而飞行模式开着：只有关掉飞行模式 Wi-Fi 才会真的开（蓝牙按 Kindle 自己的规则恢复）
        [ "$1" = 1 ] && [ "$(lipc-get-prop com.lab126.cmd wirelessEnable 2>/dev/null)" = 0 ] && lipc-set-prop -i com.lab126.cmd wirelessEnable 1
    else lipc-set-prop -i com.lab126.cmd wirelessEnable "$1"; fi
}
alive() { [ -f "$1" ] || return 1; _ap=$(cat "$1" 2>/dev/null); [ -n "$_ap" ] && kill -0 "$_ap" 2>/dev/null; }
# 给显示桥发命令。显示桥不在了就不写：往没有读端的 FIFO 里 echo 会永远卡在 open()，
# 命令监听、看门狗、唤醒流程以前都可能这样被拖死（显示桥崩过：tmpfs 写满时 SIGBUS）。
# 给会卡住的命令加时限：timeout <秒> 命令...。busybox 1.30 以前的写法是 timeout -t <秒>；两种都不认就不限时。
vd_timeout() {
    vt=$1; shift
    if [ -z "$VD_TMO" ]; then
        if timeout 1 true 2>/dev/null; then VD_TMO=new; elif timeout -t 1 true 2>/dev/null; then VD_TMO=old; else VD_TMO=none; fi
    fi
    case "$VD_TMO" in new) timeout "$vt" "$@" ;; old) timeout -t "$vt" "$@" ;; *) "$@" ;; esac
}
# 显示桥在检查和写之间退出时，打开 FIFO 写会一直等一个读者：限 3 秒，不让调用者（sleep_watch 醒来时的 REDRAW、解冻、命令监听）卡死
epd_send() { [ -p $X/epd.fifo ] && alive $W/epd.pid || return 1; vd_timeout 3 sh -c 'echo "$1" > "$2"' sh "$*" $X/epd.fifo; }
# 在 Android 里执行命令的前缀：平时用 nsenter 进容器的命名空间。3.0 内核（$W/mode=chroot）没有 /proc/PID/ns，
# nsenter 用不了：chroot 到 /proc/<容器 init>/root——这个特殊链接直接落在容器（另一个挂载命名空间）的根上，
# 容器里的 /dev、/proc、/data 都看得到。用法：$(vd_in) /system/bin/sh -c '...'（也能放在 timeout 后面）。
# 重要：after_boot 是后台子 shell，可能比容器活得更久。shell 里的 $P 会过期；必须优先用仍存活的 $W/pid，
# 否则 nsenter 报 can't open '/proc/<dead>/root'，after_boot: install FAILED（用户日志 73301533302 等）。
vd_in() {
    p=$(cat $W/pid 2>/dev/null)
    if ! { [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }; then
        p=$P
        if ! { [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }; then
            return 1
        fi
    fi
    P=$p
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then echo "chroot /proc/$p/root"
    else echo "nsenter -t $p -m -u -i -n -p -r -w"; fi
}
# 屏幕中间提示一行（启动 / 返回 / 错误），只在界面已暂停时用
[ -f "$VD/boot/lib/storage.sh" ] && . "$VD/boot/lib/storage.sh"     # 系统盘大小调整 / 断电后的收尾
[ -f "$VD/boot/lib/lock.sh" ] && . "$VD/boot/lib/lock.sh"           # 锁屏密码：屏保后上锁、USB 重置文件
[ -f "$VD/boot/lib/usb.sh" ] && . "$VD/boot/lib/usb.sh"             # 插 USB 线时问要不要切换到 USB 传输（cmd usbswitch）
[ -f "$VD/boot/lib/saver.sh" ] && . "$VD/boot/lib/saver.sh"         # 屏保挑选（桌面写的选择文件）
case "$0" in */veradroid.sh) ST_START=1 ;; esac                    # 只有真正启动 Android 时才做“下次启动时扩容”
splash() { [ -x "$FBINK" ] && "$FBINK" -q -c -m -M -t regular=$KFONT,px=${2:-44} "$1" 2>/dev/null; }
# 没能启动 / 不启动时告诉用户为什么。书库点“启动”之后、Kindle 界面停下来之前出的错，屏幕上是书库和启动器的“正在打开”：
# 以前用 splash 写一行，5 秒后脚本退出、书库重画就盖掉了（pw5-5192 模拟器上一帧都没截到）；中文交给 eips 根本画不出来。
# 用户看到的就是“点了没反应”。现在：整屏写原因（中文 + 英文）和日志位置，每 5 秒重画、停留约 15 秒（启动器还开着，书库不重画），
# 再把原因记进系统盘外面的 veradroid/logs/start.log（系统盘没挂上时 veradroid.log 写进挂载点下面的空目录，挂上后就看不到了）。
VD_START_LOG=/mnt/base-us/veradroid/logs/start.log
# 启动过程的关键一步记一行到 start.log（系统盘外面，USB 里 veradroid/logs/start.log 能看到；PW5 等机型的 veradroid.log 在系统盘镜像里，电脑上看不到）
vd_slog() {
    mkdir -p "${VD_START_LOG%/*}" 2>/dev/null
    [ "$(wc -c < "$VD_START_LOG" 2>/dev/null || echo 0)" -gt 65536 ] && { tail -c 32768 "$VD_START_LOG" > "$VD_START_LOG.tmp" 2>/dev/null && mv -f "$VD_START_LOG.tmp" "$VD_START_LOG"; }
    echo "[$(date '+%Y-%m-%d %H:%M:%S') up=$(cut -d' ' -f1 /proc/uptime)] $*" >> "$VD_START_LOG" 2>/dev/null
    return 0
}
vd_notice() { # 中文 英文 [秒数，默认 15]
    t "notice: $2"
    vd_slog "NOT STARTED: $2 | $1"
    vn_s=${3:-15}; vn_i=0
    while :; do
        if ! { [ -x "$FBINK" ] && "$FBINK" -q -c -m -M -t regular=$KFONT,px=36 "$1

$2

日志 log: veradroid/logs/start.log" 2>/dev/null; }; then
            eips -c >/dev/null 2>&1; eips 1 3 "VeraDroid: $2" >/dev/null 2>&1; eips 1 6 "log: veradroid/logs/start.log" >/dev/null 2>&1
        fi
        [ $vn_i -ge $vn_s ] && break
        sleep 5; vn_i=$((vn_i + 5))
    done
    return 0
}
# 启动 / 解冻锁的持有者还活着：pid 在，而且真是 veradroid.sh。锁目录在 tmpfs 里一直留到重启：持有者被杀掉以后，
# 同一个 pid 号以后可能分给别的进程，只看 kill -0 会一直当成“正在启动”，每次点都被忽略。
vd_lock_alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null && grep -q "boot/veradroid.sh" /proc/$1/cmdline 2>/dev/null; }
storage_mount

# 传书网页 / 商店 / 在线更新放安装包的暂存目录（Android 里是 /data/media/0/.upload，VeraHome 以普通应用 uid 写）。
# 只在开机完成后 chmod 一次不够（用户报告：KT6 5.17 上传和链接下载都报 Permission denied）：开机超过等待时间时那一步根本没跑；
# 目录还可能是旧版本 / 别的 uid 留下的，或者被换成了别的东西。容器启动前、开机完成后、每次打开传书和每次安装前都整理一遍。
upload_dir_fix() {
    ud=$SYS/data/media/0/.upload
    [ -d $SYS/data ] || return 0
    if [ -L "$ud" ] || { [ -e "$ud" ] && [ ! -d "$ud" ]; }; then rm -f "$ud"; fi
    mkdir -p "$ud" 2>/dev/null
    chmod 777 $SYS/data/media $SYS/data/media/0 "$ud" 2>/dev/null
    [ -d "$ud" ] && [ "$(stat -c %a "$ud" 2>/dev/null)" = 777 ]
}

# rm -rf 之前的防护：$1 下面（self=1 时包括 $1 本身）有任何挂载就返回 0。
# chroot 模式（5.17 的 4.9 内核、3.0 内核）的 bind 挂载直接落在宿主上（$R/data/media/0/KindleLibrary -> Kindle 的
# documents），stop 没卸干净时 rm -rf 会穿过去删掉 Kindle 的书。删之前一律先查。
# 2026-11-13 事故：容器自己的挂载命名空间里的挂载（Kindle 的 documents 就绑在那里）在宿主的 /proc/mounts 里看不到。
# 所以还要看容器 init 的挂载表，把容器里的路径换算回宿主路径（容器的 / = $R，/data = $SYS/data）再比。
mounts_under() { # dir [self]
    awk -v d="${1%/}" -v self="${2:-0}" '($2 == d && self == 1) || index($2, d "/") == 1 { f = 1 } END { exit !f }' /proc/mounts && return 0
    mounts_under_ns "$1" "${2:-0}"
}
mounts_under_ns() { # dir [self]：只查容器命名空间里的挂载
    [ "$(cat $W/mode 2>/dev/null)" = chroot ] && return 1      # chroot 模式的挂载本来就在宿主表里
    for mu_p in $(cat $W/pid 2>/dev/null); do
        [ -r /proc/$mu_p/mounts ] || continue
        [ "$(readlink /proc/$mu_p/ns/mnt 2>/dev/null)" = "$(readlink /proc/self/ns/mnt 2>/dev/null)" ] && continue
        awk -v d="${1%/}" -v self="${2:-0}" -v r="$R" -v sd="$SYS/data" '
            function hit(h) { return (h == d && self == 1) || index(h, d "/") == 1 }
            { m = $2; if (m == "/") m = ""
              if (hit(r m)) f = 1
              if (m == "/data" || index(m, "/data/") == 1) { if (hit(sd substr(m, 6))) f = 1 } }
            END { exit !f }' /proc/$mu_p/mounts && return 0
    done
    return 1
}

# ---- Kindle 书库（documents）在 Android 里的位置 ----
# documents 直接绑在容器的 /data/media/0/KindleLibrary（真正的挂载点，和 0.88 一样）：应用经 /sdcard 访问时走 vold 的 FUSE
# （sdcard 守护进程），FUSE 不跟符号链接，0.89 候选版的“符号链接指到 /kindle_library”在所有应用里都是
# "Function not implemented"（KOReader / Moon+ 从 /sdcard 选书全挂）。
# 2026-11-13 事故（installd destroy_user_data 递归删 /data/media/0 穿过这个 bind 删光 Kindle 的书）的防护：
#  1. 系统镜像里：installd 递归删除不进入挂载点；PackageManager 准备用户存储失败时不删用户数据；vold 没有 fstab 不 trim；
#  2. 开机期间（PackageManager 准备用户存储的时候）书库只读绑进去，开机完成后 after_boot 的 klib_rw 再叠一层可写的 bind；
#  3. 开机前记下书库清单，开机完成后核对（docs_verify）。
# 可写的那份在容器根下的 $KLIB_MP（不在 /data 下面，installd 删不到；也不在 /storage、/mnt 下面，vold 重启时不会被卸）。
KLIB=/data/media/0/KindleLibrary
KLIB_MP=/kindle_library
# fscrypt v1 的用户分区（KT6 / Scribe 5.17+，DL_V1_POLICY=true，eink-emulator kt6-5202 实测）：密钥只在 root / framework 的
# keyring 里，Android 自己的进程（sdcard FUSE 守护进程 uid 1023、各个应用）打开书得到 "Required key not available" /
# Permission denied —— 应用从 /sdcard/KindleLibrary 打开书失败（线上 0.88 / 0.96 也一样，绑的是同一个源）。Kindle 自己通过
# /mnt/us（fuse.fsp，替所有人解密）访问。所以容器里另外准备一份 /mnt/us/documents 的 bind（$KLIB_FSP），开机完成后 klib_rw
# 在 v1 机器上把它叠成可写层（开机期间的只读层仍然是原来的源，只给 PackageManager 看）。其他机器照旧用 $KLIB_MP。
KLIB_FSP=/kindle_library_fsp
klib_fsp_src() { # Kindle 的解密视图（fuse.fsp）在不在：在就打印它的 documents
    awk '$2 == "/mnt/us" && $3 == "fuse.fsp" { f = 1 } END { exit !f }' /proc/mounts && [ -d /mnt/us/documents ] && echo /mnt/us/documents
}
klib_dir() { # $1 = data 目录（宿主 $SYS/data，或容器里的 $R/data）。KindleLibrary 是空目录（可以当挂载点）返回 0
    kl_m=$1/media/0; kl=$kl_m/KindleLibrary
    mkdir -p "$kl_m" 2>/dev/null || return 1
    if [ -L "$kl" ]; then
        rm -f "$kl"                                  # 0.89 候选版留下的符号链接（只删链接本身）
    elif [ -d "$kl" ]; then
        # 下面还挂着东西（上次没停干净）就什么都不动
        if mounts_under "$kl" 1; then t "klib: $kl is still a mount point, not binding again"; return 1; fi
        if [ -n "$(ls -A "$kl" 2>/dev/null)" ]; then
            # 不是空的：没挂 documents 时有人往里写过东西。原样挪开，不删（绑上去会把它们盖住）
            kl_s=$kl_m/KindleLibrary.stray-$(date +%Y%m%d-%H%M%S)
            mv "$kl" "$kl_s" || return 1
            t "klib: old non-empty KindleLibrary directory kept as ${kl_s##*/}"
        fi
    elif [ -e "$kl" ]; then
        mv "$kl" "$kl.stray-$(date +%Y%m%d-%H%M%S)" || return 1
    fi
    mkdir -p "$kl" 2>/dev/null; chmod 777 "$kl" 2>/dev/null
    [ -d "$kl" ] && [ ! -L "$kl" ]
}

# ---- documents 自检（2026-11-13 事故后加）：开机前记下 Kindle 书库里的文件，开机完成后核对 ----
DOCS_DIR=/mnt/base-us/documents
# 列出 documents 下的普通文件（相对 /mnt/us 的路径，和安装前备份 tgz 里的写法一致）。不算安装器脚本和临时文件
docs_list() { # [dir]
    ( cd "${1:-$DOCS_DIR}/.." 2>/dev/null && find documents -type f 2>/dev/null ) | grep -v \
        -e '^documents/VeraDroid\.sh' -e '^documents/VeraDroid-Experimental\.sh' -e '^documents/VeraDroid-Offline\.sh' \
        -e '^documents/bootstrap\.sh' -e '^documents/veradroid-.*\.tar\.gz' -e '\.partial$' -e '\.tmp$' \
        -e '^documents/VeraDroid\.sdr/' -e '^documents/VeraDroid-Experimental\.sdr/' -e '^documents/VeraDroid-Offline\.sdr/' -e '^documents/bootstrap\.sdr/' -e '^documents/VeraDroid-Uninstall\.s' | sort
}
docs_snapshot() { docs_list > $W/docs.before 2>/dev/null; }
# $1 = 清单文件（docs_list 的格式）。全部还在返回 0；少了就大声记下来，能从安装前备份恢复的恢复（tar -k：绝不覆盖现有文件）
docs_verify() { # list [why]
    [ -s "$1" ] || return 0
    dv_m=$W/docs.missing; : > $dv_m
    while IFS= read -r f; do [ -e "$DOCS_DIR/../$f" ] || [ -L "$DOCS_DIR/../$f" ] || printf '%s\n' "$f" >> $dv_m; done < "$1"
    [ -s $dv_m ] || return 0
    dv_n=$(wc -l < $dv_m | tr -d ' '); dv_all=$(wc -l < "$1" | tr -d ' ')
    dv_msg="!!! DOCUMENTS LOSS: $dv_n of $dv_all files in the Kindle library are gone (${2:-check})"
    t "$dv_msg"; echo "veradroid: $dv_msg" > /dev/kmsg 2>/dev/null
    { echo "[$(date '+%F %T')] $dv_msg"; head -50 $dv_m; } >> $VD/DOCUMENTS-LOSS.log 2>/dev/null
    dv_r=0
    for dv_b in $(ls -t /mnt/base-us/veradroid/backup/user-backup-*.tgz 2>/dev/null) $(ls -t /mnt/base-us/veradroid-user-backup-*.tgz 2>/dev/null); do   # root-legacy: 根目录里老版本的 / 用户改过名的备份也用来补
        tar -xzkf "$dv_b" -C "$DOCS_DIR/.." -T $dv_m 2>/dev/null
        dv_left=0; while IFS= read -r f; do [ -e "$DOCS_DIR/../$f" ] || dv_left=$((dv_left + 1)); done < $dv_m
        t "!!! documents: restored $((dv_n - dv_left)) of $dv_n from ${dv_b##*/}"; echo "[$(date '+%F %T')] restored $((dv_n - dv_left)) of $dv_n from $dv_b" >> $VD/DOCUMENTS-LOSS.log 2>/dev/null
        dv_r=1; [ $dv_left -eq 0 ] && break
    done
    if [ $dv_r != 1 ]; then
        dv_bm=$(sed -n 's/^result=//p' /mnt/base-us/veradroid/state/backup.last 2>/dev/null | tail -1)
        t "!!! documents: no pre-install backup to restore from${dv_bm:+ (last pre-install backup: $dv_bm)}"
        # 更新前备份关着（设置 → 设备与更新 → 更新前备份）/ 空间不够没做：清单照样核对，但没有东西可以补
        echo "[$(date '+%F %T')] 没有安装前备份，少了的文件补不回来${dv_bm:+（这次安装的备份：$dv_bm；off = 更新前备份已关闭）}" >> $VD/DOCUMENTS-LOSS.log 2>/dev/null
    fi
    sync
    return 1
}

# 回到 Kindle 以后的自检（stop.sh 结尾、冻结返回之后各跑一次）。用户报告（KT6，固件 5.17）：退出 VeraDroid 后 USB 连电脑
# 只看到一个打不开的灰色盘，重启 Kindle 才好。凡是会让 Kindle 的 USB / 用户存储不正常的残留都在这里查，
# 能当场修的修掉，修不掉的大声记下来：写进 $VD/last-stop.log（USB 里的 veradroid/last-stop.log，系统盘卸了也看得到）和内核日志。
#   $1 = stop（完全退出：不应该再有任何挂载 / loop / 占用）| freeze（冻结返回：系统盘按设计还挂着，只查 Kindle 自己的进程）
userstore_check() {
    uc_mode=${1:-stop}; uc_bad=; uc_fixed=
    # 1. volumd（管 USB 连接和用户存储）/ awesome 还停在 SIGSTOP：补发 SIGCONT
    for uc_n in volumd awesome; do
        for uc_p in $(pidof $uc_n 2>/dev/null); do
            [ "$(awk '/^State:/ {print $2}' /proc/$uc_p/status 2>/dev/null)" = T ] || continue
            kill -CONT $uc_p 2>/dev/null; usleep 200000 2>/dev/null
            if [ "$(awk '/^State:/ {print $2}' /proc/$uc_p/status 2>/dev/null)" = T ]; then uc_bad="$uc_bad $uc_n($uc_p)=still-stopped"
            else uc_fixed="$uc_fixed $uc_n($uc_p)=was-stopped"; fi
        done
    done
    # 2. 我们停过的 MTP 服务（新固件的 USB 传输）没有回来：再起一次
    if [ -f /etc/upstart/mtp.conf ] && initctl status mtp 2>/dev/null | grep -q 'stop/waiting'; then
        rm -f /var/run/upstart/mtp.restarts /var/local/upstart/mtp.restarts 2>/dev/null
        start mtp >/dev/null 2>&1
        if initctl status mtp 2>/dev/null | grep -q 'start/running'; then uc_fixed="$uc_fixed mtp=was-not-running"; else uc_bad="$uc_bad mtp=not-running"; fi
    fi
    if [ "$uc_mode" = stop ]; then
        # 3. 系统盘 / 容器的挂载、loop 设备还在：USB 大容量存储模式的机型上 Kindle 卸不掉用户存储，电脑上就是一个打不开的盘
        uc_m=$(awk -v d="$SYS" '$2 == d || index($2, d "/") == 1 { n++ } END { print n + 0 }' /proc/mounts)
        [ "$uc_m" -gt 0 ] && uc_bad="$uc_bad mounts-under-system-image=$uc_m($(awk -v d="$SYS" '$2 == d || index($2, d "/") == 1 { printf "%s,", $2 }' /proc/mounts | cut -c1-300))"
        uc_l=$(img_loops | tr '\n' ' '); [ -n "$uc_l" ] && uc_bad="$uc_bad loop-still-attached=$uc_l"
        # 4. 谁还开着系统盘 / 程序目录里的文件（只在上面发现残留时才扫：要看每个进程的 fd，慢）
        if [ -n "$uc_bad" ]; then
            for uc_d in /proc/[0-9]*; do
                uc_p=${uc_d#/proc/}; [ "$uc_p" = "$$" ] && continue
                uc_h=$(ls -l $uc_d/cwd $uc_d/root $uc_d/fd 2>/dev/null | grep -c -- "-> \($SYS\|$VD\)\(/\|\$\)")
                [ "${uc_h:-0}" -gt 0 ] && uc_bad="$uc_bad holder=$uc_p:$(tr '\000' ' ' < $uc_d/cmdline 2>/dev/null | cut -c1-70)"
            done
        fi
    fi
    uc_line="[$(date '+%F %T') up=$(cut -d' ' -f1 /proc/uptime)] after $uc_mode:"
    if [ -n "$uc_bad" ]; then
        uc_line="$uc_line USER STORE / USB NOT CLEAN:$uc_bad${uc_fixed:+ | repaired:$uc_fixed}"
        echo "veradroid: $uc_line" > /dev/kmsg 2>/dev/null
    elif [ -n "$uc_fixed" ]; then uc_line="$uc_line repaired:$uc_fixed"
    elif [ "$uc_mode" = freeze ]; then uc_line="$uc_line kindle processes running (android frozen, system image stays mounted)"
    else uc_line="$uc_line clean (volumd running, nothing of VeraDroid left on the user store)"; fi
    t "$uc_line"
    if [ -d "$VD" ]; then
        { tail -n 60 "$VD/last-stop.log" 2>/dev/null; echo "$uc_line"; } > "$VD/last-stop.log.tmp" 2>/dev/null && mv -f "$VD/last-stop.log.tmp" "$VD/last-stop.log" 2>/dev/null
    fi
    [ -z "$uc_bad" ]
}
