# SPDX-License-Identifier: GPL-3.0-or-later
# 桌面 / 面板发给宿主的命令：一个 FIFO（$X/ctl/cmd），宿主阻塞读，没有命令时一点 CPU 都不用。
# 一行一条：exit stop restart diag diag-upload feedback clear invert status apply | light N | warmth N | wifi 0|1
#   compile all|<包> | localupdate check|install（完整列表见 docs/host-settings.md）
numeric() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }
# 调亮度（桌面面板的亮度条、管理网页、优化建议都走这里）。自动亮度开着（powerd flAuto=1）时 powerd 按光线传感器隔一会儿
# 就把亮度改回去，看起来就是“调了没反应”（PW5 签名版等带传感器的机型；桌面的“自动”开关只在宿主认出传感器时才显示，
# 认不出时用户关不掉它）。手动调了就是要手动：先把自动关掉。设完读回来，对不上就记一行（诊断里看得到是哪一步没生效）。
light_set() {
    ls_p=com.lab126.powerd; ls_a=
    if [ "$(lipc-get-prop $ls_p flAuto 2>/dev/null)" = 1 ]; then
        lipc-set-prop -i $ls_p flAuto 0 2>/dev/null && ls_a=1 && t "light: auto brightness was on (powerd flAuto=1), turned it off for the manual level $1"
    fi
    lipc-set-prop -i $ls_p flIntensity "$1" >/dev/null 2>&1 || t "light $1: lipc-set-prop flIntensity failed"
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
    # slirp 模式下另外放行过的 Kindle 防火墙端口（fwd_on）
    if [ -f $W/fwd.ipt ]; then iptables -D INPUT -i "$(cut -d: -f2 $W/fwd.ipt)" -p tcp --dport "$(cut -d: -f1 $W/fwd.ipt)" -j ACCEPT 2>/dev/null; rm -f $W/fwd.ipt; fi
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
    if [ -n "$id" ]; then echo $id > $W/fwd.id; t "fwd: port $1 open (id $id)"; else t "fwd failed: $r"; return 1; fi
    # slirp4netns 在 Kindle 这边监听 0.0.0.0:<端口>，但 Kindle 的防火墙（INPUT 默认 DROP，wlan0 只放行已建立的连接）
    # 把手机 / 电脑从局域网发来的新连接全丢掉：网页打不开（PW5 真机；pw5-5192 模拟器：只有 slirp 转发时连接超时，
    # 加上这条 ACCEPT 后 200）。和共用网络模式一样临时放行这一个端口，关传书 / 停 Android 时撤掉。
    fdev=$(type wifi_dev >/dev/null 2>&1 && wifi_dev); [ -n "$fdev" ] || fdev=wlan0
    iptables -C INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null || iptables -I INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null
    if iptables -C INPUT -i $fdev -p tcp --dport $1 -j ACCEPT 2>/dev/null; then echo "$1:$fdev" > $W/fwd.ipt; t "fwd: port $1 open on the Kindle firewall ($fdev)"
    else t "fwd: could not open port $1 on the Kindle firewall ($fdev)"; fi
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
    tar -czf "$tgz" -C "$d" . 2>/dev/null || { rm -f "$tgz"; diag_result ok=0 "error=archive failed (disk full?)"; return 1; }
    [ -s "$tgz" ] || { rm -f "$tgz"; diag_result ok=0 "error=empty archive (disk full?)"; return 1; }
    id=$(cat "$SYS/device-id" 2>/dev/null | tr -cd '0-9A-Za-z' | cut -c1-40)
    [ -n "$id" ] || id=unknown
    hwmodel=$(cat "$SYS/model" 2>/dev/null | tr -cd '0-9A-Za-z_.-' | cut -c1-32)
    ver=$(cat "$SYS/version" 2>/dev/null | tr -cd '0-9A-Za-z_.-' | cut -c1-32)
    r=$(curl -sS --max-time 120 -H "Expect:" -w '\nhttp=%{http_code}' -F "device_id=$id" -F "model=$hwmodel" -F "version=$ver" -F "file=@$tgz" "$DIAG_URL" 2>&1)
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

# ---- 无线网络（Kindle 的 com.lab126.wifid）----
# 已保存的：列表写到 $X/ctl/wifi.list，一行一个，第一个字符是 * 表示当前连着的，空格表示没连；后面是网络名。
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
# cmState 是字符串属性：不带 -s 时 lipc-get-prop 先按整数读，每次在 /var/log/messages 里记一条 “LIPC access error”
wifi_cm() { lipc-get-prop -s com.lab126.wifid cmState 2>/dev/null; }
wifi_netid() { # 网络名 -> 已保存配置的 netid（没有就空）
    lipc-hash-prop -n com.lab126.wifid profileData 2>/dev/null | awk -v want="$1" '
        /^[[:space:]]*netid = \(/ { v = $0; sub(/.*\(/, "", v); sub(/\).*/, "", v); id = v }
        /^[[:space:]]*essid = "/ { v = $0; sub(/^[[:space:]]*essid = "/, "", v); sub(/"[[:space:]]*$/, "", v); if (v == want) { print id; exit } }'
}
wifi_list() {
    cur=$(wifi_current)
    wifi_saved | while IFS= read -r n; do
        if [ -n "$cur" ] && [ "$n" = "$cur" ]; then printf '*%s\n' "$n"; else printf ' %s\n' "$n"; fi
    done > $X/ctl/wifi.list.tmp
    mv -f $X/ctl/wifi.list.tmp $X/ctl/wifi.list
}
# 附近的网络（wifiscan）：先让 wifid 扫一遍（最多等 8 秒），再写 $X/ctl/wifi.scan，一行一个：
#   已保存 y|n <TAB> 要密码 y|n <TAB> 信号 0-5 <TAB> 加密方式 <TAB> 网络名
# 按信号从强到弱，同名只留最强的，隐藏网络（没有名字）不列。
wifi_scanlist() {
    lipc-hash-prop -n com.lab126.wifid scanList 2>/dev/null | awk '
        function out() { if (e != "" && !(e in seen)) { seen[e] = 1; printf "%s\t%s\t%s\t%s\t%s\n", (k == "yes" ? "y" : "n"), (s == "yes" ? "y" : "n"), g, m, e } e = ""; k = ""; s = ""; g = 0; m = "" }
        /^Hash Index/ { out() }
        /^[[:space:]]*essid = "/ { v = $0; sub(/^[[:space:]]*essid = "/, "", v); sub(/"[[:space:]]*$/, "", v); e = v }
        /^[[:space:]]*known = "/ { k = ($0 ~ /"yes"/) ? "yes" : "no" }
        /^[[:space:]]*secured = "/ { s = ($0 ~ /"yes"/) ? "yes" : "no" }
        /^[[:space:]]*signal = \(/ { v = $0; sub(/.*\(/, "", v); sub(/\).*/, "", v); g = v + 0 }
        /^[[:space:]]*key_mgmt = "/ { v = $0; sub(/^[[:space:]]*key_mgmt = "/, "", v); sub(/"[[:space:]]*$/, "", v); m = v }
        END { out() }' | sort -t "$(printf '\t')" -k3,3nr | awk -F '\t' '!($5 in s) { s[$5] = 1; print }' | head -40
}
wifi_scan() {
    command -v lipc-hash-prop >/dev/null 2>&1 || { : > $X/ctl/wifi.scan; return 0; }
    [ "$(wifi_state)" = 1 ] || { wifi_scanlist > $X/ctl/wifi.scan.tmp; mv -f $X/ctl/wifi.scan.tmp $X/ctl/wifi.scan; return 0; }
    lipc-set-prop com.lab126.wifid scan "" 2>/dev/null
    i=0
    while [ $i -lt 8 ]; do
        sleep 1; i=$((i + 1))
        [ "$(lipc-get-prop -s com.lab126.wifid scanState 2>/dev/null)" = idle ] && [ $i -ge 2 ] && break
    done
    wifi_scanlist > $X/ctl/wifi.scan.tmp
    mv -f $X/ctl/wifi.scan.tmp $X/ctl/wifi.scan
}
# 网络名的 UTF-8 十六进制 -> 文字；不合格（不是十六进制、超过 32 字节、有控制字符）返回 1
wifi_unhex() {
    case "$1" in ''|*[!0-9a-f]*) return 1 ;; esac
    [ $((${#1} % 2)) -eq 0 ] && [ ${#1} -le "${2:-64}" ] || return 1
    printf '%s' "$1" | sed 's/../&\n/g' | grep -qE '^(0[0-9a-f]|1[0-9a-f]|7f)$' && return 1
    printf "$(printf '%s' "$1" | sed 's/../\\x&/g')"
}
# 新网络的密码不走命令 FIFO（所有应用都能写、命令会进日志）：VeraHome 用管理请求 wifipsk（webadmin.sh，只认 VeraHome 自己
# 私有目录里的请求文件）交过来，存在 $W/wifi.psk（root 0600，第一行网络名十六进制、第二行密码十六进制），随后的 wificonnect 取走就删。
wifi_psk_put() { # 网络名十六进制 密码十六进制
    wifi_unhex "$1" >/dev/null || { echo "error=bad name"; return 0; }
    case "$2" in ''|*[!0-9a-f]*) echo "error=bad password"; return 0 ;; esac
    ( umask 077; printf '%s\n%s\n' "$1" "$2" > $W/wifi.psk.tmp && mv -f $W/wifi.psk.tmp $W/wifi.psk ) && echo "ok=1" || echo "error=store"
}
# wificonnect <网络名的 UTF-8 字节，十六进制>：十六进制只有 0-9a-f，不会有引号、空格、换行能混进 root 的命令行；
# 解出来不许有控制字符。之后只作为一个带引号的参数交给 lipc，不经过 eval。
#   已保存的网络：com.lab126.wifid cmConnect（以前用的 com.lab126.cmd ensureConnection "wifi:名字" 只保证“有个 Wi-Fi 连着”：
#     已经连着别的网络时什么都不做，等 30 秒超时——这就是“在安卓里切换网络总失败”的原因，测试机上实测 cmState 一直是原来的网络）。
#   新网络：先 createProfile（不把密码上传给亚马逊：store_nw_user_pref = 0），再 cmConnect；连不上就把刚建的配置删掉。
#   连不上时回到原来连着的网络（不让 Kindle 断网）。
# 结果写 $X/ctl/wifi.result：ok=1|0、essid（十六进制）、error=timeout|auth|notfound|needpsk|badpsk|create|wifioff|bad name、restored|kept=原来网络的十六进制。
wifi_result() { printf '%s\n' "$@" > $X/ctl/wifi.result.tmp && mv -f $X/ctl/wifi.result.tmp $X/ctl/wifi.result; }
wifi_connect() {
    hex=$1
    want=$(wifi_unhex "$hex") || { t "wificonnect: rejected (bad name)"; rm -f $W/wifi.psk; wifi_result ok=0 "error=bad name"; return 1; }
    psk=
    if [ -f $W/wifi.psk ]; then
        if [ "$(sed -n 1p $W/wifi.psk)" = "$hex" ] && [ $(( $(date +%s) - $(stat -c %Y $W/wifi.psk 2>/dev/null || echo 0) )) -lt 180 ]; then
            psk=$(wifi_unhex "$(sed -n 2p $W/wifi.psk)" 128) || psk=BAD
        fi
        rm -f $W/wifi.psk
    fi
    orig=$(wifi_current)
    if [ "$(wifi_state)" != 1 ]; then
        wifi_set 1
        i=0; while [ $i -lt 15 ] && [ "$(lipc-get-prop com.lab126.wifid enable 2>/dev/null)" != 1 ]; do sleep 1; i=$((i + 1)); done
        [ "$(wifi_state)" = 1 ] || { t "wificonnect: wifi did not come on"; wifi_result ok=0 "error=wifioff" "essid=$hex"; return 1; }
        # 刚打开时 wifid 会自己连它最喜欢的网络：等它安定下来再切，免得两边抢
        i=0; while [ $i -lt 12 ]; do sleep 1; i=$((i + 1)); case "$(wifi_cm)" in CONNECTED|NA|DISCONNECTED|'') [ $i -ge 3 ] && break ;; esac; done
        orig=$(wifi_current)
    fi
    if [ -n "$orig" ] && [ "$orig" = "$want" ]; then wifi_result ok=1 "essid=$hex"; wifi_list; status_write; return 0; fi
    created=
    if [ -z "$(wifi_netid "$want")" ]; then
        # 没保存过的网络：看扫描结果要不要密码。名字里有引号 / 反斜杠的写不进 lipc 的哈希格式，只能在 Kindle 的设置里连
        case "$want" in *'"'*|*'\'*) t "wificonnect: name has quote/backslash"; rm -f $W/wifi.psk; wifi_result ok=0 "error=bad name" "essid=$hex"; return 1 ;; esac
        sec=$(wifi_scanlist | awk -F '\t' -v w="$want" '$5 == w { print $2; exit }')
        if [ "$psk" = BAD ]; then wifi_result ok=0 "error=badpsk" "essid=$hex"; return 1; fi
        if [ -n "$psk" ]; then
            # 密码：WPA 要 8–63 个字符（或 64 位十六进制）；引号和反斜杠 lipc 的哈希格式里没法安全地写进去
            plen=$(printf '%s' "$psk" | wc -c)
            case "$psk" in *'"'*|*'\'*) t "wificonnect: password has quote/backslash"; wifi_result ok=0 "error=badpsk" "essid=$hex"; return 1 ;; esac
            if [ "$plen" -lt 8 ] || [ "$plen" -gt 64 ]; then wifi_result ok=0 "error=badpsk" "essid=$hex"; return 1; fi
            printf '{ essid = "%s", secured = "yes", psk = "%s", store_nw_user_pref = (0) }\n' "$want" "$psk" | lipc-hash-prop com.lab126.wifid createProfile >/dev/null 2>&1
        elif [ "$sec" = n ]; then
            printf '{ essid = "%s", secured = "no", store_nw_user_pref = (0) }\n' "$want" | lipc-hash-prop com.lab126.wifid createProfile >/dev/null 2>&1
        else
            t "wificonnect: new network needs a password"; wifi_result ok=0 "error=needpsk" "essid=$hex"; return 1
        fi
        psk=
        # 新配置要过一两秒才出现在 profileData 里（测试机实测）
        i=0; while [ $i -lt 8 ] && [ -z "$(wifi_netid "$want")" ]; do sleep 1; i=$((i + 1)); done
        [ -n "$(wifi_netid "$want")" ] || { t "wificonnect: createProfile failed"; wifi_result ok=0 "error=create" "essid=$hex"; return 1; }
        created=1
    fi
    # 不在附近的网络不用干等 40 秒：先扫一遍（最多 8 秒），扫到了别的网络却没有它，就直接说找不到
    # （扫描结果是空的——扫描没成功——或者是隐藏网络，照样去连）
    if [ -z "$created" ]; then
        wifi_scan
        if [ -s $X/ctl/wifi.scan ] && ! awk -F '\t' -v w="$want" '$5 == w { f = 1 } END { exit !f }' $X/ctl/wifi.scan; then
            t "wificonnect: not in range"
            back=; [ -n "$orig" ] && back=$(printf '%s' "$orig" | od -An -tx1 | tr -d ' \n')
            wifi_result ok=0 "error=notfound" "essid=$hex" ${back:+"kept=$back"}
            wifi_list; status_write; return 1
        fi
    fi
    t "wificonnect: switching networks${created:+ (new profile)}"
    m0=$(wc -l < /var/log/messages 2>/dev/null || echo 0)
    lipc-set-prop com.lab126.wifid cmConnect "$want"
    i=0; ok=
    while [ $i -lt 40 ]; do
        sleep 1; i=$((i + 1))
        if [ "$(wifi_current)" = "$want" ] && [ "$(wifi_cm)" = CONNECTED ]; then ok=1; break; fi
    done
    if [ -n "$ok" ]; then
        t "wificonnect: connected after ${i}s"
        wifi_result ok=1 "essid=$hex"
    else
        why=timeout
        tail -n +$((m0 + 1)) /var/log/messages 2>/dev/null | grep -qiE 'WRONG_KEY|auth_failures|4-Way Handshake|pre-shared key may be incorrect|reason=15|AuthFail' && why=auth
        [ "$why" = timeout ] && ! wifi_scanlist | awk -F '\t' -v w="$want" '$5 == w { f = 1 } END { exit !f }' && why=notfound
        t "wificonnect: failed ($why)"
        if [ -n "$created" ]; then id=$(wifi_netid "$want"); [ -n "$id" ] && lipc-set-prop -i com.lab126.wifid deleteProfile "$id"; fi
        back=; key=kept
        if [ -n "$orig" ] && [ "$(wifi_current)" != "$orig" ]; then
            key=restored
            lipc-set-prop com.lab126.wifid cmConnect "$orig"
            i=0; while [ $i -lt 25 ]; do sleep 1; i=$((i + 1)); [ "$(wifi_current)" = "$orig" ] && [ "$(wifi_cm)" = CONNECTED ] && break; done
        fi
        [ -n "$orig" ] && [ "$(wifi_current)" = "$orig" ] && back=$(printf '%s' "$orig" | od -An -tx1 | tr -d ' \n')
        # restored = 断开过、已经连回原来的网络；kept = 一直连着原来的网络
        wifi_result ok=0 "error=$why" "essid=$hex" ${back:+"$key=$back"}
    fi
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
        diag)    ( diag_save; touch $X/ctl/diag.done ) 3>&- & ;;
        diag-upload) ( diag_upload ) 3>&- & ;;        # 保存诊断并把归档上传到服务器 data/diagnostics
        feedback)    ( diag_upload note ) 3>&- & ;;   # 同上，另把用户在“问题反馈”里写的话（ctl/feedback.txt）放进归档
        clear)   epd_send FULL ;;
        jankdump) type jankrec_dump >/dev/null 2>&1 && ( jankrec_dump ) </dev/null 3>&- >>$LOG 2>&1 & ;;   # 卡顿记录导出（lib/jankrec.sh）
        screenshot)
            mkdir -p $SYS/data/media/0/Screenshots
            vd_timeout 30 $(vd_in) /system/bin/screencap -p /data/media/0/Screenshots/screen-$(date +%Y%m%d-%H%M%S).png >/dev/null 2>&1 && t "screenshot saved" ;;
        fg)      type appprof_fg >/dev/null 2>&1 && appprof_fg "$a" ;;       # 前台应用变了（VeraHome 的任务栈监听），见 appprof.sh
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
                printf '%s\n' "$aspec" | tr ';' '\n' | grep -E '^KFB_(CONTRAST|DITHER|FLASH|INVERT|LIGHTF|DARKEN|MODE|SETTLE|HOLD|SMOOTH|PACE|CLEAN|REAGL_MIN|SETTLE2|SCROLLDU|IMG|SCROLLDELAY|SCROLLSTYLE|GCN|NEWWIN|MONO|MAXDEFER|FLIPINPUT|TURNHOLD|QPASS|ANIM_MODE|ANIM_DIR|ANIM_STEPS|ANIM_MIN)=[0-9.]{1,6}$' > $SYS/settings.app.tmp
                [ -s $SYS/settings.app.tmp ] && mv -f $SYS/settings.app.tmp $SYS/settings.app || rm -f $SYS/settings.app.tmp
            fi
            settings_load 2>/dev/null || true
            settings_publish
            epd_send ANIM $KFB_ANIM_MODE $KFB_ANIM_DIR $KFB_ANIM_STEPS $KFB_ANIM_MIN
            epd_send SET $KFB_CONTRAST $KFB_DITHER $KFB_FLASH $KFB_INVERT $KFB_MODE $KFB_SETTLE ${KFB_LIGHTF:-0} ${KFB_DARKEN:-0}
            epd_send HOLD ${KFB_HOLD:-70}
            epd_send_smooth
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
        light)   numeric "$a" && light_set "$a" ;;
        lightauto) case "$a" in 0|1) lipc-set-prop -i com.lab126.powerd flAuto "$a" 2>/dev/null; status_write ;; esac ;;   # 自动调整亮度（有光线传感器的机型）
        warmth)  numeric "$a" && lipc-set-prop -i com.lab126.powerd currentAmberLevel "$a" ;;
        sleeptime) numeric "$a" && [ "$a" -ge 60 ] && lipc-set-prop -i com.lab126.powerd screenSaverTimeout "$a" ;;
        fwd)     numeric "$a" && fwd_on "$a" ;;
        fwdoff)  fwd_off ;;
        fullupdate) # 整包更新：后台脱离 cmd 进程，stop.sh 杀掉 cmd 也能继续下载/替换；安装器和它的输出放在 /tmp（以前在系统盘里：sh 一直开着它，安装器自己调的 stop.sh 就卸不掉系统盘）；实验机型沿用安装时的 model
            ( setsid sh -c 'm=$(cat /mnt/base-us/system/veradroid/model 2>/dev/null); case "$m" in ""|pw6|cs) ;; *) export VD_MODEL="$m" VD_EXPERIMENTAL=1 ;; esac; B=/tmp/vd-fullupdate-bootstrap.sh; cd /; U=/dev/shm/veradroid/kfb/ctl/update.status; printf "state=checking\nstep=net\nlabel=正在连接更新服务器\nmode=manual\ntime=%s\n" "$(date +%s)" > $U.tmp && chmod 644 $U.tmp && mv -f $U.tmp $U; if curl -fsSL --retry 3 --connect-timeout 20 https://veradroid.wenjin.jp/bootstrap.sh -o "$B"; then sh "$B"; else printf "state=error\ncode=13\nlabel=连不上 VeraDroid 服务器（下载安装器失败）\nline2=可能被墙或服务器在维护，不是机器的问题。可以在 存储与连接 → 网络诊断 里看看是哪一步不通。\ntime=%s\n" "$(date +%s)" > $U.tmp && chmod 644 $U.tmp && mv -f $U.tmp $U; fi; rm -f "$B"; i=0; while [ $i -lt 3 ]; do sleep 2; P=$(cat /dev/shm/veradroid/pid 2>/dev/null); if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then break; fi; sh /mnt/base-us/veradroid/boot/veradroid.sh home; i=$((i+1)); done' > /tmp/veradroid-fullupdate.log 2>&1; echo $? > /tmp/veradroid-fullupdate.rc ) </dev/null >/dev/null 2>&1 3>&- & ;;
        uninstall) # 卸载 VeraDroid：交给 uninstall.sh（先拷到 tmpfs 再跑，因为它会删掉 $VD）。
            # 这里以前内联着旧流程：先 stop.sh（卸载 loop 系统盘）再 mv data/media，PW5 / KT5 / KT6 上 Android 里的书会被一起删掉。
            if [ -f "$VD/boot/uninstall.sh" ]; then
                cp "$VD/boot/uninstall.sh" /dev/shm/vd-uninstall.sh && ( cd / && setsid sh /dev/shm/vd-uninstall.sh ) </dev/null >/dev/null 2>&1 3>&- &
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
                  echo "$r" > $X/ctl/uninst.$a.tmp; mv -f $X/ctl/uninst.$a.tmp $X/ctl/uninst.$a ) 3>&- & ;;
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
                  # 桌面的进度卡片（我的 页）：正在装哪个、从什么时候开始（大应用要装几分钟）
                  type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status state=installing "name=$iname" "start=$(date +%s)"
                  # 不是 APK、32 位库不全（apk_precheck / apk_abi_check，afterboot.sh）：不装，直接把原因给传书网页 / 商店
                  if type apk_precheck >/dev/null 2>&1 && bad=$(apk_precheck "$SYS$ipath"); then
                      echo "$bad" > $X/ctl/inst.$iname.tmp; mv -f $X/ctl/inst.$iname.tmp $X/ctl/inst.$iname
                      type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status state=error "name=$iname" "result=$bad"
                      t "install: $iname refused before pm: $bad"
                      exit 0
                  fi
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
                  # 装完 / 更新完：按清单停用这个应用的后台组件（appslim.sh；新版本可能换了组件名）
                  case "$r" in *Success*) type appslim_all >/dev/null 2>&1 && appslim_all install ;; esac
                  case "$r" in *Success*) type compile_watch >/dev/null 2>&1 && compile_watch
                      type shizuku_start >/dev/null 2>&1 && shizuku_start ;; esac ) 3>&- &
            fi ;;
        netdiag) # 桌面“网络诊断”：Wi-Fi / 域名解析 / 服务器 / 速度，结果写 ctl/netdiag.status（progress.sh 的 vp_netdiag）
            type vp_netdiag >/dev/null 2>&1 && ( vp_netdiag $X/ctl/netdiag.status ) </dev/null >/dev/null 2>&1 3>&- & ;;
        saver)   type saver_cmd >/dev/null 2>&1 && saver_cmd "$a" ;;   # saver list | done（saver.sh）：屏保挑选页要看 veradroid/screensaver 里的图
        appslim) type appslim_cmd >/dev/null 2>&1 && ( appslim_cmd "$a" ) 3>&- & ;;   # appslim <包> | all | restore（appslim.sh，后台瘦身）
        compile) type compile_cmd >/dev/null 2>&1 && compile_cmd "$a" ;;   # compile all | compile <包>（compile.sh）；进度 ctl/compile.status
        webview) case "$a" in get|cancel) type webview_cmd >/dev/null 2>&1 && webview_cmd "$a" ;; esac ;;   # 新网页内核下载 / 取消（container.sh webview_dl，进度 ctl/webview.status）
        localupdate) # 离线更新文件夹（localupdate.sh）：check = 只看文件名，结果写 ctl/update.local；install = 校验并安装
            case "$a" in
                check) ( VD=$VD sh $VD/boot/lib/localupdate.sh check > $X/ctl/update.local.tmp 2>/dev/null; mv -f $X/ctl/update.local.tmp $X/ctl/update.local ) 3>&- & ;;
                install) cp $VD/boot/lib/localupdate.sh $W/localupdate.sh && ( cd / && setsid sh $W/localupdate.sh install --start ) </dev/null >/dev/null 2>&1 3>&- & ;;
            esac ;;
        storage) # storage info | resize <MB> | auto on|off（storage.sh）。结果：ctl/storage、ctl/storage.result
            type st_cmd >/dev/null 2>&1 && case "$a" in
                info) ( st_cmd info ) 3>&- & ;;
                "resize "*) sa=${a#resize }; st_num "$sa" && ( st_cmd resize "$sa" ) 3>&- & ;;
                "auto on"|"auto off") st_cmd auto "${a#auto }" ;;
            esac ;;
        bt)      type bt_cmd >/dev/null 2>&1 && bt_cmd "$a" ;;   # bt map/swap/bridge、scan/list/bond/connect/disconnect/unbond/enable/disable（bt.sh，参数在那里校验）
        optimize) ( opt_run ) 3>&- & ;;   # 面板的“优化机器状态”（opt_run）
        poke)    lipc-set-prop -i com.lab126.powerd touchScreenSaverTimeout 1 2>/dev/null ;;
        wifi)    case "$a" in 0|1) wifi_set "$a"; status_write ;; esac ;;   # 只开关 Wi-Fi，不动飞行模式和蓝牙（common.sh wifi_set）
        wifilist)    ( wifi_list ) 3>&- & ;;
        wificonnect) ( wifi_connect "$a" ) 3>&- & ;;   # 参数在 wifi_connect 里校验（十六进制、没有控制字符）；新网络的密码另走管理请求 wifipsk
        wifiscan)    ( wifi_scan ) 3>&- & ;;
        dns)     case "$a" in
                     auto|aliyun|tencent|google)
                         grep -v "^DNS_MODE=" $SYS/settings.conf > $SYS/settings.conf.tmp 2>/dev/null
                         echo "DNS_MODE=$a" >> $SYS/settings.conf.tmp; mv -f $SYS/settings.conf.tmp $SYS/settings.conf
                         settings_load; settings_publish; dns_apply ;;
                 esac ;;
        esac
    done
}
