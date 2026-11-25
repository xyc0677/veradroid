# SPDX-License-Identifier: GPL-3.0-or-later
# 电量统计：
#  - 容器启动时记下电量和时间（$W/bat.start），状态里给出“自 Android 启动以来每小时掉多少电”（batdrain，%/h）；
#    充电或电量回升时重新起算。少于 10 分钟或掉电不到 1 % 时不给（数字没意义）。
#  - $SYS/battery.log：最多每 10 分钟一行“时间 电量 是否充电 状态(run/frozen/saver)”，看门狗顺手写；
#    以后桌面可以据此画按天的耗电、分 Android 前台 / 冻结 / 屏保统计。超过 64 KB 截掉前一半。
bat_level() {
    b=$(lipc-get-prop com.lab126.powerd battLevel 2>/dev/null)
    case "$b" in ''|*[!0-9]*) b=$(cat /sys/class/power_supply/*/capacity 2>/dev/null | head -1) ;; esac
    case "$b" in ''|*[!0-9]*) ;; *) [ "$b" -gt 100 ] && b=100 ;; esac
    echo "$b"
}
bat_charging() { [ "$(lipc-get-prop com.lab126.powerd isCharging 2>/dev/null)" = 1 ]; }
bat_baseline() { # 起算点：uptime 秒 电量
    b=$(bat_level); case "$b" in ''|*[!0-9]*) return 0 ;; esac
    echo "$(cut -d. -f1 /proc/uptime) $b" > $W/bat.start
}
bat_state() { if [ -f $W/saver_frozen ]; then echo saver; elif [ -f $W/frozen ]; then echo frozen; else echo run; fi; }
bat_log() {
    now=$(cut -d. -f1 /proc/uptime)
    [ $((now - $(cat $W/bat.logt 2>/dev/null || echo -9999))) -ge 600 ] || return 0
    echo $now > $W/bat.logt
    b=$(bat_level); case "$b" in ''|*[!0-9]*) return 0 ;; esac
    c=0; bat_charging && c=1
    f=$SYS/battery.log
    [ "$( { wc -c < $f; } 2>/dev/null || echo 0)" -gt 65536 ] && { tail -c 32768 $f > $f.tmp 2>/dev/null && mv -f $f.tmp $f; }
    echo "$(date +%s) $b $c $(bat_state)" >> $f
    # 充电或者电量回升：起算点重设
    set -- $(cat $W/bat.start 2>/dev/null)
    if [ $c = 1 ] || [ -z "$2" ] || [ "$b" -gt "$2" ]; then bat_baseline; fi
}
bat_status_lines() { # status_write 调用
    b=$(bat_level); set -- $(cat $W/bat.start 2>/dev/null)
    [ -n "$2" ] || return 0
    now=$(cut -d. -f1 /proc/uptime); el=$((now - $1))
    echo "batstart=$2"; echo "batmins=$((el / 60))"
    case "$b" in ''|*[!0-9]*) return 0 ;; esac
    if bat_charging || [ "$b" -gt "$2" ]; then bat_baseline; return 0; fi
    [ $el -ge 600 ] && [ $(($2 - b)) -ge 1 ] && awk -v d=$(($2 - b)) -v s=$el 'BEGIN { printf "batdrain=%.1f\n", d * 3600 / s }'
    return 0
}
