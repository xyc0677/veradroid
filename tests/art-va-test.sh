#!/bin/sh
# 1 GB models: ART must not reserve a backup heap space (heapsize 512m x 2 = 1 GB of a 32-bit app's 3 GB address space).
# 番茄 on a KT6 1 GB (user diagnostic): "thread constructor failed: Try again" SIGABRT and CursorWindow
# error -12 with physical memory to spare. heap_va_fix adds dalvik.vm.backgroundgctype=CMS + -XX:DisableHSpaceCompactForOOM
# to build.prop (on a test device: dalvik-main 1024 -> 512 MB, VmSize 1.94 -> 1.46 GB), ART_HSPACE=1 takes them out again.
set -eu
cd "$(dirname "$0")/.."
fail() { echo "FAIL: $*"; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
for H in host/pw6/boot host/k49/boot; do
    M=$H/lib/memory.sh
    grep -q '^heap_va_fix()' $M || fail "$H: no heap_va_fix"
    # only on the >= 700 MB branch of heap_fix (512 MB keeps background compaction)
    awk '/^heap_fix\(\)/,/^}/' $M | awk '/-lt 700000/{low=1} /^    else/{low=0; hi=1} /heap_va_fix/{ if (low) bad=1; if (hi) ok=1 } END{ exit !(ok && !bad) }' \
        || fail "$H: heap_va_fix not (only) in the >= 700 MB branch of heap_fix"
    t() { echo "$*" >> "$T/log"; }
    eval "$(awk '/^heap_va_fix\(\)/,/^}/' $M)"
    f=$T/build.prop
    printf 'dalvik.vm.heapsize=512m\nro.x=1\n' > $f
    ART_HSPACE=; heap_va_fix $f; heap_va_fix $f
    [ "$(grep -c '^dalvik.vm.backgroundgctype=CMS$' $f)" = 1 ] || fail "$H: backgroundgctype not added exactly once"
    [ "$(grep -c '^dalvik.vm.extra-opts=-XX:DisableHSpaceCompactForOOM$' $f)" = 1 ] || fail "$H: extra-opts not added exactly once"
    [ "$(grep -c 'art: no backup heap space' $T/log)" = 1 ] || fail "$H: log line not written once"
    ART_HSPACE=1; heap_va_fix $f
    grep -q 'backgroundgctype\|extra-opts' $f && fail "$H: ART_HSPACE=1 did not remove the lines"
    [ "$(cat $f)" = "$(printf 'dalvik.vm.heapsize=512m\nro.x=1')" ] || fail "$H: ART_HSPACE=1 changed other lines"
    # someone else's extra-opts / gc type in the image: leave the file alone
    printf 'dalvik.vm.extra-opts=-Xfoo\n' > $f; ART_HSPACE=; heap_va_fix $f
    [ "$(cat $f)" = "dalvik.vm.extra-opts=-Xfoo" ] || fail "$H: foreign extra-opts overwritten"
    printf 'dalvik.vm.backgroundgctype=SS\n' > $f; heap_va_fix $f
    [ "$(cat $f)" = "dalvik.vm.backgroundgctype=SS" ] || fail "$H: foreign backgroundgctype overwritten"
    rm -f $T/log
done
# heap_fix runs before Android starts (zram_on), after settings_load (ART_HSPACE comes from settings.conf)
for H in host/pw6/boot host/k49/boot; do
    awk '/^zram_on\(\)/,/^}/' $H/lib/memory.sh | grep -q heap_fix || fail "$H: zram_on no longer calls heap_fix"
done

# 2G/2G kernels (3.x and 4.1.15-lab126 on PW4 / Oasis 2 / Oasis 3 / KT4, CONFIG_VMSPLIT_2G):
# vd_art_stack must switch to bottom-up mmap (ulimit -s unlimited) when ro.vd.artbase is absent (boot.art @ 0x70000000),
# keep 8 MB stack when ro.vd.artbase is set, and stay a complete no-op on 4.9 / 5.15 kernels.
fn() { awk -v n="$2" '$0 ~ "^" n "\\(\\) \\{" { p = 1 } p { print } p && /^}/ { exit }' "$1"; }
for H in host/legacy/boot host/k49/boot; do
    for s in $H/lib/common.sh $H/lib/container.sh $H/lib/afterboot.sh $H/ashell.sh; do
        sh -n "$s" || fail "$s: syntax error"
    done
    grep -q '^vd_art_stack()' "$H/lib/common.sh" || fail "$H: common.sh missing vd_art_stack()"
    grep -q '^vd_art_stack$' "$H/lib/common.sh" || fail "$H: common.sh does not invoke vd_art_stack at load"

    # Evaluate vd_art_stack in isolated subshell with mocked uname / ulimit
    vas_run() { # kernel build_prop_path
        (
            KREL=$1; BP=$2; ULOG=$T/ulimit.log; : > "$ULOG"
            W=$T/w; SYS=$T/sys
            uname() { echo "$KREL"; }
            ulimit() { echo "$*" >> "$ULOG"; }
            eval "$(fn "$H/lib/common.sh" vd_art_stack)"
            if [ "$BP" = - ]; then vd_art_stack; else vd_art_stack "$BP"; fi
            cat "$ULOG"
        )
    }
    bp=$T/art-build.prop
    printf 'ro.build.version.release=7.1.2\n' > "$bp"
    for k in 4.9.77-lab126 5.15.41-lab126; do
        [ -z "$(vas_run "$k" "$bp")" ] || fail "$H: vd_art_stack not a no-op on $k"
    done
    for k in 4.1.15-lab126 3.0.35-lab126; do
        [ "$(vas_run "$k" "$T/no-such-build.prop")" = "-s unlimited" ] || fail "$H ($k): missing build.prop -> want -s unlimited"
        printf 'ro.build.version.release=7.1.2\n' > "$bp"
        [ "$(vas_run "$k" "$bp")" = "-s unlimited" ] || fail "$H ($k): 0x70000000 image -> want -s unlimited"
        printf 'ro.build.version.release=7.1.2\nro.vd.artbase=0x30000000\n' > "$bp"
        [ "$(vas_run "$k" "$bp")" = "-s 8192" ] || fail "$H ($k): ro.vd.artbase image -> want -s 8192"
    done

    # container.sh: run_in_ns must call vd_art_stack $R/system/build.prop before exec /init --second-stage
    fn "$H/lib/container.sh" run_in_ns | awk '
        /exec \/init$/ { demo = NR }
        /vd_art_stack \$R\/system\/build\.prop/ { vas = NR }
        /\/init --second-stage/ { if (!vas || vas > NR) bad = 1; n++ }
        END { exit !(vas && (!demo || vas > demo) && n >= 1 && !bad) }
    ' || fail "$H: run_in_ns does not call vd_art_stack before every /init --second-stage exec"

    # afterboot.sh: adb_sh runs vd_art_stack inside a subshell before invoking container /system/bin/sh
    fn "$H/lib/afterboot.sh" adb_sh | awk '
        /\(.*vd_art_stack/ { sub_vas = 1 }
        /vd_art_stack/ { vas = NR }
        /\$pref \/system\/bin\/sh/ { sh = NR }
        END { exit !(sub_vas && vas && sh && vas < sh) }
    ' || fail "$H: adb_sh does not run vd_art_stack in a subshell before container sh"

    # ashell.sh: 3.*|4.1.* sets ulimit -s before chroot / nsenter
    awk '
        /3\.\*\|4\.1\.\*/ { k = NR }
        /ro\.vd\.artbase=.*ulimit -s 8192.*ulimit -s unlimited/ { u = NR }
        /^exec (chroot|nsenter)/ { if (!k || !u || u > NR) bad = 1; e++ }
        END { exit !(k && u && e >= 1 && !bad) }
    ' "$H/ashell.sh" || fail "$H: ashell.sh missing 3.*|4.1.* ulimit -s guard before exec"
done

# k49 specifics: SYS default fallback, HOSTNS + pivot_init branches, adb_sh_t, and dalvik-cache/arm 0711 0:0 guard
H=host/k49/boot
mkdir -p "$T/sys/rootfs/system" "$T/w"
printf 'ro.build.version.release=7.1.2\n' > "$T/sys/rootfs/system/build.prop"
[ "$(vas_run 4.1.15-lab126 -)" = "-s unlimited" ] || fail "k49: SYS default fallback without artbase -> want -s unlimited"
printf 'ro.vd.artbase=0x30000000\n' >> "$T/sys/rootfs/system/build.prop"
[ "$(vas_run 4.1.15-lab126 -)" = "-s 8192" ] || fail "k49: SYS default fallback with artbase -> want -s 8192"

fn "$H/lib/container.sh" run_in_ns | awk '
    /dalvik_cache_dirs/ { dcd = NR }
    /vd_art_stack \$R\/system\/build\.prop/ { vas = NR }
    /exec chroot \$R \/init --second-stage/ { chr = NR }
    /exec \$VD\/bin\/pivot_init \$R mnt \/init --second-stage/ { piv = NR }
    END { exit !(dcd && vas && chr && piv && dcd < chr && vas < chr && chr < piv) }
' || fail "k49: run_in_ns order for dalvik_cache_dirs / vd_art_stack / chroot / pivot_init"

fn "$H/lib/afterboot.sh" adb_sh_t | awk '
    /\(.*vd_art_stack/ { sub_vas = 1 }
    /vd_art_stack/ { vas = NR }
    /vd_timeout .* \$pref \/system\/bin\/sh/ { sh = NR }
    END { exit !(sub_vas && vas && sh && vas < sh) }
' || fail "k49: adb_sh_t does not run vd_art_stack in a subshell before vd_timeout"

(
    W=$T/w; SYS=$T/sys; CALLER_S=untouched
    printf 'ro.build.version.release=7.1.2\n' > "$SYS/rootfs/system/build.prop"
    uname() { echo 4.1.15-lab126; }
    ulimit() { CALLER_S="$*"; echo "ulimit:$*"; }
    vd_in() { echo true; }
    vd_timeout() { shift; "$@"; }
    eval "$(fn "$H/lib/common.sh" vd_art_stack)"
    eval "$(fn "$H/lib/afterboot.sh" adb_sh)"
    eval "$(fn "$H/lib/afterboot.sh" adb_sh_t)"
    [ "$(adb_sh 'pm list packages')" = "ulimit:-s unlimited" ] || fail "k49: adb_sh subshell did not apply -s unlimited"
    [ "$(adb_sh_t 10 'cmd package compile')" = "ulimit:-s unlimited" ] || fail "k49: adb_sh_t subshell did not apply -s unlimited"
    [ "$CALLER_S" = untouched ] || fail "k49: adb_sh mutated caller shell state"
)

(
    R=$T/r; mkdir -p "$R/data"
    : > "$T/chown.log"
    chown() { echo "$*" >> "$T/chown.log"; }
    eval "$(fn "$H/lib/container.sh" dalvik_cache_dirs)"
    dalvik_cache_dirs "$R"
    [ -d "$R/data/dalvik-cache" ] || fail "k49: dalvik-cache not created"
    [ ! -e "$R/data/dalvik-cache/arm" ] || fail "k49: dalvik-cache/arm created without system/framework/arm"
    case "$(ls -ld "$R/data/dalvik-cache" | awk '{print $1}')" in drwx--x--x*) ;; *) fail "k49: dalvik-cache mode not 0711" ;; esac
    grep -qxF "0:0 $R/data/dalvik-cache" "$T/chown.log" || fail "k49: dalvik-cache chown 0:0 missing"

    chmod 0777 "$R/data/dalvik-cache"
    mkdir -p "$R/system/framework/arm"
    : > "$T/chown.log"
    dalvik_cache_dirs "$R"
    [ -d "$R/data/dalvik-cache/arm" ] || fail "k49: dalvik-cache/arm not created for 32-bit image"
    case "$(ls -ld "$R/data/dalvik-cache" | awk '{print $1}')" in drwx--x--x*) ;; *) fail "k49: dalvik-cache mode not reset to 0711" ;; esac
    case "$(ls -ld "$R/data/dalvik-cache/arm" | awk '{print $1}')" in drwx--x--x*) ;; *) fail "k49: dalvik-cache/arm mode not 0711" ;; esac
    grep -qxF "0:0 $R/data/dalvik-cache/arm" "$T/chown.log" || fail "k49: dalvik-cache/arm chown 0:0 missing"
)

echo "art-va: ok"
