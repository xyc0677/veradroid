#!/bin/sh
# Per-app display profiles (host/*/boot/lib/appprof.sh, boot/appprofiles.conf, the fg / appset commands in cmd.sh):
# the three hosts carry the same files, the built-in table is well-formed, and the lookup / save logic behaves.
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
for h in k49 legacy; do
    cmp -s host/pw6/boot/lib/appprof.sh host/$h/boot/lib/appprof.sh || fail "host/$h/boot/lib/appprof.sh differs from host/pw6"
    cmp -s host/pw6/boot/appprofiles.conf host/$h/boot/appprofiles.conf || fail "host/$h/boot/appprofiles.conf differs from host/pw6"
done
for h in pw6 k49 legacy; do
    c=host/$h/boot/lib/cmd.sh
    grep -q '\. "\$VD/boot/lib/appprof.sh"' $c || fail "$c does not source appprof.sh"
    grep -q 'fg)  *type appprof_fg' $c || fail "$c has no fg command"
    grep -q 'appset)  *type appprof_set' $c || fail "$c has no appset command"
    grep -q 'appprof_publish' host/$h/boot/veradroid.sh || fail "host/$h/boot/veradroid.sh does not publish the table"
    sh -n host/$h/boot/lib/appprof.sh || fail "syntax host/$h/boot/lib/appprof.sh"
done
# every rule: "<pkg>[*] KEY=v[;KEY=v]" with keys the host accepts, one rule per package
. host/pw6/boot/lib/appprof.sh
bad=$(grep -v '^[[:space:]]*\(#\|$\)' host/pw6/boot/appprofiles.conf | sed 's/[[:space:]]*#.*//' | while read -r p s rest; do
    [ -n "$rest" ] && { echo "extra field: $p"; continue; }
    pp=${p%\*}; appprof_valid_pkg "$pp" || { echo "bad package: $p"; continue; }
    printf '%s\n' "$s" | tr ';' '\n' | while read -r kv; do printf '%s\n' "$kv" | grep -Eq "$APPPROF_RE|^DENSITY=(auto1080|[1-6][0-9][0-9])\$" || echo "bad setting $p: $kv"; done
done)
[ -z "$bad" ] || fail "appprofiles.conf: $bad"
dup=$(grep -v '^[[:space:]]*\(#\|$\)' host/pw6/boot/appprofiles.conf | awk '{print $1}' | sort | uniq -d)
[ -z "$dup" ] || fail "appprofiles.conf: duplicate $dup"
# behaviour, with the display bridge / settings mocked
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/vd/boot $T/sys $T/w/kfb
cp host/pw6/boot/appprofiles.conf $T/vd/boot/
out=$(VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb sh -c '
    t() { :; }; settings_load() { :; }; epd_send() { echo "EPD $*" >> $W/epd; }
    . host/pw6/boot/lib/appprof.sh
    appprof_init
    appprof_fg com.tencent.weread.eink; echo "A $(tr "\n" ";" < $SYS/settings.app)"
    : > $W/epd; appprof_fg com.duokan.reader; echo "B $(wc -l < $W/epd | tr -d " ")"            # same profile: nothing sent
    appprof_fg org.mozilla.firefox; echo "C $(tr "\n" ";" < $SYS/settings.app)"                  # prefix rule
    appprof_fg com.example.none; echo "D $([ -f $SYS/settings.app ] && echo app || echo global)"
    appprof_set "com.duokan.reader|KFB_MODE=2;KFB_X=\$(reboot);KFB_FLASH=3"
    echo "E $(cat $SYS/settings.apps)"
    appprof_set "org.mozilla.firefox|-"; appprof_fg org.mozilla.firefox; echo "F $([ -f $SYS/settings.app ] && echo app || echo global)"
    echo "G $(grep "^org.mozilla.firefox " $X/appprof)"
    appprof_set "com.duokan.reader|"; appprof_fg com.duokan.reader; echo "H $(tr "\n" ";" < $SYS/settings.app)"
    appprof_set "bad;pkg|KFB_MODE=1"; echo "I $(grep -c bad $SYS/settings.apps)"
    appprof_set "*|"; echo "J $(wc -c < $SYS/settings.apps | tr -d " ")"
    appprof_fg home; echo "K $([ -f $SYS/settings.app ] && echo app || echo global)"
    for p in com.douban.book.reader com.jjwxc.reader cn.duku com.eusoft.eudic com.pldx.engang; do appprof_fg $p; echo "L $p $(tr "\n" ";" < $SYS/settings.app)"; done
')
expect() { printf '%s\n' "$out" | grep -qxF "$1" || fail "expected '$1' in:
$out"; }
expect "A KFB_SMOOTH=3;KFB_FLASH=6;"
expect "B 0"
expect "C KFB_SCROLLDU=300;"
expect "D global"
expect "E com.duokan.reader KFB_MODE=2;KFB_FLASH=3"
expect "F global"
expect "G org.mozilla.firefox u -"
expect "H KFB_MODE=1;KFB_FLASH=6;"
expect "I 0"
expect "J 0"
expect "K global"
# e-ink reader apps that run on the 32-bit container
expect "L com.douban.book.reader KFB_MODE=1;KFB_FLASH=6;"
expect "L com.jjwxc.reader KFB_SMOOTH=2;KFB_FLASH=6;KFB_SCROLLDU=300;"
expect "L cn.duku KFB_MODE=1;KFB_FLASH=6;"
expect "L com.eusoft.eudic KFB_SCROLLDU=300;"
expect "L com.pldx.engang KFB_SCROLLDU=300;"
# display density (DENSITY=auto1080): IWindowManager mocked; one call per real change, reset on home, settings file scrubbed
for hw in 1272:376 1236:366 1072:317 1264:374 1448:429; do
    w=${hw%%:*}; d=${hw#*:}
    got=$(VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb WIDTH=$w sh -c '
        t() { :; }; . host/pw6/boot/lib/appprof.sh; rm -f $W/density.*
        appprof_wm() { case "$1" in 6) echo "Result: Parcel(00000000 00000001 $(printf %08x $WIDTH) 000006a0 '"'"'....'"'"')" ;; 10) echo "Result: Parcel(00000000 0000012c   '"'"'....'"'"')" ;; esac; }
        appprof_density_want com.duokan.einkreader')
    [ "$got" = "$d" ] || fail "auto1080 for width $w: got '$got', want $d"
done
rm -f $T/w/density.* $T/w/appprof.pkg
mkdir -p $T/sys/data/system/users/0
printf '%s\n' '<settings version="131">' '  <setting id="477" name="display_density_forced" value="377" package="android" />' '</settings>' > $T/sys/data/system/users/0/settings_secure.xml
dout=$(VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb sh -c '
    t() { :; }; settings_load() { :; }
    . host/pw6/boot/lib/appprof.sh
    appprof_wm() { echo "WM $*" >> $W/wm.log; case "$1" in 6) echo "Result: Parcel(00000000 00000001 000004f8 000006a0 '"'"'....'"'"')" ;; 10) echo "Result: Parcel(00000000 0000012c   '"'"'....'"'"')" ;; *) echo "Result: Parcel(00000000    '"'"'....'"'"')" ;; esac; }
    calls() { grep -c "^WM 1[23] " $W/wm.log 2>/dev/null || echo 0; }
    appprof_density_boot; echo "S $(grep -c "value=\"\"" $SYS/data/system/users/0/settings_secure.xml)"
    appprof_fg com.duokan.einkreader; echo "P $(cat $W/density.cur) $(calls)"
    appprof_fg com.duokan.reader; echo "Q $(cat $W/density.cur) $(calls)"
    appprof_fg com.tencent.weread.eink; echo "R $(cat $W/density.cur 2>/dev/null || echo default) $(calls)"
    appprof_fg home; echo "U $(calls)"
    appprof_fg com.duokan.reader; appprof_density_reset; appprof_density_reset; echo "V $(cat $W/density.cur 2>/dev/null || echo default) $(calls)"
    appprof_fg com.duokan.reader; echo "Y $(cat $W/density.cur) $(calls) $(cat $X/density.fg)"     # same package after a reset (thaw): density back
    echo "Z $(cat $X/density.apps 2>/dev/null | tr "\n" " ")"
')
out=$dout
expect "S 1"
expect "P 376 1"
expect "Q 376 1"
expect "R default 2"
expect "U 2"
expect "V default 4"
expect "Y 376 5 com.duokan.reader"
expect "Z com.duokan.reader com.duokan.einkreader "
# image with per-app settings (ro.vd.oec): level 1 (1.00's pw6-a8ffca4, no default-density fix) keeps the host's global
# density for the built-in DENSITY rules (多看) and leaves DENSITY out of appoec; level 2 hands density to the image
for lv in 1 2; do
    rm -rf $T/r $T/w/density.* $T/w/appprof.pkg $T/w/kfb/appoec $T/w/wm.log; mkdir -p $T/r/system
    echo "ro.vd.oec=$lv" > $T/r/system/build.prop
    oout=$(R=$T/r VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb sh -c '
        t() { :; }; settings_load() { :; }
        . host/pw6/boot/lib/appprof.sh
        appprof_wm() { echo "WM $*" >> $W/wm.log; case "$1" in 6) echo "Result: Parcel(00000000 00000001 000004f8 000006a0 '"'"'....'"'"')" ;; 10) echo "Result: Parcel(00000000 0000012c   '"'"'....'"'"')" ;; *) echo "Result: Parcel(00000000    '"'"'....'"'"')" ;; esac; }
        appprof_publish
        appprof_fg com.duokan.einkreader
        echo "O$(cat $W/density.cur 2>/dev/null || echo default)|$(tr "\n" " " < $X/density.apps)|$(grep "^com.duokan.einkreader " $X/appoec)"')
    case $lv in
        1) [ "$oout" = "O376|com.duokan.reader com.duokan.einkreader |com.duokan.einkreader OEC_TOAST=1" ] || fail "ro.vd.oec=1: $oout" ;;
        2) [ "$oout" = "Odefault||com.duokan.einkreader DENSITY=auto1080;OEC_TOAST=1" ] || fail "ro.vd.oec=2: $oout" ;;
    esac
done
echo "appprof: ok"
