# SPDX-License-Identifier: GPL-3.0-or-later
# 外挂内核模块：PW4 / Oasis 2 / Oasis 3（4.1.15-lab126）的内核没有 binder、tun、uinput，5.13–5.15 固件还没有 zram。
# 整包里按内核版本放预编的模块：$VD/kmod/<uname -r>/{binder,tun,uinput,zsmalloc,zram}.ko。
# 按 /proc/misc 判断设备在不在（下面 mknod 补建的 /dev/uinput 在模块卸掉后还在，不能只看节点）。
kmod_load() {
    d=$VD/kmod/$(uname -r)
    [ -d "$d" ] || return 0
    for m in tun uinput binder vdblk; do
        [ -f "$d/$m.ko" ] || continue
        grep -q "^$m " /proc/modules && continue
        case $m in
            binder) grep -qw vndbinder /proc/misc && continue ;;
            tun)    grep -qw tun /proc/misc && continue ;;
            uinput) grep -qw uinput /proc/misc && continue ;;
        esac
        if insmod "$d/$m.ko" 2>>$LOG; then t "kmod: loaded $m"; else t "kmod: insmod $m failed"; fi
    done
    if [ "${ZRAM_MB:-0}" -gt 0 ] 2>/dev/null && [ ! -e /sys/block/zram0 ] && [ -f "$d/zram.ko" ]; then
        if [ -f "$d/zsmalloc.ko" ] && ! grep -q "^zsmalloc " /proc/modules; then
            insmod "$d/zsmalloc.ko" 2>>$LOG && t "kmod: loaded zsmalloc" || t "kmod: insmod zsmalloc failed"
        fi
        if ! grep -q "^zram " /proc/modules; then
            insmod "$d/zram.ko" num_devices=1 2>>$LOG && t "kmod: loaded zram" || t "kmod: insmod zram failed"
        fi
    fi
    # 没有 devtmpfs 自动建节点时，按 /proc/misc 里的次设备号补建（misc 主设备号 10）
    for n in vndbinder:vndbinder tun:net/tun uinput:uinput; do
        name=${n%%:*}; node=/dev/${n#*:}
        if [ ! -e $node ]; then
            minor=$(awk -v m=$name '$2==m{print $1}' /proc/misc 2>/dev/null)
            [ -n "$minor" ] && mkdir -p "$(dirname $node)" && mknod -m 666 $node c 10 $minor && t "kmod: mknod $node"
        fi
    done
    if [ ! -e /dev/vdblk-ctl ]; then
        minor=$(awk '$2=="vdblk-ctl"{print $1}' /proc/misc 2>/dev/null)
        [ -n "$minor" ] && mknod -m 600 /dev/vdblk-ctl c 10 "$minor" && t "kmod: mknod /dev/vdblk-ctl"
    fi
    chmod 600 /dev/vdblk-ctl 2>/dev/null || true
    chmod 666 /dev/vndbinder /dev/uinput /dev/input/uinput 2>/dev/null || true
    return 0
}
