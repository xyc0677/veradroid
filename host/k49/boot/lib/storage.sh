# SPDX-License-Identifier: GPL-3.0-or-later
# 系统盘大小可调（docs/host-settings.md “存储空间”）。由 common.sh 在第一次 storage_mount 之前 source。
#
# 只对“系统盘是一个镜像文件”的机型有意义（用户分区是 FAT 或 fscrypt 加密的：PW5 / KT5 / KT6 / Scribe，以及 3.0 内核的老机型）。
# PW6 上 Android 直接用 Kindle 的剩余空间，没有镜像：st_info 只报 mode=shared 和剩余空间，别的命令一律拒绝。
#
#   扩大：ext4 + 镜像正挂着 → 在线做（加长文件、losetup -c 让 loop 设备重新读大小、resize2fs 让内核在线扩，有日志保护，
#         Android 不用停）。做不了在线（ext3、没有 losetup -c、在线失败）→ 离线。
#   缩小：只能离线：停 Android → e2fsck -f → resize2fs 到目标 → 超级块确认文件系统已经不超过目标 → 才截短文件 → 再 e2fsck。
#   任何一步失败都不截短文件：文件系统还是原来（或已缩好）的大小，重新挂上、照常开机，结果写进 veradroid/state/resize.last。
#   断电 / 被杀：镜像外面有“进行中”标记（veradroid/state/resize.state）。下次 storage_mount 先完整 e2fsck，再看超级块：
#         缩小已经做完 → 把文件截短（完成）；没做完 → 文件系统仍是原大小，文件没动过（回退）。扩大 → 把文件系统补到文件大小。
#   至少给 Kindle 留 ST_KEEP MB；用户分区是 FAT 时单个文件最大 4 GB - 1。
#   自动扩容（STORAGE_AUTO=1，默认开）：镜像里剩余不到 ST_LOW MB、Kindle 还有地方，就扩 ST_STEP MB（看门狗每 20 秒看一次）。
ST_STATE=/mnt/base-us/veradroid/state/resize.state       # 进行中标记：op= from= to= phase=
ST_PENDING=/mnt/base-us/veradroid/state/resize.pending   # 下次启动时扩到多少 MB（在线扩不了时自动扩容留下的）
ST_LAST=/mnt/base-us/veradroid/state/resize.last         # 上一次的结果（桌面 / 网页显示）
ST_NOTIFY=/mnt/base-us/veradroid/state/resize.notify     # 还没告诉用户的一句话（开机后看门狗转给桌面）
ST_LOG=/mnt/base-us/veradroid/logs/resize.log
ST_LOCK=/dev/shm/veradroid/resize.lock             # 正在调整（tmpfs：重启就没了，标记还在）
ST_KEEP=256          # 至少给 Kindle 留这么多 MB
ST_LOW=320           # 镜像里可用空间低于这么多 MB 就自动扩
ST_STEP=512          # 自动扩容一次扩多少 MB
ST_FATCAP=4095       # FAT 上单个文件的上限（4 GB - 1 字节）
ST_HARDMAX=32768     # 再大没有意义
ST_MINIMUM=768       # 不管用了多少，最小也不低于这个

st_log() { echo "[$(date '+%F %T')] $*" >> $ST_LOG 2>/dev/null; type t >/dev/null 2>&1 && t "storage: $*" >&2; return 0; }
# 工具：固件自带的优先（和固件的内核、文件系统特性是一套），没有才用整包里带的静态版
st_tool() {
    [ -x "$VD/bin/$1" ] && { echo "$VD/bin/$1"; return 0; }
    command -v "$1" 2>/dev/null && return 0
    return 1
}
st_num() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ ${#1} -le 6 ]; }
st_img_mb() { stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1048576 }'; }
st_img_kb() { stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1024 }'; }
st_alloc_mb() { du -k "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1024 }'; }   # 真正占掉的（ext4 上镜像是稀疏文件）
st_kfs() { stat -f -c %T /mnt/base-us 2>/dev/null; }
st_isfat() { case "$(st_kfs)" in vfat|msdos|fat) return 0 ;; esac; return 1; }
st_mounted() { grep -q " $SYS " /proc/mounts; }
# 超级块里的一个 32 位小端字段（偏移从超级块开头算）。直接读镜像文件，不需要 dumpe2fs。
st_sb() { dd if="$SYSTEM_IMG" bs=1 skip=$((1024 + $1)) count=4 2>/dev/null | od -An -tu4 2>/dev/null | tr -d ' \n'; }
# 文件系统自己认为的大小（KB）。读不出来（不是 ext2/3/4）就什么都不输出。
st_fs_kb() {
    st_mounted || { type img_drop_file_cache >/dev/null 2>&1 && img_drop_file_cache; }
    magic=$(dd if="$SYSTEM_IMG" bs=1 skip=1080 count=2 2>/dev/null | od -An -tu2 2>/dev/null | tr -d ' \n')
    [ "$magic" = 61267 ] || return 1                     # 0xEF53
    b=$(st_sb 4); l=$(st_sb 24)
    case "$b" in ''|*[!0-9]*) return 1 ;; esac
    [ "${#b}" -le 10 ] || return 1
    case "$l" in 0|1|2|3|4|5|6) ;; *) return 1 ;; esac
    awk -v b="$b" -v l="$l" 'BEGIN { printf "%d", b * (2 ^ l) }'
}
st_fstype() { awk -v m="$SYS" '$2 == m { print $3 }' /proc/mounts | tail -1; }
st_battery_ok() {   # 离线调整要几分钟，中途没电最伤：电量不到 15% 又没在充电就不做
    command -v lipc-get-prop >/dev/null 2>&1 || return 0
    b=$(lipc-get-prop com.lab126.powerd battLevel 2>/dev/null); c=$(lipc-get-prop com.lab126.powerd isCharging 2>/dev/null)
    st_num "$b" || return 0
    [ "$b" -ge 15 ] || [ "$c" = 1 ]
}
st_auto_on() { ! grep -q '^STORAGE_AUTO=0' "$SYS/settings.conf" 2>/dev/null; }
# 能不能在线扩：ext4 正挂在唯一的一个 loop 设备上、有 resize2fs、losetup 认识 -c
st_online_ok() {
    case "$(uname -r)" in 3.*|4.1.*) return 1 ;; esac
    [ "$(st_fstype)" = ext4 ] || return 1
    [ "$(img_loops | wc -l)" = 1 ] || return 1
    st_tool resize2fs >/dev/null || return 1
    losetup --help 2>&1 | grep -q -e '-c' || return 1
}

# 算一遍现在的数字，放进 ST_* 变量（单位 MB）
st_calc() {
    ST_IMG=$(st_img_mb); ST_ALLOC=$(st_alloc_mb)
    set -- $(df -kP /mnt/base-us 2>/dev/null | awk 'NR == 2 { printf "%d %d", $2 / 1024, $4 / 1024 }')
    ST_KTOTAL=${1:-0}; ST_KFREE=${2:-0}
    if st_mounted; then
        set -- $(df -kP "$SYS" 2>/dev/null | awk 'NR == 2 { printf "%d %d %d", $2 / 1024, $3 / 1024, $4 / 1024 }')
        ST_FS_TOTAL=${1:-0}; ST_FS_USED=${2:-0}; ST_FS_FREE=${3:-0}
    else
        # 没挂着：超级块里的总块数和空闲块数（偏移 12）
        ST_FS_TOTAL=$(( $(st_fs_kb || echo 0) / 1024 ))
        fb=$(st_sb 12); l=$(st_sb 24)
        ST_FS_FREE=$(awk -v b="${fb:-0}" -v l="${l:-2}" 'BEGIN { printf "%d", b * (2 ^ l) / 1024 }')
        ST_FS_USED=$((ST_FS_TOTAL - ST_FS_FREE)); [ $ST_FS_USED -ge 0 ] || ST_FS_USED=0
    fi
    # 最小：已用 + 元数据（inode 表约 2%、日志最多 128 MB）+ 200 MB 余量，向上取到 64 MB 的整数倍
    ST_MIN=$(( (ST_FS_USED * 103 / 100 + 128 + 200 + 63) / 64 * 64 ))
    [ $ST_MIN -ge $ST_MINIMUM ] || ST_MIN=$ST_MINIMUM
    [ $ST_MIN -le $ST_IMG ] || ST_MIN=$ST_IMG
    # 最大：镜像真正占着的 + Kindle 剩的 - 留给 Kindle 的。FAT 再封顶 4 GB。
    ST_MAX=$((ST_ALLOC + ST_KFREE - ST_KEEP)); ST_CAP=space
    if st_isfat && [ $ST_MAX -ge $ST_FATCAP ]; then ST_MAX=$ST_FATCAP; ST_CAP=fat; fi
    [ $ST_MAX -le $ST_HARDMAX ] || { ST_MAX=$ST_HARDMAX; ST_CAP=hard; }
    [ $ST_MAX -ge $ST_IMG ] || ST_MAX=$ST_IMG
}

# 状态：一行一个 st.键=值（MB）。桌面（storage info → $X/ctl/storage）和管理网页（sysinfo / storage）都用它。
st_info() {
    if [ ! -f "$SYSTEM_IMG" ]; then
        echo "st.mode=shared"
        df -kP /mnt/base-us 2>/dev/null | awk 'NR == 2 { printf "st.kindle_total=%d\nst.kindle_used=%d\nst.kindle_free=%d\n", $2 / 1024, $3 / 1024, $4 / 1024 }'
        echo "st.kfs=$(st_kfs | tr -cd 'a-z0-9/')"
        return 0
    fi
    st_calc
    echo "st.mode=image"
    echo "st.image=$ST_IMG"; echo "st.alloc=$ST_ALLOC"
    echo "st.total=$ST_FS_TOTAL"; echo "st.used=$ST_FS_USED"; echo "st.free=$ST_FS_FREE"
    echo "st.kindle_total=$ST_KTOTAL"; echo "st.kindle_free=$ST_KFREE"
    echo "st.min=$ST_MIN"; echo "st.max=$ST_MAX"; echo "st.cap=$ST_CAP"; echo "st.keep=$ST_KEEP"
    echo "st.kfs=$(st_kfs | tr -cd 'a-z0-9/')"; echo "st.fs=$(st_fstype | tr -cd 'a-z0-9')"
    st_online_ok && echo "st.online=1" || echo "st.online=0"
    st_auto_on && echo "st.auto=1" || echo "st.auto=0"
    [ -d $ST_LOCK ] && echo "st.busy=1" || echo "st.busy=0"
    [ -f $ST_PENDING ] && echo "st.pending=$(head -c 8 $ST_PENDING | tr -cd '0-9')"
    [ -f $ST_LAST ] && sed -n 's/^\(time\|op\|from\|to\|result\|reason\)=\([A-Za-z0-9 :._-]*\)$/st.last.\1=\2/p' $ST_LAST
    return 0
}
st_last() { # result op from to [reason]
    { echo "time=$(date '+%F %T')"; echo "result=$1"; echo "op=$2"; echo "from=$3"; echo "to=$4"; echo "reason=$5"; } > $ST_LAST.tmp 2>/dev/null && mv -f $ST_LAST.tmp $ST_LAST
    st_log "result: $1 $2 $3 -> $4 $5"
}
st_notify() { echo "$*" > $ST_NOTIFY.tmp 2>/dev/null && mv -f $ST_NOTIFY.tmp $ST_NOTIFY; st_notify_flush; }
st_notify_last() { st_notify "result $(sed -n 's/^result=//p' $ST_LAST | tr -cd 'a-z') $(sed -n 's/^op=//p' $ST_LAST | tr -cd 'a-z') $(sed -n 's/^from=//p' $ST_LAST | tr -cd '0-9') $(sed -n 's/^to=//p' $ST_LAST | tr -cd '0-9')"; }
# 桌面在的时候（$X/ctl 在、Android 开完机）把那句话交给它（HostService 看 ctl/storage.notice），交完就删
st_notify_flush() {
    [ -f $ST_NOTIFY ] && [ -d $X/ctl ] && [ -f $W/booted ] || return 0
    cp $ST_NOTIFY $X/ctl/storage.notice.tmp 2>/dev/null && chmod 644 $X/ctl/storage.notice.tmp && mv -f $X/ctl/storage.notice.tmp $X/ctl/storage.notice && rm -f $ST_NOTIFY
}

# 目标大小行不行。行就什么都不输出；不行输出原因：nan same small nospace fatlimit toobig
st_check() { # MB（先 st_calc）
    st_num "$1" || { echo nan; return 1; }
    d=$(($1 - ST_IMG)); [ $d -lt 0 ] && d=$((-d))
    [ $d -ge 16 ] || { echo same; return 1; }
    if [ "$1" -lt "$ST_IMG" ]; then
        [ "$1" -ge "$ST_MIN" ] || { echo small; return 1; }
        return 0
    fi
    if st_isfat && [ "$1" -gt $ST_FATCAP ]; then echo fatlimit; return 1; fi
    [ "$1" -le $ST_HARDMAX ] || { echo toobig; return 1; }
    [ "$1" -le "$ST_MAX" ] || { echo nospace; return 1; }
    return 0
}

# 把镜像文件加长到 MB。FAT 没有稀疏文件：内核要真的写零，512 MB 要几十秒。
st_extend() {
    cur=$(st_img_mb); [ "$1" -gt "$cur" ] || return 0
    truncate -s "${1}M" "$SYSTEM_IMG" 2>/dev/null
    [ "$(st_img_mb)" = "$1" ] && return 0
    cur=$(st_img_mb)
    [ "$1" -gt "$cur" ] && dd if=/dev/zero of="$SYSTEM_IMG" bs=1M seek=$cur count=$(($1 - cur)) conv=notrunc 2>/dev/null
    [ "$(st_img_mb)" = "$1" ]
}
# 把镜像文件截短到 MB。只有超级块说文件系统不超过这个大小才动手：这是“绝不丢数据”的最后一道闸。
st_cut() {
    fk=$(st_fs_kb) || return 1
    [ -n "$fk" ] && [ "$fk" -le $(($1 * 1024)) ] || { st_log "cut to $1 MB refused: filesystem is $fk KB"; return 1; }
    [ "$(st_img_mb)" -gt "$1" ] || return 0
    truncate -s "${1}M" "$SYSTEM_IMG" 2>/dev/null || dd if=/dev/null of="$SYSTEM_IMG" bs=1M seek=$1 2>/dev/null
    [ "$(st_img_mb)" = "$1" ]
}
# 完整检查（镜像必须没挂着）。0 = 干净或已修好
st_fsck() {
    type img_drop_file_cache >/dev/null 2>&1 && img_drop_file_cache
    f=$(st_tool e2fsck) || { st_log "no e2fsck"; return 9; }
    "$f" -f -y "$SYSTEM_IMG" >> $ST_LOG 2>&1; rc=$?
    if [ $rc -ge 4 ]; then echo "-- e2fsck -f -y rc=$rc, once more" >> $ST_LOG; "$f" -f -y "$SYSTEM_IMG" >> $ST_LOG 2>&1; rc=$?; fi
    echo "-- $(date '+%F %T') e2fsck -f -y rc=$rc" >> $ST_LOG
    [ $rc -lt 4 ]
}
st_state() { printf 'op=%s\nfrom=%s\nto=%s\nphase=%s\n' "$1" "$2" "$3" "$4" > $ST_STATE.tmp && mv -f $ST_STATE.tmp $ST_STATE && sync; }
st_lock() {
    mkdir -p ${ST_LOCK%/*} 2>/dev/null
    if ! mkdir $ST_LOCK 2>/dev/null; then
        op=$(cat $ST_LOCK/pid 2>/dev/null)
        [ -n "$op" ] && kill -0 "$op" 2>/dev/null && return 1
    fi
    echo ${1:-$$} > $ST_LOCK/pid
}
st_unlock() { rm -f $ST_LOCK/pid; rmdir $ST_LOCK 2>/dev/null; return 0; }
st_locked_by_other() {
    [ -d $ST_LOCK ] || return 1
    op=$(cat $ST_LOCK/pid 2>/dev/null)
    [ "$op" = "$ST_OWNER" ] && return 1
    [ -n "$op" ] && kill -0 "$op" 2>/dev/null && return 0
    st_unlock; return 1
}

# 文件比文件系统大出 8 MB 以上：有一次扩大没做完（或在线扩失败），把文件系统补到文件大小
st_needs_fill() {
    fk=$(st_fs_kb) || return 1
    ik=$(st_img_kb); [ -n "$ik" ] && [ $((ik - fk)) -gt 8192 ]
}
st_fill() {   # 镜像没挂着、刚 e2fsck -f 过
    r=$(st_tool resize2fs) || return 1
    "$r" "$SYSTEM_IMG" >> $ST_LOG 2>&1; sf_rc=$?
    sync; type img_drop_file_cache >/dev/null 2>&1 && img_drop_file_cache
    return $sf_rc
}

# storage_mount 在挂载前调：把上次没做完的调整收尾。返回 1 = 另一个进程正在调整，现在不能挂。
st_recover() {
    [ -f "$SYSTEM_IMG" ] || return 0
    st_locked_by_other && return 1
    mkdir -p /mnt/base-us/veradroid/state /mnt/base-us/veradroid/logs 2>/dev/null
    for st_f in resize.state resize.pending; do   # root-legacy: 老版本记在根目录的“进行中 / 待扩容”标记
        [ -f /mnt/base-us/veradroid-$st_f ] && [ ! -e /mnt/base-us/veradroid/state/$st_f ] && mv -f /mnt/base-us/veradroid-$st_f /mnt/base-us/veradroid/state/$st_f   # root-legacy
    done
    [ -f $ST_STATE ] || [ -f $ST_PENDING ] || return 0
    [ -n "$ST_OWNER" ] && return 0                     # 调整流程自己（resize.sh）：它知道自己在做什么
    if [ -f $ST_STATE ]; then
        op=$(sed -n 's/^op=//p' $ST_STATE | tr -cd 'a-z'); from=$(sed -n 's/^from=//p' $ST_STATE | tr -cd '0-9')
        to=$(sed -n 's/^to=//p' $ST_STATE | tr -cd '0-9'); ph=$(sed -n 's/^phase=//p' $ST_STATE | tr -cd 'a-z')
        st_log "interrupted $op $from -> $to MB (phase ${ph:-?}): recovering"
        type splash >/dev/null 2>&1 && splash "VeraDroid：正在检查存储空间，请稍候…" 36
        if ! st_num "$to" || ! st_num "$from"; then
            st_fsck; rm -f $ST_STATE; st_last failed "${op:-resize}" "$from" "$to" badstate
        elif [ "$ph" = check ]; then
            rm -f $ST_STATE; st_last rolledback "$op" "$from" "$to" interrupted
        elif ! st_fsck; then
            # 修不好：不再动它，留给后面的 e2fsck -p / -y 和挂载去试；文件大小没有变过
            rm -f $ST_STATE; st_last failed "$op" "$from" "$to" fsck
        elif [ "$op" = shrink ]; then
            fk=$(st_fs_kb)
            if [ -n "$fk" ] && [ "$fk" -le $((to * 1024)) ] && st_cut "$to" && st_fsck; then
                rm -f $ST_STATE; st_last ok shrink "$from" "$to" finished-after-interruption
            else
                st_needs_fill && st_fill      # 文件系统停在中间某个大小（正常不会）：补回文件大小
                rm -f $ST_STATE; st_last rolledback shrink "$from" "$(st_img_mb)" interrupted
            fi
        else
            # 扩大：文件加长到一半就把它加完（空间还够的话），再把文件系统补上
            if [ "$(st_img_mb)" -lt "$to" ]; then
                kf=$(df -kP /mnt/base-us 2>/dev/null | awk 'NR == 2 { printf "%d", $4 / 1024 }')
                if st_isfat && [ $((to - $(st_img_mb) + ST_KEEP)) -gt "${kf:-0}" ]; then :; else st_extend "$to"; fi
            fi
            if st_needs_fill; then st_fill; st_fsck; fi
            rm -f $ST_STATE; st_last ok grow "$from" "$(( $(st_fs_kb || echo 0) / 1024 ))" finished-after-interruption
        fi
        st_notify_last
    fi
    # 自动扩容留到“下次启动”的：只在真正启动 Android 时做（关机时的 stop.sh 也会 source common.sh，那时不做）
    if [ -f $ST_PENDING ] && [ -n "$ST_START" ]; then
        to=$(head -c 8 $ST_PENDING | tr -cd '0-9'); rm -f $ST_PENDING
        st_calc
        if st_num "$to" && [ "$to" -gt "$ST_IMG" ] && [ -z "$(st_check "$to")" ] && st_tool resize2fs >/dev/null; then
            from=$ST_IMG
            st_log "pending grow $from -> $to MB"
            type splash >/dev/null 2>&1 && splash "VeraDroid：正在扩大存储空间，请稍候…" 36
            st_state grow "$from" "$to" extend
            if st_fsck && st_extend "$to" && st_fill && st_fsck; then
                st_last ok grow "$from" "$to" auto; st_notify "grown $from $to"
            else
                st_cut "$from"; st_last failed grow "$from" "$to" offline
            fi
            rm -f $ST_STATE
        fi
    fi
    return 0
}

# 在线扩大到 MB（先 st_calc + st_check）。0 = 成功
st_grow_online() {
    l=$(img_loops | head -1); [ -n "$l" ] || return 1
    dev=$(loop_dev "$l"); from=$(st_img_mb)
    r=$(st_tool resize2fs) || return 1
    st_log "online grow $from -> $1 MB on $dev"
    st_state grow "$from" "$1" extend
    if ! st_extend "$1"; then
        st_log "extend failed"; st_cut "$from"; rm -f $ST_STATE; return 2
    fi
    sync
    st_state grow "$from" "$1" resize
    if losetup -c "$dev" 2>> $ST_LOG && "$r" "$dev" >> $ST_LOG 2>&1; then
        sync; rm -f $ST_STATE; return 0
    fi
    # 内核不肯在线扩：文件已经加长了，文件系统还是原样（没有坏处）。标记留着，下次启动（或接下来的离线流程）补上。
    st_log "online resize2fs failed"
    return 3
}

# 请求调整到 MB。结果（ok=、mode=、error=）写到标准输出；cmd.sh 存成 $X/ctl/storage.result，管理网页直接拿去。
st_request() {
    [ -f "$SYSTEM_IMG" ] || { echo "ok=0"; echo "error=shared"; return 0; }
    st_num "$1" || { echo "ok=0"; echo "error=nan"; return 0; }
    [ -d $ST_LOCK ] && st_locked_by_other && { echo "ok=0"; echo "error=busy"; return 0; }
    st_tool resize2fs >/dev/null && st_tool e2fsck >/dev/null || { echo "ok=0"; echo "error=notool"; return 0; }
    st_calc
    e=$(st_check "$1")
    if [ -n "$e" ]; then
        echo "ok=0"; echo "error=$e"; echo "min=$ST_MIN"; echo "max=$ST_MAX"; echo "image=$ST_IMG"
        st_log "resize $ST_IMG -> $1 MB refused: $e (min $ST_MIN max $ST_MAX, used $ST_FS_USED, kindle free $ST_KFREE)"
        return 0
    fi
    from=$ST_IMG
    if [ "$1" -gt "$from" ] && st_online_ok; then
        st_lock || { echo "ok=0"; echo "error=busy"; return 0; }
        if st_grow_online "$1"; then
            st_unlock; st_last ok grow "$from" "$1" online
            echo "ok=1"; echo "mode=online"; echo "image=$1"
            return 0
        fi
        st_unlock
        # 文件没能加长（多半是空间）：到此为止。文件加长了但内核不肯扩：走离线把它补上。
        if [ "$(st_img_mb)" != "$1" ]; then
            st_last failed grow "$from" "$1" extend
            echo "ok=0"; echo "error=extend"; return 0
        fi
    fi
    st_battery_ok || { echo "ok=0"; echo "error=battery"; st_log "offline resize refused: battery low"; return 0; }
    [ -f "$VD/boot/resize.sh" ] || { echo "ok=0"; echo "error=notool"; return 0; }
    ( cd / && setsid sh "$VD/boot/resize.sh" "$1" ) </dev/null >/dev/null 2>&1 &
    echo "ok=1"; echo "mode=offline"; echo "from=$from"; echo "to=$1"
}

# 自动扩容：看门狗每个周期调一次。很便宜（一次 df）；真要扩的时候才干活。
st_auto_tick() {
    [ -f "$SYSTEM_IMG" ] || return 0
    st_notify_flush
    st_auto_on || return 0
    [ -d $ST_LOCK ] && return 0
    [ -f $ST_PENDING ] && return 0
    f=$(df -kP "$SYS" 2>/dev/null | awk 'NR == 2 { printf "%d", $4 / 1024 }')
    st_num "$f" || return 0
    [ "$f" -lt $ST_LOW ] || { rm -f $W/st.full; return 0; }
    # 刚失败过：十分钟内不再试
    if [ -f $W/st.wait ]; then
        now=$(cut -d. -f1 /proc/uptime); [ $((now - $(cat $W/st.wait 2>/dev/null || echo 0))) -lt 600 ] && return 0
        rm -f $W/st.wait
    fi
    st_tool resize2fs >/dev/null || return 0
    st_calc
    to=$((ST_IMG + ST_STEP)); [ $to -le $ST_MAX ] || to=$ST_MAX
    if [ $((to - ST_IMG)) -lt 64 ]; then
        # 扩不动了（Kindle 没地方了，或者 FAT 的 4 GB 上限）：只说一次
        [ -f $W/st.full ] || { touch $W/st.full; st_log "auto: image low ($f MB free) but cannot grow (max $ST_MAX, cap $ST_CAP)"; st_notify "full $ST_CAP"; }
        return 0
    fi
    from=$ST_IMG
    st_log "auto: $f MB free in the image, growing $from -> $to MB"
    if st_online_ok; then
        st_lock || return 0
        if st_grow_online "$to"; then
            st_unlock; st_last ok grow "$from" "$to" auto-online; st_notify "grown $from $to"
        else
            st_unlock; cut -d. -f1 /proc/uptime > $W/st.wait
            [ "$(st_img_mb)" = "$to" ] && st_notify "pending $to"      # 文件已加长：下次启动把文件系统补上
        fi
    else
        echo "$to" > $ST_PENDING; st_notify "pending $to"
    fi
}

# storage info | resize <MB> | auto on|off（cmd.sh 的 FIFO）
st_cmd() {
    case "$1" in
    info)   st_info > $X/ctl/storage.tmp 2>/dev/null && chmod 644 $X/ctl/storage.tmp && mv -f $X/ctl/storage.tmp $X/ctl/storage ;;
    resize) rm -f $X/ctl/storage.result
            st_request "$2" > $X/ctl/storage.result.tmp 2>/dev/null; chmod 644 $X/ctl/storage.result.tmp 2>/dev/null
            mv -f $X/ctl/storage.result.tmp $X/ctl/storage.result 2>/dev/null
            st_cmd info ;;
    auto)   case "$2" in on|1) v=1 ;; off|0) v=0 ;; *) return 0 ;; esac
            touch $SYS/settings.conf
            grep -v "^STORAGE_AUTO=" $SYS/settings.conf > $SYS/settings.conf.tmp 2>/dev/null
            echo "STORAGE_AUTO=$v" >> $SYS/settings.conf.tmp; mv -f $SYS/settings.conf.tmp $SYS/settings.conf
            [ $v = 0 ] && rm -f $ST_PENDING
            st_log "auto grow $v"; st_cmd info ;;
    esac
}
