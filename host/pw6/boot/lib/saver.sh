# 屏保挑选（VeraHome 的 ScreensaverActivity，docs/host-settings.md 第 10 节）。
# 选择存在 Android 存储里的一个小文件（桌面写，宿主在每次进屏保时读）：
#   $SYS/data/media/0/Screensaver/.vd-saver   （Android 里 /sdcard/Screensaver/.vd-saver）
#     mode=kindle   用 Kindle 自带的屏保图（默认）
#     mode=rotate   轮换用户的全部图片（veradroid/screensaver/ 和 Android 的 Screensaver/）
#     mode=one      只用一张：file=sd/<文件名>（Android 的 Screensaver/）或 file=vd/<文件名>（Kindle 的 veradroid/screensaver/）
# 没有这个文件 = 以前的做法（有用户图就轮换，没有就用 Kindle 自带的），老用户行为不变。
SAVER_SEL=$SYS/data/media/0/Screensaver/.vd-saver
# 读选择：设 smode（kindle / rotate / one / 空=旧做法）和 sone（一张图的宿主路径）
saver_choice() {
    smode=; sone=
    [ -f "$SAVER_SEL" ] && [ ! -L "$SAVER_SEL" ] || return 0
    smode=$(sed -n 's/^mode=\([a-z]*\)$/\1/p' "$SAVER_SEL" | tail -1)
    case "$smode" in kindle|rotate|one) ;; *) smode= ; return 0 ;; esac
    [ "$smode" = one ] || return 0
    sf=$(sed -n 's/^file=\(.*\)$/\1/p' "$SAVER_SEL" | tail -1)
    sname=${sf#*/}
    case "$sname" in ''|*/*|.*) smode=rotate; return 0 ;; esac          # 只认文件名，不认路径（.. 之类一律不要）
    case "$sf" in
        sd/*) sone="$SYS/data/media/0/Screensaver/$sname" ;;
        vd/*) sone="$VD/screensaver/$sname" ;;
    esac
    [ -n "$sone" ] && [ -f "$sone" ] && [ ! -L "$sone" ] || { sone=; smode=rotate; }   # 那张图被删了：退回轮换
    return 0
}
# 屏保图片的格式（docs/host-settings.md 第 10 节，fix/saver-formats）。以前只认扩展名 png / jpg / jpeg，别的格式（webp、bmp、gif、
# 手机拍的 heic）放进去不报错也不显示。现在：
#  - 两个文件夹里这些扩展名（大小写都行）都算屏保图；真正是什么格式看文件头，不看扩展名（改了扩展名的 webp 也认得出来）。
#  - 桌面的屏保页打开时把每张图转成适合这块屏的灰度 PNG（按面板大小缩放，Android 能解 jpg / png / webp / bmp / gif），
#    放在 Screensaver/.vd-cache/：sd-<文件名>.png（Android 的 Screensaver）、vd-<文件名>.png（Kindle 的 veradroid/screensaver），
#    旁边的 .src 记着原图的“字节数 修改时间”。进屏保时原图没变就画转好的那张（小、快，几十兆的照片也不用在休眠的那一刻解码）。
#  - 没转过（还没打开过屏保页）时直接画原图：fbink 认 PNG / JPEG / BMP / GIF；webp / heic 之类画不了，跳到下一张，日志里写原因。
SAVER_EXTS='png jpg jpeg jpe jfif bmp gif webp heic heif avif tif tiff'
SAVER_CACHE=$SYS/data/media/0/Screensaver/.vd-cache
saver_is_img() {   # $1 = 文件名：扩展名在 SAVER_EXTS 里（不分大小写），不以 . 开头
    case "${1##*/}" in .*|*/*) return 1 ;; *.*) ;; *) return 1 ;; esac
    se=$(printf '%s' "${1##*.}" | tr 'A-Z' 'a-z')
    case " $SAVER_EXTS " in *" $se "*) return 0 ;; esac
    return 1
}
saver_kind() {     # 文件头 -> png / jpeg / gif / bmp / webp / heic / avif / tiff，认不出来时为空（设 skind）
    skind=
    sh_=$(dd if="$1" bs=16 count=1 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
    case "$sh_" in
    89504e470d0a1a0a*) skind=png ;;
    ffd8ff*) skind=jpeg ;;
    474946383[79]61*) skind=gif ;;
    424d*) skind=bmp ;;
    52494646????????57454250*) skind=webp ;;
    ????????6674797061766966*|????????6674797061766973*) skind=avif ;;
    ????????66747970*) skind=heic ;;
    49492a00*|4d4d002a*) skind=tiff ;;
    esac
}
saver_cached() {   # 桌面转好的灰度 PNG：有、不是链接、.src 和原图的“字节数 修改时间”一样（设 scache）
    scache=
    case "$1" in
    "$SYS"/data/media/0/Screensaver/*) sc_=$SAVER_CACHE/sd-${1##*/}.png ;;
    "$VD"/screensaver/*) sc_=$SAVER_CACHE/vd-${1##*/}.png ;;
    *) return 1 ;;
    esac
    [ -f "$sc_" ] && [ ! -L "$sc_" ] && [ -s "$sc_" ] && [ -f "$sc_.src" ] || return 1
    [ "$(cat "$sc_.src" 2>/dev/null)" = "$(stat -c '%s %Y' "$1" 2>/dev/null)" ] || return 1
    scache=$sc_
}
saver_complete() { # 没传完 / 截断的 JPEG、PNG（fbink 照样画出半张，剩下黑的灰的）：最后 4 KB 里要有 FF D9 / IEND（桌面 SaverFormat.complete 同一规则）
    case "$skind" in
    jpeg) tail -c 4096 "$1" 2>/dev/null | od -An -v -tx1 2>/dev/null | tr '\n' ' ' | tr -s ' ' | grep -q 'ff d9' ;;
    png) tail -c 4096 "$1" 2>/dev/null | grep -q IEND ;;
    *) return 0 ;;
    esac
}
saver_resolve() {  # 进屏保时这张图真正要画的文件（设 sdraw；空 = 这张用不了，写日志说为什么）
    sdraw=
    saver_cached "$1" && { sdraw=$scache; return 0; }
    saver_kind "$1"
    case "$skind" in
    png|jpeg|gif|bmp)
        if [ "$(wc -c < "$1" | tr -d ' ')" -gt $SAVER_MAX1 ]; then t "screensaver: skipped $1 (bigger than 32 MB: too slow to decode while going to sleep)"
        elif saver_complete "$1"; then sdraw=$1; else t "screensaver: skipped $1 ($skind file is cut short)"; fi ;;
    '') t "screensaver: skipped $1 (not an image this Kindle can show)" ;;
    webp) t "screensaver: skipped $1 (webp: open 设置 → 屏保 once so the launcher converts it)" ;;
    *) t "screensaver: skipped $1 ($skind: this format cannot be shown, convert it to JPG or PNG)" ;;
    esac
}
# 给桌面看 veradroid/screensaver/ 里的图（Android 看不到 Kindle 的 veradroid 文件夹）：临时拷到 $X/saver/（tmpfs，Android 里 /dev/kfb/saver/），
# 另拷一张 Kindle 自带的屏保图当“Kindle 自带”的预览。页面关掉时 saver done 删掉。拷贝保留修改时间（cp -p：桌面转图时要记原图的修改时间）。
# 每张不超过 32 MB、合计不超过 96 MB（tmpfs 占的是内存）、最多 60 张；没拷的写进 .skipped（“文件名<TAB>字节数”），页面上说太大。
# 还写 .panel（屏保画在多大的屏上：fbink 看到的宽 高）和 .elsewhere（Kindle 盘上别的常见屏保文件夹里有几张图，那些地方 VeraDroid 不读）。
SAVER_MAX1=33554432
SAVER_MAXALL=100663296
saver_cmd() {
    case "$1" in
    list)
        rm -rf $X/saver $X/saver.tmp $X/ctl/saver.listed; mkdir -p $X/saver.tmp || return 0
        mem_kb=$(sed -n 's/^MemTotal:[[:space:]]*\([0-9][0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null | head -1)
        maxall=$SAVER_MAXALL
        [ -n "$mem_kb" ] && [ "$mem_kb" -le 600000 ] && maxall=25165824
        n=0; tot=0; : > $X/saver.tmp/.skipped; : > $X/saver.tmp/.cached
        for f in "$VD"/screensaver/*; do
            [ -f "$f" ] && [ ! -L "$f" ] || continue
            saver_is_img "$f" || continue
            case "${f##*/}" in default.png|default.PNG) continue ;; esac
            sz=$(wc -c < "$f" | tr -d ' ')
            if [ $n -ge 60 ]; then
                printf '%s\t%s\tcount\n' "${f##*/}" "$sz" >> $X/saver.tmp/.skipped; continue
            fi
            if [ "$sz" -gt $SAVER_MAX1 ]; then
                printf '%s\t%s\tsize\n' "${f##*/}" "$sz" >> $X/saver.tmp/.skipped; continue
            fi
            if saver_cached "$f"; then
                printf '%s\n' "${f##*/}" >> $X/saver.tmp/.cached
                n=$((n + 1)); continue
            fi
            if [ $((tot + sz)) -gt $maxall ]; then
                printf '%s\t%s\ttotal\n' "${f##*/}" "$sz" >> $X/saver.tmp/.skipped; continue
            fi
            cp -p "$f" $X/saver.tmp/ 2>/dev/null && { n=$((n + 1)); tot=$((tot + sz)); }
        done
        for f in /usr/share/blanket/screensaver/bg_ss*.png; do [ -f "$f" ] && { cp "$f" $X/saver.tmp/.kindle-preview.png 2>/dev/null; break; }; done
        pw=; ph=
        if [ -x "$FBINK" ]; then
            fe=$("$FBINK" -e 2>/dev/null | tr ';' '\n')
            pw=$(printf '%s\n' "$fe" | sed -n 's/^viewWidth=\([0-9][0-9]*\)$/\1/p' | head -1)
            ph=$(printf '%s\n' "$fe" | sed -n 's/^viewHeight=\([0-9][0-9]*\)$/\1/p' | head -1)
        fi
        [ -n "$pw" ] && [ -n "$ph" ] && echo "$pw $ph" > $X/saver.tmp/.panel
        : > $X/saver.tmp/.elsewhere
        for d in /mnt/us/screensaver /mnt/us/linkss/screensavers /mnt/us/documents/screensaver "$VD/Screensaver" "$VD/screensavers"; do
            [ -d "$d" ] || continue
            [ "$d" -ef "$VD/screensaver" ] 2>/dev/null && continue      # vfat / 不分大小写的盘上 Screensaver 就是 screensaver
            c=0; for f in "$d"/*; do [ -f "$f" ] && saver_is_img "$f" && c=$((c + 1)); done
            [ $c -gt 0 ] && printf '%s\t%s\n' "${d#/mnt/us/}" "$c" >> $X/saver.tmp/.elsewhere
        done
        chmod 755 $X/saver.tmp; chmod 644 $X/saver.tmp/* $X/saver.tmp/.[a-z]* 2>/dev/null
        mv $X/saver.tmp $X/saver; : > $X/ctl/saver.listed && chmod 644 $X/ctl/saver.listed 2>/dev/null
        t "screensaver: exported $n image(s) from veradroid/screensaver for the picker$([ -s $X/saver/.skipped ] && echo ", $(wc -l < $X/saver/.skipped | tr -d ' ') too big")" ;;
    done) rm -rf $X/saver $X/saver.tmp $X/ctl/saver.listed ;;
    esac
}

# Android 的 内部存储/Screensaver（/sdcard/Screensaver）：用户放屏保图片的另一个地方。开机就建好，里面放一份说明
# （和 veradroid/screensaver/ 的那份对应）。以前要等桌面第一次打开屏保页才由桌面自己建，是桌面 uid 的 0700
# （测试机上：10012 drwx------），传书网页以外的应用、文件管理进不去。现在和 Books / Download 一样：属主 media_rw（1023，
# sdcard 守护进程用它）、777。里面已有的文件（桌面写的 .vd-saver、导入的图）不动。
SAVER_NOTE_SD="把屏保图片（jpg / png / webp / bmp / gif，大小写都行）放在这个文件夹里。
Kindle 休眠时，VeraDroid 显示这里的图片。在 设置 → 屏保 里可以选“轮换全部图片”或只用其中一张。
竖图、和屏幕差不多大最好。webp 图要先打开一次 设置 → 屏保（桌面会把它转好）；手机拍的 HEIC 照片请先转成 JPG。

也可以用 USB 连电脑，放进 Kindle 盘的 veradroid/screensaver/ 文件夹；传书网页的“文件”里也能上传到这里。"
saver_dir_fix() {
    sdd=$SYS/data/media/0
    [ -d "$sdd" ] || return 0
    [ -L "$sdd/Screensaver" ] && return 0
    { [ -e "$sdd/Screensaver" ] && [ ! -d "$sdd/Screensaver" ]; } && return 0     # 同名的文件：用户的，不动
    mkdir -p "$sdd/Screensaver" 2>/dev/null || return 0
    chown 1023:1023 "$sdd/Screensaver" 2>/dev/null || true; chmod 777 "$sdd/Screensaver" 2>/dev/null || true
    sdn="$sdd/Screensaver/把屏保图片放这里.txt"
    [ -L "$sdn" ] && rm -f "$sdn" 2>/dev/null
    [ -L "$sdn" ] && return 0
    if [ "$(cat "$sdn" 2>/dev/null)" != "$SAVER_NOTE_SD" ]; then
        { printf '%s\n' "$SAVER_NOTE_SD" > "$sdn" && chmod 644 "$sdn" && chown 1023:1023 "$sdn"; } 2>/dev/null || true
    fi
    return 0
}
