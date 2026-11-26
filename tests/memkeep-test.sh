#!/bin/sh
# Memory / background-app management (host/*/boot/lib/memkeep.sh): lmkd thresholds for 1 GB models, the extra zram device,
# home / IME kept out of swap, timed background stop. Same file in the three hosts, wired into boot / watchdog / fg / stop.
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
for h in k49 legacy; do
    cmp -s host/pw6/boot/lib/memkeep.sh host/$h/boot/lib/memkeep.sh || fail "host/$h/boot/lib/memkeep.sh differs from host/pw6"
done
for h in pw6 k49 legacy; do
    b=host/$h/boot
    sh -n $b/lib/memkeep.sh || fail "syntax $b/lib/memkeep.sh"
    grep -q '^for m in settings memory memkeep ' $b/veradroid.sh || fail "$b/veradroid.sh does not source memkeep.sh"
    awk '/^lmk_tune\(\)/,/^}/' $b/lib/afterboot.sh | grep -q 'lmk_send' || fail "$h lmk_tune does not use lmk_send"
    awk '/^zram_on\(\)/,/^}/' $b/lib/memory.sh | grep -q 'zram_extra' || fail "$h zram_on does not call zram_extra"
    awk '/^zram_restore\(\)/,/^}/' $b/lib/memory.sh | grep -q 'mem_keep_off' || fail "$h zram_restore does not remove the cgroup"
    grep -q 'mem_keep_tick; bg_stop_tick; wifi_idle_tick' $b/lib/watchdog.sh || fail "$h watchdog does not tick memkeep"
    grep -q 'boot_block_apply ) &' $b/lib/afterboot.sh || fail "$h after_boot does not run boot_block_apply"
    grep -q '^    WIFI_IDLE_OFF_MIN=0 ' $b/lib/settings.sh || fail "$h: Wi-Fi idle off must default to off"
    grep -q 'appprof_oec_publish' $b/lib/appprof.sh && grep -q '^appknobs_join()' $b/lib/appprof.sh || fail "$h appprof_publish does not join appknobs.d"
    grep -q 'mem_fg "\$ap_pkg"' $b/lib/appprof.sh || fail "$h appprof_fg does not report fg to memkeep"
    for k in ZRAM_EXTRA LMK_MINFREE MEM_KEEP MEM_KEEP_APPS BG_FREEZE BG_STOP_MIN BG_STOP_APPS BOOT_BLOCK BOOT_ALLOW WIFI_IDLE_OFF_MIN WIFI_IDLE_BPS; do
        grep -q "^    $k=" $b/lib/settings.sh || fail "$h settings.sh has no default for $k"
    done
    grep -q '^    BG_FREEZE=0 ' $b/lib/settings.sh || fail "$h: background freeze must default to off (prevents TIME_TICK ANR/WAL loss)"
    grep -q '^    BG_STOP_MIN=0 ' $b/lib/settings.sh || fail "$h: timed background stop must default to off"
done
for h in pw6 k49; do
    [ -x host/$h/bin/vdlmk ] || { [ ! -d host/$h/bin ] && [ -f tools/src/vdlmk.c ]; } || fail "host/$h/bin/vdlmk missing (lmk_send needs it)"
    grep -q '^    lowmem_ram || ZRAM_MB=768' host/$h/boot/lib/settings.sh || fail "$h: 1 GB default zram is not 768"
done
M=host/pw6/boot/lib/memkeep.sh
grep -q '"2048:0 3072:100 4096:200 8192:300 14336:900 16384:906"' $M || fail "512 MB lmkd table changed"
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/w
out=$(W=$T/w sh -c '
    t() { echo "LOG $*"; }; adb_sh() { echo "ADB $*" >> $W/adb; }
    appprof_valid_pkg() { case "$1" in ""|*[!A-Za-z0-9._]*) return 1 ;; esac; }
    . host/pw6/boot/lib/memkeep.sh
    lowmem_ram() { return 1; }
    echo "A $(lmk_targets)"
    lowmem_ram() { return 0; }
    echo "B $(lmk_targets)"
    LMK_MINFREE=stock; lmk_targets || echo "C stock"
    LMK_MINFREE="4096:0 8192:906"; echo "D $(lmk_targets)"
    LMK_MINFREE="4096:0;reboot"; lmk_targets || echo "E bad"
    LMK_MINFREE="1:1 2:2 3:3 4:4 5:5 6:6 7:7"; lmk_targets || echo "F toomany"
    LMK_MINFREE=auto
    MEM_KEEP=auto; mem_keep_on || echo "G lowmem off"
    MEM_KEEP=on; mem_keep_on && echo "H on"
    MEM_KEEP=off; mem_keep_on || echo "I off"
    mem_keep_tick() { :; }
    date() { echo 1000; }
    mem_fg com.a.big; mem_fg home; mem_fg com.b.other
    echo "J $(cat $W/memfg.cur) $(tr "\n" ";" < $W/memfg.bg)"
    date() { echo 2000; }
    BG_STOP_MIN=0; BG_STOP_APPS=com.a.big; bg_stop_tick; echo "K $([ -f $W/adb ] && echo ran || echo none)"
    BG_STOP_MIN=10; BG_STOP_APPS="com.x,com.a.big"; bg_stop_tick
    echo "L $(cat $W/adb) | $(tr "\n" ";" < $W/memfg.bg)"
    : > $W/adb; mem_fg com.a.big; date() { echo 99999; }; BG_STOP_APPS=com.a.big; bg_stop_tick; echo "M $(wc -c < $W/adb | tr -d " ")"
' 2>&1)
expect() { printf '%s\n' "$out" | grep -qxF "$1" || fail "expected '$1' in:
$out"; }
expect "A 6144:0 8192:100 10240:200 12288:300 20480:900 24576:906"
expect "B 2048:0 3072:100 4096:200 8192:300 14336:900 16384:906"
expect "C stock"
expect "D 4096:0 8192:906"
expect "E bad"
expect "F toomany"
expect "G lowmem off"
expect "H on"
expect "I off"
expect "J com.b.other com.a.big 1000;"
expect "K none"
expect "L ADB am force-stop com.a.big | "
expect "M 0"
# per-app knobs (KEEPALIVE / BGSTOP via the merged table), boot receivers
mkdir -p $T/x $T/sys
cat > $T/x/appprof <<'TBL'
com.keep u KEEPALIVE=1;BGSTOP=1
com.five u BGSTOP=5
com.zero ub KFB_MODE=1;BGSTOP=0
com.zhangyue.* b KEEPALIVE=1
TBL
cat > $T/dump <<'DMP'
Receiver Resolver Table:
  Non-Data Actions:
      android.intent.action.BOOT_COMPLETED:
        52e76c5 com.douban.book.reader/androidx.work.impl.background.systemalarm.RescheduleReceiver
        59a1676 com.android.providers.media/.MediaScannerReceiver
        a04ba41 com.ichi2.anki/.services.BootService
        b04ba41 com.foo.clock/.Boot
        e56c81a com.tipsoon.android/.Boot
      android.intent.action.USER_PRESENT:
        4e75b0 com.eusoft.eudic/com.eusoft.dns.NetworkReceiver
      android.intent.action.LOCKED_BOOT_COMPLETED:
        1111111 com.tipsoon.android/.Boot
DMP
out2=$(W=$T/w X=$T/x SYS=$T/sys D=$T/dump sh -c '
    t() { echo "LOG $*"; }
    adb_sh() { case "$1" in "dumpsys package r receiver") cat $D ;; "pm list packages -3") printf "package:%s\n" com.douban.book.reader com.ichi2.anki com.foo.clock com.tipsoon.android com.eusoft.eudic ;;
               "dumpsys package com.douban.book.reader") printf "      disabledComponents:\\n        androidx.work.impl.background.systemalarm.RescheduleReceiver\\n      enabledComponents:\\n        x.Y\\n" ;;
               "pm disable "*) echo "$1" >> $W/pm; echo "Component {${1#pm disable }} new state: disabled" ;; "pm enable "*) echo "$1" >> $W/pm ;; "am force-stop "*) echo "$1" >> $W/adb ;; esac; }
    appprof_valid_pkg() { case "$1" in ""|*[!A-Za-z0-9._]*) return 1 ;; esac; }
    . host/pw6/boot/lib/memkeep.sh
    mem_knobs; echo "N $(cut -d"|" -f1 $X/appknobs.d/mem | tr "\n" ",")"
    echo "O $(mem_knob com.keep BGSTOP) $(mem_knob com.zero BGSTOP) $(mem_knob com.none BGSTOP)x $(mem_knob_pkgs KEEPALIVE 1 | tr "\n" ",")"
    rm -f $W/memfg.*; date() { echo 100; }
    mem_fg com.keep; mem_fg com.five; mem_fg com.zero; mem_fg com.other; mem_fg home
    date() { echo 400; }; BG_STOP_MIN=1; BG_STOP_APPS=com.other; bg_stop_tick; echo "P $(tr "\n" "," < $W/adb)"
    : > $W/adb; date() { echo 9999; }; bg_stop_tick; echo "Q $(tr "\n" "," < $W/adb)"
    echo "R $(boot_receivers | tr "\n" ",")"
    BOOT_BLOCK=1; boot_block_apply; echo "S $(tr "\n" "," < $SYS/bootblock.list)"
    : > $W/pm; boot_block_apply; echo "T $(wc -l < $SYS/bootblock.list | tr -d " ")"
    BOOT_BLOCK=0; : > $W/pm; boot_block_apply; echo "U $(tr "\n" "," < $W/pm) $([ -f $SYS/bootblock.list ] && echo kept || echo gone)"
    rm -f $W/memfg.* $W/sig; date() { echo 500; }
    bg_pkg_pids() { case "$1" in com.keep) echo 101 ;; com.five) echo "201 202" ;; com.other) echo 301 ;; esac; }
    kill() { echo "KILL $*" >> $W/sig; }
    BG_FREEZE=1; mem_fg com.keep; mem_fg com.five; mem_fg com.other
    bg_freeze_sweep
    echo "V0 $(tr "\n" ";" < $W/sig) | $(tr "\n" "," < $W/memfg.frozen 2>/dev/null)"
    date() { echo 520; }
    bg_freeze_sweep
    echo "V $(tr "\n" ";" < $W/sig) | $(tr "\n" "," < $W/memfg.frozen)"
    : > $W/sig; mem_fg com.five; date() { echo 540; }; bg_freeze_sweep
    echo "W $(tr "\n" ";" < $W/sig) | $(tr "\n" "," < $W/memfg.frozen)"
    : > $W/sig; bg_thaw_all
    echo "X $(tr "\n" ";" < $W/sig) | $([ -f $W/memfg.frozen ] && echo kept || echo gone)"
' 2>&1)
expect2() { printf '%s\n' "$out2" | grep -qxF "$1" || fail "expected '$1' in:
$out2"; }
expect2 "N KEEPALIVE,BGSTOP,"
expect2 "O 1 0 x com.keep,"
expect2 "P am force-stop com.five,am force-stop com.other,"
expect2 "Q "
expect2 "R com.android.providers.media/.MediaScannerReceiver,com.douban.book.reader/androidx.work.impl.background.systemalarm.RescheduleReceiver,com.foo.clock/.Boot,com.ichi2.anki/.services.BootService,com.tipsoon.android/.Boot,"
expect2 "S com.tipsoon.android/.Boot,"
expect2 "T 1"
expect2 "U pm enable com.tipsoon.android/.Boot, gone"
expect2 "V0  | "
expect2 "V KILL -STOP 201 202; | com.five,"
expect2 "W KILL -CONT 201 202;KILL -STOP 301; | com.other,"
expect2 "X KILL -CONT 301; | gone"
echo "memkeep-test: ok"
