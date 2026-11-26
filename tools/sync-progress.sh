#!/bin/sh
# host/pw6/boot/lib/progress.sh 是进度画面（方块风格）的唯一来源：拷到 k49 / legacy 的宿主，并替换 cloud/static/bootstrap.sh
# 里 # BEGIN vd_progress … # END vd_progress 之间的那一份（安装器在 Android 装好之前就要用，只能内嵌）。改完 progress.sh 跑一次。
set -eu
cd "$(dirname "$0")/.."
L=host/pw6/boot/lib/progress.sh
for v in k49 legacy; do cp "$L" host/$v/boot/lib/progress.sh; done
for b in cloud/static/bootstrap.sh cloud/static/bootstrap-legacy.sh; do
python3 - "$L" "$b" <<'P'
import sys
lib = open(sys.argv[1]).read().rstrip('\n') + '\n'
p = sys.argv[2]; s = open(p).read()
a = s.index('# BEGIN vd_progress\n'); b = s.index('# END vd_progress\n') + len('# END vd_progress\n')
open(p, 'w').write(s[:a] + lib + s[b:])
P
sh -n "$b"
done
echo "progress.sh synced"
