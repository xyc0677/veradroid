# SPDX-License-Identifier: GPL-3.0-or-later
# 暂停 / 恢复 Kindle 界面（“普通模式”：只暂停，不停 lab126_gui 和 KPPMainApp）。
#   暂停：存下当前帧缓冲 → 关 pillow → SIGSTOP awesome 和 volumd → 禁用翻页键设备 → 停一小批服务（记下来）→ core_pattern 改 |/bin/true
#   恢复：写回帧缓冲 → 整屏刷新 → 启用输入设备 → SIGCONT → 开 pillow → 叫应用管理器回书库主页 → 起回服务 → 还原 core_pattern
# 不停：lab126_gui（电源键和休眠要靠它）、KPPMainApp（冷启动要 30 秒）、powerd、wifid、otav3 / otaupd。
FWD=$W/fw
# Android 在前台时怎么处置 Kindle 自己的界面和后台程序：设置 KINDLE_UI（lib/settings.sh 的 kindle_ui_mode 把它解析成下面三种之一）。
#   keep    只做上面这些（pillow 关、awesome / volumd SIGSTOP、停 FW_BASE 那一小批服务）。
#   freeze  另外把 FW_FREEZE_PROCS 里的进程 SIGSTOP，回 Kindle 前 SIGCONT。不腾内存，只是保证它们不再醒来；返回和 keep 一样快。
#           只能冻“没有人会通过 lipc 去问它”的进程：KT5 模拟器实测，把 Java 框架（cvm）SIGSTOP 之后电源键 46 秒内都没反应
#           （powerd 要读 com.lab126.kaf 的 frameworkStarted，对方冻着就一直等），所以 cvm 永远不在冻结名单里。
#   stop    把 Java 框架（upstart 任务 framework，进程 cvm）和书库程序（kppmainapp）停掉，内存真正让出来
#           （KT5 模拟器实测：可用内存 +50 MB、交换区空出约 70 MB；PW6 真机上 java + KPPMainApp 约 70 MB 常驻 + 110 MB 在 zram 里）。
#           代价是返回 Kindle 时框架要冷启动（约一分钟，屏幕上有提示），而且返回 = 完全退出 Android（settings_load 强制 RETURN_MODE=off）。
#           框架一停，依赖它的任务（fastmetrics、kmc、whisperstore、poll_daemons、progressivedownloads、juno-acs-helper……）跟着停，
#           start framework 不会把它们带回来：停之前记下当时在跑的全部任务（$FWD/running0），回来时把没回来的逐个起回去。
#           框架停着时 powerd 读不到 frameworkStarted，会当成“还在开机”而不理电源键（模拟器实测如此）：
#           bin/vdkaf（tools/vdkaf/vdkaf.c）在这期间顶替 com.lab126.kaf 发布 frameworkStarted=1。没有 vdkaf 就退回 keep。
# 低内存模式（lowmem）下书库程序 KPPMainApp 在 keep / freeze 时也停（一直如此：它常驻约 40 MB，冷启动十几到三十秒）。
# 不管哪种方式都不碰：powerd、lipc / dbus、appmgrd、wifid / wifim / cmd（Wi-Fi）、lab126_gui、X。
# 恢复只有一个入口 framework_start（stop.sh、冻结返回、容器意外退出后看门狗调的 stop.sh 都走它）：
# 它按 $FWD 里记下的东西恢复，不依赖当时的设置，所以中途改设置或脚本被杀都不会留下冻着 / 停着的 Kindle 界面。
FW_BASE="mtp stored webreader kfxreader kfxview todo tmd rcm archive scanner"
# freeze 名单只放实测过“冻着也没人等它”的进程（KT5 模拟器，按一下电源键到 powerd 进屏保的时间）：
#   什么都不冻 0.7 秒；冻 KPPMainApp 0.6 秒（可以冻）；冻 fastmetrics 64 秒、期间 powerd 自己的 lipc 属性全部超时（不能冻：
#   powerd 同步给它报指标）；冻 cvm 46 秒以上没反应（不能冻）。
# PW6 上还有 weblabdaemon、minerva_service、KindleDocumentIndexerService、KindleSyncService，模拟器镜像里没有这些进程，
# 没法做同样的测试，不放进来：冻着的 lipc 对端会让调用方（包括 powerd）挂住。
FW_FREEZE_PROCS="KPPMainApp"

fw_services() {
    FW_SERVICES="$FW_BASE"
    [ -f /etc/upstart/statusbar.conf ] && FW_SERVICES="$FW_SERVICES statusbar"
    FW_MODE=$(kindle_ui_mode)
    if [ "$FW_MODE" = stop ]; then
        if [ ! -f /etc/upstart/framework.conf ]; then FW_MODE=keep
        elif [ ! -x "$(vdkaf_bin)" ]; then
            t "fw: KINDLE_UI=stop wanted but $(vdkaf_bin) is missing (the power key would stop working): keeping the framework"; FW_MODE=keep
        elif [ "$(cat $SYS/vdkaf.bad 2>/dev/null)" = "$(vdkaf_id)" ]; then
            # 这个固件 + 这个 vdkaf 上次没能顶替成功（见 framework_stop 里的自检）：不再停框架，免得每次进来都白白重启它一遍
            t "fw: vdkaf did not work on this firmware last time ($SYS/vdkaf.bad): keeping the framework"; FW_MODE=keep
        fi
    fi
    if { lowmem || [ "$FW_MODE" = stop ]; } && [ -f /etc/upstart/kppmainapp.conf ]; then FW_SERVICES="$FW_SERVICES kppmainapp"; fi
    [ "$FW_MODE" = stop ] && FW_SERVICES="$FW_SERVICES framework"
    return 0
}
# 当前在跑的 upstart 任务名（一行一个）
fw_running() { initctl list 2>/dev/null | awk '$2 ~ /^start\// {print $1}'; }

# “这个固件 + 这个 vdkaf”的标识：自检失败时记进 $SYS/vdkaf.bad，固件升级或 vdkaf 换了就自动再试一次
vdkaf_bin() { if [ -e /lib/ld-linux-armhf.so.3 ]; then echo "$VD/bin/vdkaf"; else echo "$VD/bin/vdkaf-sf"; fi; }
vdkaf_id() { echo "$(cat /etc/prettyversion.txt 2>/dev/null | head -1) $(wc -c < "$(vdkaf_bin)" 2>/dev/null)"; }
# 框架停着的时候顶替 com.lab126.kaf（见上）。等框架真的退出、名字空出来再起；框架回来之前先停掉。
vdkaf_start() {
    [ -x "$(vdkaf_bin)" ] || return 0
    ( cd / && exec "$(vdkaf_bin)" ) </dev/null >>$W/vdkaf.log 2>&1 &
    echo $! > $W/vdkaf.pid
    t "fw: vdkaf started (pid $!)"
}
vdkaf_stop() {
    p=$(cat $W/vdkaf.pid 2>/dev/null); rm -f $W/vdkaf.pid
    [ -n "$p" ] && kill "$p" 2>/dev/null && t "fw: vdkaf stopped"
    return 0
}
# freeze：记下冻了哪些 pid，恢复时按记录 SIGCONT（进程名可能已经换了 pid，所以 CONT 时名字和 pid 都发一遍）
fw_freeze() {
    : > $FWD/frozen
    for n in $FW_FREEZE_PROCS; do
        for p in $(pidof $n 2>/dev/null); do kill -STOP $p 2>/dev/null && echo "$n $p" >> $FWD/frozen; done
    done
    [ -s $FWD/frozen ] && t "fw: frozen: $(cut -d' ' -f1 $FWD/frozen | sort -u | tr '\n' ' ')"
    return 0
}
fw_unfreeze() {
    [ -f $FWD/frozen ] || return 0
    while read n p; do kill -CONT $p 2>/dev/null; done < $FWD/frozen
    for n in $(cut -d' ' -f1 $FWD/frozen | sort -u); do killall -CONT $n 2>/dev/null; done
    rm -f $FWD/frozen
    return 0
}

fb_geom() {
    FB_STRIDE=$(cat $FB/stride)
    FB_PANY=$(cut -d, -f2 $FB/pan)
    FB_ROWS=$(sed -n 's/.*x\([0-9]*\)p.*/\1/p' $FB/modes | head -1)
}

# 停框架时跟着停掉的任务：停之前在跑、现在没在跑的，逐个起回去（已经在跑的 start 会报错，无害）。
fw_restore_running() {
    [ -s $FWD/running0 ] || return 0
    now=" $(fw_running | tr '\n' ' ') "; n=0
    for s in $(cat $FWD/running0); do
        case "$now" in *" $s "*) continue ;; esac
        rm -f "/var/run/upstart/$s.restarts" "/var/local/upstart/$s.restarts" 2>/dev/null   # 我们起的，不算它崩溃重启
        echo $s >> $FWD/restored
        start $s >/dev/null 2>&1 && n=$((n + 1))
    done
    t "fw: $n dependent kindle services restarted"
}

# 一批 Kindle 服务并行起来，全部起好（upstart 的 start 要等任务真的跑起来）才返回。
# 以前一个一个 start：每个要 0.3–2 秒（stored、scanner、webreader……），十来个加起来好几秒，都算在“回到书库”里（PW5 模拟器约 9 秒）。
fw_start_many() {
    fs_p=
    for fs_s in "$@"; do ( start $fs_s >/dev/null 2>&1 ) & fs_p="$fs_p $!"; done
    for fs_x in $fs_p; do wait $fs_x 2>/dev/null; done
    return 0
}

kindle_home() {
    # 框架停过（stop）：等它和书库程序重新起来，最多 3 分钟；只暂停的情况最多 1 分钟
    # 每 0.25 秒看一次（以前每 2 秒：书库程序好了平均还要多等 1 秒）
    max=240; [ -n "$NOFW" ] && max=720
    i=0
    until lipc-probe com.lab126.KPPMainApp 2>/dev/null | grep -qw go || [ $i -ge $max ]; do usleep 250000; i=$((i + 1)); done
    perf_mark "fw: library app probe done ($i rounds)"
    if [ -f /etc/upstart/kppmainapp.conf ]; then
        lipc-set-prop com.lab126.appmgrd start "app://com.lab126.KPPMainApp?view=KPP_HOME"
    else
        lipc-set-prop com.lab126.appmgrd start "app://com.lab126.booklet.home"
    fi
}

# Kindle 界面冷启动（stop 模式，最多等 3 分钟）时的等待画面：方块风格，已用时间每 5 秒走一次，方格按一分钟估；
# 慢的时候告诉用户怎么办。kindle_home 之后停掉秒表（书库画出来会盖掉这个画面）。
fw_wait_screen() {
    type vp_begin >/dev/null 2>&1 && [ -n "$FBINK" ] || return 1
    VP_STATUS=
    vp_begin "正在恢复 Kindle 界面" "BACK TO KINDLE" "Kindle 自己的界面要重新启动（大约一分钟）" \
        "超过 3 分钟还没回来：长按电源键约 15 秒重启 Kindle（书和设置不会丢）" "" "$FBINK"
    [ "$VP_MODE" = fb ] || return 1
    VP_HEAD="请稍候"; vp_show
    vp_step kindle "等 Kindle 的书库程序起来" ""
    vp_ticker_start 60 "Android 已经停了，正在等 Kindle 自己的界面" "比平常慢：再等一会儿；超过 3 分钟就长按电源键重启 Kindle"
    return 0
}
framework_paused() { [ -f $FWD/paused ]; }

# Kindle 自己的 /etc/upstart/chroot.conf（浏览器用的 chroot）每次 framework_ready 都把 /lib、/proc、/dev、/usr/bin/chromium、
# /usr/lib 等重新绑到 /var/tmp/chroot 下面，可它的 chroot_umount 并不卸这几项：框架每重启一次就多叠一层。
# KINDLE_UI=stop（512 MB 机型的默认）每次回 Kindle 都重启框架：PW5 模拟器 9 次后每项叠了 10 层，一台测试机上 75 层、/proc/mounts 570 行。
# 框架回来后把多出来的层去掉，只留一层。只动下面没有别的挂载的那几项（/usr/lib 上还压着 pango / locale 的绑定，层次关系
# 说不清，不碰）。
fw_chroot_dedupe() {
    i=0; while [ $i -lt 50 ] && initctl status chroot 2>/dev/null | grep -q "start/"; do usleep 200000; i=$((i + 1)); done
    n=0
    for m in $(awk '$2 ~ "^/var/tmp/chroot/" {print $2}' /proc/mounts | sort | uniq -d); do
        awk -v d="$m/" 'index($2, d) == 1 { f = 1 } END { exit !f }' /proc/mounts && continue
        c=$(awk -v m="$m" '$2 == m' /proc/mounts | wc -l)
        while [ "$c" -gt 1 ]; do umount -l "$m" 2>/dev/null || break; c=$((c - 1)); n=$((n + 1)); done
    done
    [ $n -gt 0 ] && t "fw: dropped $n stacked binds under /var/tmp/chroot (chroot.conf re-binds them on every framework start)"
    return 0
}
# 框架冷启动完成：登录服务能回答（cvm 登记了 com.lab126.amazonRegistrationService），framework_setup 也做完了（它发出 framework_ready，
# upstart 随后起书库程序等一批任务）。最多等 FW_READY_MAX 秒（模拟器上框架冷启动约 50 秒，慢的真机更久）。
fw_wait_ready() {
    i=0; max=${FW_READY_MAX:-180}
    while [ $i -lt $max ]; do
        if lipc-get-prop com.lab126.amazonRegistrationService isRegistered >/dev/null 2>&1 && ! initctl status framework_setup 2>/dev/null | grep -q "start/"; then
            # framework_ready 刚发出时 kppmainapp 正被 upstart 拉起：给它一两秒，免得下面误判“没人起它”
            j=0; while [ $j -lt 10 ] && ! initctl status kppmainapp 2>/dev/null | grep -q "start/"; do usleep 200000; j=$((j + 1)); done
            t "fw: framework ready after ${i}s (registration service answers: $(lipc-get-prop com.lab126.amazonRegistrationService isRegistered 2>/dev/null))"
            return 0
        fi
        sleep 1; i=$((i + 1))
    done
    t "fw: framework not ready after ${max}s"
    return 1
}

# 停书库程序（KPPMainApp）。它收到 SIGTERM 后自己有一套退出流程，最后打 "main() returns"；之后只剩 C++ 的析构，
# 在固件 5.19.5 上其中清理 CurlHandleContainer 时经常段错误（一台 PW6 测试机的系统日志："main() returns" → 0.4 秒后
# "Cleaning up CurlHandleContainer" → stackdumpd "received Segmentation fault"）：stackdumpd 收集堆栈、写 core，dmcc 再打出
# 约 3.6 MB 的 KPPMainAppV2_*_crash_*.tgz / .txt 放进书库；进程要等收集完才退出，停它要 4–6.4 秒（不崩时 0.7 秒）。
# 不是我们停得不对（pillow / awesome 当时都还正常），是它自己退出路径上的错；5.20.2 的另一台测试机上 3.6 秒正常退出。
# 所以：照常 `stop kppmainapp`（upstart 记下“要停”，不会 respawn），盯着系统日志，它的 "main() returns" 一出现
# （应用层的保存 / 关闭已经做完）就直接 SIGKILL，不给它走到崩溃的那段析构；先删掉 upstart 任务的标记文件，
# 让 KPPMainAppCrashRecovery 当成正常退出（它看标记文件判断是否崩溃循环）。日志里看不到这一行（别的固件 / 日志位置不同）
# 就和以前一样等它自己退出，最多 10 秒。
fw_stop_kpp() {
    kp=$(pidof KPPMainApp 2>/dev/null | cut -d' ' -f1)
    ff=$(sed -n 's/^env KPPMAINAPP_FLAG_FILE=//p' /etc/upstart/kppmainapp.conf 2>/dev/null | head -1)
    ( stop kppmainapp >/dev/null 2>&1 ) & sp=$!
    i=0; how=exit
    while kill -0 $sp 2>/dev/null && [ $i -lt 200 ]; do
        if [ "$how" = exit ] && [ -n "$kp" ] && kill -0 "$kp" 2>/dev/null && \
           tail -n 40 /var/log/messages 2>/dev/null | grep -q "\[$kp\].*main() returns"; then
            [ -n "$ff" ] && rm -f "$ff"
            kill -9 "$kp" 2>/dev/null && how="killed after main() returned"
        fi
        usleep 50000; i=$((i + 1))
    done
    t "fw: library app stopped before pausing the window manager ($((i * 50)) ms, $how)"
}

# Kindle 开机卡在树的画面（2026-11-21，社区“装完微信读书看了一会就无限重启卡大树”排查，KT6 5.20.2 模拟器复现）：
# 5.18.6 起的新固件，/etc/upstart/system_setup.conf 的 pre-start 里有一句 `platformmetricsd-send-notification reboot reboot`。
# 指标守护进程 platformmetricsd 没在跑（它挂在 lab126 后面，开机早期本来就没起来；停掉 / 冻住 Kindle 界面时也收不到）时，
# 每条通知追加到 /var/local/platform-metrics/pending-notifications，等它起来再取走。这个文件超过 64 KB（libplatformmetrics 的
# 上限 65536）以后这个命令就退出 255 -> system_setup 起不来 -> 没有 dbus / lab126 / X / 框架，platformmetricsd 也就永远起不来
# 把文件取走：每次开机都停在开机画面，Kindle 自己出不来（/var/local 不在 USB 盘里，用户也删不了）。
# 文件里是 Kindle 自己的遥测（各进程的 lipc 调用失败计数等），不是用户数据。超过 32 KB 就只留最新的约 16 KB（整行）。
PM_PENDING=${PM_PENDING:-/var/local/platform-metrics/pending-notifications}
pm_pending_guard() {
    [ -f "$PM_PENDING" ] && [ ! -L "$PM_PENDING" ] || return 0
    pg_sz=$(wc -c < "$PM_PENDING" 2>/dev/null | tr -d ' ')
    [ "${pg_sz:-0}" -gt 32768 ] 2>/dev/null || return 0
    # 新内容写回原文件（cat >：属主 / 权限不变，platformmetricsd 用 framework 用户写它）；去掉截断处不完整的第一行
    tail -c 16384 "$PM_PENDING" 2>/dev/null | sed 1d > "$PM_PENDING.vd" 2>/dev/null && cat "$PM_PENDING.vd" > "$PM_PENDING" 2>/dev/null
    rm -f "$PM_PENDING.vd"
    t "kindle: $PM_PENDING was $pg_sz bytes (over 64 KB the Kindle hangs at the boot logo): kept the newest $(wc -c < "$PM_PENDING" | tr -d ' ') bytes"
    return 0
}
framework_stop() {
    mkdir -p $FWD $SYS || return 1
    pm_pending_guard
    framework_paused && { t "kindle ui already paused"; return 0; }
    fb_geom
    [ -n "$FB_STRIDE" ] && [ -n "$FB_ROWS" ] || { t "cannot read fb geometry"; return 1; }
    echo "$FB_STRIDE $FB_PANY $FB_ROWS" > $FWD/geom
    dd if=/dev/fb0 of=$SYS/kindle-screen.raw bs=$FB_STRIDE skip=$FB_PANY count=$FB_ROWS 2>/dev/null
    cat /proc/sys/kernel/core_pattern > $FWD/core_pattern
    # 从这里开始就有东西要恢复了：先立标记。以前标记在函数最后才写，中途被杀 / 出错时 framework_start 认为“没暂停”，
    # pillow、awesome、触摸都停着却没人恢复。
    : > $FWD/stopped; rm -f $FWD/stopdone; touch $FWD/paused
    stat -c %Y /mnt/base-us/documents > $W/docs.mtime 2>/dev/null      # 回到 Kindle 时用来判断书库目录有没有变过
    echo "|/bin/true" > /proc/sys/kernel/core_pattern
    t "fw: screen saved"
    # 书库程序要停的时候（低内存模式、KINDLE_UI=stop）：先把它停掉，再关 pillow、冻 awesome / volumd。它退出时要和这两个打交道，
    # pillow 关着或 awesome 冻着都会卡住，6 秒后被系统当成挂死（堆栈转储 + SIGILL），每进一次 Android 书库里就多一份约 3.6 MB 的
    # KPPMainAppV2_*_crash_*.tgz / .txt / .mbp1（PW6 5.19.5 真机：LOWMEM=on 测 20 分钟留下 10 份；手工复现：pillow / awesome 都正常时 `stop kppmainapp`
    # 1 秒退出、没有转储；只关 pillow 5 秒、只冻 awesome 6 秒，都有转储）。
    # 先停它以后一台测试机上仍然每次都有转储：那是它自己退出时的段错误，见 fw_stop_kpp。
    fw_services
    fw_running > $FWD/running0          # 回来时用它找出“跟着框架一起停掉、却不会自己回来”的任务（要在先停书库之前记）
    case " $FW_SERVICES " in
        *" kppmainapp "*)
            if grep -qx kppmainapp $FWD/running0; then
                echo kppmainapp >> $FWD/stopped     # 马上记下：启动在这之后被叫停（stop.sh 结束 veradroid.sh）时也要把它起回来
                fw_stop_kpp
            fi ;;
    esac
    lipc-set-prop com.lab126.pillow disableEnablePillow disable 2>/dev/null
    killall -STOP awesome volumd 2>/dev/null
    t "fw: pillow off, awesome stopped"
    DISPLAY=:0 xinput disable kindle-input-pt 2>/dev/null
    t "fw: xinput off"
    # 屏幕已经存好了：先把开机画面画上去，后面停服务这几秒用户就不是对着书库发呆
    [ -n "$FRAMEWORK_AFTER_SAVE" ] && $FRAMEWORK_AFTER_SAVE
    # 各个服务并行停（以前一个一个停，要好几秒）
    # 停服务（每个要等它真的退出，一共要好几秒）放到后台做：Android 容器不依赖它们，可以同时开始启动。
    # 恢复（framework_start）会等 stopdone 出现再往下。
    # 中途叫停（启动才几秒、服务还在后台停的时候就退出 / 返回 Kindle）：framework_start 先写 $FWD/abort，后台这一段看到就不再
    # 起 vdkaf、不做自检、不冻进程，直接写 stopdone 让恢复往下走。以前恢复只等 20 秒就照常往下：KT5（512 MB）上框架要停很久，
    # 后台这一段在恢复已经把框架起回去以后才起 vdkaf（顶着 com.lab126.kaf，框架的 cvm 登记不上），自检再判“vdkaf 不行”又起一遍框架、
    # 写 vdkaf.bad；书库一直等不到，回 Kindle 要十几分钟。
    rm -f $FWD/stopdone $FWD/abort
    echo "$FW_MODE" > $FWD/mode
    (
        RUNNING=" $(tr '\n' ' ' < $FWD/running0) "
        for s in $FW_SERVICES; do
            case "$RUNNING" in *" $s "*) grep -qx $s $FWD/stopped || echo $s >> $FWD/stopped; ( stop $s >/dev/null 2>&1 ) & ;; esac
        done
        wait
        fw_clear_restart_counters
        if [ "$FW_MODE" = stop ] && [ ! -f $FWD/abort ]; then
            i=0; while pidof cvm >/dev/null 2>&1 && [ $i -lt 20 ] && [ ! -f $FWD/abort ]; do usleep 250000; i=$((i + 1)); done
        fi
        if [ "$FW_MODE" = stop ] && [ ! -f $FWD/abort ]; then
            vdkaf_start
            # 自检：最多等 12 秒（vdkaf 自己会等框架把名字让出来）。答不上来（比如这个固件的 liblipc 不一样、进程起不来）
            # 就把框架起回去并记下来——宁可不省内存，也不能让电源键失灵。模式记成 stopfail：返回时照样要把跟着框架停掉的任务起回去。
            i=0; ok=
            while [ $i -lt 12 ]; do
                _vp=$(cat $W/vdkaf.pid 2>/dev/null)
                [ "$(lipc-get-prop com.lab126.kaf frameworkStarted 2>/dev/null)" = 1 ] && [ -n "$_vp" ] && kill -0 "$_vp" 2>/dev/null && { ok=1; break; }
                [ -f $FWD/abort ] && { ok=abort; break; }     # 正在恢复：自检没意义，更不能记 vdkaf.bad
                sleep 1; i=$((i + 1))
            done
            if [ -z "$ok" ]; then
                t "fw: vdkaf is not answering on com.lab126.kaf: starting the framework again, KINDLE_UI falls back to keep on this firmware"
                vdkaf_stop; echo stopfail > $FWD/mode
                vdkaf_id > $SYS/vdkaf.bad 2>/dev/null
                rm -f /var/run/upstart/framework.restarts /var/local/upstart/framework.restarts 2>/dev/null
                start framework >/dev/null 2>&1
            fi
        fi
        [ "$FW_MODE" = freeze ] && [ ! -f $FWD/abort ] && fw_freeze
        touch $FWD/stopdone
    ) &
    echo $! > $FWD/stopper
    case "$FW_MODE" in
        stop) t "kindle ui stopped (java framework + library app; services are being stopped in the background)" ;;
        freeze) t "kindle ui paused, background processes frozen (services are being stopped in the background)" ;;
        *) t "kindle ui paused (services are being stopped in the background)" ;;
    esac
}

# 书库里的“VeraDroid 启动”入口。两种情况会让它不见：
#  1. 安装器刚写完入口文件就启动 Android，而启动时会停掉 Kindle 的 scanner（索引服务）——文件在，书库里却没有，
#     scanner 重新起来以后也不会自己补扫；
#  2. 用户在书库里把它删了（等于删了文件）。
# 回到 Kindle 界面、scanner 重新运行之后检查一次：文件没了就从程序目录补回来，书库数据库里没有就让 scanner 单独重扫这个文件。
# Kindle 给部分服务（stored 等）记“重启次数”：每启动一次加一，只有连续运行满 10 秒再停才清零；
# 累计超过 5 次，Kindle 就整机重启（/etc/upstart/mesquite_functions 的 respawn_script）。
# VeraDroid 每次进出都会停 / 起这些服务：回到 Kindle 不到 10 秒又进来（或者用“重启 Android”，停完马上起），
# 计数只增不减，第 6 次时 Kindle 会自己重启（2026-11-11 在 PW6 5.20.2 真机上发生，系统日志 "C stored:reboot"）。
# 这些停 / 起是我们主动做的，不是服务崩溃：每次停完、起之前把它们的计数清掉。
fw_clear_restart_counters() {
    # 本次开机内的重启次数在 /var/run/upstart/<服务>.restarts（超过 5 就重启整机），跨开机的重启计数在 /var/local/upstart/ 下同名文件
    # 我们主动停 / 起的每一个任务都清：固定那一批、这次实际停掉的、以及跟着框架停掉后由我们起回去的（$FWD/restored）
    for s in $FW_BASE statusbar kppmainapp framework $(cat $FWD/stopped $FWD/restored 2>/dev/null); do rm -f "/var/run/upstart/$s.restarts" "/var/local/upstart/$s.restarts" 2>/dev/null; done
}

# Kindle 自己的崩溃记录（*_crash_*.tgz / .txt / .mbp1，每份约 3.6 MB）会写进书库 documents：VeraDroid 停 / 恢复 Kindle 界面时
# 书库程序等系统进程偶尔被当成挂死（一台测试机：从 11-13 到 11-15 攒了 40 份 KPPMainAppV2_*）。它们不是用户的书，却占空间、出现在书库里。
# 回到 Kindle 时挪到 veradroid/kindle-crash/（只留最近 6 份），连同索引服务给它们建的 .sdr 目录。
# 只认 Kindle 自己的命名：<进程>_<pid>_<版本>_crash_<月>_<日>_<时.分.秒>_<年>.<tgz|txt|mbp1>
# （例：KPPMainAppV2_25595_058-juno_1905_sangria_bellatrix4-479431_crash_Nov_15_19.22.27_2026.tgz）。
# 用户自己名字里带 “_crash_” 的书 / 笔记（my_crash_course.txt）绝不碰：挪走后这里只留 6 份，旧的会被删。
fw_is_crash_dump() { # basename
    echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9.-]*_[0-9]+_[^/]*_crash_[A-Z][a-z][a-z]_[0-9]{1,2}_[0-9]{2}\.[0-9]{2}\.[0-9]{2}_[0-9]{4}\.(tgz|txt|mbp1|sdr)$'
}
fw_tidy_crash() {
    D=/mnt/base-us/documents; K=/mnt/base-us/veradroid/kindle-crash; n=0
    for f in $D/*_crash_*.tgz $D/*_crash_*.txt $D/*_crash_*.mbp1; do
        [ -f "$f" ] || continue
        fw_is_crash_dump "${f##*/}" || continue
        mkdir -p $K 2>/dev/null || return 0
        mv -f "$f" $K/ 2>/dev/null && n=$((n + 1))
        b=${f%.*}; [ -d "$b.sdr" ] && rm -rf "$b.sdr"
    done
    for s in $D/*_crash_*.sdr; do
        [ -d "$s" ] || continue; fw_is_crash_dump "${s##*/}" || continue; b=${s%.sdr}
        [ -e "$b.tgz" ] || [ -e "$b.txt" ] || [ -e "$b.mbp1" ] || rm -rf "$s"
    done
    [ -d $K ] && ls -t $K 2>/dev/null | tail -n +7 | while read -r x; do rm -f "$K/$x"; done
    # 书库里已经有它们的条目：挪走以后让索引服务整库重扫一次，不然书库里留着打不开的空条目
    [ $n -gt 0 ] && { t "fw: moved $n Kindle crash file(s) out of documents to $K"; lipc-set-prop com.lab126.scanner doFullScan 1 2>/dev/null; }
    return 0
}

# 书库入口“VeraDroid 启动”（documents/VeraDroid 启动.sh）。用户找不到它是最常见的“进不去”：误删了、从 KUAL / 备用入口启动的、
# 书库按“最近”排时被新书挤到后面几页（KO3 上）。补回去的地方：安装器、veradroid.sh 每次启动（launcher_restore）、
# 回到 Kindle 时（launcher_ensure）、KUAL 菜单“恢复书库入口”（kual/restore-entry.sh，同样几步，不依赖这里）。
LAUNCH_ENTRY="/mnt/us/documents/VeraDroid 启动.sh"
# 入口文件不在：从程序目录的备用入口拷一份，标题改回正式的。补了返回 0
launcher_restore() {
    [ -d /mnt/us/documents ] || return 1
    [ -f "$LAUNCH_ENTRY" ] && return 1
    [ -f "$VD/VeraDroid-Launch.sh" ] || return 1
    sed 's/^# Name: .*/# Name: VeraDroid 启动/' "$VD/VeraDroid-Launch.sh" > "$LAUNCH_ENTRY" 2>/dev/null && chmod 755 "$LAUNCH_ENTRY" 2>/dev/null \
        || { rm -f "$LAUNCH_ENTRY" 2>/dev/null; return 1; }
    t "launcher: library entry file restored"
    lipc-set-prop com.lab126.scanner reScanFile "$LAUNCH_ENTRY" 2>/dev/null && t "launcher: asked the scanner to index the library entry"
    return 0
}
launcher_ensure() {
    [ -d /mnt/us/documents ] || return 0
    L=$LAUNCH_ENTRY
    launcher_restore && return 0
    [ -f "$L" ] || return 0
    # 以前的入口标题是“VeraDroid”，说明文字里却一直写“VeraDroid 启动”：改成一样的（只认我们的那一行）
    if head -3 "$L" 2>/dev/null | grep -q '^# Name: VeraDroid$'; then
        sed 's/^# Name: VeraDroid$/# Name: VeraDroid 启动/' "$L" > "$L.tmp" 2>/dev/null && chmod 755 "$L.tmp" 2>/dev/null && mv -f "$L.tmp" "$L" \
            && { t "launcher: entry title changed to VeraDroid 启动"; lipc-set-prop com.lab126.scanner reScanFile "$L" 2>/dev/null; return 0; }
        rm -f "$L.tmp" 2>/dev/null
    fi
    command -v sqlite3 >/dev/null 2>&1 && [ -f /var/local/cc.db ] || return 0
    n=$(sqlite3 /var/local/cc.db "select count(*) from Entries where p_location='$L'" 2>/dev/null)
    if [ -n "$n" ] && [ "$n" -gt 0 ] 2>/dev/null; then
        # 书库按“最近”排（p_lastAccess）：比入口新的书 / 文档有 4 本以上，入口就不在第一屏了。改一下文件时间再让索引服务重扫，
        # 它把“最近”记成文件的修改时间（测试机实测：安装器 touch 过的入口 p_lastAccess = p_modificationTime），入口回到前面。
        # 只在被挤下去时做：正在读的书仍排在它前面。
        la=$(sqlite3 /var/local/cc.db "select max(p_lastAccess) from Entries where p_location='$L'" 2>/dev/null)
        case "$la" in ''|*[!0-9]*) la= ;; esac
        if [ -n "$la" ]; then
            nb=$(sqlite3 /var/local/cc.db "select count(*) from Entries where p_isVisibleInHome=1 and p_type like 'Entry:Item%' and p_type!='Entry:Item:Dictionary' and p_location!='$L' and p_lastAccess>$la" 2>/dev/null)
            if [ -n "$nb" ] && [ "$nb" -ge 4 ] 2>/dev/null; then
                touch "$L" 2>/dev/null
                lipc-set-prop com.lab126.scanner reScanFile "$L" 2>/dev/null && t "launcher: entry was behind $nb newer items in the library, indexed it again to bring it forward"
                return 0
            fi
        fi
        # 已经在书库里。封面：索引服务从脚本头的 "# Icon:" 解出 <文件>.sdr/icon.png；没有这张图时书库显示成普通文档的样子。
        # 每个固件版本只补扫一次（有的越狱环境不解图标，不要每次返回都重扫）。
        [ -f "$L.sdr/icon.png" ] && return 0
        fwv=$(head -1 /etc/prettyversion.txt 2>/dev/null)
        [ "$(cat $SYS/launcher-icon.tried 2>/dev/null)" = "$fwv" ] && return 0
        echo "$fwv" > $SYS/launcher-icon.tried 2>/dev/null
        lipc-set-prop com.lab126.scanner reScanFile "$L" 2>/dev/null && t "launcher: entry has no cover image, asked the scanner to index it again"
        return 0
    fi
    lipc-set-prop com.lab126.scanner reScanFile "$L" 2>/dev/null && t "launcher: asked the scanner to index the library entry"
}

framework_start() {
    pm_pending_guard
    framework_paused || { t "kindle ui not paused"; return 0; }
    # 后台停服务还没做完：叫它别再往下做（不起 vdkaf、不自检、不冻），等它收尾；还在等某个服务退出的话最多等 20 秒，
    # 过了就把那一段杀掉再往下（它不能在我们恢复之后再动 Kindle 的东西）
    if [ ! -f $FWD/stopdone ] && [ -f $FWD/stopper ]; then   # 没有 stopper：后台那一段还没起（启动在那之前被叫停），不用等
        touch $FWD/abort
        i=0; while [ ! -f $FWD/stopdone ] && [ $i -lt 100 ]; do usleep 200000; i=$((i + 1)); done
        if [ -f $FWD/stopdone ]; then t "fw: early return: background service stop wound up after $((i * 200)) ms" >> $LOG 2>/dev/null
        else
            sp=$(cat $FWD/stopper 2>/dev/null)
            [ -n "$sp" ] && kill "$sp" 2>/dev/null
            t "fw: early return: background service stop still busy after 20 s ($(cat $FWD/stopped 2>/dev/null | tr '\n' ' ')): ended it" >> $LOG 2>/dev/null
        fi
    fi
    set -- $(cat $FWD/geom 2>/dev/null)
    if [ -f $SYS/kindle-screen.raw ] && [ -n "$1" ]; then
        dd if=$SYS/kindle-screen.raw of=/dev/fb0 bs=$1 seek=$2 count=$3 2>/dev/null
    fi
    # 写回后整屏刷一次：书库没变时它不重画，墨水屏会一直停在我们的画面上
    [ -x "$FBINK" ] && "$FBINK" -q -s 2>/dev/null
    perf_mark "fw: kindle screen restored"
    DISPLAY=:0 xinput enable kindle-input-pt 2>/dev/null
    killall -CONT awesome 2>/dev/null
    # USB（固件 5.16.3 以后是 MTP：volumd 收到插线事件后用 lipc 叫 com.lab126.mtp 的 startMtp，由它打开 USB 设备）。
    # 以前是先唤醒 volumd、一秒多以后才把停掉的 mtp 起回来：volumd 一醒就处理停着期间攒下的插线事件，这时 MTP 服务还没登记。
    # 用户报告（KT6 5.17）退出后 USB 连电脑只有一个打不开的盘、重启才好；模拟器没有 USB 主机也没有 mtp 任务，这个顺序是不是
    # 那次故障的原因没法在模拟器上证实，但顺序本身是错的：先把 mtp 起回来、等它登记好，再唤醒 volumd。
    if grep -qx mtp $FWD/stopped 2>/dev/null; then
        rm -f /var/run/upstart/mtp.restarts /var/local/upstart/mtp.restarts 2>/dev/null
        start mtp >/dev/null 2>&1
        # 登记好了没有：lipc-get-prop 返回 3（lipcErrNoSuchSource）= 这个名字还没人登记；别的返回值（包括“没有这个属性”）都说明服务在了
        i=0; while [ $i -lt 15 ]; do lipc-get-prop com.lab126.mtp isMtpStarted >/dev/null 2>&1; [ $? = 3 ] || break; usleep 200000; i=$((i + 1)); done
        t "fw: mtp back before volumd (waited $((i * 200)) ms)"
    fi
    killall -CONT volumd 2>/dev/null
    lipc-set-prop com.lab126.pillow disableEnablePillow enable 2>/dev/null
    # Restart scanner/stored before asking the home app to draw. If KPP_HOME is
    # opened first, its cached book list can be shown and KUAL/KOReader launcher
    # entries look missing until the next reboot.
    fw_clear_restart_counters
    NOFW=; case "$(cat $FWD/mode 2>/dev/null)" in stop|stopfail) NOFW=1 ;; esac
    fw_unfreeze
    # 书库程序（kppmainapp）不能比框架先起来：它一启动就问 com.lab126.amazonRegistrationService（在 cvm 里）登录状态，
    # 框架刚 start、这个服务还没登记时它拿到的是“取不到状态”，于是一直显示“欢迎使用 Kindle / 请登录亚马逊账户”的未注册主页，
    # 直到它下次重启（PW5 真机：改完系统盘大小退出 Android 后出现；pw5-5192 模拟器日志："Invalid Token Status : " 空值
    # + "Error in getting registration status"，比 cvm 的 "RegisteredService ... AmazonRegistrationService" 早 9 秒）。
    # 它的 upstart 任务本来就是 "start on framework_ready"：框架停过时由 upstart 在 framework_ready 之后自己起它，我们只等；
    # 等不到（超时）才自己起，而且也要等登录服务能回答之后。
    fw_svc=
    for s in $(cat $FWD/stopped 2>/dev/null); do
        case "$s" in framework) continue ;; kppmainapp) [ -n "$NOFW" ] && continue ;; esac
        fw_svc="$fw_svc $s"
    done
    if [ -n "$NOFW" ]; then
        vdkaf_stop
        killall vdkaf 2>/dev/null      # 漏记 pid 的（被叫停的后台那一段刚起的）也不能留：它顶着 com.lab126.kaf，框架就登记不上
        # 框架先起（别的任务有些要等它），再并行起其余的。整段放到后台：框架冷启动是回书库最长的一段，
        # 以前要先画完等待画面（fbink，几秒）才开始起框架，start framework 自己又要等好几秒才返回
        ( grep -qx framework $FWD/stopped 2>/dev/null && start framework >/dev/null 2>&1; fw_start_many $fw_svc ) & fw_svp=$!
        # 框架要重新启动（冷启动约一分钟）：告诉用户在等什么，不然屏幕上是停之前存下的那一帧，点了没反应
        fw_wait_screen || splash "正在恢复 Kindle 界面，大约需要一分钟…" 36
        fw_wait_ready
        # upstart 的 `start framework` 要等框架自己的 post-start（lipc-wait-event -s 0 … FrameworkStarted，没有超时）：框架起不来时
        # 以前这里就永远等下去，返回 Kindle 的整个流程（和之后的“启动”）一起卡死（PW5 512 MB 模拟器实测卡了 18 分钟）。
        # fw_wait_ready 已经最多等过 FW_READY_MAX 秒：再给这一段最多 30 秒，还没完就不等了（它在后台照样会把剩下的服务起完）。
        i=0; while kill -0 $fw_svp 2>/dev/null && [ $i -lt 150 ]; do usleep 200000; i=$((i + 1)); done
        kill -0 $fw_svp 2>/dev/null && t "fw: framework / services still starting after the wait: not waiting any longer"
        if grep -qx kppmainapp $FWD/stopped 2>/dev/null && ! initctl status kppmainapp 2>/dev/null | grep -q "start/"; then
            t "fw: library app not started by upstart after framework_ready: starting it"
            start kppmainapp >/dev/null 2>&1
        fi
        fw_restore_running
        fw_chroot_dedupe
    else
        fw_start_many $fw_svc
    fi
    perf_mark "fw: services started ($fw_svc)"
    if [ -n "$NOFW" ]; then
        sleep 1
        kindle_home
        perf_mark "fw: library app answers, home requested"
    else
        # 框架没停过（freeze / keep）：书库程序刚被解冻，存下的画面已经写回屏幕，用户这时已经能点书库了。等它回答 lipc 再请
        # appmgrd 回主页这一步放到后台：真机上要 9–21 秒（PW6 模拟器上等满 60 秒），以前整段返回流程（冻结标记之后的收尾、
        # 下一次“启动”要等的这个 shell）都卡在这里（perf/ret，tools/bench 的宿主日志时间线）。用户看到的不变。
        # 后台这一段不能开着系统盘上的东西（stop.sh 也调 framework_start，随后要卸载系统盘）：cwd 是 /、输出写 tmpfs
        ( cd /; sleep 1; kindle_home; t "fw: library app answers, home requested (background)" ) </dev/null >>$W/fwhome.log 2>&1 &
    fi
    type vp_ticker_stop >/dev/null 2>&1 && vp_ticker_stop
    # 书库程序是我们停的（低内存模式 / KINDLE_UI=stop）：停它的那一刻它是 appmgrd 的前台应用，appmgrd 当它崩了，弹出
    # “Application Error / The selected application could not be started”（pillow 的 appmgrAppFailedFatal 提示框）。
    # 进 Android 时这个框被盖住，回来以后就挂在书库上（pw5-5192 模拟器：handle_app_failure abnormalExit=1 → show_simple_alert）。
    # 书库重新起来以后把它关掉；框架冷启动时开机画面那几行字 X 不知道，没被重画到的地方会一直留着：让 X 整屏重画一次。
    KH_DISMISS=; grep -qx kppmainapp $FWD/stopped 2>/dev/null && KH_DISMISS=1
    if [ -n "$KH_DISMISS" ] || [ -n "$NOFW" ]; then
        # pillow 的 simple_alert.js 只认 "hide":true（或 clearAll）；"show":false 什么都不做（kt6-517104 5.17.1 实测：框一直在）。
        # 框可能在书库主页起来以后才（再）弹出来（appmgrd 切到 KPP_HOME 时），所以隔一段再关几次；没有这个框时 hide 什么都不做。
        ( for d in 4 8 20; do
              sleep $d
              [ -n "$KH_DISMISS" ] && lipc-set-prop com.lab126.pillow pillowAlert '{"clientParams":{"alertId":"appmgrAppFailedFatal","hide":true}}' 2>/dev/null
              [ $d = 4 ] && [ -n "$NOFW" ] && command -v xrefresh >/dev/null 2>&1 && DISPLAY=:0 xrefresh 2>/dev/null
          done ) </dev/null >/dev/null 2>&1 &
    fi
    [ -f $FWD/core_pattern ] && cat $FWD/core_pattern > /proc/sys/kernel/core_pattern
    rm -rf $FWD
    # Android 运行期间 Kindle 的索引服务是停的：这期间传进 documents 的书，回来以后要让它整库重扫一次才会出现在书库里
    dm0=$(cat $W/docs.mtime 2>/dev/null); dm1=$(stat -c %Y /mnt/base-us/documents 2>/dev/null); rm -f $W/docs.mtime
    ( sleep 10; launcher_ensure; fw_tidy_crash
      if [ -n "$dm0" ] && [ -n "$dm1" ] && [ "$dm0" != "$dm1" ]; then lipc-set-prop com.lab126.scanner doFullScan 1 2>/dev/null; fi ) >/dev/null 2>&1 &
    t "kindle ui resumed"
}
