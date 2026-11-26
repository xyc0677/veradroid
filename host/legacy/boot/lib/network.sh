# SPDX-License-Identifier: GPL-3.0-or-later
# 网络：Kindle 内核没有 veth，容器有自己的 net 命名空间，用 slirp4netns（用户态 NAT，官方静态二进制）经 /dev/net/tun
# 在里面造 eth0。容器内网段刻意避开常见的 10.0.2.0/24、198.18/15（Clash）、100.64/10（Tailscale）、172.17（Docker）、10.233（K8s）。
# 系统里的 Wi-Fi 是假的：真正的 Wi-Fi 连接由 Kindle 管，系统只看到一块 eth0。
NET=10.157.43
SLIRP=$VD/bin/slirp4netns

net_setup() {
    # DNS：ANDROID_DNS 在前，Kindle 当前的在后。只在 slirp 自己的 mount 命名空间里 bind，不动 Kindle 的 /etc
    { for d in $ANDROID_DNS; do echo "nameserver $d"; done
      grep '^nameserver' "$(readlink -f /etc/resolv.conf)" 2>/dev/null; } > $W/resolv.conf
    # Wi-Fi 省电模式保持开着（电池优先）：仅在 WIFI_PS=off 或传书/下载（wifi_ps_tick）时关闭，避免离线/轻度阅读白耗电
    [ "${WIFI_PS:-auto}" = off ] && wifi_ps_off
    return 0
}

# Wi-Fi 省电。设置 WIFI_PS：
#   on   = 一直保持 Kindle 自己的省电模式（省电，下载慢）
#   off  = Android 运行期间关掉省电（下载快、耗电多）；回到 Kindle / 屏保 / 停止时恢复
#   auto = 平时保持省电；传书开着（端口转发在）或 Wi-Fi 上正在大量收数据（下载、同步书）时临时关掉，
#          闲下来 1 分钟后恢复。判断只读 /sys 里网卡的收包字节数，看门狗每一轮（20 秒）看一次。
# 只恢复我们自己关掉的（$W/wifi.ps 标记）：用户或 Kindle 本来就关着省电时不去动它。
wifi_dev() { for d in wlan0 eth0; do [ -d /sys/class/net/$d ] && { echo $d; return 0; }; done; echo wlan0; }
wifi_ps_state() { # 输出 on / off / 空（读不到）
    d=$(wifi_dev)
    if command -v iw >/dev/null 2>&1; then
        s=$(iw dev $d get power_save 2>/dev/null | sed -n 's/.*[Pp]ower save: *\(o[nf]*\).*/\1/p')
        [ -n "$s" ] && { echo $s; return 0; }
    fi
    iwconfig $d 2>/dev/null | sed -n 's/.*Power Management:\(o[nf]*\).*/\1/p'
}
wifi_ps_set() { # on|off
    d=$(wifi_dev)
    if command -v iw >/dev/null 2>&1 && iw dev $d set power_save $1 2>/dev/null; then return 0; fi
    iwconfig $d power $1 2>/dev/null
}
# 运行期间关 Wi-Fi 省电（回到 Kindle 时 stop.sh / do_exit 恢复）
wifi_ps_off() {
    [ -f $W/wifi.ps ] && return 0
    [ "$(wifi_ps_state)" = on ] && wifi_ps_set off && touch $W/wifi.ps && t "wifi: power saving off (${WIFI_PS:-auto})"
    return 0
}
wifi_ps_restore() { [ -f $W/wifi.ps ] && rm -f $W/wifi.ps && wifi_ps_set on && t "wifi: power saving restored"; return 0; }
# 看门狗每轮调一次（冻结 / 屏保时不调，那时已经恢复）
wifi_ps_tick() {
    case "${WIFI_PS:-auto}" in
        on)  wifi_ps_restore ;;
        off) wifi_ps_off ;;
        *)
            d=$(wifi_dev); now=$(cut -d. -f1 /proc/uptime)
            rx=$(cat /sys/class/net/$d/statistics/rx_bytes 2>/dev/null || echo 0)
            set -- $(cat $W/wifi.rx 2>/dev/null); prx=${2:-$rx}; pt=${1:-$now}
            echo "$now $rx" > $W/wifi.rx
            busy=
            [ -f $W/fwd.id ] && busy=fwd                                  # 传书网页开着
            if [ -f $W/dl.active ]; then
                dl_t=$(cat $W/dl.active 2>/dev/null)
                case "$dl_t" in
                    ''|*[!0-9]*)
                        dl_m=$(stat -c %Y $W/dl.active 2>/dev/null || echo 0)
                        set -- $(cat $W/wifi.dl 2>/dev/null)
                        if [ "$1" = "$dl_m" ] && [ -n "$2" ]; then dl_t=$2
                        else dl_t=$now; echo "$dl_m $now" > $W/wifi.dl; fi ;;
                esac
                [ $((now - dl_t)) -ge 0 ] && [ $((now - dl_t)) -lt 35 ] && busy=dl   # 弱网下载期间也关 Wi-Fi 省电（/proc/uptime 单调秒）
            fi
            dt=$((now - pt)); [ $dt -gt 0 ] && [ $(( (rx - prx) / dt )) -ge ${WIFI_BUSY_BPS:-16384} ] && busy=rx   # ≥ 16 KB/s 下载/同步时临时关省电，避免后台心跳误唤醒射频
            if [ -n "$busy" ]; then echo $now > $W/wifi.busy; wifi_ps_off
            elif [ -f $W/wifi.ps ] && [ $((now - $(cat $W/wifi.busy 2>/dev/null || echo 0))) -ge 60 ]; then wifi_ps_restore
            fi ;;
    esac
}

# 共用宿主网络（没有 net 命名空间的内核：3.0 的 PW2/PW3，4.9 的 KT6 5.17 等，走 chroot）：把 Kindle 现在的网卡、
# 地址、网关、DNS 写给系统（系统里的以太网服务直接用它注册一个已连接的网络，不跑 DHCP、不改网卡；见 VdHostNet.java）。
hostnet_write() {
    dev=$(ip route 2>/dev/null | awk '$1 == "default" { for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')
    gw=$(ip route 2>/dev/null | awk '$1 == "default" { for (i = 1; i < NF; i++) if ($i == "via") { print $(i + 1); exit } }')
    [ -n "$dev" ] || dev=wlan0
    addr=$(ip -4 addr show dev "$dev" 2>/dev/null | awk '$1 == "inet" { print $2; exit }')
    {
        echo "iface=$dev"
        [ -n "$addr" ] && echo "addr=$addr"
        [ -n "$gw" ] && echo "gw=$gw"
        for d in $ANDROID_DNS; do echo "dns=$d"; done
        # 只要 IPv4：以前的 [0-9.]* 把 IPv6 的 "240c::6666" 截成了 "240"（用户诊断 的 dns=240）
        sed -n 's/^nameserver[[:space:]]*\([0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\)[[:space:]]*$/dns=\1/p' "$(readlink -f /etc/resolv.conf)" 2>/dev/null
    } > $X/hostnet
    chmod 644 $X/hostnet
    t "net: host network for android: $(tr '\n' ' ' < $X/hostnet)"
}

# 0.79–0.81 在 chroot 模式下跑的是没打补丁的 netd：它清掉了 Kindle 的策略路由（连 32766 main 都删了），只留自己的规则，
# 其中 32000 unreachable 会让带 netId 标记的连接全部不通。发现这种残留就恢复成 Linux 默认的规则（只删 10000–32000 段）。
hostnet_fix_rules() {
    for v in -4 -6; do
        ip $v rule 2>/dev/null | grep -qE '^(1[0-9]{4}|2[0-9]{4}|3[01][0-9]{3}|32000):' || continue
        ip $v rule 2>/dev/null | grep -q '^32766:' || ip $v rule add pref 32766 lookup main 2>/dev/null
        ip $v rule 2>/dev/null | grep -q '^32767:' || ip $v rule add pref 32767 lookup default 2>/dev/null
        for p in $(ip $v rule 2>/dev/null | sed -n 's/^\([0-9]*\):.*/\1/p'); do
            [ "$p" -ge 10000 ] && [ "$p" -le 32000 ] && ip $v rule del pref "$p" 2>/dev/null
        done
        t "net: removed stale netd ip $v rules from the Kindle"
    done
    return 0
}

net_start() {
    # chroot 模式（内核没有 net 命名空间）：Android 和 Kindle 共用网络，不起 slirp，改走 hostnet
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ] || hostns; then hostnet_fix_rules; hostnet_write; return 0; fi
    [ -x "$SLIRP" ] || { t "net: $SLIRP missing, no network"; return 0; }
    rm -f $W/slirp.sock $W/slirp.log   # 旧日志也删：重启 slirp 时下面的等待会先读到上一个 slirp 的 "sent tapfd"（还没被截断），当成已经起来
    # 演示系统没有 DHCP 客户端，用 --configure 直接配好；真的 Android 自己 DHCP（slirp 发 .15）
    CONF=; [ -f $R/.demo ] && CONF=--configure
    target=$(readlink -f /etc/resolv.conf)
    unshare -m sh -c "{ mount --make-rprivate / 2>/dev/null || true; } &&
        { [ -e '$target' ] && mount --bind $W/resolv.conf '$target'; true; } &&
        exec $SLIRP --mtu=1500 --cidr=$NET.0/24 $CONF --api-socket $W/slirp.sock $P eth0" > $W/slirp.log 2>&1 &
    echo $! > $W/slirp.pid
    i=0; while [ $i -lt 20 ] && ! grep -q 'sent tapfd' $W/slirp.log 2>/dev/null; do usleep 500000; i=$((i + 1)); done
    t "net: slirp4netns $(grep -q 'sent tapfd' $W/slirp.log && echo up || echo 'NOT up'): $(tail -1 $W/slirp.log)"
}

# ---- 网络自愈（fix/1105-net-resume，2026-11-21 社区：KT6“待机一夜回来网络没了、一直转圈，退出再进才好”，
#      KT5“读着读着断网、下一章加载不出来，只能退出”；用户诊断：解冻后几分钟用户自己点了重启 / 退出再进）----
# 待机（1.03 起 Kindle 真的会休眠）、关 / 开 Wi-Fi、换网络时，Kindle 的 wlan0 会被拆掉重建、DHCP 重新拿地址 / DNS。
#  - chroot（hostnet，没有 net 命名空间的内核）：根因在 netd（pw5-516211 模拟器复现）：wlan0 一拆，netd 卡死在 clearTetheringRules
#    的死循环里、占满一个核，之后所有命令超时，Android 的网络再也注册不回来。修法是换上修好的 netd（netd_hostnet_fix），下面的检查兜底。
#  - 命名空间（slirp4netns）：slirp 自己的 DNS 只读启动时那份 resolv.conf（ANDROID_DNS + 当时的 Kindle DNS）；外面的地址变了以后
#    还开着的连接全是死的。
# 做法：解冻 / 屏保醒来 / Kindle 的网络变了（地址、网关、DNS、wlan0 重建）时，等 Kindle 自己连上，再从 Android 里面试一次
# “解析域名 + 连 80 端口”（Android 自己的解析器和路由，toybox nc）。不通而 Kindle 通：先刷新 slirp 的 DNS 再试，还不通就重启
# slirp（Android 的 eth0 重建、重新 DHCP，旧连接全部断开重连）；chroot 模式下 netd 卡死就结束它（init 会拉起来）。每一步记日志。
# 用着的时候（没冻结、没屏保）每 NET_CHECK_S 秒（默认 600，0 = 不查）也查一次；平时看门狗只比较几个 /sys、/proc 里的值，不发包。
NET_PROBE_HOSTS=${NET_PROBE_HOSTS:-"connect.rom.miui.com www.baidu.com www.qq.com"}
net_sig() { # Kindle 现在的网络：默认路由的网卡 + 它的 ifindex + IPv4 地址 + 网关 + DNS（变了就要查一次）
    set -- $(ip route 2>/dev/null | awk '$1 == "default" { d=""; g=""; for (i = 1; i < NF; i++) { if ($i == "dev") d = $(i + 1); else if ($i == "via") g = $(i + 1) } if (d != "") { print d, g; exit } }')
    ns_dev=$1; ns_gw=$2
    [ -n "$ns_dev" ] || { echo none; return 0; }
    ns_idx=; read -r ns_idx < "/sys/class/net/$ns_dev/ifindex" 2>/dev/null || true
    ns_ip=$(ip -4 addr show dev "$ns_dev" 2>/dev/null | awk '$1 == "inet" { print $2; exit }')
    ns_dns=$(awk '/^nameserver/ { printf "%s,", $2 }' /etc/resolv.conf 2>/dev/null)
    echo "$ns_dev $ns_idx $ns_ip $ns_gw $ns_dns"
}
net_host_up() { # Kindle 自己有默认路由和地址（不发包）。chroot：还得是 Android 跟踪的那块网卡（Wi-Fi 断着时可能只剩 USB 网络）
    nh_d=$(net_sig | cut -d' ' -f1); [ "$nh_d" != none ] || return 1
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then
        nh_i=$(sed -n 's/^iface=//p' $X/hostnet 2>/dev/null); [ -z "$nh_i" ] || [ "$nh_i" = "$nh_d" ] || return 1
    fi
    ip -4 addr show dev "$nh_d" 2>/dev/null | grep -q 'inet '
}
net_probe() { # 从 Android 里面：解析 + TCP 连 80。通了返回 0（$NP_OK = 通的那个域名）
    np_p=$(cat $W/pid 2>/dev/null); [ -n "$np_p" ] && kill -0 "$np_p" 2>/dev/null || return 2
    for np_h in $NET_PROBE_HOSTS; do
        if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then
            vd_timeout 12 chroot /proc/$np_p/root /system/bin/toybox timeout 9 /system/bin/toybox nc -w 6 "$np_h" 80 </dev/null >/dev/null 2>&1
        else
            vd_timeout 12 nsenter -t $np_p -m -u -i -n -p -r -w /system/bin/toybox timeout 9 /system/bin/toybox nc -w 6 "$np_h" 80 </dev/null >/dev/null 2>&1
        fi && { NP_OK=$np_h; return 0; }
    done
    return 1
}
net_slirp_dns() { # slirp 的上游 DNS 换成 Kindle 现在的（同一个文件原地改写：bind 挂载认 inode，libslirp 看到修改时间变了就重读）
    [ -f $W/resolv.conf ] || return 0
    { for d in $ANDROID_DNS; do echo "nameserver $d"; done
      grep '^nameserver' "$(readlink -f /etc/resolv.conf)" 2>/dev/null; } > $W/resolv.new
    cmp -s $W/resolv.new $W/resolv.conf || { cat $W/resolv.new > $W/resolv.conf; t "net: slirp dns now $(awk '{printf "%s ", $2}' $W/resolv.conf)"; }
    rm -f $W/resolv.new
}
net_slirp_restart() {
    sp=$(cat $W/slirp.pid 2>/dev/null)
    for p in $(pgrep -f "^[^ ]*slirp4netns .*--api-socket $W/slirp.sock" 2>/dev/null) $sp; do kill -CONT $p 2>/dev/null; kill $p 2>/dev/null; done
    i=0; while [ $i -lt 20 ] && [ -n "$(pgrep -f "^[^ ]*slirp4netns .*--api-socket $W/slirp.sock" 2>/dev/null)" ]; do usleep 100000; i=$((i + 1)); done
    for p in $(pgrep -f "^[^ ]*slirp4netns .*--api-socket $W/slirp.sock" 2>/dev/null) $sp; do kill -9 $p 2>/dev/null; done
    net_setup
    P=$(cat $W/pid 2>/dev/null) net_start
    # 传书和无线调试的端口转发在旧的 slirp 里：重新加（fwd_on / wa_adb_fwd_on 会自己清理旧记录）
    nf_port=$(cat $W/fwd.port 2>/dev/null)
    [ -n "$nf_port" ] || nf_port=$(cut -d: -f1 $W/fwd.ipt 2>/dev/null)
    [ -f $W/fwd.id ] && [ -n "$nf_port" ] && type fwd_on >/dev/null 2>&1 && { fwd_on "$nf_port"; t "net: transfer port $nf_port forwarded again"; }
    if [ -f $W/adbfwd.id ] || [ -f $X/ctl/adb.on ]; then
        type wa_adb_fwd_on >/dev/null 2>&1 || { [ -f "$VD/boot/lib/webadmin.sh" ] && . "$VD/boot/lib/webadmin.sh"; }
        type wa_adb_fwd_on >/dev/null 2>&1 && { wa_adb_fwd_on >/dev/null 2>&1 && t "net: adb port forwarded again"; }
    fi
    if [ "${DNS_MODE:-auto}" != auto ] && type dns_apply >/dev/null 2>&1; then
        (
            _dp=$(cat $W/pid 2>/dev/null)
            _di=0
            while [ $_di -lt 25 ] && [ -n "$_dp" ] && kill -0 "$_dp" 2>/dev/null; do
                P=$_dp; $(vd_in) /system/bin/ip -4 addr show dev eth0 2>/dev/null | grep -q 'inet ' && break
                sleep 1; _di=$((_di + 1))
            done
            sleep 2
            dns_apply
        ) </dev/null >/dev/null 2>&1 &
    fi
}
netd_wedged() { # chroot 模式：netd 有线程一直在跑（老 netd 的 clearTetheringRules 死循环）
    np=$(chroot /proc/$(cat $W/pid)/root /system/bin/toybox pidof netd 2>/dev/null | cut -d' ' -f1)
    [ -n "$np" ] || np=$(for d in /proc/[0-9]*; do [ "$(cat $d/comm 2>/dev/null)" = netd ] && { echo ${d#/proc/}; break; }; done)
    [ -n "$np" ] || return 1
    a=$(awk '{ if (sub(/^.*\) +/, "")) s += $12 + $13 } END { print s + 0 }' /proc/$np/task/*/stat 2>/dev/null); sleep 2
    b=$(awk '{ if (sub(/^.*\) +/, "")) s += $12 + $13 } END { print s + 0 }' /proc/$np/task/*/stat 2>/dev/null)
    [ $((b - a)) -ge 150 ] 2>/dev/null   # 2 秒里用户态+内核态 ≥ 1.5 秒 = 在空转
}
net_frozen() { # Android 处于屏保或冻结态：不要做探测或重启 slirp（43313616300AD 诊断：锁屏 24s 后 net_heal 误判并重启 slirp）
    [ "$(cat $W/sleep.cur 2>/dev/null)" = saver ] && return 0
    if [ "$(cat $W/fzmode 2>/dev/null)" = signal ]; then
        [ "$(cat $FZ/state 2>/dev/null)" = FROZEN ]
    else
        case "$(cat $FZ/freezer.state 2>/dev/null)" in FROZEN|FREEZING) return 0 ;; esac
        return 1
    fi
}
# $1 = 为什么查（resume / thaw / change / periodic / manual）。在后台跑；同时只有一个。
net_heal() {
    [ -f $W/booted ] || return 0
    nh_why=$1
    net_frozen && { t "net check ($nh_why): android frozen/saver, skipping"; return 0; }
    # 醒来以后 Kindle 要几秒到几十秒才重新连上 Wi-Fi：等它（最多 90 秒）。Kindle 自己没网（Wi-Fi 关着、飞行模式）就不是我们的事
    i=0; while ! net_host_up && ! net_frozen && [ $i -lt 45 ]; do sleep 2; i=$((i + 1)); done
    net_frozen && { t "net check ($nh_why): android frozen/saver while waiting, skipping"; return 0; }
    net_host_up || { t "net check ($nh_why): kindle has no network itself, nothing to do"; return 0; }
    # chroot：给 Android 的那份（DNS 等）按 Kindle 现在的重写，但只在默认路由还在同一块网卡上时（Wi-Fi 断着、走 USB 网络的那一刻
    # 写进去的 iface=usb0 会让以太网服务一直等一块它不跟踪的网卡：模拟器实测）
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then
        [ "$(net_sig | cut -d' ' -f1)" = "$(sed -n 's/^iface=//p' $X/hostnet 2>/dev/null)" ] && hostnet_write >/dev/null
    else net_slirp_dns; fi
    sleep ${NET_SETTLE_S:-3}
    net_frozen && { t "net check ($nh_why): android frozen/saver before probe, skipping"; return 0; }
    net_probe && { [ "$nh_why" = periodic ] || t "net check ($nh_why): ok ($NP_OK)"; echo "$(cut -d. -f1 /proc/uptime) ok" > $W/net.last; return 0; }
    net_frozen && { t "net check ($nh_why): android frozen/saver during probe, skipping repair"; return 0; }
    net_host_up || { t "net check ($nh_why): kindle lost network during probe, skipping repair"; return 0; }
    t "net check ($nh_why): android cannot reach the internet but the kindle is online ($(net_sig)): repairing"
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ]; then
        hostnet_fix_rules
        [ "$(net_sig | cut -d' ' -f1)" = "$(sed -n 's/^iface=//p' $X/hostnet 2>/dev/null)" ] && hostnet_write >/dev/null
        type dns_apply >/dev/null 2>&1 && dns_apply
        if netd_wedged; then
            t "net: netd ($np) is spinning (old netd, wlan0 was removed): ending it, init restarts it"
            kill -9 "$np" 2>/dev/null; sleep 8
        else
            sleep 3
        fi
    else
        t "net: restarting slirp4netns (android's eth0 comes back and takes a new lease)"
        net_slirp_restart; sleep 12
    fi
    net_frozen && { t "net check ($nh_why): android frozen/saver during repair, skipping"; return 0; }
    if net_probe; then t "net: repaired ($nh_why): ok ($NP_OK)"; echo "$(cut -d. -f1 /proc/uptime) repaired" > $W/net.last; return 0; fi
    sleep 15
    net_frozen && { t "net check ($nh_why): android frozen/saver during repair retry, skipping"; return 0; }
    if net_probe; then t "net: repaired ($nh_why, slow): ok ($NP_OK)"; echo "$(cut -d. -f1 /proc/uptime) repaired" > $W/net.last; return 0; fi
    net_frozen && { t "net check ($nh_why): android frozen/saver after repair, skipping"; return 0; }
    net_host_up || { t "net check ($nh_why): kindle lost network during repair, skipping"; return 0; }
    t "net: still no network in android after repair ($nh_why); kindle: $(net_sig)"
    echo "$(cut -d. -f1 /proc/uptime) fail" > $W/net.last
    vd_slog "network: android had no network after $nh_why and the automatic repair did not help (kindle: $(net_sig))"
    return 1
}
net_heal_kick() { # 后台查一次（已经在查就不再起）
    [ "${NET_CHECK:-1}" = 1 ] || return 0
    alive $W/netheal.pid && return 0
    ( net_heal "$1"; rm -f $W/netheal.pid ) </dev/null >>$LOG 2>&1 &
    echo $! > $W/netheal.pid
}
# 看门狗每轮（没冻结时）调：Kindle 的网络变了 → 查；用着的时候每 NET_CHECK_S 秒查一次（屏保 / 冻结时不查）
net_watch_tick() {
    [ -f $W/booted ] || return 0
    nw_s=$(net_sig); nw_now=$(cut -d. -f1 ${NET_UPTIME:-/proc/uptime})
    if [ "$nw_s" != "$(cat $W/net.sig 2>/dev/null)" ]; then
        [ -f $W/net.sig ] || { echo "$nw_s" > $W/net.sig; echo "$nw_now start" > $W/net.last; return 0; }   # 这次运行的第一轮：只记下来
        echo "$nw_s" > $W/net.sig
        [ "$nw_s" = none ] || { t "net: kindle network changed: $nw_s"; net_heal_kick change; }
        return 0
    fi
    [ "${NET_CHECK_S:-600}" -gt 0 ] 2>/dev/null || return 0
    [ "$(cat $W/sleep.cur 2>/dev/null)" = saver ] && return 0
    nw_l=$(cut -d' ' -f1 $W/net.last 2>/dev/null)
    [ $((nw_now - ${nw_l:-0})) -ge ${NET_CHECK_S:-600} ] || return 0
    echo "$nw_now pending" > $W/net.last
    [ "$nw_s" = none ] || net_heal_kick periodic
}
# chroot（hostnet）模式：系统镜像里的 netd 换成包里修好的那份（bin/netd-hostnet；clearTetheringRules 死循环，见上）。
# 内容一样就什么都不做；换不成只记日志（老 netd 还能用，只是断网后要靠上面的检查兜底）。
netd_hostnet_fix() {
    hostns || return 0
    [ -f $VD/bin/netd-hostnet ] && [ -f $R/system/bin/netd ] || return 0
    cmp -s $VD/bin/netd-hostnet $R/system/bin/netd && return 0
    if cp $VD/bin/netd-hostnet $R/system/bin/netd.new && chmod 755 $R/system/bin/netd.new && mv -f $R/system/bin/netd.new $R/system/bin/netd; then
        t "hostns: netd replaced with the fixed hostnet netd (no more hang when wlan0 goes away)"
    else
        rm -f $R/system/bin/netd.new; t "hostns: could not replace netd (read-only system?): keeping the old one"
    fi
    return 0
}
