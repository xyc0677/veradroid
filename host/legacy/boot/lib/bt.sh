# SPDX-License-Identifier: GPL-3.0-or-later
# 蓝牙翻页器 / 键盘（宿主侧）。设计见 docs/bluetooth.md。
# Kindle 自己的蓝牙栈（btmanagerd / acsbtfd）一直管着芯片（/dev/stpbt），Android 里不跑蓝牙栈（镜像里没有 Bluetooth.apk）。
# 两部分：
#  1. 配对 / 连接：桌面的“蓝牙翻页器”页经命令 FIFO 发 "bt scan|list|bond|connect|disconnect|unbond|enable|disable"，
#     这里转成 Kindle 的 lipc（com.lab126.btfd）调用；结果写 $X/ctl/bt.list（设备列表）和 $X/ctl/bt.result（这次操作的结果）。
#     Android 运行时 Kindle 的界面（pillow）是关着的，配对确认框弹不出来：配对时这里替用户回答 PairUserConfirmation。
#  2. 按键桥 bin/vdbtkeys：连上以后 Kindle 的 HID host 经 /dev/uhid 生成一个内核输入设备（总线 BUS_BLUETOOTH）。
#     容器的 /dev 是私有的，后来才出现的节点 Android 看不见，所以 vdbtkeys：
#   - 建一个常驻的 uinput 设备 veradroid-btkeys 放进容器（开机时 bind 到 /dev/input/event3，或 Android 已经在跑时 mknod 进去）；
#   - 翻页器（没有字母键的蓝牙设备，以及 5.20+ 的 kindle-input-pt）：独占后按 BT_PT_MAP 改写方向键再从 btkeys 发出；
#   - 键盘（有字母键）：另建 veradroid-btkbd 克隆，mknod 进容器的 /dev/input，Android 自己热插；断开时删掉；
#   - 返回 Kindle 时（input_release）放开设备，Kindle 自己照常用；冻结 / 屏保期间按键直接丢掉，解冻后不会连翻好几页。
# 没有 Kindle 蓝牙栈的机型什么都不做（不起进程、不写日志、不调 lipc）。
# 设置（$SYS/settings.conf）：
#   BT_BRIDGE=auto（默认）有配对过的蓝牙设备时才起桥：开机后在后台查一次（不拖慢开机），回到 Android、在桌面配对 / 连接成功后也查；
#             1 = 一直开（开机时就起，和旧版一样）；0 = 关掉整个桥（配对页照样能用）
#   BT_PT_MAP=volume|page|dpad|raw（默认 volume：下一页 = 音量-，上一页 = 音量+，微信读书等阅读 App 都认）；
#   BT_PT_SWAP=1 反转方向；BT_KINDLE_PT=0 不接管 kindle-input-pt。
BTF=com.lab126.btfd

bt_supported() {
    [ "$VD_BT_FORCE" = 1 ] && return 0
    [ -x /usr/bin/btmanagerd ] || [ -x /usr/bin/acsbtfd ] || [ -x /usr/bin/btfd ]
}
bt_mode() { case "${BT_BRIDGE:-auto}" in 0|off) echo 0 ;; 1|on) echo 1 ;; *) echo auto ;; esac; }

# 起 vdbtkeys，等 veradroid-btkeys 出现；成功时 btn = 它的 eventN，宿主上有节点
bt_launch() {
    [ -x "$VD/bin/vdbtkeys" ] || { t "bt: vdbtkeys missing"; return 1; }
    bt_stop                                      # 上次留下的（stop.sh 没跑完之类）
    case "$BT_PT_MAP" in volume|page|dpad|raw) btm=$BT_PT_MAP ;; *) btm=volume ;; esac
    bts=; [ "$BT_PT_SWAP" = 1 ] && bts=--swap
    btk=; [ "$BT_KINDLE_PT" = 0 ] && btk=--no-kindle-pt
    chmod 666 /dev/uinput /dev/input/uinput 2>/dev/null
    : > $W/bt.log
    # 和 veradroid-input 一样不在 freezer 组里，冻结时还能收到放开 / 独占的信号
    $VD/bin/vdbtkeys $W/bt.log --map $btm $bts $btk --conf $SYS/settings.conf --pidfile $W/pid --state $W/bt.state \
        --drop-flag $W/frozen --drop-flag $W/saver_frozen --action $VD/boot/action.sh </dev/null >/dev/null 2>&1 &
    echo $! > $W/bt.pid
    i=0; btn=
    while [ $i -lt 15 ]; do
        btn=$(input_sysname veradroid-btkeys); [ -n "$btn" ] && break
        usleep 200000; i=$((i + 1))
    done
    [ -n "$btn" ] || { t "bt: veradroid-btkeys did not appear, bridge off"; bt_stop; return 1; }
    # 没有 devtmpfs 的老内核：按 sysfs 的设备号自己建节点
    if [ ! -e /dev/input/$btn ]; then
        dev=$(cat /sys/class/input/$btn/dev 2>/dev/null)
        [ -n "$dev" ] && mknod /dev/input/$btn c ${dev%%:*} ${dev##*:}
    fi
    [ -c /dev/input/$btn ] || { t "bt: no node for $btn, bridge off"; bt_stop; return 1; }
    chmod 666 /dev/input/$btn
    return 0
}

# 开机（container_start 之前）。BT_BRIDGE=1：现在就起，节点由 container.sh bind 进去（KFB_BTKEYS）。
# auto：不拖慢开机，后台等 Android 的根目录就位后看有没有配对过的设备，有才起（bt_late）。
bt_start() {
    KFB_BTKEYS=; export KFB_BTKEYS
    rm -f $W/bt.state $W/bt.node
    bt_supported || return 0                     # 没有蓝牙的机型：一行日志都不写
    case "$(bt_mode)" in
        0) t "bt: bridge off (BT_BRIDGE=0)"; return 0 ;;
        auto) bt_late_bg boot; return 0 ;;
    esac
    bt_launch || return 0
    KFB_BTKEYS=/dev/input/$btn; export KFB_BTKEYS
    t "bt: bridge up, keys $KFB_BTKEYS map=$btm${bts:+ swap}"
}

# Android 已经在跑（或正在起）时起桥：veradroid-btkeys 的节点 mknod 进容器的 /dev/input，Android 的 EventHub 用 inotify 自己加上。
bt_late_start() {
    alive $W/bt.pid && return 0
    bt_launch || return 1
    dev=$(cat /sys/class/input/$btn/dev 2>/dev/null)
    p=$(cat $W/pid 2>/dev/null); R=/proc/$p/root
    if [ -z "$p" ] || [ ! -f $R/system/build.prop ] || [ -z "$dev" ]; then
        t "bt: android not running, bridge not started"; bt_stop; return 1
    fi
    if [ "$(stat -c %d:%i $R/dev/input 2>/dev/null)" != "$(stat -c %d:%i /dev/input 2>/dev/null)" ]; then
        n=
        for e in event3 event10 event11 event12 event13 event14 event15; do [ -e $R/dev/input/$e ] || { n=$e; break; }; done
        [ -n "$n" ] && mknod -m 666 $R/dev/input/$n c ${dev%%:*} ${dev##*:} 2>/dev/null || { t "bt: cannot publish $btn into android"; bt_stop; return 1; }
        echo "$p $n" > $W/bt.node
    fi
    # 现在是 Kindle 在前台（冻结着）：先放开，回到 Android 时 input_grab 再独占
    # （正在解冻时 $W/frozen 还在，但 input_grab 已经发过了：不放开）
    [ -f $W/frozen ] && [ ! -d $W/thawing ] && kill -USR1 "$(cat $W/bt.pid)" 2>/dev/null
    t "bt: bridge up (late), keys ${n:-$btn} map=$btm${bts:+ swap}"
    status_write 2>/dev/null
    return 0
}

# auto：有配对过的设备（或已经有蓝牙输入设备连着）才起桥。后台跑，最多等 Android 的根目录 3 分钟。
bt_late_bg() {
    [ "$(bt_mode)" = auto ] && bt_supported || return 0
    alive $W/bt.pid && return 0
    (
        echo $$ > $W/bt.auto 2>/dev/null
        i=0
        while [ $i -lt 90 ]; do
            [ -f $W/bt.auto ] || exit 0          # stop.sh 已经在停了
            p=$(cat $W/pid 2>/dev/null)
            [ -n "$p" ] && [ -f /proc/$p/root/system/build.prop ] && break
            sleep 2; i=$((i + 1))
        done
        [ -f $W/bt.auto ] || exit 0
        if bt_input_present || { bt_on && [ -n "$(bt_paired_macs)" ]; }; then
            bt_late_start
        else
            t "bt: off or nothing paired, bridge not started ($1)"
        fi
        rm -f $W/bt.auto
    ) </dev/null >>${LOG:-/dev/null} 2>&1 &
}
# 已经有蓝牙输入设备连着（总线 0005，不算我们自己的 veradroid-*，也不算 5.20+ 的 kindle-input-pt：
# 它是 input_manager 建的常驻虚拟设备，PW6 5.20.2 真机上没配对任何东西时也在）
bt_input_present() {
    for d in /sys/class/input/input*; do
        [ "$(cat $d/id/bustype 2>/dev/null)" = 0005 ] || continue
        case "$(cat $d/name 2>/dev/null)" in veradroid-*|kindle-input-pt) ;; *) return 0 ;; esac
    done
    return 1
}

bt_release() { alive $W/bt.pid && kill -USR1 "$(cat $W/bt.pid)" 2>/dev/null; return 0; }
# 回到 Android：桥在跑就重新独占；auto 模式下桥没起（开机时没有配对设备，后来在 Kindle 设置里配对了）就在后台查一次
bt_grab() {
    if alive $W/bt.pid; then kill -USR2 "$(cat $W/bt.pid)" 2>/dev/null; else bt_late_bg grab; fi
    return 0
}
bt_stop() {
    rm -f $W/bt.auto
    p=$(cat $W/bt.pid 2>/dev/null)
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
        kill "$p" 2>/dev/null
        # 等它自己删掉 uinput 设备和容器里的键盘节点；1 秒还不走就强杀（内核会随 fd 关闭销毁 uinput 设备）
        i=0; while kill -0 "$p" 2>/dev/null && [ $i -lt 10 ]; do usleep 100000; i=$((i + 1)); done
        kill -9 "$p" 2>/dev/null
    fi
    # 晚起时 mknod 进容器的节点（容器还在的话）
    if [ -f $W/bt.node ]; then
        set -- $(cat $W/bt.node)
        [ -n "$2" ] && [ -c /proc/$1/root/dev/input/$2 ] && rm -f /proc/$1/root/dev/input/$2
    fi
    rm -f $W/bt.pid $W/bt.state $W/bt.node
    return 0
}

# 桌面状态（status.sh 调）：btsupported=0/1 这台机器有没有 Kindle 蓝牙栈；bt=1 桥在跑；bton=1 蓝牙开着；
# btdev=当前接管的设备（kind:name，逗号分隔）；btmap / btswap / btbridge 当前设置
bt_status_lines() {
    if ! bt_supported; then echo "btsupported=0"; echo "bt=0"; return 0; fi
    echo "btsupported=1"
    if alive $W/bt.pid; then echo "bt=1"; else echo "bt=0"; fi
    echo "bton=$(lipc-get-prop $BTF BTstate 2>/dev/null)"
    echo "btdev=$(awk -F'\t' '{printf "%s%s:%s", (NR>1?",":""), $1, $2}' $W/bt.state 2>/dev/null)"
    echo "btmap=${BT_PT_MAP:-volume}"
    echo "btswap=${BT_PT_SWAP:-0}"
    echo "btbridge=$(bt_mode)"
}

# ---- Kindle 蓝牙服务（com.lab126.btfd）----
# 列表属性（ListDiscovered / ListPaired / ListConnected）是 lipc 的 hash 数组，lipc-hash-prop 要从标准输入读一个参数哈希：
# 一定给它 "{}"（选项写错时它会段错误）。输出（5.19.5 实测格式）：
#   Hash Index: 0
#   	bd_name = "Page Turner"
#   	bd_address = "AA:BB:CC:DD:EE:FF"
#   	is_paired = (1)
# 字符串带引号、整数带括号。解析不依赖缩进：按 "键 = 值" 一对一对读，"Hash Index" 或同一个键第二次出现就是下一个设备。
# 输出：MAC<TAB>名字<TAB>paired<TAB>connected（MAC 大写；名字去掉制表符和控制字符，最多 64 字节）
bt_hash() {
    echo '{}' | lipc-hash-prop $BTF "$1" 2>/dev/null | awk -v tab="$(printf '\t')" '
    function yes(v) { v = tolower(v); return (v == "1" || v == "true" || v == "yes") ? 1 : 0 }
    function flush() {
        if (mac != "") { gsub(/[\001-\037\177",]/, "", name); if (length(name) > 64) name = substr(name, 1, 64); print mac tab name tab pa tab co }
        mac = ""; name = ""; pa = 0; co = 0; seen = " "
    }
    BEGIN { pa = 0; co = 0; seen = " " }
    /^[ \t]*Hash Index/ { flush(); next }
    /^[ \t]*bd_name[ \t]*=[ \t]*".*"[ \t]*$/ {
        if (index(seen, " bd_name ")) flush()
        seen = seen "bd_name "
        v = $0; sub(/^[ \t]*bd_name[ \t]*=[ \t]*"/, "", v); sub(/"[ \t]*$/, "", v)
        gsub(/[\001-\037\177",]/, "", v)
        name = v; next
    }
    {
        line = $0
        while (match(line, /[A-Za-z_][A-Za-z_0-9]*[ \t]*=[ \t]*("[^"]*"|\([^)]*\)|[^ \t]+)/)) {
            kv = substr(line, RSTART, RLENGTH); line = substr(line, RSTART + RLENGTH)
            k = kv; sub(/[ \t]*=.*/, "", k)
            v = kv; sub(/^[^=]*=[ \t]*/, "", v); gsub(/^"|"$/, "", v); if (v ~ /^\(.*\)$/) v = substr(v, 2, length(v) - 2)
            if (k != "bd_address" && k != "bd_name" && k != "is_paired" && k != "is_connected") continue
            if (index(seen, " " k " ")) flush()
            seen = seen k " "
            if (k == "bd_address") mac = toupper(v)
            else if (k == "bd_name") name = v
            else if (k == "is_paired") pa = yes(v)
            else co = yes(v)
        }
    }
    END { flush() }' | grep -E '^([0-9A-F]{2}:){5}[0-9A-F]{2}	'
}
bt_paired_macs() { bt_hash ListPaired | cut -f1; }
bt_on() { [ "$(lipc-get-prop $BTF BTstate 2>/dev/null)" = 1 ]; }

# 合并三张表写 $X/ctl/bt.list：名字<TAB>MAC<TAB>paired<TAB>connected，已配对的在前。
# 名字空的用 MAC 代替。原子替换（先写 .tmp 再 mv），桌面用 inotify 等 MOVED_TO。
bt_list_write() {
    mkdir -p $X/ctl
    _btp=$(awk '{print $1}' /proc/self/stat 2>/dev/null || echo $$)
    _btf="$X/ctl/bt.list.$$.$_btp.tmp"
    { bt_hash ListPaired | awk -F'\t' -v OFS='\t' '{ $3 = 1; print "P", $0 }'
      bt_hash ListConnected | awk -F'\t' -v OFS='\t' '{ $4 = 1; print "C", $0 }'
      bt_hash ListDiscovered | awk -F'\t' -v OFS='\t' '{ print "D", $0 }'
    } | awk -F'\t' -v OFS='\t' '
        { m = $2; if (!(m in nm)) { ord[++n] = m; nm[m] = "" }
          if ($3 != "" && nm[m] == "") nm[m] = $3
          if ($4 == 1 || $1 == "P") pa[m] = 1
          if ($5 == 1 || $1 == "C") co[m] = 1 }
        END { for (pass = 1; pass >= 0; pass--) for (i = 1; i <= n; i++) { m = ord[i]; if ((pa[m] ? 1 : 0) != pass) continue
                  print (nm[m] == "" ? m : nm[m]), m, (pa[m] ? 1 : 0), (co[m] ? 1 : 0) } }' > "$_btf" && chmod 644 "$_btf" && mv -f "$_btf" $X/ctl/bt.list || rm -f "$_btf"
}
bt_is() {  # bt_is <mac> paired|connected：在 Kindle 的表里查（大小写不敏感）
    case "$2" in paired) f=3 ;; *) f=4 ;; esac
    awk -F'\t' -v m="$1" -v f=$f 'toupper($2) == m && $f == 1 { found = 1 } END { exit !found }' $X/ctl/bt.list 2>/dev/null
}
# Kindle 表里这个地址原来的写法（大小写），表里没有就原样
bt_addr() {
    a=$(echo '{}' | lipc-hash-prop $BTF ListDiscovered 2>/dev/null; echo '{}' | lipc-hash-prop $BTF ListPaired 2>/dev/null)
    a=$(printf '%s\n' "$a" | grep -oiE '([0-9a-f]{2}:){5}[0-9a-f]{2}' | awk -v m="$1" 'toupper($0) == m { print; exit }')
    echo "${a:-$1}"
}

# 结果：$X/ctl/bt.result（state=running|done，op，mac，ok，error，secs = 预计最长秒数）
bt_result() {
    _btp=$(awk '{print $1}' /proc/self/stat 2>/dev/null || echo $$)
    _btf="$X/ctl/bt.result.$$.$_btp.tmp"
    printf '%s\n' "$@" > "$_btf" && chmod 644 "$_btf" && mv -f "$_btf" $X/ctl/bt.result || rm -f "$_btf"
}
# 只允许一个蓝牙操作同时进行（扫描 15 秒、配对最多 40 秒）；持有者已经不在了就当残留接管
bt_lock() {
    if ! mkdir $W/bt.op 2>/dev/null; then
        op=$(cat $W/bt.op/pid 2>/dev/null)
        if [ -n "$op" ] && kill -0 "$op" 2>/dev/null; then return 1; fi
    fi
    sh -c 'echo $PPID' > $W/bt.op/pid
    return 0
}
bt_unlock() { rm -rf $W/bt.op; }

bt_mac_ok() {
    case "$1" in ''|*[!0-9A-F:]*) return 1 ;; esac
    [ ${#1} -eq 17 ] && printf '%s\n' "$1" | grep -qE '^([0-9A-F]{2}:){5}[0-9A-F]{2}$'
}

bt_wait() {  # bt_wait <秒> <条件命令…>：每秒查一次，按墙上时间算（条件本身慢时也不会拖长）
    end=$(( $(date +%s) + $1 )); shift
    while [ $(date +%s) -lt $end ]; do "$@" && return 0; sleep 1; done
    "$@"
}
bt_has() { bt_list_write; bt_is "$1" "$2"; }
bt_hasnt() { bt_list_write; ! bt_is "$1" "$2"; }

# 向导自己配对的设备（PW6 5.19.5 真机实测：扫描时把附近一台 LG 电视配对并连上了）：扫描只该列出设备，配对要等用户在桌面上点。
# 扫描期间新出现在已配对表里、扫描前不在的，一律断开并取消配对。
bt_undo_autopair() { # " 扫描前已配对的 MAC "
    for m in $(bt_paired_macs); do
        case "$1" in *" $m "*) continue ;; esac
        a=$(bt_addr "$m")
        lipc-set-prop -s $BTF Disconnect "$a" 2>/dev/null; lipc-set-prop -s $BTF Unbond "$a" 2>/dev/null
        t "bt: the Kindle scan wizard paired $m by itself: disconnected and unpaired"
    done
}
bt_scan() {
    bt_on || { bt_list_write; bt_result state=done op=scan ok=0 error=off; t "bt: scan refused, bluetooth is off"; return 1; }
    bt_result state=running op=scan secs=45
    t "bt: scan"
    bt_before=" $(bt_paired_macs | tr '\n' ' ') "
    lipc-set-prop -i $BTF triggerBTscan 1 2>/dev/null || { bt_result state=done op=scan ok=0 error=lipc; return 1; }
    # Kindle 一次扫描大约 15 秒；中间每 3 秒把找到的写出来，桌面一边扫一边显示。
    # 5.20.2 真机：triggerBTscan 启动的是 Kindle 的“BTCH 向导”——最多 3 轮“发现 10 秒 + 自动配对它认可的设备”
    # （BtDiscoveryFilter 只收 Kindle Remote 的 VID/PID、HID/音频 UUID 匹配的设备，其余 rejected，不进 ListDiscovered），
    # 向导还在跑（isBtchRunning=1）就接着等，最多 45 秒。
    i=0; while [ $i -lt 15 ]; do
        sleep 3; bt_list_write; i=$((i + 1))
        bt_undo_autopair "$bt_before"
        [ $i -ge 6 ] && [ "$(lipc-get-prop $BTF isBtchRunning 2>/dev/null)" != 1 ] && break
    done
    bt_undo_autopair "$bt_before"; sleep 2; bt_list_write
    bt_result state=done op=scan ok=1 "count=$(wc -l < $X/ctl/bt.list 2>/dev/null | tr -d ' ')"
}

# 配对：Bond，等它出现在已配对表里。Kindle 收到设备的配对请求时发 btConfirmationEvent（平时由 Kindle 界面弹框问用户），
# Android 在前台时 Kindle 的界面是关着的，框弹不出来：这里听这个事件，替用户答 PairUserConfirmation 1（接受）。
# 只在我们自己发起的这次配对期间听，确认的就是用户在桌面上点的那个设备。PairingFailed / btNotConfirmed = 对方拒绝或失败。
# 配好以后连接（Kindle 多数会自己连，没连就再发 Connect）。
bt_bond() {
    mac=$1; a=$(bt_addr "$mac")
    bt_on || { bt_result state=done op=bond "mac=$mac" ok=0 error=off; return 1; }
    bt_result state=running op=bond "mac=$mac" secs=45
    t "bt: bond $mac"
    ev=$W/bt.ev.$$; : > $ev
    lipc-wait-event -m -s 40 $BTF btConfirmationEvent,PairingFailed,btNotConfirmed > $ev 2>/dev/null &
    evp=$!
    usleep 300000
    if ! lipc-set-prop -s $BTF Bond "$a" 2>/dev/null; then
        kill $evp 2>/dev/null; rm -f $ev; bt_result state=done op=bond "mac=$mac" ok=0 error=lipc; return 1
    fi
    i=0; ok=; conf=; err=timeout; end=$(( $(date +%s) + 35 ))
    while [ $(date +%s) -lt $end ]; do
        sleep 1; i=$((i + 1))
        if [ -z "$conf" ] && awk -v m="$mac" 'BEGIN{m=toupper(m)} /btConfirmationEvent/ && index(toupper($0), m) { found=1; exit } END { exit !found }' $ev 2>/dev/null; then
            conf=1; lipc-set-prop -i $BTF PairUserConfirmation 1 2>/dev/null; t "bt: pairing confirmation for $mac accepted"
        fi
        grep -qE 'PairingFailed|btNotConfirmed' $ev 2>/dev/null && { err=failed; break; }
        bt_has "$mac" paired && { ok=1; break; }
    done
    kill $evp 2>/dev/null
    # 5.19 上 lipc-wait-event 收到事件时会往控制台写一行（stdout 重定向了也会，见 sleep.sh）：收到过事件就让显示桥整屏重画一次盖掉
    [ -s $ev ] && type epd_send >/dev/null 2>&1 && epd_send REDRAW
    rm -f $ev
    [ -n "$ok" ] || { bt_result state=done op=bond "mac=$mac" ok=0 error=$err; t "bt: bond $mac failed ($err)"; return 1; }
    t "bt: bonded $mac"
    bt_connect_wait "$mac" "$a" bond
}
bt_connect_wait() {  # mac addr op
    bt_result state=running op=$3 "mac=$1" secs=20
    if ! bt_is "$1" connected; then
        lipc-set-prop -s $BTF Connect "$2" 2>/dev/null
        bt_wait 12 bt_has "$1" connected || { lipc-set-prop -s $BTF ensureBTconnection "$2" 2>/dev/null; bt_wait 8 bt_has "$1" connected; }
    fi
    if bt_is "$1" connected; then
        # 连上了：桥没在跑就起（auto 和 1 都起；BT_BRIDGE=0 是用户关掉的，不起）
        [ "$(bt_mode)" != 0 ] && ! alive $W/bt.pid && bt_late_start
        bt_result state=done op=$3 "mac=$1" ok=1
        t "bt: connected $1"
    else
        # 配好了但没连上：多数翻页器要按一下键才连，不算失败（桥照样起好，等它连上来）
        [ "$3" = bond ] && [ "$(bt_mode)" != 0 ] && ! alive $W/bt.pid && bt_late_start
        bt_result state=done op=$3 "mac=$1" ok=0 error=notconnected
        t "bt: $1 not connected"
    fi
}
bt_connect() {
    mac=$1; a=$(bt_addr "$mac")
    bt_on || { bt_result state=done op=connect "mac=$mac" ok=0 error=off; return 1; }
    bt_list_write
    bt_is "$mac" paired || { bt_result state=done op=connect "mac=$mac" ok=0 error=notpaired; return 1; }
    t "bt: connect $mac"
    bt_connect_wait "$mac" "$a" connect
}
bt_disconnect() {
    mac=$1; a=$(bt_addr "$mac")
    bt_result state=running op=disconnect "mac=$mac" secs=10
    t "bt: disconnect $mac"
    lipc-set-prop -s $BTF Disconnect "$a" 2>/dev/null
    if bt_wait 10 bt_hasnt "$mac" connected; then bt_result state=done op=disconnect "mac=$mac" ok=1
    else bt_result state=done op=disconnect "mac=$mac" ok=0 error=timeout; fi
}
bt_unbond() {
    mac=$1; a=$(bt_addr "$mac")
    bt_result state=running op=unbond "mac=$mac" secs=15
    t "bt: unbond $mac"
    bt_is "$mac" connected && { lipc-set-prop -s $BTF Disconnect "$a" 2>/dev/null; sleep 1; }
    lipc-set-prop -s $BTF Unbond "$a" 2>/dev/null
    if bt_wait 12 bt_hasnt "$mac" paired; then bt_result state=done op=unbond "mac=$mac" ok=1
    else bt_result state=done op=unbond "mac=$mac" ok=0 error=timeout; fi
}
bt_power() {  # 1|0：开关 Kindle 的蓝牙（和 Kindle 设置里的开关是同一个）
    bt_result state=running op=power "on=$1" secs=20
    t "bt: power $1"
    # 只写 BTenable 关不掉：btfd 里还有别的“保持开启”请求（从飞行模式恢复时挂上的 intent 2，日志
    # “Deactivate:intent=1 … active=0x4”），BTenable 0 只撤掉 intent 1。加上 BTflightMode 1 才真的关掉
    # （一台 PW6 5.19.5 测试机实测：BTstate 1 -> 0）；开的时候先撤掉飞行模式再 BTenable 1。
    if [ "$1" = 1 ]; then
        lipc-set-prop -i $BTF BTflightMode 0 2>/dev/null
        lipc-set-prop -s $BTF BTenable 1 2>/dev/null
    else
        lipc-set-prop -s $BTF BTenable 0 2>/dev/null
        lipc-set-prop -i $BTF BTflightMode 1 2>/dev/null
    fi
    if [ "$1" = 1 ]; then bt_wait 15 bt_on; else bt_wait 15 eval '! bt_on'; fi
    r=$?; bt_list_write
    if [ $r = 0 ] && [ "$(bt_mode)" = auto ]; then
        if [ "$1" = 1 ]; then bt_late_bg power; else alive $W/bt.pid && bt_stop; fi
    fi
    status_write 2>/dev/null          # 先写状态再写结果：结果一出来桌面就可能发下一条命令，那时锁应该已经快放了
    if [ $r = 0 ]; then bt_result state=done op=power "on=$1" ok=1; else bt_result state=done op=power "on=$1" ok=0 error=timeout; fi
}

# cmd FIFO（一行一条，参数作为一个字符串传进来，这里拆）：
#   bt map volume|page|dpad|raw / bt swap 0|1   写进 settings.conf，SIGHUP 让 vdbtkeys 立即重读（节点不变）
#   bt bridge auto|1|0                          按键桥的开关（auto = 有配对设备才起）
#   bt list | scan | enable | disable           列表 / 扫描 / 开关蓝牙
#   bt bond|connect|disconnect|unbond <MAC>     MAC 必须是大写的 AA:BB:CC:DD:EE:FF，否则什么都不做
# 慢的操作在后台跑（命令 FIFO 不能被堵住），同一时间只有一个；忙时结果写 error=busy。
bt_cmd() {
    if [ $# -ge 2 ]; then sub=$1; shift; arg="$*"; else sub=${1%% *}; arg=; [ "$sub" != "$1" ] && arg=${1#* }; fi
    case "$sub" in
        map|swap|bridge)
            case "$sub:$arg" in
                map:volume|map:page|map:dpad|map:raw) k=BT_PT_MAP ;;
                swap:0|swap:1) k=BT_PT_SWAP ;;
                bridge:auto|bridge:0|bridge:1) k=BT_BRIDGE ;;
                *) t "bt: bad argument"; return 1 ;;
            esac
            { grep -v "^$k=" $SYS/settings.conf 2>/dev/null; echo "$k=$arg"; } > $SYS/settings.conf.tmp && mv -f $SYS/settings.conf.tmp $SYS/settings.conf
            case "$k" in BT_PT_MAP) BT_PT_MAP=$arg ;; BT_PT_SWAP) BT_PT_SWAP=$arg ;; BT_BRIDGE) BT_BRIDGE=$arg ;; esac
            type settings_publish >/dev/null 2>&1 && settings_publish
            alive $W/bt.pid && kill -HUP "$(cat $W/bt.pid)" 2>/dev/null
            if [ "$k" = BT_BRIDGE ] && bt_supported; then
                case "$arg" in
                    1) alive $W/bt.pid || ( bt_late_start ) </dev/null >>${LOG:-/dev/null} 2>&1 & ;;
                    auto) bt_late_bg cmd ;;
                    0) bt_stop ;;
                esac
            fi
            t "bt: $k=$arg"
            status_write 2>/dev/null
            return 0 ;;
    esac
    bt_supported || { bt_result state=done "op=$sub" ok=0 error=unsupported; return 1; }
    case "$sub" in
        list|scan|enable|disable) [ -z "$arg" ] || { t "bt: bad argument"; return 1; } ;;
        bond|connect|disconnect|unbond)
            bt_mac_ok "$arg" || { t "bt: $sub rejected (bad address)"; bt_result state=done "op=$sub" ok=0 error=badmac; return 1; } ;;
        *) t "bt: unknown command"; return 1 ;;
    esac
    (
        if ! bt_lock; then
            [ "$sub" = list ] && exit 0
            bt_result state=done "op=$sub" "mac=$arg" ok=0 error=busy
            exit 1
        fi
        trap bt_unlock EXIT
        case "$sub" in
            list) bt_list_write; bt_result state=done op=list ok=1 ;;
            scan) bt_scan ;;
            enable) bt_power 1 ;;
            disable) bt_power 0 ;;
            bond) bt_bond "$arg" ;;
            connect) bt_connect "$arg" ;;
            disconnect) bt_disconnect "$arg" ;;
            unbond) bt_unbond "$arg" ;;
        esac
    ) </dev/null >>${LOG:-/dev/null} 2>&1 &
    return 0
}
