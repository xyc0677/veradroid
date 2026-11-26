# SPDX-License-Identifier: GPL-3.0-or-later
# 时区和 12 / 24 小时制跟 Kindle 一致（调查结果见 docs/host-settings.md “时区”一节）。
#
# Kindle 这边（5.19 固件，pw6-5195 / kt6-5192 模拟器上查的）：
#  - /etc/localtime -> /var/local/system/tz：用户在“设置 → 设备时间”里选了时区以后，这里是一个 TZif 文件；
#    没选过时不存在（= UTC）。Kindle 上没有 /usr/share/zoneinfo 的时区文件，也没有地方存 IANA 名字（KPP 界面
#    另写一份 /var/local/system/tzVar，内容是 KINDLE_TZ=GMT+偏移）。
#  - TZif（v2 及以上）文件末尾有一行 POSIX TZ 规则，例如 "CST-8"、"IST-5:30"、"EST5EDT,M3.2.0,M11.1.0"。
#    用它在下面的表里找时区名；表里没有就在 Android 自己的 tzdata 里找规则完全相同的时区；再没有就按偏移。
#  - 12 / 24 小时制：lipc 属性 com.lab126.kppsettings timeFormat（"12" / "24"，可读写，设置界面改了会发 timeFormatChanged）。
#
# Android 这边：开机前写 $SYS/data/property/persist.sys.timezone（init 读）；运行中（解冻时）发现 Kindle 的时区变了，
# 用 AlarmManager.setTimeZone（service call alarm 3）改，应用会收到 TIMEZONE_CHANGED。
# 12 / 24：settings put system time_12_24（开机完成后，值变了才写，每次 settings 要起一个 JVM）。
# 设置 TIME_12_24=auto|12|24（auto = 跟 Kindle）。

# POSIX 规则 -> 时区名。同一规则的地方很多（CST-8 = 上海 / 台北 / 澳门…），选一个常用的就行：时间是一样的。
tz_from_rule() {
    case "$1" in
        CST-8) echo Asia/Shanghai ;;
        HKT-8) echo Asia/Hong_Kong ;;
        JST-9) echo Asia/Tokyo ;;
        KST-9) echo Asia/Seoul ;;
        IST-5:30) echo Asia/Kolkata ;;
        "<+0530>-5:30") echo Asia/Colombo ;;
        "<+0545>-5:45") echo Asia/Kathmandu ;;
        "<+0630>-6:30") echo Asia/Yangon ;;
        "<+0430>-4:30") echo Asia/Kabul ;;
        "<+0330>-3:30"|"<+0330>-3:30<+0430>"*|IRST-3:30*) echo Asia/Tehran ;;
        PKT-5) echo Asia/Karachi ;;
        "<+05>-5") echo Asia/Tashkent ;;
        "<+06>-6") echo Asia/Dhaka ;;
        "<+07>-7"|ICT-7) echo Asia/Bangkok ;;
        WIB-7) echo Asia/Jakarta ;;
        "<+08>-8"|SGT-8|MYT-8) echo Asia/Singapore ;;
        PST-8) echo Asia/Manila ;;
        "<+04>-4"|GST-4) echo Asia/Dubai ;;
        "<+03>-3"|AST-3) echo Asia/Riyadh ;;
        MSK-3) echo Europe/Moscow ;;
        IST-2IDT*) echo Asia/Jerusalem ;;
        EET-2EEST,M3.5.0/3,M10.5.0/4) echo Europe/Helsinki ;;
        EET-2) echo Africa/Cairo ;;
        CET-1CEST,M3.5.0,M10.5.0/3) echo Europe/Berlin ;;
        CET-1) echo Africa/Algiers ;;
        WAT-1) echo Africa/Lagos ;;
        SAST-2) echo Africa/Johannesburg ;;
        EAT-3) echo Africa/Nairobi ;;
        GMT0BST,M3.5.0/1,M10.5.0) echo Europe/London ;;
        IST-1GMT0,M10.5.0,M3.5.0/1) echo Europe/Dublin ;;
        WET0WEST,M3.5.0/1,M10.5.0) echo Europe/Lisbon ;;
        GMT0) echo Etc/GMT ;;
        UTC0) echo Etc/UTC ;;
        EST5EDT,M3.2.0,M11.1.0) echo America/New_York ;;
        CST6CDT,M3.2.0,M11.1.0) echo America/Chicago ;;
        MST7MDT,M3.2.0,M11.1.0) echo America/Denver ;;
        MST7) echo America/Phoenix ;;
        PST8PDT,M3.2.0,M11.1.0) echo America/Los_Angeles ;;
        AKST9AKDT,M3.2.0,M11.1.0) echo America/Anchorage ;;
        HST10) echo Pacific/Honolulu ;;
        AST4ADT,M3.2.0,M11.1.0) echo America/Halifax ;;
        NST3:30NDT,M3.2.0,M11.1.0) echo America/St_Johns ;;
        CST6) echo America/Mexico_City ;;
        EST5) echo America/Cancun ;;
        "<-03>3") echo America/Sao_Paulo ;;
        "<-04>4") echo America/Caracas ;;
        "<-05>5") echo America/Bogota ;;
        AEST-10AEDT,M10.1.0,M4.1.0/3) echo Australia/Sydney ;;
        AEST-10) echo Australia/Brisbane ;;
        ACST-9:30ACDT,M10.1.0,M4.1.0/3) echo Australia/Adelaide ;;
        ACST-9:30) echo Australia/Darwin ;;
        AWST-8) echo Australia/Perth ;;
        "<+0845>-8:45") echo Australia/Eucla ;;
        NZST-12NZDT,M9.5.0,M4.1.0/3) echo Pacific/Auckland ;;
        "<+1245>-12:45<+1345>"*) echo Pacific/Chatham ;;
        "<-0930>9:30") echo Pacific/Marquesas ;;
        *) return 1 ;;
    esac
}
# 当前偏移 -> 时区名（文件里没有规则时的退路）。非整点的用真实地区（没有 Etc/GMT+5:30 这种名字），整点用 Etc/GMT∓N。
tz_from_offset() { # +0530
    case "$1" in
        +0530) echo Asia/Kolkata ;; +0545) echo Asia/Kathmandu ;; +0630) echo Asia/Yangon ;;
        +0430) echo Asia/Kabul ;; +0330) echo Asia/Tehran ;; +0930) echo Australia/Darwin ;;
        +1030) echo Australia/Adelaide ;; +0845) echo Australia/Eucla ;; +1245|+1345) echo Pacific/Chatham ;;
        -0330) echo America/St_Johns ;; -0230) echo America/St_Johns ;; -0930) echo Pacific/Marquesas ;;
        [+-][0-9][0-9]00)
            sg=${1%${1#?}}; hh=${1#?}; hh=${hh%??}; hh=${hh#0}; [ -n "$hh" ] || hh=0
            [ "$hh" = 0 ] && { echo Etc/UTC; return 0; }
            [ "$sg" = "+" ] && echo "Etc/GMT-$hh" || echo "Etc/GMT+$hh" ;;
        *) return 1 ;;
    esac
}
# Kindle 时区文件末尾的 POSIX 规则（TZif v2+ 最后一行）
tz_kindle_rule() {
    f=$(readlink -f /etc/localtime 2>/dev/null); [ -f "$f" ] || f=/var/local/system/tz
    [ -f "$f" ] || return 1
    [ "$(dd if="$f" bs=4 count=1 2>/dev/null)" = TZif ] || return 1
    # 只有 v2 以上的文件末尾才有 POSIX 规则行。v1 文件（PW6 5.19.5 真机、5.17 的 KT6）末尾是时区缩写和标志字节，
    # 照样切出来会得到 "GMT+8:0100" 这种假规则（用户日志里 date 打印的就是这个缩写），白白在后台扫一遍 tzdata。
    case "$(dd if="$f" bs=1 skip=4 count=1 2>/dev/null)" in [2-9]) ;; *) return 1 ;; esac
    tail -c 64 "$f" 2>/dev/null | tr '\0' '\n' | sed '/^$/d' | tail -n 1 | tr -cd 'A-Za-z0-9<>+:,./-'
}
TZDATA=$R/system/usr/share/zoneinfo/tzdata
# 名字在 Android 的 tzdata 里有没有（2016g，新一点的名字可能没有）
tz_known() { [ -f "$TZDATA" ] || return 0; grep -q "$1" "$TZDATA" 2>/dev/null; }
# 在 Android tzdata 里找规则完全相同的时区（表里没有的少见时区）。tzdata 格式：12 字节版本 + 三个 32 位大端偏移
# （索引、数据、zone.tab），索引每项 52 字节：名字 40 + 偏移 4 + 长度 4 + 原始偏移 4。只看每个时区数据的最后 64 字节。
tz_search_android() { # rule
    [ -f "$TZDATA" ] || return 1
    hex=$(dd if="$TZDATA" bs=1 skip=12 count=12 2>/dev/null | od -An -tx1 | tr -d ' \n')
    io=$((0x${hex%????????????????})); rest=${hex#????????}; dofs=$((0x${rest%????????})); n=$(( (dofs - io) / 52 ))
    [ $n -gt 0 ] && [ $n -lt 1000 ] || return 1
    want=$1
    dd if="$TZDATA" bs=4 skip=$((io / 4)) count=$((n * 13)) 2>/dev/null | od -An -v -tx1 | awk '{ for (i = 1; i <= NF; i++) { printf "%s%s", (c ? " " : ""), $i; if (++c == 52) { printf "\n"; c = 0 } } }' | while read -r l; do
        set -- $l
        [ $# -ge 48 ] || continue
        off=$(eval "echo \$((0x\${41}\${42}\${43}\${44}))"); len=$(eval "echo \$((0x\${45}\${46}\${47}\${48}))")
        [ "$len" -gt 64 ] || continue
        r=$(dd if="$TZDATA" bs=1 skip=$((dofs + off + len - 64)) count=64 2>/dev/null | tr '\0' '\n' | sed '/^$/d' | tail -n 1 | tr -cd 'A-Za-z0-9<>+:,./-')
        [ "$r" = "$want" ] || continue
        nm=; k=0
        for b in "$@"; do k=$((k + 1)); [ $k -gt 40 ] || [ "$b" = 00 ] && break; nm="$nm\\x$b"; done
        printf "$nm\n"; break
    done
}
# Kindle 现在的时区 -> Android 时区名。$1=search：表里没有的规则当场扫 tzdata（模拟器上约 30 秒，所以只在后台做，
# 结果缓存在 $SYS/tzcache；开机路径上先按偏移，扫完后 tz_sync_live 再改成准确的名字）
# 真机（PW6 5.19.5）上 tzVar 里是时区名本身（KINDLE_TZ=Asia/Tokyo），时区文件是没有 POSIX 规则的 TZif v1：
# 有地区名就直接用（模拟器上这里只有 GMT 偏移，那时继续走下面的规则 / 偏移）。
tz_kindle_name() {
    n=$(sed -n 's/^KINDLE_TZ=\([A-Za-z_]*\/[A-Za-z_/+-]*\)$/\1/p' /var/local/system/tzVar 2>/dev/null | head -1)
    [ -n "$n" ] && tz_known "$n" && echo "$n"
}
tz_android_name() {
    z=$(tz_kindle_name) && [ -n "$z" ] && { echo "$z"; return 0; }
    rule=$(tz_kindle_rule)
    if [ -n "$rule" ]; then
        z=$(tz_from_rule "$rule") && tz_known "$z" && { echo "$z"; return 0; }
        c=$(awk -v r="$rule" -F '\t' '$1 == r { print $2; exit }' $SYS/tzcache 2>/dev/null)
        [ -n "$c" ] && { echo "$c"; return 0; }
        if [ "$1" = search ]; then
            z=$(tz_search_android "$rule")
            [ -n "$z" ] && { printf '%s\t%s\n' "$rule" "$z" >> $SYS/tzcache; echo "$z"; return 0; }
            printf '%s\t%s\n' "$rule" "$(tz_from_offset "$(date +%z)")" >> $SYS/tzcache   # 找不到也记下，不再扫
        else
            touch $W/tz.search
        fi
    fi
    tz_from_offset "$(date +%z)"
}
# 开机前：写 persist.sys.timezone（init 从 /data/property 读）
tz_prepare() {
    z=$(tz_android_name); [ -n "$z" ] || z=Etc/UTC
    mkdir -p $SYS/data/property
    # 曾在 PW5 模拟器上发现它被建成了目录（写入报 Is a directory）：空目录就删掉，非空则只记日志
    [ -d $SYS/data/property/persist.sys.timezone ] && { rmdir $SYS/data/property/persist.sys.timezone 2>/dev/null || t "persist.sys.timezone is a non-empty dir"; }
    if [ ! -d $SYS/data/property/persist.sys.timezone ]; then
        printf '%s' "$z" > $SYS/data/property/persist.sys.timezone && chmod 600 $SYS/data/property/persist.sys.timezone
    fi
    echo "$z" > $W/tz.android
    t "timezone: kindle rule '$(tz_kindle_rule)' offset $(date +%z) -> $z"
    # 规则不在表里：后台扫 Android 的 tzdata，开机完成后（after_boot 调 tz_sync_live）换成准确的名字
    [ -f $W/tz.search ] && { rm -f $W/tz.search; ( tz_android_name search >/dev/null ) </dev/null >/dev/null 2>&1 & }
    return 0
}
# 运行中（解冻时）：Kindle 的时区变了就告诉 Android（AlarmManager.setTimeZone，7.1 的事务号 3）
tz_sync_live() {
    z=$(tz_android_name); [ -n "$z" ] || return 0
    [ "$z" = "$(cat $W/tz.android 2>/dev/null)" ] && return 0
    adb_sh_t 20 "service call alarm 3 s16 $z" >/dev/null 2>&1 && { echo "$z" > $W/tz.android; t "timezone changed on the Kindle -> $z"; }
}
# 12 / 24 小时制：auto 时读 Kindle；开机完成后调用
time_format_apply() {
    case "${TIME_12_24:-auto}" in
        12|24) v=$TIME_12_24 ;;
        *) v=$(lipc-get-prop com.lab126.kppsettings timeFormat 2>/dev/null); case "$v" in 12|24) ;; *) return 0 ;; esac ;;
    esac
    [ "$(cat $SYS/data/.vd-time1224 2>/dev/null)" = "$v" ] && return 0
    adb_sh_t 20 "settings put system time_12_24 $v" >/dev/null 2>&1 && echo "$v" > $SYS/data/.vd-time1224 && t "time format: ${v}h (${TIME_12_24:-auto})"
}
