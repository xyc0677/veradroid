#!/bin/sh
# VeraDroid preflight guard. Read-only.
# Usage: sh veradroid_preflight.sh [--report] [--force-experimental]
# Exit: 0=VERIFIED (or forced EXPERIMENTAL), 2=EXPERIMENTAL, 1=REFUSE
set -u

FORCE=0
REPORT=0
while [ $# -gt 0 ]; do
    case "$1" in
        --force-experimental) FORCE=1 ;;
        --report) REPORT=1 ;;
        *) echo "usage: $0 [--report] [--force-experimental]" >&2; exit 2 ;;
    esac
    shift
done

MACH=${VERADROID_BPL:-$(uname -m 2>/dev/null)}
case "$MACH" in
    aarch64|arm64|64) BPL=64 ;;
    *) BPL=32 ;;
esac

has_bit() {
    _bits=$1; _bit=$2
    _widx=$((_bit / BPL)); _boff=$((_bit % BPL))
    set -- $_bits
    _pos=$(($# - _widx))
    [ "$_pos" -ge 1 ] || return 1
    eval _w=\${$_pos}
    [ -n "${_w:-}" ] || return 1
    [ $(( (0x${_w} >> _boff) & 1 )) -eq 1 ]
}

input_blocks() {
    IN=${VERADROID_INPUT_DEVICES:-/proc/bus/input/devices}
    awk -v input_file="$IN" '
    function emit() { if (name != "") printf "%s|%s|%s|%s|%s|%s|%s|%s\n", name, handlers, phys, sysfs, prop, ev, key, abs }
    /^I: Bus=/ { emit(); name=""; handlers=""; phys=""; sysfs=""; prop=""; ev=""; key=""; abs="" }
    /^N: Name=/ { name=$0; sub(/^N: Name="/,"",name); sub(/"$/,"",name) }
    /^P: Phys=/ { phys=$0; sub(/^P: Phys=/,"",phys) }
    /^S: Sysfs=/ { sysfs=$0; sub(/^S: Sysfs=/,"",sysfs) }
    /^H: Handlers=/ { handlers=$0; sub(/^H: Handlers=/,"",handlers) }
    /^B: PROP=/ { prop=$0; sub(/^B: PROP=/,"",prop) }
    /^B: EV=/ { ev=$0; sub(/^B: EV=/,"",ev) }
    /^B: KEY=/ { key=$0; sub(/^B: KEY=/,"",key) }
    /^B: ABS=/ { abs=$0; sub(/^B: ABS=/,"",abs) }
    END { emit() }
    ' "$IN" 2>/dev/null
}

RESULT=VERIFIED
REASON=

refuse() { RESULT=REFUSE; REASON="$*"; }
experimental() { [ "$RESULT" = REFUSE ] || { RESULT=EXPERIMENTAL; REASON="$*"; }; }

# ---- firmware ----
FV=
for f in /etc/prettyversion.txt /etc/version.txt; do
    [ -f "$f" ] || continue
    FV=$(sed -n 's/.*Kindle[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$f" 2>/dev/null | head -1)
    [ -n "$FV" ] && break
done
if [ -z "$FV" ]; then
    experimental "firmware version not found"
elif [ "$FV" != "5.19.5" ]; then
    experimental "firmware $FV is not the tested 5.19.5"
fi
# Hard range check (public Vera range for PW6).
if [ -n "$FV" ]; then
    n=$(printf '%s\n' "$FV" | awk -F. '{printf "%d%03d%03d", $1, $2, $3}')
    # Vera's official page now lists PW6 through 5.20.1. 5.19.5 is the only
    # combination we have actually tested; 5.20.1 is therefore EXPERIMENTAL.
    case "$(cat /proc/device-tree/compatible 2>/dev/null | tr '\0' ' ')" in *imx7d-zelda*) fw_lo=5013000 ;; *) fw_lo=5018000 ;; esac
    if [ "$n" -lt "$fw_lo" ] || [ "$n" -gt 5020001 ]; then
        refuse "firmware $FV outside Vera range"
    fi
fi

# ---- model ----
# Colorsoft shares the PW6 resolution; device-tree is the reliable guard.
MODEL=$(cat "${VERADROID_MODEL_TREE:-/proc/device-tree/compatible}" 2>/dev/null | tr '\0' ' ')
# Existing KPM experiment profiles pass VD_MODEL even when the device tree uses
# a generic MediaTek compatible string (e.g. PW5's "mediatek,mt8110").
if [ -n "${VD_MODEL:-}" ]; then MODEL="$MODEL veradroid,$VD_MODEL"; fi
DEV=pw6
case "$MODEL" in
    *sangria*) DEV=pw6 ;;
    *malbec*) DEV=pw5; experimental "PW5 profile is simulated only; no real-device verification" ;;
    *seabreeze*) DEV=cs; experimental "Colorsoft profile is simulated only; color framebuffer not adapted" ;;
    *rossini*) DEV=kt6; experimental "KT6 profile is simulated only; no real-device verification" ;;
    *cava*) DEV=kt5; experimental "KT5 profile is simulated only; no real-device verification" ;;
    *barolo*) DEV=ks; experimental "Scribe profile is simulated only; rotation/stylus not adapted" ;;
    *imx7d-zelda*) DEV=oa2; experimental "Oasis 2/3 uses 32-bit k49/a7-512 profile" ;;
    *pisco*) refuse "KS2 has no validated resolution/profile" ;;
    '') experimental "device-tree model missing; assuming PW6" ;;
    *) experimental "unknown device-tree model: $MODEL" ;;
esac

# ---- data filesystem ----
FS=$(stat -f -c %T /mnt/base-us 2>/dev/null || true)
case "$FS" in
    ext2/ext3|ext4|xfs|btrfs|f2fs|'') ;;
    vfat|msdos|exfat) refuse "/mnt/base-us is $FS; Android rootfs needs a symlink-capable filesystem" ;;
    *) experimental "unknown /mnt/base-us filesystem: $FS" ;;
esac

# ---- framebuffer ----
FBW=0; FBH=0
for d in /sys/class/graphics/fb*; do
    [ -e "$d" ] || continue
    v=$(cat "$d/virtual_size" 2>/dev/null)
    [ -n "$v" ] || continue
    FBW=${v%%,*}; FBH=${v#*,}
    break
done
case "$FBH" in ''|*[!0-9]*) FBH=0 ;; esac
case "$DEV" in
    pw5)
        if { [ "$FBW" != "1236" ] && [ "$FBW" != "1248" ]; } || [ "$FBH" -lt 1648 ]; then
            refuse "framebuffer ${FBW}x${FBH}, expected PW5 1236/1248x1648"
        fi ;;
    kt5|kt6)
        if [ "$FBW" != "1072" ] || [ "$FBH" -lt 1448 ]; then
            refuse "framebuffer ${FBW}x${FBH}, expected KT 1072x1448/2896"
        fi ;;
    ks)
        case "$FBW,$FBH" in
            1860,2480|1860,4960|2480,1860|2480,3720|2480,4960) ;;
            *) refuse "framebuffer ${FBW}x${FBH}, expected Scribe 1860x2480/4960" ;;
        esac ;;
    oa2|ko3)
        if [ "$FBW" != "1264" ] || [ "$FBH" -lt 1680 ]; then
            refuse "framebuffer ${FBW}x${FBH}, expected Oasis 2/3 1264x1680/3360"
        fi ;;
    *)
        if [ "$FBW" != "1272" ] || [ "$FBH" -lt 1696 ]; then
            refuse "framebuffer ${FBW}x${FBH}, expected 1272x1696/3392"
        fi ;;
esac
FB_BPP=$(for d in /sys/class/graphics/fb*; do [ -e "$d" ] && cat "$d/bits_per_pixel" 2>/dev/null && break; done)
if [ -n "$FB_BPP" ] && [ "$FB_BPP" != "8" ]; then
    refuse "framebuffer is ${FB_BPP}bpp, Vera image expects 8bpp"
fi

# ---- touch device ----
TOUCH_COUNT=0
SINGLE_TOUCH=
TOUCH_NAME=; TOUCH_EVENT=; TOUCH_ABS=; TOUCH_KEY=; TOUCH_PROP=; TOUCH_SYSFS=
while IFS='|' read -r name handlers phys sysfs prop ev key abs; do
    [ -n "$name" ] || continue
    lname=$(printf '%s' "$name" | tr 'A-Z' 'a-z')
    case "$sysfs" in */virtual/*) continue ;; esac
    case "$lname" in *kindle-input-pt*|*veradroid-touch*|*veradroid-keys*|*pwrkey*|*power*|*gpio*|*button*) continue ;; esac
    # MT-B requires X/Y; otherwise a BTN_TOUCH single-touch device (PW5
    # goodix) is still usable by the Android InputReader, but experimental.
    if ! has_bit "$abs" 53 || ! has_bit "$abs" 54; then
        case "$lname" in *goodix*|*single*) SINGLE_TOUCH="$name" ;; esac
        continue
    fi
    event=$(printf '%s\n' "$handlers" | tr ' ' '\n' | grep '^event[0-9]' | head -1)
    [ -n "$event" ] || continue
    TOUCH_COUNT=$((TOUCH_COUNT + 1))
    TOUCH_NAME=$name; TOUCH_EVENT=$event; TOUCH_ABS=$abs; TOUCH_KEY=$key; TOUCH_PROP=$prop; TOUCH_SYSFS=$sysfs
done <<EOF
$(input_blocks)
EOF

if [ "$TOUCH_COUNT" -eq 0 ] && [ -n "${SINGLE_TOUCH:-}" ]; then
    experimental "single-touch $SINGLE_TOUCH (PW5-style); gestures/back edge not available until tested"
else
    [ "$TOUCH_COUNT" -eq 1 ] || refuse "expected exactly 1 MT touchscreen, found $TOUCH_COUNT"
fi
if ! has_bit "${TOUCH_ABS:-0}" 57; then
    experimental "touch is protocol A (no ABS_MT_TRACKING_ID); one-finger/gesture support needs emulator or real-device testing"
elif ! has_bit "${TOUCH_ABS:-0}" 47; then
    experimental "touch device has no ABS_MT_SLOT"
fi

# ---- driver / verified combos ----
case "$TOUCH_NAME" in
    pt_mt) [ "$FV" = "5.19.5" ] || experimental "pt_mt on $FV" ;;
    fts_ts) experimental "fts_ts touch driver not yet verified" ;;
    *) experimental "untested touch driver: ${TOUCH_NAME:-none}" ;;
esac

# ---- Android dependencies ----
if [ ! -e /dev/uinput ] && [ ! -e /dev/input/uinput ]; then
    refuse "missing /dev/uinput (or /dev/input/uinput)"
fi
if [ ! -e /dev/binder ] && [ ! -e /dev/vndbinder ] && ! grep -q binder /proc/misc 2>/dev/null; then
    refuse "missing binder"
fi
if [ ! -e /dev/ashmem ] && ! grep -q ashmem /proc/misc 2>/dev/null; then
    experimental "ashmem device not visible"
fi

# ---- report ----
if [ "$REPORT" = 1 ] || [ "$RESULT" != "VERIFIED" ]; then
    echo "RESULT=$RESULT"
    echo "firmware=${FV:-unknown}"
    echo "framebuffer=${FBW}x${FBH}"
    echo "touch_count=$TOUCH_COUNT"
    echo "touch_name=${TOUCH_NAME:-none}"
    echo "touch_event=${TOUCH_EVENT:-none}"
    echo "touch_abs=${TOUCH_ABS:-none}"
    echo "touch_key=${TOUCH_KEY:-none}"
    echo "btn_touch=$(has_bit "${TOUCH_KEY:-0}" 330 && echo 1 || echo 0)"
    echo "mt_slot=$(has_bit "${TOUCH_ABS:-0}" 47 && echo 1 || echo 0)"
    echo "mt_tracking=$(has_bit "${TOUCH_ABS:-0}" 57 && echo 1 || echo 0)"
    echo "prop_direct=$(has_bit "${TOUCH_PROP:-0}" 1 && echo 1 || echo 0)"
    [ -n "$REASON" ] && echo "reason=$REASON"
fi

case "$RESULT" in
    VERIFIED) exit 0 ;;
    EXPERIMENTAL)
        if [ "$FORCE" = 1 ]; then
            echo "forced experimental install" >&2
            exit 0
        fi
        exit 2 ;;
    *) exit 1 ;;
esac
