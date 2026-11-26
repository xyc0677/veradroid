# SPDX-License-Identifier: GPL-3.0-or-later
# 外挂内核模块：PW4 / Oasis 3（4.1.15-lab126）的内核没有 binder、tun、uinput。
# 整包里按内核版本放预编的模块：$VD/kmod/<uname -r>/{binder,tun,uinput}.ko（tools/kmod/build.sh 编，
# 在 eink-emulator 里加载验证过才放进包）。设备节点已经存在的就不加载；模块不会卸载（下次启动直接复用）。
kmod_load() {
    d=$VD/kmod/$(uname -r)
    [ -d "$d" ] || return 0
    for m in tun uinput binder; do
        [ -f "$d/$m.ko" ] || continue
        grep -q "^$m " /proc/modules && continue
        # 内核里已经有这个设备就不加载。按 /proc/misc 判断，不看 /dev 里的节点：
        # 预建或 mknod 残留的空壳节点在模块未加载时仍存在，看节点会跳过 insmod 报 ENODEV。
        case $m in
            binder) grep -qw vndbinder /proc/misc 2>/dev/null && continue ;;
            tun)    grep -qw tun /proc/misc 2>/dev/null && continue ;;
            uinput) grep -qw uinput /proc/misc 2>/dev/null && continue ;;
        esac
        if insmod "$d/$m.ko" 2>>$LOG; then t "kmod: loaded $m"; else t "kmod: insmod $m failed"; fi
    done
    # 没有 devtmpfs 自动建节点时，按 /proc/misc 里的次设备号补建（misc 主设备号 10）
    for n in vndbinder:vndbinder tun:net/tun uinput:uinput; do
        name=${n%%:*}; node=/dev/${n#*:}
        [ -e $node ] && continue
        minor=$(awk -v m=$name '$2==m{print $1}' /proc/misc 2>/dev/null)
        [ -n "$minor" ] || continue
        mkdir -p "$(dirname $node)"; mknod $node c 10 $minor && t "kmod: mknod $node"
    done
    return 0
}
