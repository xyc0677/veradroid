#!/bin/sh
# Manual "立即优化" (host/pw6 + host/k49 boot/lib/compile.sh) and the per-boot odex prefault, no device needed:
#  m1. compile all with nothing to do writes an idle status with a message at once (the settings page used to sit on
#      "已排队，正在开始…" forever) and leaves no manual flag behind.
#  m2. compile all also queues installed apps whose compiled code Android says is not usable (stale), and writes a
#      status before the worker runs.
#  m3. manual start skips the "wait until idle" gate (needidle + the device in use) and the in-use memory threshold;
#      without the manual flag the same state waits (automatic behaviour unchanged).
#  m4. manual start keeps the battery and foreground gates: low battery pauses, the foreground app is not compiled.
#  m5. a queued app running in the background: manual start asks Android to end it at once (no 10 min idle wait), and on
#      a 1 GB device compiles it anyway once am kill could not end it (Feeder's forced compile waited forever);
#      on a 512 MB device it keeps waiting. Without the manual flag nothing is killed while the device is in use.
#  m6. a sleeping worker wakes up early when compile.wake appears (compile_nap).
#  m7. a busy abort during a manual start ends the manual start (no start/stop loop).
#  a1. automatic mode: a queued app am kill could not end is compiled after COMPILE_RUNWAIT_S on 1 GB (no endless wait);
#      512 MB keeps waiting with the hint; before any am kill nothing is compiled.
#  b1-b7. very large apps (dex >= COMPILE_BIG_DEX_MB, or a speed timeout on record): never speed; speed-profile once there is
#      a JIT profile, left at the install's odex without one; not started while another app is in front, stopped when one comes.
#  p0. the prefault runs before compile_watch's one-minute gate; stop.sh clears the per-boot markers.
#  p1. prefault skipped for memory is retried on a later watchdog round (not marked done), and each odex is read once.
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
C=host/pw6/boot/lib/compile.sh
cmp -s host/pw6/boot/lib/compile.sh host/k49/boot/lib/compile.sh || fail "host/k49 compile.sh differs from host/pw6"
sh -n $C || fail "syntax $C"
C=$(pwd)/$C
# static: the prefault runs right after boot, before compile_watch's one-minute gate
wt=$(sed -n '/^compile_watch()/,/^}/p' $C)
lp=$(printf '%s\n' "$wt" | grep -n '^ *compile_prefault' | head -n 1 | cut -d: -f1)
lg=$(printf '%s\n' "$wt" | grep -n -- '-ge 60 ]' | head -n 1 | cut -d: -f1)
[ -n "$lp" ] && [ -n "$lg" ] && [ "$lp" -lt "$lg" ] || fail "p0: compile_prefault waits for the one-minute compile gate"
# static: stopping Android clears the per-boot prefault / manual markers ($W is tmpfs and outlives an Android restart;
# a prefault.d left behind made the next boot skip every odex -- found on a test device)
for sf in host/pw6/boot/stop.sh host/k49/boot/stop.sh; do
    for m in prefault.done prefault.d compile.manual compile.wake 'compile.mkill.\*'; do
        grep -q "rm -rf .*\$W/$m" $sf || fail "p0: $sf does not clear \$W/$m"
    done
done

T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/bin $T/w $T/x/ctl $T/sys/data/system
for p in com.tencent.weread.eink com.nononsenseapps.feeder com.duokan.einkreader; do mkdir -p $T/sys/data/app/$p-1/oat/arm; done
printf 'com.tencent.weread.eink 10020 0\ncom.nononsenseapps.feeder 10013 0\ncom.duokan.einkreader 10015 0\n' > $T/sys/data/system/packages.list
echo 1000 > $T/up
cat > $T/bin/cut <<EOF
#!/bin/sh
case "\$*" in *"/proc/uptime"*) cat $T/up; exit 0 ;; esac
exec $(command -v cut) "\$@"
EOF
if [ "$(uname -s)" != Linux ]; then # BSD sed -i (and no GNU \| in basic regexps) / stat -c / date -r
    cat > $T/bin/sed <<'EOF'
#!/bin/sh
[ "$1" = -i ] || exec /usr/bin/sed "$@"
e=$(printf '%s' "$2" | /usr/bin/sed -e 's/\\(/(/g; s/\\)/)/g; s/\\|/|/g')
exec /usr/bin/sed -i '' -E "$e" "$3"
EOF
    cat > $T/bin/stat <<EOF
#!/bin/sh
[ "\$1" = -c ] && [ "\$2" = %Y ] && { shift 2; exec /usr/bin/stat -f %m "\$@"; }
exec /usr/bin/stat "\$@"
EOF
fi
chmod +x $T/bin/*
st() { sed -n "s/^$1=//p" $T/x/ctl/compile.status 2>/dev/null | head -n 1; }
# run <snippet>: source compile.sh with Android / proc stubbed. Knobs (files in $T): filter.<pkg> (odex filter),
# running.<pkg>, fg, active (device in use), batt_low, mem (MB available), stale (dumpsys lines "pkg status"), lowmem
run() {
    PATH=$T/bin:$PATH W=$T/w X=$T/x SYS=$T/sys T=$T LOG=$T/hostlog timeout 60 sh -c '
        . "$0" 2>/dev/null
        t() { echo "$*" >> $T/log; }
        alive() { case "$1" in $W/pid) return 0 ;; $W/compile.pid) [ -f $T/worker ] ;; *) return 1 ;; esac; }
        lowmem_ram() { [ -f $T/lowmem ]; }
        adb_sh() { echo "$*" >> $T/adb; case "$*" in "am kill "*) [ -f $T/killable ] && rm -f $T/running.${*#am kill } ;; esac; return 0; }
        adb_sh_t() { shift; adb_sh "$@"; }
        sleep() { echo $(( $(cat $T/up) + 2 )) > $T/up; echo x >> $T/naps; [ "$(grep -c . $T/naps)" -ge 80 ] && exit 3; command sleep 0.01; }
        pgrep() { return 1; }
        compile_fg() { cat $T/fg 2>/dev/null; }
        compile_session_pending() { return 1; }; compile_held() { return 1; }
        compile_idle_s() { [ -f $T/active ] && echo 5 || echo 999999; }
        compile_charging() { return 1; }
        compile_battery_ok() { [ ! -f $T/batt_low ]; }
        compile_app_running() { [ -f $T/running.$1 ]; }
        mem_avail_mb() { cat $T/mem 2>/dev/null || echo 500; }
        odex_filter() { cat $T/filter.$1 2>/dev/null; }
        compile_stale_pkgs() { cat $T/stale 2>/dev/null; }
        compile_kick() { [ -s $CQ ] && echo kick >> $T/kicks; }
        compile_one() { echo "$1 ${3:-}" >> $T/compiled; echo "$1 $2" >> $T/modes; echo "$1 ok 1" > $W/compile.last; }
        compile_dex_mb() { [ -f $T/dex.$1 ] && cat $T/dex.$1 || echo 10; }
        compile_has_profile() { [ -f $T/prof.$1 ]; }
        compile_restore_next() { return 1; }
        '"$1" $C
}
reset() { rm -rf $T/emptied $T/odex.after $T/preempted $T/log $T/adb $T/kicks $T/compiled $T/modes $T/dex.* $T/prof.* $T/sys/data/.vd-compile.log $T/naps $T/fg $T/active $T/batt_low $T/mem $T/stale $T/lowmem $T/killable $T/worker $T/running.* $T/filter.* $T/w/* $T/x/ctl/* 2>/dev/null; echo 1000 > $T/up; }
allspeed() { for p in com.tencent.weread.eink com.nononsenseapps.feeder com.duokan.einkreader; do echo speed > $T/filter.$p; done; }

# m1
reset; allspeed
run 'compile_cmd all'
[ "$(st state)" = idle ] || fail "m1: state '$(st state)' after compile all with nothing to do, want idle"
[ "$(st code)" = nothing ] || fail "m1: code '$(st code)', want nothing"
[ -n "$(st msg)" ] || fail "m1: no msg"
[ -f $T/w/compile.manual ] && fail "m1: manual flag left behind"
[ -f $T/kicks ] && fail "m1: worker started with nothing queued"
echo "m1 ok: nothing to do -> idle + msg at once"

# m2
reset; allspeed; echo "com.duokan.einkreader NeedsRelocation" > $T/stale
run 'compile_cmd all'
grep -qx "com.duokan.einkreader stale" $T/w/compile.queue || fail "m2: stale app not queued: $(cat $T/w/compile.queue 2>/dev/null)"
[ "$(st code)" = starting ] || fail "m2: code '$(st code)' after compile all, want starting"
[ "$(st manual)" = 1 ] || fail "m2: status without manual=1"
[ -s $T/kicks ] || fail "m2: worker not started"
[ -n "$(st time)" ] || fail "m2: status without time="
echo "m2 ok: stale apps queued, status written before the worker runs"

# m3: needidle + in use + 250 MB (in-use threshold 400, idle 180)
reset; : > $T/active; echo 250 > $T/mem; : > $T/w/compile.needidle; echo com.tencent.weread.eink > $T/w/compile.queue
run 'compile_worker'
[ -f $T/compiled ] && fail "m3: automatic compile started while the device is in use after a busy abort"
[ "$(st code)" = busy ] || fail "m3: automatic wait code '$(st code)', want busy"
reset; : > $T/active; echo 250 > $T/mem; : > $T/w/compile.needidle; echo com.tencent.weread.eink > $T/w/compile.queue
date +%s > $T/w/compile.manual
run 'compile_worker'
grep -q '^com.tencent.weread.eink' $T/compiled 2>/dev/null || fail "m3: manual start did not compile: $(cat $T/x/ctl/compile.status)"
[ "$(grep -c . $T/naps 2>/dev/null || echo 0)" -le 2 ] || fail "m3: manual start waited ($(grep -c . $T/naps) naps)"
[ -f $T/w/compile.manual ] && fail "m3: manual flag not cleared after the batch"
[ "$(st state)" = idle ] || fail "m3: state '$(st state)' after the batch"
echo "m3 ok: manual start skips the idle / in-use waits; automatic still waits"

# m3b: manual start still needs the idle memory threshold
reset; : > $T/active; echo 120 > $T/mem; echo com.tencent.weread.eink > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'compile_worker'
[ -f $T/compiled ] && fail "m3b: manual start compiled with 120 MB available"
[ "$(st code)" = memory ] || fail "m3b: code '$(st code)' with 120 MB, want memory"
echo "m3b ok: manual start keeps the memory check"

# m4: battery + foreground
reset; : > $T/batt_low; echo com.tencent.weread.eink > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'compile_worker'
[ -f $T/compiled ] && fail "m4: compiled on low battery"
[ "$(st code)" = battery ] || fail "m4: code '$(st code)' on low battery"
reset; echo com.tencent.weread.eink > $T/fg; echo com.tencent.weread.eink > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'compile_worker'
[ -f $T/compiled ] && fail "m4: compiled the foreground app"
[ "$(st code)" = foreground ] || fail "m4: code '$(st code)' for the foreground app"
echo "m4 ok: manual start keeps the battery and foreground checks"

# m5: background-resident app
F=com.nononsenseapps.feeder
reset; : > $T/active; : > $T/running.$F; echo "$F force" > $T/w/compile.queue
run 'compile_worker'
grep -q "am kill" $T/adb 2>/dev/null && fail "m5: automatic mode killed an app while the device is in use"
[ -f $T/compiled ] && fail "m5: automatic mode compiled a running app"
reset; : > $T/active; : > $T/running.$F; : > $T/killable; echo "$F force" > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'compile_worker'
grep -q "am kill $F" $T/adb || fail "m5: manual start did not ask Android to end $F"
grep -qx "$F force" $T/compiled || fail "m5: $F not compiled after it ended"
echo "m5a ok: manual start ends a background app at once and compiles it"
reset; : > $T/active; : > $T/running.$F; echo "$F force" > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'COMPILE_MANUAL_RUNWAIT_S=0; compile_worker'
grep -qx "$F force" $T/compiled || fail "m5: 1 GB: app that would not end never compiled (forced compile waits forever)"
grep -q "compiling it anyway" $T/log || fail "m5: not logged"
reset; : > $T/lowmem; : > $T/running.$F; echo "$F force" > $T/w/compile.queue; date +%s > $T/w/compile.manual
run 'COMPILE_MANUAL_RUNWAIT_S=0; compile_worker'
[ -f $T/compiled ] && fail "m5: 512 MB: compiled an app that is still running"
[ "$(st code)" = running ] || fail "m5: 512 MB: code '$(st code)', want running"
case "$(st msg)" in *划掉*) ;; *) fail "m5: 512 MB: msg does not say how to start now: $(st msg)" ;; esac
echo "m5b ok: 1 GB compiles a background app that would not end; 512 MB waits and says how to start now"

# m6: compile_nap returns early on compile.wake
reset
run 'sleep() { echo x >> $T/naps; [ "$(grep -c . $T/naps)" = 3 ] && : > $W/compile.wake; command sleep 0.01; }; compile_nap 300'
[ "$(grep -c . $T/naps)" = 3 ] || fail "m6: compile_nap slept $(grep -c . $T/naps) times, want 3 (woken after the third)"
[ -f $T/w/compile.wake ] && fail "m6: wake flag not consumed"
echo "m6 ok: a waiting worker wakes on compile.wake"

# m7: a busy abort during a manual start ends the manual start
reset; date +%s > $T/w/compile.manual; mkdir -p $T/sys/data/app/com.tencent.weread.eink-1; echo Failure > $T/out
run '. "$0" >/dev/null 2>&1      # the real compile_one (run stubs it)
     t() { echo "$*" >> $T/log; }; odex_filter() { :; }; compile_held() { return 1; }; compile_app_running() { return 1; }
     compile_babysit() { echo busy > $W/compile.abort; }; adb_sh() { echo "$*" >> $T/adb; return 0; }
     compile_one com.tencent.weread.eink speed force'
[ -f $T/w/compile.manual ] && fail "m7: manual flag kept after a busy abort"
[ -f $T/w/compile.needidle ] || fail "m7: needidle not set"
grep -q "manual start ended" $T/log || fail "m7: not logged"
echo "m7 ok: busy abort ends the manual start"

# a1: automatic (no manual flag): am kill already tried and the app is still in the background
F=com.nononsenseapps.feeder
reset; : > $T/running.$F; echo "$F force" > $T/w/compile.queue; : > $T/w/compile.mkill.$F
run 'COMPILE_RUNWAIT_S=0; compile_worker'
grep -qx "$F force" $T/compiled || fail "a1: automatic forced compile still waits for a background app am kill could not end"
reset; : > $T/running.$F; echo "$F force" > $T/w/compile.queue; : > $T/active
run 'COMPILE_RUNWAIT_S=0; compile_worker'
[ -f $T/compiled ] && fail "a1: automatic mode compiled a running app before any am kill"
[ "$(st code)" = running ] || fail "a1: code '$(st code)', want running"
reset; : > $T/lowmem; : > $T/running.$F; echo "$F force" > $T/w/compile.queue; : > $T/w/compile.mkill.$F
run 'COMPILE_RUNWAIT_S=0; compile_worker'
[ -f $T/compiled ] && fail "a1: 512 MB: automatic mode compiled a running app"
case "$(st msg)" in *划掉*) ;; *) fail "a1: 512 MB: no hint: $(st msg)" ;; esac
echo "a1 ok: automatic forced compile no longer waits forever (1 GB), 512 MB waits with the hint"

# b1: 243 MB dex, no profile, odex from the install -> not queued, a forced entry is skipped (no speed, no interpret-only)
D=com.duokan.einkreader
reset; echo 243 > $T/dex.$D; echo interpret-only > $T/filter.$D
run 'compile_needed '$D && fail "b1: large app without a profile still needs a compile"
run '[ "$(compile_pkg_mode '$D' speed)" = interpret-only ]' || fail "b1: mode for a large app without profile is not interpret-only"
echo "$D force" > $T/w/compile.queue
run 'compile_worker'
[ -f $T/compiled ] && fail "b1: large app compiled without a profile: $(cat $T/modes)"
grep -q "is large (243 MB dex), kept at interpret-only" $T/log || fail "b1: not logged: $(cat $T/log)"
echo "b1 ok: large app without a JIT profile left at its install odex"

# b2: large app with a profile -> speed-profile; compile all queues it
reset; echo 243 > $T/dex.$D; echo interpret-only > $T/filter.$D; : > $T/prof.$D; echo speed > $T/filter.com.tencent.weread.eink; echo speed > $T/filter.$F
run 'compile_cmd all'
grep -q "^$D" $T/w/compile.queue || fail "b2: large app with a profile not queued"
run 'compile_worker'
grep -qx "$D speed-profile" $T/modes || fail "b2: large app compiled with '$(cat $T/modes 2>/dev/null)', want speed-profile"
echo speed-profile > $T/filter.$D
run 'compile_needed '$D && fail "b2: speed-profile odex of a large app still needs a compile"
echo "b2 ok: large app with a profile compiled speed-profile, then done"

# b3: small dex but speed timed out before (any install) -> speed-profile; a speed-profile timeout too -> interpret-only
reset; echo 30 > $T/dex.$D; : > $T/prof.$D; echo "$D $D-0 timeout speed 543s 20261119-112320" > $T/sys/data/.vd-compile.log
run '[ "$(compile_pkg_mode '$D' speed)" = speed-profile ]' || fail "b3: speed timeout history not used"
echo "$D $D-0 timeout speed-profile 541s 20261119-122320" >> $T/sys/data/.vd-compile.log
run '[ "$(compile_pkg_mode '$D' speed)" = interpret-only ]' || fail "b3: speed-profile timeout history not used"
run '[ "$(compile_pkg_mode com.tencent.weread.eink speed)" = speed ]' || fail "b3: normal app no longer speed"
echo "b3 ok: timeout history picks a lighter filter, normal apps keep speed"

# b4: large app not started while another app is in front; with Home in front it starts
reset; echo 243 > $T/dex.$D; : > $T/prof.$D; echo com.tencent.weread.eink > $T/fg; echo "$D force" > $T/w/compile.queue
run 'compile_worker'
[ -f $T/compiled ] && fail "b4: large app compiled while WeRead is in front"
[ "$(st code)" = large ] || fail "b4: code '$(st code)', want large"
reset; echo 243 > $T/dex.$D; : > $T/prof.$D; echo com.veradroid.home > $T/fg; echo "$D force" > $T/w/compile.queue
run 'compile_worker'
grep -qx "$D speed-profile" $T/modes || fail "b4: large app not compiled with Home in front"
echo "b4 ok: large app waits while another app is in front"

# b5: an app comes to the front while a large app is being compiled -> stopped (busy)
reset; echo 243 > $T/dex.$D; echo com.tencent.weread.eink > $T/fg
run 'compile_preempt_pkg() { echo "$1" >> $T/preempted; kill $cp; return 0; }
     command sleep 20 & cp=$!; compile_babysit $cp '$D 2>/dev/null
grep -qx "$D" $T/preempted 2>/dev/null || fail "b5: large compile not stopped when an app came to the front"
[ "$(cat $T/w/compile.abort 2>/dev/null)" = busy ] || fail "b5: abort reason '$(cat $T/w/compile.abort 2>/dev/null)', want busy"
reset; echo 10 > $T/dex.$D; echo com.tencent.weread.eink > $T/fg
run 'compile_preempt_pkg() { echo "$1" >> $T/preempted; return 0; }
     COMPILE_MAX_S=100000; command sleep 1 & cp=$!; compile_babysit $cp '$D
[ -f $T/preempted ] && fail "b5: a normal compile was stopped because an app is in front"
echo "b5 ok: large compile stopped when an app comes to the front"

# b6: a large app on compile.restore (its compile was stopped) is restored with speed-profile when it has a profile,
#     and not at all while another app is in front
reset; echo 243 > $T/dex.$D; : > $T/prof.$D; echo "$D" > $T/w/compile.restore; echo com.tencent.weread.eink > $T/fg
mkdir -p $T/sys/data/misc/profiles/cur/0/$D; echo p > $T/sys/data/misc/profiles/cur/0/$D/primary.prof
run '. "$0" >/dev/null 2>&1; t() { echo "$*" >> $T/log; }; odex_filter() { :; }; compile_app_running() { return 1; }
     compile_fg() { cat $T/fg; }; compile_dex_mb() { echo 243; }; compile_has_profile() { return 0; }; lowmem_ram() { return 1; }
     compile_user_active() { return 1; }; compile_charging() { return 1; }
     adb_sh() { echo "$*" >> $T/adb; }; compile_babysit() { :; }; compile_restore_next' && fail "b6: restored a large app while WeRead is in front"
echo com.veradroid.home > $T/fg
run '. "$0" >/dev/null 2>&1; t() { echo "$*" >> $T/log; }; odex_filter() { :; }; compile_app_running() { return 1; }
     compile_fg() { cat $T/fg; }; compile_dex_mb() { echo 243; }; compile_has_profile() { return 0; }; lowmem_ram() { return 1; }
     compile_user_active() { return 1; }; compile_charging() { return 1; }
     adb_sh() { echo "$*" >> $T/adb; }; compile_babysit() { :; }; compile_restore_next' || fail "b6: large app not restored with Home in front"
grep -q "compile -m speed-profile -f $D" $T/adb || fail "b6: restore did not use speed-profile: $(cat $T/adb)"
# the restore reached the app's target filter: the re-queued entry is dropped (no second 270 s compile)
echo "$D force" > $T/w/compile.queue; echo "$D" > $T/w/compile.restore; rm -f $T/odex.after
run '. "$0" >/dev/null 2>&1; t() { echo "$*" >> $T/log; }; odex_filter() { [ -f $T/odex.after ] && echo speed-profile; }; compile_app_running() { return 1; }
     compile_fg() { cat $T/fg; }; compile_dex_mb() { echo 243; }; compile_has_profile() { return 0; }; lowmem_ram() { return 1; }
     compile_user_active() { return 1; }; compile_charging() { return 1; }
     adb_sh() { echo "$*" >> $T/adb; }; compile_babysit() { : > $T/odex.after; }; compile_restore_next'
grep -q "^$D" $T/w/compile.queue 2>/dev/null && fail "b6: re-queued entry kept after the restore reached speed-profile"
rm -rf $T/sys/data/misc
# b7: a stopped speed-profile compile empties the JIT profiles (installd merges cur into ref first): they are put back
reset; mkdir -p $T/sys/data/misc/profiles/cur/0/$D $T/sys/data/misc/profiles/ref/$D
echo curprof > $T/sys/data/misc/profiles/cur/0/$D/primary.prof; echo refprof > $T/sys/data/misc/profiles/ref/$D/primary.prof
run '. "$0" >/dev/null 2>&1; t() { echo "$*" >> $T/log; }; odex_filter() { :; }
     compile_babysit() { [ -f $T/emptied ] && return 0; : > $T/emptied; : > $SYS/data/misc/profiles/cur/0/'$D'/primary.prof; : > $SYS/data/misc/profiles/ref/'$D'/primary.prof; }
     adb_sh() { :; }; compile_light '$D' timeout'
[ "$(cat $T/sys/data/misc/profiles/cur/0/$D/primary.prof)" = curprof ] || fail "b7: current profile not put back"
[ "$(cat $T/sys/data/misc/profiles/ref/$D/primary.prof)" = refprof ] || fail "b7: reference profile not put back"
ls $T/w/compile.prof.* >/dev/null 2>&1 && fail "b7: saved profile copies left behind"
rm -rf $T/sys/data/misc
echo "b7 ok: profiles emptied by a stopped speed-profile compile are put back"
echo "b6 ok: stopped large app restored with speed-profile, not while another app is in front"

# p1: prefault retried after a memory skip
reset; rm -rf $T/w/prefault.d $T/log
for p in com.tencent.weread.eink com.duokan.einkreader; do dd if=/dev/zero of=$T/sys/data/app/$p-1/oat/arm/base.odex bs=1024 count=70 2>/dev/null; done
echo 300 > $T/mem
run 'APP_PREFAULT_MB=0; compile_prefault; wait'
[ -f $T/w/prefault.done ] && fail "p1: prefault marked done although memory was short"
grep -q prefault: $T/log 2>/dev/null && fail "p1: prefault read files with 300 MB"
echo 500 > $T/mem
run 'APP_PREFAULT_MB=0; compile_prefault; wait'
[ -f $T/w/prefault.done ] || fail "p1: prefault not done on the retry"
[ "$(grep -c prefault: $T/log)" = 2 ] || fail "p1: $(grep -c prefault: $T/log) files read, want 2"
run 'APP_PREFAULT_MB=0; compile_prefault; wait'
[ "$(grep -c prefault: $T/log)" = 2 ] || fail "p1: files read again after done"
echo "p1 ok: prefault retried after a memory skip, each odex read once"

# b8: host without unzip: large base.apk (>= COMPILE_BIG_APK_MB) still detected as big, and on 512 MB an existing
#     verify-at-runtime odex is never upgraded to speed (both pw6/k49 and legacy)
mkdir -p $T/minbin
for u in sh awk sed head wc stat tr cut date grep tail sort cat rm mv cp mkdir; do
    up=$(command -v $u 2>/dev/null) && [ -x "$up" ] && ln -sf "$up" $T/minbin/$u
done
for lc in $C $(pwd)/host/legacy/boot/lib/compile.sh; do
    reset; mkdir -p $T/sys/data/app/com.big.nounzip-1
    dd if=/dev/zero of=$T/sys/data/app/com.big.nounzip-1/base.apk bs=1048576 count=2 2>/dev/null
    out=$(PATH=$T/bin:$T/minbin W=$T/w SYS=$T/sys COMPILE_BIG_APK_MB=2 sh -c '
        . "$0" 2>/dev/null
        command -v unzip >/dev/null 2>&1 && exit 9
        lowmem_ram() { return 0; }
        odex_filter() { echo interpret-only; }
        echo "$(compile_dex_mb com.big.nounzip) $(compile_pkg_mode com.big.nounzip speed)"' "$lc")
    [ "$out" = "120 verify-at-runtime" ] || fail "b8 ($lc): big apk without unzip -> '$out', want '120 verify-at-runtime'"
    echo tiny > $T/sys/data/app/com.big.nounzip-1/base.apk
    rm -f $T/w/compile.dexmb.com.big.nounzip
    out=$(PATH=$T/bin:$T/minbin W=$T/w SYS=$T/sys sh -c '
        . "$0" 2>/dev/null
        command -v unzip >/dev/null 2>&1 && exit 9
        lowmem_ram() { return 0; }
        odex_filter() { echo verify-at-runtime; }
        compile_needed com.big.nounzip && echo need || echo keep
        compile_pkg_mode com.big.nounzip speed' "$lc" | tr '\n' ' ')
    [ "$out" = "keep verify-at-runtime " ] || fail "b8 ($lc): verify-at-runtime without unzip -> '$out', want 'keep verify-at-runtime '"
done
rm -rf $T/sys/data/app/com.big.nounzip-1 $T/minbin
echo "b8 ok: missing unzip falls back to APK size and preserves verify-at-runtime on 512 MB"

# b9: 512 MB large app already at verify-at-runtime: non-kOatUpToDate status (Needed / kOatNeeded / BootImageOutOfDate)
#     is NOT re-queued by compile_scan or compile_cmd all, while invalid and NeedsRelocation / kOatNeedsRelocation ARE
D=com.duokan.einkreader
reset; allspeed; : > $T/lowmem; echo 243 > $T/dex.$D; echo verify-at-runtime > $T/filter.$D
echo "$D Needed" > $T/stale
run 'compile_scan'
[ -s $T/w/compile.queue ] && fail "b9 (pw6): Needed verify-at-runtime was queued by compile_scan: $(cat $T/w/compile.queue)"
run 'compile_cmd all'
[ -s $T/w/compile.queue ] && fail "b9 (pw6): Needed verify-at-runtime was queued by compile_cmd all"
[ "$(st code)" = nothing ] || fail "b9 (pw6): compile_cmd all code '$(st code)', want nothing"
for bad in invalid NeedsRelocation kOatNeedsRelocation; do
    reset; allspeed; : > $T/lowmem; echo 243 > $T/dex.$D; echo verify-at-runtime > $T/filter.$D
    echo "$D $bad" > $T/stale
    run 'compile_scan'
    grep -qx "$D stale" $T/w/compile.queue || fail "b9 (pw6): $bad verify-at-runtime not queued: $(cat $T/w/compile.queue 2>/dev/null)"
done
# legacy compile_scan as well
LC=$(pwd)/host/legacy/boot/lib/compile.sh
for st_case in "kOatNeeded:skip" "kOatBootImageOutOfDate:skip" "invalid:queue" "kOatNeedsRelocation:queue"; do
    st_v=${st_case%:*}; st_want=${st_case#*:}
    reset; allspeed; echo verify-at-runtime > $T/filter.$D
    PATH=$T/bin:$PATH W=$T/w X=$T/x SYS=$T/sys T=$T D=$D ST=$st_v sh -c '
        . "$0" 2>/dev/null
        t() { echo "$*" >> $T/log; }
        lowmem_ram() { return 0; }
        odex_filter() { cat $T/filter.$1 2>/dev/null; }
        compile_dex_mb() { [ "$1" = "$D" ] && echo 243 || echo 10; }
        compile_fix_invalid() { :; }
        compile_stale_pkgs() { echo "$D $ST"; }
        compile_kick() { :; }
        compile_scan' "$LC"
    if [ "$st_want" = skip ]; then
        [ -s $T/w/compile.queue ] && fail "b9 (legacy): $st_v verify-at-runtime was queued: $(cat $T/w/compile.queue)"
    else
        grep -qx "$D force" $T/w/compile.queue || fail "b9 (legacy): $st_v verify-at-runtime not force-queued: $(cat $T/w/compile.queue 2>/dev/null)"
    fi
done
echo "b9 ok: verify-at-runtime not endlessly re-queued on Needed, still repaired on invalid / NeedsRelocation"

echo "compile-manual: ok"
