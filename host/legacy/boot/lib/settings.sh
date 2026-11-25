# SPDX-License-Identifier: GPL-3.0-or-later
# 设置：默认值 + 用户的 $SYS/settings.conf（shell 变量赋值，一行一个）
settings_defaults() {
    RETURN_MODE=freeze     # 返回 Kindle 时：freeze = 冻结留在内存（再打开几秒），off = 关掉
    ZRAM_MB=512            # 运行期间的压缩内存（MB），0 = 不开
    SLIM=1                 # 系统瘦身（lib/slim.sh）：1 = 停掉用不上的系统服务，0 = 原样
    HIDE_SU=1              # 1 = 应用看不到 su（lib/slim.sh su_hide：/system/xbin/su 改名 vdsu；加固应用见到 su 就自杀），0 = 原样
    LOWMEM=auto            # 低内存模式：auto = 内存不到约 700 MB 的机型自动开，on / off = 强制开 / 关（见下面 lowmem 的说明）
    KINDLE_UI=auto         # Android 在前台时 Kindle 自己的界面怎么处置（lib/framework.sh）：keep 只暂停 | freeze 再把后台进程冻住 |
                           # stop 把 Java 框架和书库程序停掉腾内存（返回 Kindle 约一分钟）| auto = 低内存模式用 stop，否则 freeze
    SYSTEM_APP=home        # 启动后打开什么：home 或包名
    ANDROID_DNS="223.5.5.5 223.6.6.6"
    KFB_CONTRAST=1.3       # 显示：对比度曲线 gamma 1.0~2.5
    KFB_DITHER=3           # 硬件抖动：0 关，1/2/3 开（硬件只有开 / 关）。只用在有图片的刷新上，文字不抖动（epd.c text_no_dither）
    KFB_FLASH=8            # 显示：每翻 N 页（大面积变化）整屏闪刷一次，0 = 不按页数（和 Kindle 的页面刷新一样，清残影）
    KFB_INVERT=0           # 显示：反色
    KFB_LIGHTF=0           # 显示：浅色过滤 0 关 1 弱 2 中 3 强：比阈值浅的颜色（浅灰 / 米色 / 浅绿底、半透明的灰色蒙层）直接变纯白，
                           # 少了抖动噪点和大面积变化，闪刷少（epd.c build_lut：弱 235 / 中 220 / 强 200，按原始亮度）。紧挨着深色笔画的浅色像素不滤，
                           # 字边缘的灰阶留着（lightf_pass，docs/text-sharpness.md）
    KFB_DARKEN=0           # 显示：深色加深 1 = 没被滤成白的部分再压深一档（浅灰字变黑）；和对比度合起来的指数最多 2.5，不把字边缘的灰阶压没
    KFB_SETTLE=40          # 显示：滑动 / 动画时合并中间帧，静止这么多毫秒后才刷（0 = 每帧都刷）
    KFB_MODE=0             # 显示：刷新方式 0 均衡 1 灰度最准 2 最快
    KFB_HOLD=70            # 显示：小于屏幕 3% 的首个变化（按钮高亮）暂缓这么多毫秒，和随后的整页变化合成一次刷新（0 = 不暂缓）
    KFB_QPASS=300          # 显示：图片补刷（消除分步加载图片 / 快刷残影，画面静止这么多毫秒后对图片区做一次不闪的 16 级灰补刷；0 = 关）
    KFB_ANIM_MODE=1        # 显示：翻页动画 0 关 1 跟随手势 2 左 3 右 4 上 5 下
    KFB_ANIM_DIR=2         # 动画方向：0 下 1 上 2 左 3 右（翻下一页通常用左）
    KFB_ANIM_STEPS=12      # 动画步数：越大越慢越细腻（MTK 驱动要求 <= 刷新区域宽/高）
    KFB_ANIM_MIN=45        # 变化面积超过百分之多少才动画（微信读书翻页约 60%，给别的 App 留余量）
    KFB_ROTATE=0           # 屏幕旋转 0 竖 / 90 / 180 倒过来 / 270（度，画面顺时针转；docs/rotation.md）。180 可即时切换，90/270 要重启 Android
    G_TL=none; G_TR=none; G_BL=kindle; G_BR=none   # 四角长按的动作。左下角默认“回到 Kindle”（2026-11-14 起：Android / 应用卡住时总有一个宿主手势能出去，
                           # 手势提示的“没有响应”会说这个）；其余默认不做（握机器时大拇指压在角上 2 秒容易误触发）。只有配了动作的角才吞掉长按（G_CORNERS）
    BOOST_MS=150           # 触摸加速：手指按下时把 CPU 最低频率提到最高，最后一根手指离开后这么多毫秒再放回。0 = 关
    BOOST_KHZ=0            # 触摸加速提到的频率（kHz）；0 = 当前允许的最高频率
    KFB_SCROLLDELAY=500    # 触摸滚动（docs/legacy-perf.md）：手指拖动时每帧立刻用最快的波形刷、面板一刷完就刷最新的一帧（跟手）；
                           # 停下这么多毫秒后把拖动过的区域整块补刷一次（16 级灰、不闪）。0 = 关（回到以前：合并中间帧、按大小选波形）
    KFB_SCROLLSTYLE=1      # 1 拖动时跟手快刷；2 拖动时不刷，停下后刷一次（按应用设）
    KFB_SCROLLWFM=a2       # 拖动时的波形：a2（i.MX 最快的 1 位波形，约为 DU 的一半时间）| du
    FAST_DRAG=1            # 单指一动（超过拖动阈值）就马上交给 Android，不等双指判定窗口（G_WINDOW）走完
    KFB_EPD_NICE=-8        # 显示桥的优先级（nice）：单核老机型上应用画图时显示桥不再排在 Android 的界面线程后面。重启 Android 生效
    BG_NICE=10             # 后台应用（oom_score_adj >= 500）所有线程的 nice：老内核没有 cpu cgroup，Android 的后台组不生效（bgnice.sh）。0 = 关
    INPUT_RT=5             # 触摸前端的实时优先级（SCHED_FIFO 1-20；0 = 不改）。重启 Android 生效
    KFB_GESTURES=1         # 全局手势总开关（底边上滑返回总是有效）
    KFB_EDGE_BACK=1        # 底边上滑返回：阅读类 App 自己也用底部上滑（微信读书：上滑显示工具栏），冲突时设 0
    # 每个手势单独的开关（veradroid-input 下一次触摸就读到，不用重启）
    G_EDGE_BACK=1          # 从最下边往上划：返回
    G_EDGE_PANEL=1         # 从最上边往下划：面板
    G_TWO_TAP=1            # 双指轻点：整屏刷新
    G_TWO_LONG=1           # 双指长按 2 秒：反色
    G_TWO_BACK=1           # 双指左右滑：返回
    G_TWO_SLIDE=1          # 双指上下滑：亮度 / 色温
    G_EDGE_SENS=1          # 边缘滑动灵敏度：0 低（条窄、要滑得长）1 标准 2 高（条宽、滑一点就算）
    G_BACK_EXCLUDE=com.tencent.weread.eink   # 这些 App 在前台时不做底边上滑返回（逗号分隔；none = 不排除）。微信读书上滑 = 阅读工具栏
    UPDATE_BACKUP=full     # 更新前备份（安装器读，backup.sh）：full 设置、阅读进度和书 | light 只备份设置和阅读进度 | off 不备份。
                           # 只能经管理请求 updatebackup 改（不在下面 settings_apply 的键里：别的应用不能悄悄把备份关掉）
    DNS_MODE=auto          # DNS：auto（Wi-Fi 自动）| aliyun | tencent | google
    G_WINDOW=40            # 双指判定窗口（毫秒）。越小单指触摸越跟手（每次触摸都要先等这么久才放给 Android）
    G_TWO_TAP_ACT=clear    # 双指轻点做什么（动作名同四角长按，见 action.sh / docs/host-settings.md）
    G_TWO_LONG_ACT=invert  # 双指长按 2 秒做什么
    SAVER_EXIT_H=0         # 锁屏（屏保）超过这么多小时，醒来时直接退出 Android 回到 Kindle（不解冻）；0 = 不退出（freeze.sh saver_exit_check）
    KFB_HWC=1              # 系统合成：1 = hwcomposer.kindle（不缩放的图层按行原样拷贝；去掉 libagl 两个三角形之间 1-2 像素的斜线错位）；0 = 软件 GLES。重启 Android 生效
    KFB_HZ=30              # KFB_HWC=1 时的界面帧率 15 / 20 / 30 / 60。重启 Android 生效
    KFB_DIM=20             # 对话框背后的遮罩压暗百分之几（0-100，hwcomposer 画；off = 交给 GLES）。重启 Android 生效
    USB_ASK=1              # Android 在前台时插上 USB 线：1 = 问要不要切到 USB 传输（回到 Kindle），0 = 不问（usb.sh）
    WIFI_PS=auto           # Wi-Fi 省电：auto（平时省电，传书 / 大量下载时临时关）| on（一直省电）| off（Android 运行时关）
    APP_COMPILE=auto       # 应用预编译（compile.sh）：auto | speed | off
    COMPILE_RUNNING=auto   # 没有能用编译代码的应用（更新后失效 / 刚装好）在用着也编：auto | 0 = 以前那样等它退出（compile.sh compile_nocode）
    TIME_12_24=auto        # 12 / 24 小时制：auto（跟 Kindle）| 12 | 24
    GESTURE_HINT=all       # 手势提示（宿主画的小方块，action.sh / docs/gesture-hint.md）：all 每个手势都提示 | stuck 只在 Android 没有响应时提示 | off 不提示。action.sh 每次手势都重读，立即生效
    SYSTEM_FONT=           # 所有应用的默认字体：Android 里的字体文件路径（/data/media/0/Fonts/…、KindleFonts/…、/system/fonts/…），空 = 原样。
                           # 重启 Android 生效（container.sh 的 sysfont_prepare / sysfont_mount）。settings.conf 里带单引号存
}
# ---- 低内存模式（一个开关）-------------------------------------------------------------------------------
# 512 MB 的机型（PW5、Kindle 2022 / KT5、KT6 和老机型）上 Kindle 自己的界面和 Android 抢同一块内存。低内存模式把相关做法收在一起：
#   1. Android 运行期间 Kindle 的书库程序（KPPMainApp）停掉；KINDLE_UI=auto 在这个模式下取 stop：连 Java 框架（cvm）也停掉
#      （KT5 模拟器实测：只停框架一项，可用内存 +50 MB、交换区空出 70 MB，见 docs/slim-android.md）；
#   2. 返回 Kindle = 完全退出 Android（不冻结留在内存里）；框架停过的话要等它重新启动，约一分钟，屏幕上有提示；
#   3. 可选的重功能默认关：翻页动画（KFB_ANIM_MODE=0）、窗口动画、几个用不上的系统应用。
# 下面这些是 512 MB 的内核上跑起来的前提，不是“模式”，只看实际内存，LOWMEM=off 也照做：
#   overcommit_memory=1、内核 LMK / lmkd 阈值、zram 扩到 256 MB、Java 堆 128m / 192m（lib/memory.sh）。
# 状态写在 /dev/kfb/ctl/status 里（lowmem= / lowmem_setting= / kindleui=），桌面可以显示；LOWMEM 和 KINDLE_UI 也能从桌面的设置改。
lowmem_ram() { _mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo); [ -n "$_mt" ] && [ "$_mt" -lt 700000 ]; }
lowmem() {
    case "${LOWMEM:-auto}" in on|1) return 0 ;; off|0) return 1 ;; esac
    lowmem_ram
}
# ---- 具体机型（legacy 包）--------------------------------------------------------------------------------
# 先看主板号（3.0 内核的 /proc/board_id，前三位是机型：亚马逊源码 include/boardid.h），没有或认不出再按硬件特征：
#   3.0：有 fsr_keypad（PagePress）= Voyage；面板短边 758 = PW2，1072 = PW3（Oasis 1 只认主板号）
#   4.1：设备树 imx7d-zelda = Oasis 2 / 3（Oasis 2 的 5.16 内核没有 PID 命名空间）；imx6sll（rex）短边 600 = 入门款 2019，否则 PW4
# 测试 / 排障：$VD/submodel 里写机型名强制指定。结果缓存在 $W/submodel（一次运行内硬件不会变）。
fb_short_side() {
    awk -F'[:x p]' '/^U:/ { w=$2; h=$3; if (w+0 > 0) { print (w < h ? w : h); exit } }' /sys/class/graphics/fb0/modes 2>/dev/null
}
model_sub() {
    [ -s "$VD/submodel" ] && { tr -cd 'a-z0-9' < "$VD/submodel"; return 0; }
    [ -n "$W" ] && [ -s "$W/submodel" ] && { cat "$W/submodel"; return 0; }
    _ms=
    case "$(uname -r)" in
        3.*)
            case "$(cut -c1-3 /proc/board_id 2>/dev/null)" in
                027|02A|02E|02F) _ms=pw2 ;;
                01A|01B|025|026|047|048) _ms=kv ;;
                067|068|13G) _ms=pw3 ;;
                078|079|07F) _ms=oa1 ;;
            esac
            if [ -z "$_ms" ]; then
                if grep -q 'Name="fsr_keypad"' /proc/bus/input/devices 2>/dev/null; then _ms=kv
                else case "$(fb_short_side)" in 758) _ms=pw2 ;; 1072) _ms=pw3 ;; esac; fi
            fi ;;
        4.1.*)
            case "$(tr '\0' ' ' < /proc/device-tree/compatible 2>/dev/null)" in
                *imx7d-zelda*) if [ -e /proc/self/ns/pid ]; then _ms=ko3; else _ms=oa2; fi ;;
                *imx6sll*) case "$(fb_short_side)" in 600) _ms=kt4 ;; *) _ms=pw4 ;; esac ;;
            esac ;;
    esac
    [ -n "$_ms" ] && [ -n "$W" ] && [ -d "$W" ] && echo "$_ms" > "$W/submodel" 2>/dev/null
    echo "$_ms"
}
# Kindle 界面这次怎么处置：keep / freeze / stop（旧值 normal = keep，nofw = stop）
kindle_ui_mode() {
    case "${KINDLE_UI:-auto}" in
        keep|normal) echo keep ;;
        freeze) echo freeze ;;
        stop|nofw) echo stop ;;
        *) if lowmem; then echo stop; else echo freeze; fi ;;
    esac
}

# PW4：硬件抖动默认改成关（models/old-base.conf，PxP 有序抖动把深灰纯色块画成竖条纹）。桌面的设置页一保存就把当时的
# KFB_DITHER=3 也写进了 settings.conf，默认值改了老用户收不到：每台机器只做一次，删掉 settings.conf 里的 KFB_DITHER=3
# （回到机型默认 0）。之后用户在 设置 → 显示 里再选“有序点阵”，就照用户的。
dither_migrate() {
    [ -f "$SYS/settings.conf" ] && [ ! -f "$SYS/.dither-default-0" ] || return 0
    if grep -q '^KFB_DITHER=3$' "$SYS/settings.conf"; then
        grep -v '^KFB_DITHER=3$' "$SYS/settings.conf" > "$SYS/settings.conf.dm" && mv -f "$SYS/settings.conf.dm" "$SYS/settings.conf" &&
            { type t >/dev/null 2>&1 && t "settings: KFB_DITHER=3 dropped once (PW4 hardware dithering now off by default)"; }
    fi
    touch "$SYS/.dither-default-0" 2>/dev/null
    return 0
}
settings_load() {
    settings_defaults
    # First-install default only.  It must be applied before settings.conf:
    # otherwise every settings_apply() reset the user's animation choice to 0.
    case "$(cat "$SYS/model" 2>/dev/null)" in
        kt5|kt6|pw6) KFB_ANIM_MODE=0 ;;
    esac
    # Optional per-model overlay (models/<id>.conf). User settings.conf still wins.
    m=$(cat "$SYS/model" 2>/dev/null)
    if [ -n "$m" ] && [ -f "$VD/models/$m.conf" ]; then
        . "$VD/models/$m.conf"
    fi
    # legacy 包：安装器只按内核代际写 legacy / imx41，具体机型（pw2 / kv / pw3 / oa1 / pw4 / kt4 / oa2 / ko3）在这里按硬件认，
    # 再叠一层 models/<机型>.conf（每台机型的调优默认值）。settings.conf 仍然最后读、优先。
    # 两层：models/old-base.conf = 所有老机型共用的默认值（先验证、单独量），models/<机型>.conf = 这台机型在共用层之上的少量差异。
    MODEL_SUB=$(model_sub)
    if [ -n "$MODEL_SUB" ]; then
        [ -f "$VD/models/old-base.conf" ] && . "$VD/models/old-base.conf"
        [ -f "$VD/models/$MODEL_SUB.conf" ] && . "$VD/models/$MODEL_SUB.conf"
    fi
    [ "$MODEL_SUB" = pw4 ] && dither_migrate
    [ -f "$SYS/settings.conf" ] && . "$SYS/settings.conf"
    # 排障 / 测试用的覆盖：系统盘没挂上时也能改（settings.conf 在系统盘里）
    [ -f "$VD/slim" ] && SLIM=$(cat "$VD/slim" 2>/dev/null)
    # 同上，任意设置（KEY=value 行，比如测试时 LOWMEM=off / KINDLE_UI=keep）：程序目录在用户分区上，不用先挂系统盘
    [ -f "$VD/settings.test" ] && . "$VD/settings.test"
    # Low-memory mode: page-turn animation is an optional heavy feature, off unless the user chose one.
    if lowmem && ! grep -qs '^KFB_ANIM_MODE=' "$SYS/settings.conf"; then KFB_ANIM_MODE=0; fi
    # 记下全局值：面板 / 设置页显示和修改的是全局设置，不能把“当前应用的单独设置”当成全局值发布出去
    # （否则在某个应用里打开面板会看到“翻页动画关 / 刷新合并关”，再点一下别的就把它存成全局了）。
    for k in KFB_CONTRAST KFB_DITHER KFB_FLASH KFB_INVERT KFB_LIGHTF KFB_DARKEN KFB_MODE KFB_SETTLE KFB_HOLD KFB_QPASS KFB_ANIM_MODE KFB_ANIM_DIR KFB_ANIM_STEPS KFB_ANIM_MIN; do eval "GLOB_$k=\$$k"; done
    [ -f "$SYS/settings.app" ] && . "$SYS/settings.app"       # 当前前台应用的显示参数（cmd.sh appdisp 写，已校验）
    # 512 MB devices: default to full exit when returning to Kindle, unless the
    # user already set RETURN_MODE. A frozen WeRead + Home sits on ~200 MB and
    # is what LMK then shoots (adj 0) when the user re-opens it.
    if ! grep -q '^RETURN_MODE=' "$SYS/settings.conf" 2>/dev/null; then
        lowmem && RETURN_MODE=off
    fi
    # Kindle framework stopped: freezing Android and restarting the framework next to it would put both back in memory.
    [ "$(kindle_ui_mode)" = stop ] && RETURN_MODE=off
    # 3.0 内核没有 PID 命名空间，也没有 freezer（冻结只能逐个发 SIGSTOP）：返回 Kindle 一律完全退出，回到 Kindle 以后用户存储上
    # 不留 VeraDroid 的进程、loop 设备和系统盘挂载（USB 连电脑时用户分区要能导出；k49 chroot 模式同样的规则）。屏保仍用信号冻结（sleep.sh）。
    if [ ! -e /proc/self/ns/pid ] && [ "$RETURN_MODE" != off ]; then RETURN_MODE=off; RETURN_MODE_FORCED=hostns; fi
}

# 把当前设置写给桌面看（/dev/kfb/settings）
settings_publish() {
    { for k in KFB_CONTRAST KFB_DITHER KFB_FLASH KFB_INVERT KFB_LIGHTF KFB_DARKEN KFB_MODE KFB_SETTLE KFB_HOLD KFB_QPASS KFB_ANIM_MODE KFB_ANIM_DIR KFB_ANIM_STEPS KFB_ANIM_MIN KFB_GESTURES KFB_ROTATE RETURN_MODE LOWMEM KINDLE_UI DNS_MODE G_TL G_TR G_BL G_BR G_EDGE_BACK G_EDGE_PANEL G_TWO_TAP G_TWO_LONG G_TWO_BACK G_TWO_SLIDE G_EDGE_SENS STORAGE_AUTO G_BACK_EXCLUDE G_TWO_TAP_ACT G_TWO_LONG_ACT WIFI_PS APP_COMPILE TIME_12_24 GESTURE_HINT BOOST_MS BOOST_KHZ KFB_SCROLLDELAY KFB_SCROLLSTYLE KFB_SCROLLWFM FAST_DRAG UPDATE_BACKUP USB_ASK PAGE_KEYS_MAP; do eval "echo $k=\${GLOB_$k:-\$$k}"; done; echo "PAGE_KEYS_HW=$([ -n "$PAGE_KEYS" ] && echo 1 || echo 0)"; } > $X/settings.tmp \
        && printf 'SYSTEM_FONT=%s\nSYSTEM_FONT_LIVE=%s\n' "$SYSTEM_FONT" "$(cat $W/sysfont/android 2>/dev/null)" >> $X/settings.tmp \
        && mv -f $X/settings.tmp $X/settings
    type ub_mirror >/dev/null 2>&1 && ub_mirror      # 安装器读的副本（backup.sh）
    return 0
}
# 桌面写了 ctl/settings.new（KEY=value 行）：只接受已知的键和简单的值，合并进 settings.conf，立即生效的推给显示桥
settings_apply() {
    # 先把文件挪走再读：看门狗和命令监听会同时调到这里（桌面发 apply 的同时看门狗看到 settings.new），
    # 以前两个一起读同一个文件，一个读完删掉，另一个满屏 "sed: settings.new: No such file"，那次设置可能没存上（用户诊断）
    sfile=$W/settings.apply.$$.$(cut -d' ' -f1 /proc/uptime | tr -d .)
    mv -f $X/ctl/settings.new "$sfile" 2>/dev/null || return 0
    touch $SYS/settings.conf
    if [ -s "$sfile" ]; then
        sa_raw=" $(tr '\n' ' ' < "$sfile") "
        sa_upd=""
        for k in KFB_CONTRAST KFB_DITHER KFB_FLASH KFB_INVERT KFB_LIGHTF KFB_DARKEN KFB_MODE KFB_SETTLE KFB_HOLD KFB_QPASS KFB_ANIM_MODE KFB_ANIM_DIR KFB_ANIM_STEPS KFB_ANIM_MIN KFB_GESTURES KFB_ROTATE RETURN_MODE LOWMEM KINDLE_UI DNS_MODE G_TL G_TR G_BL G_BR G_EDGE_BACK G_EDGE_PANEL G_TWO_TAP G_TWO_LONG G_TWO_BACK G_TWO_SLIDE G_EDGE_SENS STORAGE_AUTO G_BACK_EXCLUDE G_TWO_TAP_ACT G_TWO_LONG_ACT WIFI_PS APP_COMPILE TIME_12_24 GESTURE_HINT BOOST_MS BOOST_KHZ KFB_SCROLLDELAY KFB_SCROLLSTYLE KFB_SCROLLWFM FAST_DRAG USB_ASK PAGE_KEYS_MAP; do
            case "$sa_raw" in *" $k="*) ;; *) continue ;; esac
            v=$(sed -n "s/^$k=//p" "$sfile" | tail -1)
            [ -n "$v" ] || continue
            case "$v" in *[!0-9A-Za-z._,]*) continue ;; esac
            case "$k=$v" in
                LOWMEM=auto|LOWMEM=on|LOWMEM=off) ;; LOWMEM=*) continue ;;
                KINDLE_UI=auto|KINDLE_UI=keep|KINDLE_UI=freeze|KINDLE_UI=stop) ;; KINDLE_UI=*) continue ;;
                GESTURE_HINT=all|GESTURE_HINT=stuck|GESTURE_HINT=off|GESTURE_HINT=default) ;; GESTURE_HINT=*) continue ;;
                PAGE_KEYS_MAP=volume|PAGE_KEYS_MAP=page|PAGE_KEYS_MAP=dpad|PAGE_KEYS_MAP=default) ;; PAGE_KEYS_MAP=*) continue ;;
            esac
            sa_upd="${sa_upd}${k}=${v}
"
        done
        if [ -n "$sa_upd" ]; then
            # KEY=default：删掉用户的设置，回到默认值（机型默认 / models/<id>.conf / settings_defaults）——桌面“恢复默认设置”用
            awk -v upd_str="$sa_upd" '
                BEGIN {
                    n = split(upd_str, lines, "\n")
                    for (i = 1; i <= n; i++) {
                        eq = index(lines[i], "=")
                        if (eq > 1) { k = substr(lines[i], 1, eq - 1); upd[k] = substr(lines[i], eq + 1); ord[++m] = k }
                    }
                }
                {
                    eq = index($0, "=")
                    if (eq > 1 && (substr($0, 1, eq - 1) in upd)) next
                    print
                }
                END {
                    for (i = 1; i <= m; i++) {
                        k = ord[i]
                        if (upd[k] != "default") print k "=" upd[k]
                    }
                }
            ' "$SYS/settings.conf" > "$sfile.conf" 2>/dev/null && mv -f "$sfile.conf" $SYS/settings.conf
        fi
    fi
    # 系统字体：值是路径（有 /、-、空格、中文），不走上面那条只认 [0-9A-Za-z._,] 的规则。只收三个字体目录里的文件（sysfont_host），
    # 不收引号、$、`、\；带单引号写进 settings.conf（root 会 source 它）。default / none = 删掉（原样字体）。重启 Android 生效。
    if grep -q '^SYSTEM_FONT=' "$sfile"; then
        v=$(sed -n 's/^SYSTEM_FONT=//p' "$sfile" | tail -1)
        case "$v" in
            ''|default|none) v= ;;
            *[\'\"\$\`\\]*) v=bad ;;
            *) sysfont_host "$v" >/dev/null || v=bad ;;
        esac
        if [ "$v" != bad ]; then
            grep -v '^SYSTEM_FONT=' $SYS/settings.conf > "$sfile.conf" 2>/dev/null
            [ -n "$v" ] && printf "SYSTEM_FONT='%s'\n" "$v" >> "$sfile.conf"
            mv -f "$sfile.conf" $SYS/settings.conf
            rm -f $SYS/sysfont.bad $SYS/sysfont.try      # 用户重新选过：之前“开不了机”的记录作废
            t "settings: SYSTEM_FONT=${v:-stock} (restart Android to apply)"
        else
            t "settings: SYSTEM_FONT rejected"
        fi
    fi
    rm -f "$sfile" "$sfile.conf"
    pk_before=$PAGE_KEYS_MAP
    settings_load
    settings_publish
    # 实体翻页键发成什么键（memory.sh）：改了就重写键位表，让 Android 重新打开这个设备（立即生效）
    [ "$PAGE_KEYS_MAP" != "$pk_before" ] && type page_keys_apply >/dev/null 2>&1 && page_keys_apply
    # ANIM 必须先于 SET：显示桥收到 SET 会立刻重绘，若 ANIM 在后面，这次重绘仍用旧步数，
    # 下一条 P/D 又可能先碰到旧缓冲，导致“设置里选了 6 步，日志还是 12”。
    epd_send ANIM $KFB_ANIM_MODE $KFB_ANIM_DIR $KFB_ANIM_STEPS $KFB_ANIM_MIN
    epd_send SET $KFB_CONTRAST $KFB_DITHER $KFB_FLASH $KFB_INVERT $KFB_MODE $KFB_SETTLE ${KFB_LIGHTF:-0} ${KFB_DARKEN:-0}
    epd_send HOLD ${KFB_HOLD:-70}
    epd_send QPASS ${KFB_QPASS:-300}
    epd_send_scroll
    dns_apply
    # 手势开关：输入程序每次触摸前看这个标记文件
    if [ "$KFB_GESTURES" = 0 ]; then touch $W/nogestures; else rm -f $W/nogestures; fi
    gestures_write
    # 新键（docs/host-settings.md）：立即生效的部分
    type wifi_ps_tick >/dev/null 2>&1 && [ ! -f $W/frozen ] && wifi_ps_tick
    type time_format_apply >/dev/null 2>&1 && alive $W/pid && time_format_apply
    [ "$APP_COMPILE" = off ] && rm -f $W/compile.queue
    t "settings applied: contrast=$KFB_CONTRAST dither=$KFB_DITHER flash=$KFB_FLASH invert=$KFB_INVERT lightf=${KFB_LIGHTF:-0} darken=${KFB_DARKEN:-0}"
    rotate_apply      # 旋转变了才有动作（cmd.sh）
}


# 可选 DNS：Android 的 netd 里把 eth0（netId 100）的解析器换掉；不碰 Kindle 的 /etc/resolv.conf。
# auto = slirp 给的 10.157.43.3；其余是公共 DNS。
dns_apply() {
    case "$DNS_MODE" in
        aliyun)  d1=223.5.5.5; d2=223.6.6.6 ;;
        tencent) d1=119.29.29.29; d2=182.254.116.116 ;;
        google)  d1=8.8.8.8; d2=8.8.4.4 ;;
        *)       d1=10.157.43.3; d2= ;;
    esac
    # hostnet（chroot，共用 Kindle 网络）没有 slirp，10.157.43.3 不存在：auto 时用写给系统的那份 DNS（ANDROID_DNS + Kindle 的）
    if [ "$DNS_MODE" != aliyun ] && [ "$DNS_MODE" != tencent ] && [ "$DNS_MODE" != google ] && [ -s $X/hostnet ]; then
        d1=$(sed -n "s/^dns=//p" $X/hostnet | sed -n 1p); d2=$(sed -n "s/^dns=//p" $X/hostnet | sed -n 2p)
        [ -n "$d1" ] || return 0
    fi
    pid=$(cat $W/pid 2>/dev/null)   # 以前读 $X/pid（从来没人写），DNS 选择一直没生效
    [ -n "$pid" ] || return 0
    P=$pid; $(vd_in) /system/bin/sh -c "export ANDROID_DATA=/data ANDROID_ROOT=/system PATH=/system/bin; ndc resolver setnetdns 100 '' $d1 $d2" >/dev/null 2>&1
    t "dns $DNS_MODE ($d1 $d2)"
}

# 触摸滚动的几条（显示桥 SCROLLDELAY / SCROLLSTYLE / SCROLLWFM 等，docs/legacy-perf.md）。appprof.sh 切换应用时也发
# （那份文件三个宿主相同：它看到 epd_scroll_lines 才发）
epd_scroll_lines() {
    echo "SCROLLDELAY ${KFB_SCROLLDELAY:-500}"; echo "SCROLLSTYLE ${KFB_SCROLLSTYLE:-1}"; echo "SCROLLWFM ${KFB_SCROLLWFM:-a2}"
    echo "MONO ${KFB_MONO:-0}"; echo "MAXDEFER ${KFB_MAXDEFER:-1200}"; echo "FLIPINPUT ${KFB_FLIPINPUT:-1000}"
}
epd_send_scroll() {
    [ -p $X/epd.fifo ] && alive $W/epd.pid || return 0
    vd_timeout 3 sh -c 'printf "%s\n" "$1" > "$2"' sh "$(epd_scroll_lines)" $X/epd.fifo
}

# 每个手势的开关写给 veradroid-input（--gconf）：它在每次触摸开始时看文件有没有变，变了就重读，立即生效
gestures_write() {
    eb=${G_EDGE_BACK:-1}; [ "${KFB_EDGE_BACK:-1}" = 0 ] && eb=0   # 旧设置 KFB_EDGE_BACK=0 仍然有效
    { echo "G_EDGE_BACK=$eb"; echo "G_EDGE_PANEL=${G_EDGE_PANEL:-1}"; echo "G_TWO_TAP=${G_TWO_TAP:-1}"; echo "G_TWO_LONG=${G_TWO_LONG:-1}"
      echo "G_TWO_BACK=${G_TWO_BACK:-1}"; echo "G_TWO_SLIDE=${G_TWO_SLIDE:-1}"; echo "G_EDGE_SENS=${G_EDGE_SENS:-1}"; echo "G_BACK_EXCLUDE=${G_BACK_EXCLUDE:-none}"
      cs=; for c in TL:tl TR:tr BL:bl BR:br; do eval "a=\${G_${c%%:*}:-none}"; [ "$a" = none ] || cs="$cs${cs:+,}${c#*:}"; done
      # 触摸加速和拖动信号（SCROLL 1 / 0 给显示桥的触摸滚动方式；KFB_SCROLLDELAY=0 时显示桥自己不理）
      echo "BOOST_MS=${BOOST_MS:-150}"; echo "BOOST_KHZ=${BOOST_KHZ:-0}"; echo "SCROLL_PX=${SCROLL_PX:-20}"; echo "FAST_DRAG=${FAST_DRAG:-1}"
      echo "G_CORNERS=${cs:-none}"   # 只有配了动作的角才算手势，别的角的长按照样给 Android
      echo "ROTATE=$(rot_deg)"; } > $W/gestures.tmp \
        && mv -f $W/gestures.tmp $W/gestures
}
