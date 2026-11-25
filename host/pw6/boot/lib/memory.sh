# SPDX-License-Identifier: GPL-3.0-or-later
# 内存：Kindle 没有 swap。运行期间用内核自带的 zram 做压缩 swap，停止时恢复 swappiness，zram 本身不撤
# （swapoff 要把换出去的页读回来，会拖很久）。zram 的大小只能在它空闲时改。
# 小内存机型（PW5/KT5/KT6，512 MB）：lab126 4.9 内核的启发式 overcommit 拒绝“大于当前可用内存”的单块私有可写映射，
# ART 的 256 MB 堆（memfd + MAP_PRIVATE）和 zygote fork 都会被拒 → zygote 无限崩溃。运行期间改为 1，停止时恢复。
# 957 MB 的 PW6（5.15 内核）不受影响，不改。
overcommit_on() {
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    [ -n "$mt" ] && [ "$mt" -lt 700000 ] || return 0
    [ -f $W/overcommit.state ] || cat /proc/sys/vm/overcommit_memory > $W/overcommit.state
    echo 1 > /proc/sys/vm/overcommit_memory && t "low ram ${mt}kB: overcommit_memory=1"
}
overcommit_restore() {
    [ -f $W/overcommit.state ] || return 0
    cat $W/overcommit.state > /proc/sys/vm/overcommit_memory
    rm -f $W/overcommit.state
}

# Kernel LMK (drivers/staging/android/lowmemorykiller.c) is what actually kills
# processes on lab126 4.9. The log "cache X is below limit Y for oom_adj Z"
# comes from the kernel module, NOT lmkd. vdlmk only talks to the lmkd socket;
# ActivityManager often logs "Error writing to lowmemorykiller socket", and the
# stock large-screen table stays (adj0=72MB, adj100=92MB, adj200=108MB).
# User 092A032524017 (Kindle 2022 / KT5, 485 MB): 8× foreground WeRead kills.
# 停止 / 返回 Kindle 时要把 Kindle 原来的那一套改回去（lmk_kernel_restore，zram_restore 调用）：以前只改不回，Android 停了以后
# Kindle 自己一直带着 8 MB 的门槛跑到下次重启——内存紧时内核 LMK 不再提前杀后台进程，Kindle 的界面 / 书库程序被拖进
# 反复回收、卡死，严重时看门狗整机重启（2026-11-21 社区“用完微信读书 Kindle 无限重启”排查时发现；不是重启循环的直接原因）。
LMK_SYS=${LMK_SYS:-/sys/module/lowmemorykiller/parameters}
lmk_kernel_tune() {
    mf=$LMK_SYS/minfree
    [ -f "$mf" ] || return 0
    mt=
    if [ -z "$LMK_MEMINFO" ] && [ -n "$_LMK_MEMTOTAL" ]; then
        mt=$_LMK_MEMTOTAL
    elif [ -r "${LMK_MEMINFO:-/proc/meminfo}" ]; then
        while read -r lk_k lk_v _; do
            [ "$lk_k" = "MemTotal:" ] && { mt=$lk_v; break; }
        done < "${LMK_MEMINFO:-/proc/meminfo}"
        [ -z "$LMK_MEMINFO" ] && _LMK_MEMTOTAL=$mt
    fi
    [ -n "$mt" ] && [ "$mt" -lt 700000 ] 2>/dev/null || return 0
    ad=$LMK_SYS/adj
    if [ -f $W/lmk.state ]; then
        lk_cur=; read -r lk_cur < "$mf" 2>/dev/null || true
        [ "$lk_cur" = "2048,3072,4096,8192,14336,16384" ] && return 0
    fi
    # 第一次改之前记下 Kindle 原来的值（看门狗每一轮都会再调一次：已经记过就不再记，免得把我们自己的值当成原值）
    lk_new=; [ -f $W/lmk.state ] || { { cat "$mf"; [ -f "$ad" ] && cat "$ad"; } > $W/lmk.state.tmp 2>/dev/null && mv -f $W/lmk.state.tmp $W/lmk.state && lk_new=1; }
    echo 2048,3072,4096,8192,14336,16384 > "$mf" 2>/dev/null || return 1
    [ -f "$ad" ] && echo 0,100,200,300,900,906 > "$ad" 2>/dev/null || true
    [ -n "$lk_new" ] && t "lmk: kernel minfree 8/12/16/32/56/64MB (stock $(head -n 1 $W/lmk.state) saved, restored on stop)"
    return 0
}
lmk_kernel_restore() {
    [ -f $W/lmk.state ] || return 0
    { read lk_mf; read lk_ad; } < $W/lmk.state
    # 先 adj 后 minfree（内核按下标配对；两组数量一样，顺序无所谓，只要两个都写回）
    [ -n "$lk_ad" ] && [ -f $LMK_SYS/adj ] && echo "$lk_ad" > $LMK_SYS/adj 2>/dev/null
    [ -n "$lk_mf" ] && echo "$lk_mf" > $LMK_SYS/minfree 2>/dev/null && t "lmk: kernel minfree restored to the Kindle's $lk_mf"
    rm -f $W/lmk.state
    return 0
}

# heap + low_ram props. Must run before the container starts (build.prop is read by zygote).
# Do NOT raise 512 MB devices to 256m/512m (that was a PW6-oriented fix and causes LMK kills).
heap_fix() {
    f=$R/system/build.prop
    [ -f "$f" ] || return 0
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    _prop() {
        k=$1; v=$2
        if grep -q "^$k=" "$f"; then
            sed -i "s|^$k=.*|$k=$v|" "$f"
        else
            echo "$k=$v" >> "$f"
        fi
    }
    if [ -n "$mt" ] && [ "$mt" -lt 700000 ]; then
        _prop dalvik.vm.heapstartsize 8m
        _prop dalvik.vm.heapgrowthlimit 128m
        _prop dalvik.vm.heapsize 192m
        _prop dalvik.vm.heapminfree 512k
        _prop dalvik.vm.heapmaxfree 2m
        _prop dalvik.vm.heaptargetutilization 0.75
        _prop ro.config.low_ram true
        _prop ro.config.max_starting_bg 2
        _prop ro.sys.fw.bg_apps_limit 2
        # 换了系统镜像后第一次开机 PackageManager 给所有应用重新 dexopt（pm.dexopt.first-boot，镜像里是 interpret-only）：装了番茄这类
        # 200 MB 以上 dex 的应用时要几百 MB，开机卡死。改成 verify-at-runtime，之后 compile.sh 空闲时再编（compile_low_big）。
        # （models/ram-512.conf 的 #@prop 不行：安装器不拷 models/，那一节从来没生效过）
        [ "${COMPILE_BIG_LOW:-on}" = off ] || _prop pm.dexopt.first-boot verify-at-runtime
        t "low ram ${mt}kB: heap 8/128/192m + ro.config.low_ram=true"
    else
        if grep -q '^dalvik.vm.heapgrowthlimit=128m$' "$f"; then
            sed -i 's/^dalvik.vm.heapgrowthlimit=128m$/dalvik.vm.heapgrowthlimit=256m/; s/^dalvik.vm.heapsize=256m$/dalvik.vm.heapsize=512m/' "$f" \
                && t "heap limit raised to 256m/512m (was 128m)"
        fi
        heap_va_fix "$f"
    fi
}

# 32 位应用的地址空间（ARMv7 内核 3G/1G：每个进程 3 GB）。ART 给 Java 堆按 heapsize 预留两块一样大的地址空间：主堆和
# “后台整理”（HomogeneousSpaceCompact）用的备用堆。heapsize=512m 时每个应用一开就占 1 GB 地址（测试机上番茄刚到隐私弹窗：
# memfd:ashmem-dalvik-main 1024 MB，VmSize 1.9 GB）。番茄登录 / 书城这类线程多、插件多的应用再加上 WebView、几百个线程栈，
# 地址用完：创建线程失败（“thread constructor failed: Try again”，SIGABRT）、CursorWindow 映射失败（error -12）——
# 用户诊断（KT6 1 GB）里番茄就是这样闪退的，物理内存还有富余（overcommit=1）。
# 后台 GC 改用 CMS、关掉 OOM 时的整理，ART 就不建备用堆：每个应用省 512 MB 地址，堆上限（heapsize）不变。
# 512 MB 机型不改（heapsize 192m，备用堆只占 192 MB；后台整理能让缓存进程少占实际内存，那边更要紧）。
# 设置 ART_HSPACE=1 恢复原样（两行都删掉）。build.prop 在启动前改，下次启动 Android 生效。
heap_va_fix() { # build.prop
    hv_f=$1
    if [ "${ART_HSPACE:-0}" = 1 ]; then
        grep -q -e '^dalvik.vm.backgroundgctype=CMS$' -e '^dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM$' "$hv_f" || return 0
        grep -v -e '^dalvik.vm.backgroundgctype=CMS$' -e '^dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM$' "$hv_f" > "$hv_f.vdtmp" \
            && cat "$hv_f.vdtmp" > "$hv_f" && t "art: background heap compaction back on (ART_HSPACE=1)"
        rm -f "$hv_f.vdtmp"
        return 0
    fi
    # 镜像里已经有别的 extra-opts / 后台 GC：不去覆盖
    grep -q '^dalvik.vm.extra-opts=' "$hv_f" && ! grep -q '^dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM$' "$hv_f" && return 0
    grep -q '^dalvik.vm.backgroundgctype=' "$hv_f" && ! grep -q '^dalvik.vm.backgroundgctype=CMS$' "$hv_f" && return 0
    hv_n=0
    grep -q '^dalvik.vm.backgroundgctype=CMS$' "$hv_f" || { echo "dalvik.vm.backgroundgctype=CMS" >> "$hv_f" && hv_n=1; }
    grep -q '^dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM$' "$hv_f" || { echo "dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM" >> "$hv_f" && hv_n=1; }
    [ $hv_n = 1 ] && t "art: no backup heap space (background GC CMS): 512 MB less address space per app"
    return 0
}

zram_on() {
    overcommit_on
    heap_fix
    lmk_kernel_tune
    Z=/sys/block/zram0
    [ "${ZRAM_MB:-0}" -gt 0 ] && [ -e $Z/disksize ] || return 0
    if ! grep -q '^/dev/zram0 ' /proc/swaps; then
        [ "$(cat $Z/disksize)" != 0 ] && echo 1 > $Z/reset
        [ "$(cat $Z/disksize)" = 0 ] || { t "zram0 busy, skipped"; return 0; }
        echo ${ZRAM_MB}M > $Z/disksize && mkswap /dev/zram0 >/dev/null && swapon -p 100 /dev/zram0 || {
            echo 1 > $Z/reset 2>/dev/null; t "zram failed"; return 0; }
    fi
    [ -f $W/zram.state ] || echo "$(cat /proc/sys/vm/swappiness) $(cat /proc/sys/vm/page-cluster)" > $W/zram.state
    # swappiness：957 MB 的 PW6 用 40（100 会把正在用的应用页也换出去，再用时要从 zram 解压回来）。
    # 512 MB 机型用 100：内存紧的时候 40 让内核去扔文件页（应用的 odex / apk 代码），下一次用到就是一次读盘的主缺页；
    # 换成往 zram 里压匿名页更便宜。KT5 模拟器（stop，3 轮脚本操作）：微信读书主缺页 −25 %，直接回收次数减半；
    # PW5 模拟器冷启动到主界面 81 / 97 / 70 s -> 62 / 65 / 45 s（docs/slim-android.md 第 7 节）。设置里写 VM_SWAPPINESS 可覆盖。
    sw=40; lowmem_ram && sw=100
    echo ${VM_SWAPPINESS:-$sw} > /proc/sys/vm/swappiness
    echo 0 > /proc/sys/vm/page-cluster
    # 实验用：kswapd 提前多少开始回收（watermark_scale_factor，万分之几，内核默认 10）。空 = 不动
    if [ -n "$VM_WATERMARK" ] && [ -w /proc/sys/vm/watermark_scale_factor ]; then
        [ -f $W/wsf.state ] || cat /proc/sys/vm/watermark_scale_factor > $W/wsf.state
        echo "$VM_WATERMARK" > /proc/sys/vm/watermark_scale_factor 2>/dev/null
    fi
    type zram_extra >/dev/null 2>&1 && zram_extra     # zram0 被占着、比 ZRAM_MB 小：再加一块（memkeep.sh）
    t "zram ${ZRAM_MB}MB on"
}
zram_restore() {
    type mem_keep_off >/dev/null 2>&1 && mem_keep_off
    overcommit_restore
    lmk_kernel_restore
    [ -f $W/zram.state ] || return 0
    read sw pc < $W/zram.state
    echo $sw > /proc/sys/vm/swappiness
    [ -n "$pc" ] && echo $pc > /proc/sys/vm/page-cluster
    rm -f $W/zram.state
    [ -f $W/wsf.state ] && cat $W/wsf.state > /proc/sys/vm/watermark_scale_factor 2>/dev/null; rm -f $W/wsf.state
}

# CPU governor (from rel-next-perf): powersave while frozen, restore on thaw. Fail-soft.
cpu_gov_set() {
    want=$1
    [ -n "$want" ] || return 0
    for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        [ -f "$g" ] || continue
        if [ ! -f $W/cpu_gov.state ]; then
            cat "$g" > $W/cpu_gov.state
        fi
        echo "$want" > "$g" 2>/dev/null || true
    done
    [ -f $W/cpu_gov.state ] && t "cpu_gov -> $want (was $(cat $W/cpu_gov.state))"
}
cpu_gov_restore() {
    [ -f $W/cpu_gov.state ] || return 0
    prev=$(cat $W/cpu_gov.state)
    for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        [ -f "$g" ] || continue
        echo "$prev" > "$g" 2>/dev/null || true
    done
    rm -f $W/cpu_gov.state
    t "cpu_gov restored -> $prev"
}

# 存储预读：Kindle 把 eMMC 和 loop 设备的 read_ahead_kb 设成 4（每次缺页只读一页）。Android 的应用是几十到一百多 MB 的
# 预编译文件，靠缺页读进来，4 KB 预读时冷启动很慢。PW6 实测（清掉缓存后）：微信读书冷启动 3.6–4.1 s -> 2.5–2.8 s，
# 到界面可见 8.5–9.2 s -> 5.9–6.3 s；Android 开机 15.8 s -> 12.9 s。128 和 256/512/1024 没有差别，取最小的。
# 只调大、不调小；原值记在内存盘里，stop 时恢复。
readahead_on() {
    [ "${READAHEAD_KB:-128}" -gt 0 ] 2>/dev/null || return 0
    : > $W/readahead.saved
    for b in /sys/block/mmcblk0 /sys/block/loop[0-9]*; do
        f=$b/queue/read_ahead_kb
        [ -w "$f" ] || continue
        case "$b" in */loop*) [ -s "$b/loop/backing_file" ] || continue ;; esac
        old=$(cat "$f" 2>/dev/null); [ -n "$old" ] || continue
        [ "$old" -lt "${READAHEAD_KB:-128}" ] 2>/dev/null || continue
        echo "$b $old" >> $W/readahead.saved
        echo "${READAHEAD_KB:-128}" > "$f" 2>/dev/null
    done
    return 0
}
readahead_restore() {
    [ -f $W/readahead.saved ] || return 0
    while read b old; do [ -w "$b/queue/read_ahead_kb" ] && echo "$old" > "$b/queue/read_ahead_kb" 2>/dev/null; done < $W/readahead.saved
    rm -f $W/readahead.saved
    return 0
}

# 回环设备直读写（LOOP_DIO，默认 1；docs/jank.md、k49 同名函数）。PW6 的 /mnt/base-us（Android 的 rootfs 和 data 都在这里）是
# eMMC 分区 mmcblk0p10 上的 loop0（Kindle 自己的），缓冲方式：Android 读过的每一页在页缓存里存两份（ext4 的文件页 + loop0 下面
# 块设备的页，/proc/meminfo 的 Buffers 有 150–170 MB），loop 内核线程还多拷一遍。应用之间切换时缓存装不下几个应用的 odex，
# 每切一次读盘几百 MB。直读写以后块设备那一份不再进缓存。loop0 是 Kindle 的：记下来，stop 时改回缓冲方式。
loopdio_set() { # loopN 0|1
    f=/sys/block/$1/loop/dio
    [ -r "$f" ] || return 1
    [ "$(cat "$f")" = "$2" ] && return 0
    "$VD/bin/vdloopdio" "$(loop_dev $1)" $2 2>>$LOG
}
loopdio_on() {
    [ "${LOOP_DIO:-1}" = 1 ] && [ -x "$VD/bin/vdloopdio" ] || return 0
    d=$(awk '$2 == "/mnt/base-us" {print $1}' /proc/mounts | tail -1)
    n=$(echo "${d##*/}" | sed 's/^loop//')
    case "$d" in /dev/loop*)
        if [ -n "$n" ] && [ "$(cat /sys/block/loop$n/loop/dio 2>/dev/null)" = 0 ] && loopdio_set loop$n 1; then
            echo loop$n > $W/loopdio.base; t "loop dio: /mnt/base-us (loop$n) direct"
        fi ;;
    esac
    return 0
}
loopdio_restore() {
    [ -f $W/loopdio.base ] || return 0
    l=$(cat $W/loopdio.base); rm -f $W/loopdio.base
    [ -x "$VD/bin/vdloopdio" ] && loopdio_set $l 0 && t "loop dio: /mnt/base-us ($l) back to buffered"
    return 0
}
