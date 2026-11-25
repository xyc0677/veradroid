# SPDX-License-Identifier: GPL-3.0-or-later
# 内存：Kindle 没有 swap。运行期间用内核自带的 zram 做压缩 swap，停止时恢复 swappiness，zram 本身不撤
# （swapoff 要把换出去的页读回来，会拖很久）。zram 的大小只能在它空闲时改。
# 小内存机型（PW5/KT5/KT6，512 MB）：lab126 4.9 内核的启发式 overcommit 拒绝“大于当前可用内存”的单块私有可写映射，
# ART 的 256 MB 堆（memfd + MAP_PRIVATE）和 zygote fork 都会被拒 → zygote 无限崩溃。运行期间改为 1，停止时恢复。
# 957 MB 的 PW6（5.15 内核）不受影响，不改。
overcommit_on() {
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    # 4.x 及更老的内核一律开（同 host/k49 4609bda：Scribe 上 zram 满、shmem 高时启发式 overcommit 拒了 zygote 的 fork，
    # 微信读书开书时起 :seg 进程失败、整个包被 force-stop）。legacy 的 3.0 / 4.1 机型本来都在 700 MB 以下，这里只是把规则写全
    case "$(uname -r)" in [1-4].*) ;; *) [ -n "$mt" ] && [ "$mt" -lt 700000 ] || return 0 ;; esac
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
        set -- $(heap_values); hg=$1; hs=$2
        _prop dalvik.vm.heapstartsize ${HEAP_START:-8m}
        _prop dalvik.vm.heapgrowthlimit $hg
        _prop dalvik.vm.heapsize $hs
        _prop dalvik.vm.heapminfree 512k
        _prop dalvik.vm.heapmaxfree 2m
        _prop dalvik.vm.heaptargetutilization 0.75
        _prop ro.config.low_ram true
        _prop ro.config.max_starting_bg ${MAX_STARTING_BG:-2}
        _prop ro.sys.fw.bg_apps_limit 2
        # 系统镜像是 userdebug：系统应用（VeraHome）主线程的每次读写盘都被 StrictMode 记一次、经 binder 报给 ActivityManager 写 dropbox。
        # 慢机型开机时盘很忙，这些报告把桌面主线程卡住，HostService 启动超时 ANR（pw2-5122 模拟器的 traces：main 停在
        # handleApplicationStrictModeViolation）。关掉 StrictMode 的调试报告。
        _prop persist.sys.strictmode.disable true
        # 换了系统镜像后第一次开机 PackageManager 给所有应用重新 dexopt（pm.dexopt.first-boot，镜像里是 interpret-only）：装了番茄这类
        # 200 MB 以上 dex 的应用时要几百 MB，开机卡死。改成 verify-at-runtime，之后 compile.sh 空闲时再编（compile_low_big）。
        [ "${COMPILE_BIG_LOW:-on}" = off ] || _prop pm.dexopt.first-boot verify-at-runtime
        t "low ram ${mt}kB: heap ${HEAP_START:-8m}/$hg/$hs + ro.config.low_ram=true"
    else
        if grep -q '^dalvik.vm.heapgrowthlimit=128m$' "$f"; then
            sed -i 's/^dalvik.vm.heapgrowthlimit=128m$/dalvik.vm.heapgrowthlimit=256m/; s/^dalvik.vm.heapsize=256m$/dalvik.vm.heapsize=512m/' "$f" \
                && t "heap limit raised to 256m/512m (was 128m)"
        fi
    fi
}

# Java 堆上限（growthlimit / heapsize）：按内存给默认值，机型文件 / settings.conf 的 HEAP_GROWTH / HEAP_SIZE 可以改。
# heap_fix（build.prop）和 after_boot（setprop）都用它，两处必须一致。
heap_values() {
    _hm=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    _hg=128m; _hs=192m
    [ -n "$_hm" ] && [ "$_hm" -lt 400000 ] && { _hg=96m; _hs=160m; }     # 256 MB（PW2）：Android 全部加起来只有约 200 MB 实际内存 + 交换文件
    echo "${HEAP_GROWTH:-$_hg} ${HEAP_SIZE:-$_hs}"
}

zram_grow() {
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    [ -n "$mt" ] && [ "$mt" -lt 700000 ] || return 0
    Z=/sys/block/zram0
    grep -q '^/dev/zram0 ' /proc/swaps || return 0
    want=$((256 * 1024 * 1024))
    [ "$(cat $Z/disksize 2>/dev/null)" -lt $want ] 2>/dev/null || return 0
    used=$(awk '$1 == "/dev/zram0" {print $4}' /proc/swaps)               # KB
    avail=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
    [ -n "$used" ] && [ -n "$avail" ] && [ "$avail" -gt $((used + 80000)) ] || { t "zram: not enough free memory to resize (used ${used}kB, avail ${avail}kB)"; return 0; }
    prio=$(awk '$1 == "/dev/zram0" {print $5}' /proc/swaps)
    case "$prio" in ""|-*) prio=100 ;; esac      # 内核自动分配的负优先级 swapon -p 不接受
    swapoff /dev/zram0 2>/dev/null || { t "zram: swapoff failed"; return 0; }
    echo 1 > $Z/reset
    if echo $want > $Z/disksize && mkswap /dev/zram0 >/dev/null 2>&1 && swapon -p ${prio:-100} /dev/zram0 2>/dev/null; then
        t "zram0 resized to 256MB"
    else
        # 扩不成就恢复成原来的 128 MB，不能让 Kindle 没有交换区
        echo 1 > $Z/reset; echo $((128 * 1024 * 1024)) > $Z/disksize && mkswap /dev/zram0 >/dev/null 2>&1 && swapon -p ${prio:-100} /dev/zram0 2>/dev/null
        t "zram: resize failed, restored 128MB"
    fi
}
zram_on() {
    overcommit_on
    heap_fix
    tune_apply
    lmk_kernel_tune
    Z=/sys/block/zram0
    swapfile_on        # 只在 256 MB 机型上做（在 zram 之后用：优先级低）
    [ -e $Z/disksize ] || return 0
    [ "${ZRAM_MB:-0}" -gt 0 ] || return 0
    # 3.0 内核（zram 是我们加载的模块，Kindle 自己没开）：按内存定大小，压缩后满载约占三分之一
    # 4.1 内核（zram 编进内核了）同样按内存封顶：默认的 512 在 512 MB 机型上压满要占约 170 MB 实际内存
    case "$(uname -r)" in 3.*|4.1.*) mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
        if [ "$mt" -lt 400000 ] 2>/dev/null; then [ "$ZRAM_MB" -gt 160 ] && ZRAM_MB=160; else [ "$ZRAM_MB" -gt 256 ] && ZRAM_MB=256; fi ;; esac
    # 512 MB 机型上 Kindle 自己已经开了 128 MB 的 zram0，其中大半被 Kindle 的 Java 界面占着；系统一跑起来交换区就满，
    # 一直在回收内存（KT5 模拟器实测 SwapFree 0、kswapd 长期占 CPU），非常卡。Android 启动前内存还宽裕时把它扩到
    # 256 MB（压缩后满载约占 85 MB 实际内存）。swapoff 要把已经换出的页读回来：可用内存不够就不动。扩大后不改回去。
    zram_grow
    if ! grep -q '^/dev/zram0 ' /proc/swaps; then
        [ "$(cat $Z/disksize)" != 0 ] && echo 1 > $Z/reset
        [ "$(cat $Z/disksize)" = 0 ] || { t "zram0 busy, skipped"; return 0; }
        echo $((ZRAM_MB * 1048576)) > $Z/disksize && mkswap /dev/zram0 >/dev/null && swapon -p 100 /dev/zram0 || {
            echo 1 > $Z/reset 2>/dev/null; t "zram failed"; return 0; }
    fi
    [ -f $W/zram.state ] || echo "$(cat /proc/sys/vm/swappiness) $(cat /proc/sys/vm/page-cluster)" > $W/zram.state
    # swappiness：957 MB 的 PW6 用 40（100 会把正在用的应用页也换出去，再用时要从 zram 解压回来）。
    # 512 MB 机型用 100：内存紧的时候 40 让内核去扔文件页（应用的 odex / apk 代码），下一次用到就是一次读盘的主缺页；
    # 换成往 zram 里压匿名页更便宜（docs/slim-android.md 第 7 节）。设置里写 VM_SWAPPINESS 可覆盖。
    sw=40; lowmem_ram && sw=100
    echo ${VM_SWAPPINESS:-$sw} > /proc/sys/vm/swappiness
    echo 0 > /proc/sys/vm/page-cluster
    # 实验用：kswapd 提前多少开始回收（watermark_scale_factor，万分之几，内核默认 10）。空 = 不动
    if [ -n "$VM_WATERMARK" ] && [ -w /proc/sys/vm/watermark_scale_factor ]; then
        [ -f $W/wsf.state ] || cat /proc/sys/vm/watermark_scale_factor > $W/wsf.state
        echo "$VM_WATERMARK" > /proc/sys/vm/watermark_scale_factor 2>/dev/null
    fi
    t "zram ${ZRAM_MB}MB on"
}
zram_restore() {
    overcommit_restore
    lmk_kernel_restore
    tune_restore
    swapfile_off
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
    # 4.1 内核（PW4 / 入门款 2019 / Oasis 2 / 3）的 eMMC 走 falcon_blk，叫 mmcblk1 / mmcblk2，不是 mmcblk0
    for b in /sys/block/mmcblk[0-9] /sys/block/loop[0-9]*; do
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

# ---- 交换文件（legacy：3.0 内核没有 zram，CONFIG_SWAP=y、有 mkswap / swapon）----
# 256 MB 的 PW2 上 Kindle 自己开机后就用掉约 228 MB（模拟器实测），Android 没有交换区根本起不来。
# 内存不到约 400 MB 时（SWAPFILE_MB=auto），Android 运行期间在用户分区上开一个交换文件；停的时候 swapoff
# （USB 连电脑时用户分区要能卸下来）。文件留着，下次不用再写一遍；卸载时删掉。
# 用户分区剩余空间不够（交换文件 + 200 MB）就不开，记日志。
SWAPFILE=/mnt/base-us/veradroid-swap
swapfile_mb() {
    case "${SWAPFILE_MB:-auto}" in
        auto) mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo); if [ -n "$mt" ] && [ "$mt" -lt 400000 ]; then echo 256; else echo 0; fi ;;
        *[!0-9]*|'') echo 0 ;;
        *) echo "$SWAPFILE_MB" ;;
    esac
}
swapfile_on() {
    n=$(swapfile_mb); [ "$n" -gt 0 ] 2>/dev/null || return 0
    grep -q "^$SWAPFILE " /proc/swaps && return 0
    command -v swapon >/dev/null 2>&1 && command -v mkswap >/dev/null 2>&1 || { t "swapfile: no swapon/mkswap"; return 0; }
    have=$(( $(stat -c %s "$SWAPFILE" 2>/dev/null || echo 0) / 1048576 ))
    if [ "$have" != "$n" ]; then
        rm -f "$SWAPFILE"
        free_mb=$(df -m /mnt/base-us 2>/dev/null | awk 'NR==2{print $4}')
        [ -n "$free_mb" ] && [ "$free_mb" -ge $((n + 200)) ] 2>/dev/null || { t "swapfile: not enough space on the user storage (${free_mb:-?} MB free, need $((n + 200)))"; return 0; }
        t "swapfile: creating ${n} MB $SWAPFILE"
        nice -n 10 dd if=/dev/zero of="$SWAPFILE" bs=1048576 count=$n 2>/dev/null && mkswap "$SWAPFILE" >/dev/null 2>&1 \
            || { rm -f "$SWAPFILE"; t "swapfile: create failed"; return 0; }
    fi
    swapon "$SWAPFILE" 2>/dev/null || { mkswap "$SWAPFILE" >/dev/null 2>&1 && swapon "$SWAPFILE" 2>/dev/null; } || { t "swapfile: swapon failed"; return 0; }
    [ -f $W/zram.state ] || echo "$(cat /proc/sys/vm/swappiness) $(cat /proc/sys/vm/page-cluster)" > $W/zram.state
    echo ${VM_SWAPPINESS:-60} > /proc/sys/vm/swappiness
    echo 0 > /proc/sys/vm/page-cluster
    touch $W/swapfile.on
    t "swapfile: ${n} MB on ($SWAPFILE)"
}
swapfile_off() {
    [ -f $W/swapfile.on ] || return 0
    swapoff "$SWAPFILE" 2>/dev/null && t "swapfile: off" || t "swapfile: swapoff failed"
    rm -f $W/swapfile.on
}

# ---- 机型调优（models/<机型>.conf 的键，都可以在 settings.conf 里改；空 = 不动，和以前一样）----------------------------
#   VM_VFS_CACHE_PRESSURE  VM_MIN_FREE_KB   运行期间的 vm 参数，停止时恢复（VM_SWAPPINESS 在 zram_on 里）
#   LCD_DENSITY            ro.sf.lcd_density（系统镜像里是 300，按 PW6 的 300 ppi；PW2 212 ppi、入门款 2019 167 ppi 要改）
#   CPU_GOV                Android 运行期间的 CPU 调速器（空 = 用 Kindle 的），停止时恢复
#   PAGE_KEYS              翻页键的输入设备名（Voyage fsr_keypad、Oasis 2/3 gpio-keys），绑进 Android（container.sh）
#   PAGE_KEYS_SWAP=1       两个键对调；PAGE_KEYS_MAP=volume|page|dpad  翻页键给 Android 的键码（VOLUME / PAGE_UP/DOWN / DPAD_LEFT/RIGHT，用户可在设置里改）
#   PAGE_KEYS_ENABLE       deviced 的属性名（空格分隔），Android 运行期间设成 1（Voyage：fsrkeypadEnable fsrkeypadPrevEnable fsrkeypadNextEnable）
#   FL_COLD / FL_WARM      前光：/sys/class/backlight/ 下冷光的名字；暖光 none | lipc（powerd currentAmberLevel）。桌面从 ctl/status 读
#   FL_LIPC0_DIM=1         powerd 的 flIntensity=0 不是关灯（PW1/PW2/Voyage/PW3，KOReader canTurnFrontlightOff=false）：关灯时再写 sysfs 0
#   PROC_LIMIT             ActivityManager 后台进程上限（开机后设；空 = 系统默认）。这些内核都没有 lowmemorykiller，
#                          系统自己的上限是唯一提前收掉后台应用的手段
TUNE_VM="vfs_cache_pressure:VM_VFS_CACHE_PRESSURE min_free_kbytes:VM_MIN_FREE_KB"
tune_apply() {
    t "model profile: ${MODEL_SUB:-none} ($(uname -r), board $(cut -c1-3 /proc/board_id 2>/dev/null), fb $(fb_short_side), MemTotal $(awk '/^MemTotal:/{print $2}' /proc/meminfo) kB)"
    for kv in $TUNE_VM; do
        k=${kv%%:*}; eval "v=\$${kv#*:}"
        f=/proc/sys/vm/$k
        case "$v" in ''|*[!0-9]*) continue ;; esac
        [ -w "$f" ] || continue
        # min_free_kbytes 只往上调：PW3 的 3.0 内核默认已经是 4160（模拟器实测），比按公式估的大
        [ $k = min_free_kbytes ] && [ "$(cat $f)" -ge "$v" ] 2>/dev/null && [ ! -f $W/vm.$k.state ] && { t "vm: $k stays $(cat $f) (>= $v)"; continue; }
        [ -f $W/vm.$k.state ] || cat $f > $W/vm.$k.state
        echo "$v" > $f 2>/dev/null && t "vm: $k=$v (was $(cat $W/vm.$k.state))"
    done
    if [ -n "$CPU_GOV" ]; then
        for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
            [ -f "$g" ] || continue
            grep -qw "$CPU_GOV" ${g%/*}/scaling_available_governors 2>/dev/null || { t "cpu: governor $CPU_GOV not available"; break; }
            [ -f $W/cpu_gov_run.state ] || cat "$g" > $W/cpu_gov_run.state
            echo "$CPU_GOV" > "$g" 2>/dev/null
        done
        [ -f $W/cpu_gov_run.state ] && t "cpu: governor $CPU_GOV (was $(cat $W/cpu_gov_run.state))"
    fi
    f=$R/system/build.prop
    case "$LCD_DENSITY" in
        ''|*[!0-9]*) ;;
        *) if [ -f "$f" ] && ! grep -q "^ro.sf.lcd_density=$LCD_DENSITY\$" "$f"; then
               if grep -q '^ro.sf.lcd_density=' "$f"; then sed -i "s|^ro.sf.lcd_density=.*|ro.sf.lcd_density=$LCD_DENSITY|" "$f"; else echo "ro.sf.lcd_density=$LCD_DENSITY" >> "$f"; fi
               t "display: ro.sf.lcd_density=$LCD_DENSITY"
           fi ;;
    esac
    page_keys_layout
    # 前光的机型参数给 action.sh（手势进程不读 settings.sh，只读这个小文件）
    { echo "FL_COLD=$FL_COLD"; echo "FL_WARM=$FL_WARM"; echo "FL_LIPC0_DIM=${FL_LIPC0_DIM:-0}"; } > $W/fl.conf 2>/dev/null
    # Voyage 的 PagePress：deviced 的 fsrkeypad*Enable 决定压感键发不发事件（Kindle 只在阅读界面打开）。Android 运行期间打开，停止时恢复
    : > $W/pk.state
    for k in $PAGE_KEYS_ENABLE; do
        case "$k" in *[!A-Za-z]*) continue ;; esac
        v=$(lipc-get-prop com.lab126.deviced $k 2>/dev/null) || continue
        echo "$k $v" >> $W/pk.state
        lipc-set-prop -i com.lab126.deviced $k 1 2>/dev/null && t "keys: deviced $k=1 (was $v)"
    done
    return 0
}
tune_restore() {
    if [ -s $W/pk.state ]; then
        while read k v; do lipc-set-prop -i com.lab126.deviced $k "$v" 2>/dev/null; done < $W/pk.state
    fi
    rm -f $W/pk.state $W/fl.conf
    for kv in $TUNE_VM; do
        k=${kv%%:*}
        [ -f $W/vm.$k.state ] || continue
        cat $W/vm.$k.state > /proc/sys/vm/$k 2>/dev/null
        rm -f $W/vm.$k.state
    done
    if [ -f $W/cpu_gov_run.state ]; then
        prev=$(cat $W/cpu_gov_run.state)
        for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do [ -f "$g" ] && echo "$prev" > "$g" 2>/dev/null; done
        rm -f $W/cpu_gov_run.state $W/cpu_gov.state
    fi
    return 0
}
# 翻页键的键位表（Android 先按设备名找 /system/usr/keylayout/<名字>.kl，找不到才用 Generic.kl）。只写两个翻页键：
# 同一个设备上别的键（万一有电源键）在 Android 里就没有映射，不会让 Android 自己去睡眠。
# PAGE_KEYS_MAP（设置 → 手势 → 实体翻页键，和蓝牙翻页器的 BT_PT_MAP 同一套值）：
#   volume（默认）下一页 = VOLUME_DOWN、上一页 = VOLUME_UP：掌阅 / 微信读书 / 起点 / QQ 阅读 / 多看 / 阅读（Legado）都认音量键，
#          认 PAGE_UP / PAGE_DOWN 的少（社区 Oasis 3：掌阅精选按翻页键没反应）；蓝牙翻页器、机身双击也默认发音量键
#   page   PAGE_DOWN / PAGE_UP（0.8.13 以前的默认）
#   dpad   DPAD_RIGHT / DPAD_LEFT
# 改了 PK_CHANGED=1。
page_keys_layout() {
    PK_CHANGED=
    [ -n "$PAGE_KEYS" ] && [ -d "$R/system/usr/keylayout" ] || return 0
    case "$PAGE_KEYS" in *[!A-Za-z0-9_.-]*) return 0 ;; esac
    case "$PAGE_KEYS_MAP" in
        page) up=PAGE_UP; dn=PAGE_DOWN ;;
        dpad) up=DPAD_LEFT; dn=DPAD_RIGHT ;;
        *)    up=VOLUME_UP; dn=VOLUME_DOWN ;;
    esac
    if [ "$PAGE_KEYS_SWAP" = 1 ]; then k104=$dn; k109=$up; else k104=$up; k109=$dn; fi
    f=$R/system/usr/keylayout/$PAGE_KEYS.kl
    printf '# generated by VeraDroid (boot/lib/memory.sh page_keys_layout)\nkey 104   %s\nkey 109   %s\n' $k104 $k109 > $W/kl.new
    if ! cmp -s $W/kl.new "$f"; then
        if cp $W/kl.new "$f" && chmod 644 "$f"; then PK_CHANGED=1; t "keys: $PAGE_KEYS.kl 104=$k104 109=$k109"
        else t "keys: cannot write $PAGE_KEYS.kl (applies at the next Android start)"; fi
    fi
    rm -f $W/kl.new
    return 0
}
# 设置里改了 PAGE_KEYS_MAP（settings.sh settings_apply）：Android 运行中时让它重新打开翻页键设备。键位表只在打开设备时读
# （EventHub 盯着 /dev/input 的 IN_CREATE / IN_DELETE），所以在容器里把那个节点（container.sh 绑在 event2）换成同一个
# 设备号的字符设备：删掉 = 关设备，mknod = 用新键位表重新打开。不重启 Android；做不了就下次启动生效。
page_keys_apply() {
    page_keys_layout
    [ -n "$PK_CHANGED" ] && [ -n "$PAGE_KEYS" ] && alive $W/pid || return 0
    _pkn=$(awk -v pat="\"$PAGE_KEYS\"" '/^N: Name=/ { m = ($0 ~ pat) }
        m && /^H: Handlers=/ { for (i = 1; i <= NF; i++) if (match($i, /event[0-9]+$/)) { print substr($i, RSTART); exit } }' /proc/bus/input/devices)
    _pkd=$(cat /sys/class/input/$_pkn/dev 2>/dev/null)
    case "$_pkd" in [0-9]*:[0-9]*) ;; *) t "keys: $PAGE_KEYS not found, new layout at the next Android start"; return 0 ;; esac
    adb_sh "umount /dev/input/event2 2>/dev/null; rm -f /dev/input/event2 && mknod -m 666 /dev/input/event2 c ${_pkd%%:*} ${_pkd#*:}" >/dev/null 2>&1 \
        && t "keys: $PAGE_KEYS reopened in Android ($PAGE_KEYS_MAP)" || t "keys: reopen failed, new layout at the next Android start"
    return 0
}
# 关灯：powerd 写 0 之后，在 0 档还亮的机型上把 sysfs 的亮度也写 0（下一次 powerd 设亮度时会自己写回去）
fl_off_sysfs() {
    [ "${FL_LIPC0_DIM:-0}" = 1 ] && [ -n "$FL_COLD" ] || return 0
    case "$FL_COLD" in *[!A-Za-z0-9_.-]*) return 0 ;; esac
    [ -w /sys/class/backlight/$FL_COLD/brightness ] && echo 0 > /sys/class/backlight/$FL_COLD/brightness 2>/dev/null
    return 0
}
# 开机后：后台进程上限（after_boot 调；不持久，每次开机都要设）
proc_limit_apply() {
    case "$PROC_LIMIT" in ''|*[!0-9]*) return 0 ;; esac
    # IActivityManager.SET_PROCESS_LIMIT_TRANSACTION = FIRST_CALL_TRANSACTION + 50（7.1.1 源码），即开发者选项里的“后台进程限制”
    r=$(adb_sh "service call activity 51 i32 $PROC_LIMIT" 2>/dev/null | tr -d '\r\n')
    t "am: background process limit $PROC_LIMIT (${r:-no reply})"
}

# 页缓存去重（CACHE_DEDUP，默认 1；docs/legacy-perf.md）。系统盘是 vfat 上的 ext4 镜像（loop1），vfat 本身又挂在 eMMC 分区上的 loop0：
# 缓冲方式的 loop 让 Android 读过的每一页在页缓存里存三份（ext4 文件、vfat 上的镜像文件、loop0 下的块设备）。4.9 以后的机型用
# loop 直读写（k49 的 loopdio_on）；4.1 / 3.0 内核没有这个功能，只能定期把后两份丢掉：bin/vdloopdio --drop 对镜像文件和 loop0 的
# 后端做 posix_fadvise(DONTNEED)，只丢干净、没映射的页，脏页照常写回。PW4 5.18.1 模拟器，微信读书从桌面冷启动（150 秒窗口）：
# 显示出来 44.5 s -> 13.6 s，读盘 0.80 GB -> 0.13 GB，workingset_refault 20 万 -> 1 万页，整机 CPU（不含微信读书）23.7 % -> 6.9 %。
# Android 冻着（返回 Kindle / 屏保）时不丢，30 秒才看一次。
dedup_start() {
    [ "${CACHE_DEDUP:-1}" = 1 ] && [ -x "$VD/bin/vdloopdio" ] && [ -f "$SYSTEM_IMG" ] || return 0
    d=$(awk '$2 == "/mnt/base-us" {print $1}' /proc/mounts | tail -1)
    n=$(echo "${d##*/}" | sed 's/^loop//'); bk=
    case "$d" in /dev/loop*) bk=$(cat /sys/block/loop$n/loop/backing_file 2>/dev/null) ;; esac
    case "$bk" in /dev/*) [ -b "$bk" ] || bk= ;; *) bk= ;; esac
    alive $W/dedup.pid && return 0
    (
        while [ -f $W/pid ]; do
            if [ -f $W/frozen ] || [ -f $W/saver_frozen ]; then sleep 30; continue; fi
            "$VD/bin/vdloopdio" --drop "$SYSTEM_IMG" $bk 2>/dev/null
            sleep ${CACHE_DEDUP_S:-3}
        done
    ) </dev/null >/dev/null 2>&1 &
    echo $! > $W/dedup.pid
    t "cache dedup: dropping duplicate pages of $SYSTEM_IMG${bk:+ and $bk} every ${CACHE_DEDUP_S:-3}s"
}
dedup_stop() {
    p=$(cat $W/dedup.pid 2>/dev/null); rm -f $W/dedup.pid
    [ -n "$p" ] && kill "$p" 2>/dev/null
    return 0
}
