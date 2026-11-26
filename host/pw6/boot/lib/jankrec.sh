# 卡顿记录（JANKREC=1 才开，默认关；docs/jank.md）：机主觉得“卡”的那一刻按一下手势（动作 jank）或发命令 jankdump，
# 把之前十几秒的情况存成一个文件，事后就能看出那次卡在哪（CPU 被谁占、在不在换页 / 读盘、显示桥那几次刷新各用了多久）。
#   每 2 秒：一行系统计数（/proc/stat 的 CPU、vmstat 里换页 / 读盘 / 回收、CPU 频率、可用内存），只留最后 12 行；
#   每 10 秒：所有进程累计 CPU 的快照（/proc/*/stat），只留最近 3 份；导出时和当前比，得出最近 20–30 秒谁用了多少 CPU。
# Android 冻结（回到 Kindle）或屏保时不采样。开销（PW6 实测）：一个核的 1.5 %（每秒采一次时是 2–4 %：每次 fork 约 10 ms）。
# 导出：$SYS/jank/jank-<时间>.txt（最多留 20 个），宿主日志记一行。
JR=$W/jankrec

jr_snap() {   # 所有进程的累计 CPU（tick）：pid utime+stime 名字。用 cat 读：awk 直接读时某个进程正好退出会让它整个停下
    cat /proc/[0-9]*/stat 2>/dev/null | awk '{ i = index($0, ") "); n = substr($0, index($0, "(") + 1, i - index($0, "(") - 1); gsub(/ /, "_", n);
           split(substr($0, i + 2), b, " "); print $1, b[12] + b[13], n }'
}

jankrec_loop() {
    mkdir -p $JR
    n=0
    while :; do
        if [ -e $W/frozen ] || [ -e $X/ctl/sleep ]; then sleep 5; continue; fi
        # one awk for the whole line (each fork costs ~10 ms of CPU here)
        cf=; [ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq ] && cf=/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
        awk 'FILENAME == "/proc/uptime" { printf "%s ", $1 }
             FILENAME == "/proc/stat" && $1 == "cpu" { printf "cpu=%d,%d,%d,%d ", $2+$3, $4+$7+$8, $5, $6 }
             FILENAME == "/proc/vmstat" && $1 ~ /^(pgmajfault|pgpgin|pswpin|pswpout|workingset_refault_file|pgsteal_direct|pgscan_kswapd|allocstall_movable)$/ { printf "%s=%s ", $1, $2 }
             FILENAME ~ /cpufreq/ { printf "freq=%s ", $1 }
             FILENAME == "/proc/meminfo" && $1 == "MemAvailable:" { printf "avail=%s", $2 }
             END { print "" }' /proc/uptime /proc/stat /proc/vmstat $cf /proc/meminfo >> $JR/vm 2>/dev/null
        if [ $((n % 12)) = 11 ]; then tail -n 12 $JR/vm > $JR/vm.t && mv -f $JR/vm.t $JR/vm; fi
        if [ $((n % 5)) = 0 ]; then
            i=$((n / 5 % 3))
            { read -r up _ < /proc/uptime; echo $up; jr_snap; } > $JR/ps.$i.t && mv -f $JR/ps.$i.t $JR/ps.$i
        fi
        n=$((n + 1))
        sleep 2
    done
}

jankrec_start() {
    [ "${JANKREC:-0}" = 1 ] || return 0
    kill $(cat $W/jankrec.pid 2>/dev/null) 2>/dev/null
    jankrec_loop </dev/null >/dev/null 2>&1 &
    echo $! > $W/jankrec.pid
    t "jankrec: on"
}

# jankdump：把记下来的东西和当前状态写成一个文件
jankrec_dump() {
    [ -d $JR ] || { t "jankdump: recorder is off (JANKREC=1 to turn it on)"; return 0; }
    d=$SYS/jank; mkdir -p $d
    f=$d/jank-$(date +%Y%m%d-%H%M%S).txt
    now=$(cut -d' ' -f1 /proc/uptime)
    old=$(ls -t $JR/ps.? 2>/dev/null | tail -1)
    {
        echo "# jankdump up=$now fg=$(cat $X/fg 2>/dev/null) settings: $(grep -E '^KFB_(SMOOTH|MODE|SETTLE|PACE|HZ|REAGL_MIN)=' $SYS/settings.conf 2>/dev/null | tr '\n' ' ')"
        echo "## every 2 s: uptime cpu=user,sys,idle,iowait (ticks, cumulative) + vmstat counters"
        cat $JR/vm 2>/dev/null
        if [ -n "$old" ]; then
            t0=$(head -1 $old)
            echo "## CPU ms per process since up=$t0 ($(awk -v a=$t0 -v b=$now 'BEGIN{printf "%.0f", b-a}') s)"
            jr_snap > $JR/ps.now
            awk 'NR==FNR { if (FNR > 1) c[$1] = $2; next } { d = ($2 - ($1 in c ? c[$1] : 0)) * 10; if (d >= 30) printf "%7d ms  %s %s\n", d, $1, $3 }' $old $JR/ps.now | sort -rn | head -25
        fi
        echo "## epd (bridge) log, last 60 lines"
        tail -n 60 $W/epd.log 2>/dev/null
        echo "## input log, last 30 lines"
        tail -n 30 $W/input.log 2>/dev/null
        echo "## meminfo"
        grep -E '^(MemFree|MemAvailable|Cached|AnonPages|Shmem|SwapFree|SwapTotal):' /proc/meminfo
    } > $f 2>&1
    ls -t $d/jank-*.txt 2>/dev/null | tail -n +21 | while read -r o; do rm -f "$o"; done
    t "jankdump: $f"
}
