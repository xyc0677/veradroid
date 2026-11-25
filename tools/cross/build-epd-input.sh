#!/bin/sh
# Build veradroid-epd + veradroid-input for the NEW models (pw6 / k49 hosts: PW5 / PW6 / KT5 / KT6 / Scribe / Colorsoft,
# all Cortex-A53 / A7 with NEON) with the vdcross-legacy toolchain. :
#   sh build-epd-input.sh <src dir with display/ input/> <out dir>       (FBInk with `make kindle` done in $FBINK_DIR)
# -mfpu=neon matters: the toolchain defaults to vfpv3-d16, so without it the NEON RGBA -> gray conversion in epd.c
# (#ifdef __ARM_NEON) is not compiled in and every frame takes 22-41 ms instead of 16-24 ms on a PW6
#. The legacy bundle (i.MX, 3.0 / 4.1 kernels) keeps tools/cross/build-legacy-bin.sh.
set -eu
SRC=$1; OUT=$2; FB=${FBINK_DIR:-${VD_BUILD:-$HOME/veradroid-build}/rot/FBInk}
mkdir -p "$OUT"
nice -n 12 docker run --rm --cpus=4 --memory=2g -v "$SRC":/s:ro -v "$OUT":/o -v "$FB":/fbink:ro vdcross-legacy:gcc5-glibc219 sh -c '
set -e; CC="arm-linux-gnueabihf-gcc ${EPD_CPU_FLAGS:--O3 -mfpu=neon} -static -Wall -Wno-unused-function"
$CC -pthread -I /fbink /s/display/epd.c /fbink/Release/libfbink.a -lm -o /o/veradroid-epd
$CC /s/input/vinput.c -lm -o /o/veradroid-input
arm-linux-gnueabihf-strip /o/veradroid-epd /o/veradroid-input; chmod 755 /o/*; md5sum /o/*'
