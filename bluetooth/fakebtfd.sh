#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 模拟器测试用：假的 Kindle 蓝牙服务（com.lab126.btfd）。模拟器里没有蓝牙控制器，真的 acsbtfd 打不开蓝牙，
# 所以把 lipc-get-prop / lipc-set-prop / lipc-hash-prop / lipc-wait-event 用 bind mount 换成本脚本：
# 对 com.lab126.btfd 的调用在这里按一个小状态机回答，其他服务原样交给真的程序（拷在 $F/real/）。
# “设备”连上时起 fakebtdev（uinput，总线 BUS_BLUETOOTH，名字和 Kindle 给的一样），断开时销毁——和真机上 /dev/uhid 的设备一样出现 / 消失。
#   fakebtfd.sh install <fakebtdev 路径>    装上（设备表 $F/devices.conf：一行 "MAC 类型 名字"，类型 pt|kbd|confirm|refuse）
#   fakebtfd.sh uninstall                  卸掉（umount，杀掉所有假设备）
#   fakebtfd.sh key <MAC> <code>...        让这个设备按键（Linux 键码）
#   fakebtfd.sh drop <MAC>                 设备掉线（像休眠 / 省电断开），仍是已配对
#   fakebtfd.sh relink <MAC>               设备自己重新连上（像按了一下翻页器）
#   fakebtfd.sh state                      打印状态
# 和真机的已知差异：真机 Bond 后多数自动连接（这里也是）；扫描时长、事件参数格式按 acsbtfd 的字符串猜的。
F=/tmp/fbt
BTF=com.lab126.btfd
me=${0##*/}

san() { echo "$1" | tr -d ':'; }
has() { grep -qx "$2" $F/$1 2>/dev/null; }
add() { has $1 "$2" || echo "$2" >> $F/$1; }
del() { grep -vx "$2" $F/$1 > $F/$1.tmp 2>/dev/null; mv -f $F/$1.tmp $F/$1; }
kind() { awk -v m="$1" 'toupper($1) == toupper(m) { print $2; exit }' $F/devices.conf; }
dname() { awk -v m="$1" 'toupper($1) == toupper(m) { $1 = ""; $2 = ""; sub(/^  /, ""); print; exit }' $F/devices.conf; }
evt() { echo "$1 $2" >> $F/events; echo "$(date +%T) event $1 $2" >> $F/log; }
on() { [ "$(cat $F/on 2>/dev/null)" = 1 ]; }

dev_up() {    # MAC：起 fakebtdev
    m=$1; s=$(san $m)
    [ -f $F/dev.$s.pid ] && kill -0 "$(cat $F/dev.$s.pid)" 2>/dev/null && { add conn $m; return; }
    k=$(kind $m); case "$k" in kbd) t=kbd ;; *) t=pt ;; esac
    setsid $F/fakebtdev $t "$(dname $m)" $F/keys.$s > $F/dev.$s.out 2>&1 < /dev/null &
    echo $! > $F/dev.$s.pid
    sleep 1; add conn $m
    echo "$(date +%T) connected $m" >> $F/log
}
dev_down() {
    m=$1; s=$(san $m)
    [ -f $F/dev.$s.pid ] && kill "$(cat $F/dev.$s.pid)" 2>/dev/null
    rm -f $F/dev.$s.pid; del conn $m
    echo "$(date +%T) disconnected $m" >> $F/log
}

hashout() {   # 表名
    i=0
    case "$1" in
        ListDiscovered) l=$(cat $F/disc 2>/dev/null) ;;
        ListPaired) l=$(cat $F/paired 2>/dev/null) ;;
        ListConnected) l=$(cat $F/conn 2>/dev/null) ;;
        *) return 0 ;;
    esac
    for m in $l; do
        p=0; has paired $m && p=1; c=0; has conn $m && c=1
        printf 'Hash Index: %d\n\tbd_name = "%s"\n\tbd_address = "%s"\n\tis_connected = (%d)\n\tis_paired = (%d)\n\n' $i "$(dname $m)" "$(echo $m | tr A-F a-f)" $c $p
        i=$((i + 1))
    done
}

setprop() {   # prop value
    echo "$(date +%T) set $1 $2" >> $F/log
    v=$(echo "$2" | tr a-f A-F)
    case "$1" in
        BTenable) if [ "$2" = 1 ]; then echo 1 > $F/on; else echo 0 > $F/on; for m in $(cat $F/conn 2>/dev/null); do dev_down $m; done; fi ;;
        triggerBTscan)
            on || return 0
            ( sleep 3; for m in $(awk 'NR <= 2 { print toupper($1) }' $F/devices.conf); do add disc $m; done
              sleep 4; for m in $(awk 'NR > 2 { print toupper($1) }' $F/devices.conf); do add disc $m; done
              evt btDiscoveryCompleted ) </dev/null >/dev/null 2>&1 & ;;
        Bond)
            on || return 0
            k=$(kind $v); [ -n "$k" ] || return 0
            ( case "$k" in
                refuse) sleep 2; evt PairingFailed "$v"; exit 0 ;;
                confirm) rm -f $F/confirmed; sleep 1; evt btConfirmationEvent "$v"
                         i=0; while [ $i -lt 20 ] && [ ! -f $F/confirmed ]; do sleep 1; i=$((i + 1)); done
                         [ -f $F/confirmed ] || { evt btNotConfirmed "$v"; exit 0; } ;;
                *) sleep 2 ;;
              esac
              add paired $v; add disc $v; dev_up $v ) </dev/null >/dev/null 2>&1 & ;;
        PairUserConfirmation) [ "$2" = 1 ] && touch $F/confirmed ;;
        Connect|ensureBTconnection) on && has paired $v && ( dev_up $v ) </dev/null >/dev/null 2>&1 & ;;
        Disconnect) dev_down $v ;;
        Unbond) dev_down $v; del paired $v ;;
    esac
    return 0
}

waitev() {    # [-m] [-s N] source events
    multi=; secs=1000000; src=; evs=; skip=
    for x in "$@"; do
        if [ -n "$skip" ]; then secs=$x; skip=; continue; fi
        case "$x" in
            -m) multi=1 ;;
            -s) skip=1 ;;
            -*) ;;
            *) if [ -z "$src" ]; then src=$x; elif [ -z "$evs" ]; then evs=$x; fi ;;
        esac
    done
    [ "$src" = "$BTF" ] || exec $F/real/lipc-wait-event "$@"
    n0=$(wc -l < $F/events); end=$(( $(date +%s) + secs ))
    while [ $(date +%s) -lt $end ]; do
        n=$(wc -l < $F/events)
        if [ $n -gt $n0 ]; then
            matched=$(sed -n "$((n0 + 1)),${n}p" $F/events | awk -v evs=",$evs," '{ if (index(evs, "," $1 ",")) print }')
            n0=$n
            if [ -n "$matched" ]; then
                if [ -z "$multi" ]; then
                    printf '%s\n' "$matched" | head -n 1
                    exit 0
                fi
                printf '%s\n' "$matched"
            fi
        fi
        usleep 300000
    done
}

case "$me" in
lipc-get-prop|lipc-set-prop|lipc-hash-prop)
    src=
    for x in "$@"; do case "$x" in -*) ;; *) src=$x; break ;; esac; done
    if [ "$src" != "$BTF" ]; then exec $F/real/$me "$@"; fi
    while [ $# -gt 0 ]; do case "$1" in -*) shift ;; *) break ;; esac; done
    case "$me" in
        lipc-get-prop) case "$2" in BTstate) cat $F/on ;; BTconnectedDevName) m=$(head -1 $F/conn 2>/dev/null); [ -n "$m" ] && dname $m || echo ;; *) echo 0 ;; esac ;;
        lipc-set-prop) shift; p=$1; shift; setprop "$p" "$*" ;;
        lipc-hash-prop) cat >/dev/null; hashout "$2" ;;
    esac
    exit 0 ;;
lipc-wait-event) waitev "$@"; exit 0 ;;
esac

case "$1" in
install)
    [ -d $F/real ] && { echo "already installed"; exit 0; }
    mkdir -p $F/real $F/bin; : > $F/events; : > $F/disc; : > $F/paired; : > $F/conn; echo 1 > $F/on
    [ -f $F/devices.conf ] || printf '%s\n' "11:22:33:44:55:01 pt Fake Page Turner" "11:22:33:44:55:02 confirm Fake Confirm Turner" \
        "11:22:33:44:55:03 refuse Fake Refusing Device" "11:22:33:44:55:04 kbd Fake BT Keyboard" > $F/devices.conf
    cp "$2" $F/fakebtdev; chmod 755 $F/fakebtdev
    cp "$0" $F/fakebtfd.sh; chmod 755 $F/fakebtfd.sh
    for b in lipc-get-prop lipc-set-prop lipc-hash-prop lipc-wait-event; do
        cp /usr/bin/$b $F/real/$b
        cp $F/fakebtfd.sh $F/bin/$b         # 脚本按 $0 的名字分派：每个名字一个副本
        mount --bind $F/bin/$b /usr/bin/$b
    done
    echo installed ;;
uninstall)
    for m in $(cat $F/conn 2>/dev/null); do dev_down $m; done
    for p in $F/dev.*.pid; do [ -f "$p" ] && kill "$(cat $p)" 2>/dev/null; done
    for b in lipc-get-prop lipc-set-prop lipc-hash-prop lipc-wait-event; do umount /usr/bin/$b 2>/dev/null; done
    rm -rf $F; echo uninstalled ;;
key) m=$(echo $2 | tr a-f A-F); s=$(san $m); shift 2
    [ -f $F/dev.$s.pid ] && kill -0 "$(cat $F/dev.$s.pid)" 2>/dev/null || { echo "$m not connected"; exit 1; }
    for c in "$@"; do echo $c > $F/keys.$s; done ;;
drop) dev_down $(echo $2 | tr a-f A-F) ;;
relink) m=$(echo $2 | tr a-f A-F); has paired $m && dev_up $m ;;
state) echo "on=$(cat $F/on) disc=$(cat $F/disc | tr '\n' ' ') paired=$(cat $F/paired | tr '\n' ' ') conn=$(cat $F/conn | tr '\n' ' ')" ;;
*) echo "usage: $0 install <fakebtdev>|uninstall|key MAC code..|drop MAC|relink MAC|state"; exit 2 ;;
esac
