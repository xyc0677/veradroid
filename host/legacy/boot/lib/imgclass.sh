# Per-class system images (docs/per-model-images.md): an image built for one hardware class carries ro.vd.class in
# build.prop. Before the container starts, check that this Kindle can run it at all; the installer already refuses a
# wrong-class bundle, this catches what got past it (a hand-copied rootfs, a restored backup).
#   hard (do not start, the Kindle UI is left alone): binder ABI (64-bit image on a 32-bit binder kernel or back),
#        integer divide (an A53 / A7 image on a Cortex-A9 dies with SIGILL)
#   soft (log only): memory class differs: the host's memory.sh / props.sh still write the right heap values per MemTotal
# vd_device_class must stay the same as in cloud/static/bootstrap.sh.
vd_device_class() {
    _pr=${VD_TEST_PROC:-/proc}   # tests/device-class-test.sh points this at saved /proc files
    _mt=$(awk '/^MemTotal:/{print $2}' $_pr/meminfo 2>/dev/null); _mt=${_mt:-0}
    _div=0; grep '^Features' $_pr/cpuinfo 2>/dev/null | grep -qw idiva && _div=1
    case "${VD_TEST_UNAME:-$(uname -r)}" in
        5.*) [ $_div = 1 ] && [ "$_mt" -ge 700000 ] && echo pw6 ;;
        4.9.*) [ $_div = 1 ] || return 0
               if [ "$_mt" -lt 700000 ]; then echo k49-512; else echo k49-1g; fi ;;
        3.*|4.1.*)
            if [ $_div = 1 ]; then [ "$_mt" -ge 400000 ] && [ "$_mt" -lt 700000 ] && echo a7-512
            elif [ "$_mt" -lt 400000 ]; then echo a9-256
            elif [ "$_mt" -lt 700000 ]; then echo a9-512; fi ;;
    esac
    return 0
}
imgclass_check() {
    ic=$(sed -n 's/^ro.vd.class=//p' "$R/system/build.prop" 2>/dev/null | head -1)
    [ -n "$ic" ] || return 0                       # family image (pw6 / k49 / legacy): as before
    dc=$(vd_device_class)
    [ "$ic" = "$dc" ] && { t "image class $ic"; return 0; }
    case "$ic" in pw6) ib=64 ;; *) ib=32 ;; esac
    case "$(uname -r)" in 5.*) kb=64 ;; *) kb=32 ;; esac
    [ "$ib" = "$kb" ] || { t "image class $ic needs ${ib}-bit binder, kernel $(uname -r) has ${kb}-bit: not starting"; return 1; }
    case "$ic" in
        a9-*) ;;
        *) grep '^Features' /proc/cpuinfo 2>/dev/null | grep -qw idiva || { t "image class $ic needs hardware divide, this CPU has none: not starting"; return 1; } ;;
    esac
    t "image class $ic, device class ${dc:-unknown}: runs (memory settings follow this device's MemTotal)"
    return 0
}
