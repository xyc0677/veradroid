# SPDX-License-Identifier: GPL-3.0-or-later
# 应用预编译：新装的应用只做了很少的编译（interpret-only / verify），冷启动慢一倍多
# （微信读书 PW6 实测 10.6 s → speed 编译后 4.5 s）。装完以后在后台、低优先级、一次一个地用
#   cmd package compile -m speed -f <包>
# 把它编好。触发：宿主 install 命令、after_boot 装 APK、Android 里自己装 / 更新的应用（看门狗发现 packages.list 变了），
# 以及命令 `compile all|<包>`。电量低（< 20 % 且没充电）、内存紧张、Android 冻着时不编，等条件好了再继续。
#
# 进度写在 $X/ctl/compile.status（KEY=value，一行一个；桌面读它显示“正在优化 微信读书 (2/5)”，名字由桌面按包名查）：
#   state=idle|running|waiting|paused   pkg=<正在编的包>   index=<第几个>   total=<这一批共几个>
#   reason=battery|memory|frozen|off|foreground|busy（waiting / paused 时）；foreground / busy 时 reason 直接是中文（老桌面原样显示），机器读 code=   last=<包> ok|fail <秒>
#   msg=<给用户看的一句话>（有就直接显示；老桌面不认 foreground / busy，新桌面优先用它）
#   code=starting|nothing|queued|install|frozen|battery|busy|memory|running|foreground|aot（机器读：现在在等什么）
#   manual=1（用户点了“立即优化”，见 compile_manual）  time=<写这一行的时间，秒>
# 设置 APP_COMPILE=auto|speed|off（settings.sh）：auto = 957 MB 的机型 speed，512 MB 机型见 compile_mode。
#
# 什么时候编（0.89 用户诊断：系统更新后微信读书的 odex 失效，宿主在用户正读书时给微信读书本身重编 357 秒——dex2oat 173 MB
# + 微信读书 221 MB，MemAvailable 掉到 zram 换页、system_server lowmem、微信读书的 :seg 服务 ANR、WindowManager 锁等了 8.9 秒）：
# - 前台的应用（$X/fg，VeraHome 的 HostService 在任务栈一变时写）不编：跳过它先编别的，它离开前台再编。
#   编着的时候它到了前台：马上停掉 dex2oat（和给安装让路一样），重新排队。
# - 用户在用（COMPILE_IDLE_S 秒内摸过屏：action.sh 的 poke 每 20 秒最多 touch 一次 $W/lastinput）：dex2oat 单线程，
#   可用内存不到 COMPILE_ACTIVE_MB（1 GB 机型 400，512 MB 机型 200）不开始；编着时掉到 COMPILE_ACTIVE_ABORT_MB
#   （200 / 100）以下就停掉重排，之后等用户不用了（空闲）再开始。
# - 充电 + 空闲：dex2oat 用系统默认线程数（最快）；其余情况一律单线程。
# - 屏保时 Android 冻着，编不了（sleep.sh 冻结整个容器）；不为编译去解冻或推迟屏保，醒来后接着编。
# 过滤器仍用 speed（不是 speed-profile）：7.1 的 speed-profile 只编 JIT 记下的热点方法，odex 小、编得快，但更新后刚启动的应用
# 还没有档案（profile），编出来几乎等于 interpret-only；阅读类应用翻页 / 打开书的耗时 speed 实测更好。
# 代价（编得久、内存大）由上面的调度挡住，不在过滤器上省。
#
# 是否已经编好：直接在宿主上读 odex 的 OAT 头（key-value 区里的 compiler-filter），不用为一次查询起 JVM，冻结时也能读。
CQ=$W/compile.queue
CST=$X/ctl/compile.status
CLOG=$SYS/data/.vd-compile.log     # 结果记录（包 过滤器 秒数 结果），也用来避免对同一个安装反复失败重试

# 当前这个安装（/data/app/<包>-N）的 odex 用的编译过滤器；没有 odex 时输出空
odex_filter() { # pkg
    for d in $SYS/data/app/$(app_dir "$1"); do
        [ -d "$d" ] || continue
        for o in "$d"/oat/arm/base.odex "$d"/oat/arm64/base.odex; do
            [ -f "$o" ] || continue
            # OAT 头在 ELF 的 oatdata 段开头（PW6 / KT6 的 7.1 镜像实测在 0x1000），key-value 区是 \0 分隔的字符串对
            dd if="$o" bs=4096 count=4 2>/dev/null | tr '\0' '\n' 2>/dev/null | sed -n '/^compiler-filter$/{n;p;q;}'
            return 0
        done
    done
}
# 当前安装的代码目录（/data/app/<包>-N 的 N），用来区分“同一个安装编译失败过”和“已经更新成新版本”
# 有两个目录时（安装 / 更新途中 Android 被停、Kindle 重启，旧的 <包>-1 没删掉：模拟器 2026-11-20）用 packages.xml 里的 codePath，
# 不然编译、查 odex、保住 odex（compile_keep_*）都会对着那个没人用的目录
app_dir() {
    ad_n=; ad_f=
    for d in $SYS/data/app/"$1"-*; do [ -d "$d" ] && { ad_n=$((ad_n + 1)); [ -n "$ad_f" ] || ad_f=${d##*/}; }; done
    [ -n "$ad_f" ] || return 1
    if [ "$ad_n" -gt 1 ]; then
        ad_c=$(sed -n "s#.*<package name=\"$1\" codePath=\"/data/app/\([^\"/]*\)\".*#\1#p" $SYS/data/system/packages.xml 2>/dev/null | head -n 1)
        [ -n "$ad_c" ] && [ -d "$SYS/data/app/$ad_c" ] && ad_f=$ad_c
    fi
    echo "$ad_f"
}
# 数据区里装的第三方应用（系统应用已经在镜像里预编译）
data_pkgs() {
    for d in $SYS/data/app/*-[0-9]*; do
        [ -d "$d" ] || continue
        p=${d##*/}; p=${p%-*}
        case "$p" in *[!A-Za-z0-9._]*|'') continue ;; esac
        echo "$p"
    done | sort -u
}

# APP_COMPILE 设置 → 实际用的过滤器（空 = 不编）。
# 512 MB 机型（KT5 / KT6 / PW5）：kt6-5192 模拟器实测 speed 编微信读书能完成、没有被杀，但 dex2oat 占 ~200 MB，
# 期间 zram 换出很多；所以只在内存够的时候开始（compile_mem_ok），并把 dex2oat 限成单线程（compile_one）。
compile_mode() {
    case "${APP_COMPILE:-auto}" in
        off) echo "" ;;
        speed|auto) echo speed ;;
        *) echo speed ;;
    esac
}
# 编好了没有：$2 = 这个包要的过滤器（compile_pkg_mode；不给就是 speed）。要 speed-profile 的，speed 也算；要 interpret-only 的，有 odex 就算
compile_done_filter() { # filter [target]
    case "${2:-speed}" in
        speed-profile) case "$1" in speed-profile|speed|everything) return 0 ;; esac ;;
        interpret-only|verify-at-runtime) [ -n "$1" ] && return 0 ;;
        *) case "$1" in speed|everything) return 0 ;; esac ;;
    esac
    return 1
}
# 特别大的应用（2026-11-19）：番茄免费小说 7.4.5 的 dex 243 MB（微信读书 67 MB），PW6 上 speed 编不完（两台测试机都是
# 543 s 超时），dex2oat 占 600 多 MB（测试机：RSS 330 + 交换 355 MB），交换区用完、lmk 把常驻的微信读书杀掉。这类应用不编 speed：
# 用户用过、有 JIT 档案了编 speed-profile（测试机实测番茄 194 s），没有档案就留在安装时的 interpret-only；speed-profile 也超时过的只要
# interpret-only。判断：apk 里 classes*.dex 合计 ≥ COMPILE_BIG_DEX_MB（默认 120），或者这个包（任何一次安装）speed 超时过。
# 而且只在前台没有别的应用时编（桌面在前台 / 屏幕没人用），编着时有应用到了前台就停（busy，等空闲再编）：不为它挤掉正在读的应用。
compile_dex_mb() { # pkg -> MB（读不出来返回 1）；按安装目录 + base.apk 时间缓存在 $W
    cdm_d=$(app_dir "$1") || return 1
    cdm_a=$SYS/data/app/$cdm_d/base.apk
    [ -f "$cdm_a" ] || return 1
    cdm_k="$cdm_d $(stat -c %Y "$cdm_a" 2>/dev/null)"; cdm_c=$W/compile.dexmb.$1
    if [ -f "$cdm_c" ] && [ "$(head -n 1 "$cdm_c")" = "$cdm_k" ]; then sed -n 2p "$cdm_c"; return 0; fi
    cdm_m=
    if command -v unzip >/dev/null 2>&1; then
        cdm_m=$(unzip -l "$cdm_a" 2>/dev/null | awk '$1 ~ /^[0-9]+$/ && $NF ~ /^classes[0-9]*\.dex$/ { s += $1; n++ } END { if (n) print int(s / 1048576) }')
    fi
    case "$cdm_m" in ''|*[!0-9]*)
        # 宿主没有 unzip（或 ZIP64 大包 unzip -l 读不出来）时按 APK 体积保守兜底：
        # 多 dex 大应用（番茄小说等）APK >= COMPILE_BIG_APK_MB（默认 80 MB）时未压缩 dex 往往 > 120 MB
        cdm_apk=$( { stat -c %s "$cdm_a" 2>/dev/null || wc -c < "$cdm_a" 2>/dev/null; } | awk '$1 ~ /^[0-9]+$/ { print int($1 / 1048576) }')
        case "$cdm_apk" in ''|*[!0-9]*) return 1 ;; esac
        [ "$cdm_apk" -ge "${COMPILE_BIG_APK_MB:-80}" ] || return 1
        cdm_m=$((cdm_apk * 3 / 2))
        [ "$cdm_m" -lt "${COMPILE_BIG_DEX_MB:-120}" ] && cdm_m=${COMPILE_BIG_DEX_MB:-120}
        echo "$cdm_m"
        return 0 ;;
    esac
    printf '%s\n%s\n' "$cdm_k" "$cdm_m" > "$cdm_c" 2>/dev/null
    echo "$cdm_m"
}
compile_timed_out() { # pkg filter：这个包（任何一次安装）用这个过滤器超时过
    [ -f $CLOG ] && awk -v p="$1" -v m="$2" '$1 == p && $3 == "timeout" && $4 == m { f = 1 } END { exit !f }' $CLOG
}
compile_big() { # pkg
    cb_m=$(compile_dex_mb "$1") && [ "$cb_m" -ge ${COMPILE_BIG_DEX_MB:-120} ] && return 0
    compile_timed_out "$1" speed
}
compile_has_profile() { [ -s $SYS/data/misc/profiles/cur/0/$1/primary.prof ] || [ -s $SYS/data/misc/profiles/ref/$1/primary.prof ]; }
# speed-profile 编译开始时 installd 把当前档案并进参考档案、清空当前档案；dex2oat 被停掉（前台来了应用 / 超时）以后两份都成了 0 字节
# （测试机上番茄：22:56 停掉以后 cur / ref 都空了，之后只能补 interpret-only，313 s）。编之前存一份，没编成、档案又空了就放回去
# （cat 进原文件，属主和权限不变）。
compile_prof_save() { # pkg
    for cps_k in cur ref; do
        cps_f=$SYS/data/misc/profiles/$cps_k/$1/primary.prof; [ $cps_k = cur ] && cps_f=$SYS/data/misc/profiles/cur/0/$1/primary.prof
        if [ -s "$cps_f" ]; then cat "$cps_f" > $W/compile.prof.$cps_k.$1 2>/dev/null; else rm -f $W/compile.prof.$cps_k.$1; fi
    done
}
compile_prof_back() { # pkg：编成了（odex 是 speed-profile）就只删存的那份
    for cpb_k in cur ref; do
        cpb_f=$SYS/data/misc/profiles/$cpb_k/$1/primary.prof; [ $cpb_k = cur ] && cpb_f=$SYS/data/misc/profiles/cur/0/$1/primary.prof
        if [ -s $W/compile.prof.$cpb_k.$1 ] && [ "$(odex_filter "$1")" != speed-profile ] && [ -f "$cpb_f" ] && [ ! -s "$cpb_f" ]; then
            cat $W/compile.prof.$cpb_k.$1 > "$cpb_f" 2>/dev/null && t "compile: $1 profile ($cpb_k) put back after the stopped speed-profile compile"
        fi
        rm -f $W/compile.prof.$cpb_k.$1
    done
}
# 这个包实际用的过滤器（$2 = 设置给的，speed）
# 512 MB 机型上的大应用：只编 verify-at-runtime（COMPILE_BIG_LOW=off 恢复以前的 interpret-only / speed-profile）。
# PW5 5.16.2 模拟器（485 MB，2026-11-21）番茄 7.4.5（243 MB dex）：
#   安装时的 interpret-only：dex2oat 占到 455 MB（RSS 130 + 交换 325），交换区用完、88 次 page allocation failure，
#     600 s 还没编完被停，结果一个 odex 也没有；
#   没有 odex 时打开它：dex 整个解压进应用自己的匿名内存，主进程 RSS 143 + 交换 190 MB，可用内存掉到 20–60 MB，
#     2 分钟里 40 次分配失败、1024 次交换区写入失败（用户诊断里“番茄卡死、点什么都没反应”的样子）；
#   verify-at-runtime：74 s 编完，dex2oat 峰值 256 MB，交换区几乎没动、0 次失败；之后打开番茄 dex 从 odex 里映射
#     （97 MB 是可以随时丢掉再读回的文件页），可用内存一直在 225 MB 左右、交换只用 10 MB。
# 代价：类的校验挪到运行时（和没有 odex 时一样），只是不再占匿名内存。
# 缺 unzip（或读不出 dex 大小）的 512 MB 设备上，当前已是 verify-at-runtime 的应用保持原过滤器，绝不升回 455 MB 峰值的 speed
compile_low_big() {
    lowmem_ram && [ "${COMPILE_BIG_LOW:-on}" != off ] || return 1
    compile_big "$1" && return 0
    { ! command -v unzip >/dev/null 2>&1 || [ -n "$(app_dir "$1" 2>/dev/null)" ]; } &&
        ! compile_dex_mb "$1" >/dev/null && [ "$(odex_filter "$1")" = verify-at-runtime ]
}
compile_light_filter() { if compile_low_big "$1"; then echo verify-at-runtime; else echo interpret-only; fi; }
compile_pkg_mode() { # pkg mode
    [ "$2" = speed ] && compile_low_big "$1" && { echo verify-at-runtime; return 0; }
    [ "$2" = speed ] && compile_big "$1" || { echo "$2"; return 0; }
    if compile_has_profile "$1" && ! compile_timed_out "$1" speed-profile; then echo speed-profile; else echo interpret-only; fi
}
# 前台有应用（不是桌面）：大应用不编、编着就停
compile_fg_app() { cfa_f=$(compile_fg); [ -n "$cfa_f" ] && [ "$cfa_f" != com.veradroid.home ]; }
# 这个安装记过几次某个结果（$CLOG 一行："包 目录 结果 过滤器 秒数 时间"）。只算 base.apk 写入之后的记录：卸载再装 / 更新后
# 目录名常常不变（又是 <包>-1），以前这个安装之前的 timeout / capped / fail 记录照样算数，新装的应用就永远不编、一直解释执行
# （测试机 2026-11-19：番茄免费小说 11-17 那次安装的 speed 超时记录挡住了 11-19 重新装的同名目录，冷启动 25-50 s、点开章节转圈几分钟、ANR）。
# date -r 不支持时（时间为空）所有记录都算，和以前一样。
compile_rec() { # pkg dir result [filter]（给了 filter 只算这个过滤器的记录）
    [ -f $CLOG ] || { echo 0; return 0; }
    cr_s=$(date -r "$SYS/data/app/$2/base.apk" +%Y%m%d-%H%M%S 2>/dev/null)
    awk -v p="$1" -v d="$2" -v r="$3" -v s="$cr_s" -v m="$4" '$1 == p && $2 == d && $3 == r && $6 >= s && (m == "" || $4 == m) { n++ } END { print n + 0 }' $CLOG
}
# 这个包要不要编（$2=force 时只要装着就编；stale 绕过 odex_filter 检查，但仍尊重同版本的 crash / fail 上限）
compile_needed() { # pkg [force|stale]
    app_dir "$1" >/dev/null || return 1
    [ "$2" = force ] && return 0
    cn_m=$(compile_pkg_mode "$1" speed)
    [ "$2" = stale ] || { compile_done_filter "$(odex_filter "$1")" "$cn_m" && return 1; }
    # 同一个安装 dex2oat 崩溃过（SIGSEGV/SIGABRT，如加固包）或失败过两次就不再自动试（命令 compile <包> 仍然会强制编）。
    # 超时只挡同一个过滤器：大应用 speed 超时以后改编 speed-profile
    cn_d=$(app_dir "$1")
    [ "$(compile_rec "$1" "$cn_d" crash)" -gt 0 ] && return 1
    [ "$(compile_rec "$1" "$cn_d" fail)" -ge 2 ] && return 1
    [ "$2" = stale ] && return 0
    [ "$(compile_rec "$1" "$cn_d" capped)" -gt 0 ] && return 1
    [ "$(compile_rec "$1" "$cn_d" timeout "$cn_m")" -gt 0 ] && return 1
    return 0
}

compile_status() { # KEY=value...
    [ -d $X/ctl ] || return 0
    { printf '%s\n' "$@"; echo "time=$(date +%s)"; if compile_manual; then echo manual=1; fi; } > $CST.tmp 2>/dev/null && mv -f $CST.tmp $CST
}
# 排队（去重）。$2=force：强制重编
compile_enqueue() { # pkg [force]
    case "$1" in ''|*[!A-Za-z0-9._]*) return 1 ;; esac
    compile_needed "$1" "$2" || return 1
    grep -q "^$1\( \|\$\)" $CQ 2>/dev/null && return 0
    echo "$1${2:+ $2}" >> $CQ
    echo $(( $(cat $W/compile.total 2>/dev/null || echo 0) + 1 )) > $W/compile.total
    t "compile: queued $1${2:+ ($2)}"
}
# 编好的代码失效了的应用。odex 是对着“当时那份启动镜像（boot.art）”编的；系统更新（换了 rootfs、清了 dalvik-cache）以后
# 启动镜像重新生成、重定位到另一个随机地址，旧的 odex 变成 kOatNeedsRelocation：ART 只把它当数据映射（不可执行），
# 整个应用退回解释执行 + JIT，而 OAT 头里的过滤器仍然写着 speed，所以上面的 odex_filter 看不出来。
# PW6 真机（0.88 → 0.89 更新后）实测：5 个第三方应用全部如此，微信读书的 169 MB odex 没有一页是可执行映射；
# 重新编译后翻页 / 打开书 / 切换标签的耗时在真机上实测过。
# 这里问 Android 自己的判断（dumpsys package dexopt，一次约 0.3 s），每次开机每个包最多因此重编一次。
compile_stale_pkgs() {
    [ -f $W/frozen ] || [ -f $W/saver_frozen ] && return 0
    # dumpsys 按 120 列折行（长包名的 "status=kOatUpToDate]" 会断成 "kOatUpToD" + 下一行 "ate]"），odex 坏掉 / 半截时
    # 只有 "status: invalid[]"、没有 "status="（pw5-5182 模拟器：一次失败的 speed 编译留下 71 MB 开头全零的 odex，
    # 以前这里看不出来，微信读书一直从 apk 解释执行）。所以把一个 path 之后到下一个 path / 包头为止的文字去掉空白拼起来再判断。
    adb_sh_t 60 "dumpsys package dexopt" 2>/dev/null | tr -d '\r' | awk '
        function flush() { if (p != "" && b != "" && b !~ /status=kOatUpToDate/) { s = b; if (match(b, /status=kOat[A-Za-z]+/)) s = substr(b, RSTART + 11, RLENGTH - 11); else if (b ~ /status:invalid/) s = "invalid"; else s = "other"; print p, s }; p = ""; b = "" }
        /path: \/data\/app\// { flush(); p = $2; sub(/^\/data\/app\//, "", p); sub(/-[0-9]+\/.*$/, "", p); next }
        /path:/ || /^ *\[/ { flush(); next }
        p != "" { l = $0; gsub(/[ \t]/, "", l); b = b l }
        END { flush() }' | sort -u -k1,1
}
# 512 MB 大应用的目标本来就是 verify-at-runtime：7.1 的 dumpsys package dexopt 对它仍可能报 Needed 等非 kOatUpToDate 状态；
# 只要当前 odex 已经是 verify-at-runtime，且不是损坏/缺失（invalid）或待重定位（NeedsRelocation），就不重复重编
compile_stale_skip() { # pkg status
    case "$2" in invalid|NeedsRelocation|kOatNeedsRelocation) return 1 ;; esac
    compile_low_big "$1" && [ "$(odex_filter "$1")" = verify-at-runtime ]
}
compile_scan() { # 所有还没编好的数据区应用排队
    # 换了版本：以前记的 fail 不再算（同一个安装失败两次就不再自动编；0.8.8 的 compile_held 把 $f 改坏、给安装让路的
    # 中断被记成 fail，KO3 用户诊断：微信读书因此一直没编，升级以后也不会再试）。timeout / capped 保留。
    csv=$(cat $VD/version 2>/dev/null || cat $SYS/version 2>/dev/null)
    if [ -n "$csv" ] && [ "$(cat $SYS/data/.vd-compile.ver 2>/dev/null)" != "$csv" ]; then
        [ -f $CLOG ] && sed -i '/^[^ ]* [^ ]* fail /d' $CLOG 2>/dev/null
        echo "$csv" > $SYS/data/.vd-compile.ver 2>/dev/null
    fi
    [ -n "$(compile_mode)" ] || return 0
    for p in $(data_pkgs); do compile_enqueue "$p"; done
    # kOatNeedsRelocation（启动镜像换了地址）只要 patchoat（几秒）：不带 -f 让 PackageManager 自己挑最省的办法（stale）；
    # 以前一律 -f 整个重编（微信读书 speed 约 341 s），用户这时打开它，编译被停、odex 已经没了，就一直解释执行
    compile_stale_pkgs | while read -r p st; do
        compile_stale_skip "$p" "$st" && continue
        grep -q "^$p\$" $W/compile.stale 2>/dev/null && continue
        echo "$p" >> $W/compile.stale
        compile_needed "$p" stale || continue
        t "compile: $p compiled code not usable ($st), $( { [ "$st" = NeedsRelocation ] || [ "$st" = kOatNeedsRelocation ]; } && echo relocating || echo recompiling)"
        compile_enqueue "$p" stale
    done
    [ -s $CQ ] && compile_kick
    return 0
}
# packages.list 变了（装 / 卸 / 更新了应用）就扫一遍。看门狗每一轮调一次：只比较 mtime 和大小，几乎不花时间
compile_watch() {
    # 开机完成（after_boot 写 $W/booted）一分钟以后才开始：别和用户打开的第一个应用抢 CPU
    b=$(cat $W/booted 2>/dev/null); [ -n "$b" ] || return 0
    # 预读不等这一分钟：只是空闲优先级读文件（微信读书 161 MB 约 1.3 s），开机后马上就打开阅读应用的人也赶得上
    # （一台测试机 1.01：开机 47 秒打开微信读书，预读要到 60 秒以后，第一次打开 7.5 s；另一台重启后预读过 4.9 s / 没预读 7.3 s）
    compile_prefault
    [ $(( $(cut -d. -f1 /proc/uptime) - b )) -ge 60 ] || return 0
    # 工作进程因为内存一直不够先停了（队列还在）：到了约定时间再起一次，保证队列最终能编完
    if [ -s $CQ ] && [ -f $W/compile.retry_at ] && ! alive $W/compile.pid &&
       [ "$(cut -d. -f1 /proc/uptime)" -ge "$(cat $W/compile.retry_at 2>/dev/null || echo 0)" ]; then
        rm -f $W/compile.retry_at; compile_kick
    fi
    f=$SYS/data/system/packages.list
    s=$(stat -c '%Y %s' $f 2>/dev/null) || return 0
    [ "$s" = "$(cat $W/pkglist.stamp 2>/dev/null)" ] && return 0
    echo "$s" > $W/pkglist.stamp
    compile_scan
}
# 大应用的编译结果预读（APP_PREFAULT=auto|off，默认 auto）。speed 编译把微信读书的 odex 撑到 169 MB，冷启动反而要先从存储里
# 读进这么多（PW6 5.19.5 真机：解释执行 5.5 s → speed 7.2 s）。开机完成后（看门狗第一轮），空闲优先级把大于 APP_PREFAULT_MB（默认 64）
# 的 odex 读一遍进页缓存：真机上微信读书冷启动到正文 10.3 → 8.8 s（5 次中位），应用里的翻页不变。
# 只在内存够的机型（不是 512 MB）、可用内存还有 400 MB 以上时做，每次开机一次；页缓存在内存紧时内核自己会放掉。
# 内存不够（< 400 MB）时不记成做完：以前第一次看就写 prefault.done，开机后一分钟内就打开了微信读书（它常驻 ~300 MB）的话
# 这次开机再也不预读（一台 PW6 测试机 1.01 升级后：15:02:22 开机 47 秒打开微信读书，15:02:45 预读因内存跳过，之后一直没做）。
# 现在看门狗每一轮再看一次，每个文件读过就记下（$W/prefault.d/），全部读完才算做完。
compile_prefault() {
    [ "${APP_PREFAULT:-auto}" = off ] && return 0
    [ -f $W/prefault.done ] && return 0
    lowmem_ram && { : > $W/prefault.done; return 0; }
    alive $W/prefault.pid && return 0
    [ "$(mem_avail_mb)" -ge 400 ] || return 0
    [ -n "$(pgrep dex2oat 2>/dev/null)" ] && return 0     # 编译时不预读（见下面）
    mkdir -p $W/prefault.d 2>/dev/null
    (
        for o in $SYS/data/app/*/oat/arm/base.odex; do
            [ -f "$o" ] || continue
            pd=${o#$SYS/data/app/}; pd=$W/prefault.d/${pd%%/*}
            [ -f "$pd" ] && continue
            kb=$(du -k "$o" 2>/dev/null | cut -f1)
            [ "${kb:-0}" -ge $(( ${APP_PREFAULT_MB:-64} * 1024 )) ] || { : > "$pd"; continue; }
            [ "$(mem_avail_mb)" -ge 400 ] || exit 0
            [ -f $W/frozen ] || [ -f $W/saver_frozen ] && exit 0
            # 有 dex2oat 在跑（我们的或 Android 自己的编译）不预读：它本身就要几百 MB，预读进来的页马上又被挤掉；
            # 正在编的那个包的 odex 编完就换掉了。不记成做完，看门狗下一轮再看
            [ -n "$(pgrep dex2oat 2>/dev/null)" ] && exit 0
            [ -f "${o%/*}/.vd-keep.odex" ] && exit 0
            t0=$(cut -d' ' -f1 /proc/uptime)
            ionice -c 3 nice -n 19 cat "$o" > /dev/null 2>&1 || nice -n 19 cat "$o" > /dev/null 2>&1
            : > "$pd"
            t "prefault: ${o#$SYS/data/app/} $((kb / 1024)) MB in $(awk -v a=$t0 -v b="$(cut -d' ' -f1 /proc/uptime)" 'BEGIN{printf "%.1f", b-a}') s"
        done
        : > $W/prefault.done
    ) </dev/null >>$LOG 2>&1 &
    echo $! > $W/prefault.pid
}
compile_kick() { # 没有在跑就起一个工作进程（看门狗和 install 命令可能同时调：mkdir 当锁）
    [ -s $CQ ] || return 0
    if ! mkdir $W/compile.lock 2>/dev/null; then
        alive $W/compile.pid && return 0
        rm -rf $W/compile.lock; mkdir $W/compile.lock 2>/dev/null || return 0
    fi
    compile_worker </dev/null >>$LOG 2>&1 &
    echo $! > $W/compile.pid
}

# 安装 / 卸载应用让路：dex2oat 跑着的时候 PackageManager 一直拿着安装锁（mInstallLock），这期间的安装（传书网页、商店、apps 文件夹）
# 要等编译做完——微信读书 nice 19 下要编好几分钟，模拟器上安装等了 400 多秒，网页 10 分钟超时。
# 安装前 compile_hold：标记“有安装在等”，给已装应用编译的 dex2oat / patchoat 立刻停掉（记下包名）；工作进程看到标记就先不开始下一个。
# 安装完 compile_release：去掉标记，被打断的包强制重新排队，接着编。标记是 $W/compile.hold/<pid>，持有者死了自动失效。
# 拿着锁的不只是我们自己的 cmd package compile：Android 自己的 BackgroundDexOptService 开机 1 分钟后（应用的 odex 对不上新的
# 启动镜像时，正好是系统更新后的微信读书）和充电空闲时也会编，同样拿着安装锁，一个包接一个包。以前只在我们的工作进程在编时才动手，
# Android 自己的编译不管（PW6 5.19.5 真机：装一个小 APK 等了 99 秒）。现在按 dex2oat 编的是哪个文件判断：/data/app/<包>-N/ 下的
# （已装应用）都停掉，不管是谁发起的；安装自己的编译在 /data/app/vmdl*.tmp/ 下，不碰。持有期间有个看守进程每 0.5 秒再查一次
# （Android 的后台任务杀掉一个就接着编下一个）。被杀的 dex2oat 由 installd 自己删掉没写完的输出（Dex2oatFileWrapper 失败时 unlink），
# 包的 odex 因此没了，所以一律强制重编。
compile_preempt() { # 停掉给已安装应用编译的 dex2oat / patchoat；每个被停的包名输出一行
    for p in $(pgrep dex2oat 2>/dev/null) $(pgrep patchoat 2>/dev/null); do
        # 编的是哪个应用：命令行（--zip-location / --oat-location）加上打开的文件（patchoat 只有 fd）
        pk=$( { tr '\0' '\n' < /proc/$p/cmdline; for fd in /proc/$p/fd/*; do readlink "$fd"; done; } 2>/dev/null |
              sed -n 's#.*/data/app/\([A-Za-z0-9._]*\)-[0-9][0-9]*/.*#\1#p' | head -n 1)
        [ -n "$pk" ] || continue
        kill -9 "$p" 2>/dev/null && echo "$pk"
    done
}
compile_preempt_note() { # 把被停的包记进 compile.interrupted（compile_release 重新排队）
    for pk in "$@"; do grep -qx "$pk" $W/compile.interrupted 2>/dev/null || echo "$pk" >> $W/compile.interrupted; done
}
compile_hold() {
    mkdir -p $W/compile.hold 2>/dev/null
    read -r hp _ < /proc/self/stat; echo "$hp" > $W/compile.hold/$hp; CHOLD=$hp
    # 我们自己的工作进程在编：告诉 compile_one 这是给安装让路（记成 aborted，不是 fail，也不去补 interpret-only）
    pgrep -f "cmd package compile" >/dev/null 2>&1 && echo install > $W/compile.abort
    st=$(compile_preempt)
    if [ -n "$st" ]; then
        compile_preempt_note $st
        t "compile: stopped dexopt of $(echo $st) so an install can run (re-queued)"
    fi
    # 看守：持有期间 Android 的后台编译接着编下一个包时也停掉
    if ! alive $W/compile.guard; then
        (
            while compile_held; do
                g=$(compile_preempt)
                [ -n "$g" ] && { compile_preempt_note $g; t "compile: stopped dexopt of $(echo $g) during an install (re-queued)"; }
                usleep 500000 2>/dev/null || sleep 1
            done
            rm -f $W/compile.guard
        ) </dev/null >>$LOG 2>&1 &
        echo $! > $W/compile.guard
    fi
    [ -n "$st" ] || return 0
    # 等 PackageManager 把锁放开（cmd package compile 返回、dex2oat 都退了）：最多 10 秒
    ch_i=0
    while [ $ch_i -lt 50 ]; do
        pgrep -f "cmd package compile" >/dev/null 2>&1 || pgrep dex2oat >/dev/null 2>&1 || break
        usleep 200000 2>/dev/null || sleep 1; ch_i=$((ch_i + 1))
    done
    return 0
}
# 变量名都带 ch_ / cr_ 前缀：这几个函数在 after_boot 的安装循环里被调用，ash 的 local 是动态作用域，
# 以前这里的 for f 把调用方的 $f（正在装的 APK）改成了 compile.hold/*：装上的 APK 删不掉、每次开机重装，装不上的也不改名 .failed
# （KT5 用户诊断 的“阅读 arc 装不上”在 0.99 的模拟器上复现）
compile_held() {
    [ -d $W/compile.hold ] || return 1
    for ch_f in $W/compile.hold/*; do
        [ -f "$ch_f" ] || continue
        kill -0 "${ch_f##*/}" 2>/dev/null && return 0
        rm -f "$ch_f"
    done
    return 1
}
compile_release() {
    [ -n "$CHOLD" ] && rm -f $W/compile.hold/$CHOLD; CHOLD=
    compile_held && return 0                     # 还有别的安装在等：最后一个放开时再接着编
    if [ -s $W/compile.interrupted ]; then
        for cr_p in $(cat $W/compile.interrupted); do compile_enqueue "$cr_p" force; done
        rm -f $W/compile.interrupted
    fi
    compile_kick
}

# 电量：低于 20 % 且没在充电就暂停
compile_battery_ok() {
    b=$(lipc-get-prop com.lab126.powerd battLevel 2>/dev/null)
    c=$(lipc-get-prop com.lab126.powerd isCharging 2>/dev/null)
    case "$b" in ''|*[!0-9]*) return 0 ;; esac
    [ "$c" = 1 ] && return 0
    [ "$b" -ge 20 ]
}
mem_avail_mb() { awk '/^MemAvailable:/{a=$2} /^MemFree:/{f=$2} /^Cached:/{c=$2} END{print int((a ? a : f + c) / 1024)}' /proc/meminfo; }
# 前台应用的包名（VeraHome 写的 $X/fg；没有就空）
compile_fg() { head -n 1 $X/fg 2>/dev/null | tr -d '\r '; }
# 应用有没有进程在跑（前台、后台服务、:push / :seg 这类子进程都算）：按 packages.list 里的 uid 找 /proc（宿主看得见容器里的进程），
# 一次 awk 读完，不为每个进程 fork。dex2oat 是 installd 用 5xxxx 的共享 gid 起的，不会被算成应用自己。
# 为什么要看（0.98 门禁 PW6 模拟器 0.97 -> 0.98 第一次开机）：cmd package compile -f 先删掉应用的 odex 再从头写新的，写完前
# （dex2oat 中途崩了 / 被停掉时就一直）没有能用的 odex。已经在跑的进程手里是旧文件，不受影响；这期间新起的进程（用户点开、
# 后台服务被拉起）拿到的是没有 / 写了一半的 odex，从 apk 现解压现解释，大应用（微信读书）开不起来、反复崩。所以有进程在跑的
# 应用不编（等它退出），编着的时候它起了进程就停掉 dex2oat、重新排队。
compile_app_uid() { awk -v p="$1" '$1 == p { print $2; exit }' $SYS/data/system/packages.list 2>/dev/null; }
compile_app_running() { # pkg
    car_u=$(compile_app_uid "$1"); [ -n "$car_u" ] || return 1
    # 经 cat 读：busybox awk 打不开一个文件（进程在 glob 展开后、awk 读到它之前退出了）就整个退出、END 不跑，
    # 排在后面的应用进程根本没被看到 → 误判“没在跑”，编译开了又被停，一分钟一次反复（PW5 用户诊断 2026-11-21）。cat 跳过读不到的接着读。
    cat /proc/[0-9]*/status 2>/dev/null | awk -v u="$car_u" '/^Uid:/ { if ($2 == u) { f = 1; exit } } END { exit !f }'
}
# 队列里只剩在跑的应用（不在前台）：用户空闲、等了 COMPILE_RUNNING_WAIT_S（默认 600）秒以后，请 Android 结束它可以安全结束的
# 进程（am kill：只杀后台 / 缓存进程，不碰前台和正在被用的服务），每个包 10 分钟最多一次。结束不了就继续等，不强编。
# 用户点了“立即优化”（compile_manual）：不等空闲、不等 10 分钟，马上请 Android 结束它（之后每分钟最多一次）；
# 结束不了（有服务一直在跑）的，1 GB 机型过 COMPILE_RUNWAIT_S（默认 30）秒照样编（compile_runok，自动时也一样）。
compile_bg_kill() { # pkg...
    for cbk_p in "$@"; do
        cbk_f=$W/compile.runwait.$cbk_p
        if compile_manual; then
            [ -f "$cbk_f" ] && [ $(( $(date +%s) - $(stat -c %Y "$cbk_f" 2>/dev/null || echo 0) )) -lt 60 ] && [ -f $W/compile.mkill.$cbk_p ] && continue
            touch "$cbk_f"; [ -f $W/compile.mkill.$cbk_p ] || : > $W/compile.mkill.$cbk_p
            adb_sh_t 30 "am kill $cbk_p" >/dev/null 2>&1
            t "compile: $cbk_p is running in the background, asked Android to end it now (manual start)$(compile_app_running "$cbk_p" && echo ", still running")"
            continue
        fi
        [ -f "$cbk_f" ] || { : > "$cbk_f"; continue; }
        compile_user_active && continue
        [ $(( $(date +%s) - $(stat -c %Y "$cbk_f" 2>/dev/null || date +%s) )) -ge ${COMPILE_RUNNING_WAIT_S:-600} ] || continue
        touch "$cbk_f"; [ -f $W/compile.mkill.$cbk_p ] || : > $W/compile.mkill.$cbk_p
        adb_sh_t 30 "am kill $cbk_p" >/dev/null 2>&1
        t "compile: $cbk_p kept running in the background, asked Android to end its background processes (am kill)$(compile_app_running "$cbk_p" && echo ", still running")"
    done
}
# 多久没摸屏（秒）。$W/lastinput 不存在（这次开机还没摸过、或手势全关所以没有 poke）按空闲算
compile_idle_s() {
    l=$(stat -c %Y $W/lastinput 2>/dev/null) || { echo 999999; return 0; }
    d=$(( $(date +%s) - l )); [ $d -lt 0 ] && d=0; echo $d
}
compile_user_active() { [ "$(compile_idle_s)" -lt "${COMPILE_IDLE_S:-300}" ]; }
# 手动开始（2026-11-19：点了“开始优化”，一直显示排队、很久才开始）：用户在桌面 / 传书网页上点了优化（compile_cmd），
# 这一批立即开始，不等“几分钟不碰屏幕”（needidle）、用着时也不按“用着时”的高内存门槛（400 MB）等，不等 10 分钟才请后台应用退出。
# 留着的保护：电量（< 20 % 不充电）、内存（空闲时的门槛 180 MB；编着掉到 COMPILE_MANUAL_ABORT_MB 以下停掉、手动结束、等空闲）、
# 前台应用不编、冻结时不编、给安装让路、超时。$W/compile.manual 是点的时间，这一批编完、或者 COMPILE_MANUAL_S（默认 2 小时）后失效。
compile_manual() {
    [ -f $W/compile.manual ] || return 1
    cm_t=$(cat $W/compile.manual 2>/dev/null); case "$cm_t" in ''|*[!0-9]*) cm_t=0 ;; esac
    [ $(( $(date +%s) - cm_t )) -lt ${COMPILE_MANUAL_S:-7200} ] && return 0
    rm -f $W/compile.manual; return 1
}
# 按“用户在用”来等 / 限制：手动开始时不算
compile_user_busy() { compile_user_active && ! compile_manual; }
# 在后台跑着、请过 Android 结束（compile_bg_kill 的 am kill）还是结束不了的包，过 COMPILE_RUNWAIT_S（默认 30）秒照样编：
# 1 GB 机型编着它起了进程也不停（compile_fg_abort），已经在跑的进程手里是旧 odex，不受影响。512 MB 机型照旧等它退出（状态里提示去
# “切换应用”划掉）。前台的应用任何时候都不编。手动和自动一样（2026-11-19：自动排队的强制编译不能为 am kill 结束不了的后台
# 应用一直等；以前 Feeder 是“上一个应用”，am kill 结束不了，一直等）。自动时 am kill 还是按 compile_bg_kill 的规矩（空闲 + 10 分钟）。
compile_runok() { # pkg
    compile_fg_abort && return 1
    [ -f $W/compile.mkill.$1 ] || return 1
    [ $(( $(date +%s) - $(stat -c %Y $W/compile.mkill.$1 2>/dev/null || date +%s) )) -ge ${COMPILE_RUNWAIT_S:-${COMPILE_MANUAL_RUNWAIT_S:-30}} ]
}
# 等待时的 sleep：compile_cmd（用户点了优化）放下 $W/compile.wake 就提前醒来，马上按新的条件重看、重写状态
compile_nap() { # 秒
    cn_n=$1
    while [ "$cn_n" -gt 0 ]; do
        [ -f $W/compile.wake ] && { rm -f $W/compile.wake; return 0; }
        sleep 2; cn_n=$((cn_n - 2))
    done
    return 0
}
# 给用户看的“什么时候开始”：离“COMPILE_IDLE_S 秒没摸屏”还差多久（等待时每 30 秒重写一次状态，跟着变）
compile_idle_wait_text() {
    ci_r=$(( ${COMPILE_IDLE_S:-300} - $(compile_idle_s) )); [ $ci_r -lt 0 ] && ci_r=0
    ci_m=$(( (ci_r + 59) / 60 )); [ $ci_m -lt 1 ] && ci_m=1
    echo "$(( ${COMPILE_IDLE_S:-300} / 60 )) 分钟不碰屏幕后开始（还要约 $ci_m 分钟）"
}
compile_charging() { [ "$(lipc-get-prop com.lab126.powerd isCharging 2>/dev/null)" = 1 ]; }
# 开始编之前要有的可用内存（MB）：dex2oat 编一个大应用（微信读书 ~25 MB dex）要 150–250 MB。
# 用户正在用：要求高得多（编译不能把前台应用挤进 zram），512 MB 机型按比例减半
compile_mem_need() {
    if compile_user_busy; then
        if lowmem_ram; then echo ${COMPILE_ACTIVE_MB_LOW:-200}; else echo ${COMPILE_ACTIVE_MB:-400}; fi
    else
        if lowmem_ram; then echo ${COMPILE_MIN_MB_LOW:-120}; else echo ${COMPILE_MIN_MB:-180}; fi
    fi
}
compile_mem_ok() { [ "$(mem_avail_mb)" -ge "$(compile_mem_need)" ]; }
# 编着的时候用户在用、可用内存掉到这以下：停掉，等空闲再编
compile_active_abort_mb() {
    if compile_manual; then
        if lowmem_ram; then echo ${COMPILE_MANUAL_ABORT_MB_LOW:-60}; else echo ${COMPILE_MANUAL_ABORT_MB:-100}; fi
    elif lowmem_ram; then echo ${COMPILE_ACTIVE_ABORT_MB_LOW:-100}; else echo ${COMPILE_ACTIVE_ABORT_MB:-200}; fi
}
# 只停给某一个包编译的 dex2oat / patchoat。返回 0 = 真的停掉了至少一个；1 = 一个也没找到 / 没杀掉
# （cmd package compile 还没起 dex2oat、或者 dex2oat 刚好在换文件）。以前不看结果：babysit 当成已经停了、不再试，
# 连超时也不查了，dex2oat 照样编完（Oasis 2 用户日志：busy 停了个空，590 秒后 Success，又被当成 aborted 强制重排）。
compile_preempt_pkg() { # pkg
    cpp_r=1
    for p in $(pgrep dex2oat 2>/dev/null) $(pgrep patchoat 2>/dev/null); do
        pk=$( { tr '\0' '\n' < /proc/$p/cmdline; for fd in /proc/$p/fd/*; do readlink "$fd"; done; } 2>/dev/null |
              sed -n 's#.*/data/app/\([A-Za-z0-9._]*\)-[0-9][0-9]*/.*#\1#p' | head -n 1)
        [ "$pk" = "$1" ] && kill -9 "$p" 2>/dev/null && cpp_r=0
    done
    return $cpp_r
}
# babysit 决定停掉这个包的编译：停到了才记原因（compile.abort）、算 killed；没停到下一轮（2 秒后）再试，同一个原因只记一次日志
compile_babysit_stop() { # 原因 包 日志
    if compile_preempt_pkg "$2"; then
        t "$3"; echo "$1" > $W/compile.abort; killed=$1
    elif [ "$cbs_miss" != "$1" ]; then
        cbs_miss=$1; t "compile: wanted to stop dexopt of $2 ($1) but found no dex2oat / patchoat for it yet, retrying"
    fi
}
# dex2oat 是 Android 的 installd 起的，不是我们的子进程：自己的 nice 管不到它。编译期间每 2 秒把所有
# dex2oat 线程调成 nice 19 + IO 空闲（ionice -c3；没有 ionice 就只调 nice）。内存低到危险时直接杀掉 dex2oat：
# 编译失败只是应用继续用解释执行，总比让 lowmemorykiller 杀前台应用或让机器换页卡死好。
# Android 里自己发起的安装（软件包安装程序、浏览器下载后点安装、pm install）走 PackageInstaller 会话：APK 先写进
# /data/app/vmdl<会话号>.tmp/，提交后 PackageManager 要拿安装锁——锁被我们的 speed 编译拿着，就一直等到编完
# （一台 PW6 测试机实测：编起点读书时装 2.7 MB 的 Via 等了 394 秒，用 pm install 装起点读书本身等了 364 秒）。宿主的 install
# 命令有 compile_hold，这条路没有。会话目录存在（而且 10 分钟内动过）就当作有安装在等：停掉编译、重新排队。
# 开机时 PackageInstallerService 会删掉残留的 vmdl*.tmp，放弃的会话也会删，所以不会被旧目录一直挡住。
compile_session_pending() {
    csp_now=$(date +%s)
    for csp_d in $SYS/data/app/vmdl*.tmp; do
        [ -d "$csp_d" ] || continue
        csp_m=$(stat -c %Y "$csp_d" 2>/dev/null) || continue
        [ $((csp_now - csp_m)) -lt ${COMPILE_SESSION_S:-600} ] && return 0
    done
    return 1
}
# 正在编的应用被打开 / 起了进程时停不停编译。停了也回不到原来的 odex（installd 开始编时就把它截断了），应用照样解释执行，
# 编了一半的工作白费，下次开机又从头编（微信读书 speed 约 341 s，用户一开机就打开它，于是永远编不完）。
# 所以 1 GB 机型不停（nice 19 + IO 空闲照旧；内存紧时下面的 busy / memory 两条照样会停），只有 512 MB 机型
# 为了前台应用的内存停。COMPILE_FG_ABORT=1 / 0 强制。
compile_fg_abort() { case "${COMPILE_FG_ABORT:-auto}" in 1|on) return 0 ;; 0|off) return 1 ;; esac; lowmem_ram; }
compile_babysit() { # cmd 的 pid [包]
    killed=; cbs_miss=; t0=$(cut -d. -f1 /proc/uptime)
    cbs_big=; [ -n "$2" ] && compile_big "$2" && cbs_big=1
    while kill -0 "$1" 2>/dev/null; do
        # 大应用（compile_big）编着的时候有应用到了前台：停掉，不为它挤掉正在用的应用（busy：等空闲再编）
        if [ -n "$cbs_big" ] && [ -z "$killed" ] && compile_fg_app; then
            compile_babysit_stop busy "$2" "compile: $(compile_fg) came to the foreground while the large app $2 was being compiled, stopping it (will retry when idle)"
        fi
        # 已经决定停（killed 是原因）：cmd 还没返回时每一轮都再停一次这个包的 dex2oat / patchoat（PackageManager 可能接着编下一个
        # 代码路径），不记日志
        [ -n "$2" ] && [ -n "$killed" ] && [ "$killed" != memory ] && compile_preempt_pkg "$2"
        # 正在编的应用被打开到了前台：停掉，等它离开前台再编（compile_one 重新排队）
        if [ -n "$2" ] && [ -z "$killed" ] && compile_fg_abort && [ "$(compile_fg)" = "$2" ]; then
            compile_babysit_stop foreground "$2" "compile: $2 came to the foreground, stopping its dexopt (will finish after it leaves the foreground)"
        fi
        # Android 里有安装在等安装锁：停掉，让它先装（compile_one 重新排队）
        if [ -n "$2" ] && [ -z "$killed" ] && compile_session_pending; then
            compile_babysit_stop session "$2" "compile: an install started inside Android is waiting for PackageManager, stopping dexopt of $2 (re-queued)"
        fi
        # 编着的时候这个应用起了进程（点开了 / 后台服务被拉起）：它拿到的是没写完的 odex，停掉，等它退出再编
        if [ -n "$2" ] && [ -z "$killed" ] && compile_fg_abort && compile_app_running "$2"; then
            compile_babysit_stop running "$2" "compile: $2 started while it was being compiled, stopping its dexopt (will finish after it exits)"
        fi
        # 用户在用、内存紧：停掉，等空闲再编
        if [ -n "$2" ] && [ -z "$killed" ] && compile_user_active && [ "$(mem_avail_mb)" -lt "$(compile_active_abort_mb)" ]; then
            compile_babysit_stop busy "$2" "compile: device in use and only $(mem_avail_mb) MB available, stopping dexopt of $2 (will retry when idle)"
        fi
        # 自己的超时（dex2oat 自带的 9.5 分钟看门狗已用 --no-watch-dog 关掉）：默认 540 秒，必须小于 PackageManager 的 10 分钟看门狗。
        # 一次编译期间 PackageManager 一直拿着安装锁（mInstallLock）；这时它自己的线程上任何要这把锁的事（Android 里的安装 / 卸载 /
        # 清数据 / 查应用大小、装完的收尾 rmPackageDir）都排在后面，排满 10 分钟 system_server 就被自己的 Watchdog 杀掉、软重启
        # （pw5-5182 模拟器两次："Blocked in handler on PackageManager"，PackageManager 线程停在 InstallerConnection 上，
        # 持锁的正是微信读书 653 / 679 秒的 speed 编译；用户看到的是用着应用突然回到开机画面）。以前默认 40 分钟。
        # 不管前面停没停过（以前 killed 了就不查：停了个空的那次 dex2oat 一直编到 590 秒），过了时间还有 dex2oat 就每一轮都杀。
        if [ $(( $(cut -d. -f1 /proc/uptime) - t0 )) -ge ${COMPILE_MAX_S:-540} ]; then
            cbs_k=
            for p in $(pgrep dex2oat 2>/dev/null) $(pgrep patchoat 2>/dev/null); do kill -9 "$p" 2>/dev/null && cbs_k=1; done
            if [ -z "$killed" ]; then
                t "compile: still running after ${COMPILE_MAX_S:-540}s, aborting dex2oat"
                killed=timeout; echo timeout > $W/compile.abort
            elif [ -n "$cbs_k" ] && [ "$cbs_miss" != timeout ]; then
                cbs_miss=timeout; t "compile: dex2oat still running ${COMPILE_MAX_S:-540}s after start although stopped ($killed), killed again"
            fi
        fi
        for p in $(pgrep dex2oat 2>/dev/null); do
            for tk in /proc/$p/task/*; do
                tid=${tk##*/}
                renice -n 19 -p "$tid" >/dev/null 2>&1
                command -v ionice >/dev/null 2>&1 && ionice -c 3 -p "$tid" >/dev/null 2>&1
            done
            if [ "$(mem_avail_mb)" -lt "${COMPILE_ABORT_MB:-40}" ] && [ -z "$killed" ]; then
                t "compile: memory critically low ($(mem_avail_mb) MB available), aborting dex2oat $p"
                kill -9 "$p" 2>/dev/null && { killed=memory; echo memory > $W/compile.abort; }
            fi
        done
        sleep 2
    done
}
# 编译期间保住原来的 odex（2026-11-20，PW6 模拟器：刚装好的微信读书 / 番茄在后台编译时打开，
# ClassNotFoundException / Unable to instantiate application，第二次打开又好了）。Android 7.1 的 installd 每次 dexopt 都先
# unlink 掉 oat/<isa>/base.odex（和 base.art），再在同一个路径上 O_EXCL 新建、让 dex2oat 往里写；dex2oat 失败 / 被停掉时再按路径
# unlink 掉没写完的文件。所以整个编译期间（大应用几分钟）以及编译失败 / 被停以后，应用都没有能用的 odex，只能从 apk 现解压 dex、
# 校验、解释执行（又慢又占内存；模拟器上编译期间这一步还会偶尔校验失败：Bad checksum → 找不到类 → 崩）。
# 现在：编译前把原来的 odex / art 硬链接一份（.vd-keep.*）；installd 新建的文件一出现就把它也硬链接走（.vd-new.*，dex2oat 继续
# 通过 fd 往同一个 inode 写），再把原来的那份原子地 rename 回 base.*：应用打开时看到的始终是原来那份完整的 odex。
# 编成功（cmd 说 Success）才把新的 rename 到 base.*；失败 / 被停 / 超时：installd 按路径删掉的是原来那份的一个名字，
# 从 .vd-keep 再放回去，没写完的新文件删掉。窗口只剩 installd 新建到看守进程发现之间（≤ 0.2 秒）。
# 只管 base（本项目里没有分包 apk）；没有原来的 odex（或者它不是完整的 ELF）时和以前一样。
compile_oat_dir() { # pkg -> 宿主路径 $SYS/data/app/<包>-N/oat/<isa>（已有的那个）
    cod_d=$(app_dir "$1") || return 1
    for cod_i in arm arm64; do
        [ -d "$SYS/data/app/$cod_d/oat/$cod_i" ] && { echo "$SYS/data/app/$cod_d/oat/$cod_i"; return 0; }
    done
    return 1
}
compile_elf_ok() { [ -s "$1" ] && [ "$(dd if="$1" bs=4 count=1 2>/dev/null | tail -c 3)" = ELF ]; }   # 写完的 oat 才有 ELF 头（dex2oat 最后才写）
# 编译开始前：把原来的 odex / art 硬链接成 .vd-keep.*。输出要保护的 oat 目录；没有可保的返回 1
compile_keep_begin() { # pkg
    ckb_o=$(compile_oat_dir "$1") || return 1
    rm -f "$ckb_o"/.vd-keep.* "$ckb_o"/.vd-new.* "$ckb_o"/.vd-tmp.* 2>/dev/null
    compile_elf_ok "$ckb_o/base.odex" || return 1
    ln -f "$ckb_o/base.odex" "$ckb_o/.vd-keep.odex" 2>/dev/null || return 1
    [ -f "$ckb_o/base.art" ] && ln -f "$ckb_o/base.art" "$ckb_o/.vd-keep.art" 2>/dev/null
    echo "$ckb_o"
}
# 把 base.<ext> 放回原来那份（.vd-keep.<ext>；没有原来那份就删掉 base.<ext>）。rename 是原子的：打开它的进程要么拿到旧的、要么拿到新的
compile_keep_put() { # dir ext
    if [ -f "$1/.vd-keep.$2" ]; then
        [ "$1/base.$2" -ef "$1/.vd-keep.$2" ] && return 0
        ln -f "$1/.vd-keep.$2" "$1/.vd-tmp.$2" && mv -f "$1/.vd-tmp.$2" "$1/base.$2"
    else rm -f "$1/base.$2"; fi
}
# installd 新建了 base.<ext>（和原来那份不是一个 inode，也不是已经挪走的那个）：硬链接到 .vd-new.<ext>，base.<ext> 放回原来那份
compile_keep_divert() { # dir ext
    [ -f "$1/base.$2" ] || return 1
    [ -f "$1/.vd-keep.$2" ] && [ "$1/base.$2" -ef "$1/.vd-keep.$2" ] && return 1
    [ -f "$1/.vd-new.$2" ] && [ "$1/base.$2" -ef "$1/.vd-new.$2" ] && { compile_keep_put "$1" "$2"; return 0; }
    ln -f "$1/base.$2" "$1/.vd-new.$2" 2>/dev/null || return 1
    compile_keep_put "$1" "$2"
}
# 看守进程（cmd package compile 跑着时在后台）：每 0.2 秒看一次，新建的挪走、原来的放回；已经挪走过而 base.odex 没了（installd 失败
# 时按路径删的）也马上放回。cmd 结束后按结果收尾（compile_keep_end）。
compile_keep_guard() { # cmd 的 pid  oat 目录  输出文件
    while kill -0 "$1" 2>/dev/null; do
        compile_keep_divert "$2" odex; compile_keep_divert "$2" art
        [ -f "$2/.vd-new.odex" ] && [ ! -f "$2/base.odex" ] && compile_keep_put "$2" odex
        usleep 200000 2>/dev/null || sleep 1
    done
    compile_keep_end "$2" "$3"
}
compile_keep_end() { # oat 目录  输出文件
    compile_keep_divert "$1" odex; compile_keep_divert "$1" art
    if grep -q Success "$2" 2>/dev/null && compile_elf_ok "$1/.vd-new.odex"; then
        # 编成了：新的换上去。这次没有生成 app image（不是按档案编的）就不能留着原来那份 art（它属于原来的 odex）
        mv -f "$1/.vd-new.odex" "$1/base.odex"
        if [ -f "$1/.vd-new.art" ]; then mv -f "$1/.vd-new.art" "$1/base.art"; else rm -f "$1/base.art"; fi
        touch -r "$1/../../base.apk" "$1/base.odex" 2>/dev/null   # installd 的 utime（它按路径改到了原来那份上）
        t "compile: new odex of ${1#$SYS/data/app/} in place, the old one was kept until it was complete"
    elif [ -f "$1/.vd-new.odex" ]; then
        compile_keep_put "$1" odex; compile_keep_put "$1" art
        t "compile: compile of ${1#$SYS/data/app/} did not finish, the previous odex is back in place"
    else
        # installd 根本没有新建（PackageManager 不在、等锁时被停了、不需要编）：确认原来的还在
        compile_keep_put "$1" odex; [ -f "$1/.vd-keep.art" ] && compile_keep_put "$1" art
    fi
    rm -f "$1"/.vd-keep.* "$1"/.vd-new.* "$1"/.vd-tmp.* 2>/dev/null
}
# 起一次 cmd package compile（后台）：有原来的 odex 就带看守。设 cp_（cmd 的 pid，给 compile_babysit）和 cpg_（看守的 pid）；
# 之后调用方 compile_pm_wait 拿返回码（等看守收尾完，之后读到的 odex_filter 才是最终的）
compile_pm_start() { # pkg 输出文件 cmd 参数...
    cps_p=$1; cps_out=$2; shift 2
    cps_o=$(compile_keep_begin "$cps_p")
    adb_sh "cmd package compile $*" > "$cps_out" 2>&1 &
    cp_=$!; cpg_=
    if [ -n "$cps_o" ]; then compile_keep_guard $cp_ "$cps_o" "$cps_out" </dev/null >>$LOG 2>&1 & cpg_=$!; fi
}
compile_pm_wait() {
    wait $cp_; cpw_r=$?
    [ -n "$cpg_" ] && wait $cpg_
    return $cpw_r
}
# 上一次的工作进程没收尾就没了（停 Android / 宿主脚本被杀）：留下的 .vd-keep / .vd-new 收拾掉。base.odex 是完整的 ELF 就留着
# （挪走前 / 编完换上的），不是（没了、或者是没写完的新文件）就放回原来那份。正在给这个包编的 dex2oat 还在时不动。
compile_keep_cleanup() {
    for ckc_k in $SYS/data/app/*/oat/*/.vd-keep.odex $SYS/data/app/*/oat/*/.vd-new.odex; do
        [ -f "$ckc_k" ] || continue
        ckc_o=${ckc_k%/*}; ckc_p=${ckc_o#$SYS/data/app/}; ckc_p=${ckc_p%%/*}; ckc_p=${ckc_p%-*}
        compile_dex2oat_for "$ckc_p" && continue
        if [ -f "$ckc_o/.vd-keep.odex" ] && ! { compile_elf_ok "$ckc_o/base.odex" && ! [ "$ckc_o/base.odex" -ef "$ckc_o/.vd-new.odex" ]; }; then
            compile_keep_put "$ckc_o" odex; compile_keep_put "$ckc_o" art
            t "compile: ${ckc_o#$SYS/data/app/}: put the previous odex back after an unfinished compile"
        fi
        rm -f "$ckc_o"/.vd-keep.* "$ckc_o"/.vd-new.* "$ckc_o"/.vd-tmp.* 2>/dev/null
    done
}
compile_dex2oat_for() { # pkg：有给它编译的 dex2oat / patchoat 在跑
    for p in $(pgrep dex2oat 2>/dev/null) $(pgrep patchoat 2>/dev/null); do
        tr '\0' '\n' < /proc/$p/cmdline 2>/dev/null | grep -q "/data/app/$1-[0-9]*/" && return 0
    done
    return 1
}
# 轻量编译（补救用）：没有能用的 odex 时至少回到安装时的状态。也在 babysit 下跑（nice 19、内存保护、应用起了进程就停）。
# speed 在这台机器上编不完（番茄免费小说 147 MB：PW6 上 speed 超过 520 秒）时，用户用过这个应用、已经有 JIT 档案的话
# 改编 speed-profile（只编档案里的热点方法）：一台 PW6 测试机实测番茄 194 秒编完，冷启动到主界面从约 2 分钟降到 28 秒。
# 没有档案（刚装好还没打开过）、或者不是超时，就补 interpret-only。
compile_light() { # pkg [timeout]
    if compile_low_big "$1"; then       # 512 MB 的大应用：只补 verify-at-runtime（见 compile_low_big）
        compile_pm_start "$1" $W/compile.light.out -m verify-at-runtime -f "$1"
        compile_babysit $cp_ "$1"; compile_pm_wait
    elif [ "$2" = timeout ] && compile_has_profile "$1" && [ "${COMPILE_PROFILE_FALLBACK:-on}" != off ]; then
        compile_prof_save "$1"
        compile_pm_start "$1" $W/compile.light.out -m speed-profile -f "$1"
        compile_babysit $cp_ "$1"; compile_pm_wait
        compile_prof_back "$1"
        # speed-profile 没编成时原来的 odex 还在（compile_keep_*）就留着它；一个也没有才补 interpret-only
        [ -n "$(odex_filter "$1")" ] || { compile_pm_start "$1" $W/compile.light.out -m interpret-only -f "$1"; compile_babysit $cp_ "$1"; compile_pm_wait; }
    else
        compile_pm_start "$1" $W/compile.light.out -m interpret-only -f "$1"
        compile_babysit $cp_ "$1"; compile_pm_wait
    fi
}
# compile.restore 里第一个现在能补的包（不在前台、没有进程在跑、还是没有 odex）：补 interpret-only。
# 补了返回 0（工作进程这一轮就做这个）；没有能补的返回 1。补的时候又被停（应用又起来了）留在单子里下次再补；
# 已经有 odex（被别的编译编好了）或者应用卸掉了就从单子里去掉。
compile_restore_next() {
    [ -s $W/compile.restore ] || return 1
    crn_fg=$(compile_fg); crn_p=
    for crn_q in $(cat $W/compile.restore); do
        if ! app_dir "$crn_q" >/dev/null || [ -n "$(odex_filter "$crn_q")" ]; then
            sed -i "/^$crn_q\$/d" $W/compile.restore; continue
        fi
        [ "$crn_q" = "$crn_fg" ] && continue
        compile_app_running "$crn_q" && continue
        compile_fg_app && compile_big "$crn_q" && continue
        crn_p=$crn_q; break
    done
    [ -n "$crn_p" ] || return 1
    rm -f $W/compile.abort
    crn_s=$(cut -d. -f1 /proc/uptime)
    crn_t=$(adb_sh "getprop dalvik.vm.dex2oat-threads" 2>/dev/null | tr -d '\r\n')
    if lowmem_ram || compile_user_busy || ! compile_charging; then adb_sh "setprop dalvik.vm.dex2oat-threads 1" >/dev/null 2>&1; fi
    # 大应用有档案就补 speed-profile（compile_light 的 timeout 分支），和它平时的过滤器一样；interpret-only 也一样占几百 MB
    if compile_big "$crn_p"; then compile_light "$crn_p" timeout; else compile_light "$crn_p"; fi
    adb_sh "setprop dalvik.vm.dex2oat-threads '$crn_t'" >/dev/null 2>&1
    crn_f=$(odex_filter "$crn_p"); crn_a=$(cat $W/compile.abort 2>/dev/null)
    t "compile: $crn_p had no odex after an aborted compile, restored to ${crn_f:-none} ($(( $(cut -d. -f1 /proc/uptime) - crn_s ))s${crn_a:+, aborted: $crn_a})"
    [ -n "$crn_f" ] || [ -z "$crn_a" ] && sed -i "/^$crn_p\$/d" $W/compile.restore
    # 补出来的已经是它要的过滤器（大应用的 speed-profile）：队列里重排的那一项不用再编一遍
    if [ -n "$crn_f" ] && compile_done_filter "$crn_f" "$(compile_pkg_mode "$crn_p" "$(compile_mode)")"; then
        sed -i "/^$crn_p\( \|\$\)/d" $CQ 2>/dev/null
        rm -f $W/compile.needidle
    fi
    rm -f $W/compile.abort
    return 0
}
# dex2oat 是否因 SIGSEGV / SIGABRT 崩溃（加固包如 com.jjwxc.reader 的 libshell-super）：
# 查返回码（134/139）、命令输出，以及本次编译期间新写入的 dex2oat tombstone
compile_dex2oat_crashed() { # start_epoch out_file rc
    case "$3" in 134|139) return 0 ;; esac
    grep -q -e SIGSEGV -e SIGABRT -e 'Segmentation fault' -e Aborted -e 'Fatal signal 11' -e 'Fatal signal 6' "$2" 2>/dev/null && return 0
    [ -d $SYS/data/tombstones ] || return 1
    for cdc_f in $SYS/data/tombstones/tombstone_*; do
        [ -f "$cdc_f" ] || continue
        cdc_m=$(stat -c %Y "$cdc_f" 2>/dev/null) || continue
        [ "$cdc_m" -ge "$1" ] 2>/dev/null || continue
        head -n 20 "$cdc_f" 2>/dev/null | grep -q 'dex2oat' || continue
        head -n 20 "$cdc_f" 2>/dev/null | grep -q -e 'SIGSEGV' -e 'SIGABRT' && return 0
    done
    return 1
}
compile_one() { # pkg mode [force|stale]
    pkg=$1; mode=$2; cflag=-f; [ "$3" = stale ] && cflag=
    dir=$(app_dir "$pkg") || return 1
    rm -f $W/compile.abort
    # dex2oat 线程数：只有充电 + 空闲（不在用）时用系统默认（最快）；用户在用或用电池时单线程（内存峰值低很多、
    # 不和前台抢 CPU）；512 MB 机型一律单线程。COMPILE_THREADS 可以强制指定。编完恢复。
    thr=${COMPILE_THREADS:-}
    if [ -z "$thr" ]; then
        if lowmem_ram || compile_user_busy || ! compile_charging; then thr=1; fi
    fi
    othr=$(adb_sh "getprop dalvik.vm.dex2oat-threads" 2>/dev/null | tr -d '\r\n')
    [ -n "$thr" ] && adb_sh "setprop dalvik.vm.dex2oat-threads $thr" >/dev/null 2>&1
    # dex2oat 自带看门狗：9.5 分钟没编完就自杀（"dex2oat did not finish after 570 seconds"）。nice 19 下大应用
    # （多看阅读 52 MB odex，pw6-5195 模拟器上 645 秒）会被它杀掉。installd 会把 dalvik.vm.dex2oat-flags 加到命令行上：
    # 编译期间关掉它，超时由 compile_babysit 自己管。
    oflags=$(adb_sh "getprop dalvik.vm.dex2oat-flags" 2>/dev/null | tr -d '\r\n')
    # 不能有前导空格：installd 按空格切参数，空参数会让 dex2oat 报 Unknown argument 直接失败（pw6-5195 实测）
    case "$oflags" in *--no-watch-dog*) ;; '') adb_sh "setprop dalvik.vm.dex2oat-flags --no-watch-dog" >/dev/null 2>&1 ;;
        *) adb_sh "setprop dalvik.vm.dex2oat-flags '$oflags --no-watch-dog'" >/dev/null 2>&1 ;; esac
    s=$(cut -d. -f1 /proc/uptime)
    cs_ts=$(date +%s)
    [ "$mode" = speed-profile ] && compile_prof_save "$pkg"
    compile_pm_start "$pkg" $W/compile.out -m $mode $cflag $pkg
    compile_babysit $cp_ "$pkg"
    compile_pm_wait; rc=$?
    [ "$mode" = speed-profile ] && compile_prof_back "$pkg"
    # stale 不带 -f：PackageManager 只做了它认为需要的（relocation = patchoat）。之后还不是 UpToDate / 过滤器不对才整个重编
    if [ -z "$cflag" ] && [ ! -s $W/compile.abort ] && grep -q Success $W/compile.out && alive $W/pid; then
        cs_st=$(compile_stale_pkgs | awk -v p="$pkg" '$1 == p { print ($2 != "" ? $2 : "other"); exit }')
        if { [ -n "$cs_st" ] && ! compile_stale_skip "$pkg" "$cs_st"; } || ! compile_done_filter "$(odex_filter "$pkg")" "$mode"; then
            t "compile: $pkg still not usable after a plain dexopt, full recompile"
            compile_pm_start "$pkg" $W/compile.out -m $mode -f $pkg
            compile_babysit $cp_ "$pkg"; compile_pm_wait; rc=$?
        fi
    fi
    sec=$(( $(cut -d. -f1 /proc/uptime) - s ))
    f=$(odex_filter "$pkg")
    ab=$(cat $W/compile.abort 2>/dev/null)
    # 编出来的就是要的过滤器、PackageManager 也说 Success：不管中途有没有想停它（停了个空、dex2oat 自己先编完了），都算编好，
    # 不再强制重排（Oasis 2 用户日志：busy 停了个空，590 秒后 Success，以前记成 aborted 又从头编一遍）
    if [ "$f" = "$mode" ] && grep -q Success $W/compile.out; then r=ok
    elif [ "$ab" = install ]; then r=aborted   # 给安装让路：compile_release 会重新排队，不算失败
    # 到了前台 / 用户在用且内存紧 / 低内存中止：不算失败，重新排队（空闲时再编，不立刻在 OOM 压力下起 compile_light）
    elif [ "$ab" = foreground ] || [ "$ab" = running ] || [ "$ab" = busy ] || [ "$ab" = session ] || [ "$ab" = memory ]; then r=aborted
    # 超过 COMPILE_MAX_S 被停：这台机器上这个过滤器编不完（512 MB 机型上微信读书的 speed），同一个安装不再自动试（compile_needed），
    # 下面马上补 interpret-only（两分钟左右），不让应用停在没有 odex、从 apk 解释执行的状态
    elif [ "$ab" = timeout ]; then r=timeout
    # PackageManager 不在（Android 正在停 / system_server 重启）：不是这个包编不了，不能记成失败——
    # 同一个安装记满两次 fail 以后再也不会自动编（pw5-5182 实测：停 Android 那一刻两个包各记了一次 "Can't find service: package"）
    elif grep -q "Can't find service" $W/compile.out || ! alive $W/pid; then r=aborted
    elif [ "$f" = "$mode" ] && grep -q Success $W/compile.out; then r=ok
    # PackageManager 说成功了、过滤器却没变：应用自己要求安全模式（vmSafeMode，KOReader 就是）或系统把它压低了。
    # 这不是失败，再编多少次也一样：记成 capped，以后不再自动排队。
    elif [ -n "$f" ] && grep -q Success $W/compile.out; then r=capped
    else r=fail; fi
    # 加固 APK（如 libshell-super）触发 dex2oat SIGSEGV/SIGABRT：记为 crash，跳过 compile_light 且同版本不再自动重试
    [ "$r" = fail ] && compile_dex2oat_crashed "$cs_ts" $W/compile.out "$rc" && r=crash
    case "$oflags" in *--no-watch-dog*) ;; *) adb_sh "setprop dalvik.vm.dex2oat-flags '$oflags'" >/dev/null 2>&1 ;; esac
    # 失败时 installd 已经删掉了原来的 odex（pw6-5195 实测：interpret-only 的 odex 没了，应用只能从 apk 里边校验边解释）。
    # 马上（用原来的 dex2oat 参数，线程数仍按上面限制：kt6-5192 上不限时这一步 dex2oat 峰值 150 MB）补一个 interpret-only，
    # 至少回到安装时的状态。也在 babysit 下跑（nice 19、内存保护）。
    # 原来的 odex 编译期间一直保着（compile_keep_*），失败后它还在：只有一个 odex 也没有时才补；超时的大应用有档案、现在又不是
    # speed-profile 的，照旧马上改编 speed-profile
    if { [ $r = fail ] || [ $r = timeout ]; } && ! compile_held && ! compile_app_running "$pkg" &&
       { [ -z "$f" ] || { [ $r = timeout ] && [ "$f" != speed-profile ] && compile_has_profile "$pkg"; }; }; then
        compile_light "$pkg" $r
        t "compile: $pkg restored to $(odex_filter "$pkg")"
    fi
    # 被停掉（到了前台 / 起了进程 / 在用且内存紧 / Android 里有安装）时 installd 也已经把原来的 odex 截掉了：应用比编之前还慢
    # （filter=none，从 apk 解释执行；Scribe / Oasis 2 用户日志里微信读书一整天都是这样）。记进 compile.restore，等它退出、
    # 条件满足（工作进程挑包时，和别的编译同样的规矩）先补一个轻量的 interpret-only，speed 的重排照旧
    case "$ab" in foreground|running|busy|session|memory)
        [ $r = aborted ] && [ -z "$f" ] && { grep -qx "$pkg" $W/compile.restore 2>/dev/null || echo "$pkg" >> $W/compile.restore; } ;;
    esac
    [ -n "$thr" ] && adb_sh "setprop dalvik.vm.dex2oat-threads '$othr'" >/dev/null 2>&1
    t "compile: $pkg -m $mode -> $r (filter=${f:-none}, ${sec}s, rc=$rc$( [ -f $W/compile.abort ] && echo ", aborted: $(cat $W/compile.abort)"): $(tr '\n' ' ' < $W/compile.out | cut -c1-120))"
    # 记录最多 200 行
    { tail -n 199 $CLOG 2>/dev/null; echo "$pkg $dir $r $mode ${sec}s $(date +%Y%m%d-%H%M%S)"; } > $CLOG.tmp 2>/dev/null && mv -f $CLOG.tmp $CLOG
    echo "$pkg $r $sec" > $W/compile.last
    [ $r = ok ] || case "$ab" in
        foreground|running|busy|session|memory)
            sed -i "/^$pkg\( \|\$\)/d" $CQ 2>/dev/null; echo "$pkg force" >> $CQ
            echo $(( $(cat $W/compile.total 2>/dev/null || echo 0) + 1 )) > $W/compile.total
            if [ "$ab" = busy ] || [ "$ab" = memory ]; then
                : > $W/compile.needidle
                # 手动开始时还是被停了（内存掉到底线 COMPILE_MANUAL_ABORT_MB，或大应用编着时有应用到了前台）：手动这一批到此为止，等空闲再接着编（不然停了又开、开了又停）
                compile_manual && { rm -f $W/compile.manual; t "compile: manual start ended, the compile was stopped for the app in use (low memory or a large app with another app in front); continuing when idle"; }
            fi ;;
    esac
    [ "$r" != fail ] && [ "$r" != crash ]
}
compile_worker() {
    trap 'rm -f $W/compile.pid; rm -rf $W/compile.lock' EXIT
    compile_keep_cleanup
    idx=$(cat $W/compile.index 2>/dev/null || echo 0); gaveup=
    while [ -s $CQ ]; do
        # 容器没了就结束（stop.sh 也会按 pid 杀掉本进程）
        alive $W/pid || break
        mode=$(compile_mode)
        [ -n "$mode" ] || { compile_status state=paused reason=off code=off "total=$(cat $W/compile.total 2>/dev/null)"; rm -f $CQ; break; }
        last=$(cat $W/compile.last 2>/dev/null)
        tot=$(cat $W/compile.total 2>/dev/null)
        # 有安装 / 卸载在等 PackageManager 的锁：先让它做完
        if compile_held || compile_session_pending; then compile_status state=waiting reason=install code=install "index=$idx" "total=$tot" "last=$last" "msg=正在安装应用，装完马上继续"; compile_nap 3; continue; fi
        # 冻结（回到 Kindle / 屏保）时 Android 里的命令会一直卡住：等解冻
        if [ -f $W/frozen ] || [ -f $W/saver_frozen ]; then compile_status state=waiting reason=frozen code=frozen "index=$idx" "total=$tot" "last=$last" "msg=Android 暂停中（屏保或回到了 Kindle），回到 Android 后继续"; compile_nap 30; continue; fi
        if ! compile_battery_ok; then compile_status state=paused reason=battery code=battery "index=$idx" "total=$tot" "last=$last" "msg=电量低于 20%，插上电源后继续"; t "compile: paused, battery low"; compile_nap 300; continue; fi
        # 上次因为“在用 + 内存紧”停过：等用户不用了（COMPILE_IDLE_S 秒没摸屏）再开始，免得开了又停（手动开始时不等）
        if [ -f $W/compile.needidle ] && compile_user_busy; then
            compile_status state=waiting reason=你在用机器，等空闲时再优化 code=busy "index=$idx" "total=$tot" "last=$last" "msg=你在用机器，$(compile_idle_wait_text)（优化很占内存，用着的时候不做；充电不影响）"; compile_nap 30; continue
        fi
        # 桌面 / 输入法的 speed 编译（afterboot.sh 的 home_aot）在跑：一次只编一个，等它做完
        if alive $W/aot.pid; then compile_status state=waiting reason=正在优化桌面，稍后继续 code=aot "index=$idx" "total=$tot" "last=$last" "msg=正在优化桌面，稍后继续"; compile_nap 5; continue; fi
        # 取队列里第一个不在前台、也没有进程在跑的包；前台 / 在跑的留在队列里，等它离开前台、退出
        # （请过 Android 结束、还是在后台跑着的，过一会儿照样编，见 compile_runok；大应用在别的应用在前台时跳过，见 compile_big）
        fg=$(compile_fg); line=; crun=; cbig=
        while read -r cl_p cl_f; do
            [ -n "$cl_p" ] && [ "$cl_p" != "$fg" ] || continue
            if compile_fg_app && [ "$(compile_pkg_mode "$cl_p" "$mode")" != "$mode" ]; then cbig=$cl_p; continue; fi
            if compile_app_running "$cl_p"; then
                if compile_runok "$cl_p"; then t "compile: $cl_p still running in the background after am kill, compiling it anyway"
                else crun="$crun $cl_p"; continue; fi
            fi
            line="$cl_p${cl_f:+ $cl_f}"; break
        done < $CQ
        if [ -z "$line" ] && [ -n "$crun" ]; then
            compile_bg_kill $crun
            cr1=${crun# }; cr1=${cr1%% *}
            if compile_fg_abort; then cmsg="等它退出后再优化：它还在后台运行（有服务在跑），在“切换应用”里把它划掉就能马上开始"
            elif compile_manual || [ -f $W/compile.mkill.$cr1 ]; then cmsg="它还在后台运行，已请 Android 结束它，几十秒内开始"
            else cmsg="等应用退出后再优化（它还在后台运行，空闲时请 Android 结束它）"; fi
            compile_status state=waiting reason=等应用退出后再优化 code=running "pkg=$cr1" "index=$idx" "total=$tot" "last=$last" "msg=$cmsg"
            if compile_manual; then compile_nap 10; else compile_nap 30; fi; continue
        fi
        if [ -z "$line" ] && [ -n "$cbig" ]; then
            compile_status state=waiting reason=等你回到桌面再优化 code=large "pkg=$cbig" "index=$idx" "total=$tot" "last=$last" "msg=这个应用很大，优化时很占内存：等你回到桌面再做，免得挤掉正在用的应用"
            compile_nap 10; continue
        fi
        if [ -z "$line" ]; then
            compile_status state=waiting reason=等你不用这个应用时再优化 code=foreground "pkg=$fg" "index=$idx" "total=$tot" "last=$last" "msg=等你不用这个应用时再优化（回到桌面或换别的应用就开始）"
            compile_nap 10; continue
        fi
        # 内存放到挑好包以后再查（1 GB 机型）：要编的应用自己常驻在后台（微信读书 ~300 MB）时，以前先查内存（空闲要 180 MB）、
        # 永远不够，走不到上面的“请 Android 结束它”，一整天“not enough free memory … retrying in 30 min”（Scribe 用户日志 155–177 MB）。
        # 现在在跑的包先走上面的 am kill，它退了内存自然够了；挑出来的包本身没在跑时才按内存等。
        if ! compile_mem_ok; then
            if compile_user_busy; then
                # 用户在用：一直等（不算进下面的放弃计时），他不用了门槛就降下来
                compile_status state=waiting reason=你在用机器、内存不多，等空闲时再优化 code=busy "index=$idx" "total=$tot" "last=$last" "msg=你在用机器、可用内存 $(mem_avail_mb) MB（用着时要 $(compile_mem_need) MB）：$(compile_idle_wait_text)，清理后台也可能提前开始"
                compile_nap 30; continue
            fi
            compile_status state=waiting reason=memory code=memory "index=$idx" "total=$tot" "last=$last" "msg=可用内存不够（$(mem_avail_mb) MB，要 $(compile_mem_need) MB），内存够了就继续；下拉面板的“优化机器状态”或关掉后台应用能空出内存"
            # 排在前面、还在后台跑的待编应用（crun）占着内存：按同样的规矩请 Android 结束它们；在等它们的时候不算放弃计时
            if [ -n "$crun" ]; then compile_bg_kill $crun; compile_nap 30; continue; fi
            n=$(( $(cat $W/compile.memwait 2>/dev/null || echo 0) + 1 )); echo $n > $W/compile.memwait
            # 空闲了 10 分钟内存还是不够：工作进程先停，队列留着，30 分钟后看门狗（compile_watch）再起
            if [ $n -ge 20 ]; then
                t "compile: not enough free memory for 10 min ($(mem_avail_mb) MB), retrying in 30 min"
                rm -f $W/compile.memwait; echo $(( $(cut -d. -f1 /proc/uptime) + 1800 )) > $W/compile.retry_at
                compile_status state=paused reason=memory code=memory "index=$idx" "total=$tot" "last=$last" "msg=可用内存一直不够（$(mem_avail_mb) MB），约 30 分钟后再试；再点一次“立即优化”马上重看"; gaveup=1; break
            fi
            compile_nap 30; continue
        fi
        rm -f $W/compile.memwait
        # 上次被停掉、odex 已经没了的应用（compile.restore）：它退出了就先补一个 interpret-only，再接着排队编 speed
        compile_restore_next && continue
        set -- $line; pkg=$1; force=$2
        sed -i "/^$pkg\( \|\$\)/d" $CQ
        compile_needed "$pkg" "$force" || continue
        rm -f $W/compile.runwait.$pkg $W/compile.mkill.$pkg
        compile_user_active || rm -f $W/compile.needidle
        idx=$((idx + 1)); echo $idx > $W/compile.index
        pmode=$(compile_pkg_mode "$pkg" "$mode")
        # 大应用没有 JIT 档案（还没用过）、已经有安装时的 odex：没有更轻的可编，留着（不为一次 interpret-only 再占几百 MB）
        if { [ "$pmode" = interpret-only ] || [ "$pmode" = verify-at-runtime ]; } && [ -n "$(odex_filter "$pkg")" ] && [ "$force" != stale ]; then
            t "compile: $pkg is large ($(compile_dex_mb "$pkg" || echo ?) MB dex$(compile_timed_out "$pkg" speed && echo ", speed timed out before")), kept at $(odex_filter "$pkg") until it has a profile"
            continue
        fi
        [ "$pmode" != "$mode" ] && t "compile: $pkg is large ($(compile_dex_mb "$pkg" || echo ?) MB dex$(compile_timed_out "$pkg" speed && echo ", speed timed out before")), using $pmode instead of $mode"
        compile_status state=running code=running "pkg=$pkg" "index=$idx" "total=$tot" "last=$last" "filter=$pmode"
        compile_one "$pkg" "$pmode" "$force"
    done
    [ -n "$gaveup" ] && return 0
    # 一批结束：手动开始到此为止
    rm -f $W/compile.manual $W/compile.wake $W/compile.mkill.* 2>/dev/null
    compile_status state=idle code=done "last=$(cat $W/compile.last 2>/dev/null)" "done=$idx"
    # 一批结束：计数清零（下一批从 1/1 开始）
    [ -s $CQ ] || rm -f $W/compile.total $W/compile.index
}
# 命令：compile all | compile <包>（桌面“立即优化”、长按应用 / 传书网页的“优化”）。都是用户点的：手动开始（compile_manual），
# 状态马上写（以前要等工作进程第一次写，什么都不用编时一直停在桌面自己写的“已排队，正在开始…”）。
# all 也排上编好的代码已经不能用的包（dumpsys 说不是 kOatUpToDate，比如系统更新换了启动镜像），和开机扫描一样按 stale 编。
# 用户明确要求的编译在 APP_COMPILE=off 时也做（子 shell 里临时改成 speed，不影响命令监听进程自己的设置）
compile_cmd() {
    case "$1" in all) ;; *[!A-Za-z0-9._]*|'') return 1 ;; esac
    (
        [ -n "$(compile_mode)" ] || APP_COMPILE=speed
        date +%s > $W/compile.manual
        rm -f $W/compile.needidle $W/compile.retry_at $W/compile.memwait
        if [ "$1" = all ]; then
            for p in $(data_pkgs); do compile_enqueue "$p"; done
            compile_stale_pkgs | while read -r p st; do
                compile_stale_skip "$p" "$st" && continue
                grep -q "^$p\( \|\$\)" $CQ 2>/dev/null && continue
                compile_needed "$p" stale || continue
                t "compile: $p compiled code not usable ($st), queued by the manual start"
                compile_enqueue "$p" stale
            done
        else compile_enqueue "$1" force; fi
        if [ ! -s $CQ ]; then
            rm -f $W/compile.manual
            if [ "$1" = all ]; then cm="所有应用都已经优化好了，不用再做"; else cm="这个应用不能优化（没有装在数据区）"; fi
            compile_status state=idle code=nothing "last=$(cat $W/compile.last 2>/dev/null)" "msg=$cm"
            t "compile: manual start, nothing to compile"
            exit 0
        fi
        t "compile: manual start ($1), $(grep -c . $CQ) queued"
        if alive $W/compile.pid; then
            : > $W/compile.wake        # 工作进程在等（sleep 里）：叫醒它马上重看条件、重写状态
        else
            compile_status state=waiting code=starting "total=$(cat $W/compile.total 2>/dev/null)" "last=$(cat $W/compile.last 2>/dev/null)" "msg=马上开始…"
            compile_kick
        fi
    )
}
