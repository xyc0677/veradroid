#!/bin/sh
# progress.sh eips error wrapping (#16): 46-column aligned slices (1-46, 47-92, 93-138) + conditional 4th row (139-184).
set -eu
cd "$(dirname "$0")/.."
fail() { echo "FAIL: $*"; exit 1; }

for h in pw6 k49 legacy; do
    sh -n "host/$h/boot/lib/progress.sh" || fail "syntax host/$h/boot/lib/progress.sh"
done
cmp -s host/pw6/boot/lib/progress.sh host/k49/boot/lib/progress.sh || fail "progress.sh differs between pw6 and k49"
cmp -s host/pw6/boot/lib/progress.sh host/legacy/boot/lib/progress.sh || fail "progress.sh differs between pw6 and legacy"

T=$(mktemp -d) || exit 2
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
cat > "$T/bin/eips" <<EOF
#!/bin/sh
printf '%s:%s:%s\n' "\$1" "\$2" "\$3" >> "$T/eips.log"
EOF
chmod +x "$T/bin/eips"

# 1. Short message (<= 138 chars): rows 6..8 drawn, row 9 NOT drawn
PATH="$T/bin:$PATH" sh -c '
    . host/pw6/boot/lib/progress.sh
    VP_MODE=eips; VP_ON=1; VP_CUR=net; VP_STATUS=""
    vp_error 21 "空间不足" "detail"
'
grep -q '^1:6:Not enough storage' "$T/eips.log" || fail "short error missing row 6"
grep -q '^1:9:' "$T/eips.log" && fail "short error unexpectedly drew row 9"

# 2. Long message (160 chars via NC_EN on code 13): exact 46-char slices across rows 6..9 without truncation at 140
rm -f "$T/eips.log"
L1="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"   # 46 chars (1-46)
L2="BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"   # 46 chars (47-92)
L3="CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"   # 46 chars (93-138)
L4="DDDDDDDDDDDDDDDDDDDDDD"                           # 22 chars (139-160)
PATH="$T/bin:$PATH" NC_EN="${L1}${L2}${L3}${L4}" sh -c '
    . host/pw6/boot/lib/progress.sh
    VP_MODE=eips; VP_ON=1; VP_CUR=net; VP_STATUS=""
    vp_error 13 "连不上服务器" "detail"
'
grep -q "^1:6:${L1}\$" "$T/eips.log" || fail "row 6 slice mismatch"
grep -q "^1:7:${L2}\$" "$T/eips.log" || fail "row 7 slice mismatch"
grep -q "^1:8:${L3}\$" "$T/eips.log" || fail "row 8 slice mismatch (93-138)"
grep -q "^1:9:${L4} *\$" "$T/eips.log" || fail "row 9 slice mismatch (139-184)"
echo "progress-eips-test: OK"
