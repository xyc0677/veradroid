# SPDX-License-Identifier: GPL-3.0-or-later
set -e
: "${AOSP_WORK:?set AOSP_WORK to the build work directory (src/ = AOSP tree, out/ = its build output)}"
A=$AOSP_WORK/src; O=${HWC_LIBDIR:-$AOSP_WORK/out/target/product/kindle/obj/lib}
T=$A/prebuilts/gcc/linux-x86/arm/arm-linux-androideabi-4.9/bin/arm-linux-androideabi-
cd "$(dirname "$0")"
CPU_FLAGS=${HWC_CPU_FLAGS:--O3 -marm -march=armv7-a -mtune=cortex-a7 -mfloat-abi=softfp -mfpu=neon -fomit-frame-pointer -funroll-loops}
if [ -n "${HWC_CLANG:-}" ]; then
  "$HWC_CLANG" --target=armv7a-linux-androideabi24 $CPU_FLAGS -std=gnu99 -fPIC -fno-strict-aliasing -Wall -Wno-unused-parameter \
    -DANDROID -D__ANDROID__ -nostdinc -isystem $A/bionic/libc/arch-arm/include -isystem $A/bionic/libc/include \
    -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-arm -isystem $A/bionic/libc/kernel/android/uapi -isystem $A/bionic/libm/include \
    -isystem "$("$HWC_CLANG" -print-resource-dir)/include" \
    -I$A/hardware/libhardware/include -I$A/system/core/include -I$A/system/media/audio/include \
    -c hwc_kindle.c -o hwc_kindle.o
else
  ${T}gcc $CPU_FLAGS -std=gnu99 -fPIC -fno-strict-aliasing -Wall -Wno-unused-parameter \
    -DANDROID -D__ANDROID__ -nostdinc -isystem $A/bionic/libc/arch-arm/include -isystem $A/bionic/libc/include \
    -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-arm -isystem $A/bionic/libc/kernel/android/uapi -isystem $A/bionic/libm/include \
    -isystem $(${T}gcc -print-file-name=include) \
    -I$A/hardware/libhardware/include -I$A/system/core/include -I$A/system/media/audio/include \
    -c hwc_kindle.c -o hwc_kindle.o
fi
${T}gcc -nostdlib -shared -Wl,-soname,hwcomposer.kindle.so -Wl,--no-undefined -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,--hash-style=both \
  $O/crtbegin_so.o hwc_kindle.o -L$O -llog -lc -lm -ldl $(${T}gcc $CPU_FLAGS -print-libgcc-file-name) $O/crtend_so.o -o hwcomposer.kindle.so
${T}strip --strip-unneeded hwcomposer.kindle.so
ls -la hwcomposer.kindle.so; ${T}readelf -d hwcomposer.kindle.so | grep -E "NEEDED|SONAME"; ${T}nm -D hwcomposer.kindle.so | grep -E " U | HMI"
