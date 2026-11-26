# Per-model Android properties (stage 2 of the image optimisation; docs/slim-android.md "按机型").
# The image flavours stay generic (pw6 / k49); what differs per model is data in models/<model>.conf, in the section
#   # >>> android props
#   #@prop ro.logd.size=128K
#   # <<< android props
# (sh comments, so settings_load ignores them; format shared with host/legacy).
# props_apply runs before the container starts and edits the system image's build.prop IN PLACE: ro.* properties keep
# their first definition, so appending would not override. Every key it touches is remembered with its original line in
# $SYS/props.orig; a key the model file no longer lists gets its original line back. A new image (ro.vd.image changes)
# starts from a clean slate. Keys owned by host settings (heap, density, max_starting_bg: memory.sh) are refused here.
# Sources, later wins per key: models/ram-<256|512|1024>.conf (by MemTotal: < 400 MB, < 700 MB, else), then models/<model>.conf.
props_files() {
    mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo); c=1024
    [ -n "$mt" ] && [ "$mt" -lt 700000 ] && c=512; [ -n "$mt" ] && [ "$mt" -lt 400000 ] && c=256
    [ -f "$VD/models/ram-$c.conf" ] && echo "$VD/models/ram-$c.conf"
    m=$(cat "$SYS/model" 2>/dev/null); [ -n "$m" ] && [ -f "$VD/models/$m.conf" ] && echo "$VD/models/$m.conf"
}
props_re() { printf '%s\n' "$1" | sed 's/[][\\.^$*|/]/\\&/g'; }
props_repl() { printf '%s\n' "$1" | sed 's/[\\&|]/\\&/g'; }
props_apply() {
    pf=$R/system/build.prop; po=$SYS/props.orig
    [ -f "$pf" ] || return 0
    case $- in *f*) _pa_gf=1 ;; *) _pa_gf=0; set -f ;; esac
    img=$(sed -n 's/^ro\.vd\.image=//p' "$pf" | head -1)
    if [ -f "$po" ] && [ "$(sed -n 's/^#image=//p' "$po")" != "$img" ]; then rm -f "$po"; fi
    [ -f "$po" ] || printf '#image=%s\n' "$img" > "$po"
    mf=$(props_files | tr '\n' ' ')
    want=$(for f in $mf; do sed -n '/^# >>> android props/,/^# <<< android props/s/^#@prop //p' "$f"; done | grep -E '^[a-z][a-z0-9._-]*=[^ ]*$' |
           awk -F= '{ if (!($1 in v)) k[++n] = $1; v[$1] = $0 } END { for (i = 1; i <= n; i++) print v[k[i]] }')
    n=0
    # 1. keys applied earlier and no longer wanted: original line back (or removed when there was none)
    for k in $(grep -v '^#' "$po" | cut -d'|' -f1); do
        kr=$(props_re "$k")
        printf '%s\n' "$want" | grep -q "^$kr=" && continue
        orig=$(grep "^$kr|" "$po" | head -1 | cut -d'|' -f2-)
        if [ -n "$orig" ]; then
            or=$(props_repl "$orig")
            sed -i "s|^$kr=.*|$or|" "$pf"
        else
            sed -i "/^$kr=/d" "$pf"
        fi
        sed -i "/^$kr|/d" "$po"; n=$((n + 1)); t "props: $k restored${orig:+ ($orig)}"
    done
    # 2. wanted keys
    for kv in $want; do
        k=${kv%%=*}
        case "$k" in dalvik.vm.heap*|ro.sf.lcd_density|ro.config.max_starting_bg) t "props: $k is a host setting (memory.sh), ignored"; continue ;; esac
        kr=$(props_re "$k")
        grep -q "^$kr|" "$po" || printf '%s|%s\n' "$k" "$(grep "^$kr=" "$pf" | head -1)" >> "$po"
        grep -qxF -- "$kv" "$pf" && continue
        if grep -q "^$kr=" "$pf"; then
            kvr=$(props_repl "$kv")
            sed -i "s|^$kr=.*|$kvr|" "$pf"
        else
            printf '%s\n' "$kv" >> "$pf"
        fi
        n=$((n + 1)); t "props: $kv"
    done
    [ $n -gt 0 ] && chmod 644 "$pf"
    [ "$_pa_gf" = 0 ] && set +f
    return 0
}
