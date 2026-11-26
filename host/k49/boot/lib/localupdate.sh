#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 离线更新文件夹（用法见 docs/host-settings.md）。
# 用户把离线安装包放进 Kindle 的 veradroid/update/（USB 里看到的路径；设备上是 /mnt/us/veradroid/update = /mnt/base-us/veradroid/update）：
#   veradroid-pw6-<版本>.tar.gz（PW6 / Colorsoft，5.x 内核）、veradroid-k49-<版本>.tar.gz（PW5 / KT5 / KT6，4.9 内核），
#   或者整个离线包 VeraDroid-Offline.zip / VeraDroid-Offline-Experimental.zip（不用解压）。
# 下次点“VeraDroid 启动”（veradroid.sh）或者桌面发 `localupdate install` 时：
#   1. 只看文件名就能判断的先判断（便宜）：机型变体对不对（看内核），版本是不是比现在装的新。旧的 / 一样的 / 别的机型的
#      改名成 *.rejected，不装、不再重试。
#   2. 校验：整包里的 SHA256SUMS（pack.sh 写的，覆盖包里每个文件）逐个核对；老包没有 SHA256SUMS 时退一步，
#      gzip 全文件 CRC 校验（tar -tz 读一遍）。旁边有 <包>.sha256 时也核对整个文件。manifest.json 的 product / version
#      必须和文件名一致。
#   3. 用包里自带的 bootstrap.sh（新包都带；老包用 update/ 里或 zip 里的 bootstrap.sh）走原来的离线安装流程：
#      安装前备份、veradroid/state/install.incomplete 标记、被打断后回退，和书库里的离线安装一模一样（VD_BUNDLE）。
#   4. 成功（版本号变成新版本、没有 incomplete 标记）：删掉安装包。失败：包改名 *.failed，留着给用户看。
#   结果写 veradroid/update/result.txt（KEY=value：result=ok|failed|rejected、file、from、to、time、reason）。
# 用法：sh localupdate.sh check            只按文件名列出候选（给桌面看），不读包内容
#       sh localupdate.sh install [--start] 校验并安装；--start = 装好后启动新版本（桌面命令用；开机路径由 veradroid.sh 自己接着启动）
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH
VD=/mnt/base-us/veradroid
SYS=/mnt/base-us/system/veradroid
W=/dev/shm/veradroid
UPD=$VD/update
STAGE=/mnt/base-us/veradroid/state/update.stage      # 安装期间暂放的地方：bootstrap 把程序目录挪成备份时 update/ 跟着走、state/ 不动
LOG=/mnt/base-us/veradroid/logs/localupdate.log
INCOMPLETE=/mnt/base-us/veradroid/state/install.incomplete
mkdir -p /mnt/base-us/veradroid/logs /mnt/base-us/veradroid/state 2>/dev/null
MODE=${1:-check}
lg() { echo "[$(date '+%F %T')] $*" >> $LOG; }
# 桌面的更新进度卡片（$W/kfb/ctl/update.status，同安装器 / progress.sh 的格式）：校验这一段在这里写，后面安装器自己接着写
lu_status() {
    U=$W/kfb/ctl/update.status; [ -d $W/kfb/ctl ] || return 0
    { printf '%s\n' "$@" mode=local steps=verify,unpack,backup,install; echo "time=$(date +%s)"; } > $U.tmp 2>/dev/null && chmod 644 $U.tmp && mv -f $U.tmp $U
    return 0
}
ver_gt() { # $1 比 $2 新
    [ "$1" = "$2" ] && return 1
    printf '%s\n%s\n' "$1" "$2" | awk -F. 'NR == 1 { n1 = split($0, a, ".") } NR == 2 { n2 = split($0, b, ".") }
        END { n = n1 > n2 ? n1 : n2; for (i = 1; i <= n; i++) { x = a[i] + 0; y = b[i] + 0; if (x > y) exit 0; if (x < y) exit 1 } exit 1 }'
}
cur_version() { cat "$VD/version" 2>/dev/null || cat "$SYS/version" 2>/dev/null || echo 0; }
# 这台机器该用哪个变体：5.x 内核 = pw6，4.9 = k49，3.x / 4.1 = legacy
my_variant() { case "$(uname -r)" in 5.*) echo pw6 ;; 4.9.*) echo k49 ;; 3.*|4.1.*) echo legacy ;; *) echo "" ;; esac; }
# 文件名 -> 变体|版本
name_info() { # basename
    case "$1" in
        veradroid-pw6-*.tar.gz) v=${1#veradroid-pw6-}; printf 'pw6|%s\n' "${v%.tar.gz}" ;;
        veradroid-k49-*.tar.gz) v=${1#veradroid-k49-}; printf 'k49|%s\n' "${v%.tar.gz}" ;;
        veradroid-legacy-*.tar.gz) v=${1#veradroid-legacy-}; printf 'legacy|%s\n' "${v%.tar.gz}" ;;
        VeraDroid-Offline-Legacy*.zip) printf 'legacy|?\n' ;;
        VeraDroid-Offline-Experimental*.zip) printf 'k49|?\n' ;;
        VeraDroid-Offline*.zip) printf 'pw6|?\n' ;;
    esac
}
valid_ver() { case "$1" in ''|*[!0-9.]*|.*|*.) return 1 ;; esac; return 0; }
# zip 里的整包名（版本在里面的文件名上）
zip_bundle() { unzip -l "$1" 2>/dev/null | awk '{print $NF}' | grep -E '^veradroid-(pw6|k49|legacy)-[0-9.]+\.tar\.gz$' | head -1; }

result() { # result=... reason=...
    mkdir -p "$UPD" 2>/dev/null
    { echo "time=$(date '+%F %T')"; printf '%s\n' "$@"; } > "$UPD/result.txt.tmp" && mv -f "$UPD/result.txt.tmp" "$UPD/result.txt"
    lg "result: $*"
}
reject() { # file reason
    mv -f "$1" "$1.rejected" 2>/dev/null
    result result=rejected "file=${1##*/}" "from=$(cur_version)" "reason=$2"
}

# 候选：update/ 里每个包一行“变体|版本|状态|文件”（状态 ok = 可以装；文件名放末列兼容含空格文件名）
scan() {
    mv_=$(my_variant); cur=$(cur_version)
    for f in "$UPD"/veradroid-*-*.tar.gz "$UPD"/VeraDroid-Offline*.zip; do
        [ -f "$f" ] || continue
        bn=${f##*/}
        ni=$(name_info "$bn")
        var=${ni%%|*}; ver=${ni#*|}
        [ "$ni" = "$var" ] && ver=
        case "$f" in
            *.zip)
                b=$(zip_bundle "$f")
                if [ -n "$b" ]; then
                    ni=$(name_info "$b")
                    var=${ni%%|*}; ver=${ni#*|}
                    [ "$ni" = "$var" ] && ver=
                fi ;;
        esac
        st=ok
        if [ -z "$mv_" ]; then st=unsupported-kernel
        elif [ "$var" != "$mv_" ]; then st=wrong-variant
        elif ! valid_ver "$ver"; then st=bad-name
        elif ! ver_gt "$ver" "$cur"; then st=not-newer
        fi
        printf '%s|%s|%s|%s\n' "${var:-?}" "${ver:-?}" "$st" "$bn"
    done
}

if [ "$MODE" = check ]; then
    echo "installed=$(cur_version)"; echo "variant=$(my_variant)"
    scan | while IFS='|' read -r v ver st n; do echo "candidate=$n $v $ver $st"; done
    [ -f "$UPD/result.txt" ] && sed 's/^/last./' "$UPD/result.txt"
    exit 0
fi
[ "$MODE" = install ] || { echo "usage: localupdate.sh check | install [--start]"; exit 2; }
START=; [ "${2:-}" = --start ] && START=1
mkdir -p $W
if ! mkdir $W/localupdate.lock 2>/dev/null; then
    op=$(cat $W/localupdate.lock/pid 2>/dev/null)
    [ -n "$op" ] && kill -0 "$op" 2>/dev/null && { echo "another local update is running"; exit 1; }
    rm -rf $W/localupdate.lock; mkdir $W/localupdate.lock || exit 1
fi
read -r mypid _ < /proc/self/stat; echo "$mypid" > $W/localupdate.lock/pid
trap 'rm -rf $W/localupdate.lock' EXIT
{ [ -f "$INCOMPLETE" ] || [ -f /mnt/base-us/veradroid-install.incomplete ]; } && { lg "previous install incomplete, not updating now"; exit 1; }   # root-legacy: 老安装器的标记

# 1. 只按名字挑：先把不合格的改名（不再重试），在合格的里挑最新的
best=; bestver=
scan > $W/localupdate.scan
while IFS='|' read -r v ver st n; do
    if [ "$st" != ok ]; then
        case "$st" in
            not-newer) reject "$UPD/$n" "版本 $ver 不比已安装的 $(cur_version) 新（不会降级）" ;;
            wrong-variant) reject "$UPD/$n" "这个包是 $v 机型的，这台 Kindle 要用 $(my_variant) 的包" ;;
            unsupported-kernel) reject "$UPD/$n" "这台 Kindle（内核 $(uname -r)）还不支持离线更新" ;;
            *) reject "$UPD/$n" "文件名不对（应为 veradroid-$(my_variant)-<版本>.tar.gz）" ;;
        esac
        continue
    fi
    if [ -z "$best" ] || ver_gt "$ver" "$bestver"; then best=$n; bestver=$ver; fi
done < $W/localupdate.scan
[ -n "$best" ] || exit 1
VAR=$(my_variant); SRC="$UPD/$best"; CUR=$(cur_version)
lg "candidate $best ($VAR $bestver), installed $CUR"
result result=running "file=$best" "from=$CUR" "to=$bestver"     # 桌面可以显示“正在安装离线更新”
lu_status state=verifying step=verify "label=校验离线更新 $bestver" "line1=$best" "ver=$bestver"
eips 1 3 "VeraDroid：正在校验离线更新 $bestver…" >/dev/null 2>&1 || true

# 2. 暂存目录（和 update/ 同一个分区：tar.gz 直接 mv，zip 解出整包和 bootstrap.sh）
fail() { # reason（一行：tar 的错误信息可能有好几行）
    lu_status state=error code=31 "label=离线更新没有安装" "line2=$(printf '%s' "$1" | tr '\n' ' ')" "ver=$bestver"
    result result=failed "file=$best" "from=$CUR" "to=$bestver" "reason=$(printf '%s' "$1" | tr '\n' ' ')"
    [ -f "$SRC" ] && mv -f "$SRC" "$SRC.failed" 2>/dev/null
    # 只删暂存的安装包；keep/（用户的 update 目录）放不回去时还留在这里
    rm -rf "$STAGE/x" "$STAGE/zip" "$STAGE/bootstrap.sh" "$STAGE"/veradroid-*.tar.gz "$STAGE/tar.err" "$STAGE/tar.list"
    rmdir "$STAGE" 2>/dev/null
    eips 1 3 "VeraDroid：离线更新没有安装（$1）" >/dev/null 2>&1 || true
    exit 1
}
rm -rf "$STAGE/x" "$STAGE/zip" "$STAGE/bootstrap.sh" "$STAGE"/veradroid-*.tar.gz "$STAGE/tar.err" "$STAGE/tar.list"
mkdir -p "$STAGE/x" || fail "无法创建暂存目录"
B=$STAGE/veradroid-$VAR-$bestver.tar.gz
case "$SRC" in
    *.zip)
        unzip -tq "$SRC" >/dev/null 2>&1 || fail "zip 文件损坏"
        zb=$(zip_bundle "$SRC")
        unzip -o -q "$SRC" "$zb" -d "$STAGE" 2>/dev/null && [ -f "$STAGE/$zb" ] || fail "zip 里没有安装包（空间不够？）"
        unzip -o -q "$SRC" bootstrap.sh -d "$STAGE/zip" 2>/dev/null
        mv -f "$STAGE/$zb" "$B" ;;
    *) cp "$SRC" "$B" 2>/dev/null || { rm -f "$B"; fail "无法复制安装包（空间不够？）"; } ;;
esac
# 旁边的整文件校验和（可选）
for s in "$SRC.sha256" "$UPD/veradroid-$VAR-$bestver.tar.gz.sha256"; do
    [ -f "$s" ] || continue
    want=$(tr -cd '0-9a-f' < "$s" | cut -c1-64)
    got=$(sha256sum "$B" | cut -c1-64)
    [ "$want" = "$got" ] || fail "SHA-256 不对（$s）"
    lg "outer sha256 ok"; break
done
# 预检归档条目：拒绝以 / 开头、含 .. 路径段或非 veradroid-pw6/ 越界成员（防路径穿越/软链覆写）
tar -tzf "$B" > "$STAGE/tar.list" 2>"$STAGE/tar.err" || fail "安装包损坏（$(head -c 100 "$STAGE/tar.err")）"
[ -s "$STAGE/tar.list" ] || fail "安装包为空"
while IFS= read -r te; do
    case "$te" in
        ''|/*|..|../*|*/..|*/../*) fail "安装包包含非法路径（$te）" ;;
        veradroid-pw6|veradroid-pw6/*) ;;
        *) fail "安装包包含越界路径（$te）" ;;
    esac
done < "$STAGE/tar.list"
rm -f "$STAGE/tar.list"
# 包里的小文件（除了 rootfs.tar.gz 都很小）解到 x/，顺便整读一遍 gzip（CRC 错就失败）
tar -xzf "$B" -C "$STAGE/x" --exclude veradroid-pw6/rootfs.tar.gz 2>$STAGE/tar.err || fail "安装包损坏（$(head -c 100 $STAGE/tar.err)）"
P=$STAGE/x/veradroid-pw6
[ -f "$P/manifest.json" ] || fail "安装包里没有 manifest.json"
mprod=$(sed -n 's/.*"product": *"\([^"]*\)".*/\1/p' "$P/manifest.json")
mver=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$P/manifest.json")
[ "$mprod" = "veradroid-$VAR" ] || fail "manifest 的机型是 $mprod，不是 veradroid-$VAR"
[ "$mver" = "$bestver" ] || fail "manifest 的版本 $mver 和文件名 $bestver 不一致"
if [ -f "$P/SHA256SUMS" ]; then
    # 每行“sha256  相对路径”；rootfs.tar.gz 不解出来，直接从整包里流过 sha256sum
    n=0
    while read -r h f; do
        case "$f" in ''|/*|*..*) fail "SHA256SUMS 格式不对" ;; esac
        if [ "$f" = rootfs.tar.gz ]; then
            got=$(tar -xzOf "$B" veradroid-pw6/rootfs.tar.gz | sha256sum | cut -c1-64)
        else
            [ -f "$P/$f" ] || fail "缺少文件 $f"
            got=$(sha256sum "$P/$f" | cut -c1-64)
        fi
        [ "$got" = "$h" ] || fail "文件 $f 的 SHA-256 不对"
        n=$((n + 1))
        lu_status state=verifying step=verify "label=校验离线更新 $bestver" "line1=已核对 $n 个文件" "ver=$bestver"
    done < "$P/SHA256SUMS"
    grep -q ' rootfs.tar.gz$' "$P/SHA256SUMS" || fail "SHA256SUMS 里没有 rootfs.tar.gz"
    lg "SHA256SUMS: $n files ok"
else
    # 老包：没有清单。整包 gzip CRC（上面的 tar 已经把 gzip 流读完了）+ 系统镜像能完整读出
    tar -xzOf "$B" veradroid-pw6/rootfs.tar.gz | gzip -t 2>/dev/null || fail "系统镜像损坏"
    lg "no SHA256SUMS in this bundle (built before 0.89/0.97): gzip CRC checked only"
fi
# 3. 安装器：包里的（和包同一版本）> update/ 里的 > zip 里的
BS=
for c in "$P/bootstrap.sh" "$UPD/bootstrap.sh" "$STAGE/zip/bootstrap.sh"; do
    [ -f "$c" ] && sh -n "$c" 2>/dev/null && { BS=$c; break; }
done
[ -n "$BS" ] || fail "找不到 bootstrap.sh（老安装包请把离线包里的 bootstrap.sh 也放进 update/）"
cp "$BS" "$STAGE/bootstrap.sh"
rm -rf "$STAGE/x" "$STAGE/zip"
# update/ 整个挪出 $VD（bootstrap 会把 $VD 挪成 host.bak），装完再放回新的 $VD
rm -rf "$STAGE/keep"; mv "$UPD" "$STAGE/keep" || fail "无法暂存 update 目录"
SRC=$STAGE/keep/$best
lg "installing $bestver from $best with $(basename "$(dirname "$BS")")/bootstrap.sh"
m=$(cat $SYS/model 2>/dev/null || cat $VD/model 2>/dev/null)
case "$VAR" in k49) export VD_EXPERIMENTAL=1 ;; legacy) export VD_EXPERIMENTAL=1 VD_LEGACY_TEST=1 ;; esac
case "$m" in ''|pw6|cs) ;; *) export VD_MODEL="$m" VD_EXPERIMENTAL=1 ;; esac
# 更新前备份（设置 → 设备与更新 → 更新前备份，UPDATE_BACKUP）：新安装器自己读这个设置；离线包里是老安装器时，
# light / off 至少不备份书（老安装器认 VD_BACKUP_MB=0，不认 off，照样备份设置和阅读进度）
LU_UB=
if [ -f $SYS/settings.conf ]; then LU_UB=$(sed -n 's/^UPDATE_BACKUP=//p' $SYS/settings.conf 2>/dev/null | tail -1); LU_UB=${LU_UB:-full}
else LU_UB=$(cat /mnt/base-us/veradroid/state/update-backup 2>/dev/null); fi
case "$LU_UB" in full|light|off) ;; *) LU_UB=full ;; esac
export VD_UPDATE_BACKUP=$LU_UB
[ "$LU_UB" = full ] || export VD_BACKUP_MB=0
lg "pre-install backup: $LU_UB"
( cd / && VD_BUNDLE="$B" VD_VERSION="$bestver" VD_NO_START=1 sh "$STAGE/bootstrap.sh" ) </dev/null >> $LOG 2>&1
rc=$?
# 把 update/ 放回（新装好的 $VD，或安装器自己回退后的旧 $VD）。安装被打断、$VD 不完整时绝不能去建 $VD：
# veradroid.sh 的回退是 mv host.bak.* $VD，$VD 已存在就会把备份挪进去当子目录。那时 update/ 留在暂存目录里。
if [ -d "$VD/boot" ] && [ ! -f "$INCOMPLETE" ]; then
    if [ -d "$UPD" ]; then cp -a "$STAGE/keep/." "$UPD/" 2>/dev/null && rm -rf "$STAGE/keep"; else mv "$STAGE/keep" "$UPD"; fi
    SRC=$UPD/$best
else
    lg "program dir incomplete after the installer (rc=$rc); update folder kept at $STAGE/keep"
    UPD=$STAGE/keep
fi
after=$(cur_version)
if [ $rc = 0 ] && [ "$after" = "$bestver" ] && [ ! -f "$INCOMPLETE" ]; then
    rm -f "$SRC" "$SRC.sha256"
    rm -rf "$STAGE"
    result result=ok "file=$best" "from=$CUR" "to=$bestver"
    if [ -n "$START" ]; then ( cd / && setsid sh "$VD/boot/veradroid.sh" home ) </dev/null >/dev/null 2>&1 & fi
    exit 0
fi
fail "安装器返回 $rc，现在的版本是 $after（详见 veradroid/logs/localupdate.log / install-detail.log）"
