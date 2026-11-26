# 只在模拟器上验证过的机型（安装器的机型表里 tested=emulator-only：3.0 内核 PW2 / PW3 / Voyage，4.1 内核 PW4 / 入门款 2019 /
# Oasis 2 / Oasis 3）的自检和兜底。这些机型走“实验机型”安装，交给志愿者在真机上测。安装器写 $VD/tested = emulator-only；
# 只有这时下面的东西才生效（真机验证过的机型完全不受影响）。
#  - test_begin（veradroid.sh 启动时）：记下开始时间，起一个独立的看护进程 testwatch：
#      * 开机超时（默认 15 分钟，settings.test 里 TEST_DEADLINE=秒 可改）没完成 → 记下失败、存日志、stop.sh 回到 Kindle；
#      * 启动脚本中途死掉、Kindle 界面停着却没有 Android（没人管的半截状态）超过 30 秒 → 同样 stop.sh；
#      * stop.sh 之后 Kindle 界面还停着 → 再直接恢复一次（framework_start）。
#    看护进程从 tmpfs 里的副本运行、输出写 /tmp：不占着用户存储（USB 导出）和系统盘（卸载）。
#  - 开机完成后自检（模块、binder、容器 / 开机用时、显示桥、触摸、网络、内存），写 USB 能看到的 veradroid/TEST-RESULT.txt；
#    休眠 / 唤醒（sleep.sh）、返回 Kindle 和用户存储释放（stop.sh）发生时再补进去。
#  - test_bundle：给“上传测试结果”用的精简诊断包，所有文本都去掉书名 / 文件路径；只有用户在屏幕上确认后才上传（书库入口 VeraDroid-Report.sh）。
# 报告里只有机型、固件、内核 uname -a、序列号前 4 位（机型代码），不含书名 / 文件名。
VD=${VD:-/mnt/base-us/veradroid}     # 看护进程是单独起的 sh：这时 VD 还没设（PW3 模拟器实测：状态写到了 /test）
TDIR=$VD/test
TRES=$VD/TEST-RESULT.txt
TLOG=/tmp/veradroid-test.log
# 序列号里的机型代码 → 机型名（只用于日志和测试报告，不用于判断装哪个包）。数据：公开资料整理的机型代码表
# （KindleTool kindle_tool.h + FBInk + KOReader 的事实）。规则同 FBInk：老格式序列号（B 或 9 开头）取第 3–4 位，否则取第 4–6 位。
# 3XE（Scribe 2）不在任何上游表里，是我们自己补的。以前只认 B 开头：9 开头的 PaperWhite 2（如 905A…，代码 5A）被当成
# 新格式取了第 4–6 位，测试报告里写成“未知（代码 A22）”（2026-11-16 用户测试报告 T-905A，主板 027 / fb 758 / 256 MB）。
kindle_code() { ks=$( { tr -cd '0-9A-Za-z' </proc/usid; } 2>/dev/null); case "$ks" in B*|9*) echo "$ks" | cut -c3-4 ;; ?*) echo "$ks" | cut -c4-6 ;; esac; }
kindle_name() {
    case "$1" in
        D4|5A|D5|D6|D7|D8|F2|17|60|F4|F9|62|61|5F) echo "PaperWhite 2" ;;
        13|54|2A|4F|52|53) echo "Voyage" ;;
        0G1|0G2|0G4|0G5|0G6|0G7|0KB|0KC|0KD|0KE|0KF|0KG|0LK|0LL) echo "PaperWhite 3" ;;
        0GC|0GD|0GR|0GS|0GT|0GU) echo "Oasis 1" ;;
        0LM|0LN|0LP|0LQ|0P1|0P2|0P6|0P7|0P8|0S1|0S2|0S3|0S4|0S7|0SA) echo "Oasis 2" ;;
        11L|0WQ|0WP|0WN|0WM|0WL) echo "Oasis 3" ;;
        0PP|0T1|0T2|0T3|0T4|0T5|0T6|0T7|0TJ|0TK|0TL|0TM|0TN|102|103|16Q|16R|16S|16T|16U|16V) echo "PaperWhite 4" ;;
        10L|0WF|0WG|0WH|0WJ|0VB) echo "入门款 2019（KT4）" ;;
        3V0|3V1|3X5|3UV|3X4|3X3|41E|410|3XE) echo "Scribe 2" ;;
        *) echo "未知（代码 ${1:-?}）" ;;
    esac
}
# Kindle 自己的崩溃记录（cvm / KPPMainAppV2 / rcm 崩溃时写进 documents 的 *_crash_*.tgz）：停 / 恢复 Kindle 界面时它们崩了，
# 书库里就会多出几本“书”。只数个数，报告里写本次新增几个
kindle_crashes() { ls /mnt/base-us/documents/*_crash_*.tgz 2>/dev/null | wc -l | tr -d ' '; }
test_on() { [ "$(cat $VD/tested 2>/dev/null)" = emulator-only ]; }
tup() { cut -d. -f1 /proc/uptime; }
# 状态文件：一行一个 key=value，后写的覆盖前面的。几个进程会同时写（看护进程、sleep.sh、stop.sh）：用 mkdir 当锁
tset() { # key value
    test_on || return 0
    mkdir -p $TDIR 2>/dev/null
    ts_i=0; while ! mkdir $TDIR/.lock 2>/dev/null; do ts_i=$((ts_i + 1)); [ $ts_i -ge 30 ] && { rmdir $TDIR/.lock 2>/dev/null; break; }; usleep 100000 2>/dev/null || sleep 1; done
    { grep -v "^$1=" $TDIR/state 2>/dev/null; printf '%s=%s\n' "$1" "$2"; } > $TDIR/state.$$ && mv -f $TDIR/state.$$ $TDIR/state
    rmdir $TDIR/.lock 2>/dev/null
    return 0
}
tget() { sed -n "s/^$1=//p" $TDIR/state 2>/dev/null | tail -n 1; }
tlog() { echo "[$(date '+%F %T') up=$(tup)] test: $*" >> $TLOG 2>/dev/null; t "test: $*"; }

# ---- 去掉书名 / 文件路径（上传的东西一律先过这里）----
# 书和个人文件的路径（documents、KindleLibrary、Books、sdcard、emulated/0 下面）整段换成 <redacted>，
# 再把任何带常见书 / 文档扩展名的文件名换成 <file>。宁可多删也不漏：日志里看不到书名，只看得到“有个文件”。
redact() {
    sed -e 's#documents/[^ "'"'"',;)]*#documents/<redacted>#g' \
        -e 's#KindleLibrary/[^ "'"'"',;)]*#KindleLibrary/<redacted>#g' \
        -e 's#kindle_library/[^ "'"'"',;)]*#kindle_library/<redacted>#g' \
        -e 's#Books/[^ "'"'"',;)]*#Books/<redacted>#g' \
        -e 's#Download/[^ "'"'"',;)]*#Download/<redacted>#g' \
        -e 's#WeRead/[^ "'"'"',;)]*#WeRead/<redacted>#g' \
        -e 's#sdcard/[^ "'"'"',;)]*#sdcard/<redacted>#g' \
        -e 's#emulated/0/[^ "'"'"',;)]*#emulated/0/<redacted>#g' \
        -e 's#media/0/[^ "'"'"',;)]*#media/0/<redacted>#g' \
        -e 's#[^ /"'"'"']*\.[Ee][Pp][Uu][Bb]#<file>#g' -e 's#[^ /"'"'"']*\.[Pp][Dd][Ff]#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Mm][Oo][Bb][Ii]#<file>#g' -e 's#[^ /"'"'"']*\.[Aa][Zz][Ww]3*#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Kk][Ff][Xx]#<file>#g' -e 's#[^ /"'"'"']*\.[Pp][Rr][Cc]#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Tt][Xx][Tt]#<file>#g' -e 's#[^ /"'"'"']*\.[Dd][Oo][Cc][Xx]*#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Ff][Bb]2#<file>#g' -e 's#[^ /"'"'"']*\.[Dd][Jj][Vv][Uu]#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Cc][Bb][ZzRr]#<file>#g' -e 's#[^ /"'"'"']*\.[Rr][Tt][Ff]#<file>#g' \
        -e 's#[^ /"'"'"']*\.[Kk][Pp][Ff]#<file>#g' -e 's#[^ /"'"'"']*\.[Mm][Bb][Pp]1*#<file>#g'
}

# ---- 启动时（veradroid.sh，Kindle 界面暂停之前）----
test_begin() {
    test_on || return 0
    mkdir -p $TDIR
    n=$(tget run.n); n=$((${n:-0} + 1))
    # 开机卡住被 bootwatch 自动重试的那一次：超时还按第一次的开始时间算（总时长有上限）
    if [ -f $W/boot.retry ] && [ -f $W/test.t0 ]; then t0=$(cat $W/test.t0); else t0=$(tup); echo $t0 > $W/test.t0; fi
    for k in container.s boot.s boot.result selftest ret.s ret.kindle ret.userstore ret.result fail.reason; do tset $k ""; done
    tset run.n $n; tset run.date "$(date '+%F %T')"; tset start.up $t0
    tset crash0 "$(kindle_crashes)"
    tset version "$(cat $VD/version 2>/dev/null)"
        cp $VD/boot/lib/tester.sh $W/tester-watch.sh 2>/dev/null || return 0
    # 独立于启动脚本：启动脚本死了它还在；setsid + cwd=/ + 输出到 /tmp
    ( cd / && exec setsid sh $W/tester-watch.sh watch ) </dev/null >>$TLOG 2>&1 &
    echo $! > $W/testwatch.pid
    tlog "run $n started (deadline ${TEST_DEADLINE:-900}s)"
}

# 卡住时也要留下能看的日志：Android 的 logcat（去掉文件名）和墓碑列表，存在 $VD/test/（不在系统盘上，停掉以后也在）
test_snapshot() { # tag
    mkdir -p $TDIR
    if vd_in >/dev/null 2>&1; then
        vd_timeout 60 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; logcat -b all -d -v threadtime' 2>/dev/null | tail -n 4000 | redact > $TDIR/logcat-$1.txt
        vd_timeout 30 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; logcat -b crash -d -v threadtime' 2>/dev/null | redact > $TDIR/logcat-crash-$1.txt
        vd_timeout 30 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin; getprop' 2>/dev/null | grep -v -i 'serial\|usid\|wifi\|ssid\|mac' > $TDIR/getprop-$1.txt
    fi
    ls -la $SYS/data/tombstones $SYS/data/anr 2>/dev/null > $TDIR/tombstones-$1.txt
    dmesg 2>/dev/null | tail -n 600 | redact > $TDIR/dmesg-$1.txt
    [ -f $LOG ] && tail -c 300000 $LOG | redact > $TDIR/veradroid-$1.log
}

# ---- 开机完成后的自检 ----
test_selftest() {
    r=
    # 1. 内核模块
    m=$(awk '$1 == "binder" || $1 == "uinput" || $1 == "zram" || $1 == "tun" {printf "%s ", $1}' /proc/modules 2>/dev/null)
    if grep -qw vndbinder /proc/misc 2>/dev/null; then tset chk.kmod "PASS|binder 设备在（/proc/misc vndbinder）；加载的模块：${m:-无（内核自带）}"
    else tset chk.kmod "FAIL|没有 binder 设备；加载的模块：${m:-无}"; fi
    # 2. binder 通信：servicemanager 能找到 activity（system_server 的服务）
    b=$(vd_timeout 30 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin; service check activity; service list | head -n 1' 2>/dev/null | tr '\n' ' ')
    case "$b" in *"activity: found"*) tset chk.binder "PASS|$(echo "$b" | sed 's/.*\(Found [0-9]* services\).*/\1/')" ;;
                  *) tset chk.binder "FAIL|service check activity: $(echo "$b" | cut -c1-80)" ;; esac
    # 3. 显示桥、触摸
    if alive $W/epd.pid; then tset chk.display "PASS|显示桥在运行（$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null)）"; else tset chk.display "FAIL|显示桥不在了"; fi
    td=$(grep 'input: real touch=' $LOG 2>/dev/null | tail -n 1 | sed 's/.*real touch=//')
    tn=; [ -n "$td" ] && tn=$(cat /sys/class/input/${td##*/}/device/name 2>/dev/null)
    if [ -n "$td" ] && alive $W/input.pid; then tset chk.touch "PASS|$td ${tn}（经手势前端）"
    elif [ -n "$td" ]; then tset chk.touch "PASS|$td ${tn}（直通）"
    else tset chk.touch "FAIL|没找到触摸屏"; fi
    # 4. 桌面
    f=$(vd_timeout 30 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin; dumpsys window windows | grep -m 1 mCurrentFocus' 2>/dev/null)
    case "$f" in *com.veradroid.home*) tset chk.home "PASS|桌面在前台" ;; *) tset chk.home "FAIL|前台：$(echo "$f" | redact | cut -c1-80)" ;; esac
    # 5. 网络（Android 里）：域名 → IP；都不通时看 Kindle 自己有没有网
    nr=$(vd_timeout 40 $(vd_in) /system/bin/sh -c 'export PATH=/system/bin; ping -c 1 -W 5 www.baidu.com >/dev/null 2>&1 && echo dns; ping -c 1 -W 5 223.5.5.5 >/dev/null 2>&1 && echo ip' 2>/dev/null | tr '\n' ' ')
    case "$nr" in *dns*) tset chk.net "PASS|Android 里能解析域名、能 ping 通" ;;
                  *ip*) tset chk.net "FAIL|能 ping 通 IP，域名解析失败（DNS）" ;;
                  *) if ip route 2>/dev/null | grep -q '^default'; then tset chk.net "FAIL|Kindle 有网络，Android 里不通"
                     else tset chk.net "SKIP|Kindle 自己没连 Wi-Fi，没测"; fi ;; esac
    # 6. 内存（Android 开着的时候）
    tset chk.mem "INFO|$(awk '/^MemTotal:/ {t=$2} /^MemFree:/ {f=$2} /^Cached:/ {c=$2} /^SwapTotal:/ {st=$2} /^SwapFree:/ {sf=$2}
        END {printf "总 %d MB，空闲 %d MB，缓存 %d MB，交换已用 %d / %d MB", t/1024, f/1024, c/1024, (st-sf)/1024, st/1024}' /proc/meminfo)"
    tset selftest done
}

# ---- 返回 Kindle 之后（stop.sh 结尾）----
test_after_stop() { # userstore_check 的结论行
    test_on || return 0
    s0=$(cat $W/test.stop0 2>/dev/null); rm -f $W/test.stop0
    [ -n "$s0" ] && tset ret.s $(( $(tup) - s0 ))
    a=$(lipc-get-prop com.lab126.appmgrd activeApp 2>/dev/null)
    if [ -f $W/fw/paused ]; then tset ret.kindle "FAIL|Kindle 界面还停着"
    else tset ret.kindle "PASS|Kindle 界面已恢复${a:+（$a）}"; fi
    case "$1" in *"NOT CLEAN"*) tset ret.userstore "FAIL|$(echo "$1" | sed 's/.*NOT CLEAN://' | redact | cut -c1-160)" ;;
                 *clean*|*repaired*) tset ret.userstore "PASS|系统盘已卸载、loop 已释放（USB 能导出）" ;;
                 *) tset ret.userstore "INFO|${1:-没有结论}" ;; esac
    test_report
}

# ---- 看护进程（tmpfs 里的副本：sh $W/tester-watch.sh watch）----
test_watch() {
    t0=$(cat $W/test.t0 2>/dev/null); [ -n "$t0" ] || t0=$(tup)
    dl=${TEST_DEADLINE:-900}; orphan=0; seen=
    while :; do
        sleep 5
        now=$(tup); el=$((now - t0))
        p=$(cat $W/pid 2>/dev/null)
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            [ -n "$seen" ] || { seen=1; tset container.s $el; tlog "container up after ${el}s"; }
            orphan=0
            if [ -f $W/booted ]; then
                b=$(cat $W/booted 2>/dev/null); tset boot.s $(( ${b:-$now} - t0 )); tset boot.result PASS
                tlog "boot completed after $(( ${b:-$now} - t0 ))s"
                sleep 20                       # 让 after_boot 的第一波设置先做完
                p=$(cat $W/pid 2>/dev/null)
                sp=$(cat /tmp/veradroid-stop.pid 2>/dev/null)
                if [ -z "$p" ] || ! kill -0 "$p" 2>/dev/null || [ -e $W/exiting ] || { [ -n "$sp" ] && kill -0 "$sp" 2>/dev/null; }; then
                    tlog "android exiting or stopped during post-boot wait: skipping selftest"
                    rm -f $W/test.t0
                    return 0
                fi
                test_selftest; test_snapshot boot; test_report
                test_notice
                rm -f $W/test.t0
                return 0
            fi
            if [ $el -ge $dl ]; then
                tset boot.result "FAIL"; tset fail.reason "开机超时：${el} 秒内没有完成（上限 ${dl} 秒），已自动回到 Kindle"
                tlog "boot deadline (${el}s >= ${dl}s): saving logs and returning to Kindle"
                test_snapshot fail
                test_return "test deadline ${dl}s"
                return 0
            fi
        else
            # 没有 Android：启动脚本还在（早期阶段）就等；没人管而 Kindle 界面停着超过 30 秒 → 回 Kindle
            starting=; [ -d $W/starting ] && kill -0 "$(cat $W/starting/pid 2>/dev/null)" 2>/dev/null && starting=1
            stopping=; kill -0 "$(cat /tmp/veradroid-stop.pid 2>/dev/null)" 2>/dev/null && stopping=1
            if [ -n "$seen" ] && [ -z "$starting" ]; then
                # 容器起来过、现在没了：正常退出（stop.sh 会写报告）或者崩了（stop.sh 也会被看门狗调）
                [ -f $W/booted ] || { tset boot.result FAIL; [ -n "$(tget fail.reason)" ] || tset fail.reason "Android 没开完机就退出了（${el} 秒）"; test_snapshot exit; }
                test_report; return 0
            fi
            if [ -f $W/fw/paused ] && [ -z "$starting" ] && [ -z "$stopping" ]; then
                orphan=$((orphan + 5))
                if [ $orphan -ge 30 ]; then
                    tset boot.result FAIL; tset fail.reason "启动中途失败（Android 没有起来），已自动回到 Kindle"
                    tlog "kindle ui paused but nothing is starting: returning to Kindle"
                    test_return "test: orphaned start"
                    return 0
                fi
            elif [ -z "$starting" ] && [ -z "$stopping" ] && [ ! -f $W/fw/paused ]; then
                # 启动脚本自己放弃并恢复了 Kindle（没有系统、找不到触摸屏等）
                [ $el -ge 10 ] && { tset boot.result FAIL; [ -n "$(tget fail.reason)" ] || tset fail.reason "启动脚本放弃了（见 veradroid.log），Kindle 界面已恢复"; test_report; return 0; }
            fi
            if [ $el -ge $((dl + 120)) ]; then
                tset boot.result FAIL; tset fail.reason "启动超时（${el} 秒）"; test_return "test deadline (no container)"; return 0
            fi
        fi
    done
}
test_return() { # reason
    log_release 2>/dev/null
    VD_STOP_REASON="$1" sh $VD/boot/stop.sh >/dev/null 2>&1
    # stop.sh 之后 Kindle 界面还停着（stop 自己失败了）：直接恢复一次
    if [ -f $W/fw/paused ]; then
        tlog "kindle ui still paused after stop.sh: framework_start"
        ( . $VD/boot/lib/framework.sh; framework_start ) >>$TLOG 2>&1
    fi
    test_report
}
# 开机完成后在桌面上提示一次（VeraHome 的通知）：按电源键测休眠 / 唤醒，然后返回 Kindle
test_notice() {
    [ -d $X/ctl ] || return 0
    n=$(date +%s)$$
    printf '%s\n%s\n' "实验机型自检已完成" "请按一下电源键让 Kindle 休眠，几秒后再按一下唤醒；然后点“返回 Kindle”。结果在 USB 里的 veradroid/TEST-RESULT.txt" > $X/ctl/notice.$n.tmp \
        && chmod 666 $X/ctl/notice.$n.tmp && mv -f $X/ctl/notice.$n.tmp $X/ctl/notice.$n
}

# ---- 报告 ----
tline() { # 标题 key [没有结果时的说明]
    v=$(tget $2)
    if [ -z "$v" ]; then printf '[ -- ] %s：%s\n' "$1" "${3:-还没测}"; return; fi
    case "$v" in *"|"*) printf '[%s] %s：%s\n' "${v%%|*}" "$1" "${v#*|}" ;; *) printf '[INFO] %s：%s\n' "$1" "$v" ;; esac
}
test_report() {
    test_on || return 0
    mkdir -p $TDIR
    fw=$(sed -n 's/.*Kindle[[:space:]]*\([0-9][0-9.]*\).*/\1/p' /etc/prettyversion.txt 2>/dev/null | head -n 1)
    sp=$( { tr -cd '0-9A-Za-z' </proc/usid; } 2>/dev/null | cut -c1-4)
    # 安装前的书库清单（安装器写的）：只数文件个数，不列名字
    man=/mnt/base-us/veradroid/state/docs-manifest; [ -f $man ] || man=$man.checked
    [ -f $man ] || man=/mnt/base-us/veradroid-docs-manifest; [ -f $man ] || man=$man.checked   # root-legacy
    dn=; dm=
    if [ -f $man ]; then dn=$(wc -l < $man | tr -d ' '); dm=0
        while IFS= read -r f; do [ -e "/mnt/base-us/$f" ] || dm=$((dm + 1)); done < $man; fi
    {
        echo "VeraDroid 测试结果（TEST-RESULT.txt）"
        echo "生成时间：$(date '+%F %T')    版本 $(cat $VD/version 2>/dev/null)（实验机型，只在模拟器上验证过）"
        kc=$(kindle_code)
        echo "机型：$(kindle_name "$kc")（机型代码 ${kc:-?}，安装包 $(cat $SYS/model 2>/dev/null || cat $VD/model 2>/dev/null)）    固件：${fw:-?}    序列号前 4 位：${sp:-?}"
        echo "屏幕：$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null)    内存：$(awk '/^MemTotal:/ {printf "%d MB", $2/1024}' /proc/meminfo)"
        echo "内核：$(uname -a 2>/dev/null)"
        echo "----------------------------------------"
        echo "第 $(tget run.n) 次启动（$(tget run.date)）"
        tline "内核模块加载" chk.kmod "开机没完成，没测"
        tline "binder 通信" chk.binder "开机没完成，没测"
        v=$(tget container.s); if [ -n "$v" ]; then echo "[PASS] 容器启动：${v} 秒"; else echo "[FAIL] 容器启动：没有起来"; fi
        v=$(tget boot.s)
        case "$(tget boot.result)" in PASS) echo "[PASS] 开机完成（sys.boot_completed）：${v} 秒" ;;
                                      FAIL) echo "[FAIL] 开机完成：$(tget fail.reason)" ;;
                                      *) echo "[ -- ] 开机完成：还在开机" ;; esac
        tline "桌面" chk.home "开机没完成，没测"
        tline "显示桥" chk.display "开机没完成，没测"
        tline "触摸屏" chk.touch "开机没完成，没测"
        tline "网络" chk.net "开机没完成，没测"
        tline "内存" chk.mem "开机没完成，没测"
        v=$(tget sleep.ok); w=$(tget wake.ok)
        if [ -n "$v" ] && [ -n "$w" ]; then echo "[PASS] 休眠 / 唤醒：进入屏保时 Android 冻结（$v），唤醒后解冻（$w）"
        elif [ -n "$v" ]; then echo "[FAIL] 休眠 / 唤醒：进了屏保（$v），没有看到唤醒"
        else echo "[ -- ] 休眠 / 唤醒：还没测（Android 开着时按一下电源键，几秒后再按一下）"; fi
        tline "返回 Kindle" ret.kindle "还没返回过"
        v=$(tget ret.s); [ -n "$v" ] && echo "       返回用时：${v} 秒"
        tline "用户存储释放（USB）" ret.userstore "还没返回过"
        if [ -n "$dn" ]; then
            if [ "$dm" = 0 ]; then echo "[PASS] 书库文件：安装前 $dn 个，现在都在"
            else echo "[FAIL] 书库文件：安装前 $dn 个，现在少了 $dm 个（安装前的备份在 veradroid/backup/ 里）"; fi
        fi
        [ -f $VD/DOCUMENTS-LOSS.log ] && echo "[FAIL] 有书库丢失记录（veradroid/DOCUMENTS-LOSS.log）"
        c0=$(tget crash0); c1=$(kindle_crashes)
        [ -n "$c0" ] && [ "$c1" -gt "$c0" ] 2>/dev/null && echo "[INFO] Kindle 自己的崩溃记录：这次运行后 documents 里新增 $((c1 - c0)) 个 *_crash_* 文件（请告诉我们个数）"
        u=$(tget upload.id); [ -n "$u" ] && echo "已上传：$u"
        echo "----------------------------------------"
        echo "PASS = 通过，FAIL = 失败，SKIP = 没条件测，-- = 还没到这一步。"
        echo "请把这个文件和你手动试的结果（翻页、微信读书冷启动秒数、残影、旋转、USB）一起发给我们；"
        echo "也可以点书库里的“VeraDroid 上传测试结果”（会先在屏幕上问你，确认后才上传）。"
    } > $TRES.tmp 2>/dev/null && mv -f $TRES.tmp $TRES
    sync
}

# ---- 上传用的精简诊断包（去掉文件名）----
# $1 = 输出目录。只放：报告、宿主日志（去掉文件名）、开机 / 失败时存的 logcat、内核日志、内存、模块、安装日志。
# 不放：书库清单、Android 的 dumpsys package / usagestats、应用数据。
test_bundle() {
    o=$1; rm -rf "$o"; mkdir -p "$o" || return 1
    test_report
    cp $TRES "$o/TEST-RESULT.txt" 2>/dev/null
    cp $TDIR/state "$o/state.txt" 2>/dev/null
    for f in $TDIR/logcat-*.txt $TDIR/getprop-*.txt $TDIR/tombstones-*.txt $TDIR/dmesg-*.txt; do [ -f "$f" ] && redact < "$f" > "$o/${f##*/}"; done
    [ -f $LOG ] && tail -c 400000 $LOG | redact > "$o/veradroid.log"
    for f in $TDIR/veradroid-*.log; do [ -f "$f" ] && redact < "$f" > "$o/${f##*/}"; done
    for f in /mnt/base-us/veradroid/logs/install-detail.log /mnt/us/veradroid/logs/install.log /mnt/base-us/veradroid-install-detail.log /mnt/us/veradroid-install.log /tmp/veradroid-stop.log $TLOG $VD/last-stop.log; do   # root-legacy: 老版本安装器的日志也带上
        [ -f "$f" ] && tail -c 131072 "$f" | tr '\r' '\n' | grep -av '下载中' | redact > "$o/$(basename "$f")"
    done
    # 书库丢失记录：只要带时间的那几行（数量），不要后面列出的文件名
    [ -f $VD/DOCUMENTS-LOSS.log ] && grep '^\[' $VD/DOCUMENTS-LOSS.log | redact > "$o/DOCUMENTS-LOSS.txt"
    dmesg 2>/dev/null | redact > "$o/dmesg-now.txt"
    cat /proc/last_kmsg 2>/dev/null | tail -n 400 | redact > "$o/last_kmsg.txt"
    for f in meminfo modules misc version cpuinfo; do cat /proc/$f > "$o/proc-$f.txt" 2>/dev/null; done
    ls -laR $VD/kmod 2>/dev/null > "$o/kmod.txt"
    free > "$o/free.txt" 2>/dev/null; df -k > "$o/df.txt" 2>/dev/null
    find "$o" -type f -size 0 -exec rm -f {} \; 2>/dev/null
    return 0
}

# 直接运行（tmpfs 副本）：sh tester-watch.sh watch
if [ "${1:-}" = watch ] && [ -z "${TESTER_SOURCED:-}" ]; then
    TESTER_SOURCED=1
    VD=${VD:-/mnt/base-us/veradroid}
    . $VD/boot/lib/common.sh
    . $VD/boot/lib/settings.sh 2>/dev/null && settings_load 2>/dev/null
    test_watch
    exit 0
fi
