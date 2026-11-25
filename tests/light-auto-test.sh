#!/bin/sh
# 亮度（fix/1104-light，PW5 用户“调亮度没反应”）：cmd.sh light_set 在自动亮度开着时先关自动、设完读回核对；
# 手势调亮度（action.sh step）同样关自动；status.sh 用 powerd 的 alsLux 认传感器；诊断带上 kindle-frontlight.txt。
cd "$(dirname "$0")/.." || exit 2
fail() { echo "FAIL: $*"; exit 1; }
T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p $T/bin $T/p
# 假的 lipc：属性存在 $T/p/<名字>
cat > $T/bin/lipc-get-prop <<'E'
#!/bin/sh
[ "$1" = -s ] && shift
[ -f "$P/$2" ] || { echo "no such property" >&2; exit 1; }
cat "$P/$2"
E
cat > $T/bin/lipc-set-prop <<'E'
#!/bin/sh
[ "$1" = -i ] && shift
echo "$2=$3" >> "$P/../set.log"
[ "$2" = flIntensity ] && [ -f "$P/stuck" ] && exit 0      # powerd 不理（模拟被自动亮度改回）
printf '%s' "$3" > "$P/$2"
E
chmod +x $T/bin/*
for h in pw6 k49; do
    C=host/$h/boot/lib/cmd.sh
    grep -q 'light)   numeric "$a" && light_set "$a" ;;' $C || fail "$h cmd.sh light does not use light_set"
    grep -q 'alsLux' host/$h/boot/lib/status.sh || fail "$h status.sh does not use alsLux"
    grep -q 'flAuto 0' host/$h/boot/action.sh || fail "$h action.sh step does not turn auto brightness off"
    grep -q 'kindle-frontlight.txt' host/$h/boot/lib/watchdog.sh || fail "$h diag has no frontlight file"
    awk '/^light_set\(\)/,/^}/' $C > $T/ls.sh
    rm -f $T/p/* $T/set.log; echo 1 > $T/p/flAuto; echo 10 > $T/p/flIntensity; echo 900 > $T/p/flRawIntensity
    out=$(P=$T/p PATH=$T/bin:$PATH sh -c 't() { echo "LOG $*"; }; status_write() { echo STATUS; }; . '$T'/ls.sh; light_set 5')
    [ "$(cat $T/p/flAuto)" = 0 ] || fail "$h: auto brightness still on after a manual level"
    [ "$(cat $T/p/flIntensity)" = 5 ] || fail "$h: level not set"
    echo "$out" | grep -q 'auto brightness was on' || fail "$h: no log line for auto off"
    echo "$out" | grep -q STATUS || fail "$h: status not rewritten after auto off"
    head -1 $T/set.log | grep -q '^flAuto=0$' || fail "$h: auto must be turned off before the level is set"
    # 自动本来就关着：不写 flAuto、不重写状态、不记日志
    rm -f $T/set.log; echo 0 > $T/p/flAuto
    out=$(P=$T/p PATH=$T/bin:$PATH sh -c 't() { echo "LOG $*"; }; status_write() { echo STATUS; }; . '$T'/ls.sh; light_set 7')
    [ -z "$out" ] || fail "$h: unexpected output with auto off: $out"
    grep -q flAuto $T/set.log && fail "$h: flAuto written although it was off"
    # powerd 不理：记一行读回的值
    touch $T/p/stuck
    out=$(P=$T/p PATH=$T/bin:$PATH sh -c 't() { echo "LOG $*"; }; status_write() { :; }; . '$T'/ls.sh; light_set 20')
    echo "$out" | grep -q "light 20: powerd reads back '7' (raw 900)" || fail "$h: read-back mismatch not logged: $out"
    rm -f $T/p/stuck
    # 没有 flAuto 属性的固件：照常设亮度
    rm -f $T/p/flAuto
    P=$T/p PATH=$T/bin:$PATH sh -c 't() { :; }; status_write() { :; }; . '$T'/ls.sh; light_set 3'
    [ "$(cat $T/p/flIntensity)" = 3 ] || fail "$h: level not set without flAuto"
done
# action.sh frontlight() 同样在开关前光前关闭 flAuto（且 lighton 在已亮早退时不误关）(#14)
mkdir -p $T/w
for h in pw6 k49 legacy; do
    sh -n host/$h/boot/action.sh || fail "syntax host/$h/boot/action.sh"
    awk '/^frontlight\(\)/,/^}/' host/$h/boot/action.sh > $T/fl.sh
    # lighton when cur > 0: early return, flAuto stays 1
    rm -f $T/p/* $T/set.log $T/w/*; echo 1 > $T/p/flAuto; echo 8 > $T/p/flIntensity; echo 24 > $T/p/flMaxIntensity
    P=$T/p W=$T/w PATH=$T/bin:$PATH sh -c '. '$T'/fl.sh; frontlight lighton'
    [ "$(cat $T/p/flAuto)" = 1 ] && [ ! -e $T/set.log ] || fail "$h: lighton when already on touched flAuto"
    # frontlight toggle off when cur > 0: turns flAuto=0 BEFORE flIntensity=0
    P=$T/p W=$T/w PATH=$T/bin:$PATH sh -c '. '$T'/fl.sh; frontlight frontlight'
    [ "$(cat $T/p/flAuto)" = 0 ] && [ "$(cat $T/p/flIntensity)" = 0 ] || fail "$h: frontlight off did not disable flAuto or intensity"
    head -1 $T/set.log | grep -q '^flAuto=0$' || fail "$h: frontlight off must disable flAuto before flIntensity"
    # frontlight toggle on when cur = 0 and flAuto = 1: turns flAuto=0 BEFORE restoring saved intensity
    rm -f $T/set.log; echo 1 > $T/p/flAuto
    P=$T/p W=$T/w PATH=$T/bin:$PATH sh -c '. '$T'/fl.sh; frontlight frontlight'
    [ "$(cat $T/p/flAuto)" = 0 ] && [ "$(cat $T/p/flIntensity)" = 8 ] || fail "$h: frontlight on did not disable flAuto or restore intensity"
    head -1 $T/set.log | grep -q '^flAuto=0$' || fail "$h: frontlight on must disable flAuto before flIntensity"
done
echo "light-auto-test: OK"
