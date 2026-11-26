#!/bin/sh
# Check, BEFORE insmod, that a module set built for a 4.1.15 Kindle kernel matches the kernel this Kindle is running.
# Reads nothing but /proc and /dev/mem, writes nothing, loads nothing. Busybox sh is enough (no python on the Kindle).
#
#   on the Kindle:  sh check-crc.sh <module-dir>
#   on our side:    sh check-crc.sh --host <module-dir> <device.crcs>     (tools/kmod/kcrc.py output)
#
# <module-dir> is one kmod/4.1.15-lab126/<name>/ directory: binder.ko, uinput.ko, uname-v, and versions.txt
# (written by build-amazon.sh: "sha256 <file>" lines, then "<file> 0x<crc> <symbol>" for every entry in each module's
# __versions table, i.e. every symbol the module imports plus module_layout).
#
# What the kernel does on a mismatch (kernel/module.c check_version() in Amazon's 4.1.15 tree, CONFIG_MODVERSIONS=y,
# CONFIG_MODULE_FORCE_LOAD unset): a symbol whose CRC differs ("disagrees about version of symbol X") or that has no
# CRC in the module ("no symbol version for X") makes the symbol unresolvable, and insmod fails with
# "Invalid argument" before any of the module's code runs. Nothing is written anywhere. This script tells the same
# answer without trying, and also shows WHICH symbols would differ.
#
# How the Kindle side gets the kernel's CRCs: /proc/kallsyms gives the address of __kcrctab_<symbol> (the kernel's
# own CRC for that export); on these kernels CONFIG_DEVMEM=y, CONFIG_STRICT_DEVMEM unset and PAGE_OFFSET equals
# the DRAM base (0x80000000), so that address is also the physical address and 4 bytes of /dev/mem at it are the
# CRC. A read() of RAM through /dev/mem is a plain copy (no mapping is created).
set -u
export LC_ALL=C

if [ "${1:-}" = --host ]; then
    D=$2; CRCS=$3; HERE=$(cd "$(dirname "$0")" && pwd); rc=0
    for m in "$D"/*.ko; do
        if python3 "$HERE/modcrc.py" check "$m" "$CRCS" > /tmp/vd-crc.$$; then
            echo "$(basename "$m"): all $(grep -c '^OK' /tmp/vd-crc.$$) imported CRCs equal the kernel's"
        else
            echo "$(basename "$m"): MISMATCH"; grep -v '^OK' /tmp/vd-crc.$$; rc=1
        fi
    done
    rm -f /tmp/vd-crc.$$; exit $rc
fi

D=${1:?usage: sh check-crc.sh <module-dir>}
V=$D/versions.txt
[ -f "$V" ] || { echo "no $V"; exit 2; }
echo "kernel:  $(uname -r) $(uname -v)"
echo "modules: $(cat "$D/uname-v" 2>/dev/null)"
[ "$(uname -r)" = 4.1.15-lab126 ] || { echo "RESULT: not a 4.1.15-lab126 kernel"; exit 1; }
[ "$(uname -v)" = "$(cat "$D/uname-v" 2>/dev/null)" ] || echo "NOTE: uname -v differs from the build this set was made for (continuing: the CRCs decide)"

# the files are the ones versions.txt describes
bad=0
for f in $(awk '$1 == "sha256" {print $2}' "$V"); do
    want=$(awk -v f="$f" '$1 == "sha256" && $2 == f {print $3}' "$V")
    have=$(sha256sum "$D/$f" 2>/dev/null | cut -d' ' -f1)
    [ "$want" = "$have" ] || { echo "file $f: sha256 differs from versions.txt (wrong download?)"; bad=1; }
done
[ $bad = 0 ] || { echo "RESULT: files damaged, nothing checked"; exit 1; }

K=/tmp/vd-kallsyms.$$
grep -v '\[' /proc/kallsyms > $K
if [ "$(head -1 $K | cut -c1-8)" = 00000000 ]; then
    echo "RESULT: /proc/kallsyms shows no addresses (run as root)"; rm -f $K; exit 2
fi
rd() { # 4 bytes at kernel address $1 (8 hex digits), printed as 8 hex digits.
    # addr/4 without 32-bit overflow (busybox sh may do 32-bit arithmetic): (addr >> 4) * 4 + (low nibble) / 4
    h=${1%?}; l=${1#???????}
    dd if=/dev/mem bs=4 count=1 skip=$(( 0x$h * 4 + 0x$l / 4 )) 2>/dev/null | od -An -tx4 | tr -d ' \n'
}
[ -n "$(rd "$(awk '$3 == "_text" {print $1}' $K)")" ] || { echo "RESULT: /dev/mem cannot be read here"; rm -f $K; exit 2; }

ok=0; diff=0; miss=0
# one pass over kallsyms: "<file> <crc> <symbol> <address of __kcrctab_symbol or ->"
awk 'NR == FNR { if ($1 != "sha256") { n++; f[n] = $1; c[n] = $2; s[n] = $3; want["__kcrctab_" $3] = 1 }; next }
     ($3 in want) && !($3 in at) { at[$3] = $1 }
     END { for (i = 1; i <= n; i++) { a = at["__kcrctab_" s[i]]; print f[i], c[i], s[i], (a == "" ? "-" : a) } }' "$V" $K > /tmp/vd-v.$$
while read -r f crc sym a; do
    if [ "$a" = - ]; then
        echo "MISSING $f $sym (the kernel does not export it)"; miss=$((miss + 1)); continue
    fi
    k=0x$(rd "$a")
    if [ "$k" = "$crc" ]; then ok=$((ok + 1))
    else echo "DIFF $f $sym kernel=$k module=$crc"; diff=$((diff + 1)); fi
done < /tmp/vd-v.$$
rm -f /tmp/vd-v.$$ $K
echo "checked $((ok + diff + miss)) imported symbols: same $ok, differ $diff, missing $miss"
if [ $((diff + miss)) = 0 ]; then echo "RESULT: OK - every CRC matches this kernel, the modules can be loaded"; exit 0; fi
echo "RESULT: MISMATCH - do not use this set (the kernel would refuse it anyway)"; exit 1
