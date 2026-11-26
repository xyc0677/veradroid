#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# 把蓝牙桥的钩子打进一个 VeraDroid 安装目录（幂等，先备份）。用法：sh apply-bt.sh <veradroid 目录> <patches-bt 目录>
# 只加几行：input.sh 里 source bt.sh 并在 start/release/grab/stop 调 bt_*；container.sh 绑 event3；cmd.sh 加 "bt" 命令。
set -e
VDD=${1:?veradroid dir}; P=${2:?patches dir}; L=$VDD/boot/lib
ts=$(date +%Y%m%d-%H%M%S)
for f in input.sh container.sh cmd.sh; do cp -p $L/$f $L/$f.bak-bt-$ts; done
if [ -f "$L/bt.sh" ] && grep -q 'bt_status_lines' "$L/bt.sh"; then
    echo "keeping existing native $L/bt.sh"
else
    cp $P/bt.sh $L/bt.sh
fi
cp $P/bin/vdbtkeys $VDD/bin/vdbtkeys; chmod 755 $VDD/bin/vdbtkeys
grep -q 'lib/bt.sh' $L/input.sh || sed -i 's|^input_sysname() {|# 蓝牙输入桥（可选，见 bt.sh）\n[ -f "$VD/boot/lib/bt.sh" ] \&\& . "$VD/boot/lib/bt.sh"\ninput_sysname() {|' $L/input.sh
grep -q 'bt_start' $L/input.sh || {
    sed -i 's|^\(    t "input: touch $REAL -> $KFB_TOUCH, keys $KFB_KEYS_VIRT"\)$|\1\n    type bt_start >/dev/null 2>\&1 \&\& bt_start|' $L/input.sh
    sed -i 's|^\(            t "input: single-touch goodix -> raw $KFB_TOUCH (gestures disabled)"\)$|\1\n            type bt_start >/dev/null 2>\&1 \&\& bt_start|' $L/input.sh
}
grep -q 'bt_release' $L/input.sh || sed -i 's|^input_release() { |input_release() { type bt_release >/dev/null 2>\&1 \&\& bt_release; |; s|^input_grab()    { |input_grab()    { type bt_grab >/dev/null 2>\&1 \&\& bt_grab; |; s|^input_stop()    { |input_stop()    { type bt_stop >/dev/null 2>\&1 \&\& bt_stop; |' $L/input.sh
grep -q 'KFB_BTKEYS' $L/container.sh || sed -i 's|^\(    \[ -n "$PTKEYS" \] .*kindle-input-pt\)$|\1\n    [ -n "$KFB_BTKEYS" ] \&\& [ -c "$KFB_BTKEYS" ] \&\& { touch $R/dev/input/event3; mount --bind "$KFB_BTKEYS" $R/dev/input/event3; }   # 蓝牙翻页器桥 veradroid-btkeys（bt.sh）|' $L/container.sh
grep -q 'bt_cmd' $L/cmd.sh || sed -i 's|^\(        poke)    lipc-set-prop\)|        bt)      type bt_cmd >/dev/null 2>\&1 \&\& bt_cmd $a ;;\n\1|' $L/cmd.sh
for f in input.sh container.sh cmd.sh bt.sh; do sh -n $L/$f || { echo "syntax error in $f, restoring"; for g in input.sh container.sh cmd.sh; do cp -p $L/$g.bak-bt-$ts $L/$g; done; exit 1; }; done
echo "hooks: $(grep -c 'bt_' $L/input.sh) in input.sh, $(grep -c KFB_BTKEYS $L/container.sh) in container.sh, $(grep -c bt_cmd $L/cmd.sh) in cmd.sh; backups *.bak-bt-$ts"
