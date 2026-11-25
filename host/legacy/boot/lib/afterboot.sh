# SPDX-License-Identifier: GPL-3.0-or-later
# Re-create installd profile / data dirs immediately before pm install.
# A clean 32-bit image (or a mid-boot race) can drop them; installd then
# fails with create_app_data -1 / "Failed to prepare .../profiles/cur/0/<pkg>"
# (WeRead on KT5 2022, user diag).
ensure_installd_dirs() {
    mkdir -p $SYS/data/misc/profiles/cur/0 $SYS/data/misc/profiles/ref              $SYS/data/data $SYS/data/app $SYS/data/app-lib $SYS/data/dalvik-cache 2>/dev/null
    chown 1000:1000 $SYS/data/misc/profiles $SYS/data/misc/profiles/cur         $SYS/data/misc/profiles/cur/0 $SYS/data/misc/profiles/ref 2>/dev/null
    chmod 0771 $SYS/data/misc/profiles $SYS/data/misc/profiles/cur         $SYS/data/misc/profiles/cur/0 $SYS/data/misc/profiles/ref         $SYS/data/data $SYS/data/app 2>/dev/null
}

# 开机后处理：等 sys.boot_completed=1，把 $VD/apps/*.apk 装进系统（装好即删），装完打开桌面。
# 用户把 APK 通过 USB 放进 Kindle 的 veradroid/apps/ 即可。
# 剪贴板：TrustManager 的“设备已锁定”默认是 1，只有锁屏报告状态变化时才重算；我们没有 SystemUI 锁屏，
# ClipboardService 于是对所有应用返回空剪贴板。替锁屏报告一次（ITrustManager 第 5 个方法 reportKeyguardShowingChanged），
# 它按“没有密码、没显示锁屏”重算成未锁定。开机后、system_server 软重启后、屏保解冻后各调一次（重复调用无副作用）。
trust_unlock() {
    adb_sh 'service call trust 5' >/dev/null 2>&1
}
# 桌面（VeraHome）和输入法（VeraIME）是镜像里的系统应用，开机 dexopt 只给它们 verify-profile（解释执行 + JIT）：
# 方块主页第一次画 1.5–2.2 s（Voyage 模拟器）。编成 speed 以后同一台 0.9–1.05 s，之后两次重搭 1.3–1.8 s -> 0.8–0.9 s、0.6–0.7 -> 0.35 s。
# 编一次约 10 秒（Voyage 模拟器 dex2oat，odex 3 MB），换了镜像（升级）以后开机 dexopt 会把它打回 verify-profile，所以每次开机看一眼
# dalvik-cache 里 odex 的过滤器（直接读 OAT 头，不起 JVM），不是 speed 就在开机 90 秒后低优先级编一次。冻结 / 屏保时不编。
home_aot() {
    for ap in VeraHome:com.veradroid.home VeraIME:com.veradroid.ime; do
        o=$SYS/data/dalvik-cache/arm/system@priv-app@${ap%%:*}@${ap%%:*}.apk@classes.dex
        [ -f "$o" ] || o=$SYS/data/dalvik-cache/arm/system@app@${ap%%:*}@${ap%%:*}.apk@classes.dex
        [ -f "$o" ] || continue
        f=$(dd if="$o" bs=4096 count=4 2>/dev/null | tr '\0' '\n' 2>/dev/null | sed -n '/^compiler-filter$/{n;p;q;}')
        case "$f" in speed|everything) continue ;; esac
        # 一次只跑一个 dex2oat：应用的预编译（compile.sh）正在编、或者别的 dex2oat 在跑时等它们做完（PW4 模拟器：和微信读书的编译
        # 撞在一起、换页时，编桌面用了 1205 秒，单独编 9 秒；0.98 门禁 PW6 第一次开机两边也叠在一起）。只是排着队等前台应用的
        # 工作进程不算忙（以前等 compile.pid，会被它挡满 30 分钟）。最多等 30 分钟，冻结 / 屏保时不编。编的时候写 $W/aot.pid，
        # 预编译的工作进程看到就先不开始下一个。
        hw=0
        while pidof dex2oat >/dev/null 2>&1 || [ "$(sed -n 's/^state=//p' $X/ctl/compile.status 2>/dev/null)" = running ] || [ -f $W/frozen ] || [ -f $W/saver_frozen ]; do
            hw=$((hw + 1)); [ $hw -gt 180 ] && { t "aot: ${ap#*:} skipped (compiler busy for 30 min)"; return 0; }
            sleep 10
        done
        read -r ah _ < /proc/self/stat; echo "$ah" > $W/aot.pid
        a0=$(cut -d' ' -f1 /proc/uptime)
        adb_sh "cmd package compile -m speed -f ${ap#*:}" > $W/aot.out 2>&1 &
        ac=$!
        # 和应用的预编译一样：dex2oat 调成 nice 19 + IO 空闲、内存危险时停掉（compile.sh）
        type compile_babysit >/dev/null 2>&1 && compile_babysit $ac   # 不带包名：桌面本来就在前台，带了会被当成“到了前台”停掉
        wait $ac; rm -f $W/aot.pid
        r=$(tr -d '\r' < $W/aot.out | tail -1)
        t "aot: ${ap#*:} ${f:-?} -> speed: $r ($(awk -v a=$a0 -v b=$(cut -d' ' -f1 /proc/uptime) 'BEGIN{printf "%.0f", b-a}') s)"
    done
}
# 允许“未知来源”（Settings.Secure.install_non_market_apps = 1）。系统里没有“设置”应用，这个开关用户打不开；关着时 Android 里
# 的一切安装（文件管理器 / 浏览器点 APK、应用自己的更新）都被软件包安装程序拦下：“出于安全考虑，您的手机已设置为禁止安装
# 来源不明的应用”，点“设置”也没反应（社区 Oasis 3，0.8.13：“本地安装不了软件”）。新机型 435338c 在 afterboot 里做了，
# 老机型线是在那之前分出来的，一直没有。每次开机都看：先在宿主侧读 settings_secure.xml（不起 JVM），不是 1 才写。
unknown_sources_allow() {
    grep -qs 'name="install_non_market_apps" value="1"' $SYS/data/system/users/0/settings_secure.xml && return 0
    adb_sh 'settings put secure install_non_market_apps 1' >/dev/null 2>&1 && t "after_boot: install_non_market_apps=1 (unknown sources allowed)"
    return 0
}
adb_sh() {
    # Refresh into a live container; never run host sh if vd_in fails (empty prefix).
    pref=$(vd_in) || return 1
    [ -n "$pref" ] || return 1
    # am / pm / settings 是 ART 进程：栈限制按正在跑的镜像定（vd_art_stack，在子 shell 里改，不影响调用者）
    ( vd_art_stack
      $pref /system/bin/sh -c "export PATH=/system/bin:/system/xbin ANDROID_DATA=/data ANDROID_ROOT=/system; $*" )
}

# 包是否已安装：读 PackageManager 自己维护的 packages.list（宿主上直接读文件，不用为一次查询起一个 JVM）
pkg_installed() { grep -q "^$1 " $SYS/data/system/packages.list 2>/dev/null; }

# ---- apps 文件夹里装不上的 APK（用户诊断：一个下载不完整的 APK 每次开机都重试，用户一直不知道为什么）----
apk_size() { wc -c < "$1" 2>/dev/null | tr -d ' '; }
# 安装前的检查：返回 0 并打印原因 = 这个文件肯定装不上
apk_precheck() {
    sz=$(apk_size "$1")
    [ "${sz:-0}" -ge 1024 ] 2>/dev/null || { echo "文件是空的或太小（${sz:-0} 字节），多半是下载或拷贝没有完成。请重新下载后再放进来。"; return 0; }
    case "$(dd if="$1" bs=4 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')" in
        504b0304) return 1 ;;
    esac
    echo "这不是 APK 安装包（文件开头不是 ZIP 格式，可能是网页、压缩包或别的文件改了名字）。请从应用官网重新下载 .apk 文件。"
    return 0
}
# PackageManager 的失败代码 -> 给用户看的原因
apk_fail_reason() { # $1 = pm 输出  $2 = apk（可选，用来进一步判断“格式不对”是哪一种）
    case "$1" in
    *INSTALL_FAILED_OLDER_SDK*) echo "这个版本要求更新的 Android，VeraDroid 是 Android 7.1。请换一个支持 Android 7 的旧版本。" ;;
    *INSTALL_FAILED_NO_MATCHING_ABIS*|*INSTALL_FAILED_CPU_ABI_INCOMPATIBLE*) echo "这个安装包只有 64 位或 x86 版本，Kindle 只能运行 32 位 ARM（armeabi-v7a）的应用。" ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*) echo "Android 的存储空间不够。删掉一些应用或书后，把文件名末尾的 .failed 去掉，重启 VeraDroid 会再装一次。" ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_FAILED_INCONSISTENT_CERTIFICATES*|*INSTALL_FAILED_SHARED_USER_INCOMPATIBLE*)
        echo "已经装着同一个应用，但签名不一样（来源不同）。先在桌面上卸载旧的再装。" ;;
    *INSTALL_FAILED_DUPLICATE_PERMISSION*|*INSTALL_FAILED_CONFLICTING_PROVIDER*) echo "和已经装着的另一个应用冲突（同名的权限或内容提供者）。" ;;
    *INSTALL_FAILED_MISSING_SPLIT*|*INSTALL_FAILED_INVALID_APK*) echo "这是拆分安装包（APKS / XAPK）里的一部分，单独装不上。请下载完整的单个 APK。" ;;
    *INSTALL_FAILED_MISSING_SHARED_LIBRARY*) echo "这个应用需要 Google 服务或系统里没有的组件，VeraDroid 里不能运行。" ;;
    *INSTALL_PARSE_FAILED_NO_CERTIFICATES*|*INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING*|*INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES*)
        echo "安装包没有签名或签名损坏，多半是文件不完整。请重新下载。" ;;
    *INSTALL_PARSE_FAILED_*|*INSTALL_FAILED_INVALID_URI*)
        # 没下载完的 ZIP（结尾的中央目录没了，PackageManager 报 "zipro: Invalid file"）和拆分包（ZIP 里只有几个 .apk）分开说
        if [ -n "$2" ] && [ -f "$2" ]; then
            if ! tail -c 65558 "$2" 2>/dev/null | od -An -tx1 -v | tr -d ' \n' 2>/dev/null | grep -q 504b0506; then
                echo "安装包不完整（文件结尾缺了一截，多半是下载或拷贝中断了）。请重新下载后再放进来。"; return 0
            fi
            if command -v unzip >/dev/null 2>&1; then
                l=$(unzip -l "$2" 2>/dev/null | awk '$1 ~ /^[0-9]+$/ && NF >= 4 { print $NF }')
                if ! printf '%s\n' "$l" | grep -qx 'AndroidManifest.xml' && printf '%s\n' "$l" | grep -q '\.apk$'; then
                    echo "这是拆分安装包（APKS / XAPK，里面装着几个 APK），VeraDroid 只能装单个 APK。请下载完整的 APK 版本。"; return 0
                fi
            fi
        fi
        echo "安装包已损坏或格式不对，Android 7.1 读不了它的清单文件。请重新下载，或换一个旧一点的版本。" ;;
    *) echo "安装失败：$(printf '%s' "$1" | sed -n 's/.*Failure \[\([A-Z_]*\).*/\1/p' | head -1)。" ;;
    esac
}
# 重试计数（只在“没有拿到结论”时累加）：放在系统目录里，不在用户看得见的 apps 文件夹里
apk_tries_key() { printf '%s:%s' "$(basename "$1")" "$(apk_size "$1")" | tr -c 'A-Za-z0-9._:\n-' '_'; }
apk_tries_inc() {
    k=$(apk_tries_key "$1"); n=$(awk -v k="$k" '$1 == k { print $2 }' $SYS/apk-tries 2>/dev/null | tail -1)
    n=$(( ${n:-0} + 1 ))
    { grep -v "^$k " $SYS/apk-tries 2>/dev/null; echo "$k $n"; } > $SYS/apk-tries.tmp && mv -f $SYS/apk-tries.tmp $SYS/apk-tries
    echo $n
}
apk_tries_clear() { k=$(apk_tries_key "$1"); [ -f $SYS/apk-tries ] && { grep -v "^$k " $SYS/apk-tries > $SYS/apk-tries.tmp; mv -f $SYS/apk-tries.tmp $SYS/apk-tries; }; }
# 装不上：改名成 <文件名>.failed（不再每次开机重试），旁边写一个说明，Android 里弹通知（VeraHome 的 HostService 读 ctl/notice.*）
apk_failed() { # $1 = apk  $2 = 原因（中文）  $3 = pm 原始输出（可空）
    nm=$(basename "$1")
    apk_tries_clear "$1"
    mv -f "$1" "$1.failed" 2>/dev/null
    {
        echo "$nm 没有装上。"
        echo
        echo "原因：$2"
        echo
        echo "这个文件已经改名为 $nm.failed，VeraDroid 不会再自动安装它。"
        echo "换一个能用的安装包放进这个文件夹即可；想再试一次，就把文件名末尾的 .failed 去掉。"
        [ -n "$3" ] && { echo; echo "系统返回：$(printf '%s' "$3" | tr '\r\n' '  ' | cut -c1-300)"; }
    } > "$1.failed.txt" 2>/dev/null
    t "after_boot: install FAILED, renamed to $nm.failed: $2"
    notice "应用没有装上：$nm" "$2"
}
# 在 Android 里给用户看一条通知（VeraHome 的 HostService：通知栏一条 + 屏幕上一个提示框）。第一行标题，其余是正文。
notice() {
    [ -d $X/ctl ] || return 0
    n=$(date +%s)$$
    printf '%s\n%s\n' "$1" "$2" > $X/ctl/notice.$n.tmp && chmod 666 $X/ctl/notice.$n.tmp && mv -f $X/ctl/notice.$n.tmp $X/ctl/notice.$n
}

# 开机完成后的数据自检（2026-11-13 事故后加）：
#  1. Kindle 书库：开机前的清单（container_start 的 docs_snapshot）和安装器留下的安装前清单（veradroid/state/docs-manifest）里的文件
#     必须都还在；少了就大声记下来（veradroid.log、内核日志、veradroid/DOCUMENTS-LOSS.log）并从安装前备份补回。
#  2. PackageManager 删过用户 0 的数据（uiderrors.txt 里新出现 "Destroying user"）：同样大声记下来。
boot_data_check() {
    docs_verify $W/docs.before "after boot, list taken before the container started"
    bm=/mnt/base-us/veradroid/state/docs-manifest
    [ -f $bm ] || [ ! -f /mnt/base-us/veradroid-docs-manifest ] || bm=/mnt/base-us/veradroid-docs-manifest   # root-legacy: 老版本安装器写在根目录
    if [ -f $bm ]; then
        docs_verify $bm "after the first boot of a new install, list taken by the installer" && t "documents: all $(wc -l < $bm | tr -d ' ') files of the pre-install list are present"
        mv -f $bm $bm.checked 2>/dev/null
    fi
    ue=$SYS/data/system/uiderrors.txt; n0=$(cat $W/uiderr.lines 2>/dev/null); n0=${n0:-0}
    if [ -f $ue ] && tail -n +$((n0 + 1)) $ue 2>/dev/null | grep -q 'Destroying user'; then
        m="!!! ANDROID USER DATA DESTROYED by PackageManager during this boot: $(tail -n +$((n0 + 1)) $ue | grep 'Destroying user' | head -n 1 | cut -c1-200)"
        t "$m"; echo "veradroid: $m" > /dev/kmsg 2>/dev/null; echo "[$(date '+%F %T')] $m" >> $VD/DOCUMENTS-LOSS.log 2>/dev/null
    fi
    return 0
}
after_boot() {
    # Re-assert binder before waiting — covers init races that hide the node.
    binder_repair 2>/dev/null || true
    local i=0 w=2 n t0 el P0
    MEDIA_STOPPED=
    t0=$(cut -d. -f1 /proc/uptime); P0=$(cat $W/pid 2>/dev/null)
    # First boot after an upgrade re-dexopts every app (the installer clears dalvik-cache): with a big app such as
    # WeRead that took 11 min on a 5.17 emulator. Giving up after 5 min skipped the whole after_boot for that session
    # (no $W/booted -> no background compile, no IME / .vdhome / time format fixes). Keep waiting up to 30 min:
    # every 2 s for the first 5 min, then every 10 s. The loop still ends at once when the container goes away.
    while :; do
        # Re-read live pid each iteration (container may have been restarted; shell $P goes stale).
        P=$(cat $W/pid 2>/dev/null)
        [ -n "$P" ] && kill -0 "$P" 2>/dev/null || return 0
        # Android was restarted while we waited: the new start has its own after_boot.
        [ -z "$P0" ] || [ "$P" = "$P0" ] || return 0
        # Do not run settings before boot_completed: every adb_sh is an nsenter+Android shell.
        [ "$(adb_sh 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n')" = 1 ] && break
        # 内核没有 seccomp（3.0 / 4.1 的 Kindle 都没开）：mediacodec / mediaextractor 一进 minijail 就 abort，init 每几秒重启一次。
        # 开机期间就要停：Oasis 3 模拟器上这两个循环吃满两个核，system_server 15 分钟都没开完机。它们在这些机器上本来就用不了。
        if [ -z "$MEDIA_STOPPED" ] && ! grep -q '^Seccomp:' /proc/self/status 2>/dev/null &&
           [ -n "$(adb_sh 'getprop init.svc.mediacodec' 2>/dev/null | tr -d '\r\n')" ]; then
            adb_sh 'setprop ctl.stop mediacodec; setprop ctl.stop mediaextractor' >/dev/null 2>&1 && { MEDIA_STOPPED=1; t "no seccomp: stopped mediacodec / mediaextractor"; }
        fi
        el=$(( $(cut -d. -f1 /proc/uptime) - t0 ))
        [ $el -ge 1800 ] && { t "after_boot: boot not completed after ${el}s"; return 0; }
        [ $el -ge 300 ] && w=10
        # Progress every ~30 s (every ~60 s after 5 min) so long first boots are diagnosable in veradroid.log
        i=$((i + 1)); n=15; [ $w = 10 ] && n=6
        [ $((i % n)) -eq 0 ] && t "after_boot: still waiting for boot_completed (${el}s)"
        sleep $w
    done
    t "after_boot: boot completed"
    rm -f $SYS/sysfont.try       # 带着系统字体开机完成了（container.sh 的 sysfont_prepare）
    type lock_reset_check >/dev/null 2>&1 && lock_reset_check   # USB 上的锁屏密码重置文件（lock.sh）
    boot_data_check
    cut -d. -f1 /proc/uptime > $W/booted     # compile.sh：开机完成一分钟后才开始后台预编译（看门狗）
    # 有些机型/模拟器上 epd 收不到 Android 首帧，splash_end 的 FULL 也可能落在 e-ink 尚未就绪时；
    # 开机完成后再强制一次整页 GC16，避免停在原生 Kindle/黑屏。
    epd_send FULL
    sleep 1
    # 下面这些都是写进 Android 数据库后就一直有效的设置（settings / pm disable）。每条命令都要起一个新的 JVM，
    # PW6 真机上一共约 10 秒 CPU，正好压在桌面刚出来的时候。每个版本只做一次；恢复出厂会清掉 data，标记跟着没了，自然重做。
    ABV=$(cat $SYS/version 2>/dev/null); ABM=$SYS/data/.vd-afterboot
    AB_ONCE=
    [ -n "$ABV" ] && [ "$(cat $ABM 2>/dev/null)" = "$ABV" ] || AB_ONCE=1
    if [ -n "$AB_ONCE" ]; then
        # AOSP crash dialogs (e.g. a transient media provider restart) cover the e-ink UI.
        adb_sh 'settings put global hide_error_dialogs 1; settings put global show_first_crash_dialog 0; settings put global show_restart_in_crash_dialog 0' >/dev/null 2>&1
        # 系统里没有开机向导（Provision）：不标记“已完成设置”时 HOME 键被忽略（user setup is in progress）、快捷设置受限
        adb_sh 'settings put global device_provisioned 1; settings put secure user_setup_complete 1' >/dev/null 2>&1
        # 用不到的后台服务：通知排序助手常驻约 37 MB；并关闭云备份与后台扫描省电（参考文石 Go 6 Gen 2）
        adb_sh 'pm disable-user --user 0 android.ext.services; settings put secure backup_enabled 0; settings put global wifi_scan_always_enabled 0' >/dev/null 2>&1
        slim_after_boot
        if lowmem; then
            set -- $(heap_values); hg=$1; hs=$2   # 和 heap_fix 一致（memory.sh）
            adb_sh 'for p in com.android.printspooler com.android.managedprovisioning com.android.companiondevicesupport com.android.bookmarkprovider com.android.providers.calendar com.android.calendar com.android.sharedstoragebackup com.android.camera2 com.android.gallery3d; do pm disable-user --user 0 $p >/dev/null 2>&1; done; settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0; settings put global wifi_scan_always_enabled 0; setprop dalvik.vm.heapgrowthlimit '"$hg"'; setprop dalvik.vm.heapsize '"$hs" >/dev/null 2>&1 || true
            t "low-ram: disabled extra pkgs + zero anim"
        fi
    fi
    unknown_sources_allow
    lmk_tune          # 运行时参数，不持久：每次开机都要做
    proc_limit_apply  # 机型文件的 PROC_LIMIT（memory.sh）：同样不持久
    trust_unlock      # 剪贴板可读（见 trust_unlock）
    # 默认桌面固定为 VeraHome（装了自带“桌面”入口的应用后，系统会让用户重新选）。cmd 走 binder，不起 JVM，约 0.1 秒。
    adb_sh 'cmd package set-home-activity com.veradroid.home/.HomeActivity' >/dev/null 2>&1
    chmod 777 $SYS/data/media $SYS/data/media/0 2>/dev/null   # init / vold 会把它们改回 770，传书和读书应用都要能进去
    # 商店 / 传书 / 在线更新的下载暂存目录：宿主固定从这里 pm install。
    # 以前它可能不存在或者属主不对，商店点“安装”会报“无法创建下载目录”。
    # /data/media/0 is 0771 root:sdcard_rw on a clean image; VeraHome's uid is
    # not in that group, so it cannot even traverse into .upload. Fix the parent
    # too, or every transfer fails with "Permission denied".
    # 离线更新文件夹（lib/localupdate.sh）：装好就有这个文件夹和一份说明，用户才知道离线包往哪里放
    if [ ! -f $VD/update/README.txt ]; then
        mkdir -p $VD/update 2>/dev/null && cat > $VD/update/README.txt 2>/dev/null <<'RDME'
VeraDroid 离线更新

把离线更新包原样放进这个文件夹（不用解压），任选一种：
  - veradroid-pw6-版本号.tar.gz 或 veradroid-k49-版本号.tar.gz
  - 整个离线包 VeraDroid-Offline.zip 或 VeraDroid-Offline-Experimental.zip

Paperwhite 6 / Colorsoft 用 pw6 的包，其他机型（PW5、KT5、KT6、Scribe 等）用 k49 的包；
放错的、版本不比现在新的会自动跳过（不会降级）。

下次在书库里点“VeraDroid”启动时自动校验并安装，装完包会删掉。
结果写在这个文件夹的 result.txt 里。
RDME
    fi
    upload_dir_fix || t "after_boot: upload staging dir $SYS/data/media/0/.upload is not usable ($(ls -ldn $SYS/data/media/0/.upload 2>&1))"
    # 桌面的主题 / 状态目录 .vdhome：系统重装后 VeraHome 的 uid 可能变了（Scribe 2 模拟器实测 u0_a4 -> u0_a2），
    # 目录还属于旧 uid 时壁纸 / 自定义图标写不进去。每次开机把它交给当前的 VeraHome。
    hu=$(awk '$1 == "com.veradroid.home" { print $2; exit }' $SYS/data/system/packages.list 2>/dev/null)
    # /sdcard/VeraDroid（壁纸图库、自定义图标）也是 VeraHome 建的 0700 目录，同样要跟着换属主：rc089 矩阵 kt6-5192 上重装后
    # VeraHome 从 10006 变成 10001，这个目录还属于 10006，网页传壁纸报“空间不够：剩余 0 MB”、VeraDroid/icons 里的图标不生效。
    case "$hu" in ''|*[!0-9]*) ;; *) for hd in .vdhome VeraDroid; do [ -d $SYS/data/media/0/$hd ] && chown -R "$hu" $SYS/data/media/0/$hd 2>/dev/null; done ;; esac
    # WeRead and other readers keep downloads under /sdcard/Android/data and
    # /sdcard/WeRead.  The parents are often 0770 root:root after a clean
    # install, so the app's DownloadService never finishes.
    mkdir -p $SYS/data/media/0/Android/data $SYS/data/media/0/Android/obb \
             $SYS/data/media/0/WeRead $SYS/data/media/0/Download 2>/dev/null
    chmod 777 $SYS/data/media/0/Android $SYS/data/media/0/Android/data \
              $SYS/data/media/0/Android/obb $SYS/data/media/0/WeRead \
              $SYS/data/media/0/Download 2>/dev/null
    # Books pushed by the host are root:root 0600; KOReader runs as an app uid
    # and cannot read them. Make the book directory readable on every boot.
    mkdir -p $SYS/data/media/0/Books 2>/dev/null
    chmod 777 $SYS/data/media/0/Books 2>/dev/null
    find $SYS/data/media/0/Books -type f -print0 2>/dev/null | xargs -0 chmod 644 2>/dev/null   # 一次 chmod，不是每本书 fork 一次
    # installd creates per-app profile dirs here; a clean 32-bit image may not have
    # them, and then every new APK fails with create_app_data -1.
    ensure_installd_dirs
    # 安装时只做 interpret-only（装得快，传书 / 商店装 127 MB 的微信读书不用在安装里等 dex2oat），
    # 装完由 compile.sh 在后台低优先级编成 speed（F6）。APP_COMPILE=off 时保持以前的做法：
    # PW6 (957 MB) 安装时直接 speed；512 MB 机型 interpret-only。不在开机时编译（QEMU 上开机期间的 dex2oat 不可靠）。
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    # 512 MB（老机型全是）：安装时只做 verify-at-runtime。interpret-only 对大应用要几百 MB（番茄 243 MB dex：dex2oat 455 MB、
    # 交换区用完、600 s 还没完），装的时候整机卡死；verify-at-runtime 74 s、256 MB。小应用装完照样由 compile.sh 在空闲时编。
    if [ "${APP_COMPILE:-auto}" != off ] && [ "${mt:-0}" -lt 700000 ] && [ "${COMPILE_BIG_LOW:-on}" != off ]; then
        adb_sh 'setprop pm.dexopt.install verify-at-runtime; setprop pm.dexopt.bg-dexopt verify-at-runtime' >/dev/null 2>&1
    elif [ "${APP_COMPILE:-auto}" != off ]; then
        adb_sh 'setprop pm.dexopt.install interpret-only; setprop pm.dexopt.bg-dexopt interpret-only' >/dev/null 2>&1
    elif [ "${mt:-0}" -ge 700000 ]; then
        adb_sh 'setprop pm.dexopt.install speed; setprop pm.dexopt.bg-dexopt speed' >/dev/null 2>&1
    else
        adb_sh 'setprop pm.dexopt.install interpret-only; setprop pm.dexopt.bg-dexopt interpret-only' >/dev/null 2>&1
    fi
    # 系统自带的 WebView（Chromium 52，读 /data/local/tmp/webview-command-line，调试版系统才有这个口子）。
    # 镜像里有空 GLES2（libGLESv2_vdnull，docs/webview-nogpu.md）时：不能 --disable-gpu——那样 WebView 不建同步合成器，
    # 网页正文永远空白；GPU 线程拿空 GLES2 建上下文，网页由 CPU 画。关掉要媒体服务的视频编解码探测（没有 mediaserver，
    # 探测会抛异常让 WebView 崩），关掉会画到 GPU 纹理里的加速 canvas / WebGL（空 GLES2 画不出东西，页面会自己退回软件）。
    # 旧镜像没有这个库：保持原来的 --disable-gpu（至少不崩）。
    mkdir -p $SYS/data/local/tmp
    if [ -f $SYS/rootfs/system/lib/libGLESv2_vdnull.so ]; then
        echo "webview --skip-gpu-data-loading --disable-accelerated-video-decode --disable-webrtc-hw-encoding --disable-webrtc-hw-decoding --disable-accelerated-2d-canvas --disable-webgl --disable-gpu-rasterization" > $SYS/data/local/tmp/webview-command-line
    else
        echo "webview --skip-gpu-data-loading --disable-gpu" > $SYS/data/local/tmp/webview-command-line
    fi
    chmod 644 $SYS/data/local/tmp/webview-command-line
    local d=$VD/apps f
    # 装好后删不掉的安装包（KT6 / Scribe 5.17+ 的加密存储上，中文文件名的 rm 会失败：用户诊断，
    # 每次进 Android 都把微信读书重装一遍，正在看书时被强制关掉）：按 名字 大小 修改时间 记下，以后跳过
    APK_DONE=$SYS/data/.vd-apk-done
    apk_key() { printf '%s %s %s' "$(basename "$1")" "$(apk_size "$1")" "$(stat -c %Y "$1" 2>/dev/null)"; }
    apk_done_has() { [ -f "$APK_DONE" ] && grep -qxF "$(apk_key "$1")" "$APK_DONE"; }
    for f in "$d"/*.apk; do
        [ -f "$f" ] || continue
        if apk_done_has "$f"; then t "after_boot: $(basename "$f") was installed before (file could not be removed), skip"; continue; fi
        # Never replace the system VeraHome/VeraIME with a data-app copy on every boot:
        # that force-stops Home, changes its code path and can leave the UI frozen.
        case "$(basename "$f")" in
            VeraHome.apk)
                if pkg_installed com.veradroid.home; then
                    t "after_boot: VeraHome already system app, skip"; continue
                fi ;;
            VeraIME.apk)
                if pkg_installed com.veradroid.ime; then
                    t "after_boot: VeraIME already installed, skip"; continue
                fi ;;
        esac
        t "after_boot: install $(basename "$f") ($(apk_size "$f") bytes)"
        # 文件本身就不是安装包（空文件、网页、没下载完）：不用起 pm，直接告诉用户
        if bad=$(apk_precheck "$f"); then
            apk_failed "$f" "$bad" ""
            continue
        fi
        # /data/local/tmp 在容器内可写；pm 需要读得到文件
        ensure_installd_dirs
        mkdir -p $SYS/data/local/tmp 2>/dev/null
        cp "$f" $SYS/data/local/tmp/vdinst.apk
        # 安装期间不要冻结 Android（屏保 / 休眠时 sleep.sh 看这个标记）：冻在 dex2oat 中间，解冻后 PackageManager
        # 的线程还卡着，system_server 会被自己的 Watchdog 杀掉（用户诊断：屏保 4.5 小时后解冻，Android 重启）
        read -r mypid _ < /proc/self/stat; echo "$mypid" > $W/installing    # 本进程（after_boot 是后台子 shell，$$ 是早已退出的父进程）
        type compile_hold >/dev/null 2>&1 && compile_hold      # 后台编译拿着 PackageManager 的锁：先让开（compile.sh）
        type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status state=installing "name=$(basename "$f")" "start=$(date +%s)" from=apps
        r=$(adb_sh 'pm install -d -g /data/local/tmp/vdinst.apk' 2>&1)
        case "$r" in *Success*) ;; *) r=$(adb_sh 'pm install -r -d -g /data/local/tmp/vdinst.apk' 2>&1) ;; esac
        type compile_release >/dev/null 2>&1 && compile_release
        rm -f $W/installing
        printf '%s\n' "$r" | tail -n 3 >> $LOG
        ist=error; printf '%s' "$r" | grep -q Success && ist=done
        type vp_kvw >/dev/null 2>&1 && vp_kvw $X/ctl/apkinst.status "state=$ist" "name=$(basename "$f")" "result=$(printf '%s' "$r" | tail -n 1)" from=apps
        if printf '%s' "$r" | grep -q Success; then
            AB_ONCE=1      # 装了东西（可能是输入法）：下面的输入法设置要重做
            apk_tries_clear "$f"
            rm -f "$f" 2>/dev/null; [ -e "$f" ] && mv -f "$f" "$f.installed" 2>/dev/null
            if [ -e "$f" ]; then
                apk_key "$f" >> "$APK_DONE"; echo >> "$APK_DONE"
                t "after_boot: installed; could not remove the apk, recorded so it is not installed again"
            else
                t "after_boot: installed, apk removed"
            fi
        elif printf '%s' "$r" | grep -q 'Failure \['; then
            # PackageManager 明确拒绝了这个文件：每次开机重试也不会变，改名停掉重试，并告诉用户为什么
            apk_failed "$f" "$(apk_fail_reason "$r" "$f")" "$r"
        else
            # 没有拿到 PackageManager 的结论（中途停止 / 回到 Kindle / 系统重启）：留着下次开机再装，最多试 3 次
            n=$(apk_tries_inc "$f")
            if [ "$n" -ge 3 ]; then
                apk_failed "$f" "连续 $n 次开机都没能装完（安装过程中系统停止了）。可以在传书网页里重新上传安装。" "$r"
            else
                t "after_boot: install interrupted ($n/3), apk kept for the next boot"
            fi
        fi
        rm -f $SYS/data/local/tmp/vdinst.apk
    done
    # 输入法：VeraIME 装好后设为默认（它是数据应用，重装/升级后 secure setting 不会被自动改回来）
    if [ -n "$AB_ONCE" ] && pkg_installed com.veradroid.ime; then
        adb_sh 'settings put secure enabled_input_methods com.veradroid.ime/.VeraImeService' >/dev/null 2>&1
        adb_sh 'settings put secure default_input_method com.veradroid.ime/.VeraImeService' >/dev/null 2>&1
        t "after_boot: VeraIME enabled"
    fi
    [ -n "$AB_ONCE" ] && [ -n "$ABV" ] && echo "$ABV" > $ABM
    ( sleep 90; home_aot ) </dev/null >>$LOG 2>&1 &
    # 可选 DNS：settings.conf 里的 DNS_MODE 在开机后重新应用到 netd（DHCP 会把它改回自动）
    dns_apply
    # 12 / 24 小时制跟 Kindle（tz.sh；值没变就不写）
    type time_format_apply >/dev/null 2>&1 && time_format_apply
    type tz_sync_live >/dev/null 2>&1 && tz_sync_live       # 开机前按偏移猜的时区，后台已经查到准确名字时换掉
}

# 小内存机型（< 700 MB：PW5 / KT5 / KT6 / 老机型）：ActivityManager 按“内存 + 屏幕尺寸”给 lmkd 选杀进程的门槛，
# Kindle 的大屏让它选了大屏手机那一档：文件缓存低于 72 MB 就连前台应用也杀。512 MB 的 Kindle 上微信读书（墨水屏版）
# 每 12 秒被杀一次、无限重启（KT5 模拟器实测）。开机后用 vdlmk 把门槛降下来（单位：页 = 4 KB）：
# 前台 8 MB、可见 12 MB、可感知 16 MB、备份 20 MB、缓存 24–32 MB。957 MB 的 PW6 不改。
lmk_tune() {
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    [ -n "$mt" ] && [ "$mt" -lt 700000 ] || return 0
    lmk_kernel_tune 2>/dev/null || true
    [ -x "$VD/bin/vdlmk" ] || { t "lmk: vdlmk missing"; return 0; }
    s=/proc/$P/root/dev/socket/lmkd
    i=0; while [ ! -S "$s" ] && [ $i -lt 30 ]; do sleep 1; i=$((i + 1)); done
    if [ -S "$s" ]; then
        lt_ok=
        for lt_w in 2 4 0; do
            if "$VD/bin/vdlmk" "$s" 2048:0 3072:100 4096:200 8192:300 14336:900 16384:906 >>$LOG 2>&1; then
                t "lmk: lmkd thresholds set"; lt_ok=1; break
            fi
            [ "$lt_w" -gt 0 ] && sleep "$lt_w"
        done
        [ -n "$lt_ok" ] || t "lmk: vdlmk failed (after 3 tries)"
    else
        t "lmk: lmkd socket missing, kernel minfree only"
    fi
}
