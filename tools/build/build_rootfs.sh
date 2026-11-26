#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the Android system images (rootfs.tar.gz) from this checkout:
#   AOSP_WORK=<dir> sh tools/build/build_rootfs.sh <command>   (AOSP_WORK layout: see below)
#   build_rootfs.sh prepare|sync|pw6|k49|all            family images (one per binder ABI, releases up to PW6 0.90 / k49 0.98)
#   build_rootfs.sh class <class>                         per-class image (docs/per-model-images.md):
#        pw6 | k49-512 | k49-1g | a7-512 | a9-512 | a9-256   -> rootfs-<class>-<git>.tar.gz
#   build_rootfs.sh repack-class <class>                  pack the last build of that class again
#   Family pw6 and class pw6 of one revision have the same output name (rootfs-pw6-<git>): give them different DEST.
# Paths can be overridden (e.g. for a second build host): VD_AOSP, VD_LOCK, WORK, DEST, VD_CPUS, VD_JOBS, VD_MEM, VD_MEMSWAP, VD_OC.
# Parallel builds (one per out dir): VD_CNAME=<container name>, VD_LOCK=<lock per out dir>, VD_NOSYNC=1 (run 'sync' once before
# starting them: a sync while another build compiles from the same tree removes and re-copies VeraIME / VeraHome under it).
# The k49 family build edits device/veradroid/kindle/BoardConfig.mk (shared with class pw6 / family pw6): give it its own VD_SRC.
#
# Flavours:
#   pw6  product veradroid_kindle (Cortex-A53, 64-bit binder = binder protocol 8, Kindle 5.15 kernels)
#   k49  the same product with TARGET_USES_64_BIT_BINDER=false (libbinder, servicemanager, healthd use binder
#        protocol 7: the 4.9 kernels of PW5 / KT5 / KT6 / Scribe), plus 512 MB-class heap defaults
#        in init.kindle.rc / build.prop (boot/lib/memory.sh rewrites build.prop per device anyway).
#   The legacy bundle (3.0 / 4.1 kernels, Cortex-A9) is a different product (veradroid_kindle32); not built here.
#
# Layout of $AOSP_WORK:
#   src/            AOSP android-7.1.1_r61 (repo sync), left untouched
#   out/            optional earlier build output of src/ (copied once to speed up the first build)
#   src-big/        private copy of src/ that the patches from android/ are applied to
#   out-big/, out-big32/   build output of the pw6 / k49 flavours
#   ccache/         compiler cache;  cacerts-extra/  optional current Mozilla CA files (*.0) added to the image
# Builds run in the docker image vdaosp:7.1 (tools/build/Dockerfile.aosp).
# WORK=<git checkout> (default: this repository).  Output: $DEST/rootfs-<flavour>-<git>.tar.gz (+ .sha256)
set -eu
A=${VD_AOSP:-${AOSP_WORK:?set AOSP_WORK (or VD_AOSP) to the build work directory, see the header of this script}}
if [ -z "${VD_HOME_LOCKED:-}" ] && command -v flock >/dev/null; then VD_HOME_LOCKED=1 exec flock "${VD_LOCK:-$A/.build.lock}" sh "$0" "$@"; fi
WORK=${WORK:-$(cd "$(dirname "$0")/../.." && pwd)}
# VD_SRC: another source tree (e.g. an overlay over src-big holding one branch's patches); VD_O64 / VD_O32: family out dirs
SRC=${VD_SRC:-$A/src-big}; O64=${VD_O64:-$A/out-big}; O32=${VD_O32:-$A/out-big32}; DEST=${DEST:-$A/rootfs}
REV=$(git -C "$WORK" rev-parse --short HEAD)
BC=$SRC/device/veradroid/kindle/BoardConfig.mk
# veradroid_kindle_slim = veradroid_kindle + slim.mk (trimmed preloaded-classes / boot image classes, no media helper
# daemons, no boot animation process, config.disable_* switches; docs/slim-android.md). PRODUCT=veradroid_kindle: plain.
PRODUCT=${PRODUCT:-veradroid_kindle_slim}
mkdir -p "$DEST" "$A/log"

prepare() {
    [ -d "$SRC" ] || nice -n 15 cp -a --reflink=auto "$A/src" "$SRC"
    [ -d "$O64" ] || { if [ -d "$A/out" ]; then nice -n 15 cp -a --reflink=auto "$A/out" "$O64"; else mkdir -p "$O64"; fi; }
}
sync_src() {
    # device config, source overlays, VeraHome dir, small patches (apply-patches.sh), then what apply-patches does not do
    sh "$WORK/android/apply-patches.sh" "$SRC" | grep -v '^already\|^overlay:' || true
    chmod 644 "$SRC/device/veradroid/kindle/init.kindle.rc"
    # packages/apps/Android.mk defines VeraHome (manifest = packages/apps/AndroidManifest.xml) and includes VeraIME.
    # Ours (android/packages/apps-Android.mk); the stock one would also pull in Bluetooth (not shipped).
    cp "$WORK/android/packages/apps-Android.mk" "$SRC/packages/apps/Android.mk"
    # packages/apps/res = the VeraHome module's default resource dir (LOCAL_PATH is packages/apps). It only holds two files
    # left over from an early VeraIME (app_name, input-method xml); VeraHome references neither, but they are in the shipped
    # VeraHome.apk, so they are kept byte-identical from git until a release that may change the APK drops them.
    rm -rf "$SRC/packages/apps/res"; cp -R "$WORK/android/packages/apps-res" "$SRC/packages/apps/res"
    cp "$WORK/android/packages/VeraHome/AndroidManifest.xml" "$SRC/packages/apps/AndroidManifest.xml"
    rm -f "$SRC/packages/apps/VeraHome/Android.mk"
    W=$WORK/android/packages/VeraIME; D=$SRC/packages/apps/VeraIME
    for d in src res assets; do rm -rf "$D/$d"; cp -a "$W/$d" "$D/$d"; done
    cp "$W/AndroidManifest.xml" "$W/Android.mk" "$D/"
    find "$SRC/device/veradroid" "$SRC/packages/apps/VeraHome" "$D" -name '._*' -delete
    chmod -R a+rX "$SRC/device/veradroid" "$SRC/packages/apps/VeraHome" "$D" "$SRC/packages/apps/AndroidManifest.xml" "$SRC/packages/apps/res" "$SRC/packages/apps/Android.mk"
    grep -q 'config_enableWallpaperService">false' "$SRC/device/veradroid/kindle/overlay/frameworks/base/core/res/res/values/config.xml"
    echo "synced $REV into $SRC"
}
build() { # out-dir log [device]
    DEV=${3:-kindle}
    # the --tmpfs below would make the product dir as root when it does not exist yet (a new device): the build runs as uid 1000
    [ -d "$1/target/product/$DEV" ] || { mkdir -p "$1/target/product/$DEV"; chown 1000:1000 "$1/target" "$1/target/product" "$1/target/product/$DEV"; }
    # VD_CNAME: own container name for parallel builds (each with its own out dir, VD_LOCK and VD_NOSYNC; the PC host).
    # Without it: the old rule, no other vdbuild* container at all (shared out / src trees).
    CN=${VD_CNAME:-vdbuild-big}
    if [ -z "${VD_CNAME:-}" ]; then
        docker ps --format '{{.Names}}' | grep -q '^vdbuild' && { echo "another vdbuild container is running" >&2; exit 1; }
    else
        docker ps --format '{{.Names}}' | grep -qx "$CN" && { echo "$CN is already running" >&2; exit 1; }
    fi
    docker run --rm --name "$CN" \
        --tmpfs /aosp/out/target/product/$DEV/dex_bootjars:rw,exec,size=3g,uid=1000,gid=1000,mode=0755 \
        --cpus=${VD_CPUS:-6} --memory=${VD_MEM:-14g} --memory-swap=${VD_MEMSWAP:-18g} \
        -v "$SRC":/aosp/src -v "$1":/aosp/out -v "$A/ccache":/aosp/ccache \
        -e USER=builder -e OUT_DIR=/aosp/out -e USE_CCACHE=1 -e CCACHE_DIR=/aosp/ccache -e CCACHE_MAXSIZE=30G \
        -e ANDROID_JACK_VM_ARGS='-Dfile.encoding=UTF-8 -XX:+TieredCompilation -Xmx'"${VD_JACK_XMX:-3g}" \
        -e PRODUCT=$PRODUCT -e DEV=$DEV -e VD_JOBS=${VD_JOBS:-6} vdaosp:7.1 bash -c 'cd /aosp/src && source build/envsetup.sh >/dev/null && lunch $PRODUCT-userdebug >/dev/null &&
            P=/aosp/out/target/product/$DEV && rm -rf $P/system $P/root $P/data $P/obj/PACKAGING $P/*.img $P/installed-files*.txt && nice -n 12 make -j$VD_JOBS systemimage ramdisk; echo BUILD-EXIT=$?' > "$2" 2>&1
    tail -1 "$2" | grep -q 'BUILD-EXIT=0' || { tail -30 "$2"; exit 1; }
}
pack() { # flavour product-dir
    P=$2; ST=$DEST/stage-$1; rm -rf "$ST"; mkdir -p "$ST"
    cp -a "$P/root/." "$ST/"; cp -a "$P/system/." "$ST/system/"
    for d in data cache mnt storage sdcard dev proc sys; do [ -e "$ST/$d" ] || [ -L "$ST/$d" ] || mkdir "$ST/$d"; done
    mkdir -p "$ST/storage/emulated/0"
    # Some build-host file systems hand out ACL-style modes (directories 0700, files 0705 / 0604 ...); tar only keeps the mode bits,
    # and inside the container non-root services (servicemanager runs as system) then cannot even reach /system/bin
    # ("cannot execve: Permission denied", servicemanager exit 127, zygote never starts). Normalise like an image would be:
    # directories 0755, executables 0755, everything else 0644.
    find "$ST" -type d -exec chmod 755 {} +
    find "$ST" -type f -perm /111 -exec chmod 755 {} +
    find "$ST" -type f ! -perm /111 -exec chmod 644 {} +
    for f in "$A"/cacerts-extra/*.[0-9]; do [ -f "$f" ] || continue; b=${f##*/}; [ -e "$ST/system/etc/security/cacerts/$b" ] || cp "$f" "$ST/system/etc/security/cacerts/$b"; done
    chmod 644 "$ST"/system/etc/security/cacerts/*
    # the security-relevant files keep their usual modes
    [ "$(stat -c %a "$ST/init.kindle.rc")" = 644 ] || { echo "init.kindle.rc is not 644" >&2; exit 1; }
    [ "$(find "$ST" ! -type l \( -perm -o+w -o -perm -g+w \) | wc -l)" = 0 ] || { echo "group/world writable files in the image" >&2; exit 1; }
    if [ "$1" = k49 ] && [ -z "${CLASS:-}" ]; then
        # k49 heap defaults (512 MB class)
        sed -i 's/^on early-init$/on early-init\n    setprop dalvik.vm.heapsize 256m\n    setprop dalvik.vm.heapgrowthlimit 128m\n    setprop dalvik.vm.heapstartsize 4m/' "$ST/init.kindle.rc"
        printf '\n# PW5: 512 MB RAM; keep ART under the kernel/32-bit address-space limit\ndalvik.vm.heapstartsize=4m\ndalvik.vm.heapgrowthlimit=128m\ndalvik.vm.heapsize=256m\ndalvik.vm.heaptargetutilization=0.75\ndalvik.vm.heapminfree=512k\ndalvik.vm.heapmaxfree=8m\n' >> "$ST/system/build.prop"
    elif [ "$1" = pw6 ]; then
        # 1 GB class (PW6 / Scribe): prevent blocking-GC ANRs in heavy apps like Fanqie
        grep -q '^dalvik\.vm\.heapstartsize=' "$ST/system/build.prop" || printf '\ndalvik.vm.heapstartsize=16m\ndalvik.vm.heapminfree=2m\ndalvik.vm.heapmaxfree=16m\ndalvik.vm.heaptargetutilization=0.75\n' >> "$ST/system/build.prop"
    fi
    # Common e-ink image polish (from PW6 real-device & UX emulator audit): splash anti-black-screen + no StrictMode stalls + remove stray BT HAL/perm + strip dead system_server features (print/backup/device_admin/managed_users/voice_recognizers/bluetooth -> eliminates RemotePrintSpooler 10s TimeoutException & DevicePolicyManager KeyChainService crash)
    rm -rf "$ST/system/app/Bluetooth" "$ST/system/lib/hw/bluetooth.default.so" "$ST/system/etc/permissions/android.hardware.bluetooth.xml"
    if [ -f "$ST/system/etc/permissions/handheld_core_hardware.xml" ]; then
        sed -i -E '/android\.(software\.(print|backup|device_admin|managed_users|voice_recognizers)|hardware\.bluetooth)"/d' "$ST/system/etc/permissions/handheld_core_hardware.xml"
    fi
    grep -q '^persist\.vd\.splash=' "$ST/system/build.prop" || printf '\npersist.vd.splash=1\npersist.sys.strictmode.disable=true\n' >> "$ST/system/build.prop"
    # what the images must have (the reasons for this rebuild)
    for f in system/lib/libGLESv2_vdnull.so system/lib/libEGL.so system/bin/vold system/bin/installd system/framework/services.jar system/priv-app/PackageInstaller/PackageInstaller.apk system/app/HTMLViewer/HTMLViewer.apk init.kindle.rc; do
        [ -e "$ST/$f" ] || { echo "image $1 lacks $f" >&2; exit 1; }
    done
    for f in system/app/Bluetooth system/lib/hw/bluetooth.default.so system/etc/permissions/android.hardware.bluetooth.xml system/app/CtsShimPrebuilt system/priv-app/CtsShimPrivPrebuilt system/app/WallpaperBackup system/priv-app/BackupRestoreConfirmation system/priv-app/Settings; do
        [ -e "$ST/$f" ] && { echo "image $1 has $f (not wanted)" >&2; exit 1; }
    done
    grep -q 'VeraDroid: skipping non-whitelisted zygote fd' "$ST/system/lib/libandroid_runtime.so" || { echo "libandroid_runtime without the fd patch" >&2; exit 1; }
    for a in priv-app/VeraHome/VeraHome.apk app/VeraIME/VeraIME.apk; do
        unzip -l "$ST/system/$a" | grep -q ' classes.dex$' || { echo "$a has no classes.dex" >&2; exit 1; }
        [ -f "$ST/system/${a%/*}/oat/arm/$(basename "$a" .apk).odex" ] || { echo "$a has no odex" >&2; exit 1; }
    done
    # the bundle ships these exact files: the installer keeps the image's odex when they match
    cp "$ST/system/priv-app/VeraHome/VeraHome.apk" "$DEST/VeraHome-$1-$REV.apk"; cp "$ST/system/app/VeraIME/VeraIME.apk" "$DEST/VeraIME-$1-$REV.apk"
    cp "$ST/system/lib/libandroid_runtime.so" "$DEST/libandroid_runtime-$1-$REV.so"
    if [ -n "${CLASS:-}" ]; then
        # a class image must say what it was built for (lib/props.sh / bootstrap.sh check it against the device)
        bp=$ST/system/build.prop
        grep -qx "ro.vd.class=$CLASS" "$bp" || { echo "image $1 is not class $CLASS" >&2; exit 1; }
        grep -qx "dalvik.vm.isa.arm.variant=$CPU" "$bp" || { echo "image $1 is not built for $CPU" >&2; exit 1; }
        grep -qx "ro.product.device=$DEV" "$bp" || { echo "image $1 is not device $DEV" >&2; exit 1; }
        grep -qx "ro.vd.slim=1" "$bp" || { echo "image $1 is not slim" >&2; exit 1; }
    fi
    echo "ro.vd.image=$1-$REV" >> "$ST/system/build.prop"
    t=$DEST/rootfs-$1-$REV.tar.gz
    nice -n 12 tar --owner=0 --group=0 --numeric-owner -czf "$t.partial" -C "$ST" . && mv "$t.partial" "$t"
    (cd "$ST" && find . -type f -print0 | sort -z | xargs -0 md5sum) > "$DEST/rootfs-$1-$REV.md5"
    rm -rf "$ST"
    sha256sum "$t" | tee "$t.sha256"
}
case "${1:-all}" in
    prepare) prepare ;;
    sync) sync_src ;;
    pw6) [ -n "${VD_NOSYNC:-}" ] || sync_src; build "$O64" "$A/log/build-big-pw6.log"; pack pw6 "$O64/target/product/kindle" ;;
    repack) # repack pw6|k49: pack the image again from the last build of that flavour (no compile)
        case "${2:-}" in pw6) pack pw6 "$O64/target/product/kindle" ;; k49) pack k49 "$O32/target/product/kindle" ;; *) exit 2 ;; esac ;;
    k49)
        [ -d "$O32" ] || nice -n 15 cp -a --reflink=auto "$O64" "$O32"
        [ -n "${VD_NOSYNC:-}" ] || sync_src
        trap 'sed -i "s/^TARGET_USES_64_BIT_BINDER := false # k49$/TARGET_USES_64_BIT_BINDER := true/" "$BC"' INT TERM EXIT
        sed -i 's/^TARGET_USES_64_BIT_BINDER := true$/TARGET_USES_64_BIT_BINDER := false # k49/' "$BC"
        grep -q 'BINDER := false # k49' "$BC"
        build "$O32" "$A/log/build-big-k49.log"; pack k49 "$O32/target/product/kindle" ;;
    all) prepare; sh "$0" pw6; sh "$0" k49 ;;
    class|repack-class)
        CLASS=${2:?class}
        case "$CLASS" in    # product device cpu-variant
            pw6)     set -- "$1" veradroid_pw6     kindle     cortex-a53 ;;
            k49-512) set -- "$1" veradroid_k49_512 kindle_k49 cortex-a53 ;;
            k49-1g)  set -- "$1" veradroid_k49_1g  kindle_k49 cortex-a53 ;;
            a7-512)  set -- "$1" veradroid_a7_512  kindle_a7  cortex-a7 ;;
            a9-512)  set -- "$1" veradroid_a9_512  kindle32   cortex-a9 ;;
            a9-256)  set -- "$1" veradroid_a9_256  kindle32   cortex-a9 ;;
            *) echo "unknown class $CLASS" >&2; exit 2 ;;
        esac
        PRODUCT=$2; DEV=$3; CPU=$4; OC=${VD_OC:-$A/out-c-$CLASS}   # VD_OC: a private out dir for this class
        # seed a new out dir from the last family build (host tools, target/common Java intermediates, ccache-warm objects;
        # the build container runs as uid 1000, so an empty dir made here would not even be writable)
        if [ ! -d "$OC" ]; then
            SEED=$O64; [ "$DEV" = kindle32 ] && [ -d "$A/out-legacy" ] && SEED=$A/out-legacy
            nice -n 15 cp -a --reflink=auto "$SEED" "$OC.partial" && mv "$OC.partial" "$OC"
        fi
        if [ "$1" = class ]; then [ -n "${VD_NOSYNC:-}" ] || sync_src; build "$OC" "${VD_LOG:-$A/log/build-class-$CLASS.log}" "$DEV"; fi
        pack "$CLASS" "$OC/target/product/$DEV" ;;
    *) echo "usage: prepare|sync|pw6|k49|all|class <c>|repack-class <c>" >&2; exit 2 ;;
esac
