# 后台应用降优先级（BG_NICE，docs/legacy-perf.md）。
# Android 平时把后台应用放进 cpu cgroup 的 bg_non_interactive（cpu.shares 52，前台 1024：后台最多分到约 5 %），
# 文石的老固件（i.MX6 + 512 MB）也是这么配的。老机型的 3.0 / 4.1 内核没有 CONFIG_CGROUP_SCHED（模拟器 /proc/cgroups：
# PW3 一个子系统都没有，PW4 只有 memory），Android 的 set_sched_policy 退回 SCHED_BATCH，后台进程和前台在单核上平分 CPU：
# 后台同步、下载、刚切走的应用还在收尾，前台的滑动和翻页就跟着慢。
# 这里在宿主上补一个近似：zygote 生出来的应用进程里，oom_score_adj >= BG_NICE_ADJ（500 = 服务及以下：后台服务、
# 上一个应用、桌面不在前台时、缓存进程）的，所有线程 nice 设成 BG_NICE（10：CFS 权重 110 对 1024，约 1/10）；
# 回到前台 / 可见（adj 变小）时每个线程放回原来的 nice（记在 $W/bgnice/<pid>）。看门狗每 20 秒一次、前台一换就一次。
# 前台（0）、可见（100）、可感知（200，输入法）、备份（300）、重量级（400）不动。BG_NICE=0 关掉（并全部放回）。
bgnice_tick() {
    bn=${BG_NICE:-10}; ba=${BG_NICE_ADJ:-500}
    case "$bn" in ''|*[!0-9]*) bn=0 ;; esac
    case "$ba" in ''|*[!0-9]*) ba=500 ;; esac
    [ "$bn" -gt 19 ] && bn=19
    bset=$(cat $W/bgnice.val 2>/dev/null)
    case "$bset" in ''|*[!0-9]*) bset=${BG_NICE_SET:-10} ;; esac
    mkdir -p $W/bgnice 2>/dev/null || return 0
    # 记过的进程：没了就删记录；adj 变小了（或关掉了）就放回
    for f in $W/bgnice/*; do
        [ -f "$f" ] || continue
        bp=${f##*/}
        [ -d /proc/$bp ] || { rm -f "$f"; continue; }
        badj=$(cat /proc/$bp/oom_score_adj 2>/dev/null)
        if [ "$bn" = 0 ] || [ -z "$badj" ] || [ "$badj" -lt "$ba" ] 2>/dev/null; then
            while read -r bt bo; do [ "$bo" = - ] && continue; [ -d /proc/$bp/task/$bt ] && renice "$bo" -p "$bt" >/dev/null 2>&1; done < "$f" 2>/dev/null
            # 降着的时候新开的线程继承了 BG_NICE，没有记录：仅在 bset != 10 时放回 0。
            # Android 后台线程池显式设 THREAD_PRIORITY_BACKGROUND（nice 10）：若 bset == 10 则无法区分，不误提至 0。
            if [ "$bset" != 10 ]; then
                for tt in /proc/$bp/task/[0-9]*; do
                    [ -f "$f" ] || break
                    bt=${tt##*/}; grep -q "^$bt " "$f" 2>/dev/null && continue
                    { read -r ts < $tt/stat; } 2>/dev/null || continue
                    [ "$(echo "${ts##*) }" | awk '{print $17}')" = "$bset" ] && renice 0 -p "$bt" >/dev/null 2>&1
                done
            fi
            rm -f "$f"
        fi
    done
    [ "$bn" = 0 ] && return 0
    BG_NICE_SET=$bn
    echo "$bn" > $W/bgnice.val 2>/dev/null
    zy=
    for zp in $(pidof zygote 2>/dev/null) $(pidof zygote64 2>/dev/null) $(pidof app_process32 2>/dev/null) $(pidof app_process 2>/dev/null); do
        zc=$(tr '\000' ' ' 2>/dev/null < /proc/$zp/cmdline) || continue
        case " $zc " in *" zygote "*|*" zygote64 "*|*" --zygote "*)
            case " $zy " in *" $zp "*) ;; *) zy="$zy $zp" ;; esac ;;
        esac
    done
    if [ -z "$zy" ]; then
        for zd in /proc/[0-9]*; do
            zc=$(tr '\000' ' ' 2>/dev/null < $zd/cmdline) || continue
            case " $zc " in *" zygote "*|*" zygote64 "*|*" --zygote "*) zy="$zy ${zd#/proc/}" ;; esac
        done
    fi
    [ -n "$zy" ] || return 0
    for d in /proc/[0-9]*; do
        bp=${d#/proc/}
        [ -f $W/bgnice/$bp ] && continue
        { read -r bs < $d/stat; } 2>/dev/null || continue
        bpp=${bs##*) }; bpp=${bpp#* }; bpp=${bpp%% *}     # 第 4 个字段 ppid（命令名里可能有空格，从 ") " 后面数）
        case " $zy " in *" $bpp "*) ;; *) continue ;; esac
        badj=$(cat $d/oom_score_adj 2>/dev/null)
        [ -n "$badj" ] && [ "$badj" -ge "$ba" ] 2>/dev/null || continue
        : > $W/bgnice/$bp.tmp
        for tt in $d/task/[0-9]*; do
            bt=${tt##*/}
            { read -r ts < $tt/stat; } 2>/dev/null || continue
            bo=$(echo "${ts##*) }" | awk '{print $17}')    # nice（stat 的第 19 个字段）
            case "$bo" in ''|*[!0-9-]*) continue ;; esac
            [ "$bo" -ge "$bn" ] 2>/dev/null && { echo "$bt -" >> $W/bgnice/$bp.tmp; continue; }     # 本来就更低（后台线程 THREAD_PRIORITY_BACKGROUND = 10）：记下，放回时不动
            renice "$bn" -p "$bt" >/dev/null 2>&1 && echo "$bt $bo" >> $W/bgnice/$bp.tmp
        done
        mv -f $W/bgnice/$bp.tmp $W/bgnice/$bp
    done
    return 0
}
# 停止 / 冻结前：全部放回（BG_NICE=0 的那一次 tick）
bgnice_restore() { BG_NICE=0 bgnice_tick; rm -rf $W/bgnice $W/bgnice.val; }
