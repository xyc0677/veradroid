# SPDX-License-Identifier: GPL-3.0-or-later
# 容器：unshare 出 mount / UTS / IPC / net / PID 命名空间，在新命名空间里把 $R 变成根（pivot_root）并执行 /init。
# net 命名空间里默认只有 lo（网络由 slirp4netns 另行接入，尚未实现）。

# 触摸屏（fts_ts）、翻页键（kindle-input-pt）的事件节点；名字认不出来就按多点触摸的 ABS 位图找
input_find() {   # $1 = 设备名（正则）
    awk -v pat="$1" '/^N: Name=/ { m = ($0 ~ pat) }
        m && /^H: Handlers=/ { for (i = 1; i <= NF; i++) if (match($i, /event[0-9]+$/)) { print "/dev/input/" substr($i, RSTART); exit } }' /proc/bus/input/devices
}
touch_setup() {
    if [ -n "${PAGE_KEYS:-}" ]; then
        KFB_KEYS=$(input_find "\"$PAGE_KEYS\"")
        [ -n "$KFB_KEYS" ] && chmod 666 $KFB_KEYS
        export KFB_KEYS
        type page_keys_layout >/dev/null 2>&1 && page_keys_layout
        return 0
    fi
    KFB_KEYS=$(input_find '"kindle-input-pt"')
    # kindle-input-pt（5.20 的 input_manager 建的虚拟设备，Kindle Remote 的键从这里出来）的按键位图是全部按键，包括 BTN_STYLUS /
    # BTN_TOOL_PEN：Android 7.1 的 EventHub 把它当成“外接手写笔”，触摸屏随之进入手写笔融合模式，每次手指按下都先等 72 ms
    # （EXTERNAL_STYLUS_DATA_TIMEOUT，等笔的压力数据）才往下发，触摸源也变成 0xd002（蓝牙手写笔）。PW6 / Scribe 的诊断里都是这样
    # （dumpsys input：kindle-input-pt Classes 0x800 External Stylus）。这些机型没有物理翻页键，配对过的翻页器 / Kindle Remote 由蓝牙桥
    # （bt.sh / vdbtkeys，它自己按名字接管 kindle-input-pt）从 veradroid-btkeys 发给 Android，所以默认不再把它绑进容器。KFB_PTKEYS=1 恢复旧做法。
    if [ -n "$KFB_KEYS" ] && [ "${KFB_PTKEYS:-0}" != 1 ]; then
        t "pt keys: $KFB_KEYS not given to android (it would register as an external stylus; KFB_PTKEYS=1 to bind it)"
        KFB_KEYS=
    fi
    [ -n "$KFB_KEYS" ] && chmod 666 $KFB_KEYS
    export KFB_KEYS
    t "pt keys=${KFB_KEYS:-none}"
}

display_prepare() {
    rm -rf $X; mkdir -p $X/ctl
    if [ -x "$FBINK" ]; then
        eval "$($FBINK -e 2>/dev/null)"
    else
        # No FBInk (some jailbreak packages omit it): read the real framebuffer.
        # PW5 reports virtual_size=1248 (stride) while visible modes=1236.
        # Use the hardware stride width: FBInk/fb0 write in 1248-byte rows.
        v=$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null)
        vw=${v%%,*}; vh=${v#*,}
        m=$(sed -n 's/.*U:\([0-9][0-9]*\)x\([0-9][0-9]*\)p.*/\1 \2/p' /sys/class/graphics/fb0/modes 2>/dev/null | head -1)
        mw=; mh=; [ -n "$m" ] && { set -- $m; mw=$1; mh=$2; }
        viewWidth=${vw:-$mw}
        viewHeight=${mh:-$vh}
        viewVisWidth=${mw:-$viewWidth}     # visible width (PW5: 1236 of the 1248-pixel rows); rotation turns inside it
        deviceName=$(cat /proc/device-tree/model 2>/dev/null | tr -d '\0')
        t "display: no fbink, fallback ${viewWidth:-1272}x${viewHeight:-1696}"
    fi
    # 旋转（docs/rotation.md）：90 / 270 时 Android 按横屏的逻辑尺寸（高 x 可见宽）开机，显示桥和触摸前端负责把它转到面板上
    case "$(rot_deg)" in
        90|270) echo "${viewHeight:-1696} ${viewVisWidth:-${viewWidth:-1272}} ${deviceName:-PaperWhite 6}" > $X/info ;;
        *)      echo "${viewWidth:-1272} ${viewHeight:-1696} ${deviceName:-PaperWhite 6}" > $X/info ;;
    esac
    # Hardware model detected by bootstrap; Android's ro.product.model is generic.
    m=$(cat $SYS/model 2>/dev/null | tr -d '\r\n')
    [ -n "$m" ] && echo "$m" > $X/model
    hwc_prepare
    vdshim_prepare
    chmod -R 777 $X
}
# ashmem 垫片（android/device/veradroid/kindle/shim/vdshim.c，init 给每个进程 LD_PRELOAD）也随宿主程序发（bin/libvdshim.so），
# 开机前换掉镜像里的那份：镜像里的旧版对 ASHMEM_GET_PROT_MASK 一律回答 rwx，应用自带的 Chromium 内核（番茄小说的
# libwebviewbytedance.so）把共享内存设成只读后检查不过，每次启动 SIGTRAP，应用随后进自己的“安全模式”
# （用户诊断 PW6 5.17.1，2026-11-19）。先写 .new 再 mv：镜像里的文件和回退用的旧镜像是硬链接，不能原地改。
vdshim_prepare() {
    vs_dst=$R/system/lib/libvdshim.so
    [ -f "$VD/bin/libvdshim.so" ] && [ -f "$vs_dst" ] || return 0
    cmp -s "$VD/bin/libvdshim.so" "$vs_dst" && return 0
    cp "$VD/bin/libvdshim.so" "$vs_dst.new" && chmod 644 "$vs_dst.new" && mv -f "$vs_dst.new" "$vs_dst" &&
        t "ashmem: libvdshim.so installed" || { rm -f "$vs_dst.new"; t "ashmem: libvdshim.so not installed, image copy kept"; }
    return 0
}
# 系统合成：hwcomposer.kindle.so 随宿主程序发（bin/），开机前装进系统的 /system/lib/hw；
# 用不用由 $X/hwc.off 决定（模块自己在 SurfaceFlinger 打开它时看这个文件），所以装着也不影响 KFB_HWC=0 的机器。
hwc_prepare() {
    if [ -f "$VD/bin/hwcomposer.kindle.so" ] && [ -d "$R/system/lib/hw" ]; then
        if ! cmp -s "$VD/bin/hwcomposer.kindle.so" "$R/system/lib/hw/hwcomposer.kindle.so"; then
            cp "$VD/bin/hwcomposer.kindle.so" "$R/system/lib/hw/hwcomposer.kindle.so.new" && chmod 644 "$R/system/lib/hw/hwcomposer.kindle.so.new" \
                && mv -f "$R/system/lib/hw/hwcomposer.kindle.so.new" "$R/system/lib/hw/hwcomposer.kindle.so" && t "display: hwcomposer.kindle.so installed"
        fi
    fi
    if [ "$KFB_HWC" = 1 ] && [ -f "$R/system/lib/hw/hwcomposer.kindle.so" ]; then rm -f $X/hwc.off; else touch $X/hwc.off; fi
    case "$KFB_HZ" in 60|20|15) echo $KFB_HZ > $X/hz ;; *) echo 30 > $X/hz ;; esac
    # 对话框遮罩（hwcomposer.kindle 的 hwc.dim，0-255）：KFB_DIM 是百分比，off = -1（遮罩帧交给 GLES）
    case "${KFB_DIM:-20}" in off) echo -1 > $X/hwc.dim ;; ''|*[!0-9]*) echo 51 > $X/hwc.dim ;;
        *) d=${KFB_DIM}; [ "$d" -gt 100 ] && d=100; echo $((d * 255 / 100)) > $X/hwc.dim ;; esac
    # framework 项目（docs/framework-project.md）：只有 project/framework 分支的镜像认这些开关文件，老镜像看不见它们
    if [ "$KFB_HWC" = 1 ] && [ "${KFB_ONEPAGE:-0}" = 1 ]; then touch $X/onepage; else rm -f $X/onepage; fi
    if [ "${KFB_RGB565:-0}" = 1 ]; then touch $X/rgb565; else rm -f $X/rgb565; fi
    if [ "${VD_AUDIO:-1}" = 0 ]; then touch $X/noaudio; else rm -f $X/noaudio; fi
}
# 屏幕旋转 KFB_ROTATE=0|90|180|270（docs/rotation.md）：Android 永远正着画，显示桥（veradroid-epd）转画面，
# 触摸前端（veradroid-input）转坐标和边缘手势区。0 时两个程序的命令行和以前完全一样。
rot_deg() { case "$KFB_ROTATE" in 90|180|270) echo $KFB_ROTATE ;; *) echo 0 ;; esac; }
rot_args() {   # 给 veradroid-epd / veradroid-input 的额外参数（0 度时为空：命令行和以前一样）
    r=$(rot_deg); ra=
    if [ "$r" != 0 ]; then
        ra="--rotate $r"
        [ -n "$viewVisWidth" ] && [ "$viewVisWidth" != "${viewWidth:-1272}" ] && ra="$ra --vw $viewVisWidth"
    fi
    echo $ra
}
# 显示桥接：系统往共享缓冲 $X/fb 画、往 $X/epd.fifo 报“第几页”，桥接程序比较差异后局部刷新墨水屏
display_start() {
    [ -x "$VD/bin/veradroid-epd" ] || { t "display: veradroid-epd missing"; return 0; }
    epd_pd=-1; case "$KFB_PWRDOWN" in ""|*[!0-9]*) ;; *) epd_pd=$KFB_PWRDOWN ;; esac   # 面板掉电延时，没设 / off = 不碰
    : > $W/epd.log   # 追加方式打开：watchdog 的 log_cap 截短它时，显示桥接着写在新的末尾（不会留下空洞）
    $VD/bin/veradroid-epd run $X/fb $X/epd.fifo $X/ctl --w ${viewWidth:-1272} --h ${viewHeight:-1696} --contrast ${KFB_CONTRAST:-1.3} --dither ${KFB_DITHER:-3} --flash ${KFB_FLASH:-0} --invert ${KFB_INVERT:-0} --lightf ${KFB_LIGHTF:-0} --darken ${KFB_DARKEN:-0} --mode ${KFB_MODE:-0} --settle ${KFB_SETTLE:-120} --hold ${KFB_HOLD:-70} --pace ${KFB_PACE:-0} --clean ${KFB_CLEAN:-0} --scroll-delay ${KFB_SCROLLDELAY:-500} --scroll-style ${KFB_SCROLLSTYLE:-1} --gcn ${KFB_GCN:-0} --mono ${KFB_MONO:-0} --max-defer ${KFB_MAXDEFER:-1200} --flip-input ${KFB_FLIPINPUT:-1000} --reagl-min ${KFB_REAGL_MIN:-35} --anim-mode ${KFB_ANIM_MODE:-1} --anim-dir ${KFB_ANIM_DIR:-2} --anim-steps ${KFB_ANIM_STEPS:-12} --anim-min ${KFB_ANIM_MIN:-60} --swipe ${KFB_SWIPE:-1} --pwrdown $epd_pd --rt ${EPD_RT:-0} $(rot_args) >>$W/epd.log 2>&1 &
    echo $! > $W/epd.pid
    rot_deg > $W/rotate
    # 以后能不能当场转 180：0 度启动时没传 --vw，可见宽 != 行宽（PW5 无 FBInk）的机器转了会偏 12 像素，只能重启
    if [ "$(rot_deg)" != 0 ] || [ -z "$viewVisWidth" ] || [ "$viewVisWidth" = "${viewWidth:-1272}" ]; then touch $W/rotate.live; else rm -f $W/rotate.live; fi
    # 等桥接建好共享缓冲和 FIFO（它打开 FIFO 时这两样都好了），最多 0.5 秒。以前固定睡 0.5 秒：真机上桥接几十毫秒就好了，
    # 剩下的全算在开机里（perf/boot-path，tools/bench 的宿主日志时间线 "display: bridge pid"）
    ds_p=$(cat $W/epd.pid); ds_i=0
    while [ $ds_i -lt 25 ] && kill -0 "$ds_p" 2>/dev/null && ! ls -l /proc/$ds_p/fd 2>/dev/null | grep -q 'epd\.fifo'; do usleep 20000; ds_i=$((ds_i + 1)); done
    t "display: bridge pid $ds_p (ready after $((ds_i * 20)) ms)"
}
display_stop() { alive $W/epd.pid && kill "$(cat $W/epd.pid)" 2>/dev/null; rm -f $W/epd.pid $W/rotate $W/rotate.live; }

# 宿主命名空间（chroot）模式：3.0 内核（PW2 / PW3 / Oasis 1/2 / Voyage / 入门款 2014、2016）没有任何命名空间。
# Android 直接 chroot 进 $R，和 Kindle 共用进程、挂载和网络。保护 Kindle 的手段：
#  - /dev/vdchroot 标记：系统里的 android_reboot 看到它就不调用 reboot() / remount_ro（small-patches.py）；
#  - /proc/sys 整个只读（包括 net：这里的 net 就是 Kindle 的）；没有 slirp，Android 暂时没有网络；
#  - 容器的进程按“根目录是 $R”识别（冻结 / 停止都用它），挂载在 stop.sh 里由深到浅逐个卸掉。
# 测试开关：$VD/test-hostns 存在时，有命名空间的内核也走 chroot / 共用网络这条路（只为在 PW6 真机上测这条路径；正常安装里没有这个文件）
hostns() { [ -e "$VD/test-hostns" ] || [ ! -e /proc/self/ns/pid ]; }
chroot_pids() { # 容器的进程（没有 PID 命名空间时）
    # 以前只认“/proc/PID/root 这个链接读出来正好是 $R”。容器根上面的挂载一旦被延迟卸载（umount -l：上一次 stop 和新的一次启动
    # 踩在一起时就会这样），还活着的容器进程的 root 读出来变成 “/”，这里再也认不出它们：stop.sh 杀不掉，它们一直占着系统盘的
    # loop 设备（kt6-517104 模拟器复现：/init、logd、servicemanager 留着，loop1 摘不掉，之后启动 / 安装都报“系统盘还在被占用”，
    # 只能重启 Kindle）。所以再加一条不看路径的：根目录所在的设备就是系统盘镜像的 loop 设备（或者和 $R 是同一个目录）。
    cp_devs=" "
    for cp_l in $(img_loops); do
        cp_d=$(cat /sys/block/$cp_l/dev 2>/dev/null)
        [ -n "$cp_d" ] && cp_devs="$cp_devs$(( ${cp_d%%:*} * 256 + ${cp_d##*:} )) "
    done
    cp_r=$(stat -L -c %d:%i "$R/." 2>/dev/null); [ "$cp_r" = "$(stat -L -c %d:%i /. 2>/dev/null)" ] && cp_r=
    {
        for d in /proc/[0-9]*; do
            [ "$(readlink $d/root 2>/dev/null)" = "$R" ] && echo ${d#/proc/}
        done
        # 一次 stat 看完所有进程的根目录（设备号 inode 路径）
        stat -L -c '%d %i %n' /proc/[0-9]*/root 2>/dev/null | awk -v devs="$cp_devs" -v r="$cp_r" '
            { split($3, a, "/"); if (index(devs, " " $1 " ") > 0 || (r != "" && ($1 ":" $2) == r)) print a[3] }'
    } | sort -un
}
# vold 的 fstrim 在没有 fstab 的容器里必然段错误（TrimTask::addFromFstab 解引用空指针），vold 重启的那几秒里
# PackageManager 准备用户 0 的存储失败，就会删掉用户 0 的全部数据（2026-11-13 事故：/data/data 全没了，documents 也被清空）。
# PackageManager 开机时“距上次 fstrim 超过 3 天”就强制跑一次（last-fstrim 的修改时间）：每次开机前把它摸新，这条路就不会走到。
fstrim_defuse() {
    [ -d $SYS/data/system ] || return 0
    touch $SYS/data/system/last-fstrim 2>/dev/null && chown 1000:1000 $SYS/data/system/last-fstrim 2>/dev/null
    return 0
}
# 系统字体（SYSTEM_FONT，桌面“系统字体 → 应用到所有应用”写）：让所有应用的默认字体（微信读书的“系统默认字体”、设置、浏览器……）都换成它。
# 做法：开机前按镜像里的 /system/etc/fonts.xml 生成一份改过的放在 $W/sysfont，容器里绑在 /system/etc/fonts.xml 上；字体文件绑到
# /dev/vdfont/system.ttf（容器自己的 tmpfs）。fonts.xml 里的文件名是相对 /system/fonts/ 的（FontListParser、WebView 的 Skia 都是），
# 所以写成 ../../dev/vdfont/system.ttf。改动三处：
#   1. sans-serif（第一个家族 = 默认字体）只留这一个字体（400 常规；粗体 / 斜体由 Minikin 合成）——第一个家族有的字一律用它；
#   2. 原来的 Roboto 挪成紧跟着的无名回退家族：选的字体缺的西文字母还用 Roboto；
#   3. 加 zh-Hans / zh-Hant / ja 三个家族指向它，放在所有回退字体前面：serif、monospace 之类别的家族里的中文也用它（语言匹配分更高）。
# 系统镜像本身一个字节都不改；没设、文件不在 / 读不了 / 太小 / 不是字体，就什么都不绑（原样字体）。
# 换字体要重启 Android 才生效（zygote 开机时预载 Typeface）。
# 保险：带着这个字体连续两次没开机完成（$SYS/sysfont.try 计数，after_boot 开机完成时删掉），以后就不再用它（$SYS/sysfont.bad），
# 直到用户重新选一次字体（settings_apply 清掉）。
SYSFONT_REF=../../dev/vdfont/system.ttf
sysfont_host() {   # Android 里的路径 -> 宿主上的文件。只认三个字体目录里的文件，不认子目录和 ..
    sf_n=${1##*/}
    case "$sf_n" in ''|.*|*..*) return 1 ;; esac
    case "${1%/*}" in
        /data/media/0/Fonts|/sdcard/Fonts|/storage/emulated/0/Fonts) echo "$VD/fonts/$sf_n" ;;
        /data/media/0/KindleFonts|/sdcard/KindleFonts|/storage/emulated/0/KindleFonts) echo "/usr/java/lib/fonts/$sf_n" ;;
        /system/fonts) echo "$R/system/fonts/$sf_n" ;;
        *) return 1 ;;
    esac
}
sysfont_ok() {     # 能读、至少 16 KB、文件头是 TrueType / OpenType / 字体集
    [ -f "$1" ] && [ -r "$1" ] || return 1
    [ "$(wc -c < "$1" 2>/dev/null || echo 0)" -ge 16384 ] || return 1
    case "$(head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n')" in 00010000|4f54544f|74727565|74746366) return 0 ;; esac
    return 1
}
sysfont_prepare() {   # 宿主侧，container_start 里、启动容器之前
    rm -rf $W/sysfont
    case "$SYSTEM_FONT" in ''|default|none) rm -f $SYS/sysfont.try; return 0 ;; esac
    sf_src=$(sysfont_host "$SYSTEM_FONT") || { t "sysfont: ignored (not in a font folder): $SYSTEM_FONT"; return 0; }
    if [ "$(cat $SYS/sysfont.bad 2>/dev/null)" = "$SYSTEM_FONT" ]; then t "sysfont: $SYSTEM_FONT failed to boot twice before, using stock fonts (pick it again to retry)"; return 0; fi
    sf_try=0
    if [ -f $SYS/sysfont.try ] && [ "$(sed -n 2p $SYS/sysfont.try)" = "$SYSTEM_FONT" ]; then sf_try=$(sed -n 1p $SYS/sysfont.try); fi
    case "$sf_try" in ''|*[!0-9]*) sf_try=0 ;; esac
    if [ "$sf_try" -ge 2 ]; then
        printf '%s\n' "$SYSTEM_FONT" > $SYS/sysfont.bad; rm -f $SYS/sysfont.try
        t "sysfont: Android did not finish booting twice with $SYSTEM_FONT, using stock fonts"; return 0
    fi
    sysfont_ok "$sf_src" || { t "sysfont: $sf_src missing, unreadable or not a font: stock fonts"; return 0; }
    sf_xml=$R/system/etc/fonts.xml
    [ -f "$sf_xml" ] || { t "sysfont: no $sf_xml"; return 0; }
    mkdir -p $W/sysfont || return 0
    awk -v ref="$SYSFONT_REF" '
        function fam(attr) { return "    <family" attr ">\n        <font weight=\"400\" style=\"normal\">" ref "</font>\n    </family>" }
        st == 0 && /<family[^>]*name="sans-serif"[ >\/]/ && !/<\/family>/ {
            st = 1; print "    <family name=\"sans-serif\">"; print "        <font weight=\"400\" style=\"normal\">" ref "</font>"; print "    </family>"
            print "    <family>"; next
        }
        st == 1 { print; if (/<\/family>/) { st = 2; print fam(" lang=\"zh-Hans\""); print fam(" lang=\"zh-Hant\""); print fam(" lang=\"ja\"") } next }
        { print }
        END { if (st != 2) exit 1 }' "$sf_xml" > $W/sysfont/fonts.xml 2>/dev/null
    if [ $? -ne 0 ] || ! grep -q '</familyset>' $W/sysfont/fonts.xml; then
        rm -rf $W/sysfont; t "sysfont: could not rewrite fonts.xml (no sans-serif family?): stock fonts"; return 0
    fi
    chmod 644 $W/sysfont/fonts.xml
    printf '%s\n' "$sf_src" > $W/sysfont/src
    printf '%s\n' "$SYSTEM_FONT" > $W/sysfont/android
    printf '%s\n%s\n' $((sf_try + 1)) "$SYSTEM_FONT" > $SYS/sysfont.try
    t "sysfont: $SYSTEM_FONT -> all apps (try $((sf_try + 1)))"
}
sysfont_mount() {     # 容器里（run_in_ns），/dev 的 tmpfs 建好之后、换根之前
    [ -s $W/sysfont/fonts.xml ] && [ -s $W/sysfont/src ] || return 0
    sf_src=$(cat $W/sysfont/src)
    mkdir -p $R/dev/vdfont && touch $R/dev/vdfont/system.ttf || return 0
    if mount --bind "$sf_src" $R/dev/vdfont/system.ttf; then
        mount -o remount,ro,bind $R/dev/vdfont/system.ttf 2>/dev/null
        if [ "$(wc -c < $R/dev/vdfont/system.ttf)" = "$(wc -c < "$sf_src")" ]; then   # 真的绑上了（不是那个空占位文件）
            mount --bind $W/sysfont/fonts.xml $R/system/etc/fonts.xml && return 0
        fi
        umount $R/dev/vdfont/system.ttf 2>/dev/null
    fi
    echo "veradroid: system font $sf_src not mounted, stock fonts" >&2
    return 0
}
sysbin_wrap_mount() {   # 容器里（run_in_ns），/dev 的 tmpfs 建好之后、换根之前
    # 两个系统程序换成 boot/ 里的小脚本（原因写在脚本里）：
    #   dex2oat：应用自己（DexClassLoader / 插件框架）编 /data 下的代码时只做 verify-at-runtime，不用 speed；
    #   cmd：应用自己跑的 "cmd package compile ..."（自己给自己强制重编）不执行。
    # 真的程序复制到 /dev/vdbin（不能 bind：Android init 把根设成 shared，后面绑在 /system/bin/dex2oat 上的包装会传播到
    # 同一个 peer 的 bind 上，包装 exec 自己死循环；测试机实测）。SYSBIN_WRAP=off 关掉。
    [ "${SYSBIN_WRAP:-on}" = off ] && return 0
    for sw_n in dex2oat cmd; do
        [ -f $VD/boot/$sw_n-wrap.sh ] && [ -x $R/system/bin/$sw_n ] || continue
        mkdir -p $R/dev/vdbin || return 0
        if cp $R/system/bin/$sw_n $R/dev/vdbin/$sw_n && cp $VD/boot/$sw_n-wrap.sh $R/dev/vdbin/$sw_n-wrap &&
            chmod 755 $R/dev/vdbin/$sw_n $R/dev/vdbin/$sw_n-wrap &&
            mount --bind $R/dev/vdbin/$sw_n-wrap $R/system/bin/$sw_n; then :
        else
            rm -f $R/dev/vdbin/$sw_n $R/dev/vdbin/$sw_n-wrap
            echo "veradroid: $sw_n wrapper not mounted" >&2
        fi
    done
    return 0
}
# chroot 模式下，zygote 和它孵出来的应用读自己的 /proc/self/maps（还有 /proc/self/fd 的链接）看到的是宿主路径
# （/mnt/base-us/system/veradroid/rootfs/system/lib/libart.so；它们的根挂载跟文件所在的挂载不在一条链上，内核 d_path 走不到根就给全局路径。
# init、logd 和 ashell 看是 /system/...）。加固壳（爱加密 ijiami：晋江 7.1.9 等）从 maps 里找 libart.so 再按这个路径打开它，
# 容器里没有这个路径 -> 隐藏的 dex 没加载上，ART 报 "Null DexFile::mCookie"，接着在 DexFile.defineClassNative 里
# GetArrayLength(null) 中止（用户诊断：PW6 固件 5.17.1，内核没有 PID 命名空间，每次启动 3 秒内 SIGABRT；测试机上用 test-hostns 复现，
# 建了下面的链接能进主界面，去掉又崩）。
# 办法：在 Android 自己的 /mnt（init 挂的 tmpfs；zygote 和各应用的挂载命名空间里是同一个 tmpfs，后建的文件也看得见）里建 $R 这个路径，
# 指回根。用相对链接（../../../..）：容器里解析到 /，宿主上解析到 $R 自己，宿主那边跟着链接走的工具不会走到 Kindle 的根上。
# Android 停止时 stop.sh 卸掉这个 tmpfs，链接跟着没了。
hostns_selflink() {
    case "$R" in /mnt/?*/?*) ;; *) return 0 ;; esac    # $R 在 /mnt 下才放得进 Android 的 /mnt
    sl_dir=${R%/*}
    sl_up=$(echo "$sl_dir" | awk -F/ '{ s = ".."; for (i = 3; i <= NF; i++) s = s "/.."; print s }')
    i=0
    while [ $i -lt 300 ]; do                          # init 第二阶段一开始就挂 /mnt；最多等 60 秒
        if grep -q " $R/mnt tmpfs " /proc/mounts 2>/dev/null; then
            if [ -L "$R$R" ] || { mkdir -p "$R$sl_dir" && ln -s "$sl_up" "$R$R"; }; then
                t "hostns: $R -> $sl_up inside android (apps see host paths in /proc/self/maps)"
            else
                t "hostns: could not link $R inside android"
            fi
            return 0
        fi
        usleep 200000; i=$((i + 1))
    done
    t "hostns: android /mnt not mounted after 60 s, $R not linked"
}
# AlarmManager 的定时器（docs/host-settings.md 第 5 节“时钟”）：Android 7.1 的 libandroid_servers.so 建 5 个 timerfd，其中两个用
# CLOCK_REALTIME_ALARM / CLOCK_BOOTTIME_ALARM，要 CAP_WAKE_ALARM；7.1 的 zygote 不给 system_server 这个权限（8.0 才加），
# timerfd_create 返回 EPERM，AlarmManager 退回“Falling back to a handler”：所有闹钟改用一个按 uptime 计时的 Handler 消息，
# 非唤醒和唤醒闹钟共用一条消息、后排的把前面的顶掉，休眠的时间也不算。结果：TIME_TICK 晚几分钟到几小时才到
# （测试机：TIME_TICK 过期 22 分钟还没发，下一条唤醒闹钟在 10 小时后），状态栏和应用里的时钟停着（1.00 用户反馈“时间还是不对”），
# 应用的定时任务也一样。镜像修好之前（android/small-patches.py），开机前把这个库的副本改两处再绑上去：
#   1. 时钟表 {REALTIME_ALARM, REALTIME, BOOTTIME_ALARM, BOOTTIME, MONOTONIC, REALTIME} 里的两个 *_ALARM 换成不要权限的
#      REALTIME / BOOTTIME（休眠时不叫醒 Kindle：休眠归 Kindle 的 powerd；醒来后照样马上到期）；
#   2. epoll 的 EPOLLWAKEUP 去掉（movt r5,#0x2000 换成 nop.w）：Android 冻结时到期的定时器不在内核里挂着唤醒源、挡住休眠。
# 只在两处的原字节完全对上时才改（1.00 的 pw6 / 32 位镜像都在同一个偏移），对不上（已修好的镜像、别的版本）就什么都不做。
# ALARM_FIX=0 关掉。
ALARMFIX_LIB=system/lib/libandroid_servers.so
ALARMFIX_TAB=154856; ALARMFIX_TAB_OLD=080000000000000009000000070000000100000000000000; ALARMFIX_TAB_NEW=000000000000000007000000070000000100000000000000
ALARMFIX_EPW=52736;  ALARMFIX_EPW_OLD=c2f20005; ALARMFIX_EPW_NEW=aff30080
alarmfix_hex() { dd if="$1" bs=1 skip="$2" count="$3" 2>/dev/null | od -An -v -tx1 | tr -d ' \n'; }
alarmfix_put() { printf "$(printf '%s' "$3" | sed 's/../\\x&/g')" | dd of="$1" bs=1 seek="$2" conv=notrunc 2>/dev/null; }
alarmfix_prepare() {   # 宿主侧，container_start 里、启动容器之前：$W/alarmfix/lib.so = 改好的副本（不改镜像里的文件：它和回退用的旧镜像是硬链接）
    rm -rf $W/alarmfix
    [ "${ALARM_FIX:-1}" = 0 ] && return 0
    af_src=$R/$ALARMFIX_LIB; [ -f "$af_src" ] || return 0
    [ "$(alarmfix_hex "$af_src" $ALARMFIX_TAB 24)" = $ALARMFIX_TAB_OLD ] && [ "$(alarmfix_hex "$af_src" $ALARMFIX_EPW 4)" = $ALARMFIX_EPW_OLD ] || return 0
    mkdir -p $W/alarmfix && cp "$af_src" $W/alarmfix/lib.so || { rm -rf $W/alarmfix; return 0; }
    alarmfix_put $W/alarmfix/lib.so $ALARMFIX_TAB $ALARMFIX_TAB_NEW; alarmfix_put $W/alarmfix/lib.so $ALARMFIX_EPW $ALARMFIX_EPW_NEW
    if [ "$(alarmfix_hex $W/alarmfix/lib.so $ALARMFIX_TAB 24)" = $ALARMFIX_TAB_NEW ] && [ "$(alarmfix_hex $W/alarmfix/lib.so $ALARMFIX_EPW 4)" = $ALARMFIX_EPW_NEW ] &&
       [ "$(wc -c < $W/alarmfix/lib.so)" = "$(wc -c < "$af_src")" ]; then
        chmod 644 $W/alarmfix/lib.so; t "alarmfix: AlarmManager timerfd clocks without CAP_WAKE_ALARM"
    else
        rm -rf $W/alarmfix; t "alarmfix: patching the copy failed, AlarmManager left as is"
    fi
    return 0
}
alarmfix_mount() {     # 容器里（run_in_ns），/dev 的 tmpfs 建好之后、换根之前。副本放进 /dev（不和别的绑定共用文件）
    [ -s $W/alarmfix/lib.so ] || return 0
    mkdir -p $R/dev/vdlib && cp $W/alarmfix/lib.so $R/dev/vdlib/libandroid_servers.so && chmod 644 $R/dev/vdlib/libandroid_servers.so &&
        mount --bind $R/dev/vdlib/libandroid_servers.so $R/$ALARMFIX_LIB && mount -o remount,ro,bind $R/$ALARMFIX_LIB 2>/dev/null
    [ "$(alarmfix_hex $R/$ALARMFIX_LIB $ALARMFIX_TAB 24)" = $ALARMFIX_TAB_NEW ] && return 0
    umount $R/$ALARMFIX_LIB 2>/dev/null; rm -f $R/dev/vdlib/libandroid_servers.so
    echo "veradroid: AlarmManager fix not mounted" >&2
    return 0
}
# 待机耗电（fix/standby-drain，2026-11-20 PW6 用户“关 Wi-Fi/蓝牙停在微信读书，夜里 10 小时掉 30 %”）：Android 的 healthd 把电池 uevent
# 的 netlink 和周期 timerfd（CLOCK_BOOTTIME_ALARM）用 EPOLLWAKEUP 挂在 epoll 上。Android 冻结（屏保、返回 Kindle）后下一条
# 电池 uevent / 定时器到期就把内核唤醒源（[timerfd] / NETLINK / eventpoll）激活，只有 healthd 下一次 epoll_wait 才放开，
# 冻着就一直挂着：powerd 每次进休眠都在 1 ms 内被打回（"System out of suspend"、"Repeated_unknown_wakeup"），整夜不休眠
# （用户诊断：uptime 和墙上时间一样走，电流 41-56 mA）。测试机实测冻结 1 秒后 [timerfd] 唤醒源就一直 active 到解冻。
# 修法：bin/vdnocap（tools/vdnocap）绑到 /sbin/healthd 上，去掉 CAP_BLOCK_SUSPEND / CAP_WAKE_ALARM 再 exec 原来的 healthd
# （原文件绑到 /dev/vdlib/healthd.real）：内核对没有 CAP_BLOCK_SUSPEND 的进程悄悄去掉 EPOLLWAKEUP，ALARM 定时器建不起来，
# healthd 只靠 uevent 更新电量。不能对整个容器去掉：zygote 给 system_server capset CAP_BLOCK_SUSPEND，失败就中止。
# 休眠归 Kindle 的 powerd（同 alarmfix）。NOCAP_FIX=0 关掉；文件不全就什么都不做（照旧启动）。
nocap_mount() {        # 容器里（run_in_ns），/dev 的 tmpfs 建好之后、换根之前
    [ "${NOCAP_FIX:-1}" = 0 ] && return 0
    [ -f $VD/bin/vdnocap ] && [ -f $R/sbin/healthd ] && [ ! -L $R/sbin/healthd ] || return 0
    mkdir -p $R/dev/vdlib && cp $VD/bin/vdnocap $R/dev/vdlib/vdnocap && chmod 755 $R/dev/vdlib/vdnocap &&
        : > $R/dev/vdlib/healthd.real && mount --bind $R/sbin/healthd $R/dev/vdlib/healthd.real || {
        umount $R/dev/vdlib/healthd.real 2>/dev/null; rm -f $R/dev/vdlib/vdnocap $R/dev/vdlib/healthd.real; return 0; }
    # 先确认包装能跑起来（静态 ARM 程序；跑不起来时 healthd 是 critical 服务，起不来 init 会重启容器）
    if $R/dev/vdlib/vdnocap /bin/sh -c 'exit 0' 2>/dev/null; then
        mount --bind $R/dev/vdlib/vdnocap $R/sbin/healthd && return 0
    fi
    umount $R/dev/vdlib/healthd.real 2>/dev/null; rm -f $R/dev/vdlib/vdnocap $R/dev/vdlib/healthd.real
    echo "veradroid: healthd capability wrapper not mounted" >&2
    return 0
}
# 可选的新网页内核（WEBVIEW=alt，docs/webview-alt.md）：Chromium 119.0.6045.194 WebView，包名 com.embrith.webview
# （社区贡献者 Embrith余烬 从 Chromium 源码编的，去掉了 Google Play 服务客户端库，minSdk 24，armeabi-v7a，贡献者自己的签名）。
# 不进镜像、不进安装包：用户在“显示与刷新”里打开时才下载（webview_dl，约 45 MB，sha256 钉死在下面），解到 $VD/webview-alt：
#   app/webview.apk（原样）、overlay/webview-alt-overlay.apk（android/webview-alt/overlay：framework-res 的资源覆盖，
#   config_webview_packages 只列 com.embrith.webview 并钉死它的证书）。
# 开机时（webview_mount）把一个新的 /system/vendor 绑进去：原来的 vendor/lib 照旧，加 app/webview-alt（PackageManager 把
# /vendor/app 当系统应用目录扫描）和 overlay/（zygote 开机时对 /vendor/overlay 跑 idmap --scan，framework 资源换成覆盖里的）。
# 镜像不改；自带的 com.android.webview 不动，换回去只要不绑。512 MB 机型（lowmem_ram）一律用自带的 52。
# 两个内核各用各的网页数据（每个应用自己的 app_webview：Cookie、localStorage、IndexedDB——数据在用网页的应用里，
# 不在内核的包里，所以换了包名也要分开）：换内核时把当前那份改名成 app_webview.vd-<内核> 收起来，换回去时原样放回。
# 不把 52 的数据复制给 119：模拟器实测 119 不迁移 52 的 Cookie 库和 SQLite 的 localStorage，反过来 52 读不了 119 的数据。
# 所以第一次换到 119 时应用里的网页要重新登录，换回 52 时原来的登录还在。用户把开关关掉（WEBVIEW=stock）后的下一次开机：
# 换回 52，再把下载的包和 119 的网页数据（app_webview.vd-alt）都删掉（webview_purge）。
WEBVIEW_ALT_VER=119.0.6045.194
WEBVIEW_ALT_PKG=com.embrith.webview
WEBVIEW_ALT_FILE=webview-alt-119.0.6045.194.tar.gz       # 服务器上 bundles/<文件>（主站和直链 dl.wenjin.jp 同一路径）
WEBVIEW_ALT_SHA=ae42c47db7bc99b8d676ec24d67f2c4982153f214bab051372c9c210747dab4f
WEBVIEW_ALT_SIZE=45094529
WEBVIEW_ALT_APK_SHA=5158f50ab9e73241ae5534fde8fee9ea6d81d65e8e781a0750eb2cfe0445c902
WEBVIEW_ALT_APK_SIZE=83155175
WEBVIEW_ALT_OV_SHA=05e890d2df8efb867df2040272a192befd0b86ed51b6f58d4da09220fa524323
WVD=$VD/webview-alt
webview_ok() {   # 下好的包可用：大小对、覆盖包在、下载时核对过的 sha256 记录（ok）和钉死的一样。开机不重算 80 MB 的 sha256
    [ -f $WVD/app/webview.apk ] && [ "$(webview_fsize $WVD/app/webview.apk)" = $WEBVIEW_ALT_APK_SIZE ] && [ -s $WVD/overlay/webview-alt-overlay.apk ] &&
        [ "$(cat $WVD/ok 2>/dev/null)" = "$WEBVIEW_ALT_APK_SHA $WEBVIEW_ALT_APK_SIZE" ]
}
webview_mode() {
    [ "${WEBVIEW:-stock}" = alt ] && webview_ok && ! lowmem_ram && { echo alt; return; }
    echo stock
}
webview_profiles() {   # $1 = 上次用的内核，$2 = 这次的（stock | alt）。Android 没有运行时调用
    for wp_p in $SYS/data/data/*; do
        [ -d "$wp_p" ] || continue
        wp_d=$wp_p/app_webview
        if [ -d $wp_d ]; then
            rm -rf $wp_p/app_webview.vd-$1
            mv $wp_d $wp_p/app_webview.vd-$1 || continue
        fi
        [ -d $wp_p/app_webview.vd-$2 ] && mv $wp_p/app_webview.vd-$2 $wp_d
        # WebView 记着上次用的版本号（shared_prefs/WebViewChromiumPrefs.xml 的 lastVersionCodeUsed）；版本变低就把整个
        # app_webview 清空（日志 "WebView package downgraded from 604514100 to 275610000; deleting contents"，模拟器实测）。
        # 换回 52 前删掉这个记录，放回去的那份数据才留得住。
        [ "$2" = stock ] && rm -f $wp_p/shared_prefs/WebViewChromiumPrefs.xml
    done
    return 0
}
webview_purge() {   # 用户关掉了开关、已经换回 52：删掉下载的包、没下完的部分和 119 的网页数据
    wpg_n=0
    for wpg_d in $SYS/data/data/*/app_webview.vd-alt; do [ -d "$wpg_d" ] || continue; rm -rf "$wpg_d"; wpg_n=$((wpg_n + 1)); done
    if [ -e $WVD ] || [ -e $WVD.new ] || [ -e $WVD.dl ] || [ $wpg_n -gt 0 ]; then
        rm -rf $WVD $WVD.new $WVD.dl
        t "webview: switched off: downloaded WebView 119 and $wpg_n apps' 119 web data removed"
    fi
    rm -f $X/ctl/webview.status 2>/dev/null
    return 0
}
webview_prepare() {   # 宿主侧，container_start 里、启动容器之前
    wv_m=$(webview_mode); wv_l=$(cat $SYS/webview.last 2>/dev/null); [ -n "$wv_l" ] || wv_l=stock
    echo $wv_m > $W/webview.mode
    if [ "$wv_m" != "$wv_l" ]; then
        webview_profiles $wv_l $wv_m
        # 上一个内核编好的代码（dalvik-cache）和 RELRO 对新包无效：删掉，Android 按新包重建
        rm -f $SYS/data/dalvik-cache/arm/system@app@webview@webview.apk@classes.* $SYS/data/dalvik-cache/arm/system@vendor@app@webview-alt@*@classes.* 2>/dev/null
        # zygote 的资源覆盖清单：换回 52 时覆盖包已经不在了（AssetManager 会跳过），还是删掉，下次开机按 /vendor/overlay 重新生成
        rm -f $SYS/data/resource-cache/overlays.list $SYS/data/resource-cache/*vendor@overlay@* 2>/dev/null
        rm -f $SYS/webview.svcoff      # 119 的后台服务关没关：换回 52 再换过来时重新关（afterboot.sh）
        t "webview: $wv_l -> $wv_m (app_webview profiles swapped)"
    fi
    echo $wv_m > $SYS/webview.last
    if [ "${WEBVIEW:-stock}" = alt ]; then
        [ $wv_m = stock ] && t "webview: alt requested but not used ($(lowmem_ram && echo 512MB || echo 'WebView 119 not downloaded'))"
    else
        webview_purge
    fi
    # 桌面看的 WEBVIEW_LIVE：settings_publish 在 container_start 之前已经发过一次，那时还没定
    [ -d "$X" ] && type settings_publish >/dev/null 2>&1 && settings_publish
    return 0
}
webview_mount() {     # 容器里（run_in_ns），换根之前
    [ "$(cat $W/webview.mode 2>/dev/null)" = alt ] || return 0
    wm_v=$R/dev/vdvendor
    if mkdir -p $wm_v/lib $wm_v/app/webview-alt $wm_v/overlay &&
        { [ ! -d $R/system/vendor/lib ] || mount --bind $R/system/vendor/lib $wm_v/lib; } &&
        mount --rbind $wm_v $R/system/vendor &&
        mount --bind $WVD/app $R/system/vendor/app/webview-alt && mount -o remount,ro,bind $R/system/vendor/app/webview-alt 2>/dev/null &&
        mount --bind $WVD/overlay $R/system/vendor/overlay && mount -o remount,ro,bind $R/system/vendor/overlay 2>/dev/null &&
        [ -f $R/system/vendor/app/webview-alt/webview.apk ] && [ -f $R/system/vendor/overlay/webview-alt-overlay.apk ]; then   # /vendor 是指向 /system/vendor 的绝对链接：在宿主上看要走 $R/system
        return 0
    fi
    for wm_m in $R/system/vendor/overlay $R/system/vendor/app/webview-alt $R/system/vendor/lib $R/system/vendor $wm_v/lib; do umount $wm_m 2>/dev/null; done
    echo "veradroid: webview-alt not mounted, stock WebView" >&2
    echo stock > $W/webview.mode
    return 0
}
# ---- 下载（桌面打开开关时 settings_apply 调；命令 webview get / cancel）--------------------------------------------
# 进度写 ctl/webview.status（KEY=value：state=downloading|verifying|ready|error|idle，got / total 字节，src，msg），桌面监听这个文件。
# 先直链（dl.wenjin.jp，同一台服务器、不经过 Cloudflare），再主站；network.conf 的 VD_BASE / VD_DIRECT / VD_PROXY / VD_IP / VD_DNS 同 auto-update.sh。
# 断点续传（curl -C -）；整个 tar.gz 和里面的 APK 都对钉死的 sha256，对不上就删掉换下一个来源。
webview_status() { [ -d $X/ctl ] || return 0; { printf '%s\n' "$@"; echo "time=$(date +%s)"; } > $X/ctl/webview.status.tmp 2>/dev/null && mv -f $X/ctl/webview.status.tmp $X/ctl/webview.status; }
webview_fsize() { if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo 0; fi; }
webview_dl_busy() { [ -n "$(cat $W/webview.dl.pid 2>/dev/null)" ] && kill -0 "$(cat $W/webview.dl.pid)" 2>/dev/null; }
webview_urls() { ( # network.conf（VD_BASE / VD_DIRECT）：宿主的主进程不读它，auto-update.sh 也是自己读
    BASE=; [ -f "$VD/network.conf" ] && . "$VD/network.conf"
    # 主站：VD_BASE（安装器用的名字）或 BASE（auto-update.sh 用的名字），都没有就是正式站
    wu_b=${VD_BASE:-${BASE:-https://veradroid.wenjin.jp}}; wu_b=${wu_b%/}
    case "$wu_b" in https://veradroid.wenjin.jp) wu_d=${VD_DIRECT-https://dl.wenjin.jp} ;; *) wu_d=${VD_DIRECT-} ;; esac
    wu_q="bundles/$WEBVIEW_ALT_FILE?v=$(echo $WEBVIEW_ALT_SHA | cut -c1-8)"
    [ -n "$wu_d" ] && echo "${wu_d%/}/$wu_q"
    echo "$wu_b/$wu_q" ); }
webview_curl_opts() {
    [ -f "$VD/network.conf" ] && ( . "$VD/network.conf"; o=; [ -n "${VD_PROXY:-}" ] && o="$o -x $VD_PROXY"
        [ -n "${VD_IP:-}" ] && o="$o --resolve veradroid.wenjin.jp:443:$VD_IP"; [ -n "${VD_DNS:-}" ] && o="$o --dns-servers $VD_DNS"; echo "$o" )
}
webview_dl() {   # 后台跑（settings_apply / 命令）。已经在下、已经下好、512 MB 机型：什么都不做
    lowmem_ram && { webview_status state=error msg=小内存机型不提供新网页内核; return 0; }
    webview_ok && { webview_status state=ready total=$WEBVIEW_ALT_SIZE got=$WEBVIEW_ALT_SIZE; return 0; }
    webview_dl_busy && return 0
    ( webview_dl_run ) </dev/null >>$LOG 2>&1 &
    echo $! > $W/webview.dl.pid
    return 0
}
webview_dl_run() {
    trap 'rm -f $W/webview.dl.pid' EXIT
    t "webview: download $WEBVIEW_ALT_FILE ($WEBVIEW_ALT_SIZE bytes)"
    mkdir -p $WVD.dl || { webview_status state=error msg=建不了下载目录; return 0; }
    wd_f=$WVD.dl/$WEBVIEW_ALT_FILE
    # 空间：tar.gz + 解开的 APK，再留 50 MB
    wd_have=$(df -k $VD 2>/dev/null | awk 'NR==2 {print $4}'); wd_got=$(webview_fsize $wd_f)
    if [ -n "$wd_have" ] && [ $(( wd_have * 1024 )) -lt $(( WEBVIEW_ALT_SIZE - wd_got + WEBVIEW_ALT_APK_SIZE + 52428800 )) ]; then
        webview_status state=error msg="空间不够（要约 $(( (WEBVIEW_ALT_SIZE + WEBVIEW_ALT_APK_SIZE) / 1048576 + 50 )) MB）"; return 0
    fi
    wd_ok=; wd_err=
    for wd_u in $(webview_urls); do
        wd_src=${wd_u#*://}; wd_src=${wd_src%%/*}
        webview_status state=downloading got=$wd_got total=$WEBVIEW_ALT_SIZE src=$wd_src
        # shellcheck disable=SC2046
        curl $(webview_curl_opts) -fsSL -C - --connect-timeout 10 --speed-limit 2048 --speed-time 60 --max-time 3600 -o $wd_f "$wd_u" 2>>$WVD.dl/curl.err &
        wd_c=$!
        while kill -0 $wd_c 2>/dev/null; do
            sleep 2; wd_got=$(webview_fsize $wd_f)
            webview_status state=downloading got=${wd_got:-0} total=$WEBVIEW_ALT_SIZE src=$wd_src
            [ -f $W/webview.dl.cancel ] && { kill $wd_c 2>/dev/null; rm -rf $WVD.dl $W/webview.dl.cancel; webview_status state=idle; t "webview: download cancelled"; return 0; }
        done
        wait $wd_c; wd_rc=$?
        wd_got=$(webview_fsize $wd_f)
        if [ "${wd_got:-0}" = $WEBVIEW_ALT_SIZE ]; then
            webview_status state=verifying got=$wd_got total=$WEBVIEW_ALT_SIZE src=$wd_src
            if [ "$(sha256sum $wd_f | cut -c1-64)" = $WEBVIEW_ALT_SHA ]; then wd_ok=$wd_src; break; fi
            t "webview: $wd_src: sha256 mismatch, removed"; rm -f $wd_f; wd_got=0; wd_err=校验不对
        else
            t "webview: $wd_src: curl rc=$wd_rc, $wd_got of $WEBVIEW_ALT_SIZE bytes"
            # 比应有的大 / 服务器不支持续传：从头来
            [ "${wd_got:-0}" -gt $WEBVIEW_ALT_SIZE ] 2>/dev/null && { rm -f $wd_f; wd_got=0; }
            wd_err="下载失败（$wd_src）"
        fi
    done
    [ -n "$wd_ok" ] || { webview_status state=error got=${wd_got:-0} total=$WEBVIEW_ALT_SIZE msg="${wd_err:-下载失败}，可以再试一次（会接着下）"; return 0; }
    rm -rf $WVD.new && mkdir -p $WVD.new && tar xzf $wd_f -C $WVD.new 2>>$LOG &&
        [ "$(sha256sum $WVD.new/app/webview.apk 2>/dev/null | cut -c1-64)" = $WEBVIEW_ALT_APK_SHA ] &&
        [ "$(sha256sum $WVD.new/overlay/webview-alt-overlay.apk 2>/dev/null | cut -c1-64)" = $WEBVIEW_ALT_OV_SHA ] &&
        echo "$WEBVIEW_ALT_APK_SHA $WEBVIEW_ALT_APK_SIZE" > $WVD.new/ok &&
        chmod 755 $WVD.new $WVD.new/app $WVD.new/overlay && chmod 644 $WVD.new/app/webview.apk $WVD.new/overlay/webview-alt-overlay.apk || {
            rm -rf $WVD.new $WVD.dl; webview_status state=error msg=解包或校验失败; t "webview: unpack / apk sha256 failed"; return 0; }
    rm -rf $WVD && mv $WVD.new $WVD && rm -rf $WVD.dl
    t "webview: WebView $WEBVIEW_ALT_VER ready (from $wd_ok), used from the next Android start"
    webview_status state=ready got=$WEBVIEW_ALT_SIZE total=$WEBVIEW_ALT_SIZE src=$wd_ok
    type settings_publish >/dev/null 2>&1 && settings_publish
    return 0
}
webview_cmd() {   # webview get | cancel
    case "$1" in
        get) webview_dl ;;
        cancel) webview_dl_busy && touch $W/webview.dl.cancel ;;
    esac
}
container_start() {
    # Android 本身按原来的 0 起：重启可能是从命令监听 / 看门狗里发起的，它们已经是 -800（host_oom_protect），
    # 不能让 Android 的原生进程（installd 起的 dex2oat 等）也继承成“不能杀”
    echo 0 > /proc/self/oom_score_adj 2>/dev/null
    webview_prepare       # 网页内核（WEBVIEW=alt，run_in_ns 绑进去）
    alarmfix_prepare      # AlarmManager 的 timerfd（run_in_ns 绑进去）
    sysfont_prepare       # 系统字体：生成 $W/sysfont（run_in_ns 绑进去）
    fstrim_defuse
    docs_snapshot         # 开机完成后 after_boot 拿它核对 Kindle 书库（docs_verify）
    if [ -f $SYS/data/system/uiderrors.txt ]; then wc -l < $SYS/data/system/uiderrors.txt | tr -d ' ' > $W/uiderr.lines; else echo 0 > $W/uiderr.lines; fi
    if hostns; then
        # chroot 模式同样要先进 freezer 组再 fork：以前这里只 freezer_setup，Android 的进程一个都不在组里，屏保 / 返回 Kindle 时
        # “冻结”的是一个空组（日志照样写 android frozen），Android 在屏保期间一直在跑（pw5-516211 模拟器：vd/cgroup.procs 为空，
        # system_server 的 freezer 组是 /）。没有 freezer 的内核 freezer_enter 什么都不做（信号模式按 chroot_pids 发 SIGSTOP）。
        freezer_setup && freezer_enter
        setsid sh $VD/boot/veradroid.sh --in-chroot "$KFB_TOUCH" "$KFB_KEYS_VIRT" "$KFB_KEYS" </dev/null >>$LOG 2>&1 &
        P=$!                                      # exec 链（sh -> chroot -> /init）保持同一个 PID
        [ -d $FZ ] && freezer_leave
        echo $P > $W/pid
        echo chroot > $W/mode
        t "container started (host namespaces, chroot): init=$P"
        hostns_selflink </dev/null >>$LOG 2>&1 &
        return 0
    fi
    # 本进程先进 freezer 组再 fork，容器的进程都在组里；fork 完回到根组
    freezer_setup && freezer_enter
    unshare -m -u -i -n -p -f -- sh $VD/boot/veradroid.sh --in-ns "$KFB_TOUCH" "$KFB_KEYS_VIRT" "$KFB_KEYS" </dev/null >>$LOG 2>&1 &
    UPID=$!
    [ -d $FZ ] && freezer_leave
    P=
    # 每 50 毫秒看一次（以前 200 毫秒），最多仍是 2 秒
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do
        read -r P _ < /proc/$UPID/task/$UPID/children 2>/dev/null
        [ -n "$P" ] && break
        usleep 50000
    done
    [ -n "$P" ] || { t "container did not start"; return 1; }
    echo $P > $W/pid
    echo $UPID > $W/unshare.pid
    t "container started: unshare=$UPID init=$P"
}


# 确保容器根下的 /dev/binder 是可打开的字符设备。init 偶发把 tmpfs 盖在节点上，
# 或 bind 失败时只剩普通文件 → 早期服务 SIGABRT（Binder driver could not be opened）。

binder_repair() {
    # Prefer repairing inside the live container mount namespace. Doing
    # mount --bind on the host path $R/dev/binder leaves a nested mount under
    # $SYS that stop/storage_umount must clear, or Kindle userstore unmount fails.
    p=$(cat $W/pid 2>/dev/null)
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null && [ -e /proc/$p/ns/mnt ]; then
        nsenter -t "$p" -m -- /system/bin/sh -c '
            target=/dev/binder
            if [ -c "$target" ]; then chmod 666 "$target" 2>/dev/null; exit 0; fi
            if [ -d "$target" ]; then umount "$target" 2>/dev/null; rmdir "$target" 2>/dev/null; fi
            # Inside the container ns, /dev/vndbinder is the host bind (or missing).
            if [ -e /dev/vndbinder ]; then
                chmod 666 /dev/vndbinder 2>/dev/null
                rm -f "$target" 2>/dev/null
                touch "$target"
                mount --bind /dev/vndbinder "$target" || exit 1
                chmod 666 "$target" 2>/dev/null
                [ -c "$target" ] || exit 1
                exit 0
            fi
            exit 1
        ' && return 0
        # fall through to host-path repair if nsenter path failed
    fi
    target=$R/dev/binder
    mkdir -p $R/dev
    if [ -d "$target" ]; then
        umount "$target" 2>/dev/null || true
        rmdir "$target" 2>/dev/null || true
    fi
    if [ -c "$target" ] && [ -e /dev/vndbinder ]; then
        chmod 666 "$target" 2>/dev/null || true
        return 0
    fi
    rm -f "$target" 2>/dev/null || true
    if [ -e /dev/vndbinder ]; then
        chmod 666 /dev/vndbinder
        touch "$target"
        mount --bind /dev/vndbinder "$target" || return 1
        chmod 666 "$target" 2>/dev/null || true
        [ -c "$target" ] || return 1
        return 0
    fi
    return 1
}

# 在新命名空间里执行（由 veradroid.sh --in-ns 调用）
run_in_ns() {
    TOUCH=$1 KEYS=$2 PTKEYS=$3
    # HOSTNS=1：由 veradroid.sh --in-chroot 调用（没有命名空间，挂载直接做在宿主上，stop.sh 负责卸掉）
    [ -z "$HOSTNS" ] && mount --make-rprivate / 2>/dev/null
    # Kindle 书库的挂载点在根下（见 common.sh 的 KLIB_MP）：根马上要变只读，先建好
    [ -f $R/.demo ] || mkdir -p $R$KLIB_MP $R$KLIB_FSP 2>/dev/null
    mount --bind $R $R || exit 1                       # pivot_root 要求新根是挂载点
    DEMO=; [ -f $R/.demo ] && DEMO=1
    [ -z "$DEMO" ] && mount -o remount,ro,bind $R
    mount -t tmpfs -o nosuid,mode=755 tmpfs $R/dev || exit 1
    : > $R/dev/.coldboot_done
    for n in null zero full tty random urandom kmsg fuse; do
        [ -e /dev/$n ] && touch $R/dev/$n && mount --bind /dev/$n $R/dev/$n
    done
    # Kindle 自己用 /dev/binder，系统借没人用的 /dev/vndbinder。
    # 没有可用 binder 时 Android 会在开机前几秒连环 SIGABRT（Abort: Binder driver could not be opened）
    # ——用户日志 PW5/KT5 低内存机；必须保证容器里真有可打开的 /dev/binder，否则直接失败。
    binder_ok=
    if [ -e /dev/vndbinder ]; then
        chmod 666 /dev/vndbinder
        touch $R/dev/binder
        if mount --bind /dev/vndbinder $R/dev/binder; then binder_ok=1; fi
    fi
    # binderfs 回退（部分 5.x 内核）：单独实例，不跟 Kindle 的 /dev/binder 抢。
    if [ -z "$binder_ok" ] && grep -qw binder /proc/filesystems 2>/dev/null; then
        mkdir -p $R/dev/binderfs
        if mount -t binder binder $R/dev/binderfs 2>/dev/null; then
            # binderfs 默认节点名 binder、hwbinder、vndbinder
            if [ -e $R/dev/binderfs/binder ]; then
                ln -sf binderfs/binder $R/dev/binder
                binder_ok=1
            fi
        fi
    fi
    if [ -z "$binder_ok" ] || [ ! -e $R/dev/binder ]; then
        echo "veradroid: no binder device for Android (need /dev/vndbinder or binderfs)" >&2
        exit 1
    fi
    chmod 666 $R/dev/binder 2>/dev/null || true
    # Final: must be a char device, not a plain file/dir covered by tmpfs.
    if [ ! -c $R/dev/binder ]; then
        binder_repair || { echo "veradroid: binder node is not a char device" >&2; exit 1; }
    fi
    [ -z "$HOSTNS" ] && [ -e /dev/net/tun ] && { touch $R/dev/tun; mount --bind /dev/net/tun $R/dev/tun; }
    [ -n "$HOSTNS" ] && touch $R/dev/vdchroot      # 系统里的 android_reboot 看到它就不碰 Kindle（见上面 hostns 的说明）
    mkdir -p $R/dev/socket $R/dev/input $R/dev/kfb $R/dev/pts $R/dev/__properties__
    # 内核 < 3.17 没有 memfd：ashmem 退回到这块 tmpfs 上的已删除文件（libcutils/ashmem-dev.c、vdshim.c）
    mkdir -p $R/dev/vdshm && chmod 1777 $R/dev/vdshm
    for d in "$TOUCH" "$KEYS" "$PTKEYS"; do [ -c "$d" ] && chmod 666 "$d" 2>/dev/null; done
    [ -n "$TOUCH" ] && { touch $R/dev/input/event0; mount --bind "$TOUCH" $R/dev/input/event0; }
    [ -n "$KEYS" ]  && { touch $R/dev/input/event1; mount --bind "$KEYS" $R/dev/input/event1; }
    [ -n "$PTKEYS" ] && { touch $R/dev/input/event2; mount --bind "$PTKEYS" $R/dev/input/event2; }   # Kindle 的翻页键 kindle-input-pt
    [ -n "$KFB_BTKEYS" ] && [ -c "$KFB_BTKEYS" ] && { touch $R/dev/input/event3; mount --bind "$KFB_BTKEYS" $R/dev/input/event3; }   # 蓝牙翻页器桥 veradroid-btkeys（bt.sh）
    mount --bind $X $R/dev/kfb
    # 墨水屏：系统里的显示后端直接写 Kindle 的帧缓冲、发刷新
    [ -e /dev/fb0 ] && { touch $R/dev/epd $R/dev/fb0; mount --bind /dev/fb0 $R/dev/epd; mount --bind /dev/fb0 $R/dev/fb0; }   # FBInk 认 /dev/fb0，系统里的显示后端用 /dev/epd
    mount -t devpts -o mode=620,ptmxmode=666,newinstance devpts $R/dev/pts && ln -s pts/ptmx $R/dev/ptmx

    mount -t proc proc $R/proc || exit 1
    [ -f $W/cmdline ] && mount --bind $W/cmdline $R/proc/cmdline
    # /proc/sys 是整个内核的，不是命名空间的：Android 的 init 会往里写一大批参数（调度、内存、pid_max……），不能让它改 Kindle。
    # 整个只读；net 是 net 命名空间自己的，放开；init 开机必须写 mmap 随机化位数（写不了它就停），只放开这一个。
    if [ -z "$DEMO" ]; then
        [ -z "$HOSTNS" ] && mount --bind $R/proc/sys/net $R/proc/sys/net    # chroot 模式下 net 就是 Kindle 的：一起只读
        mount --rbind $R/proc/sys $R/proc/sys && mount -o remount,ro,bind $R/proc/sys
        f=vm/mmap_rnd_compat_bits; [ -e /proc/sys/$f ] || f=vm/mmap_rnd_bits
        # 4.5 以前的内核（PW4 / Oasis 3 / 3.0 机型）没有这个设置；系统的 init 已打补丁，文件不存在时跳过
        if [ -e /proc/sys/$f ]; then
            mount --bind /proc/sys/$f $R/proc/sys/$f && mount -o remount,rw,bind $R/proc/sys/$f || { echo "cannot open $f"; exit 1; }
        fi
        for f in sysrq-trigger cpu/alignment; do
            [ -e "$R/proc/$f" ] && mount --bind "$R/proc/$f" "$R/proc/$f" && mount -o remount,ro,bind "$R/proc/$f"
        done
    fi
    mount -t sysfs -o ro sysfs $R/sys || exit 1
    # 内核编进了 SELinux 但没加载策略：系统绝不能碰宿主的 SELinux，盖一层只读空目录
    for d in sys/fs/selinux sys/fs/bpf sys/fs/pstore sys/kernel/tracing sys/kernel/debug; do
        [ -d $R/$d ] && mount -t tmpfs -o ro,size=4k tmpfs $R/$d
    done
    # BatteryStatsService 的 KernelWakelockReader 每隔几分钟读 /proc/wakelocks 或 /d/wakeup_sources（/d -> /sys/kernel/debug）；
    # 两者都不存在时 system_server 持续触发 Slog.wtf，向 /data/system/dropbox 写入大量 wtf 堆栈文件（用户诊断 实证）。
    # 在只读空 tmpfs 中放一个 0 字节只读 wakeup_sources 即可彻底静音且保持宿主 debugfs 隔离。
    if [ ! -e /proc/wakelocks ] && [ -d $R/sys/kernel/debug ]; then
        mount -o remount,rw $R/sys/kernel/debug 2>/dev/null && \
            : > $R/sys/kernel/debug/wakeup_sources && chmod 444 $R/sys/kernel/debug/wakeup_sources && \
            mount -o remount,ro $R/sys/kernel/debug 2>/dev/null || true
    fi
    # 测延迟用：/dev/shm/vd-tracefs 存在时，把 tracefs 给系统看（atrace / Trace 标记要在 zygote 起来之前就能打开）。
    # 只读空目录换成可写的小 tmpfs + tracefs；宿主的跟踪设置是共用的，测完删掉这个文件、重启安卓即恢复
    if [ -e /dev/shm/vd-tracefs ] && [ -d $R/sys/kernel/debug ]; then
        mount -t tmpfs -o size=4k tmpfs $R/sys/kernel/debug && mkdir -p $R/sys/kernel/debug/tracing && mount -t tracefs nodev $R/sys/kernel/debug/tracing
    fi

    if [ -n "$DEMO" ]; then
        # 演示根：借宿主的 busybox、库、FBInk 和字体（只读）
        for d in bin lib usr/lib; do mkdir -p $R/$d; mount --bind /$d $R/$d; mount -o remount,ro,bind $R/$d; done
        mkdir -p $R/opt $R/fonts $R/tmp
        touch $R/opt/fbink; mount --bind "$FBINK" $R/opt/fbink
        mount --bind /usr/java/lib/fonts $R/fonts
    else
        mkdir -p $SYS/data && mount --bind $SYS/data $R/data && mount -o remount,bind,nosuid,nodev,noatime $R/data
        mkdir -p $R/data/cache                       # 根里的 /cache 是指向 /data/cache 的符号链接
        # 存储：Android 里的 /sdcard 和 /storage/emulated/0 都指向 data/media/0（没有 sdcard 守护进程，直接绑）；
        # Kindle 自己的书库（documents）也绑进来，传书网页上传到“Kindle 书库”就直接出现在 Kindle 里。
        mkdir -p $R/data/media/0/Books $R/data/local/tmp
        chmod 777 $R/data/media $R/data/media/0 $R/data/media/0/Books $R/data/local/tmp 2>/dev/null
        upload_dir_fix 2>/dev/null     # 安装包暂存目录：Android 开机前就准备好（common.sh）
        # Kindle 的 documents 属于它自己的框架用户、权限 755：Android 应用（传书网页）写不进去，“传到 Kindle 书库”报 Permission denied。
        # 给其他用户加上写权限（FUSE 那一侧的 /mnt/us/documents 本来就是 777）。
        [ -d /mnt/base-us/documents ] && chmod o+rwx /mnt/base-us/documents 2>/dev/null
        # Kindle 书库：documents 绑在 /data/media/0/KindleLibrary（真正的挂载点：应用经 /sdcard 的 FUSE 才看得到，见 common.sh）。
        # 开机期间只读（PackageManager 准备用户存储时万一要删 /data/media/0，删除只会得到 EROFS），开机完成后 after_boot 的
        # klib_rw 把可写的那份（$R$KLIB_MP，/data 外面）再叠绑上去。不能 remount：应用的挂载命名空间里留着只读那份的拷贝，
        # 改标志传不过去；Android 的 toybox mount 也做不了 remount。只读做不到就不绑：宁可 Android 里暂时看不到书库。
        if [ -d /mnt/base-us/documents ] && [ -d $R$KLIB_MP ] && klib_dir $R/data; then
            # 加密用户分区时可写层要用的解密视图（见 common.sh 的 KLIB_FSP）；用不用由 klib_rw 开机后试读决定
            fsp=$(klib_fsp_src) && [ -d $R$KLIB_FSP ] && mount --bind "$fsp" $R$KLIB_FSP 2>/dev/null
            if mount --bind /mnt/base-us/documents $R$KLIB_MP; then
                if mount --bind /mnt/base-us/documents $R$KLIB && ! mount -o remount,ro,bind $R$KLIB; then
                    umount $R$KLIB; echo "veradroid: cannot bind the Kindle library read-only, NOT bound into Android" >&2
                fi
            fi
        else
            echo "veradroid: KindleLibrary mount point not in place, Kindle library NOT bound into Android" >&2
        fi
        # Kindle 自带字体（宋体/黑体/Bookerly 等）只读绑进 Android，阅读器可以直接选，不复制、不打包
        mkdir -p $R/data/media/0/KindleFonts $R/data/media/0/Fonts
        [ -d /usr/java/lib/fonts ] && mount --bind /usr/java/lib/fonts $R/data/media/0/KindleFonts
        [ -d $VD/fonts ] && mount --bind $VD/fonts $R/data/media/0/Fonts
        chmod 777 $R/data/media/0/Fonts 2>/dev/null
        sysfont_mount        # 系统字体（SYSTEM_FONT）：上面 sysfont_prepare 准备好的才绑
        sysbin_wrap_mount    # dex2oat / cmd 的包装（boot/*-wrap.sh）
        alarmfix_mount       # AlarmManager 的 timerfd（alarmfix_prepare 改好的副本）
        nocap_mount          # healthd 不带 CAP_BLOCK_SUSPEND / CAP_WAKE_ALARM（冻结时不挂唤醒源，待机能休眠）
        webview_mount        # 新网页内核（webview_prepare 选好的）
        # /sdcard -> /storage/self/primary -> /storage/emulated/0. vold/FUSE
        # replaces /storage/emulated and filters by sdcard_rw group; KOReader
        # then gets EACCES on books. Drop that FUSE view and bind the ext4
        # media directory directly, so normal app permissions apply.
        umount -l $R/storage/emulated 2>/dev/null
        mkdir -p $R/storage/emulated/0
        mount --bind $R/data/media/0 $R/storage/emulated/0
    fi
    # 新根是只读的，put_old 必须是根里已有的目录：Android 的根里有个空的 /mnt，旧根临时挂在那儿，由 pivot_init（静态）卸掉。
    # Android 的 init 第一阶段会加载 SELinux 策略（对宿主内核的操作！）并重新挂 /dev。必须直接走第二阶段：
    # 它不加载策略，用我们准备好的 /dev。演示系统的 init 是普通脚本。
    if [ -n "$DEMO" ]; then
        mkdir -p $R/old
        cd $R && pivot_root . old && cd / && umount -l /old && rmdir /old 2>/dev/null
        exec /init
    fi
    if [ -n "$HOSTNS" ]; then
        cd $R && exec chroot $R /init --second-stage
    fi
    exec $VD/bin/pivot_init $R mnt /init --second-stage
}
