#!/system/bin/sh
# VeraDroid: /system/bin/dex2oat inside the container (bound over the real one by container.sh, the real binary is
# /dev/vdbin/dex2oat). Only compiles of an app's own code under /data (--dex-file=/data/..., DexClassLoader / plugin
# frameworks such as ByteDance Mira) are changed: a compiling filter (speed, speed-profile, interpret-only, ...) becomes
# verify-at-runtime: the dex is only copied into the oat file, verification and JIT happen when the code runs. Installs and
# "cmd package compile" (installd: --zip-fd) pass through untouched.
# Why: 番茄免费小说 7.4.5 extracts ~15 plugin APKs on first start and runs up to three dex2oat --compiler-filter=speed
# at once in the background (one took 56 s); on a 1 GB Kindle that swaps the whole system and the
# app's own pages (书城 / 书架) stay blank for minutes. Even interpret-only still verified the big plugins for 67-99 s
# each (on a test PW6); verify-at-runtime does no compile work at all.
sec=0 last=
for a in "$@"; do
    case "$a" in
    --zip-fd=*) sec=0; break ;;
    --dex-file=/data/*|--dex-file=/storage/*|--dex-file=/sdcard/*|--dex-file=/mnt/*) sec=1 ;;
    --compiler-filter=*) last=${a#--compiler-filter=} ;;
    esac
done
if [ $sec = 1 ]; then
    case "$last" in
    verify-none|verify-at-runtime|extract) ;;
    *) set -- "$@" --compiler-filter=verify-at-runtime ;;     # the last --compiler-filter wins
    esac
fi
exec /dev/vdbin/dex2oat "$@"
