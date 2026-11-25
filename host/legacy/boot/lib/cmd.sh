# SPDX-License-Identifier: GPL-3.0-or-later
# 桌面 / 面板发给宿主的命令：一个 FIFO（$X/ctl/cmd），宿主阻塞读，没有命令时一点 CPU 都不用。
# 一行一条：exit stop restart diag diag-upload feedback clear invert status apply | light N | warmth N | wifi 0|1
#   compile all|<包> | localupdate check|install（完整列表见 docs/host-settings.md）
numeric() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }
# 调亮度（桌面面板的亮度条、管理网页、优化建议都走这里；port of 066674db）。自动亮度开着（powerd flAuto=1，Oasis 2 / 3 有光线传感器）
# 时 powerd 隔一会儿就按传感器把亮度改回去，看起来就是“调了没反应”。手动调了就是要手动：先把自动关掉。设完读回来，对不上就记一行。
light_set() {
    ls_p=com.lab126.powerd; ls_a=
    if [ "$(lipc-get-prop $ls_p flAuto 2>/dev/null)" = 1 ]; then
        lipc-set-prop -i $ls_p flAuto 0 2>/dev/null && ls_a=1 && t "light: auto brightness was on (powerd flAuto=1), turned it off for the manual level $1"
    fi
    lipc-set-prop -i $ls_p flIntensity "$1" >/dev/null 2>&1 || { t "light $1: lipc-set-prop flIntensity failed"; return 1; }
    ls_g=$(lipc-get-prop $ls_p flIntensity 2>/dev/null)
    [ "$ls_g" = "$1" ] || t "light $1: powerd reads back '$ls_g' (raw $(lipc-get-prop $ls_p flRawIntensity 2>/dev/null))"
    [ -z "$ls_a" ] || status_write      # 面板上的“自动”勾掉
    return 0
}
# 管理网页的请求（SSH、截屏、日志、占用、自动更新）：先校验来源再做，见 webadmin.sh
[ -f "$VD/boot/lib/webadmin.sh" ] && . "$VD/boot/lib/webadmin.sh"
# 每个应用的显示设置（前台应用 → 显示参数，三个宿主共用一份，见 appprof.sh）
[ -f "$VD/boot/lib/appprof.sh" ] && . "$VD/boot/lib/appprof.sh"

# 传书：把 Wi-Fi 上的端口转进 Android（slirp4netns 的 add_hostfwd）。只在传书开着的时候开；关了就撤掉。
fwd_off() {
    [ -f $W/fwd.id ] || return 0
    case "$(cat $W/fwd.id)" in ipt:*)
        iptables -D INPUT -i "$(cut -d: -f3 $W/fwd.id)" -p tcp --dport "$(cut -d: -f2 $W/fwd.id)" -j ACCEPT 2>/dev/null
        rm -f $W/fwd.id; return 0 ;;
    esac
    $VD/bin/slirpctl $W/slirp.sock "{\"execute\":\"remove_hostfwd\",\"arguments\":{\"id\":$(cat $W/fwd.id)}}" >/dev/null 2>&1
    rm -f $W/fwd.id
}
fwd_on() {
    fwd_off
    upload_dir_fix 2>/dev/null     # 打开传书：暂存目录现在就要能写（common.sh）
    # 共用 Kindle 网络（chroot / hostnet：KT6 5.17、老机型）时没有 slirp，传书网页的端口就在 Kindle 自己的 wlan0 上，
    # 但 Kindle 的防火墙默认拦掉进来的连接：临时放行这一个端口（关传书时撤掉）。以前这里只记一句 "no slirp control"，
    # 传书网页打不开（用户诊断 连开了 5 次）。
    if [ "$(cat $W/mode 2>/dev/null)" = chroot ] || hostns; then
        fdev=$(sed -n 's/^iface=\([A-Za-z0-9_.-]*\)$/\1/p' $X/hostnet 2>/dev/null | head -1); [ -n "$fdev" ] || fdev=wlan0
        iptables -C INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null || iptables -I INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null
        if iptables -C INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null; then echo "ipt:$1:$fdev" > $W/fwd.id; t "fwd: port $1 open on the Kindle firewall ($fdev, host network)"
        else t "fwd: could not open port $1 on the Kindle firewall"; fi
        return 0
    fi
    [ -x $VD/bin/slirpctl ] && [ -S $W/slirp.sock ] || { t "fwd: no slirp control"; return 1; }
    r=$($VD/bin/slirpctl $W/slirp.sock "{\"execute\":\"add_hostfwd\",\"arguments\":{\"proto\":\"tcp\",\"host_addr\":\"0.0.0.0\",\"host_port\":$1,\"guest_addr\":\"$NET.15\",\"guest_port\":$1}}" 2>&1)
    id=$(echo "$r" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
    if [ -n "$id" ]; then echo $id > $W/fwd.id; t "fwd: port $1 open (id $id)"; else t "fwd failed: $r"; fi
}

# 旋转（docs/rotation.md）：0 <-> 180、90 <-> 270 逻辑尺寸不变，显示桥（ROT）和触摸前端（gestures 里的 ROTATE）当场切换；
# 横竖变化要让 Android 按新尺寸重新开机（do_restart）。触摸屏没经过 veradroid-input（PW5 goodix 竖屏直通）时也只能重启。
rotate_apply() {
    [ -f $W/rotate ] || return 0          # 显示桥还没起来（开机前）：下次启动自然按新设置
    rnew=$(rot_deg); rold=$(cat $W/rotate 2>/dev/null); [ -n "$rold" ] || rold=0
    [ "$rnew" = "$rold" ] && return 0
    if [ $((rnew % 180)) = $((rold % 180)) ] && [ -f $W/rotate.live ] && alive $W/input.pid && alive $W/epd.pid; then
        gestures_write
        epd_send ROT $rnew
        echo $rnew > $W/rotate
        t "rotate $rold -> $rnew (live)"
    else
        t "rotate $rold -> $rnew: restarting Android with the new size"
        do_restart
    fi
}

# 保存诊断并上传。结果写到 $X/ctl/diag.upload.result（ok=0|1、id=服务器上的文件名、error=原因），桌面据此告诉用户。
# $1 = note：把桌面写好的 $X/ctl/feedback.txt 放进归档（feedback.txt）。内容只当数据拷贝，绝不解释；
# 最多 4000 字节；是符号链接就不要（ctl 目录别的应用也能写，不能让它借此把 root 才读得到的文件带出去）。
DIAG_URL=https://veradroid.wenjin.jp/api/diag/upload
diag_result() { printf '%s\n' "$@" > $X/ctl/diag.upload.tmp && mv -f $X/ctl/diag.upload.tmp $X/ctl/diag.upload.result; touch $X/ctl/diag.upload.done; }
diag_upload() {
    rm -f $X/ctl/diag.upload.result
    fb=$X/ctl/feedback.txt; note=
    if [ "$1" = note ]; then
        # 先拿走：诊断要跑一两分钟，这期间别的上传（桌面崩溃时自动触发的）不该把这段话带走
        if [ -f "$fb" ] && [ ! -L "$fb" ]; then note=$W/feedback.$$; head -c 4000 "$fb" > "$note" 2>/dev/null || note=; fi
        rm -f "$fb"
        [ -n "$note" ] || { diag_result ok=0 "error=no feedback text"; return 1; }
    fi
    diag_save
    d=$(ls -dt "$SYS/data/media/0/Diagnostics"/* 2>/dev/null | head -1)
    [ -n "$d" ] && [ -d "$d" ] || { rm -f "$note"; diag_result ok=0 "error=diagnostics not saved"; return 1; }
    [ -n "$note" ] && { mv -f "$note" "$d/feedback.txt" 2>/dev/null || { cp "$note" "$d/feedback.txt"; rm -f "$note"; }; }
    tgz="${d%/*}/.${d##*/}.tgz"          # 放在诊断目录旁边（不在 $d 里：tar 不该把自己正在写的归档也打进去；也不放 tmpfs，几十 MB）
    tar -czf "$tgz" -C "$d" . 2>/dev/null || { rm -f "$tgz"; diag_result ok=0 "error=archive failed"; return 1; }
    id=$(cat "$SYS/device-id" 2>/dev/null | tr -cd '0-9A-Za-z' | cut -c1-40)
    [ -n "$id" ] || id=unknown
    hwmodel=$(cat "$SYS/model" 2>/dev/null | tr -cd '0-9A-Za-z_.-' | cut -c1-32)
    ver=$(cat "$SYS/version" 2>/dev/null | tr -cd '0-9A-Za-z_.-' | cut -c1-32)
    r=$(curl -sS --max-time 120 -w '\nhttp=%{http_code}' -F "device_id=$id" -F "model=$hwmodel" -F "version=$ver" -F "file=@$tgz" "$DIAG_URL" 2>&1)
    rm -f "$tgz"
    echo "$r" > "$d/upload-result.txt"
    file=$(printf '%s' "$r" | sed -n 's/.*"file" *: *"\([A-Za-z0-9._-]\{1,80\}\)".*/\1/p' | head -1)
    if [ -n "$file" ]; then
        diag_result ok=1 "id=$file"; t "diag upload ok: $file"
    else
        # 原因只留普通字符（会显示在桌面上），最多 120 个
        e=$(printf '%s' "$r" | tr '\n' ' ' | tr -cd 'A-Za-z0-9 .:=,_/()-' | cut -c1-120)
        diag_result ok=0 "error=${e:-upload failed}"; t "diag upload failed: $e"
    fi
}

# ---- 已保存的无线网络（Kindle 的 com.lab126.wifid 里存的配置）----
# 列表写到 $X/ctl/wifi.list：一行一个，第一个字符是 * 表示当前连着的，空格表示没连；后面是网络名。
# 只取网络名：profileData 里还有明文密码（psk），一个字也不能写出去。
# 读不到（没有 lipc-hash-prop、属性不存在、Wi-Fi 服务没起）就是空列表。
wifi_saved() {
    command -v lipc-hash-prop >/dev/null 2>&1 || return 0
    lipc-hash-prop -n com.lab126.wifid profileData 2>/dev/null | sed -n 's/^[[:space:]]*essid = "\(.*\)"[[:space:]]*$/\1/p' | grep -v '^$' | head -50
}
wifi_current() {
    command -v lipc-hash-prop >/dev/null 2>&1 || return 0
    lipc-hash-prop -n com.lab126.wifid currentEssid 2>/dev/null | grep -q 'connected = "yes"' || return 0
    lipc-hash-prop -n com.lab126.wifid currentEssid 2>/dev/null | sed -n 's/^[[:space:]]*essid = "\(.*\)"[[:space:]]*$/\1/p' | head -1
}
wifi_list() {
    cur=$(wifi_current)
    wifi_saved | while IFS= read -r n; do
        if [ -n "$cur" ] && [ "$n" = "$cur" ]; then printf '*%s\n' "$n"; else printf ' %s\n' "$n"; fi
    done > $X/ctl/wifi.list.tmp
    mv -f $X/ctl/wifi.list.tmp $X/ctl/wifi.list
}
# wificonnect <网络名的 UTF-8 字节，十六进制>：十六进制只有 0-9a-f，不会有引号、空格、换行能混进 root 的命令行；
# 解出来不许有控制字符，而且必须正好是一个已保存的网络名。之后只作为一个带引号的参数交给 lipc-set-prop，不经过 eval。
# 结果写 $X/ctl/wifi.result：ok=1|0、essid（十六进制）、error。
wifi_result() { printf '%s\n' "$@" > $X/ctl/wifi.result.tmp && mv -f $X/ctl/wifi.result.tmp $X/ctl/wifi.result; }
wifi_connect() {
    hex=$1
    case "$hex" in ''|*[!0-9a-f]*) t "wificonnect: rejected (not hex)"; wifi_result ok=0 "error=bad name"; return 1 ;; esac
    len=${#hex}
    if [ $((len % 2)) -ne 0 ] || [ $len -gt 64 ]; then t "wificonnect: rejected (length $len)"; wifi_result ok=0 "error=bad name"; return 1; fi
    # 控制字符 00-1f、7f 不要
    if printf '%s' "$hex" | sed 's/../&\n/g' | grep -qE '^(0[0-9a-f]|1[0-9a-f]|7f)$'; then
        t "wificonnect: rejected (control chars)"; wifi_result ok=0 "error=bad name" "essid=$hex"; return 1
    fi
    want=$(printf "$(printf '%s' "$hex" | sed 's/../\\x&/g')")
    found=
    while IFS= read -r n; do [ "$n" = "$want" ] && { found=1; break; }; done <<WIFI_EOF
$(wifi_saved)
WIFI_EOF
    [ -n "$found" ] || { t "wificonnect: not a saved network"; wifi_result ok=0 "error=not saved" "essid=$hex"; return 1; }
    [ "$(lipc-get-prop com.lab126.cmd wirelessEnable 2>/dev/null)" = 1 ] || { lipc-set-prop -i com.lab126.cmd wirelessEnable 1; sleep 2; }
    t "wificonnect: switching to a saved network"
    lipc-set-prop com.lab126.cmd ensureConnection "wifi:$want"
    i=0
    while [ $i -lt 30 ]; do
        sleep 1; i=$((i + 1))
        [ "$(wifi_current)" = "$want" ] && [ "$(lipc-get-prop com.lab126.wifid cmState 2>/dev/null)" = CONNECTED ] && break
    done
    if [ "$(wifi_current)" = "$want" ]; then wifi_result ok=1 "essid=$hex"; else wifi_result ok=0 "error=timeout" "essid=$hex"; fi
    wifi_list; status_write
}

# 下拉面板的“优化机器状态”（桌面发 optimize）：只做无害的事——丢掉干净的页缓存、整理内存碎片、报 zram 的用量和预编译队列；
# 有应用等着预编译又在充电就开始编；Wi-Fi 省电按自动规则恢复（WIFI_PS=off 是用户自己选的，不动；传书开着时也不动）。
# 不杀 Kindle 的进程，不改任何用户设置。结果写 ctl/optimize.result（KEY=value），桌面据此写结果卡片。
opt_run() {
    r=$X/ctl/optimize.result
    b=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
    sync
    echo 1 > /proc/sys/vm/drop_caches 2>/dev/null
    [ -w /proc/sys/vm/compact_memory ] && echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
    a=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)
    zo=; zc=
    if [ -r /sys/block/zram0/mm_stat ]; then set -- $(cat /sys/block/zram0/mm_stat); zo=$(( ${1:-0} / 1048576 )); zc=$(( ${2:-0} / 1048576 )); fi
    cp=0; [ -s $W/compile.queue ] && cp=$(wc -l < $W/compile.queue | tr -d ' ')
    ck=0
    if [ "$cp" -gt 0 ] && [ "$(lipc-get-prop com.lab126.powerd isCharging 2>/dev/null)" = 1 ] && type compile_kick >/dev/null 2>&1; then compile_kick; ck=1; fi
    ps=
    if type wifi_ps_state >/dev/null 2>&1; then
        case "${WIFI_PS:-auto}" in
            off) ps=user-off ;;
            *) [ -f $W/fwd.id ] || { type wifi_ps_restore >/dev/null 2>&1 && wifi_ps_restore; }; ps=$(wifi_ps_state) ;;
        esac
    fi
    { echo "before=$b"; echo "after=$a"; echo "zram_orig_mb=$zo"; echo "zram_compr_mb=$zc"
      echo "compile_pending=$cp"; echo "compile_kicked=$ck"; echo "wifips=$ps"; } > $r.tmp && mv -f $r.tmp $r
    t "optimize: MemAvailable $b -> $a MB, zram ${zo:-?}/${zc:-?} MB, compile pending $cp (started $ck), wifi ps ${ps:-?}"
}
cmd_listen() {
    mkdir -p $X/ctl
    cmdfifo=$X/ctl/cmd
    # 已经是 FIFO 就沿用，不要先删再建：解冻时桌面马上会发命令（appdisp / status），删掉的那一瞬间
    # Java 的 FileOutputStream 会在原地建一个普通文件，mkfifo 随之失败（"File exists"），命令通道从此没了，
    # 返回 Kindle / 设置全都没反应（用户诊断：解冻后只能重启 Kindle）。不是 FIFO 就删掉重建，重试几次。
    i=0
    while [ ! -p $cmdfifo ] && [ $i -lt 20 ]; do
        rm -f $cmdfifo; mkfifo $cmdfifo 2>/dev/null; i=$((i + 1))
        [ -p $cmdfifo ] || usleep 100000
    done
    [ -p $cmdfifo ] || { t "cmd: cannot create $cmdfifo"; return 1; }
    chmod 666 $cmdfifo
    # 读写方式打开一次，一直留着：这样同时来的几条命令（比如“开传书”紧跟着“查状态”）不会因为读端反复开关而丢掉
    exec 3<>$cmdfifo
    while :; do
        read -r c a <&3 || { usleep 300000; continue; }
        case "$c" in
        exit)    do_exit ;;
        usbswitch) type usb_switch >/dev/null 2>&1 && usb_switch ;;   # 插线提示里点了“切换”：回到 Kindle 进 USB 传输（usb.sh）
        usbnoask)  type usb_noask >/dev/null 2>&1 && usb_noask ;;     # 插线提示里点了“不再询问”
        stop)    do_stop ;;
        restart) do_restart ;;
        diag)    ( diag_save; touch $X/ctl/diag.done ) & ;;
        diag-upload) ( diag_upload ) & ;;        # 保存诊断并把归档上传到服务器 data/diagnostics
        feedback)    ( diag_upload note ) & ;;   # 同上，另把用户在“问题反馈”里写的话（ctl/feedback.txt）放进归档
        clear)   epd_send FULL ;;
        screenshot)
            mkdir -p $SYS/data/media/0/Screenshots
            $(vd_in) /system/bin/screencap -p /data/media/0/Screenshots/screen-$(date +%Y%m%d-%H%M%S).png >/dev/null 2>&1 && t "screenshot saved" ;;
        fg)      type appprof_fg >/dev/null 2>&1 && appprof_fg "$a"
                 # 后台应用降优先级：ActivityManager 改完各进程的 adj 再看（bgnice.sh）
                 type bgnice_tick >/dev/null 2>&1 && ( sleep 2; bgnice_tick ) </dev/null >/dev/null 2>&1 & ;;       # 前台应用变了（VeraHome 的任务栈监听），见 appprof.sh
        appset)  type appprof_set >/dev/null 2>&1 && appprof_set "$a" ;;     # 存某个应用的显示设置：appset <pkg>|<spec>
        appdisp)
            # appdisp <pkg>|<KEY=value;...>   or appdisp home
            # 新桌面不再发 appdisp <pkg>|...（改发 fg）；"appdisp home" 和老桌面照旧能用
            if [ "$a" = home ] && type appprof_fg >/dev/null 2>&1; then appprof_fg home; continue; fi
            # 每个应用的显示参数单独放在 settings.app 里（settings_load 最后读它），只接受显示相关的键和数字值。
            # 以前是把原文追加进 settings.conf：那是 root 会 source 的文件，任何应用都能借这条命令在宿主上执行代码；
            # 而且每次切换应用都用旧副本盖掉 settings.conf，面板里刚改的设置会被还原。
            apkg=${a%%|*}; aspec=${a#*|}
            [ -f $SYS/settings.global ] && mv -f $SYS/settings.global $SYS/settings.conf    # 旧版留下的全局副本：恢复一次
            rm -f $SYS/settings.app
            # 0.23.28 及更早的桌面在打开微信读书时硬塞这一组参数（关翻页动画、刷新合并 25 ms）：用户没有给它单独设置过，
            # 却把全局的翻页动画和刷新合并关掉了（滑动时闪）。不认这组写死的值，按全局设置来。
            [ "$aspec" = "KFB_ANIM_MODE=0;KFB_SETTLE=25;KFB_MODE=0;KFB_CONTRAST=1.3" ] && aspec=
            if [ "$apkg" != home ] && [ -n "$aspec" ] && [ "$aspec" != "$a" ]; then
                printf '%s\n' "$aspec" | tr ';' '\n' | grep -E '^KFB_(CONTRAST|DITHER|FLASH|INVERT|LIGHTF|DARKEN|MODE|SETTLE|HOLD|SMOOTH|PACE|CLEAN|REAGL_MIN|QPASS|ANIM_MODE|ANIM_DIR|ANIM_STEPS|ANIM_MIN)=[0-9.]{1,6}$' > $SYS/settings.app.tmp
                [ -s $SYS/settings.app.tmp ] && mv -f $SYS/settings.app.tmp $SYS/settings.app || rm -f $SYS/settings.app.tmp
            fi
            settings_load 2>/dev/null || true
            settings_publish
            epd_send ANIM $KFB_ANIM_MODE $KFB_ANIM_DIR $KFB_ANIM_STEPS $KFB_ANIM_MIN
            epd_send SET $KFB_CONTRAST $KFB_DITHER $KFB_FLASH $KFB_INVERT $KFB_MODE $KFB_SETTLE ${KFB_LIGHTF:-0} ${KFB_DARKEN:-0}
            epd_send HOLD ${KFB_HOLD:-70}
            epd_send QPASS ${KFB_QPASS:-300}
            type epd_send_smooth >/dev/null 2>&1 && epd_send_smooth   # legacy 的显示桥没有 PACE/CLEAN/REAGL 时不定义
            type epd_send_scroll >/dev/null 2>&1 && epd_send_scroll   # 触摸滚动（legacy settings.sh）
            echo "$apkg" > $W/appprof.pkg
            t "app display $apkg: $(cat $SYS/settings.app 2>/dev/null | tr '\n' ';')" ;;   # 文件不存在时 "< 文件 2>/dev/null" 的报错照样进日志（重定向从左往右）
        status)  status_write ;;
        wa)      type wa_request >/dev/null 2>&1 && wa_request "$a" ;;   # 管理网页：参数只是一个编号，真正的请求在 VeraHome 的私有目录里
        apply)   settings_apply ;;
        rotate)  # rotate 0|90|180|270
            case "$a" in 0|90|180|270)
                touch $SYS/settings.conf
                grep -v "^KFB_ROTATE=" $SYS/settings.conf > $SYS/settings.conf.tmp 2>/dev/null
                echo "KFB_ROTATE=$a" >> $SYS/settings.conf.tmp; mv -f $SYS/settings.conf.tmp $SYS/settings.conf
                settings_load; settings_publish; rotate_apply ;;
            esac ;;
        invert)
            cur=$(sed -n 's/^KFB_INVERT=//p' $X/settings 2>/dev/null); [ "$cur" = 1 ] && n=0 || n=1
            echo "KFB_INVERT=$n" > $X/ctl/settings.new; settings_apply ;;
        light)   numeric "$a" && light_set "$a" && { [ "$a" = 0 ] && fl_off_sysfs; true; } ;;
        lightauto) case "$a" in 0|1) lipc-set-prop -i com.lab126.powerd flAuto "$a" 2>/dev/null; status_write ;; esac ;;   # 自动调整亮度（有光线传感器的机型）
        warmth)  numeric "$a" && lipc-set-prop -i com.lab126.powerd currentAmberLevel "$a" ;;
        sleeptime) numeric "$a" && [ "$a" -ge 60 ] && lipc-set-prop -i com.lab126.powerd screenSaverTimeout "$a" ;;
        fwd)     numeric "$a" && fwd_on "$a" ;;
        fwdoff)  fwd_off ;;
        fullupdate) # legacy：老机型安装器 static/bootstrap-legacy.sh（以前下的是 PW6 / k49 的 bootstrap.sh，它对 3.0 / 4.1 内核直接说
            # “请改用老机型安装脚本”退出，更新卡片一直停在“正在连接”：用户看到“点更新没反应”）。安装器失败时卡片报错。
            # 整包更新：后台脱离 cmd 进程，stop.sh 杀掉 cmd 也能继续下载/替换；安装器和它的输出放在 /tmp（以前在系统盘里：sh 一直开着它，安装器自己调的 stop.sh 就卸不掉系统盘）；实验机型沿用安装时的 model
            ( setsid sh -c 'm=$(cat /mnt/base-us/system/veradroid/model 2>/dev/null); case "$m" in ""|pw6) ;; *) export VD_MODEL="$m" VD_EXPERIMENTAL=1 ;; esac; [ "$(cat /mnt/base-us/veradroid/tested 2>/dev/null)" = emulator-only ] && export VD_EMUONLY_OK=1; export VD_FROM_HOME=1; B=/tmp/vd-fullupdate-bootstrap.sh; cd /; U=/dev/shm/veradroid/kfb/ctl/update.status; [ -f /mnt/base-us/veradroid/network.conf ] && . /mnt/base-us/veradroid/network.conf; CO=; [ -n "${VD_PROXY:-}" ] && CO="-x $VD_PROXY"; D=${VD_DIRECT-https://dl.wenjin.jp}; printf "state=checking\nstep=net\nlabel=正在连接更新服务器\nmode=manual\ntime=%s\n" "$(date +%s)" > $U.tmp && chmod 644 $U.tmp && mv -f $U.tmp $U; if { [ -n "$D" ] && curl $CO -fsSL --connect-timeout 8 --max-time 90 "$D/static/bootstrap-legacy.sh" -o "$B" && [ -s "$B" ] && sh -n "$B"; } || { curl $CO -fsSL --retry 3 --connect-timeout 20 --max-time 180 https://veradroid.wenjin.jp/static/bootstrap-legacy.sh -o "$B" && [ -s "$B" ] && sh -n "$B"; }; then sh "$B" || { grep -q "^state=error" $U 2>/dev/null || { printf "state=error\ncode=14\nlabel=更新没有装上\nline2=日志在 USB 里的 veradroid-install-detail.log\ntime=%s\n" "$(date +%s)" > $U.tmp && chmod 644 $U.tmp && mv -f $U.tmp $U; }; }; else printf "state=error\ncode=13\nlabel=连不上 VeraDroid 服务器（下载安装器失败）\nline2=可能被墙或服务器在维护，不是机器的问题。可以在 存储与连接 → 网络诊断 里看看是哪一步不通。\ntime=%s\n" "$(date +%s)" > $U.tmp && chmod 644 $U.tmp && mv -f $U.tmp $U; fi; rm -f "$B"; i=0; while [ $i -lt 3 ]; do sleep 2; P=$(cat /dev/shm/veradroid/pid 2>/dev/null); if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then break; fi; sh /mnt/base-us/veradroid/boot/veradroid.sh home; i=$((i+1)); done' > /tmp/veradroid-fullupdate.log 2>&1; echo $? > /tmp/veradroid-fullupdate.rc ) </dev/null >/dev/null 2>&1 & ;;
        uninstall) # 卸载 VeraDroid：交给 uninstall.sh（先拷到 tmpfs 再跑，因为它会删掉 $VD）。
            # 这里以前内联着旧流程：先 stop.sh（卸载 loop 系统盘）再 mv data/media，PW5 / KT5 / KT6 上 Android 里的书会被一起删掉。
            if [ -f "$VD/boot/uninstall.sh" ]; then
                cp "$VD/boot/uninstall.sh" /dev/shm/vd-uninstall.sh && ( cd / && setsid sh /dev/shm/vd-uninstall.sh ) </dev/null >/dev/null 2>&1 &
            else
                t "uninstall: $VD/boot/uninstall.sh missing, nothing done"
            fi ;;
        appdirs) # create/repair per-app download dirs (WeRead writes /sdcard/WeRead)
            case "$a" in ''|*[!A-Za-z0-9._]*) ;; *)
                mkdir -p $SYS/data/media/0/WeRead $SYS/data/media/0/Download 2>/dev/null
                chmod 777 $SYS/data/media/0 $SYS/data/media/0/WeRead $SYS/data/media/0/Download 2>/dev/null
                mkdir -p "$SYS/data/media/0/Android/data/$a" 2>/dev/null
                chmod 777 "$SYS/data/media/0/Android/data/$a" 2>/dev/null
                t "appdirs $a: WeRead/Android/data writable" ;;
            esac ;;
        uninstallapp) # 卸载普通应用：宿主 root 跑 pm uninstall，避免 deletePackage 需要 observer
            case "$a" in ''|*[!A-Za-z0-9._]*) ;; *)
                ( type compile_hold >/dev/null 2>&1 && compile_hold
                  r=$(adb_sh "cmd package uninstall --user 0 $a" 2>&1 | tr '\n' ' ')
                  type compile_release >/dev/null 2>&1 && compile_release
                  echo "$r" > $X/ctl/uninst.$a.tmp; mv -f $X/ctl/uninst.$a.tmp $X/ctl/uninst.$a ) & ;;
            esac ;;
        install) # 传书网页 / 商店 / OTA 的 apk：root 身份 pm install；接受 .upload 或 /data/local/tmp 绝对路径
            case "$a" in
              # 第三种：VeraHome 自己的数据目录（共享存储写不进去时的退路，Transfer.tmpDir）
              /data/local/tmp/*|/data/media/0/.upload/*|/data/data/com.veradroid.home/files/upload/*) ipath=$a ;;
              ''|*[!A-Za-z0-9._]*) ipath= ;;
              *) ipath=/data/media/0/.upload/$a ;;
            esac
            case "$ipath" in *[!A-Za-z0-9._/-]*|*..*) ipath= ;; esac     # 路径会进 root 的命令行：只允许普通文件名字符
            if [ -n "$ipath" ]; then
                iname=${ipath##*/}
                ( upload_dir_fix 2>/dev/null; chmod 777 $SYS/data/local/tmp 2>/dev/null
                  type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status state=installing "name=$iname" "start=$(date +%s)"
                  type compile_hold >/dev/null 2>&1 && compile_hold      # 后台编译拿着 PackageManager 的锁：先让开（compile.sh）
                  # pm/app_process crashes in libjavacore on this rootfs; cmd package
                  # shares PackageManager but initializes cleanly. It reads the APK
                  # from stdin with -S <size>.
                  sz=$(stat -c%s "$SYS$ipath" 2>/dev/null)
                  # 装的时候屏保不冻结 Android（sleep.sh 看 $W/installing，和 after_boot 同一个标记）：大应用在 512 MB 机型上
                  # 刚升级完要装十几分钟（系统自己的 dexopt 拿着安装锁），冻在中间会让安装卡住、PackageManager 被 Watchdog 杀
                  read -r ipid _ < /proc/self/stat; alive $W/installing || echo "$ipid" > $W/installing
                  r=$(adb_sh "cmd package install -r -d -g -S $sz < $ipath" 2>&1 | tr '\n' ' ')
                  [ "$(cat $W/installing 2>/dev/null)" = "$ipid" ] && rm -f $W/installing
                  # 失败时把 PackageManager 的代码换成看得懂的原因放在前面（传书网页显示“安装失败：”加这一行）
                  case "$r" in *Success*) ;; *) type apk_fail_reason >/dev/null 2>&1 && r="$(apk_fail_reason "$r" "$SYS$ipath")（$r）" ;; esac
                  # 有的阅读应用把自己也注册成桌面（稻壳阅读器）：装上以后按主页键会弹“选择主屏幕应用”。每次装完都把桌面固定回 VeraHome。
                  adb_sh 'cmd package set-home-activity com.veradroid.home/.HomeActivity' >/dev/null 2>&1
                  echo "$r" > $X/ctl/inst.$iname.tmp; mv -f $X/ctl/inst.$iname.tmp $X/ctl/inst.$iname
                  case "$r" in *Success*) ist=done ;; *) ist=error ;; esac
                  type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status "state=$ist" "name=$iname" "result=$r"
                  # 装好了：后台低优先级预编译（compile.sh；packages.list 变了，扫描会找到新装的包）
                  type compile_release >/dev/null 2>&1 && compile_release
                  case "$r" in *Success*) type compile_watch >/dev/null 2>&1 && compile_watch ;; esac ) &
            fi ;;
        saver)   type saver_cmd >/dev/null 2>&1 && saver_cmd "$a" ;;   # saver list | done（saver.sh）：屏保挑选页要看 veradroid/screensaver 里的图
        netdiag) # 桌面“网络诊断”（progress.sh 的 vp_netdiag），结果写 ctl/netdiag.status
            type vp_netdiag >/dev/null 2>&1 && ( vp_netdiag $X/ctl/netdiag.status ) </dev/null >/dev/null 2>&1 & ;;
        compile) type compile_cmd >/dev/null 2>&1 && compile_cmd "$a" ;;   # compile all | compile <包>（compile.sh）；进度 ctl/compile.status
        localupdate) # 离线更新文件夹（localupdate.sh）：check = 只看文件名，结果写 ctl/update.local；install = 校验并安装
            case "$a" in
                check) ( VD=$VD sh $VD/boot/lib/localupdate.sh check > $X/ctl/update.local.tmp 2>/dev/null; mv -f $X/ctl/update.local.tmp $X/ctl/update.local ) & ;;
                install) cp $VD/boot/lib/localupdate.sh $W/localupdate.sh && ( cd / && setsid sh $W/localupdate.sh install --start ) </dev/null >/dev/null 2>&1 & ;;
            esac ;;
        storage) # storage info | resize <MB> | auto on|off（storage.sh）。结果：ctl/storage、ctl/storage.result
            type st_cmd >/dev/null 2>&1 && case "$a" in
                info) ( st_cmd info ) & ;;
                "resize "*) sa=${a#resize }; st_num "$sa" && ( st_cmd resize "$sa" ) & ;;
                "auto on"|"auto off") st_cmd auto "${a#auto }" ;;
            esac ;;
        bt)      type bt_cmd >/dev/null 2>&1 && bt_cmd $a ;;   # bt map volume|page|dpad|raw / bt swap 0|1（bt.sh）
        optimize) ( opt_run ) & ;;   # 面板的“优化机器状态”（opt_run）
        poke)    lipc-set-prop -i com.lab126.powerd touchScreenSaverTimeout 1 2>/dev/null ;;
        wifi)    numeric "$a" && lipc-set-prop -i com.lab126.cmd wirelessEnable "$a" ;;
        wifilist)    ( wifi_list ) & ;;
        wificonnect) ( wifi_connect "$a" ) & ;;   # 参数在 wifi_connect 里校验（十六进制、必须是已保存的网络）
        dns)     case "$a" in
                     auto|aliyun|tencent|google)
                         grep -v "^DNS_MODE=" $SYS/settings.conf > $SYS/settings.conf.tmp 2>/dev/null
                         echo "DNS_MODE=$a" >> $SYS/settings.conf.tmp; mv -f $SYS/settings.conf.tmp $SYS/settings.conf
                         settings_load; settings_publish; dns_apply ;;
                 esac ;;
        esac
    done
}
