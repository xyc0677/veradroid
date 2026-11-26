#!/bin/sh
# 更新前备份可选（UPDATE_BACKUP=full|light|off）和“已备份的数据”（列出 / 删除），2026-11-19：
#  1. backup.sh 在各机型的宿主里是同一份（新机型分支改 host/pw6、host/k49 和 bootstrap.sh，老机型分支改 host/legacy 和
#     bootstrap-legacy.sh；另一边留着的旧副本不查）；vd_backup_mode 块和安装器里的一字不差；宿主加载它、管理请求里有三个动词；
#     UPDATE_BACKUP 只能经管理请求改（不在 settings_apply 的键里）、会发布给桌面。
#  2. 安装器：off 不备份但照样写书库清单；完整备份放不下先退到只备份设置和阅读进度；结果写 state/backup.last。离线更新把设置传给老安装器。
#  3. 实际跑 backup.sh（假的根目录，set -eu）：列表对；删除只认固定格式的名字、拒绝路径 / 链接 / KEEP / 别的文件夹；
#     安装进行中全部不能删，书库核对之前这次的备份不能删；改设置写 settings.conf 和副本。SH= 可换 shell（Kindle 上是 busybox ash）。
cd "$(dirname "$0")/.." || exit 2
fail=0
bad() { echo "FAIL $*"; fail=1; }
SH=${SH:-sh}

# 1. 同一份 / 块 / 接线
ref=$(ls host/*/boot/lib/backup.sh 2>/dev/null | head -1)
[ -n "$ref" ] || { echo "FAIL no host/*/boot/lib/backup.sh"; exit 1; }
for f in host/*/boot/lib/backup.sh; do cmp -s "$f" "$ref" || bad "$f differs from $ref"; done
blk() { sed -n '/^# BEGIN vd_backup_mode/,/^# END vd_backup_mode/p' "$1"; }
[ -n "$(blk "$ref")" ] || bad "$ref has no vd_backup_mode block"
INST=; HOSTS=$(ls -d host/*/boot/lib 2>/dev/null | while read -r d; do [ -f $d/backup.sh ] && echo $d; done)
for d in $HOSTS; do case $d in host/legacy/*) INST="$INST cloud/static/bootstrap-legacy.sh" ;; *) INST="$INST cloud/static/bootstrap.sh" ;; esac; done
INST=$(echo $INST | tr ' ' '\n' | sort -u)
for f in $INST; do
    [ -f "$f" ] || { bad "$f missing"; continue; }
    [ "$(blk "$f")" = "$(blk "$ref")" ] || bad "$f: vd_backup_mode block differs from $ref"
    grep -q '^BK_MODE=$(vd_backup_mode)$' "$f" || bad "$f does not read the backup mode"
    grep -q 'UPDATE_BACKUP=off' "$f" || bad "$f: no log line for backup off"
    grep -q '更新前备份已关闭' "$f" || bad "$f: no screen message for backup off"
    grep -q 'echo "result=$BK_RESULT"' "$f" || bad "$f does not write state/backup.last"
    grep -q 'fallback=light' "$f" || bad "$f: no fall back to a light backup"
    # 清单在“不备份”时也要记：DOCS_MANIFEST 那段不能放进备份的 if 里（它在 rm -f $BKL $BKC 之后）
    awk '/^rm -f \$BKL \$BKC$/{r=1} r && /^DOCS_MANIFEST=/{ok=1} END{exit !ok}' "$f" || bad "$f: documents manifest no longer taken after the backup block"
    sh -n "$f" || bad "$f: syntax"
done
case " $(echo $INST) " in *bootstrap-legacy.sh*) grep -q 'TESTED" = emulator-only \].*then BK_MODE=light' cloud/static/bootstrap-legacy.sh || bad "legacy: emulator-only models must still back up on first install" ;; esac
for d in $HOSTS; do
    grep -q 'boot/lib/backup.sh' $d/common.sh || bad "$d/common.sh does not source backup.sh"
    for v in updatebackup backups backupdel; do grep -q "^    $v)" $d/webadmin.sh || bad "$d/webadmin.sh: no '$v' request"; done
    awk '/^settings_apply\(\)/{a=1} a && /^    for k in /{print; exit}' $d/settings.sh | grep -q UPDATE_BACKUP && bad "$d/settings.sh: UPDATE_BACKUP must not be settable through ctl/settings.new"
    awk '/^settings_publish\(\)/{a=1} a && /for k in /{print; exit}' $d/settings.sh | grep -q UPDATE_BACKUP || bad "$d/settings.sh: UPDATE_BACKUP not published"
    grep -q '^    UPDATE_BACKUP=full' $d/settings.sh || bad "$d/settings.sh: no UPDATE_BACKUP default"
    grep -q 'export VD_UPDATE_BACKUP=' $d/localupdate.sh || bad "$d/localupdate.sh does not pass the backup mode"
    grep -q '没有安装前备份，少了的文件补不回来' $d/common.sh || bad "$d/common.sh: docs check does not say there is nothing to restore from"
    sh -n $d/backup.sh || bad "$d/backup.sh: syntax"
done

# 2./3. 行为
T=${TMPDIR:-/tmp}/vd-backup-opt.$$; rm -rf "$T"; mkdir -p "$T/bin"
# macOS 上测：stat -c / date -d 换成 BSD 的写法（Kindle / Linux 上是 busybox / GNU，不走这里）
if ! stat -c %s / >/dev/null 2>&1; then
    cat > "$T/bin/stat" <<'EOF'
#!/bin/sh
[ "$1" = -c ] || exec /usr/bin/stat "$@"
case "$2" in %s) f=%z ;; %Y) f=%m ;; %u) f=%u ;; *) f=$2 ;; esac
shift 2; exec /usr/bin/stat -f "$f" "$@"
EOF
    cat > "$T/bin/date" <<'EOF'
#!/bin/sh
if [ "$1" = -d ]; then n=${2#@}; shift 2; exec /bin/date -r "$n" "$@"; fi
exec /bin/date "$@"
EOF
    chmod 755 "$T/bin/stat" "$T/bin/date"
fi
R=$T/top; B=$R/veradroid/backup; ST=$R/veradroid/state; SYSD=$R/system/veradroid
mkdir -p "$B/media-keep/0" "$ST" "$SYSD" "$R/vd18-keep" "$T/outside"
mk() { head -c "$2" /dev/zero > "$1"; }
mk "$B/user-backup-20261117-010203.tgz" 4096
mk "$B/user-backup-20261118-221500.tgz" 8192
mk "$B/cc.db.20261118-221500" 1024
mk "$B/documents-backup.restored-20261101-000000.tgz" 2048
mk "$B/media-keep/0/book.epub" 3072
mk "$B/user-backup-KEEP-20261101.tgz" 100           # 用户改过名的：不列、不删
mk "$B/notes.txt" 10                                # 别的文件：不列、不删
mk "$R/veradroid-user-backup-20261001-101010.tgz" 2048   # 根目录里老版本的同名备份：算
mk "$R/veradroid-user-backup-KEEP-20261001.tgz" 100      # 根目录里用户改过名的：不碰
mk "$R/vd18-keep/x.tgz" 100
mk "$T/outside/secret.tgz" 100
ln -s "$T/outside/secret.tgz" "$B/user-backup-20261119-000000.tgz"   # 链接：不列、不删、不跟过去
run() { # 在假的根目录里 set -eu 跑 backup.sh 的函数
    ( PATH=$T/bin:$PATH VD_TOP=$R SYS=$SYSD $SH -eu -c ". '$PWD/$ref'; $1" ) 2>"$T/err"
}
run vb_info > "$T/info" || bad "vb_info failed: $(cat "$T/err")"
grep -q '^n=6$' "$T/info" || bad "vb_info: expected 6 items, got $(grep '^n=' "$T/info")"
grep -q '^i.1.name=user-backup-20261118-221500.tgz$' "$T/info" || bad "vb_info: newest user backup not first"
grep -q '=user-backup-KEEP' "$T/info" && bad "vb_info lists a renamed (KEEP) backup"
grep -q '=notes.txt' "$T/info" && bad "vb_info lists an unknown file"
grep -q '=user-backup-20261119-000000.tgz' "$T/info" && bad "vb_info lists a symlink"
grep -q 'where=root' "$T/info" || bad "vb_info misses the root-layout backup"
grep -q '^mode=full$' "$T/info" || bad "vb_info: default mode should be full"
grep -q '^busy=0$' "$T/info" || bad "vb_info: busy without an install"
kb=$(sed -n 's/^total_kb=//p' "$T/info"); [ "${kb:-0}" -ge 20 ] || bad "vb_info: total_kb too small ($kb)"
grep -q '^i.[0-9]*.time=2026-11-18 22:15$' "$T/info" || bad "vb_info: time from the name"
# 不认的名字 / 路径
for a in "new ../outside/secret.tgz" "new /etc/passwd" "new user-backup-KEEP-20261101.tgz" "new notes.txt" "root vd18-keep" \
         "new user-backup-20261119-000000.tgz" "elsewhere user-backup-20261117-010203.tgz" "new user-backup-20261117-010203.tgz extra" "new" ""; do
    run "vb_del '$a'" > "$T/out" || bad "vb_del '$a' failed"
    grep -q '^deleted=0$' "$T/out" || bad "vb_del '$a' deleted something"
done
[ -f "$T/outside/secret.tgz" ] && [ -f "$B/user-backup-KEEP-20261101.tgz" ] && [ -f "$B/notes.txt" ] && [ -f "$R/vd18-keep/x.tgz" ] || bad "refused deletes touched files"
[ -L "$B/user-backup-20261119-000000.tgz" ] || bad "symlink removed"
# 安装进行中：全部不能删
mkdir -p "$ST/install.lock"; echo $$ > "$ST/install.lock/pid"
run vb_info > "$T/info"; grep -q '^busy=1$' "$T/info" || bad "busy not reported during an install"
[ "$(grep -c '\.lock=install$' "$T/info")" = 6 ] || bad "not every item locked during an install"
run "vb_del all" > "$T/out"; grep -q '^deleted=0$' "$T/out" && grep -q '^error=inuse$' "$T/out" || bad "delete all during an install: $(tr '\n' ' ' < "$T/out")"
rm -rf "$ST/install.lock"
# 书库核对之前：这次的备份和书库数据库不能删，别的能删
printf 'stamp=20261118-221500\nmode=full\nkind=full\nresult=ok\nfile=user-backup-20261118-221500.tgz\n' > "$ST/backup.last"
echo docs/a.epub > "$ST/docs-manifest"
run vb_info > "$T/info"
grep -q '^pending=1$' "$T/info" || bad "pending not reported"
for n in user-backup-20261118-221500.tgz cc.db.20261118-221500; do
    i=$(sed -n "s/^i\.\([0-9]*\)\.name=$n\$/\1/p" "$T/info")
    grep -q "^i.$i.lock=verify$" "$T/info" || bad "$n not locked before the documents check"
done
run "vb_del 'new user-backup-20261118-221500.tgz'" > "$T/out"; grep -q '^error=inuse$' "$T/out" || bad "deleted the backup of this install before the check"
[ -f "$B/user-backup-20261118-221500.tgz" ] || bad "backup of this install deleted"
run "vb_del 'new user-backup-20261117-010203.tgz'" > "$T/out"; grep -q '^deleted=1$' "$T/out" || bad "could not delete an older backup: $(tr '\n' ' ' < "$T/out")"
[ ! -e "$B/user-backup-20261117-010203.tgz" ] || bad "older backup still there"
# 没有 backup.last（老安装器）：按最新的一份算
rm -f "$ST/backup.last"; run vb_info > "$T/info"
i=$(sed -n 's/^i\.\([0-9]*\)\.name=user-backup-20261118-221500.tgz$/\1/p' "$T/info"); grep -q "^i.$i.lock=verify$" "$T/info" || bad "without backup.last the newest backup is not locked"
# 核对完：全部能删（根目录的、卸载留下的文件夹也删；别的不动）
mv "$ST/docs-manifest" "$ST/docs-manifest.checked"
run "vb_del all" > "$T/out"; grep -q '^deleted=5$' "$T/out" || bad "delete all: $(tr '\n' ' ' < "$T/out")"
for g in "$B/user-backup-20261118-221500.tgz" "$B/cc.db.20261118-221500" "$B/media-keep" "$B/documents-backup.restored-20261101-000000.tgz" "$R/veradroid-user-backup-20261001-101010.tgz"; do
    [ ! -e "$g" ] || bad "delete all left $g"
done
for k in "$B/user-backup-KEEP-20261101.tgz" "$B/notes.txt" "$R/veradroid-user-backup-KEEP-20261001.tgz" "$R/vd18-keep/x.tgz" "$T/outside/secret.tgz"; do
    [ -e "$k" ] || bad "delete all removed $k"
done
# veradroid/backup 本身是链接：什么都不删
rm -rf "$B"; mkdir -p "$T/elsewhere"; mk "$T/elsewhere/user-backup-20261117-010203.tgz" 100; ln -s "$T/elsewhere" "$B"
run "vb_del all" > "$T/out"; [ -f "$T/elsewhere/user-backup-20261117-010203.tgz" ] || bad "followed a symlinked backup dir"
rm -f "$B"; mkdir -p "$B"
# 设置
run "vb_setmode off" > "$T/out"; grep -q '^ok=1$' "$T/out" && grep -q '^mode=off$' "$T/out" || bad "vb_setmode off: $(tr '\n' ' ' < "$T/out")"
grep -qx 'UPDATE_BACKUP=off' "$SYSD/settings.conf" || bad "settings.conf not written"
[ "$(cat "$ST/update-backup" 2>/dev/null)" = off ] || bad "state/update-backup copy not written"
run "vb_setmode 'x;rm'" > "$T/out"; grep -q '^error=' "$T/out" || bad "vb_setmode accepted a bad value"
run "vb_setmode light" > /dev/null; [ "$(grep -c '^UPDATE_BACKUP=' "$SYSD/settings.conf")" = 1 ] || bad "settings.conf has duplicate UPDATE_BACKUP lines"
# vd_backup_mode：环境变量 > settings.conf > 副本 > full；坏值按 full
[ "$(run 'VD_UPDATE_BACKUP=off; vd_backup_mode')" = off ] || bad "env does not win"
[ "$(run vd_backup_mode)" = light ] || bad "settings.conf not read"
mv "$SYSD/settings.conf" "$T/sc"; echo off > "$ST/update-backup"
[ "$(run vd_backup_mode)" = off ] || bad "copy not used when settings.conf is missing"
echo 'UPDATE_BACKUP=bogus' > "$SYSD/settings.conf"; [ "$(run vd_backup_mode)" = full ] || bad "bad value not treated as full"
: > "$SYSD/settings.conf"; [ "$(run vd_backup_mode)" = full ] || bad "settings.conf without the key should be full (not the copy)"
rm -rf "$SYSD" "$ST/update-backup"; [ "$(run vd_backup_mode)" = full ] || bad "nothing at all should be full"
rm -rf "$T"
[ $fail = 0 ] && echo "backup-opt: ok"
exit $fail
