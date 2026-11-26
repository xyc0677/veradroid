# 更新前备份和已备份的数据（设置 → 设备与更新 → 更新前备份 / 已备份的数据，VeraHome 的 BackupActivity）。
# 这个文件在 host/pw6、host/k49、host/legacy 里是同一份（tests/backup-opt-test.sh 核对）；vd_backup_mode 块和两个安装器里的一字不差。
#
# 安装器（bootstrap.sh / bootstrap-legacy.sh）每次安装 / 更新前按 UPDATE_BACKUP 备份：
#   full  = 设置、阅读进度和书（书 300 MB 以内，以前的做法，默认）
#   light = 只备份设置、阅读进度、笔记这些小文件（不备份书）
#   off   = 不备份。书库清单（state/docs-manifest）照样记，装完第一次开机照样逐个核对；只是少了文件补不回来
# 结果写在 veradroid/state/backup.last（stamp= mode= kind= result= file=），每份备份是哪种记在 state/backup-kinds。
#
# 这里（宿主）做三件事，都只经管理请求（webadmin.sh 的 wa_do：只认 VeraHome 自己私有目录里的请求，别的应用冒充不了）：
#   updatebackup full|light|off|?   改设置（settings.conf + state/update-backup 一份副本）
#   backups                          列出已备份的数据（kv）
#   backupdel new|root <名字> / all 删除。名字只认安装器按固定格式起的那几种，路径由这里拼，绝不接受界面给的路径；
#                                    只删 veradroid/backup 里的（和根目录里老版本留下、vd_root_tidy 会搬的同名文件）。
# 绝不碰：用户改过名的（…-KEEP-… 等）、别的文件夹（vd18-keep/ 等）、veradroid/backup 以外的任何东西。
# 不能删（显示“正在使用，暂不能删”）：正在安装 / 离线更新时全部；这次安装做的备份在第一次开机核对书库之前（state/docs-manifest 还在）。
VB_TOP=${VD_TOP:-/mnt/base-us}
VB_DIR=$VB_TOP/veradroid/backup
VB_ST=$VB_TOP/veradroid/state
VB_D8='[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]'
# BEGIN vd_backup_mode
# 更新前备份的方式：环境变量 VD_UPDATE_BACKUP（离线更新 / 自动更新传进来）> settings.conf 的 UPDATE_BACKUP >
# veradroid/state/update-backup（宿主每次发布设置时写的副本：系统盘是镜像的机型，安装时镜像可能没挂上，读不到 settings.conf）。
# settings.conf 在却没写这一项 = 默认 full。认不出的值一律按 full（宁可多备份）。
vd_backup_mode() {
    vbm=${VD_UPDATE_BACKUP:-}
    vbc=${SYS:-/mnt/base-us/system/veradroid}/settings.conf
    if [ -z "$vbm" ] && [ -f "$vbc" ]; then
        vbm=$(sed -n 's/^UPDATE_BACKUP=//p' "$vbc" 2>/dev/null | tail -1 || true)
        vbm=${vbm:-full}
    fi
    [ -n "$vbm" ] || vbm=$(head -c 16 "${VD_TOP:-/mnt/base-us}/veradroid/state/update-backup" 2>/dev/null || true)
    case "$vbm" in full|light|off) echo "$vbm" ;; *) echo full ;; esac
}
# END vd_backup_mode

vb_log() { if type t >/dev/null 2>&1; then t "$@" >&2; fi; }
vb_live() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

# 设置的副本（settings_publish 每次都调，内容没变就不写）
ub_mirror() {
    um=$(vd_backup_mode)
    [ "$(cat "$VB_ST/update-backup" 2>/dev/null)" = "$um" ] && return 0
    mkdir -p "$VB_ST" 2>/dev/null && echo "$um" > "$VB_ST/update-backup.tmp" && mv -f "$VB_ST/update-backup.tmp" "$VB_ST/update-backup" || true
}

# 名字 -> 种类（user 安装前备份 | cc 书库数据库 | docs / docsr 老版本的书库备份 | media 卸载时留下的内部存储）；不认识就失败
vb_kind() {
    case "$1" in
        user-backup-$VB_D8-[0-9][0-9][0-9][0-9][0-9][0-9].tgz) echo user ;;
        cc.db.$VB_D8-[0-9][0-9][0-9][0-9][0-9][0-9]) echo cc ;;
        documents-backup.tgz) echo docs ;;
        documents-backup.restored-$VB_D8-[0-9][0-9][0-9][0-9][0-9][0-9].tgz) echo docsr ;;
        media-keep) echo media ;;
        *) return 1 ;;
    esac
}

# 位置 名字 -> 完整路径。位置 new = veradroid/backup，root = 根目录里老版本留下的同名文件（加 veradroid- 前缀）。
# 检查：名字格式对、只有字母数字和 ._-、路径上没有符号链接、真实路径正好落在那个目录里。
vb_path() {
    case "$2" in ''|*/*|.*|*[!A-Za-z0-9._-]*) return 1 ;; esac
    vb_kind "$2" >/dev/null || return 1
    [ -d "$VB_TOP/veradroid" ] && [ ! -L "$VB_TOP/veradroid" ] || return 1
    case "$1" in
        new)  [ -d "$VB_DIR" ] && [ ! -L "$VB_DIR" ] || return 1; vp_d=$VB_DIR; vp_p=$VB_DIR/$2 ;;
        root) vp_d=$VB_TOP; vp_p=$VB_TOP/veradroid-$2 ;;   # root-legacy: 根目录里老版本的备份
        *) return 1 ;;
    esac
    [ -e "$vp_p" ] && [ ! -L "$vp_p" ] || return 1
    if [ "$2" = media-keep ]; then [ -d "$vp_p" ] || return 1; else [ -f "$vp_p" ] || return 1; fi
    vp_r=$(readlink -f "$vp_p" 2>/dev/null || true); vp_rd=$(readlink -f "$vp_d" 2>/dev/null || true)
    [ -n "$vp_rd" ] && [ "$vp_r" = "$vp_rd/${vp_p##*/}" ] || return 1
    echo "$vp_p"
}

# 现在有的：每行“位置 名字”，新位置在前；名字倒序 = 安装前备份在最前、每种按名字里的时间从新到旧
vb_items() {
    for vi_w in new root; do
        if [ $vi_w = new ]; then vi_pre=$VB_DIR/; else vi_pre=$VB_TOP/veradroid-; fi   # root-legacy
        for vi_f in $(ls -d "$vi_pre"user-backup-*.tgz "$vi_pre"cc.db.* "$vi_pre"documents-backup*.tgz "$vi_pre"media-keep 2>/dev/null | sort -r); do
            vi_n=${vi_f#"$vi_pre"}
            vb_path $vi_w "$vi_n" >/dev/null && echo "$vi_w $vi_n"
        done
    done
    return 0
}

# 有安装 / 离线更新正在进行（安装器拿着锁；老版本的安装器拿着根目录的锁；正在写 .partial）
vb_busy() {
    vb_live "$(cat "$VB_ST/install.lock/pid" 2>/dev/null)" && return 0
    vb_live "$(cat "$VB_TOP/veradroid-install.lock/pid" 2>/dev/null)" && return 0   # root-legacy: 老版本安装器的锁
    vb_live "$(cat /dev/shm/veradroid/localupdate.lock/pid 2>/dev/null)" && return 0
    [ -f "$VB_DIR/user-backup.partial" ] && [ -n "$(find "$VB_DIR/user-backup.partial" -mmin -30 2>/dev/null)" ] && return 0
    return 1
}
# 这次安装的书库核对还没做完（新系统第一次开机完成后 afterboot.sh 的 boot_data_check 把 docs-manifest 改名 .checked）
vb_pending() { [ -f "$VB_ST/docs-manifest" ] || [ -f "$VB_TOP/veradroid-docs-manifest" ]; }   # root-legacy: 老安装器写在根目录
# 核对之前要留着的：这次安装做的那份备份和书库数据库。老安装器没有写 backup.last：按最新的一份算。
vb_pending_names() {
    vb_pending || return 0
    if [ -f "$VB_ST/backup.last" ]; then
        sed -n 's/^file=//p' "$VB_ST/backup.last" | tail -1
        vpn_s=$(sed -n 's/^stamp=//p' "$VB_ST/backup.last" | tail -1)
        [ -n "$vpn_s" ] && echo "cc.db.$vpn_s"
    else
        for vpn_f in $(ls -d "$VB_DIR"/user-backup-*.tgz 2>/dev/null | sort -r); do vb_path new "${vpn_f##*/}" >/dev/null && { echo "${vpn_f##*/}"; break; }; done
        for vpn_f in $(ls -d "$VB_DIR"/cc.db.* 2>/dev/null | sort -r); do vb_path new "${vpn_f##*/}" >/dev/null && { echo "${vpn_f##*/}"; break; }; done
    fi
    return 0
}
# 位置 名字 -> 不能删的原因（install / verify），能删就什么都不输出
vb_lock() {
    if vb_busy; then echo install; return 0; fi
    [ "$1" = new ] || return 0
    vb_pending_names | grep -qxF "$2" && echo verify
    return 0
}

vb_kb() { # 路径 -> KB
    if [ -d "$1" ]; then du -sk "$1" 2>/dev/null | awk '{print $1 + 0}'
    else stat -c %s "$1" 2>/dev/null | awk '{print int(($1 + 1023) / 1024)}'; fi
}
vb_time() { # 名字 路径 -> “2026-11-18 21:05”：名字里的时间，没有就看文件时间
    vt_s=$(printf '%s\n' "$1" | sed -n 's/.*\([0-9]\{8\}\)-\([0-9]\{6\}\).*/\1\2/p')
    if [ -n "$vt_s" ]; then printf '%s\n' "$vt_s" | sed 's/^\(....\)\(..\)\(..\)\(..\)\(..\).*/\1-\2-\3 \4:\5/'; return 0; fi
    vt_m=$(stat -c %Y "$2" 2>/dev/null || true)
    [ -n "$vt_m" ] && date -d "@$vt_m" '+%Y-%m-%d %H:%M' 2>/dev/null || true
}

# 列表（kv，VeraHome 的 BackupActivity 读）
vb_info() {
    echo "mode=$(vd_backup_mode)"
    if vb_busy; then echo busy=1; else echo busy=0; fi
    if vb_pending; then echo pending=1; else echo pending=0; fi
    echo "free_kb=$(df -Pk "$VB_TOP" 2>/dev/null | awk 'NR==2{print $4 + 0}')"
    if [ -f "$VB_ST/backup.last" ]; then
        for vi_k in stamp mode kind result; do echo "last.$vi_k=$(sed -n "s/^$vi_k=//p" "$VB_ST/backup.last" | tail -1 | tr -cd 'A-Za-z0-9._-')"; done
    fi
    vb_items | {
        vi_i=0; vi_tot=0
        while read -r vi_w vi_n; do
            vi_p=$(vb_path "$vi_w" "$vi_n") || continue
            vi_i=$((vi_i + 1)); vi_kb=$(vb_kb "$vi_p"); vi_kb=${vi_kb:-0}; vi_tot=$((vi_tot + vi_kb))
            echo "i.$vi_i.where=$vi_w"; echo "i.$vi_i.name=$vi_n"; echo "i.$vi_i.kind=$(vb_kind "$vi_n")"
            echo "i.$vi_i.kb=$vi_kb"; echo "i.$vi_i.time=$(vb_time "$vi_n" "$vi_p")"
            echo "i.$vi_i.light=$(grep -qxF "$vi_n light" "$VB_ST/backup-kinds" 2>/dev/null && echo 1 || echo 0)"
            echo "i.$vi_i.lock=$(vb_lock "$vi_w" "$vi_n")"
        done
        echo "n=$vi_i"; echo "total_kb=$vi_tot"
    }
}

# 删一份：0 = 删了，2 = 正在使用，1 = 名字 / 路径不对
vb_rm() { # 位置 名字
    vr_p=$(vb_path "$1" "$2") || return 1
    [ -z "$(vb_lock "$1" "$2")" ] || return 2
    if [ -d "$vr_p" ]; then rm -rf "$vr_p" || return 1; else rm -f "$vr_p" || return 1; fi
    [ ! -e "$vr_p" ] || return 1
    return 0
}
vb_del() { # “new 名字” | “root 名字” | all
    if [ "$1" = all ]; then vb_items; else echo "$1"; fi | {
        vd_n=0; vd_kb=0; vd_keep=0; vd_bad=0
        while read -r vd_w vd_nm vd_x; do
            [ -z "$vd_x" ] || { vd_bad=$((vd_bad + 1)); continue; }
            vd_p=$(vb_path "$vd_w" "$vd_nm") || { vd_bad=$((vd_bad + 1)); vb_log "backup: refused to delete '$vd_w $vd_nm'"; continue; }
            vd_k=$(vb_kb "$vd_p"); vd_rc=0; vb_rm "$vd_w" "$vd_nm" || vd_rc=$?
            case $vd_rc in
                0) vd_n=$((vd_n + 1)); vd_kb=$((vd_kb + ${vd_k:-0})); vb_log "backup: deleted $vd_w/$vd_nm (${vd_k:-?}K, asked from VeraHome)"
                   if [ "$vd_w" = new ] && [ -f "$VB_ST/backup-kinds" ]; then
                       grep -vF "$vd_nm " "$VB_ST/backup-kinds" > "$VB_ST/backup-kinds.tmp" 2>/dev/null || true
                       mv -f "$VB_ST/backup-kinds.tmp" "$VB_ST/backup-kinds" 2>/dev/null || true
                   fi ;;
                2) vd_keep=$((vd_keep + 1)) ;;
                *) vd_bad=$((vd_bad + 1)) ;;
            esac
        done
        echo "deleted=$vd_n"; echo "freed_kb=$vd_kb"; echo "kept=$vd_keep"; echo "refused=$vd_bad"
        if [ $vd_bad -gt 0 ] && [ $vd_n = 0 ]; then echo "error=refused"; elif [ $vd_keep -gt 0 ] && [ $vd_n = 0 ]; then echo "error=inuse"; fi
        return 0
    }
}

# 改设置：settings.conf（root 会 source 它，值只认三个词）+ 副本
vb_setmode() { # full|light|off|?
    case "$1" in
        full|light|off)
            [ -n "${SYS:-}" ] && [ -d "$SYS" ] || { echo "error=nosys"; return 0; }
            touch "$SYS/settings.conf" 2>/dev/null
            { grep -v '^UPDATE_BACKUP=' "$SYS/settings.conf" 2>/dev/null; echo "UPDATE_BACKUP=$1"; } > "$SYS/settings.conf.ub.tmp" \
                && mv -f "$SYS/settings.conf.ub.tmp" "$SYS/settings.conf" || { rm -f "$SYS/settings.conf.ub.tmp"; echo "error=write"; return 0; }
            ub_mirror
            vb_log "settings: UPDATE_BACKUP=$1 (from VeraHome)" ;;
        '?') ;;
        *) echo "error=bad value"; return 0 ;;
    esac
    echo "ok=1"; echo "mode=$(vd_backup_mode)"
}
