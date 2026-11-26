# BEGIN vd_progress
# “方块”风格的进度画面（安装器、开机画面、回到 Kindle 共用；docs/progress-ui.md）。同一份代码在 cloud/static/bootstrap.sh 里
# 也有一份（# BEGIN vd_progress … # END vd_progress 之间，两份必须一样：tools/sync-progress.sh 从这里拷过去）。
# 白底；双线黑框 + 右下实心投影的标题框；黑底白字标题条；英文小字在中文粗体标题上面；方块进度条（一排方格，完成的涂黑）；粗体数字。
# 用 FBInk 命令行画（-k 画矩形、-t 用 Kindle 自带的 TTF 写字、-s 局部刷新）；没有能用的 FBInk（或它的刷新被驱动拒绝，
# Scribe 上 FBInk 不认识的机型）就退回 eips 一行行写字。墨水屏每刷一次都看得见：只在有变化时刷，
# 方格只补新涂黑的那几格，文字至少 5 秒才刷一次（vp_progress 的节流），整屏闪刷只在画面第一次出现时一次。
# 写给 VeraHome 看的状态文件（VP_STATUS，KEY=value，一行一个，先写 .tmp 再 mv）最多一秒一次。
# 本文件要在 set -eu 下也能用：变量都带默认值，失败的命令都不让它返回非 0。
VP_FB=; VP_MODE=none; VP_W=0; VP_H=0; VP_REF=0; VP_ON=; VP_STATUS=${VP_STATUS:-}
VP_TITLE="VeraDroid"; VP_CAPS="VERADROID"; VP_SUB=""; VP_FOOT=""; VP_STEPS=""; VP_CUR=""; VP_CUR_LABEL=""
VP_T0=$(date +%s); VP_ST0=$VP_T0; VP_LASTDRAW=0; VP_LASTST=0; VP_CELLS=-1; VP_PCT=-1; VP_L1=""; VP_L2=""; VP_DL1=""; VP_DL2=""; VP_KV=""
VP_N=20     # 方格数
vp_log() { :; }
vp_label() {
    case "$1" in
        net) echo "检查网络" ;; dl) echo "下载安装包" ;; copy) echo "读取离线包" ;; verify) echo "校验安装包" ;;
        backup) echo "备份书库" ;; disk) echo "建系统盘" ;; unpack) echo "解压安装包" ;; install) echo "安装系统" ;; boot) echo "首次开机" ;;
        kindle) echo "恢复 Kindle 界面" ;; android) echo "启动 Android" ;; apps) echo "优化应用" ;; *) echo "$1" ;;
    esac
}
vp_caps() {
    case "$1" in
        net) echo "NETWORK CHECK" ;; dl) echo "DOWNLOAD" ;; copy) echo "OFFLINE PACKAGE" ;; verify) echo "VERIFY" ;;
        backup) echo "BACKUP" ;; disk) echo "SYSTEM DISK" ;; unpack) echo "UNPACK" ;; install) echo "INSTALL" ;; boot) echo "FIRST BOOT" ;;
        kindle) echo "BACK TO KINDLE" ;; android) echo "STARTING ANDROID" ;; apps) echo "OPTIMIZING APPS" ;; *) echo "" ;;
    esac
}
# 状态文件里的 state（VeraHome 按它显示步骤）
vp_state() {
    case "$1" in net) echo checking ;; dl|copy) echo downloading ;; verify) echo verifying ;; unpack) echo unpacking ;;
        backup|disk|install) echo installing ;; boot) echo booting ;; *) echo "$1" ;; esac
}
vp_now() { date +%s; }
vp_mmss() { s=${1:-0}; [ "$s" -ge 0 ] 2>/dev/null || s=0; if [ "$s" -ge 3600 ]; then printf '%d:%02d:%02d' $((s / 3600)) $((s % 3600 / 60)) $((s % 60)); else printf '%d:%02d' $((s / 60)) $((s % 60)); fi; }
vp_mb() { b=${1:-0}; printf '%d.%d' $((b / 1048576)) $(( (b % 1048576) * 10 / 1048576 )); }
vp_rate() { b=${1:-0}; if [ "$b" -ge 1048576 ]; then echo "$(vp_mb "$b") MB/s"; else echo "$((b / 1024)) KB/s"; fi; }

# ---- 字体 / FBInk ----
vp_font() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return 0; }; done; echo ""; }
VPF=/usr/java/lib/fonts
VP_FZB=$(vp_font $VPF/STHeitiBold.ttf $VPF/STHeitiMedium.ttf "${VD:-/mnt/base-us/veradroid}/fonts/DroidSansFallback.ttf")
VP_FZR=$(vp_font $VPF/STHeitiMedium.ttf $VPF/STHeitiBold.ttf "${VD:-/mnt/base-us/veradroid}/fonts/DroidSansFallback.ttf")
VP_FEN=$(vp_font $VPF/Amazon-Ember-Bold.ttf $VPF/AmazonEmberBold-Bold.ttf $VPF/Amazon-Ember-Medium.ttf)
[ -n "$VP_FEN" ] || VP_FEN=$VP_FZB
# 找一个能跑的 FBInk：每个候选真的跑一次 -e（KT6 上越狱自带的那个会段错误）。$@ = 额外的候选（放在前面）
vp_fb_find() {
    for f in "$@" "${VD:-/mnt/base-us/veradroid}/bin/fbink" /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink /usr/bin/fbink /usr/local/bin/fbink; do
        [ -n "$f" ] && [ -x "$f" ] || continue
        e=$("$f" -e 2>/dev/null) || continue
        w=$(printf '%s' "$e" | tr ';' '\n' | sed -n 's/^viewWidth=//p' | head -1)
        h=$(printf '%s' "$e" | tr ';' '\n' | sed -n 's/^viewHeight=//p' | head -1)
        [ "${w:-0}" -gt 300 ] 2>/dev/null && [ "${h:-0}" -gt 300 ] 2>/dev/null || continue
        VP_FB=$f; VP_W=$w; VP_H=$h
        return 0
    done
    return 1
}
vp_sc() { echo $(( $1 * VP_W / 1272 )); }
vp_r() { "$VP_FB" -q -b -B "$5" -k "top=$2,left=$1,width=$3,height=$4" >/dev/null 2>&1 || true; }   # x y w h BLACK|WHITE
# x y w h px font fg bg center(0/1) text：先用背景色清掉这块，再写字（可以多行）
vp_t() {
    vp_r "$1" "$2" "$3" "$4" "$8"
    [ -n "${10:-}" ] && [ -n "$6" ] || return 0
    m=; [ "$9" = 1 ] && m=-m
    "$VP_FB" -q -b $m -C "$7" -B "$8" -O -t "regular=$6,px=$5,top=$2,bottom=$((VP_H - $2 - $4)),left=$1,right=$((VP_W - $1 - $3))" "${10}" >/dev/null 2>&1 || true
}
vp_ref() { # x y w h [waveform]：局部刷新（不闪）
    VP_REF=$((VP_REF + 1))
    "$VP_FB" -q -W "${5:-GL16}" -s "top=$2,left=$1,width=$3,height=$4" >/dev/null 2>&1 || true
}
# 双线框 + 投影：x y w h（w/h 含投影）
vp_frame() {
    sh_=$(vp_sc 9); st_=$(vp_sc 4); [ $st_ -ge 2 ] || st_=2; in_=$(vp_sc 11); is_=$(vp_sc 2); [ $is_ -ge 1 ] || is_=1
    vp_r $(($1 + sh_)) $(($2 + sh_)) $(($3 - sh_)) $(($4 - sh_)) BLACK
    vp_r "$1" "$2" $(($3 - sh_)) $(($4 - sh_)) BLACK
    vp_r $(($1 + st_)) $(($2 + st_)) $(($3 - sh_ - 2 * st_)) $(($4 - sh_ - 2 * st_)) WHITE
    if [ "${5:-0}" = 1 ]; then
        vp_r $(($1 + in_)) $(($2 + in_)) $(($3 - sh_ - 2 * in_)) $(($4 - sh_ - 2 * in_)) BLACK
        vp_r $(($1 + in_ + is_)) $(($2 + in_ + is_)) $(($3 - sh_ - 2 * in_ - 2 * is_)) $(($4 - sh_ - 2 * in_ - 2 * is_)) WHITE
    fi
}
# 版面（按 1272 宽的 PW6 定，其余按宽度等比；四种屏幕的宽高比都在 0.73–0.75，竖向同样按宽度缩）
vp_geom() {
    G_M=$(vp_sc 64); G_BW=$((VP_W - 2 * G_M)); G_SH=$(vp_sc 9); G_ST=$(vp_sc 4); [ $G_ST -ge 2 ] || G_ST=2
    G_HY=$(vp_sc 100); G_HH=$(vp_sc 240)
    G_SY=$(vp_sc 380); G_SHH=$(vp_sc 610)
    G_LY=$(vp_sc 1030); G_RH=$(vp_sc 60)
    G_PAD=$(vp_sc 36)
    G_BARY=$((G_SY + $(vp_sc 232))); G_BARH=$(vp_sc 62)
    G_BX=$((G_M + G_PAD)); G_BARW=$((G_BW - G_SH - 2 * G_PAD))
    G_GAP=$(vp_sc 8); G_CW=$(( (G_BARW - G_GAP * (VP_N - 1)) / VP_N ))
    G_PCTX=$((G_M + G_BW - G_SH - G_PAD - $(vp_sc 240))); G_PCTY=$((G_SY + $(vp_sc 104))); G_PCTW=$(vp_sc 240); G_PCTH=$(vp_sc 112)
    G_I1Y=$((G_SY + $(vp_sc 318))); G_I1H=$(vp_sc 58)
    G_I2Y=$((G_SY + $(vp_sc 388))); G_I2H=$(vp_sc 190)
    G_IX=$G_BX; G_IW=$G_BARW
}
vp_cell() { # i filled(0/1)
    x=$((G_BX + $1 * (G_CW + G_GAP))); b=$(vp_sc 4); [ $b -ge 2 ] || b=2
    vp_r $x $G_BARY $G_CW $G_BARH BLACK
    [ "$2" = 1 ] || vp_r $((x + b)) $((G_BARY + b)) $((G_CW - 2 * b)) $((G_BARH - 2 * b)) WHITE
}
vp_bar_all() { i=0; while [ $i -lt $VP_N ]; do f=0; [ $i -lt "${1:-0}" ] && f=1; vp_cell $i $f; i=$((i + 1)); done; }
vp_idx() { i=1; for s in $VP_STEPS; do [ "$s" = "$1" ] && { echo $i; return; }; i=$((i + 1)); done; echo 0; }
vp_nsteps() { set -- $VP_STEPS; echo $#; }
# 步骤列表的一行：id 状态（done / cur / todo）
vp_row() {
    i=$(vp_idx "$1"); y=$((G_LY + $(vp_sc 66) + (i - 1) * G_RH)); x=$((G_M + G_ST + $(vp_sc 6))); w=$((G_BW - G_SH - 2 * G_ST - $(vp_sc 12)))
    sq=$(vp_sc 30); sx=$((G_M + $(vp_sc 40))); sy=$((y + (G_RH - sq) / 2)); tx=$((G_M + $(vp_sc 96)))
    if [ "$2" = cur ]; then
        vp_r $x $((y + 2)) $w $((G_RH - 4)) BLACK
        vp_r $((sx + $(vp_sc 6))) $((sy + $(vp_sc 6))) $((sq - $(vp_sc 12))) $((sq - $(vp_sc 12))) WHITE
        vp_t $tx $((y + $(vp_sc 8))) $((w - tx + x - $(vp_sc 10))) $((G_RH - $(vp_sc 16))) $(vp_sc 34) "$VP_FZB" WHITE BLACK 0 "$(vp_label "$1")  ·  正在进行"
    else
        vp_r $x $((y + 2)) $w $((G_RH - 4)) WHITE
        vp_r $sx $sy $sq $sq BLACK
        b=$(vp_sc 4); [ $b -ge 2 ] || b=2
        [ "$2" = done ] || vp_r $((sx + b)) $((sy + b)) $((sq - 2 * b)) $((sq - 2 * b)) WHITE
        t="$(vp_label "$1")"; [ "$2" = done ] && t="$t  ·  完成"
        f=$VP_FZR; [ "$2" = done ] && f=$VP_FZB
        vp_t $tx $((y + $(vp_sc 8))) $((w - tx + x - $(vp_sc 10))) $((G_RH - $(vp_sc 16))) $(vp_sc 32) "$f" BLACK WHITE 0 "$t"
    fi
}
vp_rows() { past=1; [ "$(vp_idx "$VP_CUR")" -gt 0 ] || past=; for s in $VP_STEPS; do if [ "$s" = "$VP_CUR" ]; then vp_row $s cur; past=; elif [ -n "$past" ]; then vp_row $s done; else vp_row $s todo; fi; done; }
# 步骤框（标题条 + 英文小字 + 步骤名 + 百分比 + 方格 + 两块说明文字）
vp_stepbox() {
    bx=$G_M; by=$G_SY; bw=$G_BW
    vp_r $((bx + G_ST)) $((by + G_ST)) $((bw - G_SH - 2 * G_ST)) $((G_SHH - G_SH - 2 * G_ST)) WHITE
    n=$(vp_nsteps); i=$(vp_idx "$VP_CUR")
    hd="第 $i 步 / 共 $n 步"; [ "$i" = 0 ] && hd="${VP_HEAD:-请稍候}"
    vp_t $((bx + G_ST)) $((by + G_ST)) $((bw - G_SH - 2 * G_ST)) $(vp_sc 62) $(vp_sc 32) "$VP_FZB" WHITE BLACK 1 "$hd"
    vp_t $G_BX $((by + $(vp_sc 92))) $(vp_sc 700) $(vp_sc 34) $(vp_sc 24) "$VP_FEN" BLACK WHITE 0 "$(vp_caps "$VP_CUR")"
    vp_t $G_BX $((by + $(vp_sc 126))) $((G_PCTX - G_BX)) $(vp_sc 90) $(vp_sc 66) "$VP_FZB" BLACK WHITE 0 "$VP_CUR_LABEL"
    VP_CELLS=-1; VP_PCT=-1; VP_DL1=; VP_DL2=
}
# 整屏画一次（第一次出现 / 步骤变了而画面可能被别人盖掉时）：闪刷一次
vp_screen() {
    [ "$VP_MODE" = fb ] || return 0
    vp_geom
    vp_r 0 0 "$VP_W" "$VP_H" WHITE
    vp_frame $G_M $G_HY $G_BW $G_HH 1
    in_=$(vp_sc 34)
    vp_t $((G_M + in_)) $((G_HY + $(vp_sc 36))) $((G_BW - 2 * in_ - G_SH)) $(vp_sc 34) $(vp_sc 24) "$VP_FEN" BLACK WHITE 0 "$VP_CAPS"
    vp_t $((G_M + in_)) $((G_HY + $(vp_sc 72))) $((G_BW - 2 * in_ - G_SH)) $(vp_sc 84) $(vp_sc 60) "$VP_FZB" BLACK WHITE 0 "$VP_TITLE"
    vp_t $((G_M + in_)) $((G_HY + $(vp_sc 162))) $((G_BW - 2 * in_ - G_SH)) $(vp_sc 44) $(vp_sc 28) "$VP_FZR" BLACK WHITE 0 "$VP_SUB"
    vp_frame $G_M $G_SY $G_BW $G_SHH 0
    vp_stepbox
    if [ -n "$VP_STEPS" ]; then
        n=$(vp_nsteps); lh=$(( $(vp_sc 66) + n * G_RH + $(vp_sc 26) ))
        vp_frame $G_M $G_LY $G_BW $lh 0
        vp_t $((G_M + G_ST)) $((G_LY + G_ST)) $((G_BW - G_SH - 2 * G_ST)) $(vp_sc 56) $(vp_sc 28) "$VP_FZB" WHITE BLACK 1 "步骤"
        vp_rows
        fy=$((G_LY + lh + $(vp_sc 22)))
    else
        fy=$((G_SY + G_SHH + $(vp_sc 30)))
    fi
    [ -n "$VP_FOOT" ] && vp_t $G_M $fy $G_BW $(vp_sc 90) $(vp_sc 26) "$VP_FZR" BLACK WHITE 1 "$VP_FOOT"
    VP_REF=$((VP_REF + 1))
    if ! "$VP_FB" -q -f -s >/dev/null 2>&1; then
        # 刷新被驱动拒绝（Scribe：FBInk 不认识这台机器，MTK 驱动不收它的刷新）：退回 eips
        VP_MODE=eips; vp_log "progress: fbink refresh rejected, eips fallback"; vp_eips_all; return 0
    fi
    VP_ON=1; VP_LASTDRAW=$(vp_now)
}
# ---- eips 退路：几行字（每行先补空格盖掉上一次的字） ----
vp_eips() { command -v eips >/dev/null 2>&1 && { eips 1 "$1" "$(printf '%-46s' "$2")" >/dev/null 2>&1 || true; }; return 0; }
vp_ebar() { n=$(( ${1:-0} * 20 / 100 )); b=$(printf '%*s' $n '' | tr ' ' '#'); p=$(printf '%*s' $((20 - n)) '' | tr ' ' '-'); echo "[$b$p] ${1:-0}%"; }
vp_eips_all() {
    # eips 只有 ASCII 字形（中文字符它画不出来：“paint_char> character not available”），这里全用英文
    i=$(vp_idx "$VP_CUR"); n=$(vp_nsteps)
    vp_eips 2 "$VP_CAPS"
    vp_eips 4 "[$i/$n] $(vp_caps "$VP_CUR")"
    [ "$VP_PCT" -ge 0 ] 2>/dev/null && vp_eips 5 "$(vp_ebar $VP_PCT)" || vp_eips 5 ""
    vp_eips 6 "$(vp_ascii "$VP_L1")"
    if [ -n "${VP_EN2:-}" ]; then vp_eips 7 "$VP_EN2"; else vp_eips 7 "$(vp_ascii "$VP_L2" | head -c 46)"; fi
    VP_LASTDRAW=$(vp_now)
}
vp_ascii() { printf '%s' "$1" | head -1 | sed 's/剩余/left/g; s/已用/elapsed/g; s/·/|/g; s/速度/speed/g; s/正常/ok/g; s/很慢/slow/g; s/一般要/usually/g; s/左右//g' | tr -cd ' -~' | sed 's/  */ /g'; }
vp_en_err() {
    case "$1" in 11|12|13) [ -n "${NC_EN:-}" ] && { echo "$NC_EN"; return 0; } ;; esac   # vp_netcheck 说得更具体
    case "$1" in
        11) echo "No Wi-Fi. Connect in Kindle settings, then tap install again." ;;
        12) echo "Wi-Fi is on but no internet (DNS failed). Network problem." ;;
        13) echo "Cannot reach the VeraDroid server (blocked/maintenance). Network problem; try another network or the offline package." ;;
        14) echo "Network too slow, download timed out. Tap install again to resume, or use the offline package." ;;
        15) echo "Download corrupted twice (checksum). Try another network." ;;
        21) echo "Not enough storage. Free some space and tap install again." ;;
        *) echo "Device-side error. Restart the Kindle and try again; log: veradroid/logs/install.log" ;;
    esac
}
# ---- 对外 ----
# vp_begin 标题 英文小字 副标题 页脚 步骤表 [fbink 候选…]：选好画法（不画）
vp_begin() {
    VP_TITLE=$1; VP_CAPS=$2; VP_SUB=$3; VP_FOOT=$4; VP_STEPS=$5; shift 5
    VP_T0=$(vp_now)
    if vp_fb_find "$@" && [ -n "$VP_FZB" ]; then VP_MODE=fb
    elif command -v eips >/dev/null 2>&1; then VP_MODE=eips
    else VP_MODE=none; fi
    [ "${VP_FORCE_EIPS:-0}" = 1 ] && VP_MODE=eips
    return 0
}
# vp_show：让画面出现（安装器在 Android 还在前台时先不画，停掉 Android 后再调）
vp_show() {
    VP_ON=1
    case "$VP_MODE" in fb) vp_screen ;; eips) vp_eips_all ;; esac
    return 0
}
# 换了一个可用的 FBInk（安装包里解出来的）：从 eips 升级到整屏方块画面
vp_upgrade() {
    [ "$VP_MODE" = fb ] && return 0
    [ "${VP_FORCE_EIPS:-0}" = 1 ] && return 0
    vp_fb_find "$@" && [ -n "$VP_FZB" ] || return 0
    VP_MODE=fb; vp_log "progress: upgraded to fbink $VP_FB"
    [ -n "$VP_ON" ] && vp_screen
    return 0
}
# KEY=value 文件（一行一个；值里的换行写成 \n 两个字符，VeraHome 读的时候换回来），先写 .tmp 再 mv
vp_kvw() {
    kf=$1; shift
    [ -n "$kf" ] && [ -d "$(dirname "$kf")" ] || return 0
    { for a in "$@"; do printf '%s\n' "$a" | awk 'NR > 1 { printf "\\n" } { printf "%s", $0 } END { print "" }'; done
      echo "time=$(date +%s)"; } > "$kf.tmp" 2>/dev/null && chmod 644 "$kf.tmp" 2>/dev/null && mv -f "$kf.tmp" "$kf" 2>/dev/null   # 桌面（普通应用 uid）要能读：安装器的 umask 可能是 077
    return 0
}
vp_status() { # 写状态文件（KEY=value…）；VP_KV 里是这一步固定带上的键
    [ -n "$VP_STATUS" ] || return 0
    vp_kvw "$VP_STATUS" "$@" $VP_KV "elapsed=$(( $(vp_now) - VP_T0 ))"
    VP_LASTST=$(vp_now); return 0
}
# vp_step id [第一行说明]：进入新的一步。整屏画面在：只重画步骤框和列表里变了的两行
vp_step() {
    type vlog >/dev/null 2>&1 && vlog "step $1: up=$(cut -d' ' -f1 /proc/uptime 2>/dev/null) memavail=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null)MB boot=$(cut -c1-8 /proc/sys/kernel/random/boot_id 2>/dev/null)"
    prev=$VP_CUR; VP_CUR=$1; VP_CUR_LABEL=$(vp_label "$1"); VP_ST0=$(vp_now); VP_L1=${2:-}; VP_L2=${3:-}; VP_PCT=-1
    vp_status "state=$(vp_state "$1")" "step=$1" "label=$VP_CUR_LABEL" "index=$(vp_idx "$1")" "steps=$(echo $VP_STEPS | tr ' ' ',')"
    [ -n "$VP_ON" ] || return 0
    case "$VP_MODE" in
        fb)
            vp_stepbox
            [ -n "$VP_L1" ] && vp_t $G_IX $G_I1Y $G_IW $G_I1H $(vp_sc 36) "$VP_FZB" BLACK WHITE 0 "$VP_L1"
            [ -n "$VP_L2" ] && vp_t $G_IX $G_I2Y $G_IW $G_I2H $(vp_sc 30) "$VP_FZR" BLACK WHITE 0 "$VP_L2"
            VP_DL1=$VP_L1; VP_DL2=$VP_L2
            vp_ref $G_M $G_SY $G_BW $G_SHH GC16
            if [ -n "$VP_STEPS" ]; then
                # 只重画变了的几行：上一步（变成“完成”）到这一步之间
                pi=$(vp_idx "$prev"); ci=$(vp_idx "$VP_CUR"); [ "$pi" -gt 0 ] || pi=1
                for s in $VP_STEPS; do
                    si=$(vp_idx "$s")
                    if [ "$si" -eq "$ci" ]; then vp_row $s cur
                    elif [ "$si" -ge "$pi" ] && [ "$si" -lt "$ci" ]; then vp_row $s done
                    elif [ "$si" -gt "$ci" ] && [ "$si" -le "$pi" ]; then vp_row $s todo
                    fi
                done
                n=$(vp_nsteps); vp_ref $G_M $G_LY $G_BW $(( $(vp_sc 66) + n * G_RH + $(vp_sc 26) )) GC16
            fi
            VP_LASTDRAW=$(vp_now) ;;
        eips) vp_eips_all ;;
    esac
    return 0
}
# vp_progress 百分比(-1 = 不知道) 第一行 第二行 [force]：方格变了就画；字变了且离上次画已有 5 秒（VP_MIN）才画
vp_progress() {
    pct=${1:--1}; [ "$pct" -gt 100 ] 2>/dev/null && pct=100
    VP_L1=${2:-}; VP_L2=${3:-}; now=$(vp_now)
    [ $((now - VP_LASTST)) -ge 1 ] && vp_status "state=$(vp_state "$VP_CUR")" "step=$VP_CUR" "label=$VP_CUR_LABEL" "index=$(vp_idx "$VP_CUR")" \
        "steps=$(echo $VP_STEPS | tr ' ' ',')" "pct=$pct" "line1=$VP_L1" "line2=$VP_L2"
    [ -n "$VP_ON" ] || return 0
    cells=-1; [ "$pct" -ge 0 ] 2>/dev/null && cells=$((pct * VP_N / 100))
    textdue=; { [ "$VP_L1" != "$VP_DL1" ] || [ "$VP_L2" != "$VP_DL2" ] || [ "$pct" != "$VP_PCT" ]; } && [ $((now - VP_LASTDRAW)) -ge ${VP_MIN:-5} ] && textdue=1
    [ "${4:-}" = force ] && textdue=1
    [ "$cells" != "$VP_CELLS" ] || [ -n "$textdue" ] || return 0
    # 解压 / 安装时（VP_CMIN）：方格也至少隔这么多秒才画，少刷屏、少抢 CPU
    [ -z "$textdue" ] && [ "${4:-}" != force ] && [ $((now - VP_LASTDRAW)) -lt ${VP_CMIN:-0} ] && return 0
    case "$VP_MODE" in
        fb)
            if [ "$cells" != "$VP_CELLS" ]; then
                if [ "$VP_CELLS" -lt 0 ] 2>/dev/null || [ "$cells" -lt "$VP_CELLS" ] 2>/dev/null; then vp_bar_all $((cells < 0 ? 0 : cells))
                else i=$VP_CELLS; while [ $i -lt $cells ]; do vp_cell $i 1; i=$((i + 1)); done; fi
            fi
            ptxt=; [ "$pct" -ge 0 ] 2>/dev/null && ptxt="$pct%"
            vp_t $G_PCTX $G_PCTY $G_PCTW $G_PCTH $(vp_sc 76) "$VP_FEN" BLACK WHITE 1 "$ptxt"
            vp_t $G_IX $G_I1Y $G_IW $G_I1H $(vp_sc 36) "$VP_FZB" BLACK WHITE 0 "$VP_L1"
            vp_t $G_IX $G_I2Y $G_IW $G_I2H $(vp_sc 30) "$VP_FZR" BLACK WHITE 0 "$VP_L2"
            vp_ref $G_M $G_PCTY $((G_BW - G_SH)) $((G_I2Y + G_I2H - G_PCTY)) GL16 ;;
        eips) vp_eips_all ;;
    esac
    VP_CELLS=$cells; VP_PCT=$pct; VP_DL1=$VP_L1; VP_DL2=$VP_L2; VP_LASTDRAW=$now
    return 0
}
vp_elapsed() { vp_mmss $(( $(vp_now) - ${1:-$VP_ST0} )); }
# vp_watch pid 文件 总字节 第二行 [out]：后台命令跑着时每秒看一次它读到（out = 写到）文件的哪里，换成进度；
# 读不到位置时只走已用时间。命令结束就返回（返回值由调用方 wait 取）
vp_watch() {
    wp=$1; wf=$2; wt=${3:-0}; wl2=${4:-}; wo=${5:-}; rp=; rfd=; tries=0
    while kill -0 "$wp" 2>/dev/null; do
        pos=
        if [ -n "$wo" ]; then pos=$( { wc -c < "$wf"; } 2>/dev/null | tr -d " ")
        else
            [ -n "$rp" ] && [ ! -e /proc/$rp/fdinfo/$rfd ] && rp=     # 读文件的进程换了（tar 的 gzip 子进程之类）：重新找
            if [ -z "$rp" ] && [ $tries -lt 60 ]; then
                tries=$((tries + 1))
                for d in /proc/[0-9]*; do
                    n_=${d#/proc/}; [ "$n_" -ge "$wp" ] 2>/dev/null || continue
                    for l in $d/fd/*; do [ "$(readlink "$l" 2>/dev/null)" = "$wf" ] && { rp=$n_; rfd=${l##*/}; break 2; }; done
                done
            fi
            [ -n "$rp" ] && pos=$(sed -n 's/^pos:[[:space:]]*//p' /proc/$rp/fdinfo/$rfd 2>/dev/null || true)
        fi
        pct=-1; l1="已用 $(vp_elapsed)"
        if [ -n "$pos" ] && [ "$wt" -gt 0 ] 2>/dev/null; then
            pct=$((pos / 1024 * 100 / (wt / 1024 + 1))); [ $pct -gt 99 ] && pct=99
            l1="$(vp_mb "$pos") / $(vp_mb "$wt") MB · 已用 $(vp_elapsed)"
        fi
        vp_progress $pct "$l1" "$wl2"
        sleep 1
    done
    return 0
}
# 后台“秒表”：一段不知道进度的等待（框架冷启动、首次开机）——每 5 秒刷一次已用时间，方格按预计时间估（最多到 95%）
# vp_ticker_start 预计秒数 第二行 [超过多少秒后换成的第二行]；vp_ticker_stop
VP_TICK=
vp_ticker_start() {
    vp_ticker_stop
    # $$ 在子 shell 里仍是起它的那个脚本的 pid：脚本被硬杀（超时、SSH 断开）时 vp_ticker_stop 跑不到，
    # 以前这个子 shell 就成了孤儿，每 5 秒 fork 一次直到 Kindle 重启（社区报告：PW4 模拟器一轮回归攒了 24 个）。父进程没了就退
    ( exp=${1:-60}; [ "$exp" -gt 0 ] 2>/dev/null || exp=60
      while [ -d /proc/$$ ]; do
          e=$(( $(vp_now) - VP_ST0 )); p=$((e * 100 / exp)); [ $p -gt 95 ] && p=95
          l2=$2; [ -n "${3:-}" ] && [ $e -gt $((exp * 2)) ] && l2=$3
          vp_progress $p "已用 $(vp_mmss $e) · 一般要 $(vp_mmss $exp) 左右" "$l2"
          sleep 5
      done ) </dev/null >/dev/null 2>&1 &
    VP_TICK=$!
}
vp_ticker_stop() { [ -n "$VP_TICK" ] && kill "$VP_TICK" 2>/dev/null; VP_TICK=; return 0; }
# 出错画面：代码 + 谁的问题 + 怎么办
vp_error() { # code 标题 说明（可多行）
    VP_CUR_LABEL=$2
    vp_status "state=error" "step=$VP_CUR" "code=$1" "label=$2" "line2=$3"
    [ -n "$VP_ON" ] || [ "${VP_ERR_SHOW:-0}" = 1 ] || return 0
    VP_ON=1
    case "$VP_MODE" in
        fb)
            [ -n "${G_M:-}" ] || vp_geom
            VP_HEAD="没有完成 · 代码 $1"; c=$VP_CUR; VP_CUR=error
            vp_stepbox; VP_CUR=$c
            vp_t $G_IX $((G_SY + $(vp_sc 232))) $G_IW $((G_SHH - $(vp_sc 260))) $(vp_sc 32) "$VP_FZR" BLACK WHITE 0 "$3"
            vp_ref $G_M $G_SY $G_BW $G_SHH GC16 ;;
        eips) vp_eips 4 "FAILED, code $1"; vp_eips 5 ""; vp_eips 6 "$(vp_en_err "$1" | cut -c1-46)"; vp_eips 7 "$(vp_en_err "$1" | cut -c47-92)"; vp_eips 8 "$(vp_en_err "$1" | cut -c93-140)" ;;
    esac
    return 0
}
# ---- 网络检查（安装器下载前、桌面的“网络诊断”）：只连 $1（我们自己的服务器），不找第三方测速站 ----
# vp_netcheck <服务器根地址>：设 NC_WIFI NC_DNS NC_SRV（ok|fail|skip）、NC_CODE（失败时的代码）、NC_LINES（给人看的几行）
vp_netcheck() {
    u=$1; host=${u#*://}; host=${host%%/*}; host=${host%%:*}
    NC_WIFI=fail; NC_DNS=skip; NC_SRV=fail; NC_CODE=0; NC_LINES=; NC_MSG=; NC_EN=
    rt=$(awk '$2 == "00000000" && $1 != "lo" {print $1; exit}' /proc/net/route 2>/dev/null)
    ip_=
    [ -n "$rt" ] && ip_=$(ip -4 addr show "$rt" 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)
    cm=$(lipc-get-prop com.lab126.wifid cmState 2>/dev/null || true)
    if [ -n "$rt" ]; then NC_WIFI=ok; NC_LINES="■ Wi-Fi 已连接（${rt}${ip_:+ $ip_}）"
    else NC_LINES="□ Wi-Fi 没有连上${cm:+（$cm）}"; NC_CODE=11; NC_MSG="没连 Wi-Fi：请在 Kindle 的设置里连上 Wi-Fi 再试"; NC_EN="No Wi-Fi. Connect Wi-Fi, tap again."; return 1; fi
    w=$(curl_do -sS -o /dev/null -I --connect-timeout 10 --max-time 20 -w '%{time_namelookup} %{time_connect} %{time_appconnect} %{http_code}' "$u/" 2>/dev/null) && rc=0 || rc=$?
    set -- $w; hc=${4:-000}
    tn=$(awk -v t="${1:-0}" 'BEGIN { printf "%.2f", t }'); tc=$(awk -v t="${2:-0}" 'BEGIN { printf "%.2f", t }'); ta=$(awk -v t="${3:-0}" 'BEGIN { printf "%.2f", t }')
    case "$host" in *[!0-9.]*) dnsok=1 ;; *) dnsok=ip ;; esac
    if [ "$rc" = 6 ]; then
        NC_DNS=fail; NC_LINES="$NC_LINES
□ 域名解析失败：$host"; NC_CODE=12; NC_MSG="Wi-Fi 连上了，但上不了网（域名解析失败）：换个 Wi-Fi，或在 network.conf 里设 VD_DNS / VD_IP"; NC_EN="DNS failed for $host. Try another Wi-Fi."; return 1
    fi
    if [ "$dnsok" = ip ]; then NC_DNS=skip; NC_LINES="$NC_LINES
■ 域名解析：不需要（直接用 IP）"
    else NC_DNS=ok; NC_LINES="$NC_LINES
■ 域名解析 $host（${tn} 秒）"; fi
    if [ "$rc" = 0 ] && [ "$hc" -lt 500 ] 2>/dev/null; then
        NC_SRV=ok; t_=$ta; [ "$t_" = 0.00 ] && t_=$tc
        NC_LINES="$NC_LINES
■ 服务器能连上（${t_} 秒）"; return 0
    fi
    NC_SRV=fail; NC_CODE=13
    # 说清楚卡在哪一步（Scribe 用户只看到“网络不行”）：连接被拒 / 连接超时 / TLS 握手卡住或失败（Kindle 时间不对时证书全部无效）/ 太慢。
    # NC_OZ = 这台机器该用的离线整包（按内核，和 VeraDroid-Offline.sh 的规则一样），NC_EN = 给 eips 的英文一句
    case "$(uname -r)" in 3.*|4.1.*) NC_OZ=VeraDroid-Offline-Legacy.zip ;; 4.*) NC_OZ=VeraDroid-Offline-Experimental.zip ;; *) NC_OZ=VeraDroid-Offline.zip ;; esac
    off="换个网络，或改用离线整包 $NC_OZ（官网下载，电脑拷进 documents）"
    case "$rc" in
        28) if [ "$tc" != 0.00 ] && [ "$ta" = 0.00 ] && [ "$dnsok" = 1 ]; then
                NC_LINES="$NC_LINES
□ 连上了服务器（${tc} 秒），但安全连接（TLS）握手 20 秒没完成"; NC_MSG="HTTPS 握手卡住（常见于网络干扰 HTTPS）。不是机器的问题：$off"; NC_EN="TLS handshake stalled (HTTPS blocked?). Use $NC_OZ"
            elif [ "$tc" = 0.00 ]; then
                NC_LINES="$NC_LINES
□ 服务器 10 秒连不上（连接超时）"; NC_MSG="连不上 VeraDroid 服务器（连接超时：被拦了或网络太差）。不是机器的问题：$off"; NC_EN="Connect timed out (blocked?). Use $NC_OZ"
            else
                NC_LINES="$NC_LINES
□ 服务器 20 秒没有回应（网络太慢）"; NC_MSG="网络太慢，服务器 20 秒没有回应。不是机器的问题：$off"; NC_EN="Network too slow. Use $NC_OZ"
            fi ;;
        7) NC_LINES="$NC_LINES
□ 连接被拒绝 / 被拦截（curl 7）"; NC_MSG="连不上 VeraDroid 服务器（连接被拒绝或被拦截）。不是机器的问题：$off"; NC_EN="Connection refused/blocked. Use $NC_OZ" ;;
        35|51|53|54|58|59|60|64|66|77|80|83|90|91)
            yr=$(date +%Y 2>/dev/null)
            if [ "${yr:-2026}" -lt 2025 ] 2>/dev/null; then
                NC_LINES="$NC_LINES
□ 安全连接（TLS）失败：Kindle 的时间不对（$(date '+%Y-%m-%d')）"; NC_MSG="Kindle 的日期时间不对，HTTPS 证书全部校验失败：在 Kindle 设置里改对日期时间，再点一次"; NC_EN="Kindle clock wrong ($(date +%F)): fix date/time"
            else
                NC_LINES="$NC_LINES
□ 安全连接（TLS）失败（curl $rc）"; NC_MSG="HTTPS 连接失败（网络在拦截或篡改 HTTPS）。不是机器的问题：$off"; NC_EN="TLS/HTTPS failed (curl $rc). Use $NC_OZ"
            fi ;;
        0) NC_LINES="$NC_LINES
□ 服务器在维护（HTTP $hc）"; NC_MSG="VeraDroid 服务器暂时不可用（HTTP $hc）：过一会儿再试，或改用离线整包 $NC_OZ"; NC_EN="Server unavailable (HTTP $hc). Retry later" ;;
        *) NC_LINES="$NC_LINES
□ 连不上服务器（curl $rc）"; NC_MSG="连不上 VeraDroid 服务器（可能被墙或服务器维护）。不是机器的问题：$off"; NC_EN="Cannot reach server (curl $rc). Use $NC_OZ" ;;
    esac
    return 1
}
# 桌面“网络诊断”（宿主命令 netdiag，结果写 $X/ctl/netdiag.status）：和安装器同样的三项检查，再用真实的安装包测几秒下载速度
# （只连我们自己的服务器，不找第三方测速站）。在子 shell 里跑：会读 network.conf（代理 / DNS / IP）。
vp_netdiag() { # 输出文件 [服务器根地址]
    nf=$1; nb=${2:-https://veradroid.wenjin.jp}
    VD=${VD:-/mnt/base-us/veradroid}
    [ -f "$VD/network.conf" ] && . "$VD/network.conf"
    CURL_OPTS=
    [ -n "${VD_PROXY:-}" ] && CURL_OPTS="$CURL_OPTS -x $VD_PROXY"
    [ -n "${VD_IP:-}" ] && CURL_OPTS="$CURL_OPTS --resolve veradroid.wenjin.jp:443:$VD_IP --resolve veradroid.wenjin.jp:80:$VD_IP"
    [ -n "${VD_DNS:-}" ] && CURL_OPTS="$CURL_OPTS --dns-servers $VD_DNS"
    type curl_do >/dev/null 2>&1 || curl_do() { curl $CURL_OPTS "$@"; }
    t0=$(date +%s)
    vp_kvw "$nf" state=running step=check "line1=正在检查 Wi-Fi、域名解析、服务器…" "start=$t0"
    if ! vp_netcheck "$nb"; then
        case "$NC_CODE" in 11) v=offline; c="没连 Wi-Fi" ;; 12) v=nodns; c="上不了网（域名解析失败）" ;; *) v=noserver; c="服务器连不上" ;; esac
        vp_kvw "$nf" state=done "verdict=$v" "code=$NC_CODE" "conclusion=$c" "lines=$NC_LINES" "line2=$NC_MSG" "start=$t0"
        return 0
    fi
    vp_kvw "$nf" state=running step=speed "line1=正在测下载速度（用真实的安装包下 6 秒）…" "lines=$NC_LINES" "start=$t0"
    case "$(uname -r)" in 3.*|4.1.*) m="$nb/static/bootstrap-legacy.json?t=$t0" ;; 4.*) m="$nb/static/bootstrap-k49.json?t=$t0" ;; *) m="$nb/static/bootstrap-pw6.json?t=$t0" ;; esac
    u=$(curl_do -fsSL --max-time 15 "$m" 2>/dev/null | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p')
    [ -z "$u" ] && case "$m" in */bootstrap-pw6.json*) u=$(curl_do -fsSL --max-time 15 "$nb/api/bootstrap" 2>/dev/null | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p') ;; esac
    sp=0
    [ -n "$u" ] && sp=$(curl_do -s -o /dev/null --connect-timeout 10 --max-time 6 -w '%{speed_download}' "$u" 2>/dev/null | cut -d. -f1)
    case "$sp" in ''|*[!0-9]*) sp=0 ;; esac
    min=$(( 141557760 / (sp + 1) / 60 ))
    if [ -z "$u" ]; then v=noserver; c="服务器连得上，但查不到安装包"; l="□ 查不到安装包信息"
    elif [ "$sp" -ge 204800 ]; then v=ok; c="网络正常"; l="■ 速度 $(vp_rate $sp)（整包约 $((min + 1)) 分钟）"
    elif [ "$sp" -ge 51200 ]; then v=slow; c="网络慢"; l="□ 速度 $(vp_rate $sp)（整包约 $((min + 1)) 分钟）"
    else v=slow; c="网络很慢"; l="□ 速度 $(vp_rate $sp)（太慢：建议换网络或用离线整包）"; fi
    w=""; [ "$v" = slow ] && w="不是机器的问题：可以换个网络，或改用离线整包（官网下载，电脑拷进 Kindle）"
    vp_kvw "$nf" state=done "verdict=$v" "conclusion=$c" "speed=$sp" "lines=$NC_LINES
$l" "line2=$w" "start=$t0"
    return 0
}
# END vd_progress
