#!/bin/sh
# VeraDroid PW6 one-click bootstrap for an already jailbroken Kindle with SSH.
#   curl -fsSL https://veradroid.wenjin.jp/bootstrap.sh | sh
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH   # 系统自带的 curl/tar 优先：越狱工具或插件装的 curl 可能没有根证书（HTTPS 全部失败）
set -eu
BASE="${VD_BASE:-https://veradroid.wenjin.jp}"
MIRROR="${VD_MIRROR:-}"
# Optional network overrides for restricted networks:
#   /mnt/base-us/veradroid/network.conf
#   VD_PROXY=socks5h://192.0.2.2:1080
#   VD_IP=203.0.113.10
#   VD_DNS=223.5.5.5
[ -f /mnt/base-us/veradroid/network.conf ] && . /mnt/base-us/veradroid/network.conf
CURL_OPTS=
[ -n "${VD_PROXY:-}" ] && CURL_OPTS="$CURL_OPTS -x $VD_PROXY"
[ -n "${VD_IP:-}" ] && CURL_OPTS="$CURL_OPTS --resolve veradroid.wenjin.jp:443:$VD_IP --resolve veradroid.wenjin.jp:80:$VD_IP"
[ -n "${VD_DNS:-}" ] && CURL_OPTS="$CURL_OPTS --dns-servers $VD_DNS"
curl_do() { curl $CURL_OPTS "$@"; }
# --retry-all-errors 是 curl 7.71 才有的：老固件（Voyage 5.13.6 自带 curl 7.61）遇到不认识的选项直接退出，
# 安装包一个字节都不下载就报“下载失败”（kv-5136 模拟器实测）。不支持时只用 --retry（只重试临时性错误，断点续传照旧）。
RETRY_ALL=; curl --help all 2>/dev/null | grep -q -- --retry-all-errors && RETRY_ALL=--retry-all-errors
SYS=/mnt/base-us/system/veradroid
VD=/mnt/base-us/veradroid
STABLE=$SYS/device-id
MODEL_FILE=$SYS/model
VERSION_FILE=$SYS/version
FULL_LOG=$SYS/fullupdate.log
W=/dev/shm/veradroid
SYSTEM_IMG=/mnt/base-us/veradroid-sys.img
# BEGIN vd_root_tidy
# 2026-11-18 起 VeraDroid 自己的日志、标记和备份都放在程序目录 veradroid/ 下面三个文件夹里，不再散在根目录：
#   veradroid/logs    install.log、install-detail.log、uninstall.log、autoupdate.log、fsck.log、localupdate.log、boot.log、resize.log
#   veradroid/state   install.lock、install.incomplete、install.<pid>（安装器临时目录）、autoupdate.lock、update.stage、
#                     docs-manifest、confirm、launchers-restored、resize.*
#   veradroid/backup  user-backup-<时间>.tgz（安装前备份）、cc.db.<时间>、documents-backup*.tgz、media-keep/（卸载时留下的书）
# 这三个文件夹不是程序：安装器把程序目录挪成 host.bak、回退时挪回来、卸载删程序，都不带上它们（vd_prog_move / vd_is_data）。
# 留在根目录的只有系统盘镜像 veradroid-sys.img 和老机型的交换文件 veradroid-swap：以前的安装器（用户手里的旧离线包）
# 会把整个 veradroid/ 挪进系统盘镜像里，镜像和交换文件要是在 veradroid/ 里就是把镜像往它自己里面拷。
# 读的地方都先看新位置、再看根目录（老版本的安装器 / 自动更新 / 离线更新还会往根目录写），vd_root_tidy 再把根目录的搬进来。
VD_TOP=${VD_TOP:-/mnt/base-us}
VD_LOGS=$VD_TOP/veradroid/logs
VD_STATE=$VD_TOP/veradroid/state
VD_BACKUP=$VD_TOP/veradroid/backup
vd_is_data() { case "$1" in logs|state|backup) return 0 ;; esac; return 1; }
# vd_prog_move 源 目标：把源目录里除 logs / state / backup 以外的东西逐个挪进目标（需要时才建目标）
vd_prog_move() {
    [ -d "$1" ] || return 0
    for vpm_e in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        [ -e "$vpm_e" ] || [ -L "$vpm_e" ] || continue
        vd_is_data "${vpm_e##*/}" && continue
        mkdir -p "$2" && mv "$vpm_e" "$2/" || return 1
    done
    return 0
}
# vd_prog_rm 目录：删掉程序（logs / state / backup 留着）
vd_prog_rm() {
    [ -d "$1" ] || return 0
    for vpr_e in "$1"/* "$1"/.[!.]* "$1"/..?*; do
        [ -e "$vpr_e" ] || [ -L "$vpr_e" ] || continue
        vd_is_data "${vpr_e##*/}" && continue
        rm -rf "$vpr_e" || true
    done
    return 0
}
# vd_data_rescue 目录：旧安装器把整个程序目录（连同 logs / backup）挪成了 host.bak / host.failed，删它之前把这两个搬回来（同名的不覆盖）
vd_data_rescue() {
    for vdr_s in logs backup; do
        [ -d "$1/$vdr_s" ] || continue
        mkdir -p "$VD_TOP/veradroid/$vdr_s" 2>/dev/null || continue
        for vdr_e in "$1/$vdr_s"/* "$1/$vdr_s"/.[!.]*; do
            [ -e "$vdr_e" ] || continue
            [ -e "$VD_TOP/veradroid/$vdr_s/${vdr_e##*/}" ] || mv "$vdr_e" "$VD_TOP/veradroid/$vdr_s/" 2>/dev/null || true
        done
    done
    return 0
}
vd_pid_live() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }
# vd_old_installer：老版本的安装器正在跑（根目录的安装锁，持有者还活着）
vd_old_installer() { vd_pid_live "$(cat "$VD_TOP/veradroid-install.lock/pid" 2>/dev/null)"; }
# vd_root_tidy：把根目录里以前的文件搬进 veradroid/。安装器每次运行、veradroid.sh 每次启动都跑，重复跑没有副作用。
# 只认我们自己按固定名字建的文件；用户改过名的（带 KEEP 的备份等）、别的文件夹（vd18-keep/ 等）一律不动。
# 老版本的进程还在用的不动：老安装器在跑时整个跳过；老自动更新拿着锁时不动它的锁和下载的安装器；老的离线更新在跑时不动暂存目录。
vd_root_tidy() {
    vrt=$VD_TOP
    [ -d "$vrt" ] || return 0
    vd_old_installer && return 0
    mkdir -p "$VD_LOGS" "$VD_STATE" "$VD_BACKUP" 2>/dev/null || return 0
    # 日志：logs/ 里已经有同名的就先并进 .old（最多留 1 MB），再把根目录的改名过来（还开着它的老进程接着写进同一个文件）
    for vrt_n in install install-detail fsck uninstall boot localupdate autoupdate resize; do
        vrt_f=$vrt/veradroid-$vrt_n.log; [ -f "$vrt_f" ] || continue
        vrt_d=$VD_LOGS/$vrt_n.log
        if [ -f "$vrt_d" ]; then
            { cat "$vrt_d.old" 2>/dev/null; cat "$vrt_d"; } | tail -c 1048576 > "$vrt_d.old.tmp" 2>/dev/null && mv -f "$vrt_d.old.tmp" "$vrt_d.old" && rm -f "$vrt_d" || true
        fi
        mv -f "$vrt_f" "$vrt_d" 2>/dev/null || true
    done
    # 标记：两边都有时留较新的那份
    for vrt_n in install.incomplete docs-manifest docs-manifest.checked launchers-restored confirm resize.state resize.pending resize.notify resize.last; do
        vrt_f=$vrt/veradroid-$vrt_n; [ -f "$vrt_f" ] || continue
        if [ -e "$VD_STATE/$vrt_n" ] && [ "$(ls -t "$vrt_f" "$VD_STATE/$vrt_n" 2>/dev/null | head -1)" != "$vrt_f" ]; then rm -f "$vrt_f"
        else mv -f "$vrt_f" "$VD_STATE/$vrt_n" 2>/dev/null || true; fi
    done
    # 锁和临时文件：持有者已经不在的删掉（安装锁的持有者上面已经确认不在了）
    rm -f "$vrt/veradroid-install.lock/pid" "$vrt/veradroid-install.lock/boot" "$vrt/veradroid-install.lock/phase" 2>/dev/null || true
    rmdir "$vrt/veradroid-install.lock" 2>/dev/null || true
    # 老版本的自动更新（更新前起的，锁在根目录）在睡觉：没有安装在进行时（两个安装锁都没人拿）把它停掉，
    # 新版的 veradroid.sh 接着起新的（否则它一直占着根目录的锁，新的那个起不来，直到 Kindle 重启）。
    # 安装器里跑到这里时自己拿着新的安装锁，不会停它的父进程。
    # set -eu 下（安装器）赋值的退出码就是 $(...) 的：锁不在时 cat 失败会让整个安装器静默退出，所以每个 $(...) 和 rm 都要 || true
    vrt_p=$(cat "$vrt/veradroid-autoupdate.lock/pid" 2>/dev/null || true)
    if vd_pid_live "$vrt_p" && ! vd_pid_live "$(cat "$VD_STATE/install.lock/pid" 2>/dev/null)"; then
        case "$( { tr '\000' ' ' < /proc/$vrt_p/cmdline; } 2>/dev/null || true)" in "sh "*"/auto-update.sh"*) kill -9 "$vrt_p" 2>/dev/null && sleep 1 ;; esac
    fi
    if ! vd_pid_live "$(cat "$vrt/veradroid-autoupdate.lock/pid" 2>/dev/null)"; then
        rm -rf "$vrt/veradroid-autoupdate.lock" "$vrt/veradroid-autoupdate-bootstrap.sh" "$vrt/veradroid-autoupdate-bootstrap.sh.part" 2>/dev/null || true
    fi
    for vrt_f in "$vrt"/veradroid-install.[0-9]*; do
        [ -d "$vrt_f" ] || continue
        case "${vrt_f##*.}" in *[!0-9]*) continue ;; esac
        vd_pid_live "${vrt_f##*.}" && [ -n "$(find "$vrt_f" -mmin -2 2>/dev/null | head -1)" ] && continue   # 不拿锁的老安装器还在往里写
        rm -rf "$vrt_f" 2>/dev/null || true
    done
    rm -f "$vrt/veradroid-user-backup.partial" 2>/dev/null || true
    if [ -d "$vrt/veradroid-update.stage" ] && [ ! -e "$VD_STATE/update.stage" ] && ! vd_pid_live "$(cat /dev/shm/veradroid/localupdate.lock/pid 2>/dev/null)"; then
        mv "$vrt/veradroid-update.stage" "$VD_STATE/update.stage" 2>/dev/null || true
    fi
    # 备份：只搬安装器按时间起名的那几种；同名的已经在 backup/ 里就不动
    for vrt_f in "$vrt"/veradroid-user-backup-*.tgz "$vrt"/veradroid-documents-backup.tgz "$vrt"/veradroid-documents-backup.restored-*.tgz "$vrt"/veradroid-cc.db.*; do
        [ -f "$vrt_f" ] || continue
        vrt_n=${vrt_f##*/}; vrt_n=${vrt_n#veradroid-}
        case "$vrt_n" in
            user-backup-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].tgz) ;;
            documents-backup.tgz|documents-backup.restored-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].tgz) ;;
            cc.db.[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]) ;;
            *) continue ;;
        esac
        [ -e "$VD_BACKUP/$vrt_n" ] || mv "$vrt_f" "$VD_BACKUP/$vrt_n" 2>/dev/null || true
    done
    if [ -d "$vrt/veradroid-media-keep" ] && [ ! -e "$VD_BACKUP/media-keep" ]; then
        mv "$vrt/veradroid-media-keep" "$VD_BACKUP/media-keep" 2>/dev/null || true
    fi
    return 0
}
# END vd_root_tidy
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
# BEGIN vd_where_notes
# 用户用 USB 打开 Kindle 盘时看到的说明（2026-11-19，“东西放在哪”）：veradroid/说明.txt 写清每个子文件夹是什么，
# veradroid/screensaver/把屏保图片放这里.txt 说屏保图片放这里。安装器每次运行、veradroid.sh 每次启动都写（内容一样就不动）。
# 这一段在 bootstrap*.sh 和各机型 boot/lib/tidy.sh 里是同一份（tests/where-notes-test.sh 核对）。set -eu 下也不能失败。
vd_note_put() { # 文件 内容：内容不同才写（文件名是中文：用户存储是 ext4 / vfat 都能存 UTF-8 名字）
    [ "$(cat "$1" 2>/dev/null || true)" = "$2" ] && return 0
    printf '%s\n' "$2" > "$1.tmp" 2>/dev/null && mv -f "$1.tmp" "$1" 2>/dev/null || rm -f "$1.tmp" 2>/dev/null || true
    return 0
}
vd_where_notes() {
    vwn=${VD_TOP:-/mnt/base-us}/veradroid
    [ -d "$vwn" ] || return 0
    vd_note_put "$vwn/说明.txt" "VeraDroid 程序文件夹（不要删、不要改名；书和 Android 的数据不在这里）

你可能会用到的：
  screensaver/            屏保图片：把 jpg / png / webp / bmp / gif 放进来，在 Android 的 设置 → 屏保 里选
  fonts/                  字体：就是 Android 里的 内部存储/Fonts，把 ttf / otf 放进来（字体商店装的也在这里）；
                          升级、重装、卸载都会留着
  update/                 离线更新：把离线包原样放进来，下次点“VeraDroid 启动”时自动安装
  VeraDroid-Launch.sh     备用入口：书库里找不到“VeraDroid 启动”时，把它拷进 documents，在书库里点它；
                          启动一次以后，书库里的“VeraDroid 启动”会自动放回去
  VeraDroid-Uninstall.sh  卸载：Android 起不来时才用，拷进 documents 再点，按屏幕上的说明做
  logs/                   日志：反馈问题时附上 install.log 和 boot.log
  backup/                 安装前自动做的备份，留着
  kindle-crash/           Kindle 自己的崩溃记录（从书库里挪出来的），可以删

其余的（boot、bin、apps、state 等）是程序本身。
找不到书库入口，也可以用 KUAL 菜单：VeraDroid → 恢复书库入口。"
    mkdir -p "$vwn/screensaver" 2>/dev/null || return 0
    vd_note_put "$vwn/screensaver/把屏保图片放这里.txt" "把屏保图片（jpg / png / webp / bmp / gif，扩展名大小写都行）放在这个文件夹里。
Kindle 休眠时，VeraDroid 显示这里的图片。在 Android 的 设置 → 屏保 里可以选“轮换全部图片”或只用其中一张。
竖图、和屏幕差不多大最好；单张不要超过 32 MB。default.png 是自带的图，不算。
打开一次 设置 → 屏保，桌面会把图转成适合这块屏的灰度图（webp 要转过才能用）；页面上会写哪张用不了、为什么。
手机拍的 HEIC 照片用不了，请先转成 JPG。不要放进 Kindle 盘根目录的 screensaver 或 linkss 文件夹：VeraDroid 不读那里。

图片也可以放在 Android 的 内部存储/Screensaver 文件夹（传书网页的“文件”里能上传，屏保页的“从内部存储导入…”也会放到那里）。"
    return 0
}
# END vd_where_notes
# BEGIN vd_user_carry
# 用户自己放进 veradroid/ 的文件（2026-11-19，KPW5 用户：“放到 veradroid/fonts 的字体，升级以后就没了”）：
#   fonts/        Android 里的 内部存储/Fonts（容器里绑在 /data/media/0/Fonts 上），用户拷进来的字体、字体商店装的字体都在这里
#   screensaver/  屏保图片      apps/  还没装的 APK（afterboot 装好才删）      kindle-crash/  从书库挪出来的 Kindle 崩溃转储
# 整包安装把程序目录挪成 host.bak、卸载删程序：这几个文件夹里用户的文件要带到新程序目录 / 留到 backup/user-files。
# 包里自带的文件名记在 state/bundled.<文件夹>（整包安装时写）：带过去时跳过旧包自带的（新包有自己的那份；新包去掉的也不复活）。
# 目标里已有同名文件的不动（包里有的以包为准）。这一段在 bootstrap*.sh 和各机型 boot/lib/tidy.sh 里是同一份
# （tests/user-files-test.sh 核对）。set -eu 下不能失败。
VD_USER_DIRS="fonts screensaver apps kindle-crash webview-alt"   # webview-alt：用户打开“新网页内核”时下载的包（container.sh webview_dl），升级时带过去不用重下
# vd_user_carry 从 到 "文件夹…" [mv]：$1/<d>/ 下（含子文件夹）用户的文件拷进 $2/<d>/；mv=mv 时挪过去（同一个盘，卸载留下的那份）。
# 同一个文件系统上先试硬链接（不多占地方，host.bak 里的那份照样能回退），不行再拷。输出带过去的文件数。
vd_user_carry() {
    vuc_n=0
    for vuc_d in $3; do
        [ -d "$1/$vuc_d" ] || continue
        vuc_c=$( ( cd "$1/$vuc_d" 2>/dev/null && find . 2>/dev/null ) | vd_user_carry1 "$1/$vuc_d" "$2/$vuc_d" \
            "${VD_TOP:-/mnt/base-us}/veradroid/state/bundled.$vuc_d" "${4:-}" ) || vuc_c=0
        vuc_n=$(( vuc_n + ${vuc_c:-0} ))
    done
    echo "$vuc_n"
    return 0
}
# vd_user_carry1 从 到 包清单 [mv]：标准输入是 find 列出的相对路径；输出带过去的文件数
vd_user_carry1() {
    vuc_c=0
    while IFS= read -r vuc_f; do
        vuc_f=${vuc_f#./}
        [ "$vuc_f" != . ] && [ -n "$vuc_f" ] || continue
        if [ -d "$1/$vuc_f" ] && [ ! -L "$1/$vuc_f" ]; then continue; fi
        if [ -e "$2/$vuc_f" ] || [ -L "$2/$vuc_f" ]; then continue; fi
        if [ -f "$3" ] && grep -Fxq -- "$vuc_f" "$3" 2>/dev/null; then continue; fi
        vuc_p=$2/$vuc_f; mkdir -p "${vuc_p%/*}" 2>/dev/null || continue
        if [ "$4" = mv ]; then mv -f "$1/$vuc_f" "$vuc_p" 2>/dev/null || continue
        else ln "$1/$vuc_f" "$vuc_p" 2>/dev/null || cp -a "$1/$vuc_f" "$vuc_p" 2>/dev/null || { rm -f "$vuc_p" 2>/dev/null; continue; }; fi
        vuc_c=$((vuc_c + 1))
    done
    echo "$vuc_c"
    return 0
}
# vd_bundled_save 包目录：记下包里自带的文件名（fonts/、screensaver/、apks/ → apps）。装完新程序、带完用户文件以后调。
vd_bundled_save() {
    vbs_s=${VD_TOP:-/mnt/base-us}/veradroid/state
    mkdir -p "$vbs_s" 2>/dev/null || return 0
    for vbs_d in fonts screensaver apps; do
        vbs_p=$vbs_d; [ "$vbs_d" = apps ] && vbs_p=apks
        if [ -d "$1/$vbs_p" ]; then
            ( cd "$1/$vbs_p" 2>/dev/null && find . -type f 2>/dev/null | sed 's#^\./##' ) > "$vbs_s/bundled.$vbs_d.tmp" 2>/dev/null \
                && mv -f "$vbs_s/bundled.$vbs_d.tmp" "$vbs_s/bundled.$vbs_d" 2>/dev/null || rm -f "$vbs_s/bundled.$vbs_d.tmp" 2>/dev/null || true
        else rm -f "$vbs_s/bundled.$vbs_d" 2>/dev/null || true; fi
    done
    return 0
}
# vd_user_keep 程序目录：卸载前把用户的字体 / 屏保挪进 backup/user-files（重新安装时 vd_user_restore 放回去）
vd_user_keep() {
    vuk_b=${VD_TOP:-/mnt/base-us}/veradroid/backup/user-files
    vuk_n=$(vd_user_carry "$1" "$vuk_b" "fonts screensaver" mv)
    rmdir "$vuk_b"/* "$vuk_b" 2>/dev/null || true
    echo "$vuk_n"
    return 0
}
# vd_user_restore 程序目录：卸载时留下的 backup/user-files 放回去（同名的不覆盖），放完的空文件夹删掉，没放回去的留着
vd_user_restore() {
    vur_b=${VD_TOP:-/mnt/base-us}/veradroid/backup/user-files
    [ -d "$vur_b" ] || { echo 0; return 0; }
    vur_n=$(vd_user_carry "$vur_b" "$1" "fonts screensaver" mv)
    find "$vur_b" -depth -type d -exec rmdir {} \; 2>/dev/null || true
    echo "$vur_n"
    return 0
}
# END vd_user_carry
# 程序目录里的备用启动入口 veradroid/VeraDroid-Launch.sh：标题改成备用的（整包安装和增量更新都调）
vd_launch_backup() {
    [ -f "$VD/VeraDroid-Launch.sh" ] || return 0
    sed 's/^# Name: .*/# Name: VeraDroid 启动（备用，可拷进 documents）/' "$VD/VeraDroid-Launch.sh" > "$VD/.vd-launch.tmp" 2>/dev/null \
        && mv "$VD/.vd-launch.tmp" "$VD/VeraDroid-Launch.sh" && chmod 755 "$VD/VeraDroid-Launch.sh" || true
    rm -f "$VD/.vd-launch.tmp" 2>/dev/null || true
    return 0
}
mkdir -p "$VD_LOGS" "$VD_STATE" "$VD_BACKUP" 2>/dev/null || true
TMP=/mnt/base-us/veradroid/state/install.$$
trap 'rm -rf "$TMP"' EXIT
# BH081-3：同一时间只允许一个安装器（书库脚本连点两次 / KUAL + 自动更新同时跑，会互相 mv 程序目录）。
# 被 kill / 断电的上一次安装留下的 veradroid-install.<pid>（整包 + 解压，约 300 MB）和还在往里写的 curl/tar 子进程，在这里清掉。
INSTALL_LOCK=/mnt/base-us/veradroid/state/install.lock
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo noboot)
# 结束所有命令行里引用目录 $1 的进程（curl -o $1/bundle.tar.gz、tar -C $1 …），不碰自己
vd_kill_users() {
    for pp in /proc/[0-9]*; do
        pn=${pp#/proc/}; [ "$pn" = "$$" ] && continue
        cl=$( { tr '\0' ' ' < "$pp/cmdline"; } 2>/dev/null ) || continue   # 进程可能刚退出：不要往安装输出里刷 can't open
        case "$cl" in *"$1/"*|*"$1 "*) kill -9 "$pn" 2>/dev/null || true ;; esac
    done
}
vd_unlock() { [ "$(cat "$INSTALL_LOCK/pid" 2>/dev/null)" = "$$" ] || return 0; rm -f "$INSTALL_LOCK/pid" "$INSTALL_LOCK/boot" "$INSTALL_LOCK/phase"; rmdir "$INSTALL_LOCK" 2>/dev/null || true; }
vd_cleanup() { _vrc=$?; set +eu; type ui_on_exit >/dev/null 2>&1 && ui_on_exit "$_vrc"; type syncer_off >/dev/null 2>&1 && syncer_off; vd_kill_users "$TMP"; rm -rf "$TMP"; type vd_kindle_back >/dev/null 2>&1 && vd_kindle_back; type vd_awake_restore >/dev/null 2>&1 && vd_awake_restore; type vd_ssh_back >/dev/null 2>&1 && vd_ssh_back; vd_unlock; }
if ! mkdir "$INSTALL_LOCK" 2>/dev/null; then
    lp=$(cat "$INSTALL_LOCK/pid" 2>/dev/null || true); lb=$(cat "$INSTALL_LOCK/boot" 2>/dev/null || true)
    # 锁在 /mnt/base-us 上，断电后还在：只认本次开机（boot_id 相同）里还活着的持有者
    if [ -n "$lp" ] && [ "$lb" = "$BOOT_ID" ] && kill -0 "$lp" 2>/dev/null; then
        printf '%s\n' "另一个 VeraDroid 安装/更新正在进行（pid $lp），这次不重复安装，请等它完成"; exit 0
    fi
    rm -f "$INSTALL_LOCK/pid" "$INSTALL_LOCK/boot" "$INSTALL_LOCK/phase"; rmdir "$INSTALL_LOCK" 2>/dev/null || true
    mkdir "$INSTALL_LOCK" 2>/dev/null || { printf '%s\n' "无法获取安装锁 $INSTALL_LOCK（另一个安装器刚启动？）"; exit 1; }
fi
echo $$ > "$INSTALL_LOCK/pid"; echo "$BOOT_ID" > "$INSTALL_LOCK/boot"
# 安装进行到哪一步（veradroid.sh 看它决定能不能启动）：check / download = 还没动任何东西，可以照常启动；
# wait = 自动更新在等用户离开 Android；replace = 正在停 Android、替换程序和系统，这时不能启动
vd_phase() { echo "$1" > "$INSTALL_LOCK/phase" 2>/dev/null || true; }
vd_phase check
trap 'vd_cleanup' EXIT
trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
# 老版本的安装器（用户手里的旧离线包）锁在根目录：它在跑时不同时装，也不搬它正在用的文件
if vd_old_installer; then printf '%s\n' "另一个 VeraDroid 安装/更新正在进行（pid $(cat /mnt/base-us/veradroid-install.lock/pid 2>/dev/null)），这次不重复安装，请等它完成"; exit 0; fi   # root-legacy
# 根目录里老版本留下的日志 / 标记 / 备份搬进 veradroid/（tidy 块的说明）
vd_root_tidy
# 安装 / 更新期间不让 Kindle 睡、Wi-Fi 不省电（2026-11-14 真机：桌面里点的更新解压到一半设备失联，原因没查明，先把能想到的都堵上）。
# 记下原值，所有退出路径（vd_cleanup，包括 die）都恢复；值读不到就不动。
VD_PSS0=$(lipc-get-prop com.lab126.powerd preventScreenSaver 2>/dev/null || true)
# 被硬杀（SIGKILL）时 EXIT 陷阱不跑，1 会一直留着、电源键进不了屏保（社区报告）：改之前把原值记在 state/keepawake.orig，下次启动时 veradroid.sh 放回
[ "$VD_PSS0" = 0 ] && { mkdir -p /mnt/base-us/veradroid/state && echo 0 > /mnt/base-us/veradroid/state/keepawake.orig; } 2>/dev/null || true
case "$VD_PSS0" in 0) lipc-set-prop -i com.lab126.powerd preventScreenSaver 1 2>/dev/null || true ;; *) VD_PSS0= ;; esac
VD_WPS0=
vd_wifi_awake() { # Wi-Fi 省电关掉（stop.sh 的 wifi_ps_restore 会把它打开，停完 Android 再调一次）
    [ "$(iwconfig wlan0 2>/dev/null | sed -n 's/.*Power Management:\(o[nf]*\).*/\1/p')" = on ] || return 0
    iwconfig wlan0 power off 2>/dev/null && VD_WPS0=on; return 0
}
vd_wifi_awake
# 诊断用的 SSH（documents/VeraDroid-SSH 诊断包的 dropbear，可选、用户自己开的）：安装前在跑，装完不在了就照原样再开一次（实测：
# 桌面里点的更新装完以后 SSH 连不上了，只能到设备上重开）。安装前没开的不碰。
vd_ssh_pids() { ls -l /proc/[0-9]*/exe 2>/dev/null | grep -c -e '-> /var/tmp/veradroid-dropbear' -e '-> /var/tmp/veradroid-ssh/dropbear' || true; }
VD_SSHWAS=$(vd_ssh_pids)
vd_ssh_back() {
    [ "${VD_SSHWAS:-0}" -gt 0 ] 2>/dev/null || return 0
    VD_SSHWAS=0
    [ "$(vd_ssh_pids)" -gt 0 ] 2>/dev/null && return 0
    # 诊断包（docs/ssh-diagnostics.md）或旧的书库脚本 VeraDroidSSH-On.sh（KOReader 的 dropbear，-D 在 $VD/ssh 里）
    for f in /mnt/us/documents/VeraDroid-SSH/VeraDroid-SSH.sh /mnt/us/documents/VeraDroidSSH-On.sh; do
        { [ -f "$f" ] && [ ! -L "$f" ]; } || continue
        vlog "diagnostic ssh was running before the install and is gone: starting it again ($f)"
        ( cd / && VD_SSH_HOLD=0 setsid sh "$f" ) </dev/null >/dev/null 2>&1 &
        break
    done
    return 0
}
vd_awake_restore() {
    [ "$VD_PSS0" = 0 ] && lipc-set-prop -i com.lab126.powerd preventScreenSaver 0 2>/dev/null && rm -f /mnt/base-us/veradroid/state/keepawake.orig
    [ "$VD_WPS0" = on ] && iwconfig wlan0 power on 2>/dev/null
    VD_PSS0=; VD_WPS0=; return 0
}
# 拿到锁以后，别的 veradroid-install.<pid> 都是残留：持有锁的只有我们。
# 唯一例外：旧版（无锁）安装器还活着——它的 pid 在本次开机里存在且目录 2 分钟内还在变。
for old_tmp in /mnt/base-us/veradroid/state/install.[0-9]*; do
    [ -d "$old_tmp" ] || continue
    [ "$old_tmp" = "$TMP" ] && continue
    op=${old_tmp##*.}
    case "$op" in *[!0-9]*) continue ;; esac
    if kill -0 "$op" 2>/dev/null && [ -n "$(find "$old_tmp" -mmin -2 2>/dev/null | head -1)" ]; then
        cl=$( { tr '\0' ' ' < /proc/$op/cmdline; } 2>/dev/null || true)
        case "$cl" in *sh*) printf '%s\n' "旧版安装器 pid $op 还在运行，跳过清理 $old_tmp"; continue ;; esac
    fi
    vd_kill_users "$old_tmp"
    show_mb=$(du -sm "$old_tmp" 2>/dev/null | cut -f1)
    printf '%s\n' "清理上次中断留下的临时目录 $old_tmp（${show_mb:-?} MB）"
    rm -rf "/mnt/base-us/veradroid/state/install.$op" 2>/dev/null || true
done

show() {
    printf '%s\n' "$*"
    if [ -n "${VP_ON:-}" ]; then ui_note "$*"; return 0; fi   # 整屏进度画面在：写进画面的说明行，不用 eips 盖上去
    [ -n "${UI_AUTO:-}" ] && return 0                           # 自动更新：用户在看书，不往屏幕上写字（状态文件里有）
    if command -v eips >/dev/null 2>&1; then
        # eips 没有中文字形：中文字节画出来是乱码，叠在上一行没擦掉的尾巴上（legacy 的 Oasis 3 用户照片）。只画英文：
        # “English / 中文”取斜杠前一半，否则去掉非 ASCII；剩下不到 4 个字母、也没有百分比的（纯中文的提示）不画。补空格到 60 列。
        case "$*" in *" / "*) se=${*%% / *} ;; *) se=$* ;; esac
        se=$(printf '%s' "$se" | tr -cd ' -~' | cut -c1-60)
        if [ "$(printf '%s' "$se" | tr -cd 'A-Za-z' | wc -c)" -ge 4 ] || case "$se" in *%*) true ;; *) false ;; esac; then
            eips 1 3 "$(printf '%-60s' "$se")" >/dev/null 2>&1 || true
        fi
    fi
}
progress_line() { # pct label
    if [ -n "${VP_ON:-}" ] || [ -n "${UI_AUTO:-}" ]; then vp_progress "$1" "$2 · 已用 $(vp_elapsed)" "${VP_L2:-}"; return 0; fi
    pct=$1
    [ "$pct" -lt 0 ] 2>/dev/null && pct=0
    [ "$pct" -gt 100 ] 2>/dev/null && pct=100
    fill=$((pct / 5))
    empty=$((20 - fill))
    bar=$(printf '%*s' "$fill" '' | tr ' ' '#')
    pad=$(printf '%*s' "$empty" '' | tr ' ' '-')
    show "$2 ${pct}% [${bar}${pad}]"
}
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
    case "$(uname -r)" in 3.*) m="$nb/static/bootstrap-legacy.json?t=$t0" ;; 4.*) m="$nb/static/bootstrap-k49.json?t=$t0" ;; *) m="$nb/api/bootstrap" ;; esac
    u=$(curl_do -fsSL --max-time 15 "$m" 2>/dev/null | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p')
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
# ---- 安装器怎么用上面的进度画面（docs/progress-ui.md）----
# 画面：书库里点安装 / 桌面里点“检查并更新”（Android 停掉以后）/ 离线安装时整屏方块画面；自动更新（VD_AUTOUPDATE=1，用户在看书）
# 不画，只写状态文件。状态文件 $W/kfb/ctl/update.status 给 VeraHome 的进度卡片看（Android 在跑时才有这个目录）。
VP_STATUS=$W/kfb/ctl/update.status
vp_log() { vlog "$*"; }
UI_AUTO=; [ "${VD_AUTOUPDATE:-0}" = 1 ] && UI_AUTO=1
NET_SUM=
ui_start() {
    if [ -f "$VD/boot/veradroid.sh" ]; then ut="正在更新 VeraDroid"; uc="VERADROID · UPDATE"; else ut="正在安装 VeraDroid"; uc="VERADROID · SETUP"; fi
    us="net dl verify unpack backup install boot"; [ -n "${VD_BUNDLE:-}" ] && us="copy verify unpack backup install boot"
    [ "$LOOP" = 1 ] && [ ! -f "$SYSTEM_IMG" ] && us="disk $us"
    [ "${VD_NO_START:-0}" = 1 ] && us=${us% boot}
    um=manual; [ -n "$UI_AUTO" ] && um=auto; [ -n "${VD_BUNDLE:-}" ] && um=local
    VP_KV="mode=$um model=$MODEL"
    # 离线整包：新包的 bin/fbink 在 tar 的最前面，读头 4 MB 就能解出来，一开始就画整屏方块画面
    uf=
    if [ -n "${VD_BUNDLE:-}" ] && [ -f "$VD_BUNDLE" ]; then
        mkdir -p "$TMP/early" 2>/dev/null && { head -c 4194304 "$VD_BUNDLE" | tar -xzf - -C "$TMP/early" veradroid-pw6/bin/fbink; } >/dev/null 2>&1 || true
        [ -x "$TMP/early/veradroid-pw6/bin/fbink" ] && uf="$TMP/early/veradroid-pw6/bin/fbink"
    fi
    vp_begin "$ut" "$uc" "Kindle ${MODEL} · 固件 ${FW:-?} · 安装期间别关机、别拔电" \
        "日志：veradroid/logs/install.log ｜ 一步卡住超过 15 分钟：长按电源键重启 Kindle，再点一次安装（书和设置不会丢）" "$us" $uf
    vlog "progress: mode=$VP_MODE fbink=${VP_FB:-none} ${VP_W}x${VP_H} steps=$us auto=${UI_AUTO:-0}"
    if android_in_use; then return 0; fi    # 桌面里点的更新：Android 停掉以后再画
    ui_show
}
ui_show() {
    [ -z "$UI_AUTO" ] || return 0
    vp_show
    [ "$VP_MODE" = fb ] && : > /tmp/vd-progress.on 2>/dev/null
    return 0
}
# 退出时：没走到“完成”或出错画面就退出了（被杀、断电前的 TERM、自动更新等用户离开）——告诉桌面的卡片，别让它停在“下载中”
ui_on_exit() {
    [ "${1:-0}" = 0 ] && return 0
    grep -q '^state=error' "$VP_STATUS" 2>/dev/null && return 0
    if [ "$1" = 75 ]; then vp_status "state=paused" "label=等你离开 Android 再装" "line2=Android 正在用，半小时后再来"
    else vp_status "state=error" "code=$1" "label=安装被中断（代码 $1）" "line2=什么都没有改动的步骤可以直接再点一次更新；日志在 veradroid/logs/install.log"; fi
    return 0
}
ui_note() { vp_progress "${VP_PCT:--1}" "${VP_L1:-}" "$*"; }
# 后台跑一个命令，跑着的时候按它读到（in）/ 写出（out）文件的位置画进度：ui_run in|out 文件 总字节 第二行 命令…
ui_run() {
    ur_m=$1; ur_f=$2; ur_t=$3; ur_l=$4; shift 4
    "$@" &
    ur_p=$!
    if [ "$ur_m" = out ]; then vp_watch $ur_p "$ur_f" "$ur_t" "$ur_l" out; else vp_watch $ur_p "$ur_f" "$ur_t" "$ur_l"; fi
    ur_rc=0; wait $ur_p || ur_rc=$?
    return $ur_rc
}
ui_netcheck() {
    vp_step net "正在检查网络…" "Wi-Fi → 域名解析 → 服务器"
    if vp_netcheck "$BASE"; then
        vlog "netcheck ok: $(printf '%s' "$NC_LINES" | tr '\n' ' ')"
        NET_SUM="■ Wi-Fi　■ 域名解析　■ 服务器"
        vp_progress -1 "网络正常，开始下载" "$NC_LINES" force
        return 0
    fi
    vlog "netcheck FAILED code $NC_CODE: $NC_MSG / $(printf '%s' "$NC_LINES" | tr '\n' ' ')"
    vp_progress -1 "网络有问题" "$NC_LINES" force
    sleep 2
    DIE_CODE=$NC_CODE die "$NC_MSG"
}
fsize() { { wc -c < "$1"; } 2>/dev/null | tr -d ' ' || true; }
# curl 退出码 → 给人看的错误代码（die 用）
dl_code() {
    case "$1" in 6) echo 12 ;; 7|35|51|60|22|52|56) echo 13 ;; 23) echo 21 ;; *) echo 14 ;; esac
}
# BEGIN vd_dl
# ---- 分段并行下载 + 镜像（docs/download-speed.md）----
# 这一块在 bootstrap.sh 和 bootstrap-legacy.sh 里一字不差（tests/dl-block-test.sh）。要用到：curl_do、vlog、fsize、vp_now、$TMP、$BASE。
# 国内到 Cloudflare 免费版的单条连接很慢，但几条连接一起下时总速度差不多按条数涨：大文件（≥ 8 MB）分成 VD_SEGS 段
# （默认 4，network.conf 里可设 1–6，1 = 不分段；dl.wenjin.jp 总共 64 条连接，所以不超过 6），每段一条 Range 连接同时下；每段下好的部分留在 <输出>.seg/ 里，
# 断了 / 重试时各段从自己的断点接着下；全部下完按顺序拼起来（拼一段删一段，不多占一份空间），之后照旧整体校验 SHA-256。
# 镜像：元数据里可以有 "mirrors": ["https://…", …]（基地址，有顺序；写 [] 就是不用镜像）；元数据里没有这一项时
# 用默认的 VD_DEF_MIRRORS（dl.wenjin.jp：同一台服务器，不经过 Cloudflare 直连）。network.conf 里的 VD_MIRROR 排在最前。
# 文件在镜像上的地址 = 基地址/<key>，key = 主站地址去掉域名和 ? 后面的部分（如 bundles/veradroid-pw6-0.95.tar.gz）；
# 基地址里有 {name} 时换成文件名（GitHub Releases 那种没有目录的）。镜像只认 https（主站本身是 http 的测试服务器时也认 http）。
# 选源（2026-11-19 起“直链优先”，站长的决定）：按顺序先测镜像（前 256 KB 计时），第一个回 206 且不慢于 VD_MIN_BPS
# （默认 50 KB/s；整个文件不到 256 KB 时只要回 206）的镜像直接用，不再拿主站比——以前取最快的，国内 Cloudflare 常常短测赢了、
# 整个下载却很慢。镜像都测不通 / 都太慢时才用主站（主站也不通时用测到的最快的那个）。镜像下坏了（SHA-256 不对）就只用主站重下。
# 镜像上的内容和主站不一样也没关系：最后都要过 SHA-256。
VD_SEGS=${VD_SEGS:-4}; case "$VD_SEGS" in [1-6]) ;; *) VD_SEGS=4 ;; esac
VD_SEG_MIN=8388608
VD_MIN_BPS=${VD_MIN_BPS:-51200}; case "$VD_MIN_BPS" in ''|*[!0-9]*) VD_MIN_BPS=51200 ;; esac
VD_DEF_MIRRORS='"https://dl.wenjin.jp"'
# 元数据（版本、地址、sha256）也先走直链：主站是默认的 veradroid.wenjin.jp 时默认 https://dl.wenjin.jp，network.conf 里
# VD_DIRECT= 关掉；主站改了（VD_BASE，测试服务器）时默认不用，测试时显式给 VD_DIRECT
case "$BASE" in https://veradroid.wenjin.jp|https://veradroid.wenjin.jp/) VD_DIRECT=${VD_DIRECT-https://dl.wenjin.jp} ;; *) VD_DIRECT=${VD_DIRECT-} ;; esac
VD_MIRRORS=
vd_mirrors_from() { # 元数据 json → VD_MIRRORS（空格分开）
    m_pat='https'; case "$BASE" in http://*) m_pat='https\{0,1\}' ;; esac
    VD_MIRRORS=$( { [ -z "${VD_MIRROR:-}" ] || printf '"%s",' "$VD_MIRROR"
        if printf '%s' "$1" | grep -q '"mirrors"'; then printf '%s' "$1" | tr -d '\n' | sed -n 's/.*"mirrors": *\[\([^]]*\)\].*/\1/p'
        else printf '%s' "$VD_DEF_MIRRORS"; fi; } | tr ',' '\n' \
        | sed -n "s/^[ ]*\"\\($m_pat:\\/\\/[^\" ]*\\)\"[ ]*\$/\\1/p" | head -n 6 | tr '\n' ' ')
    [ -z "$VD_MIRRORS" ] || vlog "mirrors: $VD_MIRRORS"
    return 0
}
vd_cands() { # 主站地址 → 候选地址（每行一个：镜像按顺序在前，主站最后）
    c_k=${1#*://}; c_k=${c_k#*/}; c_k=${c_k%%\?*}; c_n=${c_k##*/}
    for c_m in $VD_MIRRORS; do
        case "$c_m" in *'{name}'*) printf '%s\n' "$c_m" | sed "s|{name}|$c_n|" ;; *) printf '%s\n' "${c_m%/}/$c_k" ;; esac
    done
    printf '%s\n' "$1"
}
# vd_pick 主站地址 → 输出“地址 分段”：分段 = VD_SEGS（支持 Range）、1（不支持）、0（都测不通）
vd_pick() {
    p_o=$1; p_best=; p_bs=-1; p_seg=1; p_why=
    for p_u in $(vd_cands "$p_o"); do
        p_w=$(curl_do -sL -o /dev/null -r 0-262143 --connect-timeout 8 --max-time 12 -w '%{http_code} %{size_download} %{time_total}' "$p_u" 2>/dev/null) || p_w="000 0 0"
        set -- $p_w
        # 206 = 支持 Range；镜像必须支持（不然多半是错误页 / 跳转页），主站 200 也行（不分段）
        case "$1" in 206) ;; 200) [ "$p_u" = "$p_o" ] || continue ;; *) vlog "probe: $p_u -> HTTP $1"; continue ;; esac
        p_sp=$(awk -v b="$2" -v t="$3" 'BEGIN { if (t > 0) printf "%d", b / t; else print 0 }')
        vlog "probe: $p_u -> HTTP $1, $2 bytes, $p_sp B/s"
        # 直链优先：镜像够快（或整个小文件都拿到了）就用它，后面的（主站）不测
        if [ "$p_u" != "$p_o" ] && [ "$1" = 206 ] && { [ "$p_sp" -ge "$VD_MIN_BPS" ] || [ "$2" -lt 262144 ]; } 2>/dev/null; then
            p_best=$p_u; p_bs=$p_sp; p_seg=$VD_SEGS; p_why="mirror first"; break
        fi
        if [ "$p_sp" -gt "$p_bs" ] 2>/dev/null; then p_best=$p_u; p_bs=$p_sp; p_seg=1; [ "$1" = 206 ] && p_seg=$VD_SEGS; p_why=fastest; fi
    done
    [ -n "$p_best" ] || { p_best=$p_o; p_seg=0; p_why="nothing answered"; }   # 都测不通：分段数未知（0），交给真正的下载去报错
    vlog "source: $p_best ($p_why, $p_bs B/s, $p_seg segments)"
    # 记下选了哪个源（第几个候选），小文件（增量更新的部件）在 10 分钟内直接用它，不再一个个测
    p_i=0; for p_u in $(vd_cands "$p_o"); do p_i=$((p_i + 1)); [ "$p_u" = "$p_best" ] && break; done
    echo "$(vp_now) $p_i" > "$TMP/dl.pick" 2>/dev/null || true
    echo "$p_best $p_seg"
}
# 已经下好的字节数（整个文件 + 各段）
vd_have() {
    h_s=$(fsize "$1"); h_s=${h_s:-0}
    for h_f in "$1".seg/[0-9]*; do [ -f "$h_f" ] && h_s=$((h_s + $(fsize "$h_f"))); done
    echo $h_s
}
# vd_fetch 主站地址 输出 总字节：下一次（选源 + 单连接续传或分段并行）；返回 curl 的退出码。在后台跑（vd_download 看进度）。
vd_fetch() {
    f_u=$1; f_o=$2; f_t=${3:-0}; f_d=$2.seg
    if [ -f "$f_d/plan" ]; then
        set -- $(vd_pick "$f_u"); f_src=$1; f_n=$(cut -d' ' -f2 "$f_d/plan")
        [ "$2" != 1 ] || { vlog "download: $f_src has no Range now, dropping the segments"; rm -rf "$f_d"; f_n=1; }
    elif [ "$VD_SEGS" -gt 1 ] && [ "$f_t" -ge $VD_SEG_MIN ] 2>/dev/null && [ ! -s "$f_o" ]; then set -- $(vd_pick "$f_u"); f_src=$1; f_n=$2
        [ "$f_n" -gt 0 ] || f_n=1
    elif [ -n "$VD_MIRRORS" ]; then
        f_src=; set -- $(cat "$TMP/dl.pick" 2>/dev/null) 0 0
        [ $(( $(vp_now) - $1 )) -lt 600 ] 2>/dev/null && f_src=$(vd_cands "$f_u" | sed -n "${2}p")
        [ -n "$f_src" ] || { set -- $(vd_pick "$f_u"); f_src=$1; }
        f_n=1
    else f_src=$f_u; f_n=1; fi
    [ "$f_src" = "$f_u" ] || vlog "download from mirror: $f_src"
    if [ "$f_n" -le 1 ] 2>/dev/null; then
        curl_do -fsSL --retry 2 --retry-delay 3 --connect-timeout 20 --max-time 1800 --speed-limit 1024 --speed-time 30 -C - -o "$f_o" "$f_src" 2>"$TMP/curl.err"
        return $?
    fi
    mkdir -p "$f_d" || return 23
    [ "$(cat "$f_d/plan" 2>/dev/null)" = "$f_t $f_n" ] || { rm -f "$f_d"/*; echo "$f_t $f_n" > "$f_d/plan"; }
    f_len=$(( (f_t + f_n - 1) / f_n )); f_i=0; f_pids=
    while [ $f_i -lt $f_n ]; do
        f_a=$((f_i * f_len)); f_b=$((f_a + f_len - 1)); [ $f_b -lt $f_t ] || f_b=$((f_t - 1))
        f_h=$(fsize "$f_d/$f_i"); f_h=${f_h:-0}
        [ $((f_a + f_h)) -le $((f_b + 1)) ] || { rm -f "$f_d/$f_i"; f_h=0; }   # 比这一段还长：坏了，重下这段
        if [ $((f_a + f_h)) -le $f_b ]; then
            # 输出用 >> 接在已下好的后面；--stderr 带上目录名，安装器清理残留进程时能按目录找到它
            curl_do -fsSL --connect-timeout 20 --max-time 1800 --speed-limit 1024 --speed-time 30 --stderr "$f_d/err.$f_i" \
                -r $((f_a + f_h))-$f_b "$f_src" >> "$f_d/$f_i" &
            f_pids="$f_pids $!"
        fi
        f_i=$((f_i + 1))
    done
    f_rc=0; for f_p in $f_pids; do wait $f_p || f_rc=$?; done
    cat "$f_d"/err.* > "$TMP/curl.err" 2>/dev/null || true
    # 各段长度都对才拼：拼进第 0 段，拼一段删一段
    f_i=0
    while [ $f_i -lt $f_n ]; do
        f_a=$((f_i * f_len)); f_b=$((f_a + f_len - 1)); [ $f_b -lt $f_t ] || f_b=$((f_t - 1))
        f_h=$(fsize "$f_d/$f_i"); f_h=${f_h:-0}
        if [ "$f_h" != $((f_b - f_a + 1)) ]; then
            [ "$f_h" -le $((f_b - f_a + 1)) ] || rm -f "$f_d/$f_i"
            [ $f_rc != 0 ] || f_rc=18   # curl 说成功了但长度不对：当成“传输不完整”，外面会重试
            return $f_rc
        fi
        f_i=$((f_i + 1))
    done
    f_i=1
    while [ $f_i -lt $f_n ]; do cat "$f_d/$f_i" >> "$f_d/0" && rm -f "$f_d/$f_i" || return 23; f_i=$((f_i + 1)); done
    mv -f "$f_d/0" "$f_o" && rm -rf "$f_d" || return 23
    return 0
}
# vd_meta 主站地址 [curl 选项…]：元数据先从直链（VD_DIRECT + 同一路径，带 ?t= 绕缓存）拿，拿不到 / 不像元数据（没有 "version" 和
# "url"）时从主站拿。直链和主站是同一台服务器、各自的证书，可信程度一样；元数据里的镜像（可能是第三方的桶）不用来拿元数据。
# 直链现在只放行下载路径，元数据请求回 404，这里就退回主站：多一次很快的请求，站长放行后自动生效。
vd_meta() {
    m_u=$1; shift
    if [ -n "${VD_DIRECT:-}" ]; then
        m_k=${m_u#*://}; m_k=${m_k#*/}
        if m_j=$(curl_do -fsSL --connect-timeout 6 --max-time 15 "${VD_DIRECT%/}/$m_k" 2>/dev/null) \
            && printf '%s' "$m_j" | grep -q '"version"' && printf '%s' "$m_j" | grep -q '"url"'; then
            vlog "meta: from the direct link ${VD_DIRECT%/}/${m_k%%\?*}"; printf '%s' "$m_j"; return 0
        fi
        vlog "meta: direct link has no ${m_k%%\?*}, using the main site"
    fi
    curl_do -fsSL "$@" "$m_u"
}
# END vd_dl
# vd_download 地址 输出文件 总字节（0 = 不知道）：断点续传；卡住 / 断了自动重试（连续 5 次没进展才放弃，DL_RC = curl 的退出码）。
# 画面：已下 / 总共 MB、速度、剩余时间；慢（< 50 KB/s 超过 20 秒，或预计超过 20 分钟）、卡住（15 秒没数据）时说清楚是网络的问题。
DL_RC=0; DL_EARLY=
vd_download() {
    du_u=$1; du_o=$2; du_t=${3:-0}; [ "$du_t" -gt 0 ] 2>/dev/null || du_t=0
    du_try=0; du_spd=0; du_slow=0; du_stall=0; du_t0=$(vp_now); DL_RC=0
    while :; do
        du_a=$(vd_have "$du_o"); du_last=$du_a; du_lt=$(vp_now)
        # 结束标记文件 + kill -0 一起看（同 bootstrap-legacy.sh：只看 kill -0 时 busybox 测试里偶尔一直转圈，进程早就结束了）
        rm -f "$du_o.rc"
        { vd_fetch "$du_u" "$du_o" "$du_t" && echo 0 > "$du_o.rc" || echo $? > "$du_o.rc"; } &
        du_p=$!
        while [ ! -s "$du_o.rc" ] && kill -0 $du_p 2>/dev/null; do
            sleep 1
            du_sz=$(vd_have "$du_o"); du_now=$(vp_now); du_dt=$((du_now - du_lt)); [ $du_dt -ge 1 ] || du_dt=1
            du_in=$(( (du_sz - du_last) / du_dt )); [ $du_in -ge 0 ] || du_in=0
            du_spd=$(( (du_spd * 3 + du_in) / 4 )); du_last=$du_sz; du_lt=$du_now
            if [ $du_in = 0 ]; then [ $du_stall = 0 ] && du_stall=$du_now; else du_stall=0; fi
            du_el=$((du_now - du_t0))
            if [ $du_el -ge 6 ] && [ $du_spd -lt 51200 ]; then [ $du_slow = 0 ] && du_slow=$du_now; else du_slow=0; fi
            du_pct=-1; du_eta=
            if [ $du_t -gt 0 ]; then
                du_pct=$((du_sz / 1024 * 100 / (du_t / 1024 + 1)))
                [ $du_spd -gt 0 ] && du_eta=$(( (du_t - du_sz) / du_spd ))
            fi
            # 没速度 / 卡住 / 超过 24 小时：不显示一个离谱的剩余时间（2026-11-14 断网测试显示过 17852:35:30）
            du_etxt=; [ -n "$du_eta" ] && du_etxt=" · 剩余 $(vp_mmss $du_eta)"
            if [ $du_t -gt 0 ] && { [ $du_in = 0 ] || [ $du_spd -lt 1024 ] || [ "${du_eta:-0}" -gt 86400 ]; }; then du_etxt=" · 剩余时间：等网络恢复"; du_eta=; fi
            du_l1="$(vp_mb $du_sz)${du_t:+ / $(vp_mb $du_t)} MB · $(vp_rate $du_spd)$du_etxt"
            [ $du_t -gt 0 ] || du_l1="$(vp_mb $du_sz) MB · $(vp_rate $du_spd)"
            du_l2=$NET_SUM
            if [ $du_el -ge 5 ]; then
                if [ $du_spd -ge 51200 ]; then du_l2="$du_l2　■ 速度 $(vp_rate $du_spd)（正常）"; else du_l2="$du_l2　□ 速度 $(vp_rate $du_spd)（很慢）"; fi
            fi
            VP_EN2=
            if [ $du_stall != 0 ] && [ $((du_now - du_stall)) -ge 15 ]; then
                VP_EN2="Stalled $((du_now - du_stall))s, will retry. Network problem."
                du_l2="网络卡住了：$((du_now - du_stall)) 秒没有收到数据，会自动重试（下好的部分不会丢）。
这是网络的问题，不是机器的问题。"
            elif { [ $du_slow != 0 ] && [ $((du_now - du_slow)) -ge 20 ]; } || { [ -n "$du_eta" ] && [ "$du_eta" -gt 1200 ]; }; then
                if [ -n "$du_eta" ]; then du_et2="预计还要 $((du_eta / 60)) 分钟"; VP_EN2="Network slow: ~$((du_eta / 60)) min left. Not the device."
                else du_et2="剩余时间要等网速恢复才算得出"; VP_EN2="Network very slow. Not the device."; fi
                du_l2="网络很慢：$du_et2。不是机器的问题；
可以换个网络，或改用离线整包（官网下载，电脑拷进 Kindle）。"
            fi
            vp_progress $du_pct "$du_l1" "$du_l2"
            # 新的整包把 bin/fbink 放在最前面（打包脚本）：下了几 MB 就能把它先解出来，换上整屏方块画面
            if [ -z "$DL_EARLY" ] && [ "$VP_MODE" != fb ] && [ $du_sz -gt 3145728 ]; then
                DL_EARLY=1; mkdir -p "$TMP/early"
                du_f=$du_o; [ -f "$du_o.seg/0" ] && du_f=$du_o.seg/0   # 分段下载时文件开头在第 0 段
                tar -xzf "$du_f" -C "$TMP/early" veradroid-pw6/bin/fbink >/dev/null 2>&1 || true
                [ -x "$TMP/early/veradroid-pw6/bin/fbink" ] && vp_upgrade "$TMP/early/veradroid-pw6/bin/fbink"
            fi
        done
        DL_RC=0; wait $du_p 2>/dev/null || true; VP_EN2=
        DL_RC=$(cat "$du_o.rc" 2>/dev/null || echo 1); rm -f "$du_o.rc"; [ -n "$DL_RC" ] || DL_RC=1
        du_sz=$(vd_have "$du_o")
        [ $DL_RC = 0 ] && return 0
        du_osz=$(fsize "$du_o"); du_osz=${du_osz:-0}
        [ $du_t -gt 0 ] && [ -f "$du_o" ] && [ "$du_osz" -ge "$du_t" ] 2>/dev/null && return 0
        # 服务器不支持断点续传（33 / 36）：只能从头来
        case "$DL_RC" in 33|36) rm -rf "$du_o" "$du_o.seg"; du_sz=0 ;; esac
        [ $((du_sz - du_a)) -gt 524288 ] && du_try=0
        du_try=$((du_try + 1))
        vlog "download: curl rc=$DL_RC at $du_sz bytes ($(head -c 200 "$TMP/curl.err" 2>/dev/null | tr '\n' ' ')), attempt $du_try/5"
        [ $du_try -ge 5 ] && return 1
        VP_EN2="Network dropped, retrying ($du_try/5). Not the device."
        vp_progress "${du_pct:--1}" "$(vp_mb $du_sz) MB 已下好" "网络断了 / 卡住了，正在重试（$du_try/5）…
这是网络的问题，不是机器的问题。" force
        sleep 5
    done
}
# 与 boot/lib/common.sh 的 loop_mount 相同：有的机型（KT6）只有 /dev/loop/N 节点，mount -o loop 会失败
# 和 boot/lib/common.sh 的 storage_mount 一样：镜像如果还挂在某个 loop 设备上（上次延迟卸载的挂载树没放手），
# 再挂一次就是两个文件系统实例同时写同一个镜像，数据必坏。先摘掉残留的 loop，摘不掉就不装。
img_loops() {
    for b in /sys/block/loop*/loop/backing_file; do
        case "$(cat "$b" 2>/dev/null)" in *veradroid-sys.img) d=${b%/loop/backing_file}; echo "${d##*/}" ;; esac
    done
}
img_free() {
    i=0
    while [ -n "$(img_loops)" ]; do
        for l in $(img_loops); do n=${l#loop}; losetup -d /dev/loop$n 2>/dev/null || losetup -d /dev/loop/$n 2>/dev/null; done
        [ -z "$(img_loops)" ] && return 0
        i=$((i + 1)); [ $i -ge 25 ] && return 1
        sleep 1
    done
    return 0
}
loop_mount() { # img dir
    img_free || die "系统盘还在被上次的进程占用：请重启 Kindle 后再安装"
    # 3.0 内核的 ext3 要带 user_xattr（见 boot/lib/common.sh 的 LOOPOPT：没有它 Android 每次开机删光应用数据）
    lo=rw,noatime; case "$(uname -r)" in 3.*) lo=rw,noatime,user_xattr ;; esac
    mount -o loop,$lo "$1" "$2" 2>/dev/null && return 0
    for d in /dev/loop/[0-9]* /dev/loop[0-9]*; do
        [ -b "$d" ] || continue
        losetup "$d" >/dev/null 2>&1 && continue
        losetup "$d" "$1" 2>/dev/null || continue
        { mount -t ext4 -o $lo "$d" "$2" 2>/dev/null || mount -t ext3 -o $lo "$d" "$2" 2>/dev/null; } && return 0   # 3.0 内核只有 ext3
        losetup -d "$d" 2>/dev/null
    done
    return 1
}
die() {
    # 错误代码（给客服 / 日志）：调用方可以设 DIE_CODE，没设就按原因归类。11–15 网络，21 空间，3x 机器这边
    dc=${DIE_CODE:-}
    if [ -z "$dc" ]; then
        case "$*" in
            *空间*) dc=21 ;; *SHA-256*|*校验*) dc=15 ;; *下载*) dc=14 ;; *服务器*) dc=13 ;;
            *系统盘*|*mkfs*) dc=32 ;; *解压*|*rootfs*|*写入*|*安装包结构*) dc=31 ;; *) dc=30 ;;
        esac
    fi
    case "$dc" in
        11) dw="没连 Wi-Fi。
在 Kindle 的 设置 → Wi-Fi 里连上网络，再点一次安装。" ;;
        12) dw="Wi-Fi 连上了，但上不了网（域名解析失败）。不是机器的问题。
换个 Wi-Fi 或手机热点再试；还不行在 veradroid/network.conf 里写 VD_DNS=223.5.5.5。" ;;
        13) dw="连不上 VeraDroid 服务器（可能被墙，或服务器在维护）。不是机器的问题。
过一会儿再试、换个网络，或改用离线整包（官网下载，电脑拷进 Kindle 的 documents）。" ;;
        14) dw="网络太慢，下载超时了。不是机器的问题。
下好的部分留着，再点一次会接着下；或改用离线整包。" ;;
        15) dw="下载的文件损坏（重下过一次还是不对），多半是网络传输出错。
换个网络再试，或改用离线整包。" ;;
        21) dw="Kindle 存储空间不够。
删掉一些书或文件，留出空间后再点一次安装。什么都没有删除。" ;;
        *) dw="机器这边出了问题（详细原因在日志里）。
长按电源键重启 Kindle 后再点一次安装；还不行请把两个日志发给我们。" ;;
    esac
    vlog "FAILED code $dc: $*"
    printf '%s\n' "安装失败（代码 $dc）：$*" "$dw"
    VP_ERR_SHOW=; [ -z "${UI_AUTO:-}" ] && ! android_up && VP_ERR_SHOW=1
    if [ -n "${VP_MODE:-}" ] && [ "${VP_MODE:-none}" != none ] && { [ -n "${VP_ON:-}" ] || [ -n "$VP_ERR_SHOW" ]; }; then
        case "$dc" in
            11) dt="没连 Wi-Fi" ;; 12) dt="上不了网（域名解析失败）" ;; 13) dt="连不上 VeraDroid 服务器" ;; 14) dt="网络太慢，下载超时" ;;
            15) dt="下载的文件损坏" ;; 21) dt="存储空间不够" ;; *) dt="安装出错（机器这边）" ;;
        esac
        # 网络问题：把具体原因（vp_netcheck：DNS / 连接 / TLS / 太慢，该用哪个离线整包）也写上，不只是笼统的一句
        dd_=; case "$dc" in 11|12|13) dd_="
$*"; [ -n "${NC_LINES:-}" ] && dd_="$dd_
$NC_LINES" ;; 2*|3*) dd_="
原因：$*" ;; esac
        vp_error "$dc" "$dt" "$dw$dd_
日志：veradroid/logs/install.log、install-detail.log"
    else
        type vp_status >/dev/null 2>&1 && vp_status "state=error" "code=$dc" "label=$*" "line2=$dw"
        if [ -z "${UI_AUTO:-}" ] && command -v eips >/dev/null 2>&1; then
            eips 1 3 "VeraDroid install FAILED, code $dc" >/dev/null 2>&1 || true
            eips 1 4 "$(vp_en_err "$dc" | cut -c1-46)" >/dev/null 2>&1 || true
        fi
    fi
    sleep 8
    exit "$dc"
}

verify_sha() { # file expected
    if command -v sha256sum >/dev/null 2>&1; then
        echo "$2  $1" | ${LOWPRI:-} sha256sum -c - >/dev/null 2>&1
    elif command -v openssl >/dev/null 2>&1; then
        got=$(openssl dgst -sha256 "$1" 2>/dev/null | awk '{print $NF}')
        [ "$got" = "$2" ]
    else
        return 1
    fi
}

# Restore only files that are missing from DEST. busybox tar -k aborts at the
# first file that already exists ("File exists"), so list the archive first and
# extract just the missing names (-T); existing/newer user files are never touched.

# 下载前空间预检：下载目录（$TMP 所在分区）和 /mnt/base-us 都要够放下安装包+余量。
# 不够就明确报需要/剩余 MB，安全退出，不改任何已有安装。
space_check_before_download() { # size_bytes
    need_b=${1:-0}
    [ "$need_b" -gt 0 ] 2>/dev/null || return 0
    # 安装包先下载到 $TMP，再在同一个分区里解压（解压出来和安装包差不多大），两份同时存在。
    # 以前只按“安装包 + 64 MB”检查：空间刚好够下载的设备会白下 270 MB，然后在解压时报“No space left”。
    # 按 MB 算，避免 32 位 shell 里字节数相乘溢出。
    need_mb=$(( need_b / 1048576 * 21 / 10 + 64 ))
    parent=$(dirname "$TMP")
    mkdir -p "$parent" 2>/dev/null || true
    dl_mb=$(df -m "$parent" 2>/dev/null | awk 'NR==2{print $4}')
    us_mb=$(df -m /mnt/base-us 2>/dev/null | awk 'NR==2{print $4}')
    vlog "pre-download space: need ${need_mb}M (bundle + unpacked + 64M) dl=${dl_mb:-?}M base-us=${us_mb:-?}M"
    short=
    [ -n "$dl_mb" ] && [ "$dl_mb" -lt "$need_mb" ] 2>/dev/null && short="下载目录只剩 ${dl_mb} MB"
    [ -n "$us_mb" ] && [ "$us_mb" -lt "$need_mb" ] 2>/dev/null && short="${short:+$short；}用户存储(/mnt/base-us)只剩 ${us_mb} MB"
    if [ -n "$short" ]; then
        die "空间不够，无法下载安装包：需要约 ${need_mb} MB（安装包+余量），${short}。没有改动任何文件，请清理空间后重试"
    fi
}

# BEGIN vd_sys_room_early
# 镜像机型（LOOP=1：系统盘是 /mnt/base-us 上的一个 ext4 镜像）的系统盘空间，在下载之前就查（和后面“空间预检”同一个算法）。
# 以前只在下载 + 备份之后才查：用户诊断（KT6）1.07 -> 1.10 下了 101 MB（20 分钟）、备份 248 MB（5 分钟）以后才报
# “空间不够：需要约 600 MB，只有 575 MB”（代码 21），退回 Kindle，用户看到的就是“点了更新一直没反应”。
# 现在：不够就先在线扩大镜像（和 storage.sh 的自动扩容一样：ext4、一个 loop、resize2fs、losetup -c；中断了有
# veradroid/state/resize.state，下次挂载时 st_recover 收尾），扩不了才停下，什么都还没下载、没备份。
vd_sys_room_need() { # 和下面空间预检一样的估算（MB）
    vr_gz=${PARTS_ROOTFS_GZ:-}; [ -n "$PARTS" ] || vr_gz=$(( ${size:-150000000} / 2 ))
    echo $(( ${vr_gz:-150000000} * 3 / 1048576 + 200 ))
}
# 旧镜像本身占多少 MB（装新的时会删掉，算作可用）。Android 在跑时 rootfs 下面挂着 proc、sys、data（同一个系统盘）、
# storage（又绑回 data/media，里面还绑着 Kindle 的 documents）：直接 du 会把用户数据和书都算成“删掉旧镜像能腾出来的”，
# 还要走一遍 /proc（模拟器 KT6 5.17.1 chroot 模式：两分钟没走完）。非递归地再绑一次，只看镜像自己的文件。
vd_rootfs_mb() {
    [ -d "$SYS/rootfs" ] || { echo 0; return 0; }
    vm_d=/dev/shm/vd-rootfs-du.$$
    if mkdir -p "$vm_d" 2>/dev/null && mount --bind "$SYS/rootfs" "$vm_d" 2>/dev/null; then
        du -sxm "$vm_d" 2>/dev/null | awk '{print $1}'
        umount "$vm_d" 2>/dev/null || umount -l "$vm_d" 2>/dev/null
    else
        du -sxm "$SYS/rootfs" 2>/dev/null | awk '{print $1}'
    fi
    rmdir "$vm_d" 2>/dev/null
    return 0
}
vd_sys_room_free() {
    vr_f=$(df -m "$SYS" 2>/dev/null | awk 'NR==2{print $4}')
    vr_o=$(vd_rootfs_mb)
    echo $(( ${vr_f:-0} + ${vr_o:-0} ))
}
vd_sys_grow_online() { # 目标 MB；0 = 文件系统已经扩到目标
    vg_l=$(img_loops); [ "$(echo "$vg_l" | grep -c .)" = 1 ] || return 1
    [ "$(awk -v m="$SYS" '$2 == m { print $3 }' /proc/mounts | tail -1)" = ext4 ] || return 1
    vg_r=$(command -v resize2fs 2>/dev/null) || { [ -x "$VD/bin/resize2fs" ] && vg_r=$VD/bin/resize2fs; } || return 1
    losetup --help 2>&1 | grep -q -e '-c' || return 1
    vg_dev=/dev/$vg_l; [ -b "$vg_dev" ] || vg_dev=/dev/loop/${vg_l#loop}
    vg_from=$(stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1048576 }')
    [ -n "$vg_from" ] && [ "$1" -gt "$vg_from" ] || return 1
    vg_st=/mnt/base-us/veradroid/state/resize.state
    [ -e "$vg_st" ] && return 1                      # 别的调整没做完：不插手
    # 和 storage.sh 同一把锁（看门狗的自动扩容 / 设置里的调整正在做就不插手）
    vg_lk=$W/resize.lock; mkdir -p "$W" 2>/dev/null
    if ! mkdir "$vg_lk" 2>/dev/null; then
        vg_o=$(cat "$vg_lk/pid" 2>/dev/null); [ -n "$vg_o" ] && kill -0 "$vg_o" 2>/dev/null && return 1
    fi
    echo $$ > "$vg_lk/pid"
    vd_sys_grow_locked "$1" "$vg_r" "$vg_dev" "$vg_from" "$vg_st"; vg_rc=$?
    rm -f "$vg_lk/pid"; rmdir "$vg_lk" 2>/dev/null
    return $vg_rc
}
vd_sys_grow_locked() { # to resize2fs dev from statefile
    vg_r=$2; vg_dev=$3; vg_from=$4; vg_st=$5
    mkdir -p /mnt/base-us/veradroid/state 2>/dev/null
    printf 'op=grow\nfrom=%s\nto=%s\nphase=extend\n' "$vg_from" "$1" > "$vg_st" && sync
    truncate -s "${1}M" "$SYSTEM_IMG" 2>/dev/null
    vg_cur=$(stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1048576 }')
    # FAT 不支持稀疏加长时 truncate 会失败：和 storage.sh 的 st_extend 一样补零
    [ "$vg_cur" != "$1" ] && [ -n "$vg_cur" ] && [ "$1" -gt "$vg_cur" ] && dd if=/dev/zero of="$SYSTEM_IMG" bs=1M seek="$vg_cur" count=$(($1 - vg_cur)) conv=notrunc 2>/dev/null
    if [ "$(stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1048576 }')" != "$1" ]; then
        truncate -s "${vg_from}M" "$SYSTEM_IMG" 2>/dev/null; rm -f "$vg_st"; return 1   # 文件系统还没动，截回去
    fi
    sync; printf 'op=grow\nfrom=%s\nto=%s\nphase=resize\n' "$vg_from" "$1" > "$vg_st"
    if losetup -c "$vg_dev" 2>/dev/null && "$vg_r" "$vg_dev" >> /mnt/base-us/veradroid/logs/install-detail.log 2>&1; then
        sync; rm -f "$vg_st"; return 0
    fi
    return 1    # 文件加长了、文件系统没扩：标记留着，下次挂载时 st_recover 补上（没有坏处）
}
vd_sys_room_early() {
    [ "$LOOP" = 1 ] && [ -f "$SYSTEM_IMG" ] && grep -q " $SYS " /proc/mounts || return 0
    vr_need=$(vd_sys_room_need); vr_free=$(vd_sys_room_free)
    vlog "space (early): system disk need ${vr_need}M free ${vr_free}M"
    [ "$vr_free" -lt "$vr_need" ] || return 0
    # 扩多少：缺的 + 256 MB 余量，按 64 MB 取整；Kindle 那边要留 256 MB + 下载和备份的地方；FAT 上单个文件最多 4095 MB
    vr_img=$(stat -c %s "$SYSTEM_IMG" 2>/dev/null | awk '{ printf "%d", $1 / 1048576 }')
    vr_to=$(( (vr_img + vr_need - vr_free + 256 + 63) / 64 * 64 ))
    vr_k=$(df -m /mnt/base-us 2>/dev/null | awk 'NR==2{print $4}')
    if [ -n "$PARTS" ]; then vr_dl=${pp_tot:-0}; else vr_dl=${size:-0}; fi
    vr_dl=$(( vr_dl / 1048576 * 2 + 128 ))
    vr_why=
    case "$(stat -f -c %T /mnt/base-us 2>/dev/null)" in vfat|msdos|fat) [ "$vr_to" -le 4095 ] || vr_why="FAT 上系统盘最大 4 GB" ;; esac
    [ -z "$vr_why" ] && [ $(( vr_to - vr_img + 256 + vr_dl )) -gt "${vr_k:-0}" ] && vr_why="Kindle 剩余空间只有 ${vr_k:-?} MB"
    grep -q '^STORAGE_AUTO=0' "$SYS/settings.conf" 2>/dev/null && [ -z "$vr_why" ] && vr_why="自动扩容已关闭"
    if [ -z "$vr_why" ]; then
        vp_step dl "系统盘空间不够，先扩大到 $vr_to MB" "在线扩大，不用停 Android，几秒钟"
        if vd_sys_grow_online "$vr_to"; then
            vlog "space (early): system disk grown online $vr_img -> $vr_to MB, free now $(vd_sys_room_free)M"
            [ "$(vd_sys_room_free)" -ge "$vr_need" ] && return 0
        else vr_why="在线扩大没有成功"; vlog "space (early): online grow $vr_img -> $vr_to MB failed"; fi
    fi
    DIE_CODE=21 die "系统盘空间不够：这次更新需要约 ${vr_need} MB，系统盘里只有 ${vr_free} MB（${vr_why:-扩大后还是不够}）。还没有下载和备份，什么都没改。可以在 设置 → 存储空间 里把系统盘扩大，或删掉不用的应用后再更新"
}
# END vd_sys_room_early

restore_missing() { # backup.tgz destdir
    [ -f "$1" ] && [ -d "$2" ] || return 0
    mkdir -p "$TMP" 2>/dev/null || true
    rl="$TMP/restore.list"
    : > "$rl" || return 0
    tar -tzf "$1" 2>/dev/null | while IFS= read -r f; do
        case "$f" in */) continue ;; esac
        p=${f#./}
        [ -n "$p" ] || continue
        is_installer_path "$f" && continue
        [ -e "$2/$p" ] || [ -L "$2/$p" ] || printf '%s\n' "$f" >> "$rl"
    done
    [ -s "$rl" ] || return 0
    show "从安装前的备份恢复 $(wc -l < "$rl" | tr -d ' ') 个缺失文件…"
    tar -xzkf "$1" -C "$2" -T "$rl" 2>/dev/null || true
}
# 2026-11-10 数据安全（在 eink-emulator 里复现 / 排除过）
vlog() { echo "[$(date '+%F %T')] $*" >> /mnt/base-us/veradroid/logs/install-detail.log 2>/dev/null || true; }
android_up() { [ -f "$W/pid" ] && kill -0 "$(cat "$W/pid" 2>/dev/null)" 2>/dev/null; }
# 用户在用：Android 在前台，或者正在启动 / 解冻（veradroid.sh 的 starting / thawing 锁，持有者还活着）
android_in_use() {
    for d in starting thawing; do [ -d "$W/$d" ] && kill -0 "$(cat "$W/$d/pid" 2>/dev/null)" 2>/dev/null && return 0; done
    android_up && [ ! -f "$W/frozen" ]
}
# 替换文件之前调：从这里起 veradroid.sh 不再启动（它会等安装完）。
# 自动更新（VD_AUTOUPDATE=1）决定更新时用户在 Kindle 里（Android 冻结或没开），可下载要好几分钟，这期间用户可能又点了“启动”。
# 以前这里直接 stop.sh：用户刚打开的 Android 十秒后被关掉，屏幕上没有任何解释（用户报告：0.96 更新后“启动后 10 秒被停、再点 20 分钟没反应”）。
# 现在等它回到冻结或退出（最多 2 小时，之后放弃这一次，auto-update 过一会儿再来）。用户自己点的安装 / 更新照旧立刻停。
install_wait_idle() {
    if [ "${VD_AUTOUPDATE:-0}" = 1 ] && android_in_use; then
        vd_phase wait
        vlog "auto update: android is in use, waiting until it is frozen or stopped"
        n=0
        while :; do
            while android_in_use; do
                n=$((n + 1)); [ $n -gt 240 ] && { vlog "auto update: android still in use after 2 h, deferring"; exit 75; }
                sleep 30
            done
            vd_phase replace
            # 置 replace 之前那一刻用户可能刚又点了“启动”（那时 veradroid.sh 还允许启动）：再看一次，又在前台了就接着等
            sleep 2
            android_in_use && { vd_phase wait; continue; }
            break
        done
        vlog "auto update: android no longer in front, installing"
    fi
    vd_phase replace
}
# 安装器停 Android：stop.sh 把原因写进 veradroid.log（以前只记一个已经退出的父进程，看不出是谁停的）
# 桌面里点的更新（Android 在前台）：停 Android 时不把 Kindle 界面冷启动起来（和“重启 Android”一样用 $W/restarting），
# 解压 / 安装时不和 Kindle 框架抢内存和 CPU；装完安装器自己把 Android 开起来。没开起来就退出（出错 / 被杀）时，
# vd_kindle_back 把 Kindle 界面还回去。Android 冻着或没开（用户在 Kindle 里）照旧。
VD_KEEPFW=; VD_STARTED=
install_stop_android() {
    show "先退出正在运行的 VeraDroid（正在安装更新）…"
    if android_in_use && [ "${VD_NO_START:-0}" != 1 ]; then : > "$W/restarting" 2>/dev/null && VD_KEEPFW=1 && vlog "stop android: keeping the Kindle UI stopped during the install"; fi
    VD_STOP_REASON="installer pid $$ (${VD_AUTOUPDATE:+auto }update ${cur_ver:-?} -> ${LATEST_VER:-${ver:-?}})" sh "$VD/boot/stop.sh" ${1:-} || true
    vd_wifi_awake
}
vd_start_android() { VD_STARTED=1; setsid sh "$VD/boot/veradroid.sh" home </dev/null >/dev/null 2>&1 || true; }
vd_kindle_back() {
    [ -n "$VD_KEEPFW" ] || return 0
    VD_KEEPFW=
    [ -n "$VD_STARTED" ] && return 0
    android_up && return 0
    rm -f "$W/restarting" 2>/dev/null
    for s in "$VD/boot/stop.sh" "${BAK:-/nonexistent}/boot/stop.sh"; do
        [ -f "$s" ] || continue
        vlog "exit without starting android: giving the Kindle UI back ($s)"
        VD_STOP_REASON="installer exit: Kindle UI back" sh "$s" >/dev/null 2>&1 || true
        break
    done
    return 0
}
# rm -rf 之前：$1 下面（self=1 包括 $1 本身）有挂载就返回 0（绝不能穿过 bind 挂载删到 Kindle 的书）
# 2026-11-13：容器自己的挂载命名空间里的挂载（Kindle 的 documents 绑在那里）宿主 /proc/mounts 看不到，也要查（同 boot/lib/common.sh）
mounts_under() { # dir [self]
    awk -v d="${1%/}" -v self="${2:-0}" '($2 == d && self == 1) || index($2, d "/") == 1 { f = 1 } END { exit !f }' /proc/mounts && return 0
    [ "$(cat $W/mode 2>/dev/null)" = chroot ] && return 1
    for mu_p in $(cat $W/pid 2>/dev/null); do
        [ -r /proc/$mu_p/mounts ] || continue
        [ "$(readlink /proc/$mu_p/ns/mnt 2>/dev/null)" = "$(readlink /proc/self/ns/mnt 2>/dev/null)" ] && continue
        awk -v d="${1%/}" -v self="${2:-0}" -v r="$SYS/rootfs" -v sd="$SYS/data" '
            function hit(h) { return (h == d && self == 1) || index(h, d "/") == 1 }
            { m = $2; if (m == "/") m = ""
              if (hit(r m)) f = 1
              if (m == "/data" || index(m, "/data/") == 1) { if (hit(sd substr(m, 6))) f = 1 } }
            END { exit !f }' /proc/$mu_p/mounts && return 0
    done
    return 1
}
guard_rm() { # dir...: 删除系统目录前的防护；下面有挂载就停下，什么都不删
    for g in "$@"; do
        [ -e "$g" ] || continue
        if mounts_under "$g" 1; then
            vlog "refuse rm -rf $g: $(awk -v d="${g%/}" '$2 == d || index($2, d "/") == 1 {print $2}' /proc/mounts | tr '\n' ' ')"
            die "$g 下面还有挂载（上次的 VeraDroid 没有停干净）。为了不误删 Kindle 的书，安装已停止：请重启 Kindle 后再装"
        fi
    done
}
# 书库脚本（安装器）本身不是用户数据：不要从备份里恢复，也不要因为它们“不见了”就留着旧备份
is_installer_path() {
    case "$1" in
        documents/VeraDroid.sh*|documents/VeraDroid-Experimental.sh*|documents/VeraDroid-Offline.sh*|documents/bootstrap.sh*|\
        ./VeraDroid.sh*|./VeraDroid-Experimental.sh*|./VeraDroid-Offline.sh*|./bootstrap.sh*) return 0 ;;
        # 离线包带来的整包和书库给安装器建的 .sdr：装完会被删掉，同样不是用户数据
        documents/veradroid-pw6-*.tar.gz|documents/veradroid-k49-*.tar.gz|\
        documents/VeraDroid.sdr|documents/VeraDroid.sdr/*|documents/VeraDroid-Experimental.sdr|documents/VeraDroid-Experimental.sdr/*|\
        documents/VeraDroid-Offline.sdr|documents/VeraDroid-Offline.sdr/*|documents/bootstrap.sdr|documents/bootstrap.sdr/*) return 0 ;;
        # 用户为了卸载自己拷进 documents 的卸载入口（安装器 / 卸载会拿掉它）：不算用户的书
        documents/VeraDroid-Uninstall.sh*|documents/VeraDroid-Uninstall.sdr|documents/VeraDroid-Uninstall.sdr/*) return 0 ;;
    esac
    return 1
}
# 安装器运行时把自己（书库里的 VeraDroid*.sh）交给我们删：父进程 sh 还在读这个文件，立刻删掉会让它读到半截
# （KT6 模拟器：“line 27: syntax error: unterminated quoted string”）。等它退出后再删。
# 书库给每个被索引的文件建一个附属文件夹（脚本是 <文件名>.sh.sdr，放图标；txt 是 <去掉扩展名>.sdr）；
# 安装器删掉后它就是垃圾，一起删。只认这几个固定名字，只删紧挨着安装器的那两个目录名。
remove_installer_later() {
    if [ -n "${VD_INSTALLER:-}" ] && [ -f "$VD_INSTALLER" ]; then
        case "$(basename "$VD_INSTALLER")" in
            VeraDroid.sh|VeraDroid-Experimental.sh|VeraDroid-Offline.sh)
                ( cd / && setsid sh -c 'sleep 30; rm -f "$1"; for d in "$1.sdr" "${1%.sh}.sdr"; do [ -d "$d" ] && [ ! -L "$d" ] && rm -rf "$d"; done; sync' sh "$VD_INSTALLER" ) </dev/null >/dev/null 2>&1 & ;;
        esac
    fi
}
# 离线包里的 README.txt：只有内容确实是我们那份说明（很小，第一行以“VeraDroid ”开头并写着“离线”，
# 或者是 2026-11-19 起的“把下面 3 个文件放进 documents（VeraDroid 离线安装包…”）才算。用户自己的 README.txt 不动。
is_offline_readme() { # file
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    [ "$(wc -c < "$1" 2>/dev/null | tr -d ' ')" -lt 4096 ] 2>/dev/null || return 1
    head -n 1 "$1" 2>/dev/null | grep -q -e '^VeraDroid .*离线' -e '^把下面 3 个文件放进 documents（VeraDroid 离线安装包' || return 1
    return 0
}
# 离线安装成功后：把离线包带来的文件全部清掉（整包、bootstrap.sh、README.txt 和它们的 .sdr）。
# 只在离线包所在的目录里、只按固定名字删；安装器本身由 remove_installer_later 等它退出后再删。
remove_offline_package() {
    [ -n "${VD_OFFLINE_BUNDLE:-}" ] || return 0
    od=$(dirname "$VD_OFFLINE_BUNDLE")
    [ -d "$od" ] || return 0
    case "$(basename "$VD_OFFLINE_BUNDLE")" in
        veradroid-pw6-*.tar.gz|veradroid-k49-*.tar.gz) [ -f "$VD_OFFLINE_BUNDLE" ] && rm -f "$VD_OFFLINE_BUNDLE" 2>/dev/null || true ;;
    esac
    if [ -n "${VD_HELPER:-}" ] && [ -f "$VD_HELPER" ] && [ "$(basename "$VD_HELPER")" = bootstrap.sh ]; then
        # 这个文件就是正在执行的脚本：在 fuse.fsp（KT6 / Scribe 5.17+ 加密用户盘）上立刻删掉，sh 读剩下的部分会读到半截
        # （用户诊断包 KT6 5.17.1：“bootstrap.sh: line 1138: syntax error: unterminated quoted string”，装好了却报“安装失败（代码 2）”）。
        # 和 remove_installer_later 一样，等这个进程退出后再删。
        ( cd / && setsid sh -c 'sleep 30; rm -f "$1"; for d in "$1.sdr" "${1%.sh}.sdr"; do [ -d "$d" ] && [ ! -L "$d" ] && rm -rf "$d"; done; sync' sh "$VD_HELPER" ) </dev/null >/dev/null 2>&1 &
    fi
    if is_offline_readme "$od/README.txt"; then
        rm -f "$od/README.txt" 2>/dev/null || true
        if [ -d "$od/README.sdr" ] && [ ! -L "$od/README.sdr" ]; then rm -rf "$od/README.sdr" 2>/dev/null || true; fi
        vlog "offline package: removed README.txt"
    fi
    sync 2>/dev/null || true
}

res=$(cat /sys/class/graphics/fb0/virtual_size 2>/dev/null || true)
model_tree=$(cat /proc/device-tree/compatible 2>/dev/null | tr '\0' ' ')
case "$model_tree" in
    *sangria*) detected=pw6 ;;
    *seabreeze*) detected=cs ;;
    *malbec*) detected=pw5 ;;
    *rossini*) detected=kt6 ;;
    *cava*) detected=kt5 ;;
    *barolo*) detected=ks ;;
    *pisco*) detected=ks2 ;;
    *imx7d-zelda*) if [ -e /proc/self/ns/pid ]; then detected=ko3; else detected=oa2; fi ;;
    *) detected= ;;
esac
# PW5/KT5/KT6（eink-emulator 实测）的设备树只有通用的 "mediatek,mt8110 mediatek,mt8512"，没有板名。
# 这时按屏宽猜：1236/1248 -> pw5；1072 -> KT（vfat 用户分区 -> kt5，否则 kt6）。
# 只在没有 VD_MODEL 时使用；kt5/kt6 的安装行为相同（文件系统和内存都在后面按实测处理）。
guessed=
# 老机型按内核代际归类（eink-emulator 实测）：
#   3.0.x：PW2 / PW3 / Oasis 1/2 / Voyage / 入门款 2014、2016——没有命名空间，走 chroot 模式 + 自带 binder 模块（legacy）
#   4.1.x：PW4 / Oasis 3——需要亚马逊内核源码才能编模块，适配中（imx41）
case "$(uname -r)" in
    3.*) [ -z "$detected" ] && guessed=legacy ;;
    4.1.*) [ -z "$detected" ] && guessed=imx41 ;;
esac
if [ -z "$detected" ] && [ -z "$guessed" ]; then
    case "$model_tree" in
        *mt8110*)
            case "${res%%,*}" in
                1236|1248) guessed=pw5 ;;
                # Scribe：模拟器设备树没有板名（pisco/barolo）。帧缓冲行宽（virtual_size）分不清 1 代和 2 代，
                # 先看序列号里的机型代码（/proc/usid 第 4-6 位），再看可见宽度（fb0/modes）。
                1860|1872|2480)
                    case "$(cut -c4-6 /proc/usid 2>/dev/null)" in
                        27J|2BL|263|227|2BM|23L|23M|270) guessed=ks ;;
                        3V0|3V1|3X5|3UV|3X4|3X3|41E|410|3XE) guessed=ks2 ;;
                        *) case "$(sed -n '1s/^[^:]*:\([0-9]*\)x.*/\1/p' /sys/class/graphics/fb0/modes 2>/dev/null)" in
                               1860) guessed=ks ;;
                               1872) guessed=ks2 ;;
                               *) [ "${res%%,*}" = 1872 ] && guessed=ks2 || guessed=ks ;;
                           esac ;;
                    esac ;;
                1072) case "$(stat -f -c %T /mnt/base-us 2>/dev/null)" in
                          vfat|msdos) guessed=kt5 ;;
                          *) guessed=kt6 ;;
                      esac ;;
            esac ;;
    esac
fi
# Colorsoft：模拟器设备树没有 seabreeze。和 PW6 同宽 1272，但帧缓冲是 32 位色（或加载了 cfa 彩色滤镜模块）。
if [ -z "$detected" ] && [ -z "$guessed" ] && [ "${res%%,*}" = 1272 ]; then
    if [ "$(cat /sys/class/graphics/fb0/bits_per_pixel 2>/dev/null)" = 32 ] || grep -q '^cfa ' /proc/modules 2>/dev/null; then guessed=cs; fi
fi
MODEL=${VD_MODEL:-${detected:-$guessed}}
MODEL=$(printf '%s' "$MODEL" | tr 'A-Z' 'a-z')
if [ -n "$detected" ] && [ -n "${VD_MODEL:-}" ] && [ "$MODEL" != "$detected" ]; then
    echo "机型不匹配：设备是 $detected，安装包是 $MODEL。"
    exit 1
fi
[ -n "$MODEL" ] || MODEL=pw6
# 自动更新从已有的实验机型安装过来时（auto-update.sh 读 $SYS/model 来设 VD_EXPERIMENTAL）：loop 机型（KT5/KT6/PW5）
# Android 没在跑时系统盘没挂上，读不到 model，更新就被下面拒绝，设备永远卡在旧版本（KT5 0.78 -> 0.79 模拟器复现）。
# 已经装过（程序目录 + 系统盘都在）就说明用户当初选了实验机型：沿用，并把机型也记在系统盘外面。
if [ "$MODEL" != "pw6" ] && [ "$MODEL" != cs ] && [ "${VD_EXPERIMENTAL:-0}" != "1" ]; then
    prevm=$(cat /mnt/base-us/veradroid/model 2>/dev/null || true)   # set -e：文件不存在不能让脚本退出
    if [ "$prevm" = "$MODEL" ] || { [ -z "$prevm" ] && [ -d /mnt/base-us/veradroid/boot ] && [ -f /mnt/base-us/veradroid-sys.img ]; }; then
        echo "已安装的实验机型 $MODEL：继续更新"
        VD_EXPERIMENTAL=1
    fi
fi
# 还不能装的机型先说清楚：以前 PW4 / 入门款 2019 用正式脚本时被告知“请改用实验机型脚本”，换了脚本才看到“暂时不能安装”，
# 而且这些原因只写进日志，屏幕上只有“安装失败（代码 1）”。现在直接在屏幕上显示原因（show = 日志 + eips）。
case "$MODEL" in
    imx41) show "老机型：请改用官网“老机型安装”脚本，本机未改动"; exit 1 ;;
    legacy) [ "${VD_LEGACY_TEST:-0}" = 1 ] || { show "老机型：请改用官网“老机型安装”脚本，本机未改动"; exit 1; } ;;
esac
if [ "$MODEL" != "pw6" ] && [ "$MODEL" != cs ] && [ "${VD_EXPERIMENTAL:-0}" != "1" ]; then
    show "这台 Kindle 是 $MODEL（实验机型）：请改用“VeraDroid 安装（在线，实验机型）”（VeraDroid-Experimental.sh）（离线：VeraDroid-Offline-Experimental.zip）"
    echo "EN: This Kindle ($MODEL) needs VeraDroid-Experimental.sh or VeraDroid-Offline-Experimental.zip"   # 书库脚本拿去在屏幕上写英文（eips 没有中文字形）
    exit 1
fi
case "$MODEL" in
    pw6|cs) ok='1272,1696 1272,3392' ;;
    pw5) ok='1236,1648 1236,3296 1248,1648 1248,3296' ;;
    kt5|kt6) ok='1072,1448 1072,2896' ;;
    ks) ok='1860,2480 2480,1860 1860,4960 2480,3720 2480,4960 1872,2480 1872,4960' ;;
    ks2) ok='1872,4960 1872,2480 1860,2480 1860,4960' ;;
    oa2|ko3) ok='1264,1680 1264,3360' ;;
    imx41) echo "这台是老机型：请用官网的老机型安装脚本（veradroid.wenjin.jp/install/legacy），不会改动这台 Kindle。"; exit 1 ;;
    legacy) # 3.0 内核老机型（PW2 / PW3 等）：模拟器里还没开机到桌面，先不让普通用户装；测试时带 VD_LEGACY_TEST=1
        [ "${VD_LEGACY_TEST:-0}" = 1 ] || { echo "这台是老机型：请用官网的老机型安装脚本（veradroid.wenjin.jp/install/legacy），不会改动这台 Kindle。"; exit 1; }
        ok="$res" ;;   # 机型多、分辨率各不相同：显示尺寸开机时从帧缓冲读
    *) ok='' ;;
esac
valid=0
for r in $ok; do [ "$res" = "$r" ] && valid=1; done
if [ "$valid" != "1" ]; then
    echo "机型 $MODEL 的分辨率不匹配（检测到 $res）。"
    echo "EN: Screen size $res does not match model $MODEL. Nothing was changed."
    echo "这是实验性适配，未验证前不要安装。"
    exit 1
fi
if [ "$MODEL" != "pw6" ] && [ "$MODEL" != cs ]; then
    echo "!! 实验机型 $MODEL：尚未实机验证，可能黑屏/触摸失效。继续。"
fi
# Package survey: the Android userspace needs firmware 5.18+.
FW=$(sed -n 's/.*Kindle[[:space:]]*\([0-9][0-9.]*\).*/\1/p' /etc/prettyversion.txt 2>/dev/null | head -1)
if [ -n "$FW" ]; then
    fwn=$(printf '%s\n' "$FW" | awk -F. '{printf "%d%03d%03d", $1, $2, $3}')
    # PW5 停在 5.16.x 的用户（WinterBreak2 / SpiderCat / Nosebleed 越狱，升级就没了）：5.16 的内核和 5.17.1 是同一组
    # （binder / uinput / tun 都编进内核，config 完全相同；没有 PID 命名空间 -> chroot 模式，和 5.17.1 一样），不需要外挂模块。
    # 内核里真没有 binder 的话照样拒绝。
    fwmin=5017000; fwmin_s=5.17
    case "$MODEL" in pw5) fwmin=5016000; fwmin_s="5.16（PW5）" ;; oa2) fwmin=5013000; fwmin_s="5.13（Oasis 2）" ;; ko3) fwmin=5015000; fwmin_s="5.15（Oasis 3）" ;; esac
    if [ "$MODEL" != legacy ] && [ "$fwn" -lt "$fwmin" ] 2>/dev/null && [ "${VD_ALLOW_OLD_FW:-0}" != 1 ]; then
        die "固件 $FW 太旧；VeraDroid 需要 $fwmin_s 或更新"
    fi
    if [ "$fwn" -lt 5017000 ] 2>/dev/null && [ "$MODEL" = pw5 ] && ! grep -qw binder /proc/misc 2>/dev/null && [ ! -e /dev/binder ]; then
        die "这台 Kindle 的内核（$(uname -r)）没有 binder，VeraDroid 不能运行；本机未改动"
    fi
fi
if [ "${VD_DRY_RUN:-0}" = "1" ]; then
    fw=$(sed -n 's/.*Kindle[[:space:]]*\([0-9][0-9.]*\).*/\1/p' /etc/prettyversion.txt 2>/dev/null | head -1)
    show "自检通过：model=$MODEL firmware=${fw:-unknown} framebuffer=$res"
    show "未下载、未安装（VD_DRY_RUN=1）"
    exit 0
fi
[ "$(id -u)" = "0" ] || die "需要 root/越狱环境"
for c in curl tar id; do
    command -v "$c" >/dev/null 2>&1 || die "缺少命令 $c"
done
[ -d /mnt/base-us ] && [ -w /mnt/base-us ] || die "/mnt/base-us 不可写；这个越狱方式可能不兼容"
# ---- 老机型（3.0 内核：PW2 / PW3 / Voyage …）安装期间的保护 ----
# PW2 / Voyage 模拟器上两次装到一半整机重启：Kindle 自己的 Java 框架（cvm）和安装器（建 1.5 GB 系统盘、解压）抢 CPU 和闪存，
# 105 秒内没起来就算一次失败，连续 3 次 lab126_gui_monitor 就重启整机（/etc/upstart/monitor_functions 的计数文件）。所以：
#  1. 开机后马上装的话，先等框架起来（最多 3 分钟），别在它最需要 CPU 的时候抢；
#  2. 重活（建盘、校验、解压）用低优先级（nice 10、ionice 尽力而为级最低档），Kindle 的界面和看门狗先跑；
#  3. 安装期间每 5 秒清一次 lab126_gui / framework 的重启计数，框架真起不来时也不会在安装途中重启整机（安装结束就不再管）。
# 大量写盘期间（解压）每 2 秒 sync 一次：小内存的老机型上脏页攒多了一次刷盘会把整机卡住（见下面建系统盘的说明）
SYNCER=
syncer_on() { [ -n "$LOWPRI" ] || return 0; ( while :; do sleep 2; sync; done ) </dev/null >/dev/null 2>&1 & SYNCER=$!; }
syncer_off() { [ -n "$SYNCER" ] && kill "$SYNCER" 2>/dev/null; SYNCER=; return 0; }
LOWPRI=
# 解压 / 写系统时低优先级（所有机型）：Kindle 自己的进程（界面、Wi-Fi、电源）先跑
UNPACK_PRI="nice -n 10"; command -v ionice >/dev/null 2>&1 && UNPACK_PRI="$UNPACK_PRI ionice -c 2 -n 7"
if [ "$MODEL" = legacy ]; then
    LOWPRI="nice -n 10"; command -v ionice >/dev/null 2>&1 && LOWPRI="$LOWPRI ionice -c 2 -n 7"
    if [ "$(lipc-get-prop com.lab126.kaf frameworkStarted 2>/dev/null)" != 1 ] && initctl status framework 2>/dev/null | grep -q 'start/'; then
        show "等待 Kindle 界面启动完成…"
        i=0; while [ $i -lt 60 ] && [ "$(lipc-get-prop com.lab126.kaf frameworkStarted 2>/dev/null)" != 1 ]; do sleep 3; i=$((i + 1)); done
        vlog "legacy: waited $((i * 3))s for the Kindle framework (frameworkStarted=$(lipc-get-prop com.lab126.kaf frameworkStarted 2>/dev/null))"
    fi
    ( while kill -0 $$ 2>/dev/null; do
          for j in lab126_gui framework framework_setup; do rm -f "/var/run/upstart/$j.restarts" "/var/local/upstart/$j.restarts"; done
          sleep 5
      done ) </dev/null >/dev/null 2>&1 &
fi
FS=$(stat -f -c %T /mnt/base-us 2>/dev/null || true)
LOOP=0
case "$FS" in
    ext2/ext3|ext4|xfs|btrfs|f2fs) ;;
    vfat|msdos|exfat) LOOP=1; mount -o remount,exec /mnt/base-us 2>/dev/null || true ;;
    *) die "/mnt/base-us 是 $FS，不能保存 Android 的符号链接" ;;
esac
# KT6 等 4.9 内核机型（eink-emulator 实测）：/mnt/base-us/system 和 documents 用 fscrypt v1 加密，
# 密钥挂在 uid 0 的默认会话 keyring 里。system_server（uid 1000）拿不到密钥，新建文件报
# "Required key not available"，开机循环崩溃。改用 ext4 loop 系统盘，镜像内部不加密。
# 只限 4.x 内核：PW6（5.15）不走这里，已有安装不会被挪动。
if [ "$LOOP" = 0 ]; then
    case "$(uname -r)" in
        4.*) grep -q ' logon .*fscrypt:' /proc/keys 2>/dev/null && LOOP=1 ;;
    esac
fi
[ -d /dev/shm ] || die "缺少 /dev/shm"
ui_start      # 进度画面（方块风格）：从这里起每一步都画在上面

stamp=$(date +%Y%m%d-%H%M%S)
if [ "$LOOP" = 1 ]; then
    # 上次建系统盘被打断（断电 / 被杀：在 vfat 上 dd 3 GB 要好几分钟）会留下一个没有文件系统的半截镜像，
    # 以后每次安装都“无法挂载 ext4 系统盘”。没挂着、也没有 ext2/3/4 超级块（偏移 1080 处 53 ef）的镜像里不可能有数据：删掉重建。
    if [ -f "$SYSTEM_IMG" ] && ! grep -q " $SYS " /proc/mounts; then
        mg=$(dd if="$SYSTEM_IMG" bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')
        if [ -n "$mg" ] && [ "$mg" != "53ef" ]; then   # od / dd 不可用时 mg 为空：什么都不删
            vlog "system image $SYSTEM_IMG has no ext superblock (magic '$mg', size $(stat -c %s "$SYSTEM_IMG" 2>/dev/null)): interrupted creation, recreating"
            rm -f "$SYSTEM_IMG"
        fi
    fi
    if [ ! -f "$SYSTEM_IMG" ]; then
        # 3.0 内核没有 ext4 驱动，只能用 ext3；这些机型存储也小（PW2 2 GB），系统盘 1.5 GB 够用
        # 起始大小按剩余空间挑（装好以后可以在 设置 → 设备与更新 → 存储空间 里调，快满时还会自动扩大，见 boot/lib/storage.sh）：
        # 默认 2 GB；Kindle 快满时依次退到 1536 / 1280 / 1024 MB。条件：系统盘 + 安装要用的地方（IMGSLACK）+ 留给 Kindle 的 256 MB
        # 都放得下 —— 以前固定 3072 MB，8 GB 的机器上书一多就装不了，装上了也把 Kindle 挤满。
        IMGFS=ext4; IMGSIZES="2048 1536 1280 1024"
        IMGSLACK=300; IMGKEEP=256
        # legacy：建完系统盘还要下载并解开约 140 MB 的安装包（下载前的检查要 ~350 MB）。余量只留 300 MB 时，
        # 3 GB 存储的 PW3 上先花十几分钟建好 1.5 GB 的盘，然后才报“空间不够，无法下载”（模拟器实测）。先把账算够。
        # 这些机型存储小（PW2 2 GB），没有 ext4 驱动只能用 ext3：1536 起，放不下退到 1024 / 768，不另留 256 MB。
        case "$(uname -r)" in 3.*) IMGFS=ext3; IMGSIZES="1536 1024 768"; IMGSLACK=400; IMGKEEP=0 ;; esac
        free_mb=$(df -m /mnt/base-us 2>/dev/null | awk 'NR==2{print $4}')
        IMGMB=
        for c in $IMGSIZES; do
            IMGMIN=$c
            case "$free_mb" in ''|*[!0-9]*) IMGMB=$c; break ;; esac        # 读不到剩余空间：按默认值试
            [ "$free_mb" -ge $((c + IMGSLACK + IMGKEEP)) ] && { IMGMB=$c; break; }
        done
        [ -n "$IMGMB" ] || die "Kindle 空间不够：至少需要约 $((IMGMIN + IMGSLACK + IMGKEEP)) MB，现在只有 ${free_mb} MB"
        vp_step disk "创建 $IMGFS 系统盘（$IMGMB MB）" "只在第一次安装时做；存储慢的机器要几分钟"
        show "创建 $IMGFS 系统盘（$IMGMB MB）…"
        if [ -n "$LOWPRI" ]; then
            # 老机型（vfat 用户分区不支持稀疏文件，整盘全要真写）：一次写完会在 256 / 512 MB 内存里攒满脏页，
            # 整机卡死几十秒（PW2 / Voyage 模拟器在这一步被硬件看门狗复位过好几次）。每 32 MB 落盘一次、歇一下。
            rm -f "$SYSTEM_IMG"; o=0
            while [ $o -lt $IMGMB ]; do
                $LOWPRI dd if=/dev/zero of="$SYSTEM_IMG" bs=1M count=32 seek=$o conv=notrunc 2>/dev/null || die "无法创建系统盘"
                sync; o=$((o + 32)); progress_line $((o * 100 / IMGMB)) "建系统盘"; sleep 1
            done
            [ "$(stat -c %s "$SYSTEM_IMG" 2>/dev/null)" -ge $((IMGMB * 1048576)) ] 2>/dev/null || die "无法创建系统盘"
        else
            ( truncate -s ${IMGMB}M "$SYSTEM_IMG" 2>/dev/null || dd if=/dev/zero of="$SYSTEM_IMG" bs=1M count=$IMGMB 2>/dev/null ) || die "无法创建系统盘"
        fi
        $LOWPRI mkfs.$IMGFS -F -q -L VeraDroid "$SYSTEM_IMG" || die "mkfs.$IMGFS 失败"
    fi
    if ! grep -q " $SYS " /proc/mounts; then
        if [ -d "$SYS" ] && [ -n "$(ls -A "$SYS" 2>/dev/null)" ]; then
            mv "$SYS" "$SYS.pre-ext4.$stamp" 2>/dev/null || true
        fi
        mkdir -p "$SYS"
        loop_mount "$SYSTEM_IMG" "$SYS" || die "无法挂载 ext4 系统盘"
    fi
    # 卸载（或更早的非 loop 安装）留下的书 / 微信读书下载 / token 在 $SYS.pre-ext4.*/data/media：搬进系统盘（不覆盖、原目录保留）
    if [ ! -d "$SYS/data/media/0" ]; then
        pre=
        [ -d /mnt/base-us/veradroid/backup/media-keep/0 ] && pre=/mnt/base-us/veradroid/backup/media-keep   # 卸载时留下的
        [ -n "$pre" ] || pre=$(ls -dt "$SYS".pre-ext4.*/data/media 2>/dev/null | head -1)
        if [ -n "$pre" ]; then
            need=$(du -sk "$pre" 2>/dev/null | awk '{print $1}')
            freei=$(df -k "$SYS" 2>/dev/null | awk 'NR==2{print $4}')
            if [ $(( ${need:-0} + 102400 )) -lt "${freei:-0}" ]; then
                show "把以前的书和账号搬回系统盘…"
                if mkdir -p "$SYS/data" && cp -a "$pre" "$SYS/data/media"; then
                    vlog "restored data/media from $pre"
                    # 卸载留下的那份：核对数量一致后删掉（已经在系统盘里了）；pre-ext4 的旧目录不动
                    if [ "$pre" = /mnt/base-us/veradroid/backup/media-keep ] && [ "$(find "$pre" | wc -l)" = "$(find "$SYS/data/media" | wc -l)" ]; then
                        rm -rf "$pre" && vlog "removed $pre after verified copy"
                    fi
                else
                    vlog "copy from $pre failed"
                fi
            else
                vlog "not enough space in system image to restore $pre (${need}K)"
            fi
        fi
    fi
fi

# BEGIN vd_parts
# 这一块在 bootstrap.sh 和 bootstrap-legacy.sh 里一字不差（tests/dl-block-test.sh）。
# ---- 增量更新（parts；docs/incremental-updates.md）----
# 元数据里有 "parts"（索引地址）时，只下载和本机不一样的部分，不下整包：
#  - 程序目录按目录分成几个部件（boot / bin / fonts / apks …），本机已有同样内容的文件（按 sha256 认，不信版本号）就从本机拷；
#  - 系统镜像：本机镜像里已有的文件（同样按 sha256 认，包括 slim 挪出去的）直接硬链接，缺的从“差量”部件（相对旧镜像新增 / 变化的文件）
#    或 vol 部件（安装器 / 宿主会改的几个文件）补；都不够才下整个镜像（xz，比 gz 小约 25%）。
#  - 重建出来的程序目录和系统镜像都逐个文件和完整目标（索引里的清单）核对 sha256 / 权限 / 链接，任何一步不对就回到整包（或整个镜像）。
# 整包、老安装器、离线包完全不受影响：新字段只是加在元数据后面。VD_PARTS=0 关掉。
PARTS=; PARTS_VER=; PARTS_ROOTFS=; PARTS_TREE=; PARTS_ROOTFS_GZ=; PARTS_BASE=; PARTS_DL=0; PARTS_FULL=; PD=
vd_parts_url() { echo "$PARTS_BASE/$1?v=$(printf '%s' "$2" | cut -c1-8)"; }
# vd_parts_get 文件 sha 字节 说明：下载一个部件到 $PD 并校验（坏了重下一次）
vd_parts_get() {
    pg_f=$PD/$1; pg_n=0
    while [ $pg_n -lt 2 ]; do
        pg_n=$((pg_n + 1))
        [ -f "$pg_f" ] && verify_sha "$pg_f" "$2" && return 0
        [ -f "$pg_f" ] && [ "$(fsize "$pg_f")" -ge "$3" ] 2>/dev/null && rm -f "$pg_f"   # 下全了却不对：从头来
        vp_step dl "$4" "$NET_SUM"
        vd_download "$(vd_parts_url "$1" "$2")" "$pg_f" "$3" || { vlog "parts: $1: download failed (curl $DL_RC)"; return 1; }
        PARTS_DL=$((PARTS_DL + $3))
        verify_sha "$pg_f" "$2" && return 0
        vlog "parts: $1: sha256 mismatch"; rm -rf "$pg_f" "$pg_f.seg"
        VD_MIRRORS=; rm -f "$TMP/dl.pick"   # 重下只用主站
    done
    return 1
}
# 目录下所有普通文件（不跨挂载点）："sha256 路径"
vd_parts_hash() {
    for ph_d in "$@"; do [ -d "$ph_d" ] && $UNPACK_PRI find "$ph_d" -xdev -type f -exec sha256sum {} + 2>/dev/null; done | awk '{ print substr($0, 1, 64) " " substr($0, 67) }'
    return 0
}
# 树的清单（在树里运行，路径 ./…）："hexmode perm uid gid dev ino size mtime 路径"
vd_parts_stat() { ( cd "$1" && $UNPACK_PRI find . -xdev ! -path . ! -path './data/*' -exec stat -c '%f %a %u %g %d %i %s %Y %n' {} + ) 2>/dev/null; }
vd_parts_links() { ( cd "$1" && find . -xdev ! -path './data/*' -type l -exec stat -c '%N' {} + ) 2>/dev/null; }
# 树 $1 的规范清单（和 mkparts.py 的 rootfs.sums 同一格式，已排序）写到 $2。文件的 sha256：按 dev/ino/size/mtime 在 $PD/hashdb 里找，
# 找不到的现算并记进 hashdb（同一个 inode 只算一次：硬链接出来的新树不用再算）
vd_parts_canon() {
    vd_parts_stat "$1" > "$PD/c.stat"; vd_parts_links "$1" > "$PD/c.links"
    touch "$PD/hashdb"
    awk 'FILENAME == ARGV[1] { h[$1 " " $2 " " $3 " " $4] = 1; next }
         { t = substr($1, 1, length($1) - 3); if (t != "8") next
           if (!(($5 " " $6 " " $7 " " $8) in h)) { p = $0; for (i = 1; i <= 8; i++) sub(/^[^ ]* /, "", p); print p } }' "$PD/hashdb" "$PD/c.stat" > "$PD/c.todo"
    if [ -s "$PD/c.todo" ]; then
        ( cd "$1" && tr '\n' '\0' < "$PD/c.todo" | $UNPACK_PRI xargs -0 sha256sum ) > "$PD/c.sha" 2>/dev/null || true
        awk 'FILENAME == ARGV[1] { s[substr($0, 67)] = substr($0, 1, 64); next }
             { p = $0; for (i = 1; i <= 8; i++) sub(/^[^ ]* /, "", p); if ((p in s) && substr($1, 1, length($1) - 3) == "8") print $5, $6, $7, $8, s[p] }' "$PD/c.sha" "$PD/c.stat" >> "$PD/hashdb"
    fi
    awk 'FILENAME == ARGV[1] { h[$1 " " $2 " " $3 " " $4] = $5; next }
         FILENAME == ARGV[2] { if (match($0, /^'"'"'[^'"'"']*'"'"' -> '"'"'/)) { p = substr($0, 2, RLENGTH - 7); x = substr($0, RLENGTH + 1); sub(/'"'"'$/, "", x); l[p] = x }; next }
         { p = $0; for (i = 1; i <= 8; i++) sub(/^[^ ]* /, "", p); t = substr($1, 1, length($1) - 3)
           if (t == "4") print "d", $2, $3, $4, p
           else if (t == "a") print "l", ((p in l) ? l[p] : "?"), p
           else if (t == "8") print "f", ((($5 " " $6 " " $7 " " $8) in h) ? h[$5 " " $6 " " $7 " " $8] : "?"), $2, $3, $4, p
           else print "x", t, p }' "$PD/hashdb" "$PD/c.links" "$PD/c.stat" | LC_ALL=C sort > "$2"
}
# BEGIN vd_parts_under
# chroot 模式（4.9 内核的 KT6 / PW5 5.17 等，宿主和 Android 同一个挂载命名空间）Android 在跑时，宿主把几个文件直接绑在
# $SYS/rootfs 上面（dex2oat / cmd 的包装、闹钟修复的 libandroid_servers.so）：上面这一段哈希到的是绑上去的那份，
# 镜像里的原版“本机没有”，又不在 vol 部件里 -> 每次更新都下整个系统镜像（约 97 MB；用户诊断 KT6 5.17.1：
# 1.04 -> 1.07、1.07 -> 1.10 都是 image: full，慢网下光这一项 20 分钟）。这里把被盖住的原文件也算上：
# 非递归地把 $SYS/rootfs 再绑一次（只看得到镜像本身，看不到上面的挂载），读原文件的 sha256。输出 "sha 路径"（路径是
# 正常的 $SYS/rootfs/...：第二段在 Android 停了、挂载都卸掉以后才从那里拷，那时就是原文件）。
# 临时目录在 tmpfs 上，只 rmdir 不 rm -rf：万一卸不掉，也不会顺着它删到镜像里。
vd_parts_under_list() { # $SYS/rootfs 里被挂载盖住的路径（相对 rootfs；data/ 不管；带转义的怪名字跳过）
    awk -v r="$SYS/rootfs/" 'index($2, r) == 1 && $2 != r "data" && index($2, r "data/") != 1 && index($2, "\\") == 0 { print substr($2, length(r) + 1) }' "${VD_PROC_MOUNTS:-/proc/mounts}" 2>/dev/null
}
vd_parts_under() {
    [ -d "$SYS/rootfs" ] || return 0
    pu_l=$(vd_parts_under_list)
    [ -n "$pu_l" ] || return 0
    pu_d=/dev/shm/vd-parts-under.$$; mkdir -p "$pu_d" 2>/dev/null || return 0
    if mount --bind "$SYS/rootfs" "$pu_d" 2>/dev/null; then
        pu_n=0
        for pu_p in $pu_l; do
            [ -f "$pu_d/$pu_p" ] && [ ! -L "$pu_d/$pu_p" ] || continue
            pu_s=$(sha256sum "$pu_d/$pu_p" 2>/dev/null | cut -c1-64)
            [ -n "$pu_s" ] && { echo "$pu_s $SYS/rootfs/$pu_p"; pu_n=$((pu_n + 1)); }
        done
        umount "$pu_d" 2>/dev/null || umount -l "$pu_d" 2>/dev/null
        vlog "parts: $pu_n image files hidden under host bind mounts hashed from the image itself"
    fi
    rmdir "$pu_d" 2>/dev/null
    return 0
}
# END vd_parts_under
# ---- 第一段（下载阶段，Android 可能还在跑：只读本机文件）----
vd_parts_prepare() { # 元数据 json；成功：$TMP/veradroid-pw6 就绪（没有 rootfs.tar.gz），PARTS_ROOTFS=tree|full
    [ "${VD_PARTS:-1}" != 0 ] || return 1
    pp_u=$(printf '%s' "$1" | sed -n 's/.*"parts": *"\([^"]*\)".*/\1/p')
    pp_s=$(printf '%s' "$1" | sed -n 's/.*"parts_sha256": *"\([^"]*\)".*/\1/p')
    pp_z=$(printf '%s' "$1" | sed -n 's/.*"parts_size": *\([0-9]*\).*/\1/p')
    pp_v=$(printf '%s' "$1" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
    [ -n "$pp_u" ] && [ -n "$pp_s" ] || return 1
    for c in sha256sum xzcat stat find xargs; do command -v $c >/dev/null 2>&1 || { vlog "parts: no $c, whole bundle"; return 1; }; done
    PD=$TMP/parts; rm -rf "$PD" "$TMP/veradroid-pw6"; mkdir -p "$PD/idx" || return 1
    PARTS_BASE=${pp_u%%\?*}; PARTS_BASE=${PARTS_BASE%/*}
    vp_step dl "增量更新：读取更新清单" "$NET_SUM"
    # 索引也和部件一样：sha256 不对（镜像上是旧的 / 坏的）就只从主站再下一次
    pp_i=0
    until vd_download "$pp_u" "$PD/index.tar.gz" "${pp_z:-0}" && verify_sha "$PD/index.tar.gz" "$pp_s"; do
        rm -rf "$PD/index.tar.gz" "$PD/index.tar.gz.seg"; pp_i=$((pp_i + 1))
        [ $pp_i -lt 2 ] && [ -n "$VD_MIRRORS" ] || { vlog "parts: index unavailable"; return 1; }
        vlog "parts: index download failed or sha256 mismatch: again from the main site only"
        VD_MIRRORS=; rm -f "$TMP/dl.pick"
    done
    tar -xzf "$PD/index.tar.gz" -C "$PD/idx" 2>/dev/null || { vlog "parts: index unreadable"; return 1; }
    I=$PD/idx/index
    [ "$(head -n 1 "$I")" = "VDPARTS 1" ] && [ -s "$PD/idx/rootfs.sums" ] || { vlog "parts: unknown index format"; return 1; }
    PARTS_VER=$(awk '$1 == "version" { print $2 }' "$I")
    [ -n "$PARTS_VER" ] && [ "$PARTS_VER" = "$pp_v" ] || { vlog "parts: index version '$PARTS_VER' != '$pp_v'"; return 1; }
    # 旧的半成品（上次被打断）
    if [ -e "$SYS/rootfs.vdnew" ]; then mounts_under "$SYS/rootfs.vdnew" 1 && return 1; rm -rf "$SYS/rootfs.vdnew"; fi
    vp_step verify "增量更新：核对本机已有的文件" "只下载有变化的部分；系统镜像较大，要一两分钟"
    pp_t0=$(vp_now)
    # 程序目录的来源：已装的程序目录、系统里的 VeraHome / VeraIME / libandroid_runtime、KUAL 扩展、上次装时留的原版文件
    { vd_parts_hash "$VD/boot" "$VD/bin" "$VD/fonts" "$VD/kmod" "$VD/models" "$VD/screensaver" "$VD/apps" "$SYS/vd-pristine" /mnt/us/extensions/VeraDroid
      for f in "$SYS/rootfs/system/priv-app/VeraHome/VeraHome.apk" "$SYS/rootfs/system/app/VeraIME/VeraIME.apk" "$SYS/rootfs/system/lib/libandroid_runtime.so"; do
          [ -f "$f" ] && sha256sum "$f" 2>/dev/null | awk '{ print substr($0, 1, 64) " " substr($0, 67) }'; done; } > "$PD/src.host"
    # 系统镜像的来源：已装的镜像（不跨挂载点，跳过 data/）和 slim 挪出去的系统应用；记 dev/ino/size/mtime，第二段不用再算
    : > "$PD/hashdb"; : > "$PD/src.rootfs"
    for pp_d in "$SYS/rootfs" "$SYS/slim-removed"; do
        [ -d "$pp_d" ] || continue
        vd_parts_canon "$pp_d" "$PD/c.out"
        awk -v d="$pp_d" 'FILENAME == ARGV[1] { if ($1 == "f" && $2 != "?") { p = $0; for (i = 1; i <= 5; i++) sub(/^[^ ]* /, "", p); print $2, d "/" substr(p, 3) }; next }' "$PD/c.out" >> "$PD/src.rootfs"
    done
    vd_parts_under >> "$PD/src.rootfs"
    vlog "parts: hashed local files in $(( $(vp_now) - pp_t0 ))s ($(wc -l < "$PD/src.host" | tr -d ' ') program files, $(wc -l < "$PD/src.rootfs" | tr -d ' ') image files)"
    # 程序目录：每个部件，所有文件本机都有 -> 从本机拷；否则下载
    pp_need=$(awk 'FILENAME == ARGV[1] { h[$1] = 1; next } $1 == "file" && !($2 in h) { n[$4] = 1 } END { for (p in n) print p }' "$PD/src.host" "$I" | LC_ALL=C sort | tr '\n' ' ')
    # 系统镜像：缺的内容（sha256）
    awk 'FILENAME == ARGV[1] { h[$1] = 1; next } $1 == "f" && !($2 in h) { print $2 }' "$PD/src.rootfs" "$PD/idx/rootfs.sums" | LC_ALL=C sort -u > "$PD/missing"
    awk 'FILENAME == ARGV[1] { h[$1] = 1; next } ($1 in h) { print }' "$PD/src.host" "$PD/missing" > "$PD/missing.h"   # vd-pristine 里有的
    awk 'FILENAME == ARGV[1] { h[$1] = 1; next } !($1 in h) { print }' "$PD/missing.h" "$PD/missing" > "$PD/missing.r"
    # 选最省的组合：不用下 / vol / 差量(+vol) / 整个镜像
    pp_best=; pp_bz=; pp_full=$(awk '$1 == "rpart" && $2 == "full" { print $3, $4, $5, $6; exit }' "$I")
    [ -n "$pp_full" ] || return 1
    set -- $pp_full; pp_bz=$2; pp_best="full"
    pp_vol=$(awk '$1 == "rpart" && $2 == "vol" { print $3, $4, $5, $6, $7; exit }' "$I")
    covers() { awk 'FILENAME != ARGV[ARGC - 1] { h[$1] = 1; next } !($1 in h) { bad = 1; exit } END { exit bad }' "$@"; }
    if [ ! -s "$PD/missing.r" ]; then pp_best=none; pp_bz=0
    else
        vz=; [ -n "$pp_vol" ] && { set -- $pp_vol; vz=$2; }
        if [ -n "$vz" ] && covers "$PD/idx/$5" "$PD/missing.r" && [ "$vz" -lt "$pp_bz" ]; then pp_best=vol; pp_bz=$vz; fi
        while read -r k kind dsh sz fn us lst from; do
            [ "$k" = rpart ] && [ "$kind" = delta ] || continue
            if covers "$PD/idx/$lst" "$PD/missing.r"; then t=$sz
            elif [ -n "$vz" ] && covers "$PD/idx/$lst" "$PD/idx/$(echo $pp_vol | cut -d' ' -f5)" "$PD/missing.r"; then t=$((sz + vz))
            else continue; fi
            [ "$t" -lt "$pp_bz" ] && { pp_best="delta $dsh"; pp_bz=$t; }
        done < "$I"
    fi
    pp_hz=$(awk -v n=" $pp_need" '$1 == "part" && index(n, " " $2 " ") { s += $4 } END { print s + 0 }' "$I")
    pp_tot=$((pp_hz + pp_bz))
    vlog "parts: $cur_ver -> $PARTS_VER: program parts to download: ${pp_need:-none} ($pp_hz bytes); image: $pp_best ($pp_bz bytes, $(wc -l < "$PD/missing" | tr -d ' ') files missing locally)"
    [ "$pp_tot" -lt "${size:-999999999999}" ] 2>/dev/null || { vlog "parts: not smaller than the whole bundle"; return 1; }
    # 空间：下载的部件 + 解开后的程序目录（约 30 MB）+ 余量
    pp_mb=$(( pp_tot / 1048576 * 2 + 128 ))
    pp_free=$(df -m /mnt/base-us 2>/dev/null | awk 'NR == 2 { print $4 }')
    [ "${pp_free:-0}" -ge "$pp_mb" ] 2>/dev/null || { vlog "parts: need ${pp_mb} MB, free ${pp_free:-?} MB"; return 1; }
    # 要下载的部件（先下 bin：里面的 FBInk 能马上换成整屏方块画面）："文件 sha 字节 说明"
    : > "$PD/dl.list"
    pp_order=$(echo " $pp_need " | sed 's/ bin / /'); case " $pp_need " in *" bin "*) pp_order="bin $pp_order" ;; esac
    for p in $pp_order; do
        awk -v p="$p" '$1 == "part" && $2 == p { print $5, $3, $4, p; exit }' "$I" >> "$PD/dl.list"
    done
    pp_rl=; PARTS_ROOTFS=tree; PARTS_FULL=$pp_full
    case "$pp_best" in
        full) set -- $pp_full; echo "$3 $1 $2 系统镜像" >> "$PD/dl.list"; PARTS_ROOTFS=full; PARTS_ROOTFS_GZ=$(( $4 / 2 )) ;;
        none) PARTS_ROOTFS_GZ=1048576 ;;
        *)  PARTS_ROOTFS_GZ=0
            if [ "$pp_best" != vol ]; then
                set -- $(awk -v s="${pp_best#delta }" '$1 == "rpart" && $3 == s { print $3, $4, $5, $6, $7; exit }' "$I")
                echo "$3 $1 $2 系统镜像的变化" >> "$PD/dl.list"; pp_rl=$3; PARTS_ROOTFS_GZ=$4
                covers "$PD/idx/$5" "$PD/missing.r" && pp_vol=
            fi
            if [ -n "$pp_vol" ]; then
                set -- $pp_vol; echo "$3 $1 $2 系统文件" >> "$PD/dl.list"; pp_rl="$pp_rl $3"; PARTS_ROOTFS_GZ=$((PARTS_ROOTFS_GZ + $4))
            fi ;;
    esac
    # 系统盘（镜像机型）够不够装：在下载任何部件之前查，不够先在线扩大，扩不了就停在这里（vd_sys_room_early）
    PARTS=1; vd_sys_room_early; PARTS=
    pp_cnt=$(wc -l < "$PD/dl.list" | tr -d ' '); pp_n=0
    while read -r f s z l; do
        pp_n=$((pp_n + 1))
        vd_parts_get "$f" "$s" "$z" "增量更新 $pp_n/$pp_cnt：$l（$(vp_mb "$z") MB）" < /dev/null || return 1
    done < "$PD/dl.list"
    # 程序目录：下载的部件解开，其余文件从本机拷，然后整个目录和清单核对
    vp_step unpack "增量更新：组装新版本" "只用核对过的文件"
    mkdir -p "$TMP/veradroid-pw6" || return 1
    for p in $pp_need; do
        f=$(awk -v p="$p" '$1 == "part" && $2 == p { print $5; exit }' "$I")
        xzcat "$PD/$f" | tar -xf - -C "$TMP" || { vlog "parts: unpack $p failed"; return 1; }
    done
    awk -v n=" $pp_need " -v t="$TMP/veradroid-pw6" -v q="'" '
        FILENAME == ARGV[1] { if (!($1 in s) && index(substr($0, 66), q) == 0) s[$1] = substr($0, 66); next }
        $1 == "file" && !index(n, " " $4 " ") { d = $5; sub(/\/[^\/]*$/, "", d)
            if (d != $5) print "mkdir -p " q t "/" d q
            print "cp " q s[$2] q " " q t "/" $5 q " && chmod " $3 " " q t "/" $5 q }' "$PD/src.host" "$I" > "$PD/copy.sh"
    sh -e "$PD/copy.sh" || { vlog "parts: copying local files failed"; return 1; }
    vp_upgrade "$TMP/veradroid-pw6/bin/fbink"
    ( cd "$TMP/veradroid-pw6" && find . -type f -exec sha256sum {} + ) 2>/dev/null | awk '{ print substr($0, 1, 64), substr($0, 69) }' | LC_ALL=C sort -k2 > "$PD/got.files"
    ( cd "$TMP/veradroid-pw6" && find . -type f -exec stat -c '%a %n' {} + ) 2>/dev/null | awk '{ print $1, substr($0, length($1) + 4) }' | LC_ALL=C sort -k2 > "$PD/got.perm"
    awk '$1 == "file" { print $2, $5 }' "$I" | LC_ALL=C sort -k2 > "$PD/want.files"
    awk '$1 == "file" { print $3, $5 }' "$I" | LC_ALL=C sort -k2 > "$PD/want.perm"
    # 权限只在这个目录真能存权限时才核对：KT5 / KT6 / Scribe 等的用户盘（fuse fsp）上每个文件读回来都是 777，
    # 整包安装也一样（装好的程序目录同在这块盘上），以前这里每次都失败、增量更新全部退回整包（r093 闸门 KT5 / PW5 实测）
    pp_pm=1; : > "$PD/.permtest" && chmod 640 "$PD/.permtest" 2>/dev/null && [ "$(stat -c %a "$PD/.permtest" 2>/dev/null)" = 640 ] || pp_pm=
    rm -f "$PD/.permtest"
    [ -n "$pp_pm" ] || vlog "parts: this file system keeps no permission bits (read back $(stat -c %a "$TMP/veradroid-pw6/VERSION" 2>/dev/null)): checking contents only"
    if ! cmp -s "$PD/got.files" "$PD/want.files" || { [ -n "$pp_pm" ] && ! cmp -s "$PD/got.perm" "$PD/want.perm"; }; then
        vlog "parts: program files do not match the index: $(diff "$PD/want.files" "$PD/got.files" 2>/dev/null | head -5 | tr '\n' ' ') $(diff "$PD/want.perm" "$PD/got.perm" 2>/dev/null | grep '^[-+][0-9]' | head -20 | tr '\n' ' ')"
        return 1
    fi
    # 系统镜像的差量 / vol 部件解开备用（第二段在 Android 停下后组装）
    mkdir -p "$PD/rsrc" || return 1
    for f in $pp_rl; do xzcat "$PD/$f" | tar -xf - -C "$PD/rsrc" || { vlog "parts: unpack $f failed"; return 1; }; done
    vlog "parts: ready, downloaded $PARTS_DL bytes, image $PARTS_ROOTFS ($pp_best)"
    ver=$PARTS_VER
    return 0
}
# ---- 第二段（Android 已停、还没动任何东西）：组装新的系统镜像 $SYS/rootfs.vdnew ----
vd_parts_tree() {
    T=$SYS/rootfs.vdnew
    [ -d "$SYS/rootfs" ] || return 1
    mounts_under "$SYS/rootfs" 1 && { vlog "parts: mounts under $SYS/rootfs"; return 1; }
    if [ -e "$T" ]; then mounts_under "$T" 1 && return 1; rm -rf "$T"; fi
    # 新镜像先整棵硬链接旧镜像（不占空间），再按目标清单修：多的删掉，不对的换掉，缺的从本机其他地方 / 下载的部件补
    $UNPACK_PRI cp -al "$SYS/rootfs" "$T" || { vlog "parts: cp -al failed"; rm -rf "$T"; return 1; }
    rm -rf "$T/data"; mkdir -p "$T/data"   # data/ 是安装器 / 运行时建的骨架，全包安装时也是新的
    vd_parts_canon "$PD/rsrc" "$PD/r.out" 2>/dev/null
    awk -v d="$PD/rsrc" '$1 == "f" && $2 != "?" { p = $0; for (i = 1; i <= 5; i++) sub(/^[^ ]* /, "", p); print $2, d "/" substr(p, 3) }' "$PD/r.out" > "$PD/src.dl"
    vd_parts_canon "$T" "$PD/t.now"
    # 路径里有空格 / 引号的（运行时建的怪名字）：不处理，走整个镜像
    awk '($1 == "d" && NF != 5) || ($1 == "f" && NF != 6) || ($1 == "l" && NF != 3) || ($1 == "x" && NF != 3) || index($0, "\047") { bad = 1 } END { exit bad }' "$PD/t.now" \
        || { vlog "parts: odd names in the installed image"; return 1; }
    tdev=$(stat -c %d "$T")
    # 来源：sha -> 路径（先用和新镜像同一文件系统的，能硬链接；再用其他的，拷贝）
    { cat "$PD/src.rootfs"; cat "$PD/src.dl"; awk '{ print }' "$PD/src.host"; } > "$PD/src.all"
    awk -v T="$T" -v q="'" -v tdev="$tdev" '
        FILENAME == ARGV[1] { if (index($0, q)) next; s = $1; p = substr($0, 66); if (!(s in src)) src[s] = p; next }
        FILENAME == ARGV[2] { p = $NF; now[p] = $0; next }
        { p = $NF; want[p] = $0; order[++n] = p }
        END {
            for (p in now) if (!(p in want) || substr(now[p], 1, 1) != substr(want[p], 1, 1) || (substr(now[p], 1, 1) != "d" && now[p] != want[p])) {
                print "rm -rf " q T "/" substr(p, 3) q; gone[p] = 1 }
            for (i = 1; i <= n; i++) { p = order[i]; w = want[p]; split(w, a, " "); x = q T "/" substr(p, 3) q
                if ((p in now) && !(p in gone)) {
                    if (a[1] == "d" && now[p] != w) print "chmod " a[2] " " x " && chown " a[3] ":" a[4] " " x
                    continue }
                if (a[1] == "d") print "mkdir -p " x " && chmod " a[2] " " x " && chown " a[3] ":" a[4] " " x
                else if (a[1] == "l") print "ln -s " q a[2] q " " x
                else if (a[1] == "f") { if (!(a[2] in src)) { print "echo missing " p " >&2; exit 1"; continue }
                    # 硬链接共享 inode（和权限）：权限一样才链，否则拷贝；链不上（不同挂载点：PW6 的 $SYS 和下载目录同一个分区也会 EXDEV）就拷贝。
                    # 安装器会原地改写的三个文件一律拷贝，旧镜像（回退用）不跟着变
                    print "vd_put " q src[a[2]] q " " x " " a[3] " " a[4] " " a[5] }
            } }' "$PD/src.all" "$PD/t.now" "$PD/idx/rootfs.sums" > "$PD/fix.body"
    { echo 'set -e'
      echo 'vd_put() { case "$2" in */system/priv-app/VeraHome/VeraHome.apk|*/system/app/VeraIME/VeraIME.apk|*/system/lib/libandroid_runtime.so|*/system/build.prop|*/init.kindle.rc) cp -p "$1" "$2" ;;'
      echo '  *) if [ "$(stat -c "%d %a %u %g" "$1")" = "'"$tdev"' $3 $4 $5" ]; then ln "$1" "$2" 2>/dev/null || cp -p "$1" "$2"; else cp -p "$1" "$2"; fi ;; esac'
      echo '  chmod "$3" "$2"; chown "$4:$5" "$2"; }'
      cat "$PD/fix.body"; } > "$PD/fix.sh"
    vlog "parts: image fix-up: $(grep -c '^rm ' "$PD/fix.body") removed, $(grep -c '^vd_put ' "$PD/fix.body") files added, $(grep -c -e '^mkdir ' -e '^ln -s ' "$PD/fix.body") dirs/links added"
    sh "$PD/fix.sh" 2>>/mnt/base-us/veradroid/logs/install-detail.log || { vlog "parts: image fix-up failed"; return 1; }
    # 和完整目标逐项核对（文件 sha256、权限、属主、链接、目录）
    vd_parts_canon "$T" "$PD/t.final"
    if ! cmp -s "$PD/t.final" "$PD/idx/rootfs.sums"; then
        vlog "parts: rebuilt image differs from the target: $(diff "$PD/idx/rootfs.sums" "$PD/t.final" 2>/dev/null | grep '^[-+][^-+]' | head -6 | tr '\n' ' ')"
        return 1
    fi
    vlog "parts: rebuilt image matches the target ($(wc -l < "$PD/t.final" | tr -d ' ') entries)"
    return 0
}
# 安装器主流程在 Android 停下之后、备份 / 删除任何东西之前调
vd_parts_rootfs() {
    [ "$PARTS_ROOTFS" = tree ] || return 0
    vp_step unpack "增量更新：组装新的系统镜像" "用本机已有的文件；不对就下载整个镜像"
    if vd_parts_tree; then PARTS_TREE=$SYS/rootfs.vdnew; return 0; fi
    if [ -e "$SYS/rootfs.vdnew" ] && ! mounts_under "$SYS/rootfs.vdnew" 1; then rm -rf "$SYS/rootfs.vdnew"; fi
    set -- $PARTS_FULL
    vlog "parts: falling back to the whole image ($2 bytes)"
    vd_parts_get "$3" "$1" "$2" "系统镜像（$(vp_mb "$2") MB）" || DIE_CODE=$(dl_code "$DL_RC") die "系统镜像下载失败（curl $DL_RC）。什么都没有改动"
    PARTS_ROOTFS=full; PARTS_ROOTFS_GZ=$(( $4 / 2 ))
}
# 安装器主流程解开 rootfs 的地方：增量更新时由这里装（返回 0），否则返回 1 走原来的 tar -xzf
vd_parts_rootfs_install() {
    if [ -n "$PARTS_TREE" ]; then
        rmdir "$SYS/rootfs" 2>/dev/null || true
        [ ! -e "$SYS/rootfs" ] && mv "$PARTS_TREE" "$SYS/rootfs" || die "安装系统镜像失败（rootfs）"
        # 安装器下面会原地改写这三个文件：先和旧镜像（回退用的 rootfs.bak）分开 inode
        # build.prop / init.kindle.rc：宿主每次开机原地改（memory.sh 的 heap_fix 往后面追加、slim / props）
        for f in system/priv-app/VeraHome/VeraHome.apk system/app/VeraIME/VeraIME.apk system/lib/libandroid_runtime.so system/build.prop init.kindle.rc; do
            [ -f "$SYS/rootfs/$f" ] && cp -p "$SYS/rootfs/$f" "$SYS/rootfs/$f.vdu" && mv -f "$SYS/rootfs/$f.vdu" "$SYS/rootfs/$f"
        done
        return 0
    fi
    [ "$PARTS_ROOTFS" = full ] || return 1
    set -- $PARTS_FULL
    ui_run in "$PD/$3" "$2" "书、应用和账号都保留" ${LOWPRI:-$UNPACK_PRI} sh -c 'xzcat "$1" | tar -xf - -C "$2"' sh "$PD/$3" "$SYS/rootfs" || { syncer_off; die "解压 Android rootfs 失败（空间不足？）"; }
    return 0
}
# 新镜像就位后、安装器改它之前（全包和增量都调）：安装器 / 宿主会改的几个原版文件留一份（$SYS/vd-pristine/<sha256>，约 4 MB），
# 下次增量更新时本机就有原版，不用再下 vol 部件
vd_parts_keep_pristine() {
    if [ -e "$SYS/rootfs.vdnew" ] && ! mounts_under "$SYS/rootfs.vdnew" 1; then rm -rf "$SYS/rootfs.vdnew" || true; fi
    command -v sha256sum >/dev/null 2>&1 || return 0
    rm -rf "$SYS/vd-pristine.new"; mkdir -p "$SYS/vd-pristine.new" || return 0
    for f in "$SYS"/rootfs/system/priv-app/VeraHome/VeraHome.apk "$SYS"/rootfs/system/priv-app/VeraHome/oat/*/* \
             "$SYS"/rootfs/system/app/VeraIME/VeraIME.apk "$SYS"/rootfs/system/app/VeraIME/oat/*/* \
             "$SYS/rootfs/system/lib/libandroid_runtime.so" "$SYS/rootfs/system/bin/netd" "$SYS/rootfs/system/lib/hw/hwcomposer.kindle.so" \
             "$SYS/rootfs/system/lib/libvdshim.so" \
             "$SYS/rootfs/system/build.prop" "$SYS/rootfs/init.kindle.rc"; do
        [ -f "$f" ] || continue
        s=$(sha256sum "$f" 2>/dev/null | cut -c1-64); [ -n "$s" ] || continue
        cp -p "$f" "$SYS/vd-pristine.new/$s" 2>/dev/null || { rm -rf "$SYS/vd-pristine.new"; return 0; }
    done
    rm -rf "$SYS/vd-pristine"; mv "$SYS/vd-pristine.new" "$SYS/vd-pristine" 2>/dev/null || true
    return 0
}
# END vd_parts

# ---- dalvik-cache 只在系统镜像真的变了时才清（以前每次安装都清：重定位的 boot image 换了校验和，所有应用的 odex 作废，
# 只改了宿主脚本的更新之后也要把所有应用重新编译一遍）。指纹 = system/framework、system/app、system/priv-app 下每个文件的
# 大小 + 修改时间（镜像 tar 里的时间，解压 / 增量组装都保留），slim 挪出去而新镜像里有的应用按原路径算回来；VeraHome / VeraIME 不算
# （安装器自己换，下面单独清它们的缓存）。新旧指纹一样 = boot image 和所有系统 APK / jar 都没变，缓存还能用；
# 不一样（或算不出来）照旧整个清掉——旧缓存里可能有另一个 Settings.apk 的 dex（见下面清缓存处的说明）。
vd_image_list() { # rootfs -> "size mtime path" of system/framework, app, priv-app (VeraHome / VeraIME left out)
    ( cd "$1/system" 2>/dev/null && find framework app priv-app -xdev -type f -exec stat -c '%s %Y %n' {} + 2>/dev/null ) \
        | grep -v -e ' app/VeraIME/' -e ' priv-app/VeraHome/'
}
VD_OLD_IMG=; VD_KEEP_DALVIK=
vd_dalvik_old() { # 换镜像之前（Android 已停）调
    VD_OLD_IMG=; [ -d "$SYS/rootfs/system/framework" ] || return 0
    mkdir -p "$TMP" && vd_image_list "$SYS/rootfs" > "$TMP/img.old" && [ -s "$TMP/img.old" ] && VD_OLD_IMG=$TMP/img.old
    return 0
}
vd_dalvik_decide() { # 新镜像就位后、安装器改它之前调
    VD_KEEP_DALVIK=
    [ "${VD_FACTORY:-0}" != 1 ] && [ -n "$VD_OLD_IMG" ] && [ -d "$SYS/data/dalvik-cache" ] || { vlog "dalvik-cache: wipe (no old image listing)"; return 0; }
    vd_image_list "$SYS/rootfs" | LC_ALL=C sort > "$TMP/img.new"
    # slim 在旧镜像里挪走、新镜像里有的应用：按挪出去的那份算回旧镜像（开机后 slim 还会再挪走它）
    for a in "$SYS"/slim-removed/app/* "$SYS"/slim-removed/priv-app/*; do
        [ -d "$a" ] || continue; r=${a#$SYS/slim-removed/}
        [ -d "$SYS/rootfs/system/$r" ] && ! grep -q " $r/" "$VD_OLD_IMG" || continue
        ( cd "$SYS/slim-removed" && find "$r" -xdev -type f -exec stat -c '%s %Y %n' {} + 2>/dev/null ) >> "$VD_OLD_IMG" || true
    done
    LC_ALL=C sort "$VD_OLD_IMG" > "$TMP/img.old.s"
    if cmp -s "$TMP/img.old.s" "$TMP/img.new"; then VD_KEEP_DALVIK=1; vlog "dalvik-cache: kept (framework and system apps unchanged, $(wc -l < "$TMP/img.new" | tr -d ' ') files)"
    else vlog "dalvik-cache: wipe (system image changed: $(diff "$TMP/img.old.s" "$TMP/img.new" 2>/dev/null | grep -c '^[-+][0-9]') lines differ, e.g. $(diff "$TMP/img.old.s" "$TMP/img.new" 2>/dev/null | grep '^[-+][0-9]' | head -2 | tr '\n' ' '))"; fi
    return 0
}
vd_dalvik_clean() { # 清缓存处调：保留时只删 VeraHome / VeraIME 的（安装器可能换了这两个 APK）
    if [ -n "$VD_KEEP_DALVIK" ]; then
        rm -f "$SYS"/data/dalvik-cache/*/*@VeraHome@* "$SYS"/data/dalvik-cache/*/*@VeraIME@* 2>/dev/null || true
    else
        rm -rf "$SYS/data/dalvik-cache" 2>/dev/null || true
    fi
}

# Existing-install check: do not blindly reinstall a complete, current install.
# Repair if important files are missing; update only when the server has a
# newer bundle.  VD_FACTORY=1 or --force still forces a full install.
apply_patch() {
    [ -n "$LATEST_PATCH_URL" ] && [ -n "$LATEST_PATCH_SHA" ] || return 1
    [ "$LATEST_PATCH_FROM" = "$cur_ver" ] || return 1
    [ "$LATEST_VER" != "$cur_ver" ] || return 1
    show "发现增量补丁 ${cur_ver} -> ${LATEST_VER}（约 ${LATEST_PATCH_SIZE:-?} bytes）"
    vd_phase download
    rm -rf "$TMP/patch" "$TMP/patch.tar.gz"
    mkdir -p "$TMP/patch"
    vp_step dl "增量补丁 ${cur_ver} → ${LATEST_VER}" "$NET_SUM"
    vd_download "$LATEST_PATCH_URL" "$TMP/patch.tar.gz" "${LATEST_PATCH_SIZE:-0}" || return 1
    verify_sha "$TMP/patch.tar.gz" "$LATEST_PATCH_SHA" || return 1
    tar -xzf "$TMP/patch.tar.gz" -C "$TMP/patch" || return 1
    [ -d "$TMP/patch/veradroid-pw6" ] || return 1
    install_wait_idle
    if android_up; then
        install_stop_android >/dev/null 2>&1
        sleep 1
        if [ "$LOOP" = 1 ]; then
            mkdir -p "$SYS"
            grep -q " $SYS " /proc/mounts || loop_mount "$SYSTEM_IMG" "$SYS" || return 1
        fi
    fi
    for d in boot bin apks screensaver fonts kmod; do
        [ -d "$TMP/patch/veradroid-pw6/$d" ] || continue
        mkdir -p "$VD/$d"
        cp -a "$TMP/patch/veradroid-pw6/$d/." "$VD/$d/" 2>/dev/null || true
    done
    if [ -d "$TMP/patch/veradroid-pw6/rootfs" ]; then
        cp -a "$TMP/patch/veradroid-pw6/rootfs/." "$SYS/rootfs/" 2>/dev/null || true
    fi
    for f in VeraDroid-Launch.sh libandroid_runtime.so; do
        [ -f "$TMP/patch/veradroid-pw6/$f" ] || continue
        if [ "$f" = libandroid_runtime.so ]; then
            cp -a "$TMP/patch/veradroid-pw6/$f" "$SYS/rootfs/system/lib/$f" 2>/dev/null || return 1
        else
            cp -a "$TMP/patch/veradroid-pw6/$f" "$VD/$f" 2>/dev/null || true
        fi
    done
    vd_launch_backup
    vd_where_notes
    echo "$LATEST_VER" > "$VERSION_FILE"
    echo "$LATEST_VER" > "$VD/version"
    vd_phase done
    vp_status "state=done" "result=ok" "ver=$LATEST_VER" "label=已更新到 $LATEST_VER"
    show "增量更新完成：$LATEST_VER"
    [ "${VD_NO_START:-0}" = 1 ] || vd_start_android
    return 0
}

install_complete() {
    for f in \
        "$SYS/rootfs/init" \
        "$SYS/rootfs/system/bin/sh" \
        "$SYS/rootfs/system/framework/framework.jar" \
        "$SYS/rootfs/system/priv-app/VeraHome/VeraHome.apk" \
        "$SYS/rootfs/system/lib/libandroid_runtime.so" \
        "$VD/boot/veradroid.sh" \
        "$VD/boot/stop.sh" \
        "$VD/bin/veradroid-epd" \
        "$VD/bin/veradroid-input" \
        "$VD/VeraDroid-Launch.sh"; do
        [ -e "$f" ] || return 1
    done
    # vdlmk is only shipped in the k49 (4.9 kernel) bundle; the PW6 bundle has none and
    # afterboot.sh treats it as optional. Requiring it on PW6 made every run reinstall.
    case "$(uname -r)" in
        4.*) [ -e "$VD/bin/vdlmk" ] || return 1 ;;
    esac
    return 0
}
vercmp() { # $1 $2 -> -1/0/1
    awk -v a="$1" -v b="$2" 'BEGIN{
        n=split(a,x,"."); m=split(b,y,".");
        k=(n>m?n:m);
        for(i=1;i<=k;i++){ if((x[i]+0)>(y[i]+0)){print 1; exit} if((x[i]+0)<(y[i]+0)){print -1; exit} }
        print 0
    }'
}
# ---- 按硬件类别选系统镜像（docs/per-model-images.md）----
# 类别 = 系统镜像真正不同的地方：binder 协议（内核 5.x = 64 位；4.9 / 4.1 / 3.0 = 32 位）、CPU 有没有硬件除法
# （Cortex-A53 / A7 有，A9 没有：按 A53 / A7 编的镜像在 A9 上 SIGILL）、内存档（MemTotal < 400 MB / < 700 MB / 其余，
# 和宿主 memory.sh / props.sh 的分档一样）。只看内核、/proc/cpuinfo 和 /proc/meminfo，不看机型表：机型表认错了也选不错镜像。
# 不认识的组合输出空：用以前的通用镜像（family：pw6 / k49 / legacy）。这一段和 boot/lib/props.sh 的 vd_device_class 保持一致。
vd_device_class() {
    _pr=${VD_TEST_PROC:-/proc}   # tests/device-class-test.sh points this at saved /proc files
    _mt=$(awk '/^MemTotal:/{print $2}' $_pr/meminfo 2>/dev/null); _mt=${_mt:-0}
    _div=0; grep '^Features' $_pr/cpuinfo 2>/dev/null | grep -qw idiva && _div=1
    case "${VD_TEST_UNAME:-$(uname -r)}" in
        5.*) [ $_div = 1 ] && [ "$_mt" -ge 700000 ] && echo pw6 ;;
        4.9.*) [ $_div = 1 ] || return 0
               if [ "$_mt" -lt 700000 ]; then echo k49-512; else echo k49-1g; fi ;;
        3.*|4.1.*)
            if [ $_div = 1 ]; then [ "$_mt" -ge 400000 ] && [ "$_mt" -lt 700000 ] && echo a7-512
            elif [ "$_mt" -lt 400000 ]; then echo a9-256
            elif [ "$_mt" -lt 700000 ]; then echo a9-512; fi ;;
    esac
    return 0
}
# VD_CLASS=family：强制用通用镜像（回退用）。其他值必须和检测结果一样，否则不认（装错类别的镜像轻则慢、重则开不了机）。
DEV_CLASS=$(vd_device_class)
case "${VD_CLASS:-}" in
    ""|"$DEV_CLASS") ;;
    family) DEV_CLASS= ;;
    *) show "VD_CLASS=$VD_CLASS 和这台 Kindle 的类别（${DEV_CLASS:-未知}）不一致，不装"; exit 1 ;;
esac
# 按类别的元数据（static/bootstrap-<类别>.json）；服务器上还没有（404）时用通用的那份
family_meta() {
    case "$(uname -r)" in
        3.*|4.1.*) echo "$BASE/static/bootstrap-legacy.json?t=$(date +%s)" ;;
        4.*) echo "$BASE/static/bootstrap-k49.json?t=$(date +%s)" ;;
        *) echo "$BASE/api/bootstrap" ;;
    esac
}
fetch_meta() { # curl-options...   （vd_meta：直链先，主站后）
    if [ -n "$DEV_CLASS" ] && vd_meta "$BASE/static/bootstrap-$DEV_CLASS.json?t=$(date +%s)" "$@" 2>/dev/null; then return 0; fi
    vd_meta "$(family_meta)" "$@"
}
LATEST_VER=
LATEST_PATCH_URL=
LATEST_PATCH_SHA=
LATEST_PATCH_SIZE=
LATEST_PATCH_FROM=
latest_version() {
    if [ -n "${VD_BUNDLE:-}" ]; then
        LATEST_VER=${VD_VERSION:-}
        printf '%s' "$LATEST_VER"
        return 0
    fi
    j=$(fetch_meta --max-time 12 2>/dev/null) || return 1
    LATEST_VER=$(printf '%s' "$j" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
    LATEST_PATCH_URL=$(printf '%s' "$j" | sed -n 's/.*"patch_url": *"\([^"]*\)".*/\1/p')
    LATEST_PATCH_SHA=$(printf '%s' "$j" | sed -n 's/.*"patch_sha256": *"\([^"]*\)".*/\1/p')
    LATEST_PATCH_SIZE=$(printf '%s' "$j" | sed -n 's/.*"patch_size": *\([0-9]*\).*/\1/p')
    LATEST_PATCH_FROM=$(printf '%s' "$j" | sed -n 's/.*"patch_from": *"\([^"]*\)".*/\1/p')
    printf '%s' "$LATEST_VER"
}
# 上一次安装在“替换文件”阶段被打断（断电、死机、被杀）时，/mnt/base-us/veradroid/state/install.incomplete 还在：
# 程序目录是半新的，rootfs 可能只解压了一半。以前这种状态没人管，点“启动”就是起不来。
# 能回退就回退到安装前的那一版（host.bak / rootfs.bak 还在）；回退不了（loop 机型旧系统已删）就把版本号清掉，让接下来走完整重装。
INCOMPLETE=/mnt/base-us/veradroid/state/install.incomplete
install_recover() {
    [ -f "$INCOMPLETE" ] || return 0
    st=$(cat "$INCOMPLETE" 2>/dev/null); [ -n "$st" ] || { rm -f "$INCOMPLETE"; return 0; }
    if [ -d "$SYS/host.bak.$st" ]; then
        # 程序目录里的 logs / state / backup 不跟着挪；host.bak 若是老安装器整个挪过去的，里面的日志和备份搬回来
        vd_prog_move "$VD" "$SYS/host.failed.$st"
        vd_prog_move "$SYS/host.bak.$st" "$VD"
        vd_data_rescue "$SYS/host.bak.$st"; rm -rf "$SYS/host.bak.$st"
        # 半截安装那段时间里用户放进新程序目录的字体 / 屏保也带回来（host.failed 下次安装会删）
        vd_user_carry "$SYS/host.failed.$st" "$VD" "$VD_USER_DIRS" >/dev/null
        if [ -d "$SYS/rootfs.bak.$st" ]; then
            [ -e "$SYS/rootfs" ] && mv "$SYS/rootfs" "$SYS/rootfs.failed.$st"
            mv "$SYS/rootfs.bak.$st" "$SYS/rootfs"
            # 版本号也回到旧的那一版（$SYS/version 可能已经写成了新版本：以前回退后安装器认为“已是最新”，再也不升级）
            [ -s "$VD/version" ] && cp "$VD/version" "$SYS/version" 2>/dev/null
            echo "[$(date '+%F %T')] interrupted install $st: rolled back to the previous version ($(cat "$VD/version" 2>/dev/null))" >> /mnt/base-us/veradroid/logs/install-detail.log
        else
            rm -f "$VD/version" "$SYS/version"
            echo "[$(date '+%F %T')] interrupted install $st: program dir restored, system image incomplete -> needs reinstall" >> /mnt/base-us/veradroid/logs/install-detail.log
        fi
    else
        rm -f "$VD/version" "$SYS/version" 2>/dev/null
        echo "[$(date '+%F %T')] interrupted install $st: nothing to roll back to -> needs reinstall" >> /mnt/base-us/veradroid/logs/install-detail.log
    fi
    rm -f "$INCOMPLETE"
}
install_recover
[ -n "${VD_BUNDLE:-}" ] || ui_netcheck
cur_ver=
[ -f "$VERSION_FILE" ] && cur_ver=$(cat "$VERSION_FILE" 2>/dev/null || true)
[ -n "$cur_ver" ] || { [ -f "$VD/version" ] && cur_ver=$(cat "$VD/version" 2>/dev/null || true); }
if [ "${VD_FACTORY:-0}" != "1" ]; then
    if install_complete; then
        # Do NOT run latest_version in $(...): command substitution is a
        # subshell, so LATEST_PATCH_* would be lost and apply_patch() would
        # always see an empty patch URL.
        latest_version >/dev/null 2>&1 || true
        latest_ver=$LATEST_VER
        if [ -n "$cur_ver" ] && [ -n "$latest_ver" ]; then
            c=$(vercmp "$cur_ver" "$latest_ver")
            if [ "$c" = "0" ]; then
                vp_status "state=done" "result=latest" "ver=$cur_ver" "label=已是最新版本 $cur_ver"
                show "已安装 VeraDroid $cur_ver，完整性检查通过，无需重装"
                show "直接启动…"
                # 离线包和已装的是同一版：留在 documents 里没用（约 140 MB），和装完一样清掉（只在离线安装时，只删我们的固定文件名）
                remove_offline_package
                remove_installer_later
                [ "${VD_NO_START:-0}" = 1 ] || vd_start_android
                exit 0
            elif [ "$c" -gt 0 ] 2>/dev/null; then
                vp_status "state=done" "result=latest" "ver=$cur_ver" "label=已是最新版本 $cur_ver"
                show "本机 $cur_ver 不低于服务器 $latest_ver，跳过重装"
                remove_installer_later
                [ "${VD_NO_START:-0}" = 1 ] || vd_start_android
                exit 0
            else
                show "检测到 VeraDroid $cur_ver，服务器最新 $latest_ver，开始升级…"
                if apply_patch; then remove_installer_later; exit 0; fi
                # apply_patch 是旧的“补丁包”方式，服务器现在不发（没有 patch_url）：那时什么也不说——后面的增量更新（parts）
                # 才是现在的方式，以前这里总报“增量补丁不可用/失败，回退整包”，紧接着增量更新却成功了，看着像失败
                [ -n "$LATEST_PATCH_URL" ] && show "补丁包没装上，改用下面的方式更新"
            fi
        elif [ -z "$latest_ver" ]; then
            vp_status "state=error" "code=13" "label=暂时查不到服务器上的版本" "line2=现有的 ${cur_ver:-?} 保持不变"
            show "已有完整安装 VeraDroid ${cur_ver:-unknown}，但暂时无法查询服务器；保留现有安装"
            remove_installer_later
            [ "${VD_NO_START:-0}" = 1 ] || vd_start_android
            exit 0
        else
            show "已有安装但版本未知，执行完整重装以确认版本"
        fi
    else
        show "检测到已有安装但不完整，开始修复安装…"
    fi
else
    show "VD_FACTORY=1：强制完整安装"
fi
# Consolidate old install metadata and backups under system/veradroid.
mkdir -p "$SYS/tmp" 2>/dev/null || true
for f in veradroid-device-id veradroid-model veradroid-version veradroid-fullupdate.log veradroid-fullupdate.rc; do   # root-legacy
    if [ -e "/mnt/base-us/$f" ]; then
        mv "/mnt/base-us/$f" "$SYS/${f#veradroid-}" 2>/dev/null || true
    fi
done
for d in /mnt/base-us/veradroid.bak.*; do   # root-legacy
    [ -d "$d" ] || continue
    mv "$d" "$SYS/$(basename "$d")" 2>/dev/null || true
done
offline=0
vd_phase download
if [ -n "${VD_BUNDLE:-}" ] && [ -f "$VD_BUNDLE" ]; then
    offline=1
    ver=${VD_VERSION:-0.21}
    mkdir -p "$TMP"
    size=$(wc -c < "$VD_BUNDLE" | tr -d ' ')
    space_check_before_download "${size:-0}"
    vp_step copy "VeraDroid $ver 离线整包"
    show "使用离线整包 VeraDroid $ver…"
    ui_run out "$TMP/bundle.tar.gz" "${size:-0}" "从 documents 里的离线包读出来，不用联网" cp "$VD_BUNDLE" "$TMP/bundle.tar.gz" || die "无法读取离线安装包"
    sha=
else
    show "查询最新 VeraDroid 安装包…"
    # 先要这台 Kindle 类别的包（bootstrap-<类别>.json），没有就要通用包：4.9 内核机型（PW5/KT5/KT6）要用 32 位 binder 的
    # rootfs，不能用 PW6（5.15）的包；3.0 / 4.1 内核用 legacy 包（32 位系统 + binder 模块）。
    vlog "device class: ${DEV_CLASS:-unknown (family image)}"
    json=$(fetch_meta --connect-timeout 20 --max-time 30) || DIE_CODE=13 die "无法连接更新服务器（查询版本失败）"
    url=$(printf '%s' "$json" | sed -n 's/.*"url": *"\([^"]*\)".*/\1/p')
    sha=$(printf '%s' "$json" | sed -n 's/.*"sha256": *"\([^"]*\)".*/\1/p')
    ver=$(printf '%s' "$json" | sed -n 's/.*"version": *"\([^"]*\)".*/\1/p')
    size=$(printf '%s' "$json" | sed -n 's/.*"size": *\([0-9]*\).*/\1/p')
    [ -n "$url" ] && [ -n "$sha" ] || DIE_CODE=13 die "服务器没有可用的安装包"
    vd_mirrors_from "$json"
    # 增量更新（vd_parts_prepare）：成功就跳过下面的整包下载 / 校验 / 解压；不成功什么都不影响，照常下整包
    # 失败时 PARTS_ROOTFS 等也要清掉：vd_parts_prepare 中途（部件解压失败）已经把它设成了 full / tree，后面解 rootfs 的
    # vd_parts_rootfs_install 只看 PARTS_ROOTFS，会去解已经删掉的 $PD 里的部件，整包下好了却报“解压 Android rootfs 失败（空间不足？）”
    # （模拟器 PW6 0.92 -> 0.94：parts 解压 apks 失败、退回整包后安装失败，代码 21）
    vd_parts_prepare "$json" && PARTS=1 && sha= || { PARTS=; PARTS_ROOTFS=; PARTS_TREE=; PARTS_FULL=; [ -z "$PD" ] || rm -rf "$PD" "$TMP/veradroid-pw6"; }

    [ -n "$PARTS" ] || space_check_before_download "${size:-0}"
    [ -n "$PARTS" ] || vd_sys_room_early
    mkdir -p "$TMP"
    [ -n "$PARTS" ] || vp_step dl "连接服务器…" "$NET_SUM"
    [ -n "$PARTS" ] || show "正在下载 VeraDroid $ver（约 $(( ${size:-136314880} / 1048576 )) MB）…"
    # 断点续传；卡住 / 断了自动从断点重试（vd_download）。下完核对大小，再校验 SHA-256
    [ -n "$PARTS" ] || vd_download "$url" "$TMP/bundle.tar.gz" "${size:-0}" || DIE_CODE=$(dl_code "$DL_RC") die "安装包下载失败（curl $DL_RC）"
    if [ -z "$PARTS" ] && [ -n "$size" ] && [ "$size" -gt 0 ] 2>/dev/null; then
        got=$(wc -c < "$TMP/bundle.tar.gz" | tr -d ' ')
        [ "$got" = "$size" ] || DIE_CODE=14 die "下载不完整（${got}/${size} bytes）"
    fi
fi
if [ -n "$sha" ]; then
    vp_step verify "SHA-256" "核对下载的文件和服务器上的一模一样"
    if ! ui_run in "$TMP/bundle.tar.gz" "${size:-0}" "核对下载的文件和服务器上的一模一样" verify_sha "$TMP/bundle.tar.gz" "$sha"; then
        # 下载损坏（网络传输出错）：自动重下一次
        vlog "sha256 mismatch: downloading again"
        rm -rf "$TMP/bundle.tar.gz" "$TMP/bundle.tar.gz.seg"
        VD_MIRRORS=; rm -f "$TMP/dl.pick"   # 第二次只从主站下（镜像上的文件可能是旧的）
        vp_step dl "文件校验没通过：下载的文件损坏，正在重新下载…" "这是网络传输出错，不是机器的问题"
        vd_download "$url" "$TMP/bundle.tar.gz" "${size:-0}" || DIE_CODE=$(dl_code "$DL_RC") die "安装包下载失败（curl $DL_RC）"
        vp_step verify "SHA-256（第二次）"
        ui_run in "$TMP/bundle.tar.gz" "${size:-0}" "核对下载的文件" verify_sha "$TMP/bundle.tar.gz" "$sha" || DIE_CODE=15 die "SHA-256 校验失败"
    fi
fi
vp_step unpack "把安装包解开（不动书和应用）"
show "正在解压安装包…"
VP_CMIN=3; VP_MIN=8
syncer_on
[ -n "$PARTS" ] || ui_run in "$TMP/bundle.tar.gz" "$(fsize "$TMP/bundle.tar.gz")" "把安装包解开（不动书和应用）" ${LOWPRI:-$UNPACK_PRI} tar -xzf "$TMP/bundle.tar.gz" -C "$TMP" || { syncer_off; die "解压安装包失败"; }
vp_upgrade "$TMP/veradroid-pw6/bin/fbink"     # 整包里的 FBInk：没有能用的 FBInk 时，从这里起换成整屏方块画面
syncer_off
[ -d "$TMP/veradroid-pw6" ] || die "安装包结构不对"
# 包和内核要对得上：4.9 内核（PW5 / KT5 / KT6）的 binder 是 32 位协议，用 PW6 的系统所有原生服务开机即崩
# （用户诊断 / 001122：Binder driver protocol does not match，Android 永远起不来）。在线安装按内核选包；
# 离线包是用户自己挑的，这里拦一下（看包的 manifest.json，见 vd_bundle_k49）。
# 老机型（legacy）包也带 bin/vdlmk，在 4.9 / 5.x 机器（Scribe、PW5、PW6……）上会过下面那条检查，装上 32 位 A9 的老机型系统。
# 只有 legacy 包带 kmod/（我们给 3.0 / 4.1 内核编的 binder 模块）：按内核拦，两个方向都拦。
case "$(uname -r)" in
    3.*|4.1.*) [ -d "$TMP/veradroid-pw6/kmod" ] || die "这个安装包不是老机型用的。这台 Kindle（内核 $(uname -r | cut -d. -f1-2)）请用 VeraDroid-Offline-Legacy.zip / This package is not for older Kindles: use VeraDroid-Offline-Legacy.zip on this Kindle" ;;
    *) [ -d "$TMP/veradroid-pw6/kmod" ] && die "这个离线包是老机型（PW2 / PW3 / Voyage / PW4 / Oasis）用的，这台 Kindle（内核 $(uname -r | cut -d. -f1-2)）不能装，本机未改动。PW6 / Colorsoft 请用 VeraDroid-Offline.zip，PW5 / KT5 / KT6 / Scribe 请用 VeraDroid-Offline-Experimental.zip / This package is for older Kindles (Legacy) and cannot be installed here; nothing was changed. Use VeraDroid-Offline.zip (PW6 / Colorsoft) or VeraDroid-Offline-Experimental.zip (PW5 / KT5 / KT6 / Scribe)" ;;
esac
# 1.00 起 PW6 包也带 bin/vdlmk（memkeep 的 lmkd 门槛），所以先看包自己的 manifest.json（pack.sh 写的 product：veradroid-pw6 /
# veradroid-k49 / veradroid-k49-512 …）；没有 manifest 的很老的包才按 vdlmk 认（那时只有 k49 包带它）。
# BEGIN vd_bundle_k49
vd_bundle_k49() { # <包目录>：0 = k49（4.9 内核）系列的包
    vbp=$(sed -n 's/.*"product": *"\([^"]*\)".*/\1/p' "$1/manifest.json" 2>/dev/null | head -1)
    case "$vbp" in veradroid-k49*|veradroid-a7-512*) return 0 ;; veradroid-pw6*) return 1 ;; esac
    [ -e "$1/bin/vdlmk" ]
}
# END vd_bundle_k49
case "$(uname -r)" in
    4.*) vd_bundle_k49 "$TMP/veradroid-pw6" || die "这个离线包是 PW6 用的。这台 Kindle 请用“其他机型（Experimental）”安装包" ;;
    5.*) vd_bundle_k49 "$TMP/veradroid-pw6" && die "这个离线包是给 PW5 / KT5 / KT6 用的。这台 Kindle 请用 VeraDroid 安装包" ;;
esac
# 按类别编的包（包里有 image-class）只能装到同一类别的机器上：在线安装已经按类别要包，这里拦离线包、
# 服务器配错和检测不一致。通用包（没有 image-class）照旧按上面的内核规则。
if [ -f "$TMP/veradroid-pw6/image-class" ]; then
    ic=$(cat "$TMP/veradroid-pw6/image-class")
    [ "$ic" = "$DEV_CLASS" ] || die "这个安装包的系统是给“$ic”类机型编的，这台 Kindle 是“${DEV_CLASS:-未知}”类，不能装"
    vlog "image class $ic matches the device"
fi

# Stop the running Android first, so rootfs/host files are not in use.
install_wait_idle
if android_up; then
    install_stop_android
    sleep 1
    ui_show       # 桌面里点的更新：Android 停了，Kindle 界面回来以后把整屏进度画上去
    if [ "$LOOP" = 1 ]; then
        mkdir -p "$SYS"
        # stop.sh 会卸载系统盘；挂不回去就停下，否则会把文件写进（可能加密的）裸目录
        grep -q " $SYS " /proc/mounts || loop_mount "$SYSTEM_IMG" "$SYS" || die "无法重新挂载 ext4 系统盘"
    fi
fi

vd_dalvik_old      # 换镜像之前的系统文件清单（vd_dalvik_decide 比较）
[ -n "$PARTS" ] && vd_parts_rootfs      # 增量更新：Android 停了，在动任何东西之前组装新系统镜像（不行就下整个镜像）
mkdir -p "$SYS"
# Migrate a pre-existing device id before moving $VD. Older installers stored
# it under $VD or under data/media; do not discard it on upgrade.
if [ ! -s "$STABLE" ]; then
    for f in "$VD/device-id" "$SYS/data/media/0/.veradroid/device-id"; do
        if [ -s "$f" ]; then cp "$f" "$STABLE"; break; fi
    done
fi
BAK="$SYS/host.bak.$stamp"
# ---- 安装前备份（2026-11-10 改；2026-11-16 有上限，同 bootstrap-legacy.sh 6aa5c04）----
#  - 先估空间，不够就跳过备份（安装本身不删除任何用户文件），写进 veradroid/logs/install-detail.log；
#  - 写到 .partial，成功了才改名；最新一份 + 最多一份“含设备上已经没有的文件”的旧备份；
#  - veradroid-documents-backup.tgz 不再写入，已有的那份原样保留。
# 挑哪些文件（bk_select）：以前把 documents + koreader + extensions + system/thumbnails 整个 tar -czf，几 GB 的书（本来就是压缩
# 格式）在一个核上 gzip 一遍要很久（legacy：Oasis 3 用户 3 GB 卡在备份），备份又占掉同样大的空间。安装本身不删书，装完第一次开机
# boot_data_check 按 veradroid-docs-manifest 逐个核对、少了从这份备份补。现在：
#  - 一定备份：书以外的文件（阅读进度 / 笔记 .sdr、KOReader 和插件的设置、txt 等），每个 20 MB 以下；
#  - 书（azw3 / epub / pdf / mobi / kfx / cbz …，以及图片 / 音频这些已经压缩过的）20 MB 以下的按从小到大放进去，合计不超过
#    BK_BUDGETK（默认 300 MB，VD_BACKUP_MB 可改）；放不下的书和 20 MB 以上的文件不备份，只记在清单里（少了照样报 DOCUMENTS LOSS）；
#  - system/thumbnails 是缓存，不备份。tar -cvf | gzip -1；进度按 tar -v 已处理的文件对累计大小算（已备份 / 总共 MB、还要几分钟）。
# 备份的路径和以前一样（相对 /mnt/us），宿主的恢复（tar -xzkf … -T 缺的文件）不变。
BKL=$TMP/bk.list; BKC=$TMP/bk.cum
BK_BUDGETK=$(( ${VD_BACKUP_MB:-300} * 1024 )); BK_NEEDK=0; BK_SKIPN=0; BK_SKIPK=0
bk_is_book() { case "$1" in
    *.[Aa][Zz][Ww]|*.[Aa][Zz][Ww]3|*.[Aa][Zz][Ww]4|*.[Kk][Ff][Xx]|*.[Kk][Ff][Xx]-[Zz][Ii][Pp]|*.[Mm][Oo][Bb][Ii]|*.[Pp][Rr][Cc]|*.[Pp][Dd][Ff]|\
    *.[Ee][Pp][Uu][Bb]|*.[Cc][Bb][Zz]|*.[Cc][Bb][Rr]|*.[Zz][Ii][Pp]|*.[Rr][Aa][Rr]|*.7[Zz]|*.[Dd][Jj][Vv][Uu]|*.[Kk][Pp][Ff]|*.[Ff][Bb]2.[Zz][Ii][Pp]|\
    *.[Mm][Pp]3|*.[Mm]4[Aa]|*.[Mm]4[Bb]|*.[Aa][Aa][Xx]|*.[Aa][Aa][Cc]|*.[Oo][Gg][Gg]|*.[Jj][Pp][Gg]|*.[Jj][Pp][Ee][Gg]|*.[Pp][Nn][Gg]|*.[Gg][Ii][Ff]|*.[Ww][Ee][Bb][Pp]) return 0 ;;
    esac; return 1; }
bk_select() {
    : > $BKL; : > $BKC; BK_NEEDK=0; BK_SKIPN=0; BK_SKIPK=0
    [ -d /mnt/us ] || return 0
    # 大小(字节) 类别(0 = 非书，1 = 书) 路径；非书在前，书从小到大。一次 stat 一批文件（-exec … +）
    ( cd /mnt/us && for d in documents koreader extensions; do [ -d "$d" ] && find "$d" -type f -exec stat -c '%s %n' {} + 2>/dev/null; done ) | while read -r sz f; do
        case "$f" in ''|*.partial|*.tmp|documents/veradroid-*.tar.gz) continue ;; esac
        is_installer_path "$f" && continue
        c=0; bk_is_book "$f" && c=1
        printf '%s %s %s\n' "$c" "$sz" "$f"      # KB 在 awk 里算（shell 算术遇到怪名字在 set -e 下会让整个安装器退出）
    done | awk '{ k = int(($2 + 1023) / 1024); f = $0; sub(/^[^ ]* [^ ]* /, "", f); print $1, k, f }' | sort -k1,1n -k2,2n > $BKL.all
    set -- $(awk -v budget=$BK_BUDGETK -v cap=20480 -v L=$BKL -v C=$BKC '
        { c = $1; k = $2; f = $0; sub(/^[^ ]* [^ ]* /, "", f)
          if (k >= cap || (c == 1 && tot + k > budget)) { sn++; sk += k; next }
          tot += k; print f > L; print tot > C }
        END { printf "%d %d %d\n", tot, sn, sk }' $BKL.all)
    BK_NEEDK=${1:-0}; BK_SKIPN=${2:-0}; BK_SKIPK=${3:-0}
    rm -f $BKL.all
    vlog "backup selection: $(wc -l < $BKL | tr -d ' ') files ${BK_NEEDK}K (budget ${BK_BUDGETK}K for books); not backed up: $BK_SKIPN files ${BK_SKIPK}K (books over the budget / files >= 20 MB; listed in the documents manifest)"
}
# bk_watch pid verbose-log total-K：tar 跑着时每秒画一次“已备份 / 总共 MB、还要约几分钟”（按已用时间外推；方格 = 百分比）
bk_watch() {
    bw_t0=$(vp_now)
    while kill -0 "$1" 2>/dev/null; do
        n=$(wc -l < "$2" 2>/dev/null | tr -d ' '); dk=0
        [ "${n:-0}" -gt 0 ] 2>/dev/null && dk=$(sed -n "${n}p" $BKC 2>/dev/null)
        dk=${dk:-0}; el=$(( $(vp_now) - bw_t0 )); bp=$(( dk * 100 / ($3 + 1) )); [ "$bp" -gt 99 ] && bp=99
        eta=; [ "$dk" -gt 0 ] && [ "$el" -ge 5 ] && eta=$(( el * ($3 - dk) / dk / 60 + 1 ))
        if [ -n "${VP_ON:-}" ] || [ -n "${UI_AUTO:-}" ]; then
            vp_progress "$bp" "$((dk / 1024)) / $(($3 / 1024)) MB${eta:+ · 还要约 $eta 分钟} · 已用 $(vp_elapsed)" "${VP_L2:-}"
        else
            progress_line "$bp" "Backup $((dk / 1024))/$(($3 / 1024)) MB${eta:+, about $eta min left} / 备份"
        fi
        sleep 1
    done
    return 0
}
USER_SAFE=; BK_RESULT=empty; BK_FELL=
BK_MODE=$(vd_backup_mode)
# 放得下吗（bk_room 要备份的KB 带不带安装的估算）：剩余空间要够备份 + 100 MB；完整备份另外还要给接下来的安装留出下面“空间预检”
# 估的那么多（不用镜像的机型：系统装在同一个分区上）。以前完整备份放不下就整个跳过，放得下又可能把安装要的空间吃掉、到预检才失败。
# 现在完整备份放不下先退到只备份设置和阅读进度，再放不下才跳过。
bk_room() {
    br_free=$(df -k /mnt/base-us | awk 'NR==2{print $4}'); br_inst=0
    if [ "$2" = 1 ] && [ "$LOOP" != 1 ]; then
        br_gz=; [ -f "$TMP/veradroid-pw6/rootfs.tar.gz" ] && br_gz=$(wc -c < "$TMP/veradroid-pw6/rootfs.tar.gz" | tr -d ' ')
        [ -n "$PARTS" ] && br_gz=$PARTS_ROOTFS_GZ
        br_inst=$(( ${br_gz:-150000000} * 3 / 1024 + 204800 ))
    fi
    [ $(( $1 + 102400 + br_inst )) -le "${br_free:-0}" ]
}
if [ "$BK_MODE" = off ]; then
    BK_RESULT=off; : > $BKL
    show "Pre-install backup is off (Settings) / 更新前备份已关闭（设置 → 设备与更新 → 更新前备份）：这次不备份。装完照样核对书库，但出了问题无法恢复"
    vlog "user backup: off (UPDATE_BACKUP=off); the documents manifest is still taken and checked after the first boot, nothing to restore from"
else
    [ "$BK_MODE" = light ] && BK_BUDGETK=0
    bk_select || true
    if [ -s $BKL ] && [ "$BK_MODE" = full ] && ! bk_room "$BK_NEEDK" 1; then
        bk_fullk=$BK_NEEDK; BK_BUDGETK=0; bk_select || true; BK_FELL=1
        vlog "user backup: a full backup (${bk_fullk}K) does not fit next to the install, falling back to settings and reading progress only (${BK_NEEDK}K)"
    fi
fi
if [ -s $BKL ]; then
    need=$BK_NEEDK
    freek=$(df -k /mnt/base-us | awk 'NR==2{print $4}')
    if ! bk_room "$need" 0; then
        BK_RESULT=nospace
        show "Not enough space for the pre-install backup ($((need / 1024)) MB), skipped / 空间不够做安装前备份（需要约 $((need / 1024)) MB），跳过备份；安装不会删除任何书或设置"
        vlog "user backup skipped: need ${need}K + 100M, free ${freek}K"
    else
        part=/mnt/base-us/veradroid/backup/user-backup.partial; bv=$TMP/bk.v
        rm -f "$part" $bv.rc; : > $bv
        bk_l2="设置、阅读进度和 $((${VD_BACKUP_MB:-300})) MB 以内的书；只在本机，装完核对书库"
        [ "$BK_SKIPN" -gt 0 ] && bk_l2="设置、阅读进度和一部分书（另 $BK_SKIPN 个大文件不动、不备份）；装完核对书库"
        bk_l1="备份设置和书（约 $((need / 1024)) MB）"
        if [ "$BK_BUDGETK" = 0 ]; then
            bk_l1="备份设置和阅读进度（约 $((need / 1024)) MB）"; bk_l2="书不备份（设置里选的“只备份设置和阅读进度”）；装完核对书库"
            [ -n "$BK_FELL" ] && bk_l2="空间不够做完整备份，这次书不备份；装完核对书库"
        fi
        [ -n "$BK_FELL" ] && show "Not enough space for a full backup, settings and reading progress only / 空间不够做完整备份，这次只备份设置和阅读进度（约 $((need / 1024)) MB），书不备份"
        vp_step backup "$bk_l1" "$bk_l2"
        vlog "user backup: ${need}K to back up ($(wc -l < $BKL | tr -d ' ') files)"
        t0=$(date +%s)
        # 管道的退出码只看 gzip：tar 的失败另外记下来
        ( { tar -cvf - -C /mnt/us -T $BKL 2>$bv || echo fail > $bv.rc; } | gzip -1 > "$part" ) &
        bkp=$!
        bk_watch $bkp $bv "$need"
        bk_rc=0; wait $bkp || bk_rc=$?
        if [ $bk_rc = 0 ] && [ ! -f $bv.rc ] && gzip -t "$part" 2>/dev/null; then
            USER_SAFE=/mnt/base-us/veradroid/backup/user-backup-$stamp.tgz
            mv "$part" "$USER_SAFE"; BK_RESULT=ok
            vlog "user backup ok: $USER_SAFE ($(wc -c < "$USER_SAFE") bytes, $(( $(date +%s) - t0 )) s)"
            # 最新这份 + 最多再留一份“含设备上已经没有的文件”的旧备份（最近的那份），更早的删掉（BH081-6）。旧备份只解压列一次目录。
            kept_old=
            for old in $(ls -t /mnt/base-us/veradroid/backup/user-backup-*.tgz 2>/dev/null); do
                [ "$old" = "$USER_SAFE" ] && continue
                # 只管安装器自己按时间戳起名的备份；用户自己改过名、拷过来的（如 …-KEEP-….tgz）一律不动
                case "${old##*/}" in user-backup-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].tgz) ;; *) continue ;; esac
                if [ -n "$kept_old" ]; then rm -f "$old"; vlog "pruned $old (already keeping $kept_old as the one older backup)"; continue; fi
                tar -tzf "$old" > $bv.old 2>/dev/null || { vlog "keep $old (cannot read it)"; continue; }
                miss=$(while IFS= read -r f; do
                    case "$f" in (*/|*.partial|*.tmp|system/thumbnails/*|./system/thumbnails/*) continue ;; esac   # 缩略图是缓存，会自己重建
                    is_installer_path "$f" && continue
                    p=${f#./}; [ -e "/mnt/us/$p" ] || [ -L "/mnt/us/$p" ] || { echo "$p"; break; }
                done < $bv.old)
                if [ -z "$miss" ]; then rm -f "$old"; vlog "pruned $old (everything in it is still on the device)"
                else kept_old=$old; vlog "keep $old: has files no longer on the device (e.g. $miss)"; fi
            done
        else
            rm -f "$part"
            BK_RESULT=failed
            show "Pre-install backup failed, installing anyway / 安装前备份失败（见 veradroid/logs/install-detail.log），继续安装；安装不会删除任何书或设置"
            vlog "user backup FAILED ($(tail -c 300 $bv 2>/dev/null | tr '\n' ' '))"
        fi
        rm -f $bv $bv.rc $bv.old
    fi
fi
rm -f $BKL $BKC
# 这次的备份结果（veradroid/state/backup.last；设置里“已备份的数据”看它：核对书库之前这次的备份不能删）和每份备份是哪种（backup-kinds）
bk_kind=$BK_MODE; [ "$BK_BUDGETK" = 0 ] && [ "$BK_MODE" != off ] && bk_kind=light
{ echo "stamp=$stamp"; echo "mode=$BK_MODE"; echo "kind=$bk_kind"; echo "result=$BK_RESULT"
  if [ -n "$USER_SAFE" ]; then echo "file=${USER_SAFE##*/}"; fi
  if [ -n "$BK_FELL" ]; then echo "fallback=light"; fi; } > "$VD_STATE/backup.last.tmp" 2>/dev/null && mv -f "$VD_STATE/backup.last.tmp" "$VD_STATE/backup.last" || true
if [ -n "$USER_SAFE" ]; then
    { tail -n 19 "$VD_STATE/backup-kinds" 2>/dev/null || true; echo "${USER_SAFE##*/} $bk_kind"; } > "$VD_STATE/backup-kinds.tmp" 2>/dev/null && mv -f "$VD_STATE/backup-kinds.tmp" "$VD_STATE/backup-kinds" || true
fi
vlog "user backup: mode=$BK_MODE kind=$bk_kind result=$BK_RESULT${USER_SAFE:+ file=${USER_SAFE##*/}}"
# 安装前的 Kindle 书库清单（2026-11-13 事故后加）：新系统第一次开机完成后 after_boot（boot/lib/afterboot.sh 的 boot_data_check）
# 逐个核对，少了就大声记日志并从上面那份安装前备份补回；核对完改名 .checked。安装器自己启动 Android 时也在这里等它核对。
DOCS_MANIFEST=/mnt/base-us/veradroid/state/docs-manifest
# 离线包自带的 README.txt（和书库给它建的 README.sdr）装完由 remove_offline_package 删掉，也不是用户数据：不进清单，
# 否则新系统第一次开机报 “DOCUMENTS LOSS” 并把它从备份恢复回书库（KT5 模拟器 r093 闸门实测）。只认我们那份（is_offline_readme）。
DOCS_SKIP_README=
if [ -n "${VD_OFFLINE_BUNDLE:-}" ]; then
    dsr_d=$(dirname "$VD_OFFLINE_BUNDLE")
    if is_offline_readme "$dsr_d/README.txt"; then dsr_d=${dsr_d#/mnt/base-us/}; DOCS_SKIP_README=${dsr_d#/mnt/us/}; fi
fi
if [ -d /mnt/base-us/documents ]; then
    ( cd /mnt/base-us && find documents -type f 2>/dev/null ) | while IFS= read -r f; do
        is_installer_path "$f" && continue
        if [ -n "$DOCS_SKIP_README" ]; then case "$f" in "$DOCS_SKIP_README/README.txt"|"$DOCS_SKIP_README/README.sdr/"*) continue ;; esac; fi
        printf '%s\n' "$f"; done \
        | grep -v -e '\.partial$' -e '\.tmp$' -e '^documents/veradroid-.*\.tar\.gz' | sort > "$DOCS_MANIFEST.tmp" 2>/dev/null \
        && mv -f "$DOCS_MANIFEST.tmp" "$DOCS_MANIFEST" && vlog "documents manifest: $(wc -l < "$DOCS_MANIFEST" | tr -d ' ') files"
fi
if [ -f /var/local/cc.db ] && cp -a /var/local/cc.db /mnt/base-us/veradroid/backup/cc.db.$stamp 2>/dev/null; then
    for old in $(ls -t /mnt/base-us/veradroid/backup/cc.db.* 2>/dev/null | tail -n +2); do rm -f "$old"; done
fi
# ---- 空间预检：在动任何东西之前（旧系统要留作回退）----
# 增量更新（parts）时没有 rootfs.tar.gz：不能直接 `wc -c < 文件`——打不开时 sh 自己在屏幕上报 "can't open …rootfs.tar.gz"
# （2>/dev/null 只管 wc，管不到 sh 的重定向），看着像出错了，其实下一行就用 PARTS_ROOTFS_GZ
rootfs_gz=; [ -f "$TMP/veradroid-pw6/rootfs.tar.gz" ] && rootfs_gz=$(wc -c < "$TMP/veradroid-pw6/rootfs.tar.gz" | tr -d ' ')
[ -n "$PARTS" ] && rootfs_gz=$PARTS_ROOTFS_GZ
need_mb=$(( ${rootfs_gz:-150000000} * 3 / 1048576 + 200 ))
if [ "$LOOP" = 1 ]; then free_mb=$(df -m "$SYS" | awk 'NR==2{print $4}')
    old_mb=$(vd_rootfs_mb); free_mb=$(( free_mb + ${old_mb:-0} ))
else free_mb=$(df -m /mnt/base-us | awk 'NR==2{print $4}'); fi
vlog "space: need ${need_mb}M free ${free_mb}M (loop=$LOOP)"
[ "${free_mb:-0}" -ge "$need_mb" ] || die "空间不够：安装需要约 ${need_mb} MB，现在只有 ${free_mb} MB。什么都没有删除"
# 动任何东西（包括把程序目录挪成 host.bak）之前先查挂载：拒绝时设备要保持原样，“VeraDroid 启动”还能用
guard_rm "$SYS/rootfs"
# 1.00 / 0.8.11 及以前的整包安装不带 fonts/ 回来：用户的字体还在最近一份 host.bak 里（loop 机型开机后就删了，那就找不回来）。
# 删旧备份之前，一次性（state/user-carry.v1）把那里的字体 / 屏保放回程序目录，下面整包安装再带进新程序目录。
if [ ! -f "$VD_STATE/user-carry.v1" ]; then
    uc_old=$(ls -dt "$SYS"/host.bak.* 2>/dev/null | head -n 1) || uc_old=
    if [ -n "$uc_old" ] && [ -d "$uc_old" ] && [ -d "$VD" ]; then vlog "user files rescued from $uc_old: $(vd_user_carry "$uc_old" "$VD" "fonts screensaver")"; fi
    mkdir -p "$VD_STATE" 2>/dev/null && : > "$VD_STATE/user-carry.v1" 2>/dev/null || true
fi
# 只保留最近一份旧系统 / 程序目录备份（不是用户数据）。删之前查挂载。
for pat in rootfs.bak host.bak rootfs.failed host.failed; do
    for old in $(ls -dt "$SYS"/$pat.* 2>/dev/null); do
        guard_rm "$old"
        vd_data_rescue "$old"     # 老安装器整个挪过去的程序目录：里面的日志和安装前备份先搬回 veradroid/
        rm -rf "$old" 2>/dev/null || true
    done
done
echo "$stamp" > "$INCOMPLETE"; sync      # 从这里到写完版本号之间被打断，下次安装 / 启动会按这个标记回退
vd_prog_move "$VD" "$BAK"      # 程序挪成备份；veradroid/logs、state（安装锁、本次的临时目录、标记）、backup 留在原处
if [ "$LOOP" = 1 ]; then
    # loop 镜像只有 3 GB，放不下新旧两份 rootfs：旧的不是用户数据，先删
    guard_rm "$SYS/rootfs"
    rm -rf "$SYS/rootfs" 2>/dev/null || true
elif [ -d "$SYS/rootfs" ]; then
    guard_rm "$SYS/rootfs"
    if [ "${free_mb:-0}" -ge $(( need_mb + 400 )) ]; then mv "$SYS/rootfs" "$SYS/rootfs.bak.$stamp"
    else rm -rf "$SYS/rootfs" 2>/dev/null || true; vlog "old rootfs removed instead of kept (space)"; fi
fi
mkdir -p "$VD" "$SYS/rootfs"
echo "$MODEL" > "$MODEL_FILE"
echo "$MODEL" > "$VD/model" 2>/dev/null || true   # 系统盘外的一份（loop 机型停着时 auto-update 也能读到）
# 装的是按类别编的系统时记下类别（系统盘外，auto-update 用它要同一类别的包）；通用包就删掉，回到通用元数据
if [ -f "$TMP/veradroid-pw6/image-class" ]; then cp "$TMP/veradroid-pw6/image-class" "$VD/image-class" 2>/dev/null || true
else rm -f "$VD/image-class" 2>/dev/null || true; fi
cp -a "$TMP/veradroid-pw6/boot" "$TMP/veradroid-pw6/bin" "$VD/"
[ -d "$TMP/veradroid-pw6/models" ] && { rm -rf "$VD/models"; cp -a "$TMP/veradroid-pw6/models" "$VD/models"; }
[ -d "$TMP/veradroid-pw6/kmod" ] && { rm -rf "$VD/kmod"; cp -a "$TMP/veradroid-pw6/kmod" "$VD/kmod"; }
[ -f "$TMP/veradroid-pw6/VeraDroid-Launch.sh" ] && cp "$TMP/veradroid-pw6/VeraDroid-Launch.sh" "$VD/VeraDroid-Launch.sh" && chmod 755 "$VD/VeraDroid-Launch.sh"
# 程序目录里这一份只是备份（书库入口被删时 framework.sh 的 launcher_restore 用它补回去）。标题和书库里的正式入口分开：
# 有用户把整个 veradroid 文件夹拷进了 documents，书库里于是出现两个一模一样的“VeraDroid”（用户报告：KT6 5.17）。
# 标题直接说它是什么、怎么用（2026-11-19：KO3 用户书库入口没了，不知道 veradroid/ 里这个就是备用的）。
vd_launch_backup
# 卸载入口不放进书库（和启动入口挨着，容易点错）：放在程序目录 veradroid/VeraDroid-Uninstall.sh（USB 能看到）。
# 平时在 Android 的 设置 → 设备与更新 → 卸载 VeraDroid 卸载；Android 起不来时用户用 USB 把它拷进 documents 再点（点两次）。
if [ -f "$TMP/veradroid-pw6/entries/VeraDroid-Uninstall.sh" ]; then
    cp "$TMP/veradroid-pw6/entries/VeraDroid-Uninstall.sh" "$VD/VeraDroid-Uninstall.sh" 2>/dev/null && chmod 755 "$VD/VeraDroid-Uninstall.sh" 2>/dev/null || true
fi
[ -d "$TMP/veradroid-pw6/fonts" ] && cp -a "$TMP/veradroid-pw6/fonts" "$VD/fonts"
# 老内核用的外挂模块（kmod/<uname -r>/*.ko，boot/lib/kmod.sh 开机时加载）
[ -d "$TMP/veradroid-pw6/kmod" ] && cp -a "$TMP/veradroid-pw6/kmod" "$VD/kmod"
[ -d "$TMP/veradroid-pw6/screensaver" ] && cp -a "$TMP/veradroid-pw6/screensaver" "$VD/screensaver"
# Invoked via "sh" everywhere, but the launcher and KUAL also probe it.
chmod 755 "$VD/boot/veradroid.sh" 2>/dev/null || true
# Keep the debug SSH keys/authorized_keys across the upgrade.  The old host dir is
# moved to $BAK above; dropbear's -D path points into it.
# Never fatal: on the rc089 matrix (pw6-5201) the test saw the directory and cp then could not stat it, and under
# set -e that one optional copy failed the whole upgrade (rc=1, half-installed until the next start rolled it back).
if [ -d "$BAK/ssh" ]; then cp -a "$BAK/ssh" "$VD/ssh" 2>/dev/null || vlog "ssh keys not carried over from $BAK/ssh"; fi
# BH081-2：程序目录里还有用户自己放的设置 / 文件，整包安装把 $VD 挪成 $BAK 后要带回来，否则每次升级都丢：
#   autoupdate（=0 关闭自动更新）、network.conf（代理 / DNS，受限网络下没有它就更新不了）、debugboot、
#   screensaver/ 里的自定义屏保、apps/ 里还没装的 APK（afterboot 装好才删）、kindle-crash/ 里从书库挪出来的 Kindle 崩溃转储
#   （framework.sh 的 fw_tidy_crash；以前升级后留在 host.bak 里，书库核对报“少了文件”，legacy 同样修过）。包里有的同名文件以包为准。
for f in autoupdate network.conf debugboot; do
    [ -f "$BAK/$f" ] && [ ! -e "$VD/$f" ] && cp -a "$BAK/$f" "$VD/$f" 2>/dev/null || true
done
mkdir -p "$VD/apps"
cp "$TMP/veradroid-pw6/apks/"*.apk "$VD/apps/"
# fonts/ screensaver/ apps/ kindle-crash/ 里用户的文件（含子文件夹）带进新程序目录：以前只带屏保 / apps / kindle-crash 顶层的文件，
# fonts/ 整个留在 host.bak 里，下次安装就删了（vd_user_carry）。卸载时留下的 backup/user-files 也放回来。然后记下这个包自带哪些文件。
vlog "user files carried from $BAK: $(vd_user_carry "$BAK" "$VD" "$VD_USER_DIRS")"
vlog "user files restored from backup/user-files: $(vd_user_restore "$VD")"
vd_bundled_save "$TMP/veradroid-pw6"
# USB 打开 Kindle 盘时看到的说明：veradroid/说明.txt、veradroid/screensaver/把屏保图片放这里.txt（vd_where_notes）
vd_where_notes
# Stable identity lives outside $VD because $VD is moved to .bak on every
# install. It also survives a factory reset of /data.
if [ ! -s "$STABLE" ]; then
    usid=$(tr -cd '0-9A-Fa-f' </proc/usid 2>/dev/null | cut -c1-16)
    if [ -z "$usid" ]; then
        usid=$(tr -d '-' </proc/sys/kernel/random/uuid 2>/dev/null | cut -c1-16)
    fi
    [ -n "$usid" ] && echo "$usid" > "$STABLE"
fi
if [ -s "$STABLE" ]; then
    cp "$STABLE" "$VD/device-id"
    # Android cannot see /mnt/base-us; expose the same id through /data/media,
    # which factory mode deliberately preserves.
    mkdir -p "$SYS/data/media/0/.veradroid" 2>/dev/null || true
    cp "$STABLE" "$SYS/data/media/0/.veradroid/device-id" 2>/dev/null || true
    chmod 644 "$STABLE" "$VD/device-id" "$SYS/data/media/0/.veradroid/device-id" 2>/dev/null || true
fi
vp_step install "Android 系统（约 1–3 分钟）" "书、应用和账号都保留"
show "正在安装 Android 系统（约 1–3 分钟）…"
syncer_on
vd_parts_rootfs_install || ui_run in "$TMP/veradroid-pw6/rootfs.tar.gz" "${rootfs_gz:-0}" "书、应用和账号都保留" ${LOWPRI:-$UNPACK_PRI} tar -xzf "$TMP/veradroid-pw6/rootfs.tar.gz" -C "$SYS/rootfs" || { syncer_off; die "解压 Android rootfs 失败（空间不足？）"; }
syncer_off
[ -x "$SYS/rootfs/system/bin/sh" ] || die "Android rootfs 不完整（缺少 /system/bin/sh）"
vd_parts_keep_pristine
vd_dalvik_decide
# Patched Android runtime for 5.17-era kernels: skip unknown zygote fds
# (ashmem/binder) in FileDescriptorTable instead of aborting.
if [ -f "$TMP/veradroid-pw6/libandroid_runtime.so" ]; then
    cp "$TMP/veradroid-pw6/libandroid_runtime.so" "$SYS/rootfs/system/lib/libandroid_runtime.so" || die "写入 libandroid_runtime 失败"
    chmod 755 "$SYS/rootfs/system/lib/libandroid_runtime.so" 2>/dev/null || true
fi
# The bundle's VeraHome must replace the copy inside the rootfs. after_boot
# only skips "already a system app", so without this the UI stays on the old
# version even after a new bundle is installed.
# First boot may happen before after_boot; create the reader download dirs here
# so WeRead does not sit on "downloading" with an unwritable Android/data.
for d in Android Android/data Android/obb WeRead Download; do
    mkdir -p "$SYS/rootfs/data/media/0/$d" 2>/dev/null || true
    chmod 777 "$SYS/rootfs/data/media/0/$d" 2>/dev/null || true
done
# 0.46–0.70 的 VeraHome 是 OTA 装进 /data/app 的更新版（versionCode 47–54），会盖住包里的系统版。
# 往前发（roll-forward）要换回包里的 VeraHome：删掉更新版的 apk 目录（它的设置在 /data/media/0/.vdhome，不受影响）。
for vh in "$SYS"/data/app/com.veradroid.home-*; do
    [ -d "$vh" ] || continue
    guard_rm "$vh"; rm -rf "$vh" 2>/dev/null && vlog "removed updated VeraHome $vh"
done
# 输入法同理：系统镜像里带的是很早的 VeraIME 0.2（没有“长按 123 出符号键盘”），after_boot 看到“已安装”就跳过，
# 包里新的输入法永远装不上（更新日志写了有、用户长按 123 还是数字键盘）。安装时直接换掉系统里的那份。
if [ -f "$TMP/veradroid-pw6/apks/VeraIME.apk" ]; then
    for vi in "$SYS"/data/app/com.veradroid.ime-*; do
        [ -d "$vi" ] || continue
        guard_rm "$vi"; rm -rf "$vi" 2>/dev/null && vlog "removed updated VeraIME $vi"
    done
    VI="$SYS/rootfs/system/app/VeraIME"
    mkdir -p "$VI"
    # 包里的就是系统镜像里那一份（同一次编译，镜像里带预编译的 odex）：不用换，odex 留着，第一次开机就不用再编译它
    if cmp -s "$TMP/veradroid-pw6/apks/VeraIME.apk" "$VI/VeraIME.apk" && [ -d "$VI/oat" ]; then vlog "VeraIME: image copy is the bundle's, odex kept"
    else
        cp "$TMP/veradroid-pw6/apks/VeraIME.apk" "$VI/VeraIME.apk" || die "写入 VeraIME 失败"
        chmod 644 "$VI/VeraIME.apk" 2>/dev/null || true
        rm -rf "$VI/oat" 2>/dev/null || true
    fi
fi
if [ -f "$TMP/veradroid-pw6/apks/VeraHome.apk" ]; then
    VH="$SYS/rootfs/system/priv-app/VeraHome"
    mkdir -p "$VH"
    if cmp -s "$TMP/veradroid-pw6/apks/VeraHome.apk" "$VH/VeraHome.apk" && [ -d "$VH/oat" ]; then vlog "VeraHome: image copy is the bundle's, odex kept"
    else
        cp "$TMP/veradroid-pw6/apks/VeraHome.apk" "$VH/VeraHome.apk" || die "写入 VeraHome 失败"
        chmod 644 "$VH/VeraHome.apk" 2>/dev/null || true
        rm -rf "$VH/oat" 2>/dev/null || true
    fi
fi
# Keep existing data; a fresh machine gets the directories init needs.
# Factory reset mode: keep /data/media (Books, fonts, token), discard all Android
# app/system state. This is the reliable path for a machine that has been through
# several failed installs.
if [ "${VD_FACTORY:-0}" = "1" ]; then
    echo "全新安装：清理 Android 数据，保留 media..."
    for p in data/data data/system data/app data/dalvik-cache data/resource-cache              data/misc data/property data/user data/user_de data/local/tmp; do
        guard_rm "$SYS/$p"
        rm -rf "$SYS/$p" 2>/dev/null || true
    done
fi
mkdir -p "$SYS/data/data" "$SYS/data/system" "$SYS/data/local/tmp" "$SYS/data/media/0"
# Android's system_server must own /data, /data/system and /data/data.
# Fresh installs previously left them root:root, so Settings could not write its user state.
chown 1000:1000 "$SYS/data" "$SYS/data/system" "$SYS/data/data" 2>/dev/null || true
chmod 0771 "$SYS/data" "$SYS/data/data" 2>/dev/null || true
chmod 0770 "$SYS/data/system" 2>/dev/null || true
chown 2000:2000 "$SYS/data/local/tmp" 2>/dev/null || true
chmod 0771 "$SYS/data/local/tmp" 2>/dev/null || true
chown 1023:1023 "$SYS/data/media" 2>/dev/null || true
chmod 0770 "$SYS/data/media" 2>/dev/null || true
chmod 0777 "$SYS/data/media/0" 2>/dev/null || true
# A stale MediaProvider database from a previous Android image can crash on first boot.
# This only clears the media index; books and personal files are untouched.
rm -rf "$SYS/data/data/com.android.providers.media/databases" 2>/dev/null || true
rm -rf "$SYS/data/data/com.android.providers.media/files" 2>/dev/null || true
# System apps can also have stale settings databases after switching Android images.
# These are settings only; books, apps and account data are not touched.
for pkg in com.android.settings com.android.systemui com.android.providers.media; do
    rm -rf "$SYS/data/data/$pkg" 2>/dev/null || true
done
# SettingsProvider owns hide_error_dialogs and other global switches. Keep it
# across normal upgrades; deleting it makes the media crash dialog return.

# Settings is intentionally excluded from the product (packages/apps/Android.mk).
# An old Settings.apk left in the rootfs provides FallbackHome/CryptKeeper and steals
# the HOME intent on a fresh user. Remove it completely; SettingsProvider stays.
rm -rf "$SYS/rootfs/system/priv-app/Settings" 2>/dev/null || true
rm -rf "$SYS/rootfs/system/app/Settings" 2>/dev/null || true
# A stale dalvik-cache can contain a dex for a different Settings.apk. ART then
# ignores the matching system odex and ClassNotFound for CryptKeeper. Clearing it
# only forces recompilation; apps and books are untouched.
# Only when the image changed (vd_dalvik_decide): otherwise every update recompiled every app.
vd_dalvik_clean
rm -rf "$SYS/data/resource-cache" 2>/dev/null || true
# Stale user/settings state from a previous Android image can crash Settings even
# after app data is cleared. Books, /data/media, and the device token are preserved.
# Never delete a valid user 0: that makes FallbackHome launch Settings.CryptKeeper,
# whose class is not present in this stripped Settings APK. If the user directory is
# absent, let system_server recreate it from scratch instead of keeping half state.
if [ ! -d "$SYS/data/system/users/0" ]; then
    rm -rf "$SYS/data/system/users" 2>/dev/null || true
fi
# android_id (Settings.Secure.ANDROID_ID) of a seeded user 0. The seed in the bundle (user0/settings_secure.xml) carries
# one fixed value, so every install had the same id: a privacy leak, and apps that bind accounts / licences to it
# treated all VeraDroid Kindles as one device. A user 0 seeded here gets a fresh random 64-bit id, once: this only runs
# when users/0.xml does not exist yet (new install, uninstall + install, factory reset), so an upgrade keeps the id the
# installed system already has (changing it would sign users out of apps). If no random source works the line is
# dropped and SettingsProvider generates one itself on first boot (Android 7.1 does that for a missing android_id).
# BEGIN vd_seed_android_id
vd_seed_android_id() {   # $1 = settings_secure.xml of the freshly seeded user 0
    [ -f "$1" ] || return 0
    _aid=$(od -An -tx1 -N8 /dev/urandom 2>/dev/null | tr -d ' \n')
    [ ${#_aid} = 16 ] || _aid=$(hexdump -n 8 -e '8/1 "%02x"' /dev/urandom 2>/dev/null)
    case "$_aid" in ????????????????) ;; *) _aid= ;; esac
    case "$_aid" in *[!0-9a-f]*|0000000000000000) _aid= ;; esac
    if [ -n "$_aid" ]; then
        sed "s/\(name=\"android_id\" value=\"\)[0-9a-fA-F]*\"/\1$_aid\"/" "$1" > "$1.aid"
    else
        sed '/name="android_id"/d' "$1" > "$1.aid"
    fi
    [ -s "$1.aid" ] && cat "$1.aid" > "$1"   # cat, not mv: keeps the seed file's mode
    rm -f "$1.aid"
}
# END vd_seed_android_id
if [ "${VD_FACTORY:-0}" != "1" ] && [ ! -f "$SYS/data/system/users/0.xml" ]; then
    mkdir -p "$SYS/data/system/users"
    cp -a "$TMP/veradroid-pw6/user0.xml" "$SYS/data/system/users/0.xml" 2>/dev/null || true
    cp -a "$TMP/veradroid-pw6/user0/." "$SYS/data/system/users/0/" 2>/dev/null || true
    vd_seed_android_id "$SYS/data/system/users/0/settings_secure.xml"
    chown -R 1000:1000 "$SYS/data/system/users" 2>/dev/null || true
fi
# Only a factory install may discard PackageManager state. A normal KPM/OTA
# upgrade must keep installed apps and their package records.
if [ "${VD_FACTORY:-0}" = "1" ]; then
    rm -f "$SYS/data/system/packages.xml" "$SYS/data/system/packages.list" 2>/dev/null || true
fi

[ -n "$ver" ] && echo "$ver" > "$VERSION_FILE"
[ -n "$ver" ] && echo "$ver" > "$VD/version"
sync; rm -f "$INCOMPLETE"
vd_phase done      # 文件都换好了：从这里起 veradroid.sh（包括下面自己启动的那次）不用再等
# Native Kindle library entry: a .sh with # Name/# Icon is indexed as a book.
DOCS=/mnt/base-us/documents
[ -d "$DOCS" ] || DOCS=/mnt/us/documents
# Restore only missing documents from the pre-install backup.
# BH081-4：旧版留下的 veradroid-documents-backup.tgz 只恢复一次。以前每次安装 / 自动更新都从这份旧备份
# “补回缺失的书”，用户后来自己删掉的书每次更新都会复活。恢复过一次就改名保留（不删除，用户还能手动找回）。
if [ -f /mnt/base-us/veradroid/backup/documents-backup.tgz ]; then
    restore_missing /mnt/base-us/veradroid/backup/documents-backup.tgz "$DOCS"
    mv /mnt/base-us/veradroid/backup/documents-backup.tgz /mnt/base-us/veradroid/backup/documents-backup.restored-$stamp.tgz 2>/dev/null || true
    vlog "legacy documents backup restored once, renamed to documents-backup.restored-$stamp.tgz"
fi
restore_missing "$USER_SAFE" /mnt/us
# 0.96 及以前的数据清空事故（PackageManager 删用户 0 时穿过 KindleLibrary 的 bind 清空 documents）会把别人的书库入口一起删掉：
# KUAL 的 *.kual、KOReader 等的 *.sh 都放在 documents 里，程序本体（/mnt/us/koreader、/mnt/us/extensions）不在那里，没事。
# 这次的备份是事故之后才做的，里面也没有这些入口；事故前那次安装的备份被“有已删除文件的旧备份”规则留着。
# 每台设备只做一次：从所有安装前备份里，只把 documents 顶层、现在不在的第三方入口（*.kual / *.sh，不含我们自己的）放回去。
LR_MARK=/mnt/base-us/veradroid/state/launchers-restored
if [ ! -f "$LR_MARK" ]; then
    for b in $(ls -t /mnt/base-us/veradroid/backup/user-backup-*.tgz /mnt/base-us/veradroid/backup/documents-backup*.tgz 2>/dev/null) $(ls -t /mnt/base-us/veradroid-user-backup-*.tgz /mnt/base-us/veradroid-documents-backup*.tgz 2>/dev/null); do   # root-legacy: 用户改过名留在根目录的备份
        [ "$b" = "$USER_SAFE" ] && continue
        lr="$TMP/launchers.list"; : > "$lr" 2>/dev/null || break
        tar -tzf "$b" 2>/dev/null | grep -E '^(\./)?documents/[^/]+\.(kual|sh)$' | while IFS= read -r f; do
            p=${f#./}
            is_installer_path "$p" && continue
            case "${p#documents/}" in VeraDroid*) continue ;; esac
            [ -e "/mnt/us/$p" ] || [ -L "/mnt/us/$p" ] || printf '%s\n' "$f" >> "$lr"
        done
        if [ -s "$lr" ]; then
            tar -xzkf "$b" -C /mnt/us -T "$lr" 2>/dev/null || true
            vlog "launchers restored from $b: $(tr '\n' ' ' < "$lr")"
            while IFS= read -r f; do lipc-set-prop com.lab126.scanner reScanFile "/mnt/us/${f#./}" 2>/dev/null || true; done < "$lr"
        fi
    done
    date > "$LR_MARK" 2>/dev/null || true
fi
if [ -f /mnt/base-us/veradroid/backup/cc.db.$stamp ] && [ ! -f /var/local/cc.db ]; then
    cp -a /mnt/base-us/veradroid/backup/cc.db.$stamp /var/local/cc.db 2>/dev/null || true
fi
# 卸载入口只在用户为了卸载自己拷进 documents 时才会在书库里：重新安装 / 更新以后它没用了（还会排在启动入口前面），拿掉。
# BEGIN vd_retire_uninstall_entry
# 只认我们自己放的那一个：固定文件名 documents/VeraDroid-Uninstall.sh（历来只用过这一个名字），普通文件（不是链接），
# 头里是我们的标题，内容指向我们的程序目录。用户别的文件、同名但内容不是我们的文件一律不动。
vd_retire_uninstall_entry() {
    for d in /mnt/us/documents /mnt/base-us/documents; do
        f=$d/VeraDroid-Uninstall.sh
        [ -f "$f" ] && [ ! -L "$f" ] || continue
        grep -q '^# Name: VeraDroid 卸载$' "$f" 2>/dev/null && grep -q '^VD=/mnt/base-us/veradroid$' "$f" 2>/dev/null || continue
        rm -f "$f" 2>/dev/null || continue
        for s in "$d/VeraDroid-Uninstall.sh.sdr" "$d/VeraDroid-Uninstall.sdr"; do [ -d "$s" ] && [ ! -L "$s" ] && rm -rf "$s" 2>/dev/null; done
        command -v vlog >/dev/null 2>&1 && vlog "library: removed the old uninstall entry $f"
        RETIRED=1
    done
    return 0
}
# END vd_retire_uninstall_entry
RETIRED=
vd_retire_uninstall_entry
[ -n "$RETIRED" ] && lipc-set-prop com.lab126.scanner doFullScan 1 2>/dev/null || true
if [ -f "$TMP/veradroid-pw6/VeraDroid-Launch.sh" ]; then
    # Kindle's home library may use either mount; put the launcher in both so
    # returning to Kindle always finds a "VeraDroid 启动" entry.
    # 书库里显示的标题就是文字说明里一直写的“VeraDroid 启动”（以前是“VeraDroid”，和说明对不上）
    for D in "$DOCS" /mnt/us/documents /mnt/base-us/documents; do
        [ -d "$D" ] || continue
        sed 's/^# Name: .*/# Name: VeraDroid 启动/' "$TMP/veradroid-pw6/VeraDroid-Launch.sh" > "$D/VeraDroid 启动.sh" 2>/dev/null || true
        chmod 755 "$D/VeraDroid 启动.sh" 2>/dev/null || true
        # force a fresh library scan without touching user books
        touch "$D/VeraDroid 启动.sh" 2>/dev/null || true
    done
    sync 2>/dev/null || true
    # 旧包里的启动入口没有忽略 SIGPIPE：书库启动器的 fbink 管道断开时会一点就退出。在文件头注释后补一行。
    if ! grep -q "trap '' PIPE" "$DOCS/VeraDroid 启动.sh" 2>/dev/null; then
        awk 'f==0 && !/^#/ {print "trap '\'''\'' PIPE"; f=1} {print}' "$DOCS/VeraDroid 启动.sh" > "$DOCS/.vd-launch.tmp" 2>/dev/null \
            && mv "$DOCS/.vd-launch.tmp" "$DOCS/VeraDroid 启动.sh"
    fi
    chmod 755 "$DOCS/VeraDroid 启动.sh" 2>/dev/null || true
    # 马上就要启动 Android（会停掉 Kindle 的索引服务 scanner）：趁它还在，点名让它现在就索引这个入口，
    # 否则入口文件在、书库里却看不到（“安装后书库入口不见”）。
    lipc-set-prop com.lab126.scanner reScanFile "/mnt/us/documents/VeraDroid 启动.sh" 2>/dev/null || true
    sleep 2
fi
# KUAL is an alternative for jailbreaks that do not index .sh books.
if [ -d "$TMP/veradroid-pw6/kual" ]; then
    mkdir -p /mnt/us/extensions/VeraDroid 2>/dev/null || true
    cp -a "$TMP/veradroid-pw6/kual/." /mnt/us/extensions/VeraDroid/ 2>/dev/null || true
    chmod 755 /mnt/us/extensions/VeraDroid/start.sh /mnt/us/extensions/VeraDroid/install.sh /mnt/us/extensions/VeraDroid/restore-entry.sh 2>/dev/null || true
fi
# BH081-1：自动更新（VD_NO_START=1）时用户正在用 Kindle（Android 冻结或没开）：装完不要把 Android 弹到前台。
vlog "progress: $VP_REF screen refreshes during the install (mode $VP_MODE)"
vp_status "state=done" "result=ok" "ver=${ver:-}" "label=已更新到 ${ver:-}"
if [ "${VD_NO_START:-0}" = 1 ]; then
    vp_progress 100 "完成：VeraDroid ${ver:-}" "下次点“VeraDroid 启动”时生效" force
    show "更新完成：下次点“VeraDroid 启动”时生效"
else
    vp_step boot "VeraDroid ${ver:-}" "第一次开机要优化应用，可能要几分钟；接下来是开机画面"
    show "正在启动 VeraDroid…"
    rm -f /tmp/vd-progress.on
    vd_start_android
fi
# A 3G loop image cannot hold every historical backup. Keep only the newest
# host/rootfs backup on loop-backed installs; clean up after the new boot starts.
if [ "$LOOP" = 1 ]; then
    for pat in rootfs.bak.* host.bak.*; do
        for old in $(ls -dt "$SYS"/$pat 2>/dev/null | tail -n +2); do
            # Android 已经启动：下面有挂载就跳过（不 die，安装已经完成），绝不穿过挂载删
            if mounts_under "$old" 1; then vlog "skip rm -rf $old: mounts under it"; continue; fi
            vd_data_rescue "$old"
            rm -rf "$old" 2>/dev/null || true
        done
    done
fi
# The native library should show one VeraDroid launcher, not installer + launcher.
# Delete only the known installer names, never "VeraDroid 启动.sh".
remove_installer_later
remove_offline_package
echo
# 只列真的存在的备份：全新安装（或空间不够、旧系统被删掉）时没有备份，以前也照样打印两个不存在的路径
bak_list=; for b in "$BAK" "$SYS/rootfs.bak.$stamp"; do [ -e "$b" ] && bak_list="$bak_list $b"; done
if [ -n "$bak_list" ]; then
    echo "完成。旧版本已备份到："
    for b in $bak_list; do echo "  $b"; done
    echo "如果启动失败，可以用 SSH 执行 stop.sh，再把备份移回来。"
else
    echo "完成。"
fi
