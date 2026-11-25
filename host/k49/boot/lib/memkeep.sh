# 后台应用 / 内存管理。host/pw6、host/k49、host/legacy 三份完全相同（tests/memkeep-test.sh 核对）。
#
# 1. lmkd 杀进程的门槛（LMK_MINFREE）
#    Android 7.1 的 lmkd（Kindle 5.x 内核没有内核 LMK，lmkd 用 memcg 压力事件）：空闲内存和文件缓存都低于某一档就杀
#    oom_adj 不小于那一档的进程。ActivityManager 按“内存 + 屏幕尺寸”选表，Kindle 的大屏选了大屏手机那一档：
#    文件缓存 < 126 MB 就杀 adj >= 300、< 144 MB 杀缓存的应用。1 GB 的 PW6 测试机（2026-11-18）上打开豆瓣阅读 / 晋江，
#    多看、微信读书、豆瓣自己都被杀（桌面 adj 100、输入法 adj 200 也只差一档）。有了 zram，文件缓存常年在 120–150 MB，
#    这张表等于“一打开大应用就清后台”。
#    512 MB（2026-11-21 改）：前台 / 可见 / 可感知仍是 8 / 12 / 16 MB，备份 32 MB、缓存的应用 56 / 64 MB（以前 20 / 24 / 32 MB）。
#    PW5 用户诊断：番茄 + 它的编译把交换区占满、zram 分配失败，内核一直在换页（49 次 page allocation failure），
#    可文件页（应用的代码，正在被反复读回来）一直在 32 MB 以上，按旧表一个后台进程也不杀，机器卡死到只能强制重启。
#    auto：< 700 MB 用小内存表；1 GB 用 24/32/40/48/80/96 MB（前台 / 可见（桌面）/ 可感知（输入法）/
#    备份 / 缓存）。测试机实测同样三个大应用：lmkd 一次都没杀，多看回去 0.9 s（以前冷启动 2.1 s）。
#    stock = 不改（原厂表）；也可以直接写 "页数:adj ..."（最多 6 对，1 页 = 4 KB）。
# 2. 第二块 zram（zram_extra）：zram0 已经在用（Kindle 自己开的，或者上次运行留下的，只能空着时改大小）而且比 ZRAM_MB 小，
#    用 hot_add 再加一块补上差额，不用 swapoff。只在 >= 700 MB 的机型上做（512 MB 机型有 zram_grow，压缩数据也占内存）。
# 3. 桌面 / 输入法不换出（MEM_KEEP）：放进单独的 memory cgroup（vdkeep，swappiness 0），回桌面、弹键盘时不用从 zram
#    解压页面。只针对这两个（加上 MEM_KEEP_APPS），总共几十 MB。auto = >= 700 MB 的机型开。
# 4. 后台应用定时停止（BG_STOP_MIN / BG_STOP_APPS）：列出来的应用离开前台超过 N 分钟就
#    am force-stop（不停用包）。默认关（BG_STOP_MIN=0）；BG_STOP_APPS 逗号分隔的包名。
#    桌面 / 输入法 / 当前前台的应用永远不停。
#    每个应用也可以在桌面的“应用优化”页单独设（宿主在 $X/appknobs 声明选项，存进 settings.apps / 合并表 $X/appprof）：
#    KEEPALIVE=1 保持后台（也放进 vdkeep，不被定时停止）；BGSTOP=N 离开前台 N 分钟后停止（0 = 不停）。
# 5. 开机自启（BOOT_BLOCK）：第三方应用的开机广播接收器（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）
#    pm disable 掉，开机时不再自己起来、弹到桌面上面（稻壳阅读器的协议页盖住桌面，docs/TASKS.md）。闹钟 / 日历 / 待办类和
#    BOOT_ALLOW 里的包不动。关掉的记在 $SYS/bootblock.list，BOOT_BLOCK=0 时 pm enable 回来（本来就关着的不碰、不记）。默认开。
# 6. Wi-Fi 空闲关闭（WIFI_IDLE_OFF_MIN）：Android 在前台、Wi-Fi 收发低于 WIFI_IDLE_BPS 超过 N 分钟就关
#    Wi-Fi；之后前台一换应用 / 回桌面就重新打开。默认关（推送 / 同步会断）。
# 前台信号来自 appprof_fg（VeraHome 的 "fg <包名>"），这里只记时间：mem_fg。

lmk_targets() {
    case "${LMK_MINFREE:-auto}" in
        stock|off|0) return 1 ;;
        auto)
            if lowmem_ram 2>/dev/null; then echo "2048:0 3072:100 4096:200 8192:300 14336:900 16384:906"
            else echo "6144:0 8192:100 10240:200 12288:300 20480:900 24576:906"; fi ;;
        *)
            lt_n=0
            for lt_p in $LMK_MINFREE; do
                case "$lt_p" in *[!0-9:-]*|:*|*:|*:*:*|*-*-*) return 1 ;; *:*) ;; *) return 1 ;; esac
                lt_n=$((lt_n + 1))
            done
            [ $lt_n -ge 1 ] && [ $lt_n -le 6 ] || return 1
            echo "$LMK_MINFREE" ;;
    esac
}

# 把门槛发给 lmkd（afterboot 的 lmk_tune 和 watchdog 在 system_server 重启后调用）
lmk_send() {
    lt_t=$(lmk_targets) || { t "lmk: lmkd thresholds left as stock (LMK_MINFREE=${LMK_MINFREE})"; return 0; }
    [ -x "$VD/bin/vdlmk" ] || { t "lmk: vdlmk missing"; return 0; }
    lt_s=/proc/$P/root/dev/socket/lmkd
    lt_i=0; while [ ! -S "$lt_s" ] && [ $lt_i -lt 30 ]; do sleep 1; lt_i=$((lt_i + 1)); done
    if [ -S "$lt_s" ]; then
        lt_ok=
        for lt_w in 2 4 0; do
            if "$VD/bin/vdlmk" "$lt_s" $lt_t >>$LOG 2>&1; then
                t "lmk: lmkd thresholds set ($lt_t)"; lt_ok=1; break
            fi
            [ "$lt_w" -gt 0 ] && sleep "$lt_w"
        done
        [ -n "$lt_ok" ] || t "lmk: vdlmk failed (after 3 tries)"
    else
        t "lmk: lmkd socket missing, kernel minfree only"
    fi
}

# 第二块 zram：补到 ZRAM_MB（见上面 2.）
zram_extra() {
    case "${ZRAM_EXTRA:-auto}" in 0|off) return 0 ;; auto) lowmem_ram 2>/dev/null && return 0 ;; esac
    [ "${ZRAM_MB:-0}" -gt 0 ] 2>/dev/null || return 0
    [ -w /sys/class/zram-control/hot_add ] || return 0
    grep -q '^/dev/zram0 ' /proc/swaps || return 0
    # 已经有别的 zram 交换区（上次运行加的，Kindle 重启前一直在）：不再加
    awk '$1 ~ /^\/dev\/zram[1-9][0-9]*$/ { f = 1 } END { exit !f }' /proc/swaps && return 0
    ze_have=$(awk '$1 == "/dev/zram0" { print int($3 / 1024) }' /proc/swaps)
    ze_need=$((ZRAM_MB - ${ze_have:-0}))
    [ "$ze_need" -ge 64 ] || return 0
    ze_n=$(cat /sys/class/zram-control/hot_add 2>/dev/null)
    case "$ze_n" in ''|*[!0-9]*) t "zram: hot_add failed"; return 0 ;; esac
    ze_b=/sys/block/zram$ze_n
    ze_d=$(cat $ze_b/dev 2>/dev/null)
    [ -e /dev/zram$ze_n ] || { [ -n "$ze_d" ] && mknod /dev/zram$ze_n b ${ze_d%%:*} ${ze_d##*:} 2>/dev/null; }
    if echo ${ze_need}M > $ze_b/disksize && mkswap /dev/zram$ze_n >/dev/null 2>&1 && swapon -p 100 /dev/zram$ze_n 2>/dev/null; then
        t "zram: zram0 busy at ${ze_have}MB, added zram$ze_n ${ze_need}MB (total ${ZRAM_MB}MB)"
    else
        echo 1 > $ze_b/reset 2>/dev/null; echo $ze_n > /sys/class/zram-control/hot_remove 2>/dev/null
        t "zram: could not add zram$ze_n"
    fi
    return 0
}

MEMKEEP_CG=/sys/fs/cgroup/memory/vdkeep
mem_keep_on() {
    case "${MEM_KEEP:-auto}" in 0|off) return 1 ;; auto) lowmem_ram 2>/dev/null && return 1 ;; esac
    return 0
}
# 桌面 / 输入法放进 vdkeep（进程号变了才写；进程没了 cgroup 自己就空了）。watchdog 每 20 秒、回桌面时各一次
mem_keep_tick() {
    mem_keep_on || return 0
    mk_r=${MEMKEEP_CG%/*}
    [ -f $mk_r/memory.swappiness ] || return 0
    if [ ! -d $MEMKEEP_CG ]; then
        mkdir $MEMKEEP_CG 2>/dev/null || return 0
        echo 0 > $MEMKEEP_CG/memory.swappiness 2>/dev/null
        echo 1 > $MEMKEEP_CG/memory.move_charge_at_immigrate 2>/dev/null
        t "memkeep: cgroup vdkeep (swappiness 0) for home / ime${MEM_KEEP_APPS:+ / $MEM_KEEP_APPS}"
    fi
    for mk_p in com.veradroid.home com.veradroid.ime $(echo "$MEM_KEEP_APPS" | tr ',' ' ') $(mem_knob_pkgs KEEPALIVE 1); do
        for mk_id in $(pidof "$mk_p" 2>/dev/null); do
            grep -qx "$mk_id" $MEMKEEP_CG/cgroup.procs 2>/dev/null || echo "$mk_id" > $MEMKEEP_CG/cgroup.procs 2>/dev/null
        done
    done
    return 0
}
# 停止时：先解冻所有被冻结的后台应用，再删掉 cgroup（Android 的进程都已经退出，空的才删得掉）
mem_keep_off() { bg_thaw_all; [ -d $MEMKEEP_CG ] && rmdir $MEMKEEP_CG 2>/dev/null; return 0; }

# ---- 切换应用立即冻结后台（BG_FREEZE，默认开）----
# 打开/切换前台应用时，立刻给上一个后台第三方应用发 SIGSTOP 冻结（0% CPU、停止内存增长，zram 可安心回收其闲置页）；
# 切回该应用时第一时间发 SIGCONT 秒速恢复，保留原阅读进度。系统应用、桌面、输入法、以及用户设了 KEEPALIVE=1 的应用不冻结。
bg_pkg_pids() {
    awk -v p="$1" 'FNR == 1 { sub(/\0.*/, ""); if ($0 == p || index($0, p ":") == 1) { f = FILENAME; sub(/^\/proc\//, "", f); sub(/\/cmdline$/, "", f); print f } }' /proc/[0-9]*/cmdline 2>/dev/null
}
bg_freeze_skip() {
    case "$1" in ""|home|com.veradroid.*|android|com.android.*) return 0 ;; esac
    appprof_valid_pkg "$1" 2>/dev/null || return 0
    [ "$(mem_knob "$1" KEEPALIVE)" = 1 ] && return 0
    return 1
}
bg_thaw() {
    bt_p=$1
    [ -n "$bt_p" ] || return 0
    [ -s "$W/memfg.frozen" ] && grep -qxF "$bt_p" "$W/memfg.frozen" 2>/dev/null || return 0
    bt_pids=$(bg_pkg_pids "$bt_p")
    [ -n "$bt_pids" ] && kill -CONT $bt_pids 2>/dev/null
    awk -v p="$bt_p" '$0 != p' "$W/memfg.frozen" > "$W/memfg.frozen.tmp" 2>/dev/null && mv -f "$W/memfg.frozen.tmp" "$W/memfg.frozen"
    [ -n "$bt_pids" ] && t "bgfreeze: thawed $bt_p"
    return 0
}
bg_thaw_all() {
    [ -s "$W/memfg.frozen" ] || { rm -f "$W/memfg.frozen"; return 0; }
    while read -r bta_p; do
        [ -n "$bta_p" ] || continue
        bta_pids=$(bg_pkg_pids "$bta_p")
        [ -n "$bta_pids" ] && kill -CONT $bta_pids 2>/dev/null
    done < "$W/memfg.frozen"
    rm -f "$W/memfg.frozen"
    t "bgfreeze: thawed all background apps"
    return 0
}
bg_freeze_sweep() {
    if [ "${BG_FREEZE:-1}" != 1 ]; then bg_thaw_all; return 0; fi
    [ -s "$W/memfg.bg" ] || return 0
    bf_cur=$(cat "$W/memfg.cur" 2>/dev/null)
    while read -r bf_p bf_t; do
        [ "$bf_p" = "$bf_cur" ] && continue
        bg_freeze_skip "$bf_p" && continue
        [ -s "$W/memfg.frozen" ] && grep -qxF "$bf_p" "$W/memfg.frozen" 2>/dev/null && continue
        bf_pids=$(bg_pkg_pids "$bf_p")
        [ -n "$bf_pids" ] || continue
        kill -STOP $bf_pids 2>/dev/null || continue
        echo "$bf_p" >> "$W/memfg.frozen"
        t "bgfreeze: froze $bf_p ($(echo "$bf_pids" | wc -w | tr -d ' ') procs)"
    done < "$W/memfg.bg"
    return 0
}

# 前台变了：立刻解冻新前台应用；记下上一个应用离开的时间（BG_STOP / BG_FREEZE 用），1 秒后冻结后台应用；回桌面时顺便核对 vdkeep
mem_fg() {
    mf_new=$1; mf_old=$(cat $W/memfg.cur 2>/dev/null)
    bg_thaw "$mf_new"
    [ "$mf_new" = "$mf_old" ] && return 0
    echo "$mf_new" > $W/memfg.cur
    wifi_idle_wake
    if [ -n "$mf_old" ] && [ "$mf_old" != home ]; then
        { awk -v p="$mf_old" '$1 != p' $W/memfg.bg 2>/dev/null; echo "$mf_old $(date +%s)"; } > $W/memfg.bg.tmp && mv -f $W/memfg.bg.tmp $W/memfg.bg
    fi
    [ -f $W/memfg.bg ] && awk -v p="$mf_new" '$1 != p' $W/memfg.bg > $W/memfg.bg.tmp 2>/dev/null && mv -f $W/memfg.bg.tmp $W/memfg.bg
    [ "$mf_new" = home ] && mem_keep_tick
    if [ "${BG_FREEZE:-1}" = 1 ] && [ -s "$W/pid" ] && [ -s "$W/memfg.bg" ]; then
        ( sleep 1; bg_freeze_sweep ) >/dev/null 2>&1 &
    fi
    return 0
}

# 合并表（$X/appprof："<包名> b|u|ub <spec>"）里某个键的值：mem_knob <包名> <键>（只认精确的包名行）
mem_knob() { awk -v p="$1" -v k="$2" '$1 == p { n = split($3, a, ";"); for (i = 1; i <= n; i++) if (index(a[i], k "=") == 1) { print substr(a[i], length(k) + 2); exit } }' $X/appprof 2>/dev/null; }
# 某个键等于某个值的包：mem_knob_pkgs KEEPALIVE 1
mem_knob_pkgs() { awk -v k="$1" -v v="$2" '{ n = split($3, a, ";"); for (i = 1; i <= n; i++) if (a[i] == k "=" v) print $1 }' $X/appprof 2>/dev/null | grep -v '\*$'; }

# 桌面“应用优化”页上宿主管的选项：写 $X/appknobs.d/mem，appprof_publish 拼进 $X/appknobs
# （格式见 VeraHome AppDisplayActivity.Knob："键|标题|标签=值,..."，“跟随”桌面自己加）
mem_knobs() {
    mkdir -p $X/appknobs.d 2>/dev/null || return 0
    { echo "KEEPALIVE|保持后台（不被清理、不换出内存）|关=0,开=1"
      echo "BGSTOP|离开后自动停止（省内存）|不停=0,5 分钟=5,15 分钟=15,30 分钟=30,60 分钟=60"; } > $X/appknobs.d/mem.tmp 2>/dev/null &&
        chmod 644 $X/appknobs.d/mem.tmp && mv -f $X/appknobs.d/mem.tmp $X/appknobs.d/mem
    return 0
}

# 后台定时停止（watchdog 每 20 秒；冻结 / 屏保时 watchdog 不调用）。这个应用自己的 BGSTOP（分钟，0 = 不停）优先；
# 没设的看全局 BG_STOP_MIN + BG_STOP_APPS。KEEPALIVE=1 的永远不停。
bg_stop_tick() {
    [ -s $W/memfg.bg ] || return 0
    bs_now=$(date +%s); bs_cur=$(cat $W/memfg.cur 2>/dev/null)
    while read -r bs_p bs_t; do
        case "$bs_p" in home|com.veradroid.home|com.veradroid.ime|"$bs_cur") continue ;; esac
        appprof_valid_pkg "$bs_p" 2>/dev/null || continue
        [ "$(mem_knob "$bs_p" KEEPALIVE)" = 1 ] && continue
        bs_m=$(mem_knob "$bs_p" BGSTOP)
        if [ -z "$bs_m" ]; then
            case ",$BG_STOP_APPS," in *",$bs_p,"*) bs_m=${BG_STOP_MIN:-0} ;; *) continue ;; esac
        fi
        [ "$bs_m" -gt 0 ] 2>/dev/null || continue
        [ $((bs_now - bs_t)) -ge $((bs_m * 60)) ] 2>/dev/null || continue
        bg_thaw "$bs_p"
        adb_sh "am force-stop $bs_p" >/dev/null 2>&1 && t "bgstop: $bs_p stopped ($(( (bs_now - bs_t) / 60 )) min in background)"
        echo "$bs_p" >> $W/memfg.stopped
    done < $W/memfg.bg
    if [ -s $W/memfg.stopped ]; then
        awk 'NR == FNR { s[$1] = 1; next } !($1 in s)' $W/memfg.stopped $W/memfg.bg > $W/memfg.bg.tmp 2>/dev/null; mv -f $W/memfg.bg.tmp $W/memfg.bg
        rm -f $W/memfg.stopped
    fi
    return 0
}

# ---- 开机自启（BOOT_BLOCK）----
# 不动的包：自己的、闹钟 / 日历 / 待办 / 提醒类（按包名猜），加上 BOOT_ALLOW（逗号分隔）
boot_block_allowed() {
    case "$1" in com.veradroid.*|android|com.android.*) return 0 ;; esac
    case "$1" in *clock*|*alarm*|*calendar*|*reminder*|*todo*|*ticktick*|*tasks*|*timer*) return 0 ;; esac
    case "$1" in com.ichi2.anki|com.microsoft.todos) return 0 ;; esac     # 复习 / 待办提醒
    case ",$BOOT_ALLOW," in *",$1,"*) return 0 ;; esac
    return 1
}
# dumpsys package r receiver 里 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED 下面列的组件（包名/类名，类名可能以 . 开头）
boot_receivers() {
    adb_sh 'dumpsys package r receiver' 2>/dev/null | tr -d '\r' | awk '
        /^ *[A-Za-z0-9_.]+:$/ { a = $1; sub(/:$/, "", a); on = (a == "android.intent.action.BOOT_COMPLETED" || a == "android.intent.action.LOCKED_BOOT_COMPLETED"); next }
        on && NF >= 2 && $2 ~ /^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/ { print $2 }' | sort -u
}
# 这个组件现在是不是被（应用或别人）关着：dumpsys package <包> 的 disabledComponents
boot_comp_disabled() {
    adb_sh "dumpsys package $1" 2>/dev/null | tr -d '\r' | awk -v c="$2" '
        /^ *disabledComponents:$/ { on = 1; next } on && /^ *[A-Za-z]+Components:$|^ *[A-Za-z].*:/ { on = 0 }
        on { gsub(/^ +| +$/, ""); if ($0 == c) f = 1 } END { exit !f }'
}
boot_block_apply() {
    bb_l=$SYS/bootblock.list
    if [ "${BOOT_BLOCK:-1}" != 1 ]; then
        [ -s $bb_l ] || return 0
        while read -r bb_c; do adb_sh "pm enable $bb_c" >/dev/null 2>&1; done < $bb_l
        t "bootblock: re-enabled $(wc -l < $bb_l) boot receivers (BOOT_BLOCK=${BOOT_BLOCK})"; rm -f $bb_l
        return 0
    fi
    bb_3=$(adb_sh 'pm list packages -3' 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')
    [ -n "$bb_3" ] || return 0
    bb_n=0; touch $bb_l
    for bb_c in $(boot_receivers); do
        bb_p=${bb_c%%/*}
        printf '%s\n' "$bb_3" | grep -qx "$bb_p" || continue
        # 用户后来放进 BOOT_ALLOW 的：之前关掉的恢复
        if boot_block_allowed "$bb_p"; then
            grep -qxF "$bb_c" $bb_l && adb_sh "pm enable $bb_c" >/dev/null 2>&1 && { grep -vxF "$bb_c" $bb_l > $bb_l.tmp; mv -f $bb_l.tmp $bb_l; t "bootblock: $bb_c allowed again"; }
            continue
        fi
        # 已经是关着的（应用自己关的，比如 WorkManager 没活时的 RescheduleReceiver）：不碰、不记，恢复时才不会把它打开
        bb_cls=${bb_c#*/}; case "$bb_cls" in .*) bb_cls=$bb_p$bb_cls ;; esac
        grep -qxF "$bb_c" $bb_l || ! boot_comp_disabled "$bb_p" "$bb_cls" || continue
        # 每次开机都重新关一遍（WorkManager 之类会在运行时自己把接收器打开）；记录不重复
        adb_sh "pm disable $bb_c" 2>/dev/null | grep -q 'new state: disabled' || continue
        grep -qxF "$bb_c" $bb_l || { echo "$bb_c" >> $bb_l; bb_n=$((bb_n + 1)); }
    done
    t "bootblock: $(wc -l < $bb_l) third-party boot receivers off ($bb_n new)"
    return 0
}

# ---- Wi-Fi 空闲关闭（WIFI_IDLE_OFF_MIN）----
wifi_idle_tick() {
    [ "${WIFI_IDLE_OFF_MIN:-0}" -gt 0 ] 2>/dev/null || return 0
    type wifi_dev >/dev/null 2>&1 && type wifi_set >/dev/null 2>&1 || return 0
    [ -f $W/wifi.idleoff ] && return 0
    [ -f $W/fwd.id ] && { rm -f $W/wifi.idle; return 0; }       # 传书网页开着
    wi_d=$(wifi_dev); wi_now=$(cut -d. -f1 /proc/uptime)
    [ -d /sys/class/net/$wi_d ] || return 0
    wi_b=$(( $(cat /sys/class/net/$wi_d/statistics/rx_bytes 2>/dev/null || echo 0) + $(cat /sys/class/net/$wi_d/statistics/tx_bytes 2>/dev/null || echo 0) ))
    set -- $(cat $W/wifi.idle 2>/dev/null)        # 上次采样时间 字节数 开始空闲的时间
    if [ -z "$1" ]; then echo "$wi_now $wi_b $wi_now" > $W/wifi.idle; return 0; fi
    wi_dt=$((wi_now - $1)); wi_since=$3
    [ $wi_dt -gt 0 ] && [ $(( (wi_b - $2) / wi_dt )) -ge ${WIFI_IDLE_BPS:-512} ] && wi_since=$wi_now
    echo "$wi_now $wi_b $wi_since" > $W/wifi.idle
    if [ $((wi_now - wi_since)) -ge $((WIFI_IDLE_OFF_MIN * 60)) ]; then
        wifi_set 0 && { touch $W/wifi.idleoff; rm -f $W/wifi.idle; t "wifi: idle ${WIFI_IDLE_OFF_MIN} min, turned off (on again at the next app switch)"; }
    fi
    return 0
}
wifi_idle_wake() {
    [ -f $W/wifi.idleoff ] || return 0
    rm -f $W/wifi.idleoff $W/wifi.idle
    type wifi_set >/dev/null 2>&1 && wifi_set 1 && t "wifi: back on (app switch after idle off)"
    return 0
}
