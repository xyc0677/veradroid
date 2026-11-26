#!/bin/sh
# zram.ko (zsmalloc built in, LZO from the kernel) for a 4.1.15 Kindle firmware group whose kernel has no zram.
#, like build-fw.sh, against the same Amazon tree + firmware config.
#
#   build-zram41.sh <name> <fwscan-dir> <src-path>
#
# Same arguments as build-fw.sh (name = the kmod set, e.g. pw4-5151). Only the 5.14.1 - 5.15.1.1 groups need it
# (pw4-5151 kt4-5151 ko3-5151): from 5.16.2.1 on Amazon builds CONFIG_ZRAM=y / CONFIG_ZSMALLOC=y into the kernel.
# LZO (CONFIG_LZO_COMPRESS/DECOMPRESS=y, exported) is in every 4.1.15 kernel; LZ4 compression is not on the Oasis
# kernels, so the module has LZO only (zram's default "lzo").
#
# 1. the tree is prepared and verified by build-amazon.sh (vmlinux from Amazon's tree + the firmware's config; it stops
#    unless binder/uinput CRCs equal the firmware's; its binder/uinput output goes to a scratch dir, not the package);
# 2. drivers/block/zram/{zram_drv,zcomp,zcomp_lzo}.c + mm/zsmalloc.c are copied unmodified and built out of tree into ONE
#    module with tools/kmod/zram41/ (two module_init()s turned into plain functions, called zsmalloc first);
# 3. refused unless: every undefined symbol is exported by the firmware kernel (its kallsyms), every imported CRC equals
#    the firmware kernel's (modcrc.py check), vermagic is the kernel's.
# Result: $OUT/<name>/{zram.ko,versions.txt,BUILD-NOTES,UNTESTED} (OUT default kmod-pkg/extra/4.1.15-lab126).
# Shipped as kmod/extra/4.1.15-lab126/<name>/ in the bundle: outside every set directory, so neither kmod.id nor the set
# ids (kmodsets) change.
set -eu
export LC_ALL=C
NAME=$1; FS=$2; SRC=$3
HERE=$(cd "$(dirname "$0")" && pwd)
W=${W:-$AOSP_WORK/kmod/imx41}
KK=${KK:-${VD_BUILD:-$HOME/veradroid-build}/kindle-kernel/legacy}
OUT=${OUT:-${VD_BUILD:-$HOME/veradroid-build}/legacy/kmod-pkg/extra/4.1.15-lab126}
URL=${URL:-https://fireos-eink-src.s3.amazonaws.com}
TCD=$AOSP_WORK/src/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9
TC=$TCD/bin/arm-linux-androideabi-
LIMITS=${LIMITS:---cpus=4 --memory=6g}
grep -q '^CONFIG_ZRAM=y' "$FS/config" && { echo "$NAME: the kernel has zram built in, nothing to build"; exit 0; }
for c in CONFIG_SWAP CONFIG_BLOCK CONFIG_SYSFS CONFIG_LZO_COMPRESS CONFIG_LZO_DECOMPRESS CONFIG_MODULE_UNLOAD CONFIG_MODVERSIONS; do
    grep -q "^$c=y" "$FS/config" || { echo "$NAME: $c is not =y in the firmware config" >&2; exit 1; }
done
# --- tree: same download / unpack as build-fw.sh ---
D=$KK/$NAME; P=$D/$(basename "$SRC"); T=$W/amz/src-$NAME
mkdir -p "$D"
[ -s "$P" ] || { curl -sS -fL -o "$P.part" "$URL/$SRC" && mv "$P.part" "$P"; }
if [ ! -f "$T/Makefile" ]; then
    rm -rf "$D/gplrelease"; tar xzf "$P" --no-same-owner -C "$D" gplrelease/linux-4.1.15.tar.gz
    mkdir -p "$T"; tar xzf "$D/gplrelease/linux-4.1.15.tar.gz" --no-same-owner -C "$T"
fi
cp "$FS/config" "$W/$NAME.config"; zcat "$FS/kallsyms.gz" > "$W/$NAME.kallsyms"; cp "$FS/crcs" "$W/$NAME.crcs"
grep '^Linux version' "$FS/banner" > "$W/$NAME.banner"
(cd "$W" && sh "$HERE/build-amazon.sh" "$NAME" "amz/src-$NAME" "$NAME.config" "$NAME.kallsyms" "$NAME.crcs" "amz-zprep-$NAME") > "$W/amz-zprep-$NAME.log" 2>&1 \
    || { tail -20 "$W/amz-zprep-$NAME.log"; echo "$NAME: tree preparation / verification failed" >&2; exit 1; }
grep -E 'config identical|exported CRCs' "$W/amz-zprep-$NAME.log"
# --- module ---
M=$W/amz-zram-$NAME; rm -rf "$M"; mkdir -p "$M"
for f in zram_drv.c zram_drv.h zcomp.c zcomp.h zcomp_lzo.c zcomp_lzo.h; do cp "$T/drivers/block/zram/$f" "$M/"; done
cp "$T/mm/zsmalloc.c" "$M/"
cp "$HERE/zram41/Makefile" "$HERE/zram41/vd_modinit.h" "$HERE/zram41/vd_main.c" "$M/"
( cd "$M" && sha256sum zram_drv.c zram_drv.h zcomp.c zcomp.h zcomp_lzo.c zcomp_lzo.h zsmalloc.c ) > "$M/SOURCES.sha256"
docker run --rm $LIMITS -v "$AOSP_WORK":"$AOSP_WORK" -w "$T" vdaosp:7.1 sh -c "
    set -e; export ARCH=arm CROSS_COMPILE=$TC
    nice -n 12 make -j4 M=$M modules > $M/build.log 2>&1 || { tail -30 $M/build.log; exit 1; }
    chmod -R a+rwX $M"
grep -E 'error|warning|WARNING' "$M/build.log" | grep -v 'Section mismatch' || true
K=$M/zram.ko
${TC}nm -u "$K" | awk '{print $2}' | grep -v '^__aeabi_\|^__gnu_mcount_nc$' | sort -u > "$M/undef.txt"
grep -E ' __ksymtab_' "$W/$NAME.kallsyms" | sed 's/.* __ksymtab_//' | sort -u > "$M/exported.txt"
comm -23 "$M/undef.txt" "$M/exported.txt" > "$M/missing.txt" || true
[ -s "$M/missing.txt" ] && { echo "$NAME: not exported by the firmware kernel: $(tr '\n' ' ' < "$M/missing.txt")" >&2; exit 1; }
python3 "$HERE/modcrc.py" check "$K" "$W/$NAME.crcs" > "$K.crccheck" || { grep -v '^OK' "$K.crccheck"; echo "$NAME: zram.ko CRCs differ from the firmware kernel" >&2; exit 1; }
vm=$(strings "$K" | sed -n 's/^vermagic=//p')
case "$vm" in "4.1.15-lab126 SMP preempt mod_unload modversions ARMv7"*) ;; *) echo "$NAME: unexpected vermagic '$vm'" >&2; exit 1 ;; esac
mkdir -p "$OUT/$NAME"; rm -f "$OUT/$NAME/"*
cp "$K" "$OUT/$NAME/"; ${TC}strip --strip-debug "$OUT/$NAME/zram.ko"
( cd "$OUT/$NAME" && echo "sha256 zram.ko $(sha256sum zram.ko | cut -d' ' -f1)"
  python3 "$HERE/modcrc.py" show zram.ko | awk '{print "zram.ko", $1, $2}' ) > "$OUT/$NAME/versions.txt"
{ echo "zram.ko for $NAME: Amazon $(basename "$P") (gplrelease/linux-4.1.15.tar.gz), firmware config, unmodified __versions"
  echo "sources (unmodified, from that tree): drivers/block/zram/{zram_drv,zcomp,zcomp_lzo}.c, mm/zsmalloc.c; glue: tools/kmod/zram41/"
  sed 's/^/  /' "$M/SOURCES.sha256"
  echo "imports $(grep -c '^OK' "$K.crccheck") symbols, all CRCs equal the firmware kernel's; vermagic $vm"; } > "$OUT/$NAME/BUILD-NOTES"
sh "$HERE/check-crc.sh" --host "$OUT/$NAME" "$W/$NAME.crcs"
echo "built $(date -u +%F); not loaded on this kernel yet" > "$OUT/$NAME/UNTESTED"
ls -la "$OUT/$NAME"
