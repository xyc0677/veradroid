#!/bin/sh
# Touch scroll mode, GCN / NEWWIN / MONO / MAXDEFER (display/epd.c) and touch boost / scroll hints (input/vinput.c):
# the bridge knows every command and option the hosts send, pw6 and k49 define, publish, accept and send every new key,
# per-app profiles accept the display keys, and NEWWIN goes out only on a real app change with KFB_NEWWIN=1.
# The behaviour of veradroid-input itself is in tests/vinput-test.sh (boost_scroll_cases, Linux only).
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
E=display/epd.c
for c in 'SCROLLDELAY' 'SCROLLSTYLE' 'SCROLL ' 'GCN' 'MONO' 'MAXDEFER' 'STATS' 'NEWWIN' 'TOUCH' 'FLIPINPUT' 'FLIPCOUNT' 'FAST' 'FREEZE'; do
    grep -q "strn\?cmp(line, \"$c\"" $E || fail "epd.c does not parse $c"
    grep -q "^ \*     $c" $E || fail "epd.c header does not document $c"
done
grep -q "line\[0\] == 'H' && line\[1\] == ' '" $E && grep -q '^ \*     H <c>' $E || fail "epd.c: no H hint"
# an epd from before these commands must ignore them: none of the new words may start with an older command word
for c in H FAST FREEZE TOUCH FLIPINPUT FLIPCOUNT SCROLL SCROLLDELAY SCROLLSTYLE GCN NEWWIN MONO MAXDEFER STATS; do
    for old in ANIM 'BADGE ' CLEAN DIR FLIPGAP HINT HOLD IMG LAUNCH NOFLIP PACE PWRDOWN QPASS REAGL 'ROT ' 'SET ' SETTLE2 SCROLLDU SWIPE TOL; do
        case "$c " in "$old"*) case "$c" in SCROLLDELAY|SCROLLSTYLE|SCROLL) [ "$old" = SCROLLDU ] && continue ;; esac; fail "$c starts with the old command $old: an old bridge would misread it" ;; esac
    done
done
for o in --scroll-delay --scroll-style --gcn --mono --max-defer --flip-input; do
    grep -q "\"$o\"" $E || fail "epd.c has no option $o"
done
grep -q '"--cpufreq"' input/vinput.c || fail "vinput.c has no --cpufreq"
grep -q '"--epd-fifo"' input/vinput.c || fail "vinput.c has no --epd-fifo"
for k in BOOST_MS BOOST_KHZ SCROLL_PX; do grep -q "\"$k\"" input/vinput.c || fail "vinput.c does not read $k from --gconf"; done
APPK="KFB_SCROLLDELAY KFB_SCROLLSTYLE KFB_GCN KFB_NEWWIN KFB_MONO KFB_MAXDEFER KFB_FLIPINPUT"
for h in pw6 k49; do
    S=host/$h/boot/lib/settings.sh
    for k in $APPK BOOST_MS BOOST_KHZ SCROLL_PX; do
        grep -q "^    $k=" $S || fail "$S: no default for $k"
        n=$(grep -c -E "GESTURE_HINT BOOST_MS BOOST_KHZ SCROLL_PX PASS_PX IDLE_FREEZE IDLE_WAKE IDLE_FREEZE_EXCLUDE( SAVER_EXIT_H)?( USB_ASK)?( UPDATE_BACKUP)?( WEBVIEW)?; do" $S)
        [ "$n" = 2 ] || fail "$S: BOOST_MS / SCROLL_PX not in the publish / apply lists ($n)"
    done
    for k in $APPK; do
        [ "$(grep -c "KFB_IMG $APPK KFB_SCROLLWFM KFB_ANIM_MODE" $S)" -ge 3 ] || fail "$S: $k missing from a key list"
    done
    for c in SCROLLDELAY SCROLLSTYLE GCN MONO MAXDEFER FLIPINPUT; do
        grep -q "epd_send $c " $S || fail "$S: epd_send_smooth does not send $c"
    done
    grep -q 'echo "BOOST_MS=' $S && grep -q 'echo "SCROLL_PX=' $S || fail "$S: gestures_write does not pass boost / scroll"
    grep -q -- '--scroll-delay ${KFB_SCROLLDELAY:-500}' host/$h/boot/lib/container.sh || fail "$h container.sh: no --scroll-delay"
    grep -q 'input_cpufreq_arg' host/$h/boot/lib/input.sh || fail "$h input.sh: no --cpufreq"
    grep -q 'SCROLLDELAY|SCROLLSTYLE|GCN|NEWWIN|MONO|MAXDEFER' host/$h/boot/lib/cmd.sh || fail "$h cmd.sh: appdisp regex"
    sh -n $S || fail "syntax $S"; sh -n host/$h/boot/lib/input.sh || fail "syntax $h input.sh"
    # follow-finger: early release, A2 scroll waveform, real-time priorities
    for k in PASS_PX KFB_SCROLLWFM EPD_RT INPUT_RT; do grep -q "^    $k=" $S || fail "$S: no default for $k"; done
    grep -q 'echo "PASS_PX=' $S || fail "$S: gestures_write does not pass PASS_PX"
    grep -q 'epd_send SCROLLWFM ' $S || fail "$S: epd_send_smooth does not send SCROLLWFM"
    grep -q -- '--rt ${EPD_RT:-0}' host/$h/boot/lib/container.sh || fail "$h container.sh: no --rt for the bridge"
    grep -q -- '--rt ${INPUT_RT:-0}' host/$h/boot/lib/input.sh || fail "$h input.sh: no --rt for the touch front end"
    grep -q '/dev/shm/vd-tracefs' host/$h/boot/lib/container.sh || fail "$h container.sh: no tracefs debug switch"
done
# page-turn merge window (KFB_TURNHOLD, per app; docs/boox-host.md 4)
grep -q 'strncmp(line, "TURNHOLD"' $E && grep -q '^ \*     TURNHOLD' $E || fail "epd.c: no TURNHOLD"
for h in pw6 k49; do
    grep -q '^    KFB_TURNHOLD=0 ' host/$h/boot/lib/settings.sh || fail "$h settings.sh: no default for KFB_TURNHOLD"
    grep -q 'epd_send TURNHOLD ' host/$h/boot/lib/settings.sh || fail "$h settings.sh: epd_send_smooth does not send TURNHOLD"
    grep -q 'FLIPINPUT|TURNHOLD|' host/$h/boot/lib/cmd.sh || fail "$h cmd.sh: appdisp regex has no TURNHOLD"
done
grep -q 'echo "TURNHOLD ${KFB_TURNHOLD:-0}"' host/pw6/boot/lib/appprof.sh || fail "appprof_send does not send TURNHOLD"
printf '%s\n' KFB_TURNHOLD=150 | grep -Eq "$(sed -n "s/^APPPROF_RE='\(.*\)'$/\1/p" host/pw6/boot/lib/appprof.sh)" || fail "APPPROF_RE does not accept KFB_TURNHOLD"
# picture quality pass (KFB_QPASS, global + per app; issue #33: no extra QPASS flash after a clean MTK swipe)
grep -q 'strncmp(line, "QPASS"' $E && grep -q '^ \*     QPASS' $E && grep -q '"--qpass"' $E || fail "epd.c: no QPASS"
grep -q '!c\.is_animated || ui_mode == 2' $E || fail "epd.c: clean hardware swipe still forces lowq"
for h in pw6 k49 legacy; do
    grep -q '^    KFB_QPASS=300 ' host/$h/boot/lib/settings.sh || fail "$h settings.sh: no default for KFB_QPASS"
    [ "$(grep -c 'KFB_QPASS' host/$h/boot/lib/settings.sh)" -ge 5 ] || fail "$h settings.sh: KFB_QPASS missing from key lists or epd_send"
    grep -q 'epd_send QPASS ' host/$h/boot/lib/settings.sh || fail "$h settings.sh: does not send QPASS"
    grep -q -- '--qpass ${KFB_QPASS:-300}' host/$h/boot/lib/container.sh || fail "$h container.sh: no --qpass"
    grep -q '|QPASS|' host/$h/boot/lib/cmd.sh || fail "$h cmd.sh: appdisp regex has no QPASS"
    grep -q 'echo "QPASS ${KFB_QPASS:-300}"' host/$h/boot/lib/appprof.sh || fail "$h appprof_send does not send QPASS"
    grep -q "^        'KFB_QPASS|" host/$h/boot/lib/appprof.sh || fail "$h appprof_publish has no KFB_QPASS knob"
    printf '%s\n' KFB_QPASS=0 | grep -Eq "$(sed -n "s/^APPPROF_RE='\(.*\)'$/\1/p" host/$h/boot/lib/appprof.sh)" || fail "$h APPPROF_RE does not accept KFB_QPASS=0"
done
for c in SCROLLWFM 'RT '; do
    grep -q "strn\?cmp(line, \"$c\"" $E || fail "epd.c does not parse $c"
    grep -q "^ \*     $c" $E || fail "epd.c header does not document $c"
done
for o in --rt --scroll-a2; do grep -q "\"$o\"" $E || fail "epd.c has no option $o"; done
grep -q '"--rt"' input/vinput.c && grep -q '"PASS_PX"' input/vinput.c && grep -q '"RT"' input/vinput.c || fail "vinput.c: --rt / PASS_PX / RT missing"
# per-app keys + NEWWIN behaviour (bridge mocked: a regular file stands in for the FIFO check)
. host/pw6/boot/lib/appprof.sh
for kv in KFB_SCROLLDELAY=0 KFB_SCROLLSTYLE=2 KFB_GCN=25 KFB_NEWWIN=1 KFB_MONO=128 KFB_MAXDEFER=800 KFB_FLIPINPUT=0 KFB_QPASS=0; do
    printf '%s\n' "$kv" | grep -Eq "$APPPROF_RE" || fail "APPPROF_RE does not accept $kv"
done
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/vd/boot $T/sys $T/w/kfb
printf '%s\n' 'com.a KFB_SCROLLSTYLE=2' 'com.b KFB_SCROLLSTYLE=2' 'com.c KFB_GCN=20;KFB_QPASS=0' > $T/vd/boot/appprofiles.conf
run() {   # $1 = settings.conf line(s); prints what reached the bridge per step
    printf '%s\n' "$1" > $T/sys/settings.conf; rm -f $T/w/appprof.pkg $T/sys/settings.app $T/w/kfb/appprof
    VD=$T/vd SYS=$T/sys W=$T/w X=$T/w/kfb sh -c '
        t() { :; }; alive() { return 0; }
        settings_load() { unset KFB_SCROLLSTYLE KFB_NEWWIN KFB_GCN KFB_QPASS; . $SYS/settings.conf; [ -f $SYS/settings.app ] && . $SYS/settings.app; }
        epd_send_smooth() { :; }
        . host/pw6/boot/lib/appprof.sh
        rm -f $X/epd.fifo; mkfifo $X/epd.fifo; ( cat $X/epd.fifo >> $W/epd.out & ) ; exec 3>$X/epd.fifo
        step() { : > $W/epd.out; appprof_fg $1; sleep 0.2; echo "$1 $(grep -ac NEWWIN $W/epd.out) $(grep -ac "^SCROLLSTYLE 2" $W/epd.out) $(grep -a "^FLIPCOUNT" $W/epd.out | tr " " "=") $(grep -a "^QPASS" $W/epd.out | tr " " "=")"; }
        step com.a; step com.b; step com.b; step com.c; step home
        appprof_publish; awk -F"|" "NF != 3 || \$1 !~ /^KFB_/ || \$3 !~ /=/ { bad++ } END { print \"KNOBS\", NR, bad + 0 }" $X/appknobs
        for k in $(cut -d"|" -f1 $X/appknobs); do printf "%s\n" "$k=1" | grep -Eq "$APPPROF_RE" || echo "KNOB NOT SAVABLE $k"; done'
}
out=$(run 'KFB_NEWWIN=1')
for l in "com.a 1 1 FLIPCOUNT=1 QPASS=300" "com.b 1 0 FLIPCOUNT=1 " "com.b 0 0  " "com.c 1 0 FLIPCOUNT=1 QPASS=0" "home 1 0 FLIPCOUNT=0 QPASS=300" "KNOBS 9 0"; do
    printf '%s\n' "$out" | grep -qxF "$l" || fail "KFB_NEWWIN=1: expected '$l' in:
$out"
done
printf '%s\n' "$out" | grep -q 'KNOB NOT SAVABLE' && fail "appknobs key not accepted by APPPROF_RE: $out"
out=$(run 'KFB_NEWWIN=0')
[ "$(printf '%s\n' "$out" | awk '$1 != "KNOBS" {s += $2} END {print s}')" = 0 ] || fail "KFB_NEWWIN=0 sent NEWWIN:
$out"
echo "eink-host: ok"
