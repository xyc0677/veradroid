# SPDX-License-Identifier: GPL-3.0-or-later
# 容器：unshare 出 mount / UTS / IPC / net / PID 命名空间，在新命名空间里把 $R 变成根（pivot_root）并执行 /init。
# net 命名空间里默认只有 lo（网络由 slirp4netns 另行接入，尚未实现）。

# 触摸屏（fts_ts）、翻页键（kindle-input-pt）的事件节点；名字认不出来就按多点触摸的 ABS 位图找
input_find() {   # $1 = 设备名（正则）
    awk -v pat="$1" '/^N: Name=/ { m = ($0 ~ pat) }
        m && /^H: Handlers=/ { for (i = 1; i <= NF; i++) if (match($i, /event[0-9]+$/)) { print "/dev/input/" substr($i, RSTART); exit } }' /proc/bus/input/devices
}
touch_setup() {
    KFB_KEYS=$(input_find '"kindle-input-pt"')
    # 老机型的实体翻页键（机型文件的 PAGE_KEYS：Voyage 的 PagePress = fsr_keypad，Oasis 2 / 3 = gpio-keys），键位表见 memory.sh
    [ -z "$KFB_KEYS" ] && [ -n "$PAGE_KEYS" ] && KFB_KEYS=$(input_find "\"$PAGE_KEYS\"")
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
        # 竖屏那一项（3.0 内核的 i.MX EPDC 会同时列出 1072x1448 和 1448x1072）
        m=$(sed -n 's/.*U:\([0-9][0-9]*\)x\([0-9][0-9]*\)p.*/\1 \2/p' /sys/class/graphics/fb0/modes 2>/dev/null | awk '$2 >= $1 { print; exit }')
        mw=; mh=; [ -n "$m" ] && { set -- $m; mw=$1; mh=$2; }
        viewWidth=${vw:-$mw}
        viewHeight=${mh:-$vh}
        # 3.0 内核（PW2 / PW3，i.MX EPDC）：virtual_size 的宽是 32 对齐的行宽（PW3 1088），可见宽度是 modes 里的 1072。
        # 系统按共享缓冲的宽度排版，显示桥（FBInk）只画可见的 1072 列：用行宽的话右边 16 列画不出来，触摸换算也会差 1.5%。
        case "$(uname -r)" in 3.*) [ -n "$mw" ] && viewWidth=$mw ;; esac
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
    chown 0:0 $X $X/ctl 2>/dev/null || true
    chmod 0755 $X $X/ctl
    chmod 0644 $X/info $X/model $X/hwc.off $X/hz $X/hwc.dim 2>/dev/null || true
    kfb_home_chown
}
# 仅限 VeraHome（com.veradroid.home）写入的控制项：独立 0700 目录 $X/home（供 settings.new）与属主文件 $X/fg
kfb_home_chown() {
    kh_u=$(awk '$1 == "com.veradroid.home" { print $2; exit }' $SYS/data/system/packages.list 2>/dev/null)
    case "$kh_u" in ''|*[!0-9]*) return 0 ;; esac
    mkdir -p $X/home 2>/dev/null || true
    chown "$kh_u:0" $X/home 2>/dev/null || true
    chmod 0700 $X/home 2>/dev/null || true
    [ -f $X/fg ] || : > $X/fg
    chown "$kh_u:0" $X/fg 2>/dev/null || true
    chmod 0644 $X/fg 2>/dev/null || true
    return 0
}
# ashmem 垫片（android/device/veradroid/kindle/shim/vdshim.c，init 给每个进程 LD_PRELOAD）也随宿主程序发（bin/libvdshim.so，
# 和 host/pw6、host/k49 同一个文件），开机前换掉镜像里的那份：镜像里的旧版对 ASHMEM_GET_PROT_MASK 一律回答 rwx，应用自带的
# Chromium 内核（番茄小说的 libwebviewbytedance.so）把共享内存设成只读后检查不过，每次启动 SIGTRAP，应用随后进自己的“安全模式”。
# 3.0 内核没有 memfd：区域是 /dev/vdshm 上已删除的文件（0600），掩码同样记在文件权限里。两种模式（命名空间 / chroot）都在
# display_prepare 里、容器起来之前装。先写 .new 再 mv：镜像里的文件和回退用的旧镜像（rootfs.bak）可能是硬链接，不能原地改。
vdshim_prepare() {
    vs_dst=$R/system/lib/libvdshim.so
    [ -f "$VD/bin/libvdshim.so" ] && [ -f "$vs_dst" ] || return 0
    cmp -s "$VD/bin/libvdshim.so" "$vs_dst" && return 0
    cp "$VD/bin/libvdshim.so" "$vs_dst.new" && chmod 644 "$vs_dst.new" && mv -f "$vs_dst.new" "$vs_dst" &&
        t "ashmem: libvdshim.so installed" || { rm -f "$vs_dst.new"; t "ashmem: libvdshim.so not installed, image copy kept"; }
    return 0
}
# 系统合成：hwcomposer.kindle.so（和 host/pw6、host/k49 同一个文件，android/hwcomposer）随宿主程序发（bin/），开机前装进系统的
# /system/lib/hw；KFB_HWC=0 时写 $X/hwc.off，模块拒绝加载，SurfaceFlinger 回到原来的软件 GLES（libagl）。
# 为什么 legacy 也要：libagl 把每个图层画成两个三角形，非全屏的窗口（对话框、弹出菜单、输入法）两个三角形的纹理坐标差 1–2 像素，
# 画面沿一条斜线错开一格（PW4 模拟器：首页右上角菜单，GLES 帧和 1:1 拷贝相比 x 946–987、y 861–1088 的 1021 个像素整体右移 1 像素；
# 输入法窗口同样）。这个模块把不缩放、不旋转的图层按行原样拷贝，错位没有了，也省掉了 libagl 每帧的合成时间。
hwc_prepare() {
    if [ -f "$VD/bin/hwcomposer.kindle.so" ] && [ -d "$R/system/lib/hw" ]; then
        if ! cmp -s "$VD/bin/hwcomposer.kindle.so" "$R/system/lib/hw/hwcomposer.kindle.so"; then
            cp "$VD/bin/hwcomposer.kindle.so" "$R/system/lib/hw/hwcomposer.kindle.so.new" && chmod 644 "$R/system/lib/hw/hwcomposer.kindle.so.new" \
                && mv -f "$R/system/lib/hw/hwcomposer.kindle.so.new" "$R/system/lib/hw/hwcomposer.kindle.so" && t "display: hwcomposer.kindle.so installed"
        fi
    fi
    if [ "${KFB_HWC:-1}" = 1 ] && [ -f "$R/system/lib/hw/hwcomposer.kindle.so" ]; then rm -f $X/hwc.off; else touch $X/hwc.off; fi
    case "$KFB_HZ" in 60|20|15) echo $KFB_HZ > $X/hz ;; *) echo 30 > $X/hz ;; esac
    case "${KFB_DIM:-20}" in off) echo -1 > $X/hwc.dim ;; ''|*[!0-9]*) echo 51 > $X/hwc.dim ;;
        *) d=${KFB_DIM}; [ "$d" -gt 100 ] && d=100; echo $((d * 255 / 100)) > $X/hwc.dim ;; esac
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
    : > $W/epd.log   # 追加方式打开：watchdog 的 log_cap 截短它时，显示桥接着写在新的末尾（不会留下空洞）
    case "${KFB_SCROLLWFM:-0}" in 1|a2) _sa2=1 ;; *) _sa2=0 ;; esac
    $VD/bin/veradroid-epd run $X/fb $X/epd.fifo $X/ctl --w ${viewWidth:-1272} --h ${viewHeight:-1696} --contrast ${KFB_CONTRAST:-1.3} --dither ${KFB_DITHER:-3} --flash ${KFB_FLASH:-0} --invert ${KFB_INVERT:-0} --lightf ${KFB_LIGHTF:-0} --darken ${KFB_DARKEN:-0} --mode ${KFB_MODE:-0} --settle ${KFB_SETTLE:-120} --hold ${KFB_HOLD:-70} --anim-mode ${KFB_ANIM_MODE:-1} --anim-dir ${KFB_ANIM_DIR:-2} --anim-steps ${KFB_ANIM_STEPS:-12} --anim-min ${KFB_ANIM_MIN:-60} --scroll-delay ${KFB_SCROLLDELAY:-500} --scroll-style ${KFB_SCROLLSTYLE:-1} --scroll-a2 $_sa2 $(rot_args) >>$W/epd.log 2>&1 &
    echo $! > $W/epd.pid
    [ -n "${KFB_EPD_NICE:--8}" ] && renice -n "${KFB_EPD_NICE:--8}" "$!" >/dev/null 2>&1 || true
    rot_deg > $W/rotate
    # 以后能不能当场转 180：0 度启动时没传 --vw，可见宽 != 行宽（PW5 无 FBInk）的机器转了会偏 12 像素，只能重启
    if [ "$(rot_deg)" != 0 ] || [ -z "$viewVisWidth" ] || [ "$viewVisWidth" = "${viewWidth:-1272}" ]; then touch $W/rotate.live; else rm -f $W/rotate.live; fi
    usleep 500000
    t "display: bridge pid $(cat $W/epd.pid)"
}
display_stop() { alive $W/epd.pid && kill "$(cat $W/epd.pid)" 2>/dev/null; rm -f $W/epd.pid $W/rotate $W/rotate.live; }

# 宿主命名空间（chroot）模式：3.0 内核（PW2 / PW3 / Oasis 1/2 / Voyage / 入门款 2014、2016）没有任何命名空间。
# Android 直接 chroot 进 $R，和 Kindle 共用进程、挂载和网络。保护 Kindle 的手段：
#  - /dev/vdchroot 标记：系统里的 android_reboot 看到它就不调用 reboot() / remount_ro（small-patches.py）；
#  - /proc/sys 整个只读（包括 net：这里的 net 就是 Kindle 的）；没有 slirp，Android 暂时没有网络；
#  - 容器的进程按“根目录是 $R”识别（冻结 / 停止都用它），挂载在 stop.sh 里由深到浅逐个卸掉。
# 4.1.15（PW4 / 入门款 2019 / Oasis 3）有命名空间，但没有 tun：slirp4netns 给不了容器网络，单独的 net 命名空间就是断网。
# 这时也走这条路（挂载命名空间 + hostnet，和 3.0 同一套，模拟器里 PW3 / PW4 都验证过），不开 PID 命名空间。
# Oasis 2（4.1.15，固件 5.16）连 PID 命名空间都没有。kmod_load 在这之前，tun 模块要是加载了 /dev/net/tun 就在。
hostns() { [ ! -e /proc/self/ns/pid ] || [ ! -e /dev/net/tun ]; }
# legacy（3.0.35：PW2 / PW3 / Voyage / Oasis 1/2 …）：没有 PID / UTS / IPC / net 命名空间，但挂载命名空间（CLONE_NEWNS）有，
# 而且默认私有传播。所以 unshare -m 开私有挂载命名空间，在里面 pivot_root（和有命名空间的机型同一套），挂载不会漏到
# Kindle 上；直接 chroot 时系统里读到的文件路径带着宿主前缀，zygote 的描述符检查全部失败（PW3 模拟器实测）。
# pivot_root 之后宿主读容器进程的 /proc/PID/root 只显示 “/”，不能按路径比：改比根目录的 设备号:inode。
root_id() { stat -L -c %d:%i "$1/." 2>/dev/null; }
chroot_root() { ip=$(cat $W/pid 2>/dev/null); [ -n "$ip" ] && root_id /proc/$ip/root; }
chroot_pids() { # 容器的进程：根目录和容器 init 一样；$1 = 事先记下的容器根（init 已经被杀时用）
    # 只认 init 的根不够：init 先死了（崩溃、上一次 stop 杀了一半）又没记下根时，剩下的 zygote / 应用一个也认不出来，
    # 一直占着系统盘的 loop 设备，返回 Kindle 后 USB 导不出用户分区（k49 chroot 模式同样的问题，见 host/k49 的 chroot_pids）。
    # 所以再加一条：根目录所在的设备就是系统盘镜像的 loop 设备（宿主自己的进程根在 Kindle 的根分区上，不会误认）。
    r=${1:-$(chroot_root)}
    [ "$r" = "$(root_id /)" ] && r=
    cp_devs=" "
    for cp_l in $(img_loops); do
        cp_d=$(cat /sys/block/$cp_l/dev 2>/dev/null)
        [ -n "$cp_d" ] && cp_devs="$cp_devs$(( ${cp_d%%:*} * 256 + ${cp_d##*:} )) "
    done
    [ -n "$r" ] || [ "$cp_devs" != " " ] || return 0
    stat -L -c '%d %i %n' /proc/[0-9]*/root 2>/dev/null | awk -v devs="$cp_devs" -v r="$r" '
        { split($3, a, "/"); if (index(devs, " " $1 " ") > 0 || (r != "" && ($1 ":" $2) == r)) print a[3] }' | sort -un
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
alarmfix_put() { printf "$(awk -v s="$3" 'BEGIN{h="0123456789abcdef";s=tolower(s);for(i=1;i<=length(s);i+=2){v=(index(h,substr(s,i,1))-1)*16+index(h,substr(s,i+1,1))-1;printf "\\%03o",v}}')" | dd of="$1" bs=1 seek="$2" conv=notrunc 2>/dev/null; }
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
container_start() {
    # Android 本身按原来的 0 起：重启可能是从命令监听 / 看门狗里发起的，它们已经是 -800（host_oom_protect），
    # 不能让 Android 的原生进程（installd 起的 dex2oat 等）也继承成“不能杀”
    echo 0 > /proc/self/oom_score_adj 2>/dev/null
    alarmfix_prepare      # AlarmManager 的 timerfd（run_in_ns 绑进去）
    sysfont_prepare       # 系统字体：生成 $W/sysfont（run_in_ns 绑进去）
    fstrim_defuse
    docs_snapshot         # 开机完成后 after_boot 拿它核对 Kindle 书库（docs_verify）
    if [ -f $SYS/data/system/uiderrors.txt ]; then wc -l < $SYS/data/system/uiderrors.txt | tr -d ' ' > $W/uiderr.lines; else echo 0 > $W/uiderr.lines; fi
    if hostns; then
        # 先进 freezer 组再 fork（port of c1f3b033）：以前只 freezer_setup，内核有 cgroup freezer 时“冻结”的是一个空组、Android 在屏保里
        # 照跑。现在的老机型内核（3.0 没有 cgroup、4.1 没有 freezer）都是信号模式（按 chroot_pids 发 SIGSTOP），freezer_enter 不做事；
        # 以后哪个固件的内核带了 freezer，这里就不会再漏。
        freezer_setup && freezer_enter
        setsid unshare -m sh $VD/boot/veradroid.sh --in-chroot "$KFB_TOUCH" "$KFB_KEYS_VIRT" "$KFB_KEYS" </dev/null >>$LOG 2>&1 &
        P=$!                                      # exec 链（unshare -> sh -> pivot_init -> /init）保持同一个 PID
        [ -d $FZ ] && freezer_leave
        echo $P > $W/pid
        echo chroot > $W/mode
        t "container started (mount namespace only, no pid namespace): init=$P"
        return 0
    fi
    # 本进程先进 freezer 组再 fork，容器的进程都在组里；fork 完回到根组
    freezer_setup && freezer_enter
    unshare -m -u -i -n -p -f -- sh $VD/boot/veradroid.sh --in-ns "$KFB_TOUCH" "$KFB_KEYS_VIRT" "$KFB_KEYS" </dev/null >>$LOG 2>&1 &
    UPID=$!
    [ -d $FZ ] && freezer_leave
    P=
    for i in 1 2 3 4 5 6 7 8 9 10; do
        read -r P _ < /proc/$UPID/task/$UPID/children 2>/dev/null
        [ -n "$P" ] && break
        usleep 200000
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
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null && [ -d /proc/$p/ns/mnt ]; then
        nsenter -t "$p" -m -- /bin/sh -c '
            target=/dev/binder
            if [ -d "$target" ]; then umount "$target" 2>/dev/null; rmdir "$target" 2>/dev/null; fi
            if [ -c "$target" ] && [ -e /dev/vndbinder ]; then chmod 666 "$target" 2>/dev/null; exit 0; fi
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
    # legacy：HOSTNS 也在私有挂载命名空间里（unshare -m）。老固件的 mount 不认 --make-rprivate，3.0 内核默认就是私有传播，失败没关系。
    mount --make-rprivate / 2>/dev/null
    # Kindle 书库的挂载点在根下（见 common.sh 的 KLIB_MP）：根马上要变只读，先建好
    [ -f $R/.demo ] || mkdir -p $R$KLIB_MP 2>/dev/null
    mount --bind $R $R || exit 1                       # pivot_root 要求新根是挂载点
    DEMO=; [ -f $R/.demo ] && DEMO=1
    [ -z "$DEMO" ] && mount -o remount,ro,bind $R
    [ -d $R/acct ] && mount -t tmpfs -o rw,size=64k tmpfs $R/acct 2>/dev/null || true
    mount -t tmpfs -o nosuid,mode=755 tmpfs $R/dev || exit 1
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
    # udev（PW3 5.16 上有）在 uinput 设备出现后异步建节点、用 0640 root：可能在 input.sh 的 chmod 666 之后才建好，
    # Android 的 InputReader（system 用户）就打不开触摸屏，点哪里都没反应（pw3-5162 模拟器，0.5 实测）。绑进容器前再改一次。
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
    for d in sys/fs/selinux sys/fs/bpf sys/fs/pstore sys/kernel/tracing; do
        [ -d $R/$d ] && mount -t tmpfs -o ro,size=4k tmpfs $R/$d
    done
    # BatteryStatsService 的 KernelWakelockReader 每隔几分钟读 /proc/wakelocks 或 /d/wakeup_sources（/d -> /sys/kernel/debug）；
    # 文件不存在会触发 KernelWakelockReader Slog.wtf，0 字节空文件又会触发 BatteryStatsImpl "All kernel wakelocks had time of zero / were set stale"
    # Slog.wtf（用户诊断 实证：dropbox 积压百余份 wtf）。先 rw 挂载 tmpfs 写入表头+1 条静态占位记录再 remount ro（busybox mount 不支持 ro->remount,rw），双路彻底静音且保持宿主 debugfs 隔离。
    if [ -d $R/sys/kernel/debug ]; then
        if mount -t tmpfs -o rw,size=4k tmpfs $R/sys/kernel/debug; then
            if [ ! -e /proc/wakelocks ]; then
                printf 'name\tactive_count\tevent_count\twakeup_count\texpire_count\tactive_since\ttotal_time\tmax_time\tlast_change\tprevent_suspend_time\nveradroid\t1\t0\t0\t0\t0\t1\t0\t0\t0\n' > $R/sys/kernel/debug/wakeup_sources 2>/dev/null && \
                    chmod 444 $R/sys/kernel/debug/wakeup_sources 2>/dev/null || true
            fi
            mount -t tmpfs -o remount,ro,size=4k tmpfs $R/sys/kernel/debug 2>/dev/null || mount -o remount,ro $R/sys/kernel/debug 2>/dev/null || true
        fi
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
        # 绑在 /data 外面（$R$KLIB_MP），/data/media/0/KindleLibrary 是指过去的符号链接（common.sh 的 klib_link，2026-11-13 事故：
        # Android 的 installd 递归删 /data/media/0 时穿过了以前直接绑在那里的 documents）。链接弄不好就不绑：宁可 Android 里看不到书库。
        # legacy：这个 bind 在 unshare -m 的私有挂载命名空间里，容器的进程全死后随命名空间消失；stop.sh 再按 $R 下的挂载兜底卸一遍。
        # 只在模拟器上验证过的机型（$VD/tested = emulator-only）：默认不把 Kindle 书库绑进 Android——这些机型没有真机验证，
        # Android 里的任何意外都碰不到用户的书（设置里 KLIB_BIND=ro 可只读挂载、KLIB_BIND=1 可读写挂载）。KindleLibrary 链接照建，默认指向空目录。
        KLIB_SKIP=; [ "$(cat $VD/tested 2>/dev/null)" = emulator-only ] && [ "${KLIB_BIND:-0}" != 1 ] && [ "${KLIB_BIND:-0}" != ro ] && KLIB_SKIP=1
        [ -n "$KLIB_SKIP" ] && echo "veradroid: emulator-only model: Kindle library not bound into Android" >&2
        if [ -z "$KLIB_SKIP" ] && [ -d /mnt/base-us/documents ] && [ -d $R$KLIB_MP ]; then
            if klib_link $R/data; then
                mount --bind /mnt/base-us/documents $R$KLIB_MP
                if [ "${KLIB_BIND:-0}" = ro ]; then
                    mount -o remount,ro,bind $R$KLIB_MP 2>/dev/null || { umount $R$KLIB_MP 2>/dev/null; echo "veradroid: ro bind failed, Kindle library NOT bound" >&2; }
                fi
            else echo "veradroid: KindleLibrary link not in place, Kindle library NOT bound into Android" >&2; fi
        fi
        # Kindle 自带字体（宋体/黑体/Bookerly 等）只读绑进 Android，阅读器可以直接选，不复制、不打包
        mkdir -p $R/data/media/0/KindleFonts $R/data/media/0/Fonts
        [ -d /usr/java/lib/fonts ] && mount --bind /usr/java/lib/fonts $R/data/media/0/KindleFonts
        [ -d $VD/fonts ] && mount --bind $VD/fonts $R/data/media/0/Fonts
        chmod 777 $R/data/media/0/Fonts 2>/dev/null
        sysfont_mount        # 系统字体（SYSTEM_FONT）：上面 sysfont_prepare 准备好的才绑
        sysbin_wrap_mount    # dex2oat / cmd 的包装（boot/*-wrap.sh）
        alarmfix_mount       # AlarmManager 的 timerfd（alarmfix_prepare 改好的副本）
        nocap_mount          # healthd 不带 CAP_BLOCK_SUSPEND / CAP_WAKE_ALARM（冻结时不挂唤醒源，待机能休眠；4.1 内核，3.0 内核没有 EPOLLWAKEUP）
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
    # 启动镜像（boot.art / boot*.oat，约 70 MB）编在 0x70000000 起。老机型的内核是 2G/2G 分割（CONFIG_VMSPLIT_2G，用户空间到
    # 0x7f000000），默认的自顶向下 mmap 把 libart.so 这些库放在 0x73xxxxxx–0x77xxxxxx，正好压在启动镜像要的地址上：
    # zygote 开机时 patchoat 重定位崩溃（SIGSEGV），之后每个进程都 "Attempting to fall back to imageless running"
    # （整个框架没有启动镜像，每个进程自己加载初始化所有类），dex2oat 也起不来（"Only the zygote can create the global boot image"），
    # 所有应用都没有 odex、从 apk 边校验边解释（PW4 5.18.1 模拟器：微信读书每次冷启动都 ANR）。
    # 栈不限大小时内核改用自底向上的老布局（库从 TASK_SIZE/3 ≈ 0x2a000000 往上放），0x70000000 一带空出来，重定位和编译都正常。
    # 只影响容器里的进程（Android 的 init 及其子进程继承），不碰 Kindle。docs/legacy-perf.md。
    # 0.8.7 起的镜像把启动镜像编在 0x30000000（ro.vd.artbase）：用默认的自顶向下布局，不改栈限制（vd_art_stack，lib/common.sh）
    vd_art_stack $R/system/build.prop
    # legacy：HOSTNS 也走 pivot_root（在 unshare -m 的私有挂载命名空间里），不直接 chroot
    exec $VD/bin/pivot_init $R mnt /init --second-stage
}
