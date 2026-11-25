# 每个应用的显示设置（per-app display profiles）。host/pw6、host/k49、host/legacy 三份完全相同（tests/run-static.sh 核对），
# 机型差别只在各自 settings.sh 里有没有那些键 / 显示桥认不认那些命令：认不了的键写进去也只是没人用。
#
# 前台信号：VeraHome 的 TaskStackListener（任务栈一变系统就通知，不轮询、不跑 dumpsys）发命令 "fg <包名>"；
#   桌面自己点开应用时在 startActivity 之前先发一次（应用画第一帧前就换好）。桌面 / 设置 = "fg home"。
#   同一个包重复发、或者换了应用但结果参数一样：什么都不做（不发 SET，显示桥不重画）。
# 文件：
#   $VD/boot/appprofiles.conf  内置推荐（随版本更新，只读）："<包名>[*] KEY=v;KEY=v  # 注释"，末尾 * = 前缀匹配
#   $SYS/settings.apps         用户自己的（和 settings.conf 放在一起，升级不动）："<包名> KEY=v;KEY=v"；"<包名> -" = 全跟随全局
#                              （有内置推荐的应用，用户选“全部跟随”时就是这一行）。用户的一行整体代替内置的那一行。
#   $X/appprof                 合并后的表，给桌面看（容器里 /dev/kfb/appprof）："<包名> b|u|ub <spec>"（b 内置、u 用户、ub 用户的盖住了内置的；spec 空 = -）
#   $SYS/settings.app          当前前台应用实际生效的参数（settings_load 最后读；以前 cmd.sh appdisp 写的就是它）
# 命令（cmd.sh）：
#   fg <pkg>|home              前台变了
#   appset <pkg>|<spec>        存用户的设置（spec 空 = 删掉这一行，回到内置推荐 / 全局；"-" = 全跟随）；"appset *|" 全部清掉
# SLIM 不是显示参数：后台瘦身（appslim.sh，停用 / 恢复应用的后台组件），不发给显示桥
# KEEPALIVE / BGSTOP 不是显示参数：保持后台 / 离开后几分钟自动停止（memkeep.sh 读合并表 $X/appprof），不发给显示桥
# OEC_* 不是显示桥的参数：系统镜像（feat/fw-oec，ro.vd.oec=1）里每个应用进程自己读 $X/appoec（见下面 appprof_oec_publish）
APPPROF_RE='^KFB_(CONTRAST|DITHER|FLASH|INVERT|LIGHTF|DARKEN|MODE|SETTLE|HOLD|SMOOTH|PACE|CLEAN|REAGL_MIN|SETTLE2|SCROLLDU|IMG|SCROLLDELAY|SCROLLSTYLE|GCN|NEWWIN|MONO|MAXDEFER|FLIPINPUT|TURNHOLD|QPASS|ANIM_MODE|ANIM_DIR|ANIM_STEPS|ANIM_MIN)=[0-9.]{1,6}$|^(KEEPALIVE|BGSTOP)=[0-9]{1,4}$|^SLIM=[01]$|^OEC_(DPI|FLING|SMOOTH|PAGEKEY|TOAST|LIGHTBAR|DLGBORDER|WBOLD|WBLACK|WFONT|WCSS)=[0-9]{1,3}$'

appprof_valid_pkg() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1 ;; esac; [ ${#1} -le 200 ]; }

# 把 "K=v;K=v" 过滤成 settings.app 的行（只留白名单里的键和数字值）
appprof_lines() { printf '%s\n' "$1" | tr ';' '\n' | grep -E "$APPPROF_RE"; }

# 合并表：用户的一行盖掉内置的同名一行；内置的前缀行（com.foo.*）照原样列出，桌面自己按前缀匹配
appprof_publish() {
    set --; for ap_f in "$VD/boot/appprofiles.conf" "$SYS/settings.apps"; do [ -f "$ap_f" ] && set -- "$@" "$ap_f"; done
    [ $# -gt 0 ] || set -- /dev/null
    awk 'FNR == 1 { src = (FILENAME ~ /settings\.apps$/) ? "u" : "b" }
         /^[ \t]*(#|$)/ { next }
         { sub(/[ \t]*#.*/, ""); p = $1; s = $2; if (s == "") s = "-"
           if (src == "u") { u[p] = s; if (!(p in seen)) { seen[p] = 1; ord[++n] = p } }
           else { b[p] = s; if (!(p in seen)) { seen[p] = 1; ord[++n] = p } } }
         END { for (i = 1; i <= n; i++) { p = ord[i]; if (p in u) print p, (p in b ? "ub" : "u"), u[p]; else print p, "b", b[p] } }' \
        "$@" 2>/dev/null > $X/appprof.tmp
    chmod 644 $X/appprof.tmp 2>/dev/null; mv -f $X/appprof.tmp $X/appprof
    # “应用优化”页（VeraHome）认的开关：KEY|标题|选项=值,...（页面自己加“跟随”；同名的盖掉页面内置的那一项）。
    # 每个模块写自己那一份 $X/appknobs.d/<模块>（显示桥的是 appknobs.d/epd），下面 appprof_oec_publish 里的 appknobs_join 合成 $X/appknobs
    mkdir -p $X/appknobs.d
    printf '%s\n' \
        'KFB_SCROLLSTYLE|滚动方式|跟手快刷=1,停下再刷=2' \
        'KFB_SCROLLDELAY|滚动后补刷|0.5 秒后=500,1 秒后=1000,1.5 秒后=1500,不用触摸滚动=0' \
        'KFB_FLIPINPUT|按页全刷只算手动翻页|是=1000,否（画面自己变也算）=0' \
        'KFB_GCN|界面残影定时全刷|关=0,每 20 次刷新=20,每 30 次刷新=30,每 50 次刷新=50' \
        'KFB_NEWWIN|打开时整屏刷新|关=0,开=1' \
        'KFB_MONO|快刷黑白分明|关=0,开=128' \
        'KFB_MAXDEFER|画面最长等待|0.6 秒=600,1.2 秒=1200,不限=0' \
        'KFB_TURNHOLD|翻页动画合并（应用自己有翻页动画时只刷最后一帧）|关=0,0.1 秒=100,0.15 秒=150,0.25 秒=250' \
        'KFB_QPASS|图片补刷（消除分步加载图片残影）|自动 (0.3 秒)=300,关（不补刷）=0,慢速 (0.6 秒)=600' > $X/appknobs.d/epd.tmp 2>/dev/null \
        && mv -f $X/appknobs.d/epd.tmp $X/appknobs.d/epd
    # 要换显示密度的包（内置表里带 DENSITY 的规则，可以以 * 结尾）：桌面打开它们之前等宿主换好密度（/dev/kfb/density.apps）
    # 系统镜像自己按应用换密度（appprof_oec_density）时，宿主不换、桌面也不用等：表是空的
    if appprof_oec_density; then : > $X/density.apps.tmp
    else awk '/^[ \t]*(#|$)/ { next } { sub(/[ \t]*#.*/, "") } $2 ~ /(^|;)DENSITY=/ { print $1 }' "$VD/boot/appprofiles.conf" > $X/density.apps.tmp 2>/dev/null; fi
    chmod 644 $X/density.apps.tmp 2>/dev/null; mv -f $X/density.apps.tmp $X/density.apps
    # 桌面“应用优化”页上宿主管的选项：每个模块写自己的一份 $X/appknobs.d/<模块>（memkeep.sh 的 mem_knobs = appknobs.d/mem，
    # 下面的 appprof_oec_publish = appknobs.d/oec），appknobs_join 拼成 $X/appknobs（格式见 VeraHome AppDisplayActivity.Knob）
    type mem_knobs >/dev/null 2>&1 && mem_knobs
    appprof_oec_publish
}

# 这个包现在该用的 spec（没有 = 空）：用户的精确匹配 > 内置精确匹配 > 内置前缀匹配（最长的前缀）
appprof_spec() {
    [ -s $X/appprof ] || appprof_publish
    awk -v p="$1" '
        $1 == p { s = $3; exit }
        $1 ~ /\*$/ { pre = substr($1, 1, length($1) - 1); if (index(p, pre) == 1 && length(pre) > best) { best = length(pre); s = $3 } }
        END { if (s != "-") print s }' $X/appprof 2>/dev/null
}

# 这个包生效的那一行来自哪里：b 内置 / u、ub 用户的 / 空 = 没有（前缀行都是内置的）
appprof_src() {
    awk -v p="$1" '
        $1 == p { s = $2; exit }
        $1 ~ /\*$/ { pre = substr($1, 1, length($1) - 1); if (index(p, pre) == 1 && length(pre) > best) { best = length(pre); s = $2 } }
        END { print s }' $X/appprof 2>/dev/null
}

# 告诉桌面这次 fg 处理完了（密度已经换好）：桌面打开要换密度的应用时，先等到这一行再 startActivity（AppProf.launching）
appprof_ack() { echo "$1" > $X/density.fg.tmp 2>/dev/null && chmod 644 $X/density.fg.tmp && mv -f $X/density.fg.tmp $X/density.fg; }

appprof_fg() {
    ap_pkg=$1
    [ "$ap_pkg" = com.veradroid.home ] && ap_pkg=home
    [ "$ap_pkg" = home ] || appprof_valid_pkg "$ap_pkg" || return 0
    type mem_fg >/dev/null 2>&1 && mem_fg "$ap_pkg"     # 后台时间 / 桌面不换出（memkeep.sh）
    # 同一个包：显示参数不动；密度照样核对一次（返回 Kindle 时换回了默认，解冻后再打开同一个应用要换回去；没变化就什么都不做）
    [ "$ap_pkg" = "$(cat $W/appprof.pkg 2>/dev/null)" ] && { appprof_density_fg "$ap_pkg"; appprof_ack "$ap_pkg"; return 0; }
    echo "$ap_pkg" > $W/appprof.pkg
    # 显示密度先换（应用要在画第一帧之前拿到新的配置）；和下面的显示桥参数互不相干，“same as before”也照样要看
    appprof_density_fg "$ap_pkg"
    appprof_ack "$ap_pkg"
    ap_spec=; [ "$ap_pkg" = home ] || ap_spec=$(appprof_spec "$ap_pkg")
    ap_new=$(appprof_lines "$ap_spec" | grep -v '^KEEPALIVE=\|^BGSTOP=\|^OEC_\|^SLIM=')
    # 用户在全局里明确选了“不全刷”（settings.conf 里 KFB_FLASH=0）：内置推荐不能替他打开按页数全刷（0.96 用户反馈“更新后会黑屏全刷”）。
    # 用户自己给这个应用存的设置（u / ub）照旧生效。
    if [ -n "$ap_new" ] && grep -qx 'KFB_FLASH=0' "$SYS/settings.conf" 2>/dev/null && [ "$(appprof_src "$ap_pkg")" = b ]; then
        ap_new=$(printf '%s\n' "$ap_new" | grep -v '^KFB_FLASH=')
    fi
    ap_old=$(cat $SYS/settings.app 2>/dev/null)
    if [ "$ap_new" = "$ap_old" ]; then
        # 参数没变也算换了窗口：KFB_NEWWIN=1 时照样让新应用的第一屏闪刷一次；桌面 / 应用之间切换时数页开关（FLIPCOUNT）照样要换
        ap_msg=; [ "$ap_pkg" = home ] && ap_msg="FLIPCOUNT 0" || ap_msg="FLIPCOUNT 1"
        if grep -qx 'KFB_NEWWIN=1' $SYS/settings.app 2>/dev/null || { ! grep -q '^KFB_NEWWIN=' $SYS/settings.app 2>/dev/null && grep -qx 'KFB_NEWWIN=1' $SYS/settings.conf 2>/dev/null; }; then
            ap_msg="$ap_msg
NEWWIN"
        fi
        if [ -p $X/epd.fifo ] && alive $W/epd.pid; then
            if type vd_timeout >/dev/null 2>&1; then vd_timeout 3 sh -c 'printf "%s\n" "$1" > "$2"' sh "$ap_msg" $X/epd.fifo
            else printf '%s\n' "$ap_msg" > $X/epd.fifo; fi
        fi
        t "app display $ap_pkg: same as before (${ap_spec:-global})"; return 0
    fi
    if [ -n "$ap_new" ]; then printf '%s\n' "$ap_new" > $SYS/settings.app.tmp && mv -f $SYS/settings.app.tmp $SYS/settings.app; else rm -f $SYS/settings.app; fi
    ap_newpkg=1; appprof_send; ap_newpkg=0
    t "app display $ap_pkg: ${ap_spec:-global}"
}

# 把 settings.app（已经写好）发给显示桥
# 一次写进 FIFO（一次 open，一批命令；以前每条 epd_send 都要 fork 一次查进程，切换应用时多出约 100 ms）
appprof_send() {
    settings_load 2>/dev/null || true
    [ -p $X/epd.fifo ] && alive $W/epd.pid || return 0
    ap_msg=$({ echo "SET $KFB_CONTRAST $KFB_DITHER $KFB_FLASH $KFB_INVERT $KFB_MODE $KFB_SETTLE ${KFB_LIGHTF:-0} ${KFB_DARKEN:-0}"
      echo "ANIM $KFB_ANIM_MODE $KFB_ANIM_DIR $KFB_ANIM_STEPS $KFB_ANIM_MIN"
      echo "HOLD ${KFB_HOLD:-70}"
      echo "QPASS ${KFB_QPASS:-300}"
      # 桌面在前台时不数页（桌面换页、切标签不是读书翻页，用不着按页数全刷）
      [ -n "$ap_pkg" ] && { [ "$ap_pkg" = home ] && echo "FLIPCOUNT 0" || echo "FLIPCOUNT 1"; }
      # 换了前台应用（KFB_NEWWIN=1，按新应用的设置）：新应用的第一屏整屏闪刷一次
      [ "${KFB_NEWWIN:-0}" = 1 ] && [ "$ap_newpkg" = 1 ] && echo NEWWIN
      # legacy 的显示桥没有 PACE/CLEAN/REAGL/SETTLE2/SCROLLDU/IMG：那里的 settings.sh 不定义 epd_send_smooth，这几条就不发
      if type epd_send_smooth >/dev/null 2>&1; then
          echo "PACE ${KFB_PACE:-0}"; echo "CLEAN ${KFB_CLEAN:-0}"; echo "REAGL ${KFB_REAGL_MIN:-35}"
          echo "SETTLE2 ${KFB_SETTLE2:-40}"; echo "SCROLLDU ${KFB_SCROLLDU:-0}"; echo "IMG ${KFB_IMG:-0}"
          echo "SCROLLDELAY ${KFB_SCROLLDELAY:-500}"; echo "SCROLLSTYLE ${KFB_SCROLLSTYLE:-1}"; echo "GCN ${KFB_GCN:-0}"; echo "MONO ${KFB_MONO:-0}"; echo "MAXDEFER ${KFB_MAXDEFER:-1200}"; echo "FLIPINPUT ${KFB_FLIPINPUT:-1000}"; echo "TURNHOLD ${KFB_TURNHOLD:-0}"
      fi; })
    # 显示桥在上面的检查和这次写之间退出时，打开 FIFO 写会一直等读者：和 epd_send 一样限 3 秒（vd_timeout，common.sh），
    # 不让命令监听（fg / appset）卡死。没有 vd_timeout 的老宿主照旧直接写。
    if type vd_timeout >/dev/null 2>&1; then vd_timeout 3 sh -c 'printf "%s\n" "$1" > "$2"' sh "$ap_msg" $X/epd.fifo
    else printf '%s\n' "$ap_msg" > $X/epd.fifo; fi
}

# 合并表里这个包的 SLIM=（后台瘦身的选择；没有 = 空）
appprof_slimval() { awk -v p="$1" '$1 == p { print $3 }' $X/appprof 2>/dev/null | tr ';' '\n' | grep '^SLIM='; }

appprof_set() {
    ap_p=${1%%|*}; ap_s=${1#*|}; [ "$ap_s" = "$1" ] && ap_s=
    touch $SYS/settings.apps
    if [ "$ap_p" = '*' ]; then : > $SYS/settings.apps.tmp
    else
        appprof_valid_pkg "$ap_p" || return 0
        awk -v p="$ap_p" '$1 != p' $SYS/settings.apps > $SYS/settings.apps.tmp 2>/dev/null
        if [ "$ap_s" = - ]; then echo "$ap_p -" >> $SYS/settings.apps.tmp
        elif [ -n "$ap_s" ]; then
            ap_c=$(appprof_lines "$ap_s" | tr '\n' ';'); ap_c=${ap_c%;}
            echo "$ap_p ${ap_c:--}" >> $SYS/settings.apps.tmp
        fi
    fi
    mv -f $SYS/settings.apps.tmp $SYS/settings.apps
    ap_dpi0=$(appprof_oec_dpi "$ap_p")
    ap_slim0=; [ "$ap_p" != '*' ] && ap_slim0=$(appprof_slimval "$ap_p")
    appprof_publish
    # 后台瘦身的选择变了（appslim.sh）：后台按新的选择停用 / 恢复这个应用的组件（"*" = 全部清掉：清单里的应用都按默认再做一遍）
    if type appslim_apply >/dev/null 2>&1; then
        if [ "$ap_p" = '*' ]; then ( appslim_all appset ) &
        elif [ "$ap_slim0" != "$(appprof_slimval "$ap_p")" ]; then ( appslim_apply "$ap_p" appset ) &
        fi
    fi
    # 应用进程只在启动时取密度：这个应用的密度变了，停掉它（下次打开按新的密度；设置页是桌面的，应用这时不在前台）
    if [ "$ap_p" != '*' ] && appprof_oec && [ "$ap_dpi0" != "$(appprof_oec_dpi "$ap_p")" ] && type adb_sh >/dev/null 2>&1; then
        adb_sh "am force-stop $ap_p" >/dev/null 2>&1; t "app density changed: $ap_p stopped"
    fi
    # 正在前台的就是它：马上换上（不用等下次切换）
    ap_cur=$(cat $W/appprof.pkg 2>/dev/null); rm -f $W/appprof.pkg
    [ -n "$ap_cur" ] && appprof_fg "$ap_cur"
    t "app display saved: $ap_p ${ap_s:-(default)}"
}

# 启动时：合并表发布出来；上次停在某个应用时留下的 settings.app 不带进新的一次（开机时前台是桌面）
appprof_init() { rm -f $SYS/settings.app $W/appprof.pkg; appprof_density_boot; appprof_publish; }

# ---- 显示密度：规则里的 DENSITY=auto1080 | <数字>（只认内置表 appprofiles.conf，用户的设置存不了这个键）----
# 多看（com.duokan.reader / com.duokan.einkreader）自己把阅读页的密度改成 320 × 屏宽 / 1080（PW6 1272 → 376），系统是 300：
# 字按 376 画、版面按 300 排，右边和下面的字出了屏幕。它在前台时把系统密度临时换成同一个值（auto1080），离开就换回默认。
# 实现：容器里 service call window（IWindowManager，原生程序，约 50 ms；wm density 要起 app_process，约 1 秒）
#   12 = setForcedDisplayDensityForUser(display, density, user)   13 = clearForcedDisplayDensityForUser(display, user)
#   10 = getInitialDisplayDensity(display)                         6 = getInitialDisplaySize(display, out Point)
# 系统会把强制密度存进 Settings.Secure display_density_forced（/data/system/users/0/settings_secure.xml），重启后还在：
# 开机前（appprof_density_boot，容器起来之前）和停止后（stop.sh）都把它从文件里抹掉——留下一个 376 别的应用就全都变样。
# $W/density.cur = 现在生效的强制密度（空 / 没有 = 默认）；只有值真的变了才调一次（一次前台变化最多一次密度变化）。
APPPROF_DENSITY_SETTINGS="$SYS/data/system/users/0/settings_secure.xml"

# 内置表里这个包的 DENSITY 值（精确匹配 > 最长前缀），没有就空
appprof_density_rule() {
    awk -v p="$1" '/^[ \t]*(#|$)/ { next }
        { sub(/[ \t]*#.*/, ""); k = $1; d = ""; n = split($2, a, ";")
          for (i = 1; i <= n; i++) if (a[i] ~ /^DENSITY=/) d = substr(a[i], 9)
          if (k == p) { ex = d; hit = 1 }
          else if (k ~ /\*$/) { pre = substr(k, 1, length(k) - 1); if (index(p, pre) == 1 && length(pre) > best) { best = length(pre); pd = d } } }
        END { print (hit ? ex : pd) }' "$VD/boot/appprofiles.conf" 2>/dev/null
}

# service call 的回复 "Result: Parcel(00000000 00000001 000004f8 000006a0 '....')" → 十进制的各个字（不含开头的异常码）
appprof_parcel_words() { sed -n 's/^Result: Parcel(\([0-9a-f ]*\).*/\1/p' | awk '{ for (i = 2; i <= NF; i++) print $i }' | while read -r w; do echo $((0x$w)); done; }
appprof_wm() { type vd_in >/dev/null 2>&1 || return 1; ad_in=$(vd_in) || return 1; vd_timeout 3 $ad_in /system/bin/service call window "$@" 2>/dev/null; }   # Android 没在跑：什么都不做

# 这个包要的密度（数字）；空 = 默认。auto1080 = 320 × 屏宽 / 1080 舍去小数（屏宽取 Android 的原始显示宽度，开机后算一次）
appprof_density_want() {
    appprof_oec_density && return 0      # 系统镜像按进程换密度（OEC_DPI / DENSITY，OecConfig），全局密度不动
    ad_r=; [ "$1" = home ] || ad_r=$(appprof_density_rule "$1")
    case "$ad_r" in
        auto1080)
            ad_w=$(cat $W/density.width 2>/dev/null)
            if [ -z "$ad_w" ]; then
                ad_w=$(appprof_wm 6 i32 0 | appprof_parcel_words | sed -n 2p)   # 1 = Point 不为空，接着是 x y
                case "$ad_w" in ''|*[!0-9]*) return 0 ;; esac
                [ "$ad_w" -ge 400 ] 2>/dev/null || return 0
                echo "$ad_w" > $W/density.width
            fi
            ad_n=$(( 320 * ad_w / 1080 )) ;;   # 取整舍去小数：多看就是这样算的（PW6 实测 376 正好，377 时右边 / 下面各空出 3–4 像素黑边）
        ''|*[!0-9]*) return 0 ;;
        *) ad_n=$ad_r ;;
    esac
    [ "$ad_n" -ge 100 ] && [ "$ad_n" -le 640 ] 2>/dev/null || return 0
    # 和系统原本的密度一样：不用强制
    ad_i=$(cat $W/density.initial 2>/dev/null)
    if [ -z "$ad_i" ]; then ad_i=$(appprof_wm 10 i32 0 | appprof_parcel_words | head -1); case "$ad_i" in ''|*[!0-9]*) ad_i= ;; *) echo "$ad_i" > $W/density.initial ;; esac; fi
    [ "$ad_n" = "$ad_i" ] && return 0
    echo "$ad_n"
}

# 换密度时前台应用（和后台它自己的其它页面）要重建。打开应用 / 从“切换应用”回去时，桌面先发 fg、等宿主换好密度（density.fg）
# 再 startActivity / moveTaskToFront，应用直接按新密度建，不多重建一次；显示桥的 LAUNCH 挡住纯色的启动窗口。
# 桌面（VeraHome HomeActivity）自己处理密度变化、不重建（以前每次都重建一次，交出黑底和大一号的半张画面）。
# 把强制密度设成 $1（空 = 恢复默认）；和现在一样就什么都不做
appprof_density_set() {
    ad_cur=$(cat $W/density.cur 2>/dev/null)
    [ "$1" = "$ad_cur" ] && return 0
    if [ -n "$1" ]; then appprof_wm 12 i32 0 i32 "$1" i32 0 | grep -q '^Result: Parcel(00000000 ' || { t "density: setting $1 failed"; return 1; }
    else appprof_wm 13 i32 0 i32 0 | grep -q '^Result: Parcel(00000000 ' || { t "density: reset failed"; rm -f $W/density.cur; return 1; }; fi
    if [ -n "$1" ]; then echo "$1" > $W/density.cur; else rm -f $W/density.cur; fi
    t "density: ${1:-default}${ad_cur:+ (was $ad_cur)}"
}

appprof_density_fg() { appprof_density_set "$(appprof_density_want "$1")"; }

# 返回 Kindle（冻结之前，显示桥已经被 ctl/sleep 挡住）：恢复默认。解冻后桌面打开 ctl/app 里的应用 / 回桌面时发 fg，按那个应用再换。
appprof_density_reset() { [ -s $W/density.cur ] && appprof_density_set ""; return 0; }

# 从系统设置文件里抹掉强制密度（Android 没在跑的时候：开机前、停止后）。原子替换，保留属主 / 权限；AtomicFile 的 .bak 也一样处理
appprof_density_scrub() {
    rm -f $W/density.cur $W/density.width $W/density.initial
    for ad_f in "$APPPROF_DENSITY_SETTINGS" "$APPPROF_DENSITY_SETTINGS.bak"; do
        [ -f "$ad_f" ] && grep -q 'name="display_density_forced" value="[0-9]' "$ad_f" 2>/dev/null || continue
        # 属主（system 1000:1000）一定要保留：root 的 600 文件 system_server 读不了，整个 Secure 设置会被当成空的。
        # 老机型的 busybox 可能没有 stat：用 ls -ln；还拿不到就原地写回（同一个 inode，属主 / 权限不变）
        ad_o=$(stat -c '%u:%g' "$ad_f" 2>/dev/null) || ad_o=; ad_m=$(stat -c '%a' "$ad_f" 2>/dev/null) || ad_m=
        [ -n "$ad_o" ] || ad_o=$(ls -ln "$ad_f" 2>/dev/null | awk '$3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { print $3 ":" $4 }')
        if sed 's/\(name="display_density_forced" value="\)[0-9]*"/\1"/' "$ad_f" > "$ad_f.vdtmp" && [ -s "$ad_f.vdtmp" ] &&
           if [ -n "$ad_o" ]; then chown "$ad_o" "$ad_f.vdtmp" && chmod "${ad_m:-600}" "$ad_f.vdtmp" && mv -f "$ad_f.vdtmp" "$ad_f"
           else cat "$ad_f.vdtmp" > "$ad_f" && rm -f "$ad_f.vdtmp"; fi; then
            t "density: removed a saved forced density from ${ad_f##*/}"
        else rm -f "$ad_f.vdtmp"; t "density: could not clean ${ad_f##*/}"; fi
    done
    return 0
}
appprof_density_boot() { appprof_density_scrub; }

# ---- 系统镜像里的每应用设置（feat/fw-oec：framework 的 com.android.internal.vd.OecConfig）----
# 镜像带 ro.vd.oec=1 时，每个应用进程启动时自己读 $X/appoec（容器里 /dev/kfb/appoec），恢复到前台时文件变了再读一次：
#   每应用密度（不再全局 wm density：切换不重排、桌面图标不变大）、惯性滑动 / 平滑滚动动画关掉、Toast 黑字白底、
#   系统栏浅色、对话框描边、翻页键当音量键 / 滑动、WebView 加粗 / 黑字 / 字号 / 自定义 CSS。
# 表的格式："<包名>[*] KEY=v;KEY=v"，"*" 一行 = 全局（settings.conf 里的 OEC_* 键）。查找：全局 → 最长前缀 → 精确，逐键覆盖。
# 每个包：内置表（appprofiles.conf）里的 OEC_* / DENSITY，再用用户的（settings.apps）逐键盖掉（“跟随”= 用户没存这个键 = 内置的）。
# DENSITY_HOST=1（settings.conf）：不管镜像，照旧由宿主换全局密度（排查用）。
# 自定义 CSS（OEC_WCSS=1）：$SYS/oec/<包名>.css（用户的）或 $VD/boot/oec/<包名>.css（内置），发布到 $X/oec/。
appprof_oec() {
    if [ -z "${APPPROF_OEC:-}" ]; then
        APPPROF_OEC=0
        grep -q '^ro.vd.oec=[1-9]' "$R/system/build.prop" 2>/dev/null && ! grep -qx 'DENSITY_HOST=1' "$SYS/settings.conf" 2>/dev/null && APPPROF_OEC=1
    fi
    [ "$APPPROF_OEC" = 1 ]
}

# 内置表里的 DENSITY（多看）交给镜像按进程换，还是照旧由宿主换全局密度。ro.vd.oec=2 起的镜像才交给镜像：
# ro.vd.oec=1（1.00 的 pw6-a8ffca4）只换了 Resources / Display，进程的默认密度（ActivityThread.mCurDefaultDisplayDpi ->
# DisplayMetrics.DENSITY_DEVICE、Bitmap 的默认密度）还是 300。多看把正文画进按默认密度建的 Bitmap、再贴到 376 的画布上：
# 整页放大 376 / 300 = 1.25 倍，下面几行压到页脚、出了屏幕（测试机实测；1.00 用户反馈“多看排版有问题”）。
# 这种镜像上带 DENSITY 的内置规则仍由宿主换全局密度（和 0.99 一样：density.apps 有它、appoec 里不带 DENSITY），其它 OEC_* 照旧。
# 用户自己在“应用优化”里选的 OEC_DPI 照旧给镜像（宿主的全局切换只认内置表）。
appprof_oec_density() {
    appprof_oec || return 1
    if [ -z "${APPPROF_OECD:-}" ]; then
        APPPROF_OECD=0
        ap_v=$(sed -n 's/^ro\.vd\.oec=\([0-9][0-9]*\)$/\1/p' "$R/system/build.prop" 2>/dev/null | head -1)
        [ "${ap_v:-0}" -ge 2 ] 2>/dev/null && APPPROF_OECD=1
    fi
    [ "$APPPROF_OECD" = 1 ]
}

# 合并后的表里这个包的 OEC_DPI / DENSITY（精确的包名行；比较前后用）
appprof_oec_dpi() { awk -v p="$1" '$1 == p { n = split($2, a, ";"); for (i = 1; i <= n; i++) if (a[i] ~ /^(OEC_DPI|DENSITY)=/) printf "%s;", a[i] }' $X/appoec 2>/dev/null; }

appprof_oec_publish() {
    if ! appprof_oec; then rm -f $X/appoec $X/appknobs.d/oec; appknobs_join; return 0; fi
    set --; for ap_f in "$VD/boot/appprofiles.conf" "$SYS/settings.apps"; do [ -f "$ap_f" ] && set -- "$@" "$ap_f"; done
    { grep -E '^OEC_[A-Z]+=[0-9]{1,3}$' "$SYS/settings.conf" 2>/dev/null | tr '\n' ';' | sed 's/;$//; s/^./* &/; /^\* /!d'; echo
      ap_nod=1; appprof_oec_density && ap_nod=0
      [ $# -gt 0 ] && awk -v nod=$ap_nod 'FNR == 1 { src = (FILENAME ~ /settings\.apps$/) ? "u" : "b" }
         /^[ \t]*(#|$)/ { next }
         { sub(/[ \t]*#.*/, ""); p = $1; if (!(p in seen)) { seen[p] = 1; ord[++n] = p }
           m = split($2, a, ";")
           for (i = 1; i <= m; i++) if (a[i] ~ /^(OEC_[A-Z]+=[0-9]+|DENSITY=[0-9a-z]+)$/) {
               k = a[i]; sub(/=.*/, "", k); v = substr(a[i], length(k) + 2)
               if (k == "DENSITY" && (src == "u" || nod)) continue
               val[p, k] = v; if (!((p, k) in has)) { has[p, k] = 1; keys[p] = keys[p] " " k } } }
         END { for (i = 1; i <= n; i++) { p = ord[i]; if (keys[p] == "") continue
               c = split(substr(keys[p], 2), ks, " "); out = ""
               for (j = 1; j <= c; j++) out = out (out == "" ? "" : ";") ks[j] "=" val[p, ks[j]]
               print p, out } }' "$@" 2>/dev/null; } | grep -v '^$' > $X/appoec.tmp
    chmod 644 $X/appoec.tmp 2>/dev/null; mv -f $X/appoec.tmp $X/appoec
    # 自定义 CSS：内置的先放，用户的同名盖掉
    mkdir -p $X/oec.tmp && rm -f $X/oec.tmp/*.css
    for ap_d in "$VD/boot/oec" "$SYS/oec"; do [ -d "$ap_d" ] && for ap_f in "$ap_d"/*.css; do
        [ -f "$ap_f" ] && [ "$(wc -c < "$ap_f")" -le 16384 ] && cp "$ap_f" $X/oec.tmp/ 2>/dev/null; done; done
    chmod 755 $X/oec.tmp; chmod 644 $X/oec.tmp/*.css 2>/dev/null; rm -rf $X/oec; mv -f $X/oec.tmp $X/oec
    # “应用优化”页（VeraHome）的选项：KEY|标题|标签=值,...（页面自己加“跟随”）
    mkdir -p $X/appknobs.d
    printf '%s\n' \
        'OEC_DPI|显示大小（只对这个应用）|系统=0,自动（多看算法）=1,小 260=260,中 300=300,大 340=340,特大 380=380' \
        'OEC_FLING|惯性滑动|有动画=0,直接跳到停止位置=1' \
        'OEC_SMOOTH|平滑滚动动画|有=0,关（直接跳）=1' \
        'OEC_PAGEKEY|翻页键|翻页键=0,当音量键=1,模拟滑动=2' \
        'OEC_TOAST|提示框（Toast）黑字白底|关=0,开=1' \
        'OEC_LIGHTBAR|系统栏浅色|关=0,开=1' \
        'OEC_DLGBORDER|对话框加黑边|关=0,开=1' \
        'OEC_WBOLD|网页文字加粗|关=0,开=1' \
        'OEC_WBLACK|网页文字纯黑|关=0,开=1' \
        'OEC_WFONT|网页字号|100%=100,115%=115,130%=130,150%=150,175%=175' \
        'OEC_WCSS|网页自定义样式（oec/包名.css）|关=0,开=1' > $X/appknobs.d/oec.tmp && mv -f $X/appknobs.d/oec.tmp $X/appknobs.d/oec
    appknobs_join
}

# 桌面“应用优化”页的宿主选项由几个模块各自声明：每个模块写 $X/appknobs.d/<模块>，这里合成 $X/appknobs（桌面只读这一个文件）。
# 还直接写 $X/appknobs 的模块（老写法）的行留着，同一个键以 appknobs.d 里的为准；OEC_* 只来自 appknobs.d/oec。
appknobs_join() {
    [ -f $X/appknobs ] || [ -d $X/appknobs.d ] || return 0
    mkdir -p $X/appknobs.d
    awk -F'|' 'FNR == 1 { inold = (FILENAME == old) }
               /^[ \t]*(#|$)/ { next }
               inold && $1 ~ /^OEC_/ { next }
               !($1 in seen) { seen[$1] = 1; print }' old=$X/appknobs $X/appknobs.d/* $X/appknobs > $X/appknobs.tmp 2>/dev/null
    chmod 644 $X/appknobs.tmp 2>/dev/null; mv -f $X/appknobs.tmp $X/appknobs
}
