#!/bin/sh
# One 4.1.15 Kindle firmware -> binder.ko + uinput.ko, end to end, no device needed .
#
#   build-fw.sh <name> <fwscan-dir> <src-path>
#
# name        set name, <model>-<fw without dots>, e.g. pw4-5151 (PW4 5.15.1.1)
# fwscan-dir  that firmware's kernel data: boot.img banner config kallsyms.gz crcs (kimage.py, kimgsyms.py, kcrc.py)
# src-path    "<id>/Kindle_src_<fw>_<n>.tar.gz" as linked from Amazon's Kindle source code notice page
#             (the GPL package of exactly that firmware)
#
# The package goes to $KK/<name>/ (kept, with SHA256SUMS), the kernel tree to $W/amz/src-<name> (docker mount),
# then build-amazon.sh builds against Amazon's tree + the firmware's own config and refuses to finish unless every
# CRC the modules import equals the firmware kernel's. Result: $OUT/<name>/{binder.ko,uinput.ko,uname-v,versions.txt,UNTESTED}.
# Amazon's archives are untrusted input: unpacked without owners, compiled only inside docker.
set -eu
NAME=$1; FS=$2; SRC=$3
HERE=$(cd "$(dirname "$0")" && pwd)
W=${W:-$AOSP_WORK/kmod/imx41}
KK=${KK:-${VD_BUILD:-$HOME/veradroid-build}/kindle-kernel/legacy}
OUT=${OUT:-${VD_BUILD:-$HOME/veradroid-build}/legacy/kmod-pkg/4.1.15-lab126}
URL=${URL:-https://fireos-eink-src.s3.amazonaws.com}
D=$KK/$NAME; P=$D/$(basename "$SRC")
mkdir -p "$D"
[ -s "$P" ] || curl -sS -fL -o "$P.part" "$URL/$SRC" && { [ -s "$P" ] || mv "$P.part" "$P"; }
(cd "$D" && sha256sum "$(basename "$P")" > SHA256SUMS)
T=$W/amz/src-$NAME
if [ ! -f "$T/Makefile" ]; then
    rm -rf "$D/gplrelease"; tar xzf "$P" --no-same-owner -C "$D" gplrelease/linux-4.1.15.tar.gz
    (cd "$D" && sha256sum gplrelease/linux-4.1.15.tar.gz >> SHA256SUMS)
    mkdir -p "$T"; tar xzf "$D/gplrelease/linux-4.1.15.tar.gz" --no-same-owner -C "$T"
fi
cp "$FS/config" "$W/$NAME.config"; zcat "$FS/kallsyms.gz" > "$W/$NAME.kallsyms"; cp "$FS/crcs" "$W/$NAME.crcs"
grep '^Linux version' "$FS/banner" > "$W/$NAME.banner"
(cd "$W" && sh "$HERE/build-amazon.sh" "$NAME" "amz/src-$NAME" "$NAME.config" "$NAME.kallsyms" "$NAME.crcs" "amz-out-$NAME")
mkdir -p "$OUT/$NAME"
cp "$W/amz-out-$NAME/binder.ko" "$W/amz-out-$NAME/uinput.ko" "$W/amz-out-$NAME/uname-v" "$W/amz-out-$NAME/versions.txt" \
   "$W/amz-out-$NAME/BUILD-NOTES" "$OUT/$NAME/"
sh "$HERE/check-crc.sh" --host "$OUT/$NAME" "$FS/crcs"
# CRCs prove the modules match; the set still stays out of bundles until it has really loaded on that kernel
# (a check-crc.sh / kmod-test.sh log from that firmware): delete UNTESTED then.
echo "built $(date -u +%F) from $(basename "$SRC"); not loaded on this kernel yet" > "$OUT/$NAME/UNTESTED"
