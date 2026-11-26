# 应用瘦身（per-app component slimming）。host/pw6、host/k49 两份完全相同（tests/appslim-test.sh 核对）。
#
# 大应用（番茄小说之类）自带一堆和读书无关的后台部件：字节 / 小米 / 华为 / vivo / OPPO 推送、保活“守护”服务、小游戏 / 小程序容器、
# 直播、插件下载、日志上传。在 512 MB 的 Kindle 上它们一起起来，冷启动慢、内存被挤到 zram、前台读书卡。
# 这里只做系统允许用户自己做的事：把这些**组件**设成停用（PackageManager.setComponentEnabledSetting，和 pm disable 一样），
# 不改 APK、不重新打包、不调应用自己的私有接口。随时可以恢复原样（组件回到停用之前的状态）。
#
# 清单：$VD/boot/appslim.conf（随版本更新，只读），一行一条，# 后面是注释：
#   <包名> <versionCode 范围> @default=lowmem|on|off     这个应用的默认：lowmem = 只在 < 700 MB 的机型上默认打开
#   <包名> <versionCode 范围> <组件类名>                  要停用的组件（服务 / 接收器 / 内容提供器；完整类名）
#   范围：74532 | 70000-79999 | 74532- | -79999 | *（不限）。不在范围里的版本：这个应用什么都不做（已停用的恢复原样）。
#   新版本里已经没有的组件：系统拒绝（类不存在），跳过、不记、不报错。
# 用户选择：桌面“应用优化”页的“后台瘦身”（存进 settings.apps：SLIM=1 开 / SLIM=0 关 = 恢复原样；“跟随”= 用清单的默认）；
#   settings.conf 里 APP_SLIM=0 = 全部关掉（所有应用恢复原样，排查用），APP_SLIM=auto（默认）= 按上面的选择。
# 记录：$SYS/appslim/<包名>.list —— 我们停用的组件和停用之前的状态（"<类名> <0|1>"）。恢复时只动这里记着的，
#   应用自己本来就停用的组件不碰。卸载了的应用：记录删掉。
# 什么时候做：开机后（afterboot）、宿主装完 / 更新完应用（cmd install）、应用自己更新了（watchdog 每 20 秒看 packages.xml 的版本号，
#   变了才做）、用户在“应用优化”里改了（appprof_set）。每次只改状态不对的组件（get 一次，不一样才 set），重复做什么都不变。
# 怎么调：容器里的 service call package（IPackageManager，Android 7.1.1：87 = getComponentEnabledSetting，86 = setComponentEnabledSetting），
#   一个 nsenter 里跑完整张表（不起 app_process；pm 每条要 1 秒多）。flags = DONT_KILL_APP：正在跑的应用不被杀，下次启动生效。
#   状态：0 默认（按清单）、1 开、2 关。返回的第一个字不是 00000000 = 出错（类不存在 / 包不存在）。
APPSLIM_CONF="$VD/boot/appslim.conf"
APPSLIM_DIR="$SYS/appslim"
APPSLIM_GET=87
APPSLIM_SET=86

appslim_valid_pkg() { case "$1" in ''|*[!A-Za-z0-9._]*) return 1 ;; esac; [ ${#1} -le 200 ]; }

# 清单里出现的包
appslim_pkgs() { awk '/^[ \t]*(#|$)/ { next } { print $1 }' "$APPSLIM_CONF" 2>/dev/null | sort -u; }

# 装着的版本号（packages.xml，7.1 是文本 XML；没装 = 空）
appslim_vc() {
    sed -n "s#.*<package name=\"$1\" .* version=\"\([0-9]*\)\".*#\1#p" "$SYS/data/system/packages.xml" 2>/dev/null | head -n 1
}

# 系统现在认的版本号（dumpsys，约 1 秒；刚装完 packages.xml 要过几秒才写盘，做的时候用这个）
appslim_vc_live() { adb_sh "dumpsys package $1" 2>/dev/null | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -n 1; }

# 清单里这个包、这个版本的行（第 3 列起）：appslim_rows <包> <版本号>
appslim_rows() {
    awk -v p="$1" -v vc="$2" '
        /^[ \t]*(#|$)/ { next }
        { sub(/[ \t]*#.*/, "") }
        $1 != p || NF < 3 { next }
        { r = $2; lo = 0; hi = 2147483647
          if (r != "*") {
              if (r !~ /^[0-9]*-?[0-9]*$/ || r == "-") next
              if (index(r, "-")) { a = substr(r, 1, index(r, "-") - 1); b = substr(r, index(r, "-") + 1); if (a != "") lo = a + 0; if (b != "") hi = b + 0 }
              else { lo = r + 0; hi = r + 0 }
          }
          if (vc + 0 >= lo && vc + 0 <= hi) print $3 }' "$APPSLIM_CONF" 2>/dev/null
}

# 清单的默认（on / off）；这个版本不在清单里 = 空
appslim_default() {
    ad_d=$(appslim_rows "$1" "$2" | sed -n 's/^@default=//p' | tail -n 1)
    [ -n "$ad_d" ] || { appslim_rows "$1" "$2" | grep -q '^[A-Za-z]' && ad_d=off; }
    case "$ad_d" in
        on) echo on ;;
        lowmem) if lowmem_ram 2>/dev/null; then echo on; else echo off; fi ;;
        '') ;;
        *) echo off ;;
    esac
}

# 用户在“应用优化”里选的（合并表 $X/appprof 里这个包的 SLIM；没选 = 空）
appslim_user() {
    [ -s $X/appprof ] || { type appprof_publish >/dev/null 2>&1 && appprof_publish; }
    awk -v p="$1" '$1 == p { n = split($3, a, ";"); for (i = 1; i <= n; i++) if (a[i] ~ /^SLIM=[01]$/) { print substr(a[i], 6); exit } }' $X/appprof 2>/dev/null
}

# 这个包现在该不该瘦身：on / off。$2 = 版本号
appslim_want() {
    [ "${APPSLIM_FORCE_OFF:-0}" = 1 ] && { echo off; return 0; }
    case "${APP_SLIM:-auto}" in 0|off) echo off; return 0 ;; esac
    aw_d=$(appslim_default "$1" "$2")
    [ -n "$aw_d" ] || { echo off; return 0; }        # 这个版本不在清单里：不动
    case "$(appslim_user "$1")" in 1) echo on ;; 0) echo off ;; *) echo "$aw_d" ;; esac
}

# 在容器里批量 get / set：标准输入 "g <类>" / "s <类> <状态>"，输出 "<类> <结果码> <值>"（结果码 00000000 = 成功）
appslim_pm() {
    ap_pref=$(vd_in) || return 1
    [ -n "$ap_pref" ] || return 1
    vd_timeout 300 $ap_pref /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; p=$1
        while read -r op c s; do
            case "$op" in
                g) r=$(service call package '$APPSLIM_GET' i32 1 s16 "$p" s16 "$c" i32 0 2>&1) ;;
                s) r=$(service call package '$APPSLIM_SET' i32 1 s16 "$p" s16 "$c" i32 "$s" i32 1 i32 0 2>&1) ;;
                *) continue ;;
            esac
            set -- $(echo "$r" | sed -n "s/^Result: Parcel(\([0-9a-f]*\) *\([0-9a-f]*\).*/\1 \2/p" | head -n 1)
            echo "$c ${1:-err} ${2:-0}"; set -- "$p"
        done' sh "$1"
}

# 同一时间只做一份（开机、装完、watchdog、设置页可能同时触发：两份一起跑会把同一批组件各 set 一遍）。
# 锁是目录 $W/appslim.lock（mkdir 原子）；拿锁的进程没了 / 超过 10 分钟当作残留。appslim_lock 等最多 $1 秒（0 = 不等）。
appslim_lock() {
    [ "${APPSLIM_HELD:-0}" = 1 ] && return 0
    al_n=0
    while ! mkdir $W/appslim.lock 2>/dev/null; do
        al_p=$(cat $W/appslim.lock/pid 2>/dev/null)
        if [ -n "$al_p" ] && ! kill -0 "$al_p" 2>/dev/null; then rm -rf $W/appslim.lock; continue; fi
        [ -n "$(find $W/appslim.lock -maxdepth 0 -mmin +10 2>/dev/null)" ] && { rm -rf $W/appslim.lock; continue; }
        [ $al_n -ge "${1:-120}" ] && return 1
        sleep 2; al_n=$((al_n + 2))
    done
    { read -r al_me _ < /proc/self/stat; } 2>/dev/null; echo "${al_me:-$$}" > $W/appslim.lock/pid
    APPSLIM_HELD=1
}
appslim_unlock() { rm -rf $W/appslim.lock; APPSLIM_HELD=0; }

# 这个包按现在的选择做一遍（幂等）：appslim_apply <包> [原因]
appslim_apply() {
    if [ "${APPSLIM_HELD:-0}" != 1 ]; then
        appslim_lock 120 || { t "appslim: $1 skipped, another run is still busy"; return 0; }
        appslim_apply "$@"; appslim_unlock; return 0
    fi
    as_p=$1; as_why=${2:-}
    appslim_valid_pkg "$as_p" || return 0
    mkdir -p "$APPSLIM_DIR" 2>/dev/null
    as_l="$APPSLIM_DIR/$as_p.list"
    # Android 没在跑（停止途中 / 冻结前）：什么都不做，记录原样留着（不能当成“恢复失败”丢掉）
    vd_in >/dev/null 2>&1 || return 0
    [ "$(adb_sh 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r\n')" = 1 ] || return 0
    as_vc=$(appslim_vc_live "$as_p")
    if [ -z "$as_vc" ]; then
        # packages.xml 正在被系统重写（有 packages-backup.xml）/ 读不到：不当成卸载
        [ -s "$SYS/data/system/packages.xml" ] && [ ! -f "$SYS/data/system/packages-backup.xml" ] || return 0
        [ -f "$as_l" ] && { rm -f "$as_l"; t "appslim: $as_p not installed, record dropped"; }
        appslim_knobs; return 0
    fi
    as_w=$(appslim_want "$as_p" "$as_vc")
    as_want=; [ "$as_w" = on ] && as_want=$(appslim_rows "$as_p" "$as_vc" | grep '^[A-Za-z]' | sort -u)
    # 这次要停用的组件：先查现在的状态
    as_q=$(printf '%s\n' "$as_want" | grep . | sed 's/^/g /')
    as_cur=; [ -n "$as_q" ] && as_cur=$(printf '%s\n' "$as_q" | appslim_pm "$as_p")
    # 1) 记录里有、这次不要了的（关掉瘦身 / 清单改了 / 换了版本）：恢复成停用之前的状态
    as_rest=; as_keep=
    if [ -s "$as_l" ]; then
        while read -r as_c as_s; do
            [ -n "$as_c" ] || continue
            if printf '%s\n' "$as_want" | grep -qxF "$as_c"; then as_keep="$as_keep$as_c $as_s
"
            else case "$as_s" in 1) ;; *) as_s=0 ;; esac; as_rest="${as_rest}s $as_c $as_s
"; fi
        done < "$as_l"
    fi
    as_nr=0; as_out=
    [ -n "$as_rest" ] && as_out=$(printf '%s' "$as_rest" | appslim_pm "$as_p") && as_nr=$(printf '%s\n' "$as_out" | grep -c ' 00000000 ')
    # 2) 要停用、现在不是“关”的：记下原来的状态，设成关
    as_set=; as_new=
    if [ -n "$as_cur" ]; then
        as_set=$(printf '%s\n' "$as_cur" | awk '$2 == "00000000" && ($3 + 0) != 2 { print "s", $1, 2 }')
        as_new=$(printf '%s\n' "$as_cur" | awk '$2 == "00000000" && ($3 + 0) != 2 { print $1, ($3 + 0 == 1 ? 1 : 0) }')
    fi
    as_nd=0; as_miss=0; as_ok=
    if [ -n "$as_set" ]; then
        # 先把要停用的连同原来的状态记下来，再去停用：中途被打断（停止 VeraDroid、断电）时，记录里有的最多是“其实还没停用”的，
        # 恢复时按原状态再设一遍也没有坏处；反过来先停用后记录，被打断就会留下没人记得要恢复的组件
        { printf '%s' "$as_keep"; printf '%s\n' "$as_new" | while read -r as_c as_s; do
              [ -n "$as_c" ] && ! printf '%s' "$as_keep" | grep -q "^$as_c " && echo "$as_c $as_s"; done; } | grep . > "$as_l.tmp" && mv -f "$as_l.tmp" "$as_l"
        as_out=$(printf '%s\n' "$as_set" | appslim_pm "$as_p")
        as_ok=$(printf '%s\n' "$as_out" | awk '$2 == "00000000" { print $1 }')
        as_miss=$(printf '%s\n' "$as_out" | grep -vc ' 00000000 ')
        as_nd=$(printf '%s\n' "$as_ok" | grep -c .)
    fi
    # 记录：原来记着、还要的（保留原来的“之前状态”）+ 这次新停用的；已经是关、又不在记录里的（应用自己关的）不记
    { printf '%s' "$as_keep"; printf '%s\n' "$as_new" | while read -r as_c as_s; do
          [ -n "$as_c" ] && printf '%s\n' "$as_ok" | grep -qxF "$as_c" && ! printf '%s' "$as_keep" | grep -q "^$as_c " && echo "$as_c $as_s"; done; } | grep . > "$as_l.tmp"
    if [ -s "$as_l.tmp" ]; then mv -f "$as_l.tmp" "$as_l"; else rm -f "$as_l.tmp" "$as_l"; fi
    as_tot=0; [ -s "$as_l" ] && as_tot=$(grep -c . "$as_l")
    t "appslim: $as_p $as_vc ${as_why:+($as_why) }$as_w: $as_tot off ($as_nd newly disabled, $as_nr restored$([ "$as_miss" -gt 0 ] 2>/dev/null && echo ", $as_miss not in this version"))"
    appslim_bloat_plugins "$as_p" "$as_w"
    appslim_knobs
    return 0
}

# 修正由宿主创建的私有子目录属主（不能留 root:root 0755 阻塞应用写入合法插件）
appslim_fix_dir() {
    [ -n "$1" ] && [ -d "$1" ] && [ -n "$2" ] && [ -d "$2" ] || return 0
    af_ug=$(stat -c '%u:%g' "$1" 2>/dev/null) || af_ug=
    [ -n "$af_ug" ] || af_ug=$(ls -ldn "$1" 2>/dev/null | awk '$3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { print $3 ":" $4 }')
    [ -n "$af_ug" ] && [ "$af_ug" != "0:0" ] || return 0
    af_dug=$(stat -c '%u:%g' "$2" 2>/dev/null) || af_dug=
    [ -n "$af_dug" ] || af_dug=$(ls -ldn "$2" 2>/dev/null | awk '$3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { print $3 ":" $4 }')
    if [ "$3" = new ] || [ "$af_dug" = "0:0" ]; then
        chown "$af_ug" "$2" 2>/dev/null && chmod 700 "$2" 2>/dev/null || true
    fi
}

# 清理并只读阻断与阅读无关的动态下发大插件（不改 APK、不碰账号/书架/阅读核心；关掉瘦身时删掉占位文件即可恢复）
appslim_bloat_plugins() {
    case "$1" in
        com.dragon.read)
            ab_root="$SYS/data/data/com.dragon.read/files"
            ab_mk="plugins"
            ab_list="plugins/com.dragon.read.plugin.awemeim plugins/com.dragon.read.plugin.appbrand plugins/com.dragon.read.plugin.clientai plugins/com.dragon.read.plugin.ecomsearch plugins/com.dragon.read.plugin.player plugins/com.dragon.read.plugin.offlinetts plugins/com.dragon.read.plugin.awemevideo plugins/com.dragon.read.plugin.im luckycat_gecko_root_dir" ;;
        net.cpwxw.cpfiction)
            ab_root="$SYS/data/data/net.cpwxw.cpfiction"
            ab_mk="files"
            ab_list="app_baidu_sdk_remote app_e_qq_com_dex app_e_qq_com_plugin app_qm app_sodler ksad_dynamic kwad_ex files/p_plugins files/lywm files/__xadsdk__remote__final__running__.dex" ;;
        *) return 0 ;;
    esac
    [ -d "$ab_root" ] || return 0
    if [ "$2" = on ]; then
        if [ -n "$ab_mk" ]; then
            ab_new=
            [ -d "$ab_root/$ab_mk" ] || { mkdir -p "$ab_root/$ab_mk" 2>/dev/null && ab_new=new; }
            appslim_fix_dir "$ab_root" "$ab_root/$ab_mk" "$ab_new"
        fi
        ab_n=0
        for ab_rel in $ab_list; do
            ab_t="$ab_root/$ab_rel"
            if [ -d "$ab_t" ] || [ ! -e "$ab_t" ]; then
                rm -rf "$ab_t" 2>/dev/null
                touch "$ab_t" 2>/dev/null && chmod 000 "$ab_t" 2>/dev/null && ab_n=$((ab_n + 1))
            fi
        done
        if [ "$1" = "net.cpwxw.cpfiction" ]; then
            for ab_t in "$ab_root"/app_e_qq_com_*; do
                [ -d "$ab_t" ] && rm -rf "$ab_t" 2>/dev/null && ab_n=$((ab_n + 1))
            done
        fi
        [ "$ab_n" -gt 0 ] && t "appslim: $1 blocked $ab_n non-reading dynamic plugins"
    else
        for ab_rel in $ab_list; do
            ab_t="$ab_root/$ab_rel"
            [ -f "$ab_t" ] && [ ! -s "$ab_t" ] && rm -f "$ab_t" 2>/dev/null
        done
        [ -n "$ab_mk" ] && appslim_fix_dir "$ab_root" "$ab_root/$ab_mk"
    fi
    return 0
}

# 清单里的包 + 有记录的包都做一遍
appslim_all() {
    appslim_lock "${2:-120}" || { t "appslim: ($1) skipped, another run is still busy"; return 1; }
    for aa_p in $( { appslim_pkgs; ls "$APPSLIM_DIR" 2>/dev/null | sed -n 's/\.list$//p'; } | sort -u); do
        appslim_apply "$aa_p" "$1"
    done
    appslim_sig > $W/appslim.sig 2>/dev/null
    appslim_unlock
    return 0
}

# 清单里各个包装着的版本（变了 = 装了 / 更新了 / 卸载了）
appslim_sig() { for as_p in $(appslim_pkgs); do echo "$as_p $(appslim_vc "$as_p")"; done; }

# watchdog 每 20 秒：packages.xml 变了才比较版本号（应用自己更新、别的途径安装的也能赶上）
appslim_tick() {
    [ -f $W/booted ] || return 0
    [ -d $W/appslim.lock ] && return 0                 # 正在做（开机那一遍还没完）：下次再看
    [ -f $W/appslim.sig ] || return 0                  # 开机那一遍还没做过
    at_m=$(stat -c %Y "$SYS/data/system/packages.xml" 2>/dev/null) || return 0
    [ "$at_m" = "$(cat $W/appslim.mtime 2>/dev/null)" ] && return 0
    at_s=$(appslim_sig)
    [ "$at_s" = "$(cat $W/appslim.sig 2>/dev/null)" ] && { echo "$at_m" > $W/appslim.mtime; return 0; }
    ( appslim_all update 0 && echo "$at_m" > $W/appslim.mtime ) &
    return 0
}

# “应用优化”页上的选项（只给清单里有、而且装着的应用显示）：$X/appknobs.pkg，一行 "<包名> 键|标题|标签=值,..."
# （VeraHome 的 AppDisplayActivity 按包名挑；老桌面不认这个文件，不显示）
appslim_knobs() {
    [ -n "$X" ] && [ -d "$X" ] || return 0
    for ak_p in $(appslim_pkgs); do
        ak_vc=$(appslim_vc "$ak_p"); [ -n "$ak_vc" ] || continue
        ak_d=$(appslim_default "$ak_p" "$ak_vc"); [ -n "$ak_d" ] || continue
        ak_n=0; [ -s "$APPSLIM_DIR/$ak_p.list" ] && ak_n=$(grep -c . "$APPSLIM_DIR/$ak_p.list")
        if [ "$ak_n" -gt 0 ]; then ak_st="现在停用了 $ak_n 个"; else ak_st="现在没有停用"; fi
        ak_df=关; [ "$ak_d" = on ] && ak_df=开
        echo "$ak_p SLIM|后台瘦身：停掉推送、保活、日志上传等自己另开进程的后台部件，省内存、少占 CPU（不改应用本身；${ak_st}；跟随 = 本机默认${ak_df}）|开=1,关（恢复原样）=0"
    done > $X/appknobs.pkg.tmp 2>/dev/null
    chmod 644 $X/appknobs.pkg.tmp 2>/dev/null; mv -f $X/appknobs.pkg.tmp $X/appknobs.pkg 2>/dev/null
    return 0
}

# 命令（cmd.sh）：appslim <包> | all | restore（全部恢复原样，不改设置：下次开机按设置再做）
appslim_cmd() {
    case "$1" in
        all) appslim_all cmd ;;
        restore)
            for ar_p in $(ls "$APPSLIM_DIR" 2>/dev/null | sed -n 's/\.list$//p'); do APPSLIM_FORCE_OFF=1; appslim_apply "$ar_p" restore; APPSLIM_FORCE_OFF=0; done
            for ar_p in com.dragon.read net.cpwxw.cpfiction; do appslim_bloat_plugins "$ar_p" off; done ;;
        *) appslim_valid_pkg "$1" && appslim_apply "$1" cmd ;;
    esac
}
