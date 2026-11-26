#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 卸载 VeraDroid（cmd.sh 的 uninstall 先把本脚本拷到 /dev/shm 再运行，因为它会删掉 $VD）。
# 保留：Android 里的书 / 微信读书下载 / 账号 token（data/media）、Kindle 自己的一切。
# 2026-11-10 修正（eink-emulator 复现）：旧版先跑 stop.sh（会卸载 loop 系统盘），再去 mv data/media，
# 这时已经找不到了，接着删掉 veradroid-sys.img —— PW5 / KT5 / KT6 上 Android 里的书全没了。
{
VD=/mnt/base-us/veradroid
. $VD/boot/lib/common.sh
ULOG=/mnt/base-us/veradroid/logs/uninstall.log
mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null
# VD_PURGE=1（书库入口“VeraDroid 卸载”，只给只在模拟器上验证过的机型建）：不留任何痕迹——Android 里的数据也删，
# 安装器的日志 / 清单 / 备份也删（备份只在核对过 Kindle 书库一个文件都没少之后才删）。日志写 /tmp（重启就没了）。
PURGE=${VD_PURGE:-0}
[ "$PURGE" = 1 ] && ULOG=/tmp/veradroid-uninstall.log
exec >>$ULOG 2>&1
echo "== $(date) uninstall"
UN_FB=; UN_FB_TMP=
for _c in "$FBINK" "$(command -v fbink 2>/dev/null)" /var/local/kmc/bin/fbink /mnt/us/libkh/bin/fbink /mnt/us/koreader/fbink /mnt/base-us/veradroid/bin/fbink; do
    [ -n "$_c" ] && [ -x "$_c" ] && "$_c" -e >/dev/null 2>&1 && { UN_FB=$_c; break; }
done
case "$UN_FB" in "$VD"/*|/mnt/base-us/veradroid/*)
    if cp "$UN_FB" /tmp/vd-uninstall-fbink 2>/dev/null && chmod +x /tmp/vd-uninstall-fbink 2>/dev/null; then
        UN_FB=/tmp/vd-uninstall-fbink; UN_FB_TMP=$UN_FB
        trap 'rm -f "$UN_FB_TMP"' EXIT
    fi ;;
esac
UN_FONT=
for _f in /usr/java/lib/fonts/STHeitiMedium.ttf /usr/java/lib/fonts/STSongMedium.ttf /usr/java/lib/fonts/MYingHeiSMedium.ttf; do
    [ -f "$_f" ] && { UN_FONT=$_f; break; }
done
# say <中文> [纯ASCII英文回退] [eips行号]：优先用 fbink 显示中文，回退 eips 时仅输出 ASCII 英文（eips 不支持中文）
say() {
    _zh=$1; _en=${2:-VeraDroid uninstall (see logs/uninstall.log)}; _row=${3:-4}
    t "uninstall: $_zh"
    if [ -n "$UN_FB" ] && [ -x "$UN_FB" ] && [ -n "$UN_FONT" ]; then
        _sw=$("$UN_FB" -e 2>/dev/null | tr ';' '\n' | sed -n 's/^viewWidth=//p' | head -1)
        case "$_sw" in ''|*[!0-9]*) _sw=1272 ;; esac
        _px=$((26 * _sw / 1272)); [ "$_px" -ge 20 ] || _px=20
        if _fe=$("$UN_FB" -q -c -t regular="$UN_FONT",px=$_px,top=$((40 * _sw / 1272)),left=30,right=30 "$_zh" 2>&1 >/dev/null); then
            case "$_fe" in
                *Unidentified*|*SEND_UPDATE*|*rror*|*ailed*) ;;
                *) return 0 ;;
            esac
        fi
    fi
    if command -v eips >/dev/null 2>&1; then
        eips -c >/dev/null 2>&1 || true
        eips 1 "$_row" "$_en" >/dev/null 2>&1 || true
    fi
}
# 卸载停止时：本次从 loop 系统盘拷出来的书（KEEP）只是副本，原件还在镜像里 → 删掉副本，否则下次重试会因为 KEEP 已存在而永远停住
MADEKEEP=0
fail() { if [ "$MADEKEEP" = 1 ] && [ -d "$SYS/data/media" ] && [ -d "$KEEP" ]; then rm -rf /mnt/base-us/veradroid/backup/media-keep; t "uninstall: 已删掉本次的书副本 $KEEP（原件仍在系统盘里）"; fi; say "卸载停止：$1（什么都没删）" "Uninstall stopped: ${2:-see uninstall.log} (nothing deleted)" 5; exit 1; }
KEEP=/mnt/base-us/veradroid/backup/media-keep
sleep 1
cd /                                   # 自己的工作目录也不能在 $SYS 里
# 2026-11-12（kt6-517104 模拟器复现）：卸载以前不管自动更新。auto-update.sh 跑的是 /dev/shm 里的副本，卸载后还活着；
# 它看到“Android 没在运行 + 服务器有新版”就下载整包重装——卸载进行到一半时它已经在往同一个系统盘里装（结果是一套开不了机的系统，
# 之后点安装只得到“另一个安装/更新正在进行”），卸载完成以后它也会在下一次检查时把刚卸掉的 VeraDroid 又装回来。
# 所以：先停自动更新，再停正在跑的安装器（连同它的 curl / tar），清掉它们的锁和临时目录，然后才开始卸载。
kill_tree() { for c in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$c"; done; kill "$1" 2>/dev/null; }
ap=$(cat $W/autoupdate.pid 2>/dev/null); [ -n "$ap" ] || ap=$(cat /mnt/base-us/veradroid/state/autoupdate.lock/pid 2>/dev/null); [ -n "$ap" ] || ap=$(cat /mnt/base-us/veradroid-autoupdate.lock/pid 2>/dev/null)   # root-legacy: 更新前起的老自动更新
if [ -n "$ap" ] && [ "$ap" != "$$" ] && kill -0 "$ap" 2>/dev/null; then kill_tree "$ap"; t "uninstall: 已停止自动更新（pid $ap）"; fi
ip=$(cat /mnt/base-us/veradroid/state/install.lock/pid 2>/dev/null); [ -n "$ip" ] && kill -0 "$ip" 2>/dev/null || ip=$(cat /mnt/base-us/veradroid-install.lock/pid 2>/dev/null)   # root-legacy: 老版本的安装器
if [ -n "$ip" ] && [ "$ip" != "$$" ] && kill -0 "$ip" 2>/dev/null; then
    kill_tree "$ip"; t "uninstall: 已停止正在运行的安装器（pid $ip）"
    i=0; while [ $i -lt 10 ] && kill -0 "$ip" 2>/dev/null; do sleep 1; i=$((i + 1)); done
    kill -9 "$ip" 2>/dev/null
fi
rm -rf /mnt/base-us/veradroid/state/autoupdate.lock /mnt/base-us/veradroid/state/install.lock 2>/dev/null
rm -f $W/autoupdate.pid /mnt/base-us/veradroid/state/autoupdate-bootstrap.sh /mnt/base-us/veradroid/state/autoupdate-bootstrap.sh.part 2>/dev/null
rm -rf /mnt/base-us/veradroid-autoupdate.lock /mnt/base-us/veradroid-install.lock /mnt/base-us/veradroid-autoupdate-bootstrap.sh /mnt/base-us/veradroid-autoupdate-bootstrap.sh.part 2>/dev/null   # root-legacy
# 删程序时 logs / backup 留着（卸载日志、安装前备份、卸载时拷出来的书），state 里只留书库清单这类以后还有用的记录：
# 锁、半截安装标记、安装器临时目录、系统盘调整记录删掉。以前这些都在根目录，那边的老文件一起删。
un_clean() {
    # 用户自己放的字体（veradroid/fonts = Android 的 内部存储/Fonts）和屏保图片不删：挪进 backup/user-files，重新安装时放回去
    if type vd_user_keep >/dev/null 2>&1; then t "uninstall: 用户的字体 / 屏保留在 veradroid/backup/user-files：$(vd_user_keep "$VD") 个文件"; fi
    vd_prog_rm "$VD"
    rm -rf "$VD_STATE"/install.lock "$VD_STATE"/autoupdate.lock "$VD_STATE"/install.[0-9]* 2>/dev/null
    rm -f "$VD_STATE"/install.incomplete "$VD_STATE"/autoupdate-bootstrap.sh "$VD_STATE"/autoupdate-bootstrap.sh.part "$VD_STATE"/resize.state "$VD_STATE"/resize.pending "$VD_STATE"/resize.last "$VD_STATE"/resize.notify "$VD_LOGS"/resize.log 2>/dev/null
    rm -rf /mnt/base-us/veradroid-install.[0-9]* 2>/dev/null   # root-legacy
    rm -f /mnt/base-us/veradroid-install.incomplete /mnt/base-us/veradroid-resize.state /mnt/base-us/veradroid-resize.pending /mnt/base-us/veradroid-resize.last /mnt/base-us/veradroid-resize.notify /mnt/base-us/veradroid-resize.log 2>/dev/null   # root-legacy
    return 0
}
# 2026-11-13（Oasis 2 模拟器，发布门）：点卸载时一次启动还在半路（veradroid.sh 停在开机前的准备里），卸载的 stop.sh 先跑完，
# 那次启动随后照样起了容器、又拉起自动更新，系统盘一直挂着，卸载半途而废。先把正在启动的脚本（连同子进程）停掉，再 stop.sh。
for sp in $(ps 2>/dev/null | grep -E '[b]oot/veradroid\.sh|[V]eraDroid-Launch\.sh|[V]eraDroid 启动\.sh|[t]ester-watch\.sh' | awk '{print $1}'); do
    [ "$sp" != "$$" ] && kill -0 "$sp" 2>/dev/null && { kill_tree "$sp"; t "uninstall: 已停止正在进行的启动（pid $sp）"; }
done
sh $VD/boot/stop.sh >/dev/null 2>&1
# 2026-11-10（pw5-5182 模拟器复现）：开机途中的 veradroid.sh 还开着 $SYS/veradroid.log（exec >>$LOG），
# stop.sh 不杀它 → 系统盘卸不掉 → “卸载停止：还挂着”。卸载前先关掉所有还打开 $SYS 的进程（不含自己）。
sys_holders() {
    for p in /proc/[0-9]*; do
        pid=${p#/proc/}; [ "$pid" = "$$" ] && continue
        ls -l $p/cwd $p/root $p/fd 2>/dev/null | grep -qE -- "-> $SYS(/|\$)" && echo "$pid"
    done
}
i=0
while [ $i -lt 6 ]; do
    h=$(sys_holders); [ -z "$h" ] && break
    t "uninstall: 关掉仍占用 $SYS 的进程: $(for x in $h; do printf '%s(%s) ' $x "$(tr '\0' ' ' < /proc/$x/cmdline 2>/dev/null | cut -c1-60)"; done)"
    if [ $i -lt 4 ]; then kill $h 2>/dev/null; else kill -9 $h 2>/dev/null; fi
    sleep 1; i=$((i + 1))
done
say '正在卸载 VeraDroid，保留书和账号…' 'Uninstalling VeraDroid (keeping books and data)...' 4
[ -e "$KEEP" ] && fail "$KEEP 已存在（上一次卸载留下的书还在那里）" "media-keep already exists"
[ -e /mnt/base-us/veradroid-media-keep ] && fail "/mnt/base-us/veradroid-media-keep 已存在（上一次卸载留下的书还在那里）" "veradroid-media-keep already exists"   # root-legacy
LOOPIMG=0
if [ -f "$SYSTEM_IMG" ]; then
    LOOPIMG=1
    storage_mount || fail "系统盘挂不上" "cannot mount system image"
    grep -q " $SYS " /proc/mounts || fail "系统盘没有挂上" "system image not mounted"
fi
mounts_under "$SYS/rootfs" 1 && fail "$SYS/rootfs 下面还有挂载" "rootfs still has mounts"
if [ "$PURGE" != 1 ] && [ -d "$SYS/data/media" ]; then
    if [ "$LOOPIMG" = 1 ]; then
        # 系统盘里的 data/media 和 /mnt/base-us 不是同一个文件系统：先完整拷出来，核对，再删镜像
        need=$(du -sk "$SYS/data/media" 2>/dev/null | awk '{print $1}')
        free=$(df -k /mnt/base-us | awk 'NR==2{print $4}')
        if [ $(( ${need:-0} + 51200 )) -gt "${free:-0}" ]; then
            # 空间不够拷：只删系统部分，镜像（里面有书）留下
            rm -rf "$SYS/rootfs" "$SYS/data/dalvik-cache" 2>/dev/null
            storage_umount
            mounts_under "$VD" 1 && fail "$VD 下面有挂载" "veradroid still has mounts"
            un_clean
            say "空间不够把书拷出来：系统已删除，书和账号还在 veradroid-sys.img 里（重新安装会接着用）" "Uninstalled (low space: books kept in veradroid-sys.img)" 5
            exit 0
        fi
        rm -rf "$KEEP.partial" 2>/dev/null
        # vfat 用户存储（KT5、PW5 等，pw5b-5192 模拟器实测）不能 chown：busybox cp -a 照样把文件全拷过去，只是报
        # "can't preserve ownership" 并返回 1，以前卸载因此总是停在“拷贝书失败”。只有这一种报错时不算失败（下面还要核对数量）。
        if ! cp -a "$SYS/data/media" "$KEEP.partial" 2>/tmp/vd-uninstall-cp.err; then
            if grep -v "preserve ownership" /tmp/vd-uninstall-cp.err | grep -q .; then rm -rf "$KEEP.partial"; fail "拷贝书失败" "failed to copy books"; fi
            t "uninstall: 用户存储不能保留文件属主（vfat），书照样拷完了"
        fi
        rm -f /tmp/vd-uninstall-cp.err
        n1=$(find "$SYS/data/media" | wc -l); n2=$(find "$KEEP.partial" | wc -l)
        [ "$n1" = "$n2" ] || { rm -rf "$KEEP.partial"; fail "拷贝核对失败 ($n1/$n2)" "copy verify mismatch ($n1/$n2)"; }
        mv "$KEEP.partial" "$KEEP" || fail "无法保存书" "failed to move books"
        MADEKEEP=1
    else
        mv "$SYS/data/media" "$KEEP" || fail "无法保存书" "failed to move books"
    fi
fi
storage_umount
mounts_under "$SYS" 1 && fail "$SYS 还挂着（或下面有挂载）" "system still mounted"
mounts_under "$VD" 1 && fail "$VD 下面有挂载" "veradroid still has mounts"
un_clean
[ "$LOOPIMG" = 1 ] && rm -f "$SYSTEM_IMG"
rm -rf "$SYS" 2>/dev/null
swapoff /mnt/base-us/veradroid-swap 2>/dev/null; rm -f /mnt/base-us/veradroid-swap   # legacy：小内存机型的交换文件（memory.sh）
rm -rf /mnt/base-us/veradroid.bak.* 2>/dev/null       # 很早的版本留下的程序目录备份（没有用户数据）  root-legacy
# $SYS.pre-ext4.* 可能是 loop 系统盘之前的书，不删（重新安装时会搬回去）
if [ "$LOOPIMG" = 1 ]; then
    # KT5 / KT6 等：/mnt/base-us/system 是 fscrypt 加密目录，不加密的目录 mv 不进去（EPERM，模拟器实测）。
    # 书就留在 veradroid/backup/media-keep，重新安装时 bootstrap 会把它搬进新的系统盘。
    :
elif [ "$PURGE" != 1 ]; then
    mkdir -p "$SYS/data"
    [ -d "$KEEP" ] && mv "$KEEP" "$SYS/data/media"
fi
rm -f /mnt/base-us/veradroid-version /mnt/base-us/veradroid-model /mnt/base-us/veradroid-device-id /mnt/base-us/veradroid-fullupdate.log /mnt/base-us/veradroid-fullupdate.rc   # root-legacy: 很早的版本放在根目录的元数据
# 书库里我们自己建的启动入口和 KUAL 菜单也拿掉：程序没了以后入口还留着，点了只会说“未安装”（用户报告：只剩一个入口而且打不开）。
# 只按固定名字删我们自己建的东西；KUAL 目录要确认里面是我们那几份文件才删。别的 .sh 入口、别的扩展一律不动。
for uL in "/mnt/us/documents/VeraDroid 启动.sh" "/mnt/base-us/documents/VeraDroid 启动.sh"; do
    if [ -f "$uL" ] && grep -q '/mnt/base-us/veradroid/boot/veradroid.sh' "$uL" 2>/dev/null; then
        rm -f "$uL"; [ -d "$uL.sdr" ] && [ ! -L "$uL.sdr" ] && rm -rf "$uL.sdr"
        t "uninstall: 已删除书库入口 $uL"
    fi
done
# 用户为了卸载拷进 documents 的 VeraDroid-Uninstall.sh（或旧版放进书库的那个）：卸完就没用了，一起拿掉（只认我们自己那份）
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
vd_retire_uninstall_entry
uK=/mnt/us/extensions/VeraDroid
if [ -d "$uK" ] && [ ! -L "$uK" ] && grep -q 'veradroid' "$uK/start.sh" 2>/dev/null; then
    rm -f "$uK/start.sh" "$uK/install.sh" "$uK/restore-entry.sh" "$uK/config.xml" "$uK/menu.json"; rmdir "$uK" 2>/dev/null && t "uninstall: 已删除 KUAL 菜单 $uK"
fi
if [ "$PURGE" = 1 ]; then
    # 内核模块（只是内存里的，重启也会消失）：能卸就卸
    swapoff /dev/zram0 2>/dev/null; for m in binder uinput zram; do grep -q "^$m " /proc/modules && rmmod $m 2>/dev/null; done
    # 安装前的书库清单：每个文件都还在才删备份；少了就留着备份并在屏幕上说
    man=/mnt/base-us/veradroid/state/docs-manifest; [ -f $man ] || man=$man.checked
    [ -f $man ] || man=/mnt/base-us/veradroid-docs-manifest; [ -f $man ] || man=$man.checked   # root-legacy: 老版本安装器写的
    # 我们自己的书库入口（启动入口和它的 .sdr，上面刚删掉）不算少了的书：升级装的清单里有它们，以前因此总说“少了 2 个文件”、留着备份
    miss=0; [ -f $man ] && while IFS= read -r f; do
        case "$f" in "documents/VeraDroid 启动.sh"|"documents/VeraDroid 启动.sh.sdr/"*|documents/VeraDroid-*.sh|documents/VeraDroid-*.sh.sdr/*|documents/VeraDroid-*.sdr/*) continue ;; esac
        [ -e "/mnt/base-us/$f" ] || miss=$((miss + 1)); done < $man
    for f in "/mnt/us/documents/VeraDroid-Report.sh" "/mnt/us/documents/VeraDroid-Uninstall.sh" "/mnt/us/documents/VeraDroid-Experimental.sh" \
             "/mnt/base-us/documents/VeraDroid-Report.sh" "/mnt/base-us/documents/VeraDroid-Uninstall.sh" "/mnt/base-us/documents/VeraDroid-Experimental.sh"; do
        [ -f "$f" ] && grep -q -i 'veradroid' "$f" 2>/dev/null && rm -f "$f"
        for d in "$f.sdr" "${f%.sh}.sdr"; do [ -d "$d" ] && [ ! -L "$d" ] && rm -rf "$d"; done
    done
    for d in "/mnt/us/documents/VeraDroid 启动.sdr" "/mnt/us/documents/VeraDroid 启动.sh.sdr"; do [ -d "$d" ] && [ ! -L "$d" ] && rm -rf "$d"; done
    rm -f /mnt/base-us/veradroid/logs/install-detail.log /mnt/us/veradroid/logs/install.log /mnt/base-us/veradroid/logs/install.log /mnt/base-us/veradroid/logs/autoupdate.log \
          /mnt/base-us/veradroid/logs/*.log /mnt/base-us/veradroid/logs/*.log.old /mnt/base-us/veradroid/state/autoupdate-bootstrap.sh \
          /mnt/base-us/veradroid/backup/cc.db.* /mnt/base-us/veradroid/state/confirm /mnt/base-us/veradroid/state/launchers-restored 2>/dev/null
    rm -f /mnt/base-us/veradroid-install-detail.log /mnt/us/veradroid-install.log /mnt/base-us/veradroid-install.log /mnt/base-us/veradroid-autoupdate.log /mnt/base-us/veradroid-autoupdate-bootstrap.sh /mnt/base-us/veradroid-cc.db.* /mnt/base-us/veradroid-confirm /mnt/base-us/veradroid-uninstall.log /mnt/base-us/veradroid-fsck.log 2>/dev/null   # root-legacy
    if [ "$miss" = 0 ]; then
        rm -f /mnt/base-us/veradroid/backup/user-backup-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].tgz /mnt/base-us/veradroid/backup/user-backup.partial /mnt/base-us/veradroid/state/docs-manifest /mnt/base-us/veradroid/state/docs-manifest.checked 2>/dev/null
        # 根目录里老版本的：只删安装器按时间起名的那些，用户改过名的（KEEP 等）不动   root-legacy
        rm -f /mnt/base-us/veradroid-user-backup-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].tgz /mnt/base-us/veradroid-user-backup.partial /mnt/base-us/veradroid-docs-manifest /mnt/base-us/veradroid-docs-manifest.checked 2>/dev/null   # root-legacy
        rmdir /mnt/base-us/veradroid/logs /mnt/base-us/veradroid/state /mnt/base-us/veradroid/backup /mnt/base-us/veradroid 2>/dev/null   # 空了就连文件夹一起拿掉
        sync; say 'VeraDroid 已完全卸载（书库一个文件都没少，备份已删除）。' 'VeraDroid completely uninstalled (all books intact).' 5
    else
        sync; say "VeraDroid 已卸载。书库比安装前少了 $miss 个文件：安装前的备份留在 veradroid/backup/ 里" "VeraDroid uninstalled ($miss files missing, backup kept)." 5
    fi
    exit 0
fi
say 'VeraDroid 已卸载，书和账号保留。可随时重新安装。' 'VeraDroid uninstalled (books and data kept).' 5
exit 0
}
